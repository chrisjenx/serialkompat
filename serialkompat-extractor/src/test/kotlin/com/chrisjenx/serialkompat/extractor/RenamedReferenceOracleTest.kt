package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.CompatibilityEngine
import com.chrisjenx.serialkompat.core.Report
import com.chrisjenx.serialkompat.core.Rules
import com.chrisjenx.serialkompat.core.Severity
import com.chrisjenx.serialkompat.core.TransitiveCompatibility
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Oracle for #248: a declared rename is followed into the fields that reference the renamed type,
 * including a custom primitive serializer's descriptor name (never a contract, only a type
 * reference). Every verdict here is checked against a real round-trip under kotlinx-serialization.
 */
class RenamedReferenceOracleTest {
    class Zone(
        val id: String,
    )

    /** A STRING serializer whose only difference from [PlatformZoneSerializer] is its descriptor name. */
    object CachedZoneSerializer : KSerializer<Zone> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("com.example.CachedTimeZone", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Zone,
        ) = encoder.encodeString(value.id)

        override fun deserialize(decoder: Decoder): Zone = Zone(decoder.decodeString())
    }

    object PlatformZoneSerializer : KSerializer<Zone> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("kotlinx.datetime.TimeZone", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Zone,
        ) = encoder.encodeString(value.id)

        override fun deserialize(decoder: Decoder): Zone = Zone(decoder.decodeString())
    }

    /** Also STRING, but a different text format ("tz:<id>") that rejects anything else. */
    object PrefixedZoneSerializer : KSerializer<Zone> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("com.example.PrefixedZone", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: Zone,
        ) = encoder.encodeString("tz:" + value.id)

        override fun deserialize(decoder: Decoder): Zone {
            val raw = decoder.decodeString()
            require(raw.startsWith("tz:")) { "not a prefixed zone: $raw" }
            return Zone(raw.removePrefix("tz:"))
        }
    }

    @Serializable
    @SerialName("com.example.Event")
    data class EventCached(
        @Serializable(with = CachedZoneSerializer::class) val zone: Zone,
    )

    @Serializable
    @SerialName("com.example.Event")
    data class EventPlatform(
        @Serializable(with = PlatformZoneSerializer::class) val zone: Zone,
    )

    @Serializable
    @SerialName("com.example.Event")
    data class EventPrefixed(
        @Serializable(with = PrefixedZoneSerializer::class) val zone: Zone,
    )

    @Serializable
    @SerialName("com.old.Order")
    data class OldOrder(
        val id: String,
    )

    @Serializable
    @SerialName("com.new.Order")
    data class NewOrder(
        val id: String,
    )

    @Serializable
    @SerialName("com.example.Wrapper")
    data class WrapperOld(
        val order: OldOrder,
        val history: List<OldOrder>,
    )

    @Serializable
    @SerialName("com.example.Wrapper")
    data class WrapperNew(
        val order: NewOrder,
        val history: List<NewOrder>,
    )

    private val json = Json

    private fun snap(serializer: KSerializer<*>) = DescriptorSnapshotExtractor.extract(listOf(serializer.descriptor))

    private fun Report.breaks(direction: CompatibilityDirection) =
        findings.filter { it.direction == direction && it.severity == Severity.BREAK }

    @Test
    fun `a renamed custom primitive serializer round-trips, and with the rename declared no break is predicted`() {
        val renames = mapOf("com.example.CachedTimeZone" to "kotlinx.datetime.TimeZone")
        val report =
            CompatibilityEngine.check(
                snap(EventCached.serializer()),
                snap(EventPlatform.serializer()),
                renames = renames,
            )

        // Real library: both directions decode the identical "Europe/London" string.
        val old = json.encodeToString(EventCached.serializer(), EventCached(Zone("Europe/London")))
        val new = json.encodeToString(EventPlatform.serializer(), EventPlatform(Zone("Europe/London")))
        assertEquals("Europe/London", json.decodeFromString(EventPlatform.serializer(), old).zone.id)
        assertEquals("Europe/London", json.decodeFromString(EventCached.serializer(), new).zone.id)
        assertTrue(report.findings.isEmpty(), report.findings.toString())
    }

    @Test
    fun `the same rename is honoured by the history check against a version recorded before it`() {
        val recorded = listOf(snap(EventCached.serializer()), snap(EventPlatform.serializer()))
        val current = snap(EventPlatform.serializer())
        val renames = mapOf("com.example.CachedTimeZone" to "kotlinx.datetime.TimeZone")
        assertTrue(TransitiveCompatibility.checkAgainstHistory(current, recorded).shouldFail(Severity.BREAK))
        assertTrue(TransitiveCompatibility.checkAgainstHistory(current, recorded, renames = renames).findings.isEmpty())
    }

    @Test
    fun `a same-kind serializer with another text format really breaks, so it is still a BREAK`() {
        // Both are PrimitiveKind.STRING, but old data does not decode: same kind is not same wire.
        val report = CompatibilityEngine.check(snap(EventCached.serializer()), snap(EventPrefixed.serializer()))
        val old = json.encodeToString(EventCached.serializer(), EventCached(Zone("Europe/London")))
        assertFailsWith<IllegalArgumentException> { json.decodeFromString(EventPrefixed.serializer(), old) }
        assertTrue(report.breaks(CompatibilityDirection.BACKWARD).any { it.rule == Rules.PROPERTY_TYPE_CHANGED })
    }

    @Test
    fun `fields referencing a renamed contract round-trip, and are not reported once the rename is declared`() {
        val before = snap(WrapperOld.serializer())
        val after = snap(WrapperNew.serializer())
        val old = json.encodeToString(WrapperOld.serializer(), WrapperOld(OldOrder("1"), listOf(OldOrder("2"))))
        val new = json.encodeToString(WrapperNew.serializer(), WrapperNew(NewOrder("1"), listOf(NewOrder("2"))))
        assertEquals(
            "2",
            json
                .decodeFromString(WrapperNew.serializer(), old)
                .history
                .single()
                .id,
        )
        assertEquals(
            "2",
            json
                .decodeFromString(WrapperOld.serializer(), new)
                .history
                .single()
                .id,
        )

        val undeclared = CompatibilityEngine.check(before, after)
        assertTrue(undeclared.breaks(CompatibilityDirection.BACKWARD).isNotEmpty())
        val declared = CompatibilityEngine.check(before, after, renames = mapOf("com.old.Order" to "com.new.Order"))
        assertTrue(declared.findings.isEmpty(), declared.findings.toString())
    }
}
