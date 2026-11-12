package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.CompatibilityEngine
import com.chrisjenx.serialkompat.core.Scope
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.core.TransitiveCompatibility
import com.chrisjenx.serialkompat.core.applyScope
import com.chrisjenx.serialkompat.extractor.scopefixtures.V1
import com.chrisjenx.serialkompat.extractor.scopefixtures.V2
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #249 through the real extractor: each root's Kotlin class name is recorded, so `exclude` by
 * package drops a v2 type whatever its `@SerialName`, including a nested sealed class's subtypes.
 */
class ScopeByClassNameExtractionTest {
    private val tempDir: File = Files.createTempDirectory("skompat-scope").toFile()
    private val v2Package = "com.chrisjenx.serialkompat.extractor.scopefixtures.V2"
    private val excludeV2 = Scope(exclude = listOf(v2Package))

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    private fun extract(vararg types: Class<*>): Snapshot {
        val out = File(tempDir, "${types.first().simpleName}.snapshot")
        SchemaExtractionMain.run(typeNames = types.map { it.name }, jsonInstanceFqn = null, output = out)
        return SnapshotFormat.parse(out.readText())
    }

    @Test
    fun `each root records its Kotlin class name`() {
        val snapshot = extract(V2.PaymentV2::class.java, V2.Method.Card::class.java, V1.Refund::class.java)
        val byName = snapshot.contracts.filter { it.base == null }.associate { it.serialName to it.className }
        assertEquals("$v2Package.PaymentV2", byName["PaymentV2"])
        assertEquals("$v2Package.Method.Card", byName["card"])
        assertEquals("com.chrisjenx.serialkompat.extractor.scopefixtures.V1.Refund", byName["Refund"])
    }

    @Test
    fun `a v2 type with a custom serial name no longer leaks through exclude`() {
        val recorded = extract(V2.PaymentV2::class.java, V1.Refund::class.java)
        val current = extract(V2.PaymentV2Next::class.java, V1.Refund::class.java)
        assertTrue(CompatibilityEngine.check(recorded, current).findings.isNotEmpty())
        assertEquals(emptyList(), CompatibilityEngine.check(recorded, current, scope = excludeV2).findings)
        assertEquals(
            emptyList(),
            TransitiveCompatibility.checkAgainstHistory(current, listOf(recorded), scope = excludeV2).findings,
        )
    }

    @Test
    fun `a root's class name is never stamped on an unrelated subtype sharing its serial name`() {
        // Review of #254: v2 `Created` and v1 `OrderEvent.Created` both use @SerialName("created").
        val snapshot =
            extract(
                com.chrisjenx.serialkompat.extractor.scopefixtures.V2Shared.Created::class.java,
                V1.OrderEvent::class.java,
            )
        val v1Subtype = snapshot.contracts.single { it.serialName == "created" && it.base != null }
        assertEquals(null, v1Subtype.className)
        val excludeShared = Scope(exclude = listOf("com.chrisjenx.serialkompat.extractor.scopefixtures.V2Shared"))
        assertTrue(
            v1Subtype.qualifiedName in
                snapshot
                    .applyScope(excludeShared)
                    .inScope.contracts
                    .map { it.qualifiedName },
        )
    }

    @Test
    fun `a nested sealed class and its subtypes are excluded by package`() {
        val snapshot = extract(V2.Method::class.java, V2.Method.Card::class.java, V1.Refund::class.java)
        val coverage = snapshot.applyScope(excludeV2)
        assertEquals(
            listOf("Refund"),
            coverage.inScope.contracts.map { it.qualifiedName },
            coverage.excluded.toString(),
        )
    }
}
