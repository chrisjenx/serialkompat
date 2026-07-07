package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.extractor.scanfixtures.Outer
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedBox
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedEvent
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedMarker
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedOrder
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedStatus
import com.chrisjenx.serialkompat.extractor.scanfixtures.scanFixturesRoot
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Discovery producer (issue #55): `@Serializable` is RUNTIME-retained, so annotated
 * classes carry it in the class file's `RuntimeVisibleAnnotations`. The scanner reads
 * that attribute directly — no classloading — from *real* compiler output: these
 * tests scan the compiled fixtures in this module's own test-classes directory.
 */
class SerializableClassScannerTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun fixturesRoot(): File =
        scanFixturesRoot(Files.createTempDirectory("skompat-scan").toFile().also(tempDirs::add))

    @Test
    fun `detects a class-level @Serializable data class`() {
        val result = SerializableClassScanner.scan(listOf(fixturesRoot()))
        assertContains(result.typeNames, ScannedOrder::class.java.name)
    }

    @Test
    fun `ignores classes without a class-level @Serializable`() {
        val result = SerializableClassScanner.scan(listOf(fixturesRoot()))
        assertFalse(result.typeNames.any { it.endsWith("NotSerializable") })
        // Generated helpers ($serializer, Companion) are not annotated either.
        assertFalse(result.typeNames.any { it.contains("serializer") })
        assertFalse(result.typeNames.any { it.endsWith("Companion") })
    }

    @Test
    fun `detects nested classes by binary name`() {
        val result = SerializableClassScanner.scan(listOf(fixturesRoot()))
        // Binary name keeps the `$` separator: ...scanfixtures.Outer$Inner.
        assertContains(result.typeNames, Outer.Inner::class.java.name)
    }

    @Test
    fun `detects objects, enums, and sealed interfaces`() {
        val result = SerializableClassScanner.scan(listOf(fixturesRoot()))
        assertContains(result.typeNames, ScannedMarker::class.java.name)
        assertContains(result.typeNames, ScannedStatus::class.java.name)
        assertContains(result.typeNames, ScannedEvent::class.java.name)
        assertContains(result.typeNames, ScannedEvent.Created::class.java.name)
    }

    @Test
    fun `results are sorted and deduplicated across roots`() {
        val root = fixturesRoot()
        val result = SerializableClassScanner.scan(listOf(root, root))
        assertEquals(result.typeNames.distinct().sorted(), result.typeNames)
        assertEquals(1, result.typeNames.count { it == ScannedOrder::class.java.name })
    }

    @Test
    fun `skips generic classes as roots, recording them`() {
        val result = SerializableClassScanner.scan(listOf(fixturesRoot()))
        // A generic class has no standalone wire shape (`serializer()` needs concrete
        // type args); its concrete shapes are covered at use sites. Skipped — loudly.
        assertFalse(result.typeNames.any { it.endsWith("ScannedBox") })
        assertContains(result.skippedGenerics, ScannedBox::class.java.name)
    }

    @Test
    fun `a property-level @Serializable(with) does not mark the class`() {
        val result = SerializableClassScanner.scan(listOf(fixturesRoot()))
        assertFalse(result.typeNames.any { it.endsWith("PropertyLevelOnly") })
        assertFalse(result.skippedGenerics.any { it.endsWith("PropertyLevelOnly") })
    }

    @Test
    fun `a corrupt class file is reported unreadable, not thrown`() {
        val root = Files.createTempDirectory("skompat-corrupt").toFile().also(tempDirs::add)
        File(root, "com/example/Broken.class").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))
        }
        val result = SerializableClassScanner.scan(listOf(root))
        assertEquals(listOf("com/example/Broken.class"), result.unreadable)
        assertEquals(emptyList(), result.typeNames)
    }

    @Test
    fun `a truncated class file with a valid magic is unreadable, not thrown`() {
        val root = Files.createTempDirectory("skompat-truncated").toFile().also(tempDirs::add)
        File(root, "com/example/Truncated.class").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0x00))
        }
        val result = SerializableClassScanner.scan(listOf(root))
        assertEquals(listOf("com/example/Truncated.class"), result.unreadable)
    }

    @Test
    fun `an empty or missing root contributes nothing`() {
        val empty = Files.createTempDirectory("skompat-empty").toFile().also(tempDirs::add)
        val missing = File(empty, "does-not-exist")
        val result = SerializableClassScanner.scan(listOf(empty, missing))
        assertTrue(result.typeNames.isEmpty())
        assertTrue(result.unreadable.isEmpty())
        assertTrue(result.skippedGenerics.isEmpty())
    }
}
