# `@Serializable` Class-Dir Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The extractor (and its CLI) discovers `@Serializable` types by scanning compiled class directories with a dependency-free class-file parser, feeding the existing resolution loop (issue #55, re-scoped — spec: `docs/superpowers/specs/2026-07-06-serializable-classdir-discovery-design.md`).

**Architecture:** A new `internal object SerializableClassScanner` in `serialkompat-extractor` parses `*.class` files (constant pool → class-level `RuntimeVisibleAnnotations` → match `Lkotlinx/serialization/Serializable;`; `Signature` attribute detects generics). `SchemaExtractionMain.run` gains a `scanDirs` parameter whose results union with the classpath manifest when no explicit `types` are set; the CLI gains `--scan-classes`. No other module changes.

**Tech Stack:** Kotlin 2.4.0 / JVM 17, kotlinx-serialization 1.11.0, kotlin.test + JUnit 5. No new dependencies — the parser is hand-rolled.

## Global Constraints

- **Test-first, always** (RED → GREEN → REFACTOR); no production code without a driving failing test.
- The extractor **never throws** on a model/input it can't analyse — degrade to OPAQUE; unanalysable ≠ safe.
- **No KSP, ever** (maintainer decision #22). This feature is a class-file scan, not source processing.
- `serialkompat-extractor` uses `explicitApi()`; public declarations get KDoc; public API changes require `./gradlew apiDump` and committing the updated `serialkompat-extractor/api/serialkompat-extractor.api`.
- ktlint via Spotless: keep lines **≤ 115 chars** (Spotless flags at 120 and misreports the line number). Run `./gradlew spotlessApply` before every commit.
- JVM target 17; do not add toolchains that download JDKs.
- Git: stage **explicit paths only** (never `git add -A`); never commit `.superpowers/` or `.claude/settings.local.json`. Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Work on branch `feat/55-classdir-discovery` (already created; contains the spec commit).
- Rules matrix is untouched — this changes only *which types enter the snapshot* — so no round-trip oracle tests and no `docs/rules.md` change.

---

### Task 1: `SerializableClassScanner` — class-level `@Serializable` detection

**Files:**
- Create: `serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/scanfixtures/ScannerFixtures.kt`
- Create: `serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScannerTest.kt`
- Create: `serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScanner.kt`

**Interfaces:**
- Consumes: nothing (self-contained).
- Produces: `internal object SerializableClassScanner` with `fun scan(roots: List<File>): ScanResult` and `internal data class ScanResult(val typeNames: List<String>)` (Task 2 extends `ScanResult`; Task 3 calls `scan`). Also the test helper `scanFixturesRoot(tempRoot: File): File` in package `com.chrisjenx.serialkompat.extractor.scanfixtures` (used by Tasks 2–4 tests).

- [ ] **Step 1: Create the fixtures file** (compiled by the real serialization plugin — the tests scan real compiler output)

`serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/scanfixtures/ScannerFixtures.kt`:

```kotlin
package com.chrisjenx.serialkompat.extractor.scanfixtures

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ScannedOrder(val id: String)

class NotSerializable(val id: String)

class Outer {
    @Serializable
    data class Inner(val id: String)
}

@Serializable
object ScannedMarker

@Serializable
enum class ScannedStatus { NEW, DONE }

@Serializable
sealed interface ScannedEvent {
    @Serializable
    data class Created(val id: String) : ScannedEvent
}

/**
 * Copies this package's compiled class files into [tempRoot] so a scan sees a
 * deterministic set: exactly the fixtures declared in this package.
 */
fun scanFixturesRoot(tempRoot: File): File {
    val classesRoot = File(ScannedOrder::class.java.protectionDomain.codeSource.location.toURI())
    val pkg = "com/chrisjenx/serialkompat/extractor/scanfixtures"
    check(File(classesRoot, pkg).isDirectory) { "expected compiled fixtures under $classesRoot" }
    File(classesRoot, pkg).copyRecursively(File(tempRoot, pkg))
    return tempRoot
}
```

- [ ] **Step 2: Write the failing tests**

`serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScannerTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.SerializableClassScannerTest"`
Expected: COMPILATION FAILURE — `SerializableClassScanner` is unresolved. (Failing for the right reason: the unit doesn't exist.)

- [ ] **Step 4: Write the scanner**

`serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScanner.kt`:

```kotlin
package com.chrisjenx.serialkompat.extractor

import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Discovers `@Serializable` types by parsing compiled class files directly — no
 * classloading, no dependencies (design §4, issue #55). `@Serializable` has RUNTIME
 * retention, so an annotated class carries `Lkotlinx/serialization/Serializable;` in
 * its class-level `RuntimeVisibleAnnotations` attribute. Only the *class-level*
 * attribute is matched: a property-level `@Serializable(with = …)` puts the same
 * string in the constant pool but must not mark the class.
 */
internal object SerializableClassScanner {
    private const val CLASS_MAGIC = -0x35014542 // 0xCAFEBABE
    private const val SERIALIZABLE = "Lkotlinx/serialization/Serializable;"

    internal data class ScanResult(
        /** Binary names (`com.foo.Bar$Baz`) of classes with a class-level `@Serializable`. */
        val typeNames: List<String>,
    )

    /** Scans every `*.class` under each of [roots]; results are sorted + deduped. */
    fun scan(roots: List<File>): ScanResult {
        val typeNames = mutableListOf<String>()
        for (root in roots) {
            val classFiles =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .sortedBy(File::getPath)
            for (file in classFiles) {
                val parsed = runCatching { parse(file.readBytes()) }.getOrNull() ?: continue
                if (parsed.serializable) typeNames += parsed.binaryName
            }
        }
        return ScanResult(typeNames.distinct().sorted())
    }

    private class ParsedClass(val binaryName: String, val serializable: Boolean)

    private fun parse(bytes: ByteArray): ParsedClass {
        val input = DataInputStream(bytes.inputStream())
        if (input.readInt() != CLASS_MAGIC) throw IOException("not a class file")
        input.skipNBytes(4) // minor + major version
        val utf8 = HashMap<Int, String>()
        val classNameIndex = HashMap<Int, Int>()
        val constantPoolCount = input.readUnsignedShort()
        var slot = 1
        while (slot < constantPoolCount) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[slot] = input.readUTF()
                7 -> classNameIndex[slot] = input.readUnsignedShort() // Class → name_index
                8, 16, 19, 20 -> input.skipNBytes(2) // String, MethodType, Module, Package
                15 -> input.skipNBytes(3) // MethodHandle
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                5, 6 -> { // Long and Double occupy two constant pool slots
                    input.skipNBytes(8)
                    slot++
                }
                else -> throw IOException("unknown constant pool tag $tag")
            }
            slot++
        }
        input.skipNBytes(2) // access_flags
        val thisClass = input.readUnsignedShort()
        val binaryName = utf8.getValue(classNameIndex.getValue(thisClass)).replace('/', '.')
        input.skipNBytes(2) // super_class
        input.skipNBytes(input.readUnsignedShort() * 2L) // interfaces
        skipMembers(input) // fields
        skipMembers(input) // methods
        var serializable = false
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            val length = input.readInt().toLong() and 0xFFFFFFFFL
            when (name) {
                "RuntimeVisibleAnnotations" -> serializable = readAnnotations(input, utf8) || serializable
                else -> input.skipNBytes(length)
            }
        }
        return ParsedClass(binaryName, serializable)
    }

    private fun skipMembers(input: DataInputStream) {
        repeat(input.readUnsignedShort()) {
            input.skipNBytes(6) // access_flags + name_index + descriptor_index
            repeat(input.readUnsignedShort()) {
                input.skipNBytes(2) // attribute_name_index
                input.skipNBytes(input.readInt().toLong() and 0xFFFFFFFFL)
            }
        }
    }

    private fun readAnnotations(
        input: DataInputStream,
        utf8: Map<Int, String>,
    ): Boolean {
        var found = false
        repeat(input.readUnsignedShort()) {
            if (utf8[input.readUnsignedShort()] == SERIALIZABLE) found = true
            repeat(input.readUnsignedShort()) {
                input.skipNBytes(2) // element_name_index
                skipElementValue(input)
            }
        }
        return found
    }

    private fun skipElementValue(input: DataInputStream) {
        when (val tag = input.readUnsignedByte().toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> input.skipNBytes(2)
            'e' -> input.skipNBytes(4) // enum: type_name_index + const_name_index
            '@' -> { // nested annotation: type_index + element_value_pairs
                input.skipNBytes(2)
                repeat(input.readUnsignedShort()) {
                    input.skipNBytes(2)
                    skipElementValue(input)
                }
            }
            '[' -> repeat(input.readUnsignedShort()) { skipElementValue(input) }
            else -> throw IOException("unknown element_value tag '$tag'")
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.SerializableClassScannerTest"`
Expected: PASS (5/5). If a structural parse bug misaligns the stream, the symptom is usually an `IOException`/`EOFException` swallowed into "class not found" assertions — debug by parsing one known fixture file and printing offsets, don't loosen the tests.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScanner.kt \
  serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScannerTest.kt \
  serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/scanfixtures/ScannerFixtures.kt
git commit -m "feat(extractor): detect class-level @Serializable via class-file scan (#55)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Scanner robustness — generics skipped, false-positive guard, unreadable files

**Files:**
- Modify: `serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/scanfixtures/ScannerFixtures.kt`
- Modify: `serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScannerTest.kt`
- Modify: `serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScanner.kt`

**Interfaces:**
- Consumes: Task 1's scanner and fixtures.
- Produces: the final `ScanResult` shape Task 3 depends on:
  `internal data class ScanResult(val typeNames: List<String>, val unreadable: List<String>, val skippedGenerics: List<String>)` — `unreadable` holds root-relative class-file paths; `skippedGenerics` holds binary names.

- [ ] **Step 1: Add the new fixtures**

Append to `ScannerFixtures.kt` (after `ScannedEvent`, before `scanFixturesRoot`); add the imports `kotlinx.serialization.KSerializer`, `kotlinx.serialization.descriptors.PrimitiveKind`, `kotlinx.serialization.descriptors.PrimitiveSerialDescriptor`, `kotlinx.serialization.descriptors.SerialDescriptor`, `kotlinx.serialization.encoding.Decoder`, `kotlinx.serialization.encoding.Encoder`:

```kotlin
@Serializable
data class ScannedBox<T>(val value: T)

object IntAsStringSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IntAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Int = decoder.decodeString().toInt()
}

/**
 * Carries only a *property-level* `@Serializable(with = …)`: the constant pool
 * contains the annotation descriptor string, but there is no class-level
 * annotation — the scanner must not mark this class (false-positive guard).
 */
class PropertyLevelOnly(
    @Serializable(with = IntAsStringSerializer::class) val count: Int,
)
```

- [ ] **Step 2: Write the failing tests**

Append to `SerializableClassScannerTest.kt` (import `com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedBox` and `kotlin.test.assertTrue`):

```kotlin
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.SerializableClassScannerTest"`
Expected: COMPILATION FAILURE — `ScanResult` has no `unreadable`/`skippedGenerics` properties.

- [ ] **Step 4: Extend the scanner**

In `SerializableClassScanner.kt`, replace `ScanResult`, `scan`, `ParsedClass`, and the attribute loop of `parse` with:

```kotlin
    internal data class ScanResult(
        /** Binary names (`com.foo.Bar$Baz`) of classes with a class-level `@Serializable`. */
        val typeNames: List<String>,
        /** Root-relative paths of class files that could not be parsed (→ OPAQUE upstream). */
        val unreadable: List<String>,
        /** Binary names of `@Serializable` classes skipped because they declare type parameters. */
        val skippedGenerics: List<String>,
    )

    /** Scans every `*.class` under each of [roots]; results are sorted + deduped. Never throws. */
    fun scan(roots: List<File>): ScanResult {
        val typeNames = mutableListOf<String>()
        val unreadable = mutableListOf<String>()
        val skippedGenerics = mutableListOf<String>()
        for (root in roots) {
            val classFiles =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .sortedBy(File::getPath)
            for (file in classFiles) {
                val parsed = runCatching { parse(file.readBytes()) }.getOrNull()
                when {
                    parsed == null -> unreadable += file.relativeTo(root).path
                    !parsed.serializable -> {}
                    parsed.generic -> skippedGenerics += parsed.binaryName
                    else -> typeNames += parsed.binaryName
                }
            }
        }
        return ScanResult(
            typeNames = typeNames.distinct().sorted(),
            unreadable = unreadable.sorted(),
            skippedGenerics = skippedGenerics.distinct().sorted(),
        )
    }

    private class ParsedClass(val binaryName: String, val serializable: Boolean, val generic: Boolean)
```

and in `parse`, replace the attribute loop and return with:

```kotlin
        var serializable = false
        var generic = false
        repeat(input.readUnsignedShort()) {
            val name = utf8[input.readUnsignedShort()]
            val length = input.readInt().toLong() and 0xFFFFFFFFL
            when (name) {
                "RuntimeVisibleAnnotations" -> serializable = readAnnotations(input, utf8) || serializable
                // A class signature starting `<` declares type parameters (JVMS §4.7.9.1).
                "Signature" -> generic = utf8[input.readUnsignedShort()]?.startsWith("<") == true
                else -> input.skipNBytes(length)
            }
        }
        return ParsedClass(binaryName, serializable, generic)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.SerializableClassScannerTest"`
Expected: PASS (10/10).

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScanner.kt \
  serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/SerializableClassScannerTest.kt \
  serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/scanfixtures/ScannerFixtures.kt
git commit -m "feat(extractor): scanner skips generic roots loudly, degrades unreadable class files (#55)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Wire `scanDirs` into `SchemaExtractionMain.run` (+ `apiDump`)

**Files:**
- Create: `serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/ScanDiscoveryIntegrationTest.kt`
- Modify: `serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SchemaExtractionMain.kt:30-69`
- Modify (generated): `serialkompat-extractor/api/serialkompat-extractor.api`

**Interfaces:**
- Consumes: `SerializableClassScanner.scan(roots: List<File>): ScanResult` (Task 2 shape) and `scanFixturesRoot(tempRoot: File): File` (Task 1).
- Produces: `public fun run(typeNames: List<String>, jsonInstanceFqn: String?, output: File, scanDirs: List<File> = emptyList())` — Task 4's CLI calls this 4-arg form.

- [ ] **Step 1: Write the failing tests**

`serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/ScanDiscoveryIntegrationTest.kt`:

```kotlin
package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedBox
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedOrder
import com.chrisjenx.serialkompat.extractor.scanfixtures.ScannedStatus
import com.chrisjenx.serialkompat.extractor.scanfixtures.scanFixturesRoot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `run(scanDirs = …)` discovery semantics (issue #55): explicit `types` always win;
 * otherwise the classpath manifest unions with the class-dir scan. Generic roots are
 * skipped loudly; unreadable class files degrade to OPAQUE coverage gaps.
 */
class ScanDiscoveryIntegrationTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also(tempDirs::add)

    private fun fixturesRoot(): File = scanFixturesRoot(tempDir("skompat-scan"))

    private fun outFile(): File = File(tempDir("skompat-out"), "current.snapshot")

    @Test
    fun `scan dirs feed discovery when no types are configured`() {
        val out = outFile()
        SchemaExtractionMain.run(emptyList(), null, out, scanDirs = listOf(fixturesRoot()))
        val snapshot = SnapshotFormat.parse(out.readText())
        assertTrue(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
        assertFalse(snapshot.contracts.any { it.serialName.contains("ScannedBox") })
    }

    @Test
    fun `explicit types win over the scan`() {
        val out = outFile()
        SchemaExtractionMain.run(
            typeNames = listOf(ScannedStatus::class.java.name),
            jsonInstanceFqn = null,
            output = out,
            scanDirs = listOf(fixturesRoot()),
        )
        val snapshot = SnapshotFormat.parse(out.readText())
        assertTrue(snapshot.contracts.any { it.serialName == ScannedStatus::class.java.name })
        assertFalse(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
    }

    @Test
    fun `discovery unions the classpath manifest with the scan`() {
        val manifestDir = tempDir("skompat-manifest")
        File(manifestDir, "META-INF/serialkompat/serializable-types.txt").apply {
            parentFile.mkdirs()
            writeText("com.example.FromManifest\n")
        }
        val original = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader =
            URLClassLoader(arrayOf(manifestDir.toURI().toURL()), original)
        val out = outFile()
        try {
            SchemaExtractionMain.run(emptyList(), null, out, scanDirs = listOf(fixturesRoot()))
        } finally {
            Thread.currentThread().contextClassLoader = original
        }
        val snapshot = SnapshotFormat.parse(out.readText())
        // The manifest entry is unresolvable → OPAQUE, proving the manifest was read;
        // the scanned fixture extracted normally, proving the union.
        assertEquals(
            ContractKind.OPAQUE,
            snapshot.contracts.single { it.serialName == "com.example.FromManifest" }.kind,
        )
        assertTrue(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
    }

    @Test
    fun `an unreadable class file surfaces as OPAQUE even with explicit types`() {
        val root = tempDir("skompat-corrupt")
        File(root, "com/example/Broken.class").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
        val out = outFile()
        SchemaExtractionMain.run(
            typeNames = listOf(ScannedStatus::class.java.name),
            jsonInstanceFqn = null,
            output = out,
            scanDirs = listOf(root),
        )
        val snapshot = SnapshotFormat.parse(out.readText())
        val opaque = snapshot.contracts.single { it.serialName == "com/example/Broken.class" }
        assertEquals(ContractKind.OPAQUE, opaque.kind)
    }

    @Test
    fun `skipped generics are logged to stderr by name`() {
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured))
        try {
            SchemaExtractionMain.run(emptyList(), null, outFile(), scanDirs = listOf(fixturesRoot()))
        } finally {
            System.setErr(originalErr)
        }
        assertTrue(captured.toString().contains(ScannedBox::class.java.name))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.ScanDiscoveryIntegrationTest"`
Expected: COMPILATION FAILURE — `run` has no `scanDirs` parameter.

- [ ] **Step 3: Extend `run`**

In `SchemaExtractionMain.kt`, replace the `run` function (keep the KDoc, extending it) with:

```kotlin
    /**
     * Extracts [typeNames] (fully-qualified `@Serializable` class names) into a
     * snapshot written to [output]. If [jsonInstanceFqn] names a reachable `Json`
     * instance its configuration is read; otherwise a conservative default is
     * assumed with a warning (design §6 resolution order).
     *
     * When [typeNames] is empty, types are discovered instead: the classpath
     * manifest (see [TYPES_RESOURCE]) unioned with a class-dir scan of [scanDirs]
     * (issue #55). Generic classes found by the scan are skipped as roots — they
     * have no standalone wire shape; their concrete shapes are covered at use
     * sites — and logged by name. Unreadable class files degrade to OPAQUE
     * coverage gaps whenever [scanDirs] were scanned, even alongside explicit
     * [typeNames].
     */
    public fun run(
        typeNames: List<String>,
        jsonInstanceFqn: String?,
        output: File,
        scanDirs: List<File> = emptyList(),
    ) {
        val json = jsonInstanceFqn?.let(::loadJson) ?: Json
        if (jsonInstanceFqn != null && loadJson(jsonInstanceFqn) == null) {
            System.err.println(
                "serialkompat: could not load Json instance '$jsonInstanceFqn'; assuming default config.",
            )
        }
        val config = if (json === Json) SnapshotConfig() else JsonConfigReader.read(json)
        val scan = SerializableClassScanner.scan(scanDirs)
        if (scan.skippedGenerics.isNotEmpty()) {
            System.err.println(
                "serialkompat: skipped ${scan.skippedGenerics.size} generic type(s) as scan roots " +
                    "(their shapes are checked at concrete use sites): " +
                    scan.skippedGenerics.joinToString(", "),
            )
        }
        // Fall back to discovered types when none are configured explicitly: the
        // classpath manifest (see [TYPES_RESOURCE]) unioned with the class-dir scan.
        val effectiveTypes =
            typeNames.ifEmpty { (discoverTypeNames() + scan.typeNames).distinct().sorted() }

        // Resolve each type independently. A single unresolvable/broken type (stale manifest entry,
        // renamed class, missing transitive dependency, a generic that needs type args) must NEVER
        // abort the whole extraction — that would drop every type. It degrades to an OPAQUE coverage
        // gap keyed by its FQN; the gate then surfaces a WARN rather than crashing or silently
        // dropping the lot ("the extractor must never throw on a model it can't analyse", design §10).
        val descriptors = mutableListOf<SerialDescriptor>()
        val opaque = mutableListOf<Contract>()
        for (name in effectiveTypes) {
            val descriptor =
                runCatching { serializer(Class.forName(name).kotlin.createType()).descriptor }.getOrNull()
            if (descriptor != null) {
                descriptors += descriptor
            } else {
                System.err.println(
                    "serialkompat: could not resolve type '$name'; recording it as an opaque coverage gap.",
                )
                opaque += Contract(name, ContractKind.OPAQUE)
            }
        }
        // An unreadable class file is an unanalysable input: unanalysable ≠ safe, so it
        // surfaces as an OPAQUE gap regardless of whether explicit types were configured.
        for (path in scan.unreadable) {
            System.err.println(
                "serialkompat: could not parse class file '$path'; recording it as an opaque coverage gap.",
            )
            opaque += Contract(path, ContractKind.OPAQUE)
        }

        val extracted = DescriptorSnapshotExtractor.extract(descriptors, json.serializersModule, config)
        val snapshot = Snapshot(extracted.contracts + opaque, config)
        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(SnapshotFormat.serialize(snapshot))
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.ScanDiscoveryIntegrationTest"`
Expected: PASS (5/5). Also run the full module suite to catch regressions in existing `run` callers:
`./gradlew :serialkompat-extractor:test` — Expected: PASS.

- [ ] **Step 5: Regenerate the API baseline (intentional public change: new `run` parameter)**

```bash
./gradlew apiDump
git diff serialkompat-extractor/api/serialkompat-extractor.api
```
Expected diff: `run (Ljava/util/List;Ljava/lang/String;Ljava/io/File;)V` becomes
`run (Ljava/util/List;Ljava/lang/String;Ljava/io/File;Ljava/util/List;)V` plus a
`run$default` synthetic. Nothing else changes.

- [ ] **Step 6: Verify the gate, format, commit**

```bash
./gradlew :serialkompat-extractor:apiCheck spotlessApply
git add serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SchemaExtractionMain.kt \
  serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/ScanDiscoveryIntegrationTest.kt \
  serialkompat-extractor/api/serialkompat-extractor.api
git commit -m "feat(extractor): run() discovers types from scanned class dirs (#55)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: CLI `--scan-classes`

**Files:**
- Modify: `serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SchemaExtractionMain.kt:71-84` (the `main` function)
- Modify: `serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/ScanDiscoveryIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 3's `run(typeNames, jsonInstanceFqn, output, scanDirs)`.
- Produces: CLI contract `--types a,b,c | --scan-classes dir1<pathSep>dir2 --out path [--json fqn]`, at least one of `--types`/`--scan-classes` required. (#115's Gradle wiring will pass `--scan-classes`.)

- [ ] **Step 1: Write the failing tests**

Append to `ScanDiscoveryIntegrationTest.kt` (import `kotlin.test.assertFailsWith`):

```kotlin
    @Test
    fun `cli runs with --scan-classes and no --types`() {
        val out = outFile()
        SchemaExtractionMain.main(arrayOf("--scan-classes", fixturesRoot().path, "--out", out.path))
        val snapshot = SnapshotFormat.parse(out.readText())
        assertTrue(snapshot.contracts.any { it.serialName == ScannedOrder::class.java.name })
    }

    @Test
    fun `cli requires --types or --scan-classes`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                SchemaExtractionMain.main(arrayOf("--out", outFile().path))
            }
        assertTrue(failure.message!!.contains("--types or --scan-classes"))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.ScanDiscoveryIntegrationTest"`
Expected: FAIL — `cli runs with --scan-classes and no --types` throws `IllegalArgumentException: serialkompat: --types is required`; the other test fails on the message assertion.

- [ ] **Step 3: Update `main`**

Replace the `main` function in `SchemaExtractionMain.kt` with:

```kotlin
    /**
     * CLI shim: `--types a,b,c | --scan-classes dir1:dir2 --out path [--json fqn]`.
     * At least one of `--types` / `--scan-classes` is required; `--scan-classes`
     * takes [File.pathSeparator]-separated class directories.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val options = parseOptions(args)
        val types =
            options["types"]
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
        val scanDirs =
            options["scan-classes"]
                ?.split(File.pathSeparator)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.map(::File)
                .orEmpty()
        val output = options["out"] ?: error("serialkompat: --out is required")
        require(types.isNotEmpty() || scanDirs.isNotEmpty()) {
            "serialkompat: --types or --scan-classes is required"
        }
        run(types, options["json"], File(output), scanDirs)
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :serialkompat-extractor:test --tests "com.chrisjenx.serialkompat.extractor.ScanDiscoveryIntegrationTest"`
Expected: PASS (7/7). `main` is `@JvmStatic` on an object — no `.api` change (`apiCheck` stays green; verify with `./gradlew :serialkompat-extractor:apiCheck`).

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SchemaExtractionMain.kt \
  serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/ScanDiscoveryIntegrationTest.kt
git commit -m "feat(extractor): --scan-classes CLI flag; --types no longer mandatory (#55)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Docs — design §4 rewrite, stale comments, CHANGELOG; full gate

**Files:**
- Modify: `docs/design/2026-06-30-serialkompat-design.md:183-192` (§4 Approach C block), `:588-590` (§13 v1 bullet), `:638-640` (§15 decision 4)
- Modify: `serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SchemaExtractionMain.kt:86-92` (`TYPES_RESOURCE` KDoc)
- Modify: `serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/SchemaDiscoveryTest.kt:10-17` (header comment)
- Modify: `CHANGELOG.md` (under `## [Unreleased]`)

**Interfaces:**
- Consumes: the shipped behavior from Tasks 1–4 (documentation only; no code change).
- Produces: nothing downstream.

- [ ] **Step 1: Rewrite the design doc's Approach C block**

Replace the paragraph starting `**Build-time discovery (Approach C) — deferred, opt-in.**` (design doc lines 183–192) with:

```markdown
**Discovery (Approach C) — class-dir scanning (#55).** Extraction (Approach A)
needs a list of types to reflect on. The primary source is explicit configuration;
when no types are configured the extractor discovers them by unioning two
producer-agnostic sources: the classpath manifest
(`META-INF/serialkompat/serializable-types.txt`, one `@Serializable` FQN per line)
and a scan of compiled class directories (`--scan-classes`). The scan is a
dependency-free class-file parse — no classloading: `@Serializable` is
RUNTIME-retained, so annotated classes carry it in `RuntimeVisibleAnnotations`.
Generic classes are skipped as scan roots, loudly — they have no standalone wire
shape and their concrete shapes are covered at use sites (a survey of two
production codebases found 9/9 generics nested in one; the other's 2/2 root-only
envelopes are the logged, tracked gap). Unreadable class files degrade to OPAQUE
coverage gaps. **KSP remains rejected** (maintainer decision, issue #22); the
compiler-plugin producer originally tracked in #55 is retired — #22 only mandated
a compiler plugin *over KSP*, and scanning proved not-awkward.
```

- [ ] **Step 2: Update the two stale roadmap/decision mentions**

In §13 (line ~588), replace the parenthetical
`(Automated build-time discovery — a Kotlin compiler plugin, Approach C — is deferred beyond v1; explicit config + the manifest contract suffice.)`
with:
`(Automated discovery — Approach C — shipped post-v1 as an extractor class-dir scan, #55; explicit config + the manifest contract remain.)`

In §15 decision 4 (lines ~638-640), replace
`Build-time discovery (Approach C) is deferred and, per maintainer decision (#22), must be a Kotlin compiler plugin — **not KSP**.`
with:
`Discovery (Approach C) shipped as class-dir scanning inside the extractor (#55 re-scope); **KSP rejected** (#22); the compiler-plugin producer is retired.`

Then run `grep -n "compiler plugin" docs/design/2026-06-30-serialkompat-design.md` — the only remaining hits must be §4's fidelity rationale (lines ~171-181, about a compiler-plugin *extractor*, still a correctly-rejected alternative) and §14's residual-risk notes (a hypothetical compiler-plugin extractor for `@EncodeDefault`/default values — a different concern, keep as-is).

- [ ] **Step 3: Update the stale code comments**

In `SchemaExtractionMain.kt`, `TYPES_RESOURCE` KDoc: replace the sentence
`A Kotlin compiler plugin producer is the sanctioned automated route and is tracked separately (design §4, issue #22); KSP is explicitly not used.`
with:
`The extractor's own class-dir scan ([SerializableClassScanner], issue #55) is the automated producer; KSP is explicitly not used (#22).`

In `SchemaDiscoveryTest.kt`, replace the header-comment fragment
`(a Kotlin compiler plugin is the sanctioned route, tracked in #22; KSP is not used)`
with:
`(the extractor's class-dir scan, #55, is the automated producer; KSP is not used, #22)`

- [ ] **Step 4: CHANGELOG entry**

Under `## [Unreleased]`, add (create the `### Added` section if absent, above `### Security`):

```markdown
### Added
- `@Serializable` discovery via class-dir scanning (#55, re-scoped). When no `types` are configured, the extractor discovers roots by unioning the classpath manifest with a scan of compiled class directories (`--scan-classes`, `File.pathSeparator`-separated) — a dependency-free class-file parse with no classloading (`@Serializable` is RUNTIME-retained). Generic classes are skipped as scan roots and logged by name (no standalone wire shape; concrete shapes are covered at use sites); unreadable class files degrade to OPAQUE coverage gaps. The compiler-plugin producer originally tracked in #55 is retired (#22 only mandated a compiler plugin over KSP; KSP remains rejected). Unblocks discovery modes (#115).
```

- [ ] **Step 5: Run the full local gate**

Run: `./gradlew build`
Expected: PASS (compile + test + spotlessCheck + apiCheck + checkRulesDoc + koverVerify all green).

- [ ] **Step 6: Commit**

```bash
git add docs/design/2026-06-30-serialkompat-design.md CHANGELOG.md \
  serialkompat-extractor/src/main/kotlin/com/chrisjenx/serialkompat/extractor/SchemaExtractionMain.kt \
  serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/SchemaDiscoveryTest.kt
git commit -m "docs: record #55 re-scope — discovery is class-dir scanning, compiler plugin retired

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## After all tasks (controller, not a subagent)

1. Push `feat/55-classdir-discovery`, open the PR (`Closes #55`, body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`), wait for CI green, merge per repo convention.
2. Post-merge issue hygiene (spec §5): retitle #55 (`Build-time @Serializable discovery via class-dir scanning`) + closing comment recording the re-scope; comment on #115 that the blocker is resolved; file the follow-up issue for generic shape analysis (placeholder type args, `T`-holes as snapshot nodes; motivating case: root-only envelopes like `BaseResponse<T>`; link it from the #55 close and reference the loud-skip log line).
