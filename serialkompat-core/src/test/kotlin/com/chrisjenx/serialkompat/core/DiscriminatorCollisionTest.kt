package com.chrisjenx.serialkompat.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sealed/polymorphic subtype that declares a property whose JSON key equals the
 * base's class discriminator is **unserializable**: kotlinx-serialization refuses
 * to encode it (`JsonEncodingException: … conflicts with JSON class discriminator`).
 * Like a coverage gap, this is a defect of a single snapshot the gate should catch
 * statically — before the first real encode blows up (#132). It is surfaced on
 * every diff (regardless of change), and it never applies when the discriminator
 * is not emitted (`classDiscriminatorMode = NONE`).
 */
class DiscriminatorCollisionTest {
    /** `Msg` (disc="name") with a subtype `hello` that also declares a `name` property. */
    private fun collidingSnapshot(
        config: SnapshotConfig = SnapshotConfig(),
        discriminator: String = "name",
    ) = Snapshot(
        listOf(
            Contract(
                "Msg",
                ContractKind.SEALED,
                discriminator = discriminator,
                subtypes = listOf(Subtype("hello", "hello")),
            ),
            Contract(
                "hello",
                ContractKind.CLASS,
                elements =
                    listOf(
                        Element("name", "kotlin.String"),
                        Element("greeting", "kotlin.String"),
                    ),
            ),
        ),
        config,
    )

    @Test
    fun `a subtype property colliding with the discriminator is surfaced as a collision`() {
        val snapshot = collidingSnapshot()
        assertTrue(
            Change.DiscriminatorCollision("Msg", "name", "hello") in SnapshotDiffer.diff(snapshot, snapshot),
            "expected a DiscriminatorCollision for the unserializable model",
        )
    }

    @Test
    fun `a discriminator collision is classified BREAK in both directions`() {
        val findings = Classifier().classify(SnapshotDiffer.diff(collidingSnapshot(), collidingSnapshot()))
        val collision = findings.filter { it.rule == Rules.DISCRIMINATOR_COLLISION }
        assertEquals(
            setOf(CompatibilityDirection.BACKWARD, CompatibilityDirection.FORWARD),
            collision.map { it.direction }.toSet(),
            "an unserializable model breaks both directions; got $findings",
        )
        assertTrue(collision.all { it.severity == Severity.BREAK }, "got $collision")
    }

    @Test
    fun `no collision when no subtype property matches the discriminator`() {
        val snapshot =
            Snapshot(
                listOf(
                    Contract(
                        "Msg",
                        ContractKind.SEALED,
                        discriminator = "name",
                        subtypes = listOf(Subtype("hello", "hello")),
                    ),
                    Contract("hello", ContractKind.CLASS, elements = listOf(Element("greeting", "kotlin.String"))),
                ),
            )
        assertTrue(
            SnapshotDiffer.diff(snapshot, snapshot).none { it is Change.DiscriminatorCollision },
            "a subtype without a colliding property is serializable — no collision",
        )
    }

    @Test
    fun `classDiscriminatorMode NONE suppresses the collision — no discriminator is emitted`() {
        val snapshot = collidingSnapshot(config = SnapshotConfig(classDiscriminatorMode = "NONE"))
        assertTrue(
            SnapshotDiffer.diff(snapshot, snapshot).none { it is Change.DiscriminatorCollision },
            "with no discriminator on the wire there is nothing to collide with",
        )
    }
}
