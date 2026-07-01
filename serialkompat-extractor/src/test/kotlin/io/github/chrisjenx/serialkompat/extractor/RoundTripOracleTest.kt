package io.github.chrisjenx.serialkompat.extractor

import io.github.chrisjenx.serialkompat.core.Classifier
import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import io.github.chrisjenx.serialkompat.core.Severity
import io.github.chrisjenx.serialkompat.core.SnapshotDiffer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The oracle for issue #10 (design §11): it cross-checks the hand-derived rule
 * matrix against how kotlinx-serialization *actually* behaves. For each fixture
 * pair (two schema versions of the same serial name), it:
 *
 *  1. extracts + diffs + classifies the two schemas → the *predicted* break, and
 *  2. round-trips a real payload the other way (encode with one version, decode
 *     with the other) → the *observed* outcome.
 *
 * The invariant is **soundness**: whenever a real decode throws, the classifier
 * must have predicted a BREAK in that direction (`realThrew ⇒ predictedBreak`).
 * The converse need not hold — some breaks are value-dependent (a `String→Int`
 * change only throws for non-numeric data; a widening only overflows for large
 * values), so the classifier is intentionally *conservative* and may predict a
 * BREAK the specific probe value doesn't trigger. What it must never do is miss
 * a real break, which is exactly what this asserts.
 */
class RoundTripOracleTest {
    private enum class Outcome { DECODED, THREW }

    private val strict = Json
    private val lenient = Json { ignoreUnknownKeys = true }

    private fun <W, R> roundTrip(
        writerJson: Json,
        writer: KSerializer<W>,
        value: W,
        readerJson: Json,
        reader: KSerializer<R>,
    ): Outcome =
        try {
            readerJson.decodeFromString(reader, writerJson.encodeToString(writer, value))
            Outcome.DECODED
        } catch (_: Exception) {
            Outcome.THREW
        }

    /**
     * Asserts soundness for both directions: whenever the real round-trip throws,
     * the classifier must have predicted a BREAK there. `old`/`new` are the two
     * schema versions; the values are representative payloads used to probe real
     * decode behavior.
     */
    private fun <A, B> assertOracleAgrees(
        oldSerializer: KSerializer<A>,
        oldValue: A,
        newSerializer: KSerializer<B>,
        newValue: B,
        oldJson: Json = strict,
        newJson: Json = strict,
    ) {
        val oldConfig = JsonConfigReader.read(oldJson)
        val newConfig = JsonConfigReader.read(newJson)
        val changes =
            SnapshotDiffer.diff(
                DescriptorSnapshotExtractor.extract(listOf(oldSerializer.descriptor), config = oldConfig),
                DescriptorSnapshotExtractor.extract(listOf(newSerializer.descriptor), config = newConfig),
            )
        val findings = Classifier().classify(changes, oldConfig, newConfig)

        fun predictedBreak(direction: CompatibilityDirection) =
            findings.any { it.direction == direction && it.severity == Severity.BREAK }

        // backward = new code reads old data; forward = old code reads new data.
        val backwardThrew = roundTrip(oldJson, oldSerializer, oldValue, newJson, newSerializer) == Outcome.THREW
        val forwardThrew = roundTrip(newJson, newSerializer, newValue, oldJson, oldSerializer) == Outcome.THREW

        // Soundness: a real break must have been predicted (over-prediction is allowed).
        assertTrue(
            !backwardThrew || predictedBreak(CompatibilityDirection.BACKWARD),
            "backward: real decode threw but classifier predicted no break",
        )
        assertTrue(
            !forwardThrew || predictedBreak(CompatibilityDirection.FORWARD),
            "forward: real decode threw but classifier predicted no break",
        )
    }

    // --- fixtures: two versions of the same serial name ------------------------

    @Serializable
    @SerialName("AddField")
    private data class AddFieldV1(
        val id: String,
    )

    @Serializable
    @SerialName("AddField")
    private data class AddFieldV2(
        val id: String,
        val note: String = "",
    )

    @Serializable
    @SerialName("RemoveField")
    private data class RemoveFieldV1(
        val id: String,
        val note: String = "",
    )

    @Serializable
    @SerialName("RemoveField")
    private data class RemoveFieldV2(
        val id: String,
    )

    @Serializable
    @SerialName("Nullability")
    private data class NullabilityV1(
        val id: String?,
    )

    @Serializable
    @SerialName("Nullability")
    private data class NullabilityV2(
        val id: String,
    )

    @Serializable
    @SerialName("TypeChange")
    private data class TypeChangeV1(
        val v: String,
    )

    @Serializable
    @SerialName("TypeChange")
    private data class TypeChangeV2(
        val v: Int,
    )

    @Serializable
    @SerialName("Widen")
    private data class WidenV1(
        val v: Int,
    )

    @Serializable
    @SerialName("Widen")
    private data class WidenV2(
        val v: Long,
    )

    @Serializable
    @SerialName("EnumStatus")
    private enum class EnumStatusV1 { A, B }

    @Serializable
    @SerialName("EnumStatus")
    private enum class EnumStatusV2 { A, B, C }

    // --- oracle checks ---------------------------------------------------------

    @Test
    fun `adding an optional field — strict reader`() {
        assertOracleAgrees(
            serializer<AddFieldV1>(),
            AddFieldV1("x"),
            serializer<AddFieldV2>(),
            AddFieldV2("x", note = "present"),
        )
    }

    @Test
    fun `adding an optional field — lenient old reader tolerates it forward`() {
        assertOracleAgrees(
            serializer<AddFieldV1>(),
            AddFieldV1("x"),
            serializer<AddFieldV2>(),
            AddFieldV2("x", note = "present"),
            oldJson = lenient,
        )
    }

    @Test
    fun `removing an optional field`() {
        assertOracleAgrees(
            serializer<RemoveFieldV1>(),
            RemoveFieldV1("x", note = "present"),
            serializer<RemoveFieldV2>(),
            RemoveFieldV2("x"),
        )
    }

    @Test
    fun `narrowing nullable to non-null`() {
        assertOracleAgrees(
            serializer<NullabilityV1>(),
            NullabilityV1(null),
            serializer<NullabilityV2>(),
            NullabilityV2("x"),
        )
    }

    @Test
    fun `incompatible type change (String to Int)`() {
        // Non-numeric string so the backward decode genuinely fails (kotlinx would
        // otherwise coerce a numeric string like "5" straight into an Int).
        assertOracleAgrees(
            serializer<TypeChangeV1>(),
            TypeChangeV1("hello"),
            serializer<TypeChangeV2>(),
            TypeChangeV2(5),
        )
    }

    @Test
    fun `numeric widening (Int to Long)`() {
        // Forward only truly breaks for out-of-range values, which is why the
        // classifier flags it conservatively — probe with a value that overflows Int.
        assertOracleAgrees(
            serializer<WidenV1>(),
            WidenV1(5),
            serializer<WidenV2>(),
            WidenV2(Long.MAX_VALUE),
        )
    }

    @Test
    fun `adding an enum value`() {
        assertOracleAgrees(
            serializer<EnumStatusV1>(),
            EnumStatusV1.A,
            serializer<EnumStatusV2>(),
            EnumStatusV2.C,
        )
    }
}
