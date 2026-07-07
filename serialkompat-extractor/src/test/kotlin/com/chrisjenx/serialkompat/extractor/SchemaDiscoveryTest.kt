package com.chrisjenx.serialkompat.extractor

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When no types are configured, the extractor discovers them from a classpath
 * resource (`META-INF/serialkompat/serializable-types.txt`). The contract is
 * producer-agnostic — the manifest may be authored by hand or emitted by a
 * build-time discovery step (the extractor's class-dir scan, #55, is the
 * automated producer; KSP is not used, #22) — so this test fabricates the
 * resource directly rather than depending on any producer.
 */
class SchemaDiscoveryTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun loaderWith(vararg lines: String): ClassLoader {
        val dir = Files.createTempDirectory("skompat-discovery").toFile().also(tempDirs::add)
        File(dir, "META-INF/serialkompat/serializable-types.txt").apply {
            parentFile.mkdirs()
            writeText(lines.joinToString("\n"))
        }
        return URLClassLoader(arrayOf(dir.toURI().toURL()), null)
    }

    @Test
    fun `discovers type names from the classpath resource, ignoring blanks and comments`() {
        val loader = loaderWith("com.example.B", "# a comment", "", "  com.example.A  ")
        assertEquals(listOf("com.example.A", "com.example.B"), SchemaExtractionMain.discoverTypeNames(loader))
    }

    @Test
    fun `de-duplicates across multiple resources`() {
        // A single resource with a repeat still de-duplicates.
        val loader = loaderWith("com.example.A", "com.example.A", "com.example.B")
        assertEquals(listOf("com.example.A", "com.example.B"), SchemaExtractionMain.discoverTypeNames(loader))
    }

    @Test
    fun `no resource yields an empty list`() {
        assertEquals(emptyList(), SchemaExtractionMain.discoverTypeNames(URLClassLoader(arrayOf(), null)))
    }
}
