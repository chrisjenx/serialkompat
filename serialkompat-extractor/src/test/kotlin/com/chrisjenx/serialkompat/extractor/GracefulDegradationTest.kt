package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A gate must never crash and never silently drop a type it can't analyze
 * (design §10). Unknown/unresolvable descriptors become explicit OPAQUE coverage
 * gaps rather than exceptions or omissions.
 */
@OptIn(ExperimentalSerializationApi::class)
class GracefulDegradationTest {
    private class Raw

    @Serializable
    @SerialName("HasContextual")
    private data class HasContextual(
        @Contextual val raw: Raw,
        val id: String,
    )

    @Test
    fun `a primitive root becomes an opaque contract instead of being dropped`() {
        val snapshot = DescriptorSnapshotExtractor.extract(listOf(serializer<String>().descriptor))
        assertEquals(1, snapshot.contracts.size)
        assertEquals(ContractKind.OPAQUE, snapshot.contracts.single().kind)
    }

    @Test
    fun `a contextual field does not crash extraction`() {
        // The @Contextual element's serializer is unresolved here; extraction must
        // still capture the owning class and its analyzable elements without throwing.
        val snapshot = DescriptorSnapshotExtractor.extract(listOf(serializer<HasContextual>().descriptor))
        val owner = snapshot.contracts.single { it.serialName == "HasContextual" }
        assertEquals(ContractKind.CLASS, owner.kind)
        assertTrue(owner.elements.any { it.name == "id" })
        assertTrue(owner.elements.any { it.name == "raw" })
    }
}
