package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Classifier
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Rules
import com.chrisjenx.serialkompat.core.Severity
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotDiffer
import com.chrisjenx.serialkompat.core.SnapshotFormat
import com.chrisjenx.serialkompat.extractor.basefixtures.Horse
import com.chrisjenx.serialkompat.extractor.basefixtures.baseFixturesRoot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// --- a subtype shared by two sealed bases, in two versions -------------------------------------

@Serializable
@SerialName("Grooming")
private sealed interface GroomingV1

@Serializable
@SerialName("Billing")
private sealed interface BillingV1

@Serializable
@SerialName("shared")
private data class SharedV1(
    val ref: String,
) : GroomingV1,
    BillingV1

@Serializable
@SerialName("Grooming")
private sealed interface GroomingV2

@Serializable
@SerialName("Billing")
private sealed interface BillingV2

@Serializable
@SerialName("shared")
private data class SharedV2(
    val ref: Int,
) : GroomingV2,
    BillingV2

/**
 * Base-qualified contract identity (#200) end to end through the real extractor and real
 * kotlinx-serialization: subtypes that share a serial name across bases are each checked under
 * their own base, and snapshots recorded before bases were still diff cleanly.
 */
class BaseQualifiedIdentityTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile().also(tempDirs::add)

    private fun extractV1() =
        DescriptorSnapshotExtractor.extract(
            listOf(serializer<GroomingV1>().descriptor, serializer<BillingV1>().descriptor),
        )

    private fun extractV2() =
        DescriptorSnapshotExtractor.extract(
            listOf(serializer<GroomingV2>().descriptor, serializer<BillingV2>().descriptor),
        )

    @Test
    fun `a subtype of two bases is recorded once per base with the same shape`() {
        val shared = extractV1().contracts.filter { it.serialName == "shared" }
        assertEquals(listOf("Billing/shared", "Grooming/shared"), shared.map { it.qualifiedName })
        assertEquals(shared[0].elements, shared[1].elements)
    }

    @Test
    fun `a change to a subtype of two bases is reported under both, as real decodes through both agree`() {
        // Ground truth: `ref` changed String -> Int; old payloads no longer decode through either base.
        val viaGrooming = Json.encodeToString(serializer<GroomingV1>(), SharedV1("r-1"))
        val viaBilling = Json.encodeToString(serializer<BillingV1>(), SharedV1("r-1"))
        assertFailsWith<Exception> { Json.decodeFromString(serializer<GroomingV2>(), viaGrooming) }
        assertFailsWith<Exception> { Json.decodeFromString(serializer<BillingV2>(), viaBilling) }

        val findings = Classifier().classify(SnapshotDiffer.diff(extractV1(), extractV2()))
        val backwardBreaks =
            findings
                .filter { it.direction == CompatibilityDirection.BACKWARD && it.severity == Severity.BREAK }
                .map { it.contract to it.rule }
                .toSet()
        assertEquals(
            setOf(
                "Billing/shared" to Rules.PROPERTY_TYPE_CHANGED,
                "Grooming/shared" to Rules.PROPERTY_TYPE_CHANGED,
            ),
            backwardBreaks,
        )
    }

    @Test
    fun `an unchanged subtype of two bases yields no findings`() {
        // Ground truth: identical models round-trip through both bases.
        val payload = Json.encodeToString(serializer<GroomingV1>(), SharedV1("r-1"))
        assertEquals(SharedV1("r-1"), Json.decodeFromString(serializer<GroomingV1>(), payload))
        assertEquals(emptyList(), Classifier().classify(SnapshotDiffer.diff(extractV1(), extractV1())))
    }

    @Test
    fun `a snapshot recorded before base qualification diffs cleanly against a new extraction`() {
        // The pre-#200 format is today's minus the `base=` header key; recorded history and cached
        // baselines on disk look exactly like this. Pairing must fall back to the bare name.
        val current = extractV1()
        val text = SnapshotFormat.serialize(current)
        assertTrue(" base=" in text)
        val preBase = SnapshotFormat.parse(text.replace(Regex(" base=\\S+"), "")).contracts
        // A pre-#200 extractor recorded one bare `shared`; drop the now-duplicate second copy.
        val legacy = Snapshot(preBase.distinct(), current.config)
        assertEquals(1, legacy.contracts.count { it.serialName == "shared" && it.base == null })
        // Two current `shared` contracts vs one bare baseline: ambiguous, so a gap — never removed/added.
        val findings = Classifier().classify(SnapshotDiffer.diff(legacy, current))
        assertTrue(findings.none { it.rule == Rules.CONTRACT_REMOVED }, "$findings")
        assertTrue(findings.all { it.rule == Rules.COVERAGE_GAP }, "$findings")
    }

    // --- dogfood-shaped discovery: many bases reusing subtype names --------------------------------

    private fun discover(): Snapshot {
        val out = File(tempDir("skompat-out"), "current.snapshot")
        SchemaExtractionMain.run(emptyList(), null, out, scanDirs = listOf(baseFixturesRoot(tempDir("skompat-scan"))))
        return SnapshotFormat.parse(out.readText())
    }

    @Test
    fun `discovered subtypes sharing names across bases are all analysed, none opaque`() {
        val snapshot = discover()
        assertEquals(emptyList(), snapshot.contracts.filter { it.kind == ContractKind.OPAQUE }.map { it.qualifiedName })
        val subtypes = snapshot.contracts.filter { it.serialName in setOf("board", "equine", "unknown") }
        assertEquals(
            listOf(
                "Horse/board",
                "Invoice/board",
                "Stall/board",
                "Horse/equine",
                "Invoice/equine",
                "Horse/unknown",
                "Invoice/unknown",
                "Stall/unknown",
            ),
            subtypes.map { it.qualifiedName },
        )
        // Discovery hands each subtype in as a root too; it is checked under its base, not again bare.
        assertFalse(subtypes.any { it.base == null })
    }

    @Test
    fun `a pre-200 discovered snapshot of a model with unique subtype names diffs with no findings`() {
        // Discovery over a model whose subtype names are unique (Horse/equine vs Invoice/equine are
        // not — so restrict to one base): the old bare record pairs with the new qualified one.
        val current =
            DescriptorSnapshotExtractor.extract(
                listOf(
                    serializer<Horse>().descriptor,
                    serializer<Horse.Board>().descriptor,
                ),
            )
        val legacy = SnapshotFormat.parse(SnapshotFormat.serialize(current).replace(Regex(" base=\\S+"), ""))
        assertTrue(legacy.contracts.all { it.base == null })
        assertEquals(emptyList(), Classifier().classify(SnapshotDiffer.diff(legacy, current)))
    }
}
