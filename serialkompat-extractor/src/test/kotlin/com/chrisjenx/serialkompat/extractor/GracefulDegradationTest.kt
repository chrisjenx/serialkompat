package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

    /** A serializer whose static initializer throws — touching it raises ExceptionInInitializerError. */
    private class Broken

    private object BrokenSerializer : KSerializer<Broken> {
        init {
            // Always fails; written as a check so the compiler doesn't flag the rest as unreachable.
            check(Broken::class.java.name.isEmpty()) { "static init failed" }
        }

        override val descriptor: SerialDescriptor = serializer<String>().descriptor

        override fun serialize(
            encoder: Encoder,
            value: Broken,
        ): Unit = error("unused")

        override fun deserialize(decoder: Decoder): Broken = error("unused")
    }

    @Serializable
    @SerialName("HasBrokenField")
    private data class HasBrokenField(
        @Serializable(with = BrokenSerializer::class) val bad: Broken,
    )

    @Serializable
    @SerialName("Healthy")
    private data class Healthy(
        val id: String,
    )

    @Test
    fun `a field whose serializer fails static init degrades to opaque, not an aborted extraction`() {
        val snapshot =
            DescriptorSnapshotExtractor.extract(
                listOf(serializer<HasBrokenField>().descriptor, serializer<Healthy>().descriptor),
            )
        assertEquals(ContractKind.OPAQUE, snapshot.contracts.single { it.serialName == "HasBrokenField" }.kind)
        assertEquals(ContractKind.CLASS, snapshot.contracts.single { it.serialName == "Healthy" }.kind)
    }

    /** A descriptor whose every accessor fails the way a class missing at runtime does. */
    @OptIn(SealedSerializationApi::class)
    private object MissingClassDescriptor : SerialDescriptor {
        override val serialName: String get() = throw NoClassDefFoundError("com/example/Gone")
        override val kind: SerialKind get() = StructureKind.CLASS
        override val elementsCount: Int get() = throw NoClassDefFoundError("com/example/Gone")

        override fun getElementName(index: Int): String = throw NoClassDefFoundError("com/example/Gone")

        override fun getElementIndex(name: String): Int = throw NoClassDefFoundError("com/example/Gone")

        override fun getElementAnnotations(index: Int): List<Annotation> = emptyList()

        override fun getElementDescriptor(index: Int): SerialDescriptor = this

        override fun isElementOptional(index: Int): Boolean = false
    }

    @Test
    fun `a descriptor whose serial name throws a linkage error degrades to opaque`() {
        val snapshot =
            DescriptorSnapshotExtractor.extract(listOf(MissingClassDescriptor, serializer<Healthy>().descriptor))
        assertEquals(ContractKind.CLASS, snapshot.contracts.single { it.serialName == "Healthy" }.kind)
        val gap = snapshot.contracts.single { it.serialName != "Healthy" }
        assertEquals(ContractKind.OPAQUE, gap.kind)
    }
}
