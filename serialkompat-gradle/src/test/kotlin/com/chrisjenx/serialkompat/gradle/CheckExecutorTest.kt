package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.AcceptedBreak
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Element
import com.chrisjenx.serialkompat.core.Rules
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotFormat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure check orchestration behind `serialkompatCheck`: parse the baseline and
 * current snapshot texts, run the engine under the configured policy, render the
 * report, and decide whether to fail. Testable without Gradle.
 */
class CheckExecutorTest {
    private fun snapshot(vararg elements: Element) =
        SnapshotFormat.serialize(
            Snapshot(listOf(Contract("com.example.Order", ContractKind.CLASS, elements = elements.toList()))),
        )

    private fun execute(
        baseline: String,
        current: String,
        failOnBreaking: Boolean = true,
    ) = CheckExecutor.execute(
        baselineText = baseline,
        currentText = current,
        direction = CompatibilityDirection.FULL,
        include = listOf(""),
        exclude = emptyList(),
        failOnBreaking = failOnBreaking,
    )

    @Test
    fun `an empty baseline with a non-empty current fails closed`() {
        // A baseline that produced zero contracts must not read as "everything is new -> safe"
        // (that would silently mask removals); the gate fails closed by default (#78).
        val outcome =
            CheckExecutor.execute(
                baselineText = SnapshotFormat.serialize(Snapshot(emptyList())),
                currentText = snapshot(Element("id", "kotlin.String")),
                direction = CompatibilityDirection.FULL,
                include = listOf(""),
                exclude = emptyList(),
                failOnBreaking = true,
            )
        assertTrue(outcome.failed, "an empty baseline must fail closed, not read as all-added-safe")
    }

    @Test
    fun `failOnEmptyBaseline=false lets an empty baseline pass (first-time adoption)`() {
        val outcome =
            CheckExecutor.execute(
                baselineText = SnapshotFormat.serialize(Snapshot(emptyList())),
                currentText = snapshot(Element("id", "kotlin.String")),
                direction = CompatibilityDirection.FULL,
                include = listOf(""),
                exclude = emptyList(),
                failOnBreaking = true,
                failOnEmptyBaseline = false,
            )
        assertFalse(outcome.failed, "a repo adding its first serializable types may pass when the flag is off")
    }

    @Test
    fun `identical schemas pass`() {
        val text = snapshot(Element("id", "kotlin.String"))
        val outcome = execute(text, text)
        assertFalse(outcome.failed)
    }

    @Test
    fun `removing a required field fails the check`() {
        val outcome = execute(snapshot(Element("id", "kotlin.String")), snapshot())
        assertTrue(outcome.failed)
        assertTrue(outcome.console.contains("BREAK"))
    }

    @Test
    fun `a breaking change does not fail when failOnBreaking is false`() {
        val outcome = execute(snapshot(Element("id", "kotlin.String")), snapshot(), failOnBreaking = false)
        assertFalse(outcome.failed)
        // The finding is still reported for visibility.
        assertTrue(outcome.console.contains("BREAK"))
    }

    @Test
    fun `the outcome carries a machine-readable json report`() {
        val outcome = execute(snapshot(Element("id", "kotlin.String")), snapshot())
        assertTrue(outcome.json.contains("\"breaking\""))
    }

    @Test
    fun `an accepted break is acknowledged and does not fail the check`() {
        val outcome =
            CheckExecutor.execute(
                baselineText = snapshot(Element("id", "kotlin.String")),
                currentText = snapshot(), // removed required 'id' -> would break
                direction = CompatibilityDirection.FULL,
                include = listOf(""),
                exclude = emptyList(),
                failOnBreaking = true,
                accepted = listOf(AcceptedBreak("com.example.Order", Rules.PROPERTY_REMOVED)),
            )
        assertFalse(outcome.failed, "the sanctioned break must not fail the gate")
        assertTrue(outcome.report.acknowledged.isNotEmpty())
        assertTrue(outcome.report.active.isEmpty())
    }

    private fun named(
        serialName: String,
        vararg elements: Element,
    ) = SnapshotFormat.serialize(
        Snapshot(listOf(Contract(serialName, ContractKind.CLASS, elements = elements.toList()))),
    )

    // --- transitive history check (issue #88) ---

    private fun orderSnapshot(vararg elements: Element) =
        Snapshot(listOf(Contract("com.example.Order", ContractKind.CLASS, elements = elements.toList())))

    @Test
    fun `history check passes when the current schema is compatible with every published version`() {
        val v1 = orderSnapshot(Element("id", "kotlin.String"))
        // Adding an optional field is backward-safe (a new reader reads old data): the transitive
        // check must pass against the published version under BACKWARD.
        val current = snapshot(Element("id", "kotlin.String"), Element("note", "kotlin.String", optional = true))
        val outcome =
            CheckExecutor.executeHistory(
                currentText = current,
                history = listOf(v1),
                direction = CompatibilityDirection.BACKWARD,
                include = listOf(""),
                exclude = emptyList(),
                failOnBreaking = true,
            )
        assertFalse(outcome.failed, "adding an optional field is backward-compatible with the published version")
    }

    @Test
    fun `history check catches a break against an OLD version even when the latest looks fine`() {
        // v1 had 'note'; v2 dropped it (already released that way); current also lacks it.
        // Pairwise vs v2 (latest) is clean, but transitively the current schema still can't read
        // data written under v1 -> the break must surface.
        val v1 = orderSnapshot(Element("id", "kotlin.String"), Element("note", "kotlin.String"))
        val v2 = orderSnapshot(Element("id", "kotlin.String"))
        val current = snapshot(Element("id", "kotlin.String")) // == v2

        val outcome =
            CheckExecutor.executeHistory(
                currentText = current,
                history = listOf(v1, v2),
                direction = CompatibilityDirection.FORWARD,
                include = listOf(""),
                exclude = emptyList(),
                failOnBreaking = true,
            )
        assertTrue(outcome.failed, "a break vs an old published version must fail even if the latest is clean")
    }

    @Test
    fun `history check with no published versions is a no-op pass`() {
        val outcome =
            CheckExecutor.executeHistory(
                currentText = snapshot(Element("id", "kotlin.String")),
                history = emptyList(),
                direction = CompatibilityDirection.FULL,
                include = listOf(""),
                exclude = emptyList(),
                failOnBreaking = true,
            )
        assertFalse(outcome.failed, "nothing published yet -> nothing to check -> pass")
    }

    @Test
    fun `a declared rename is diffed as a move, not a spurious break`() {
        val baseline = named("com.example.Old", Element("id", "kotlin.String"))
        val current = named("com.example.New", Element("id", "kotlin.String"))

        // Without a declared rename this is remove(Old) + add(New) -> a breaking contract removal.
        val withoutRename =
            CheckExecutor.execute(baseline, current, CompatibilityDirection.FULL, listOf(""), emptyList(), true)
        assertTrue(withoutRename.failed)

        // Declaring the move makes it a plain (wire-neutral) contract move -> no break.
        val withRename =
            CheckExecutor.execute(
                baseline,
                current,
                CompatibilityDirection.FULL,
                listOf(""),
                emptyList(),
                failOnBreaking = true,
                renames = mapOf("com.example.Old" to "com.example.New"),
            )
        assertFalse(withRename.failed, "a declared rename must not be a break")
    }
}
