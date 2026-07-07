package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.extractor.scanfixtures.Outer
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
}
