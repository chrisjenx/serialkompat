package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Classifier
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.Contract
import com.chrisjenx.serialkompat.core.Rules
import com.chrisjenx.serialkompat.core.Severity
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotConfig
import com.chrisjenx.serialkompat.core.SnapshotDiffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
@OptIn(ExperimentalSerializationApi::class)
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

    /**
     * Config-only oracle: the schema is fixed, but the reader/writer [Json] *config*
     * differs. Beyond soundness (`realThrew ⇒ predictedBreak`), this also asserts
     * **no silent data loss**: if a direction decodes to a *different* value than was
     * written, the classifier must have flagged that direction (WARN or BREAK), never
     * left it silently safe. This is what backs the config rules against the real
     * library — previously the classifier's config verdicts had no round-trip proof.
     */
    private fun <T> assertConfigOracle(
        serializer: KSerializer<T>,
        value: T,
        oldJson: Json,
        newJson: Json,
    ) {
        val oldConfig = JsonConfigReader.read(oldJson)
        val newConfig = JsonConfigReader.read(newJson)
        val changes =
            SnapshotDiffer.diff(
                DescriptorSnapshotExtractor.extract(listOf(serializer.descriptor), config = oldConfig),
                DescriptorSnapshotExtractor.extract(listOf(serializer.descriptor), config = newConfig),
            )
        val findings = Classifier().classify(changes, oldConfig, newConfig)

        fun predictedBreak(d: CompatibilityDirection) =
            findings.any { it.direction == d && it.severity == Severity.BREAK }

        // Only actionable (WARN/BREAK) findings are reported, so any finding = flagged.
        fun flagged(d: CompatibilityDirection) = findings.any { it.direction == d }

        // backward = new code reads old data; forward = old code reads new data.
        checkConfigDirection(
            CompatibilityDirection.BACKWARD,
            serializer,
            value,
            oldJson,
            newJson,
            ::predictedBreak,
            ::flagged,
        )
        checkConfigDirection(
            CompatibilityDirection.FORWARD,
            serializer,
            value,
            newJson,
            oldJson,
            ::predictedBreak,
            ::flagged,
        )
    }

    private fun <T> checkConfigDirection(
        direction: CompatibilityDirection,
        serializer: KSerializer<T>,
        value: T,
        writerJson: Json,
        readerJson: Json,
        predictedBreak: (CompatibilityDirection) -> Boolean,
        flagged: (CompatibilityDirection) -> Boolean,
    ) {
        val decoded =
            try {
                readerJson.decodeFromString(serializer, writerJson.encodeToString(serializer, value))
            } catch (_: Exception) {
                assertTrue(predictedBreak(direction), "$direction: real decode threw but classifier predicted no BREAK")
                return
            }
        if (decoded != value) {
            assertTrue(
                flagged(direction),
                "$direction: decoded value differs from what was written (silent data loss) but classifier flagged nothing",
            )
        }
    }

    /**
     * Reader-tolerance config oracle. A config toggle over a *clean* single-schema payload decodes
     * identically both ways (vacuous), so this decodes a crafted [rawPayload] the [tolerant] reader
     * accepts and the [strict] reader rejects — proving, against the real library, that the WARN
     * corresponds to an exhibitable divergence. Then drives the real reader→differ→classifier path and
     * asserts the backward finding is [expectedRule] at WARN (WARN, not BREAK, because clean data still
     * decodes — the throw is conditional on old data actually carrying the bad key/value).
     */
    private fun <T> assertReaderToleranceObservable(
        serializer: KSerializer<T>,
        rawPayload: String,
        tolerant: Json,
        strict: Json,
        expectedRule: String,
    ) {
        tolerant.decodeFromString(serializer, rawPayload)
        assertFailsWith<Exception> { strict.decodeFromString(serializer, rawPayload) }
        val oldConfig = JsonConfigReader.read(tolerant)
        val newConfig = JsonConfigReader.read(strict)
        val changes =
            SnapshotDiffer.diff(
                DescriptorSnapshotExtractor.extract(listOf(serializer.descriptor), config = oldConfig),
                DescriptorSnapshotExtractor.extract(listOf(serializer.descriptor), config = newConfig),
            )
        val backward =
            Classifier()
                .classify(changes, oldConfig, newConfig)
                .single { it.direction == CompatibilityDirection.BACKWARD }
        assertEquals(expectedRule, backward.rule)
        assertEquals(Severity.WARN, backward.severity)
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

    // #118: a nullable field with NO default (not the `= null` optional idiom). Absent, it is a
    // MissingFieldException under explicitNulls=true but decodes as null under explicitNulls=false.
    @Serializable
    @SerialName("AddNullable")
    private data class AddNullableV1(
        val id: String,
    )

    @Serializable
    @SerialName("AddNullable")
    private data class AddNullableV2(
        val id: String,
        val extra: String?,
    )

    @Serializable
    @SerialName("RemoveNullable")
    private data class RemoveNullableV1(
        val id: String,
        val extra: String?,
    )

    @Serializable
    @SerialName("RemoveNullable")
    private data class RemoveNullableV2(
        val id: String,
    )

    @Serializable
    @SerialName("Rename")
    private data class RenameV1(
        val note: String = "",
    )

    @Serializable
    @SerialName("Rename")
    private data class RenameV2(
        val remark: String = "",
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
    @SerialName("FloatWiden")
    private data class FloatWidenV1(
        val v: Float,
    )

    @Serializable
    @SerialName("FloatWiden")
    private data class FloatWidenV2(
        val v: Double,
    )

    @Serializable
    @SerialName("ByteWiden")
    private data class ByteWidenV1(
        val v: Byte,
    )

    @Serializable
    @SerialName("ByteWiden")
    private data class ByteWidenV2(
        val v: Int,
    )

    @Serializable
    @SerialName("EnumStatus")
    private enum class EnumStatusV1 { A, B }

    @Serializable
    @SerialName("EnumStatus")
    private enum class EnumStatusV2 { A, B, C }

    @Serializable
    @SerialName("Optionality")
    private data class OptionalityV1(
        val id: String,
        val note: String = "",
    )

    @Serializable
    @SerialName("Optionality")
    private data class OptionalityV2(
        val id: String,
        val note: String,
    )

    @Serializable
    @SerialName("Shape")
    private sealed interface ShapeV1 {
        @Serializable
        @SerialName("circle")
        data class Circle(
            val r: Double,
        ) : ShapeV1
    }

    @Serializable
    @SerialName("Shape")
    private sealed interface ShapeV2 {
        @Serializable
        @SerialName("circle")
        data class Circle(
            val r: Double,
        ) : ShapeV2

        @Serializable
        @SerialName("square")
        data class Square(
            val s: Double,
        ) : ShapeV2
    }

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
    fun `nullable no-default field absence is a real break under explicitNulls=true, tolerated under false (#118)`() {
        // ADD nullable-no-default, BACKWARD (new reads old data that lacks the field); reader = new cfg.
        assertAbsenceCell(
            writer = serializer<AddNullableV1>(),
            writerVal = AddNullableV1("x"),
            reader = serializer<AddNullableV2>(),
            rule = Rules.PROPERTY_ADDED,
            direction = CompatibilityDirection.BACKWARD,
        )
        // REMOVE nullable-no-default, FORWARD (old reads new data that lacks the field); reader = old cfg.
        assertAbsenceCell(
            writer = serializer<RemoveNullableV2>(),
            writerVal = RemoveNullableV2("x"),
            reader = serializer<RemoveNullableV1>(),
            rule = Rules.PROPERTY_REMOVED,
            direction = CompatibilityDirection.FORWARD,
        )
    }

    /**
     * Oracle for the #118 refinement — an "absent field at the reader" scenario, checked against the
     * real library under both `explicitNulls` settings. [writer]/[reader] are ordered for the probed
     * round-trip; the (old,new) snapshot pair is built to match [direction] so the sole change is the
     * added/removed field, and the same [Json] config is used on both sides (no `ConfigChanged` noise).
     *
     *  - explicitNulls=true  → the absent field is a `MissingFieldException`: real decode THREW, and
     *    the classifier must predict a BREAK on [direction] (soundness).
     *  - explicitNulls=false → the absent nullable field decodes as null: real decode SUCCEEDED, and
     *    the classifier must NOT over-predict a BREAK on [direction] (the teeth of #118).
     */
    private fun <W, R> assertAbsenceCell(
        writer: KSerializer<W>,
        writerVal: W,
        reader: KSerializer<R>,
        rule: String,
        direction: CompatibilityDirection,
    ) {
        for (explicit in listOf(true, false)) {
            val json = if (explicit) strict else Json { explicitNulls = false }
            val cfg = JsonConfigReader.read(json)
            val (oldDesc, newDesc) =
                if (direction == CompatibilityDirection.BACKWARD) {
                    writer.descriptor to reader.descriptor
                } else {
                    reader.descriptor to writer.descriptor
                }
            val findings =
                Classifier().classify(
                    SnapshotDiffer.diff(
                        DescriptorSnapshotExtractor.extract(listOf(oldDesc), config = cfg),
                        DescriptorSnapshotExtractor.extract(listOf(newDesc), config = cfg),
                    ),
                    cfg,
                    cfg,
                )
            val outcome = roundTrip(json, writer, writerVal, json, reader)
            val predictedBreak =
                findings.any { it.rule == rule && it.direction == direction && it.severity == Severity.BREAK }
            if (explicit) {
                assertEquals(Outcome.THREW, outcome, "$rule $direction: expected a real throw under explicitNulls=true")
                assertTrue(predictedBreak, "$rule $direction: a real throw must be predicted as a BREAK")
            } else {
                assertEquals(
                    Outcome.DECODED,
                    outcome,
                    "$rule $direction: expected a clean decode under explicitNulls=false",
                )
                assertFalse(predictedBreak, "$rule $direction: a clean decode must not be predicted a BREAK (#118)")
            }
        }
    }

    @Test
    fun `a field rename silently loses data under a lenient reader — flagged at least WARN`() {
        // The differ decomposes a rename into remove(note) + add(remark). Under a lenient reader the
        // old payload decodes WITHOUT throwing — remark defaults, note is silently dropped — so
        // soundness alone (realThrew ⇒ break) cannot catch it. Prove the loss is real, then assert
        // the gate surfaces it as a backward WARN (silent data-loss), never leaving it silently SAFE.
        val json = Json { ignoreUnknownKeys = true }
        val decoded =
            json.decodeFromString(
                RenameV2.serializer(),
                json.encodeToString(RenameV1.serializer(), RenameV1(note = "keep me")),
            )
        assertEquals("", decoded.remark) // data silently lost, no exception thrown

        val oldConfig = JsonConfigReader.read(json)
        val newConfig = JsonConfigReader.read(json)
        val findings =
            Classifier().classify(
                SnapshotDiffer.diff(
                    DescriptorSnapshotExtractor.extract(listOf(RenameV1.serializer().descriptor), config = oldConfig),
                    DescriptorSnapshotExtractor.extract(listOf(RenameV2.serializer().descriptor), config = newConfig),
                ),
                oldConfig,
                newConfig,
            )
        // Assert on the remove-half finding specifically (robust to the add-half's optionality),
        // rather than assuming exactly one backward finding.
        val removal =
            findings.single {
                it.direction == CompatibilityDirection.BACKWARD && it.rule == Rules.PROPERTY_REMOVED
            }
        assertEquals(
            Severity.WARN,
            removal.severity,
            "a rename that silently drops data must surface as a backward WARN; got $findings",
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
    fun `numeric widening (Float to Double)`() {
        assertOracleAgrees(
            serializer<FloatWidenV1>(),
            FloatWidenV1(0.5f),
            serializer<FloatWidenV2>(),
            FloatWidenV2(Double.MAX_VALUE),
        )
    }

    @Test
    fun `numeric widening (Byte to Int)`() {
        assertOracleAgrees(
            serializer<ByteWidenV1>(),
            ByteWidenV1(7),
            serializer<ByteWidenV2>(),
            ByteWidenV2(Int.MAX_VALUE),
        )
    }

    @Test
    fun `numeric narrowing (Long to Int) breaks both ways`() {
        // Not a widening pair, so the classifier predicts BREAK both directions. Backward overflows
        // an old Int reader with a large Long value — the real decode must throw where BREAK is claimed.
        assertOracleAgrees(
            serializer<WidenV2>(),
            WidenV2(Long.MAX_VALUE),
            serializer<WidenV1>(),
            WidenV1(1),
        )
    }

    @Test
    fun `widening non-null to nullable — an emitted null breaks an old non-null reader`() {
        // old (non-null) -> new (nullable). Forward: the new writer emits `null` (explicitNulls
        // defaults true) and the old non-null reader chokes, so it must be predicted BREAK forward.
        assertOracleAgrees(
            serializer<NullabilityV2>(),
            NullabilityV2("x"),
            serializer<NullabilityV1>(),
            NullabilityV1(null),
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

    @Test
    fun `removing an enum value`() {
        // old carries C, which the new (C-less) reader cannot decode → backward break.
        assertOracleAgrees(
            serializer<EnumStatusV2>(),
            EnumStatusV2.C,
            serializer<EnumStatusV1>(),
            EnumStatusV1.A,
        )
    }

    @Test
    fun `optional becomes required`() {
        // new requires note; old omits it (default, encodeDefaults=false) → backward MissingField.
        assertOracleAgrees(
            serializer<OptionalityV1>(),
            OptionalityV1("x"),
            serializer<OptionalityV2>(),
            OptionalityV2("x", note = "n"),
        )
    }

    @Test
    fun `adding a sealed subtype`() {
        // forward: the old reader (circle only) can't resolve the new 'square' subtype → break.
        assertOracleAgrees(
            serializer<ShapeV1>(),
            ShapeV1.Circle(1.0),
            serializer<ShapeV2>(),
            ShapeV2.Square(2.0),
        )
    }

    @Test
    fun `removing a sealed subtype`() {
        // backward: the new reader (circle only) can't resolve old 'square' data → break.
        assertOracleAgrees(
            serializer<ShapeV2>(),
            ShapeV2.Square(2.0),
            serializer<ShapeV1>(),
            ShapeV1.Circle(1.0),
        )
    }

    // --- config-change fixtures ------------------------------------------------

    @Serializable
    @SerialName("Named")
    private data class Named(
        val orderId: String,
        val lineTotal: Long,
    )

    @Serializable
    @SerialName("NullHolder")
    private data class NullHolder(
        val id: String,
        val note: String? = null,
    )

    @Serializable
    @SerialName("DefHolder")
    private data class DefHolder(
        val id: String,
        val note: String = "default",
    )

    @Serializable
    @SerialName("Poly")
    private sealed interface Poly {
        @Serializable
        @SerialName("a")
        data class A(
            val x: Int,
        ) : Poly
    }

    // --- config oracle checks --------------------------------------------------

    @Test
    fun `changing the naming strategy renames every key — a real break both ways`() {
        // SnakeCase writes order_id / line_total; a default-naming reader expects
        // orderId / lineTotal and vice versa — both decodes throw on the missing key.
        assertConfigOracle(
            serializer<Named>(),
            Named("x", 1),
            oldJson = strict,
            newJson = Json { namingStrategy = JsonNamingStrategy.SnakeCase },
        )
    }

    @Test
    fun `changing the class discriminator breaks polymorphic decoding both ways`() {
        assertConfigOracle(
            serializer<Poly>(),
            Poly.A(1),
            oldJson = strict,
            newJson = Json { classDiscriminator = "kind" },
        )
    }

    @Test
    fun `toggling explicitNulls round-trips without a decode failure`() {
        // Writer with explicitNulls=false omits the null note; the reader restores it
        // from the field default. No throw, no loss — the classifier's WARN is conservative.
        assertConfigOracle(
            serializer<NullHolder>(),
            NullHolder("x", note = null),
            oldJson = strict,
            newJson = Json { explicitNulls = false },
        )
    }

    @Test
    fun `disabling encodeDefaults round-trips without a decode failure`() {
        // Writer with encodeDefaults=false omits the defaulted note; reader restores the default.
        assertConfigOracle(
            serializer<DefHolder>(),
            DefHolder("x"),
            oldJson = Json { encodeDefaults = true },
            newJson = strict,
        )
    }

    // --- coerceInputValues: schema + config interaction ------------------------

    @Serializable
    @SerialName("CoerceHolder")
    private data class CoerceHolderV1(
        val s: CoerceEnumV1 = CoerceEnumV1.A,
    )

    @Serializable
    @SerialName("CoerceHolder")
    private data class CoerceHolderV2(
        val s: CoerceEnumV2 = CoerceEnumV2.A,
    )

    @Serializable
    @SerialName("CoerceEnum")
    private enum class CoerceEnumV1 { A, B }

    @Serializable
    @SerialName("CoerceEnum")
    private enum class CoerceEnumV2 { A, B, C }

    @Test
    fun `coerceInputValues rescues an added enum value, downgrading the forward break to a warning`() {
        val newData = strict.encodeToString(serializer<CoerceHolderV2>(), CoerceHolderV2(CoerceEnumV2.C))

        // A strict old reader cannot decode the unknown value C -> real forward break.
        assertFailsWith<Exception> { strict.decodeFromString(serializer<CoerceHolderV1>(), newData) }
        // A coercing old reader falls back to the field default -> decodes (to A), no throw.
        val coercing = Json { coerceInputValues = true }
        assertEquals(CoerceEnumV1.A, coercing.decodeFromString(serializer<CoerceHolderV1>(), newData).s)

        // The classifier must mirror this: BREAK for a strict old reader, WARN once it coerces.
        val changes =
            SnapshotDiffer.diff(
                DescriptorSnapshotExtractor.extract(listOf(serializer<CoerceHolderV1>().descriptor)),
                DescriptorSnapshotExtractor.extract(listOf(serializer<CoerceHolderV2>().descriptor)),
            )
        val strictForward =
            Classifier()
                .classify(changes, JsonConfigReader.read(strict), JsonConfigReader.read(strict))
                .single { it.direction == CompatibilityDirection.FORWARD }
        val coerceForward =
            Classifier()
                .classify(changes, JsonConfigReader.read(coercing), JsonConfigReader.read(coercing))
                .single { it.direction == CompatibilityDirection.FORWARD }
        assertEquals(Severity.BREAK, strictForward.severity)
        assertEquals(Severity.WARN, coerceForward.severity)
    }

    // --- #129: coerce only rescues an added enum value for a *defaulted* field -----

    @Serializable
    @SerialName("ReqEnum")
    private enum class ReqEnumV1 { A, B }

    @Serializable
    @SerialName("ReqEnum")
    private enum class ReqEnumV2 { A, B, C }

    @Serializable
    @SerialName("ReqEnumHolder")
    private data class ReqEnumHolderV1(
        val e: ReqEnumV1,
    )

    @Serializable
    @SerialName("ReqEnumHolder")
    private data class ReqEnumHolderV2(
        val e: ReqEnumV2,
    )

    @Test
    fun `an added enum value on a REQUIRED field breaks even a coercing reader (#129)`() {
        // coerceInputValues coerces an unknown enum only to a field's *default*. A required field has
        // none, so the decode genuinely throws even when coercing — soundness demands a forward BREAK,
        // not the config-only WARN a coarser rule would give.
        val coercing = Json { coerceInputValues = true }
        val newData = coercing.encodeToString(serializer<ReqEnumHolderV2>(), ReqEnumHolderV2(ReqEnumV2.C))
        assertFailsWith<Exception> { coercing.decodeFromString(serializer<ReqEnumHolderV1>(), newData) }

        // assertOracleAgrees enforces realThrew ⇒ predictedBreak in this (forward) direction.
        assertOracleAgrees(
            serializer<ReqEnumHolderV1>(),
            ReqEnumHolderV1(ReqEnumV1.A),
            serializer<ReqEnumHolderV2>(),
            ReqEnumHolderV2(ReqEnumV2.C),
            oldJson = coercing,
            newJson = coercing,
        )
    }

    @Serializable
    @SerialName("OptEnumHolder")
    private data class OptEnumHolderV1(
        val e: ReqEnumV1 = ReqEnumV1.A,
    )

    @Serializable
    @SerialName("OptEnumHolder")
    private data class OptEnumHolderV2(
        val e: ReqEnumV2 = ReqEnumV2.A,
    )

    @Test
    fun `an added enum value on a DEFAULTED field is a coercing-reader WARN, a strict-reader BREAK (#129)`() {
        // The mirror of the required-field case: with a default, a coercing reader falls back (decodes,
        // no throw → WARN), while a strict reader still throws (→ BREAK). The verdict is decided by the
        // recorded field fact, not the live config alone.
        val strictData = strict.encodeToString(serializer<OptEnumHolderV2>(), OptEnumHolderV2(ReqEnumV2.C))
        assertFailsWith<Exception> { strict.decodeFromString(serializer<OptEnumHolderV1>(), strictData) }
        val coercing = Json { coerceInputValues = true }
        assertEquals(ReqEnumV1.A, coercing.decodeFromString(serializer<OptEnumHolderV1>(), strictData).e)

        val changes =
            SnapshotDiffer.diff(
                DescriptorSnapshotExtractor.extract(listOf(serializer<OptEnumHolderV1>().descriptor)),
                DescriptorSnapshotExtractor.extract(listOf(serializer<OptEnumHolderV2>().descriptor)),
            )

        fun forwardSeverity(json: Json) =
            Classifier()
                .classify(changes, JsonConfigReader.read(json), JsonConfigReader.read(json))
                .single { it.direction == CompatibilityDirection.FORWARD && it.rule == Rules.ENUM_VALUE_ADDED }
                .severity
        assertEquals(Severity.BREAK, forwardSeverity(strict))
        assertEquals(Severity.WARN, forwardSeverity(coercing))
    }

    // --- @JsonNames alias drop -------------------------------------------------

    @Serializable
    @SerialName("Alias")
    private data class AliasV1(
        @JsonNames("legacy") val name: String,
    )

    @Serializable
    @SerialName("Alias")
    private data class AliasV2(
        val name: String,
    )

    @Test
    fun `dropping a @JsonNames alias breaks a producer still sending that key`() {
        // A heterogeneous producer sending the legacy key decodes under V1 (alias present)
        // but not under V2 (alias dropped) — the backward-direction risk the classifier warns on.
        // Use isolated Json instances: V1 and V2 share @SerialName("Alias") with identical
        // elements, so their descriptors compare equal and would share the alt-names cache on a
        // single Json instance (V1's alias would leak to V2). A fresh reader per version is honest.
        val legacyPayload = """{"legacy":"x"}"""
        assertEquals("x", Json { }.decodeFromString(serializer<AliasV1>(), legacyPayload).name)
        assertFailsWith<Exception> { Json { }.decodeFromString(serializer<AliasV2>(), legacyPayload) }

        val changes =
            SnapshotDiffer.diff(
                DescriptorSnapshotExtractor.extract(listOf(serializer<AliasV1>().descriptor)),
                DescriptorSnapshotExtractor.extract(listOf(serializer<AliasV2>().descriptor)),
            )
        val findings = Classifier().classify(changes)
        assertTrue(
            findings.any { it.direction == CompatibilityDirection.BACKWARD && it.severity == Severity.WARN },
            "expected a backward WARN for the dropped alias; got $findings",
        )
    }

    // --- #128: open-polymorphism default-deserializer tolerance ----------------

    private interface Pet

    @Serializable
    @SerialName("dog")
    private data class Dog(
        val name: String,
    ) : Pet

    @Serializable
    @SerialName("cat")
    private data class Cat(
        val lives: Int,
    ) : Pet

    /** The catch-all sentinel a default deserializer coerces an unknown subtype into. */
    @Serializable
    @SerialName("unknownPet")
    private data class UnknownPet(
        val note: String = "?",
    ) : Pet

    @Test
    fun `a registered default deserializer downgrades an added-subtype forward break to WARN`() {
        // Two old (reader) versions of the same open base: one registers a default deserializer,
        // one does not. The new (writer) version adds the `cat` subtype.
        val oldWithDefault =
            SerializersModule {
                polymorphic(Pet::class) {
                    subclass(Dog::class)
                    defaultDeserializer { UnknownPet.serializer() }
                }
            }
        val oldNoDefault = SerializersModule { polymorphic(Pet::class) { subclass(Dog::class) } }
        val newModule =
            SerializersModule {
                polymorphic(Pet::class) {
                    subclass(Dog::class)
                    subclass(Cat::class)
                    defaultDeserializer { UnknownPet.serializer() }
                }
            }

        // Ground truth (forward = old reader ← new writer). The new writer emits a `cat`; the old
        // reader WITH a default deserializer coerces the unknown discriminator to the sentinel and
        // decodes without throwing, while the reader WITHOUT one throws. The default is what rescues it.
        val newData = Json { serializersModule = newModule }.encodeToString(serializer<Pet>(), Cat(9))
        val readerWithDefault =
            Json {
                serializersModule = oldWithDefault
                ignoreUnknownKeys = true
            }
        assertIs<UnknownPet>(
            readerWithDefault.decodeFromString(serializer<Pet>(), newData),
            "the default deserializer must coerce the unknown subtype to the sentinel",
        )
        assertFailsWith<Exception> {
            Json {
                serializersModule = oldNoDefault
                ignoreUnknownKeys = true
            }.decodeFromString(serializer<Pet>(), newData)
        }

        // The classifier must mirror the library: WARN forward when the old base recorded a default
        // (silent sentinel substitution), BREAK when it did not (a real decode failure).
        fun forwardAddedSubtype(oldModule: SerializersModule): Severity? =
            Classifier()
                .classify(
                    SnapshotDiffer.diff(
                        DescriptorSnapshotExtractor.extract(listOf(serializer<Pet>().descriptor), oldModule),
                        DescriptorSnapshotExtractor.extract(listOf(serializer<Pet>().descriptor), newModule),
                    ),
                ).filter { it.direction == CompatibilityDirection.FORWARD && it.rule == Rules.SUBTYPE_ADDED }
                .map { it.severity }
                .singleOrNull()

        assertEquals(Severity.WARN, forwardAddedSubtype(oldWithDefault), "default deserializer → forward WARN")
        assertEquals(Severity.BREAK, forwardAddedSubtype(oldNoDefault), "no default deserializer → forward BREAK")
    }

    // --- #139: generic-root hole resolution -------------------------------------

    @Serializable
    @SerialName("EnvV1")
    private data class EnvV1<T>(
        val data: T,
        val status: String,
    )

    @Serializable
    @SerialName("EnvV2")
    private data class EnvV2<T>(
        val data: T,
        val state: String, // status -> state (envelope-field rename)
    )

    /** Extract a single generic type as a hole root via the real extraction path (never hand-built). */
    private fun extractGeneric(kClass: kotlin.reflect.KClass<*>): Snapshot {
        val holes = List(kClass.typeParameters.size) { HoleSerializer(it) }
        return DescriptorSnapshotExtractor.extract(
            roots = emptyList(),
            module = EmptySerializersModule(),
            config = SnapshotConfig(),
            genericRoots = listOf(serializer(kClass, holes, false).descriptor),
        )
    }

    /**
     * Rebuilds [snapshot]'s single contract under a new serial name, identity-of-name only — the
     * element bodies still come straight from real hole extraction, so the oracle still exercises
     * #139's code rather than a hand-authored shape.
     */
    private fun renameContract(
        snapshot: Snapshot,
        old: String,
        new: String,
    ): Snapshot {
        val contract = snapshot.contracts.single { it.serialName == old }
        val renamed =
            Contract(
                serialName = new,
                kind = contract.kind,
                elements = contract.elements,
                enumValues = contract.enumValues,
                discriminator = contract.discriminator,
                subtypes = contract.subtypes,
                hasPolymorphicDefault = contract.hasPolymorphicDefault,
            )
        return Snapshot(listOf(renamed), snapshot.config)
    }

    @Test
    fun `oracle - renaming a generic envelope field is caught, matching the real library`() {
        // Both snapshots come from the real hole-extraction path; rename status -> state.
        val baseline = renameContract(extractGeneric(EnvV1::class), "EnvV1", "EnvV2")
        val current = extractGeneric(EnvV2::class)
        val changes = SnapshotDiffer.diff(baseline, current)
        val findings = Classifier().classify(changes, baseline.config, current.config)

        // Predicted: status removed + state added. Verify against the real library: a payload written
        // by V1 (has "status", lacks "state") fails to decode into V2 when "state" is required.
        assertTrue(
            findings.any { it.rule == "PROPERTY_REMOVED" && it.contract == "EnvV2" },
            "expected PROPERTY_REMOVED for the dropped 'status' field",
        )
        assertTrue(
            findings.any { it.rule == "PROPERTY_ADDED" && it.contract == "EnvV2" },
            "expected PROPERTY_ADDED for the new required 'state' field",
        )
        val v1Json = Json.encodeToString(EnvV1.serializer(String.serializer()), EnvV1("x", "OPEN"))
        val decodeFailed =
            runCatching { Json.decodeFromString(EnvV2.serializer(String.serializer()), v1Json) }.isFailure
        assertTrue(decodeFailed, "the real library must reject V1 data under V2 (required 'state' missing)")
    }

    @Serializable
    @SerialName("HostUsingEnv")
    private data class HostUsingEnv(
        val wrapped: EnvV1<String>,
    )

    @Test
    fun `oracle - adding a concrete use-site of a root-only generic fires no false BREAK`() {
        // Baseline: EnvV1 is root-only -> data:#0. Current: a host uses EnvV1<String> concretely ->
        // fill-if-absent makes data concrete. The hole<->concrete flip must NOT be a finding (#139 D4).
        val baseline = extractGeneric(EnvV1::class)
        val current =
            DescriptorSnapshotExtractor.extract(
                roots = listOf(serializer<HostUsingEnv>().descriptor),
                module = EmptySerializersModule(),
                config = SnapshotConfig(),
                genericRoots =
                    listOf(
                        serializer(EnvV1::class, listOf(HoleSerializer(0)), false).descriptor,
                    ),
            )
        val findings = Classifier().classify(SnapshotDiffer.diff(baseline, current), baseline.config, current.config)
        assertTrue(
            findings.none { it.contract == "EnvV1" && it.rule == "PROPERTY_TYPE_CHANGED" },
            "a hole<->concrete flip on the envelope must not be a wire finding: $findings",
        )
    }

    // --- reader-tolerance + coerce-input config oracles (#119) -----------------

    @Serializable
    @SerialName("StrictHolder")
    private data class StrictHolder(
        val id: String,
    )

    @Serializable
    @SerialName("AltHolder")
    private data class AltHolder(
        @JsonNames("legacy") val name: String,
    )

    @Serializable
    @SerialName("CoerceCfg")
    private data class CoerceCfg(
        val e: CoerceCfgEnum = CoerceCfgEnum.A,
    )

    @Serializable
    @SerialName("CoerceCfgEnum")
    private enum class CoerceCfgEnum { A, B }

    @Test
    fun `tightening ignoreUnknownKeys makes a tolerated unknown key throw — backward WARN`() {
        assertReaderToleranceObservable(
            serializer<StrictHolder>(),
            rawPayload = """{"id":"x","legacy":"y"}""",
            tolerant = Json { ignoreUnknownKeys = true },
            strict = Json {},
            expectedRule = Rules.CONFIG_READER_STRICTNESS,
        )
    }

    @Test
    fun `disabling useAlternativeNames makes a @JsonNames alias key throw — backward WARN`() {
        assertReaderToleranceObservable(
            serializer<AltHolder>(),
            rawPayload = """{"legacy":"x"}""",
            tolerant = Json {},
            strict = Json { useAlternativeNames = false },
            expectedRule = Rules.CONFIG_READER_STRICTNESS,
        )
    }

    @Test
    fun `disabling coerceInputValues makes an out-of-domain enum value throw — backward WARN`() {
        assertReaderToleranceObservable(
            serializer<CoerceCfg>(),
            rawPayload = """{"e":"NOPE"}""",
            tolerant = Json { coerceInputValues = true },
            strict = Json {},
            expectedRule = Rules.CONFIG_COERCE_INPUT,
        )
    }

    // --- DISCRIMINATOR_CHANGED via @JsonClassDiscriminator ---------------------

    @Serializable
    @SerialName("Disc")
    @JsonClassDiscriminator("type")
    private sealed interface DiscV1 {
        @Serializable
        @SerialName("a")
        data class A(
            val x: Int,
        ) : DiscV1
    }

    @Serializable
    @SerialName("Disc")
    @JsonClassDiscriminator("kind")
    private sealed interface DiscV2 {
        @Serializable
        @SerialName("a")
        data class A(
            val x: Int,
        ) : DiscV2
    }

    @Test
    fun `changing a type's @JsonClassDiscriminator key breaks decoding both ways`() {
        assertOracleAgrees(
            serializer<DiscV1>(),
            DiscV1.A(1),
            serializer<DiscV2>(),
            DiscV2.A(1),
            oldJson = Json {},
            newJson = Json {},
        )
    }
}
