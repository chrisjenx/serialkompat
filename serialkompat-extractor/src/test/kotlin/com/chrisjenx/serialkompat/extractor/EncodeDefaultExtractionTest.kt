package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.EncodeDefaultMode
import com.chrisjenx.serialkompat.core.Snapshot
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #158: `@EncodeDefault` is not a `@SerialInfo` annotation, so the descriptor never exposes it —
 * but it is RUNTIME-retained on the property's synthetic `get<Name>$annotations` method, so the
 * extractor recovers it by reflecting on the plugin-generated serializer's owning class. Optional
 * elements get ALWAYS / NEVER / ABSENT on positive evidence only; anything the extractor cannot
 * resolve stays `null` (unknown), which the classifier never treats as ABSENT.
 */
@OptIn(ExperimentalSerializationApi::class)
class EncodeDefaultExtractionTest {
    @Serializable
    @SerialName("Modes")
    private data class Modes(
        val id: String,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val always: String = "a",
        @EncodeDefault val bare: String = "b",
        @EncodeDefault(EncodeDefault.Mode.NEVER) val never: String = "n",
        val plain: String = "p",
        val nullablePlain: String? = null,
        @SerialName("renamed") @EncodeDefault(EncodeDefault.Mode.NEVER) val renamedNever: String = "r",
        @SerialName("renamed_plain") val renamedPlain: String = "rp",
        @EncodeDefault(EncodeDefault.Mode.NEVER) val isActive: Boolean = false,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val URL: String = "u",
        @EncodeDefault(EncodeDefault.Mode.NEVER) private val secret: String = "s",
    )

    @Serializable
    @SerialName("Base")
    private abstract class Base {
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        var inherited: String = "i"
        var inheritedPlain: String = "ip"
    }

    @Serializable
    @SerialName("Child")
    private class Child(
        val own: String = "o",
    ) : Base()

    @Serializable
    @SerialName("JvmNamed")
    private data class JvmNamed(
        @get:JvmName("fetchNote") @EncodeDefault(EncodeDefault.Mode.NEVER) val note: String = "n",
        val other: String = "o",
    )

    /** A hand-written serializer: no plugin-generated owner to reflect on. */
    private object HandWritten : KSerializer<String> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("HandWritten") {
                element("x", serializer<String>().descriptor, isOptional = true)
            }

        override fun serialize(
            encoder: Encoder,
            value: String,
        ): Unit = error("unused")

        override fun deserialize(decoder: Decoder): String = error("unused")
    }

    private fun extract(descriptor: SerialDescriptor): Snapshot =
        DescriptorSnapshotExtractor.extract(listOf(descriptor))

    private fun Snapshot.mode(
        contract: String,
        element: String,
    ): EncodeDefaultMode? =
        contracts
            .single { it.serialName == contract }
            .elements
            .single { it.name == element }
            .encodeDefault

    @Test
    fun `explicit modes are recovered, including the bare annotation's ALWAYS default`() {
        val s = extract(serializer<Modes>().descriptor)
        assertEquals(EncodeDefaultMode.ALWAYS, s.mode("Modes", "always"))
        assertEquals(EncodeDefaultMode.ALWAYS, s.mode("Modes", "bare"))
        assertEquals(EncodeDefaultMode.NEVER, s.mode("Modes", "never"))
    }

    @Test
    fun `an inspected optional field without the annotation is ABSENT`() {
        val s = extract(serializer<Modes>().descriptor)
        assertEquals(EncodeDefaultMode.ABSENT, s.mode("Modes", "plain"))
        assertEquals(EncodeDefaultMode.ABSENT, s.mode("Modes", "nullablePlain"))
    }

    @Test
    fun `@SerialName-renamed properties are matched by their serial name`() {
        val s = extract(serializer<Modes>().descriptor)
        assertEquals(EncodeDefaultMode.NEVER, s.mode("Modes", "renamed"))
        assertEquals(EncodeDefaultMode.ABSENT, s.mode("Modes", "renamed_plain"))
    }

    @Test
    fun `is-prefixed, all-caps and private properties are matched`() {
        val s = extract(serializer<Modes>().descriptor)
        assertEquals(EncodeDefaultMode.NEVER, s.mode("Modes", "isActive"))
        assertEquals(EncodeDefaultMode.ALWAYS, s.mode("Modes", "URL"))
        assertEquals(EncodeDefaultMode.NEVER, s.mode("Modes", "secret"))
    }

    @Test
    fun `properties inherited from a serializable superclass are matched`() {
        val s = extract(serializer<Child>().descriptor)
        assertEquals(EncodeDefaultMode.NEVER, s.mode("Child", "inherited"))
        assertEquals(EncodeDefaultMode.ABSENT, s.mode("Child", "inheritedPlain"))
        assertEquals(EncodeDefaultMode.ABSENT, s.mode("Child", "own"))
    }

    @Test
    fun `a required field records no mode — @EncodeDefault only affects defaulted fields`() {
        assertNull(extract(serializer<Modes>().descriptor).mode("Modes", "id"))
    }

    @Test
    fun `a renamed getter never lets a NEVER field read as ABSENT`() {
        // Whatever Kotlin names the annotation holder under @get:JvmName, the NEVER on `note` must
        // either be matched or leave the class's field-only matches unknown — never ABSENT.
        val s = extract(serializer<JvmNamed>().descriptor)
        val note = s.mode("JvmNamed", "note")
        assertTrue(note == EncodeDefaultMode.NEVER || note == null, "note was $note")
        if (note == null) assertNull(s.mode("JvmNamed", "other"))
    }

    @Test
    fun `a descriptor with no plugin-generated owner stays unknown, never ABSENT`() {
        assertNull(extract(HandWritten.descriptor).mode("HandWritten", "x"))
    }
}
