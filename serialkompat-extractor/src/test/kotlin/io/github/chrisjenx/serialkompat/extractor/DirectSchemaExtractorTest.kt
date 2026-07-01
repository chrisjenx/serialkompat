package io.github.chrisjenx.serialkompat.extractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectSchemaExtractorTest {
    @Serializable
    private data class Sample(
        val id: String,
        val note: String = "",
    )

    @Test
    fun `reads element names in declaration order`() {
        val descriptor = serializer<Sample>().descriptor
        assertEquals(listOf("id", "note"), DirectSchemaExtractor.elementNames(descriptor))
    }

    @Test
    fun `descriptor exposes wire optionality from defaults`() {
        val descriptor = serializer<Sample>().descriptor
        // 'note' has a default => optional on the wire; 'id' does not.
        assertFalse(descriptor.isElementOptional(0))
        assertTrue(descriptor.isElementOptional(1))
    }
}
