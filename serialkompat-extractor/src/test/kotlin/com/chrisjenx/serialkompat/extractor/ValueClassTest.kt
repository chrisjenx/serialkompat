package com.chrisjenx.serialkompat.extractor

import com.chrisjenx.serialkompat.core.Change
import com.chrisjenx.serialkompat.core.ContractKind
import com.chrisjenx.serialkompat.core.Snapshot
import com.chrisjenx.serialkompat.core.SnapshotDiffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@JvmInline value class`es are transparent on the JSON wire: a serializable inline class
 * serializes as its single underlying value, never as a wrapper object. The extractor must
 * therefore record a value-class-typed element by its *underlying* type, so that swapping a
 * raw primitive for a wire-identical value class (or back) is not misreported as a breaking
 * type change (design §14).
 */
@OptIn(ExperimentalSerializationApi::class)
class ValueClassTest {
    @Serializable
    @JvmInline
    private value class UserId(
        val raw: Int,
    )

    @Serializable
    @JvmInline
    private value class Email(
        val raw: String,
    )

    @Serializable
    @JvmInline
    private value class Millis(
        val raw: Long,
    )

    @Serializable
    @SerialName("Timed")
    private data class Timed(
        val at: Millis,
    )

    @Test
    fun `a value class over Long records the underlying Long`() {
        assertEquals("kotlin.Long", extract(serializer<Timed>().descriptor).element("Timed", "at").type)
    }

    @Serializable
    @SerialName("Account")
    private data class Account(
        val id: UserId,
        val contact: Email,
    )

    // Same wire shape as Account, but with the underlying primitives inlined by hand.
    @Serializable
    @SerialName("Account")
    private data class AccountRaw(
        val id: Int,
        val contact: String,
    )

    @Serializable
    @SerialName("Keyed")
    private data class Keyed(
        val byUser: Map<UserId, String>,
    )

    @Serializable
    @SerialName("Nullable")
    private data class NullableHolder(
        val id: UserId?,
    )

    private fun extract(
        vararg types: SerialDescriptor,
        module: SerializersModule = SerializersModule {},
    ): Snapshot = DescriptorSnapshotExtractor.extract(types.toList(), module)

    private fun Snapshot.element(
        contract: String,
        name: String,
    ) = contracts.single { it.serialName == contract }.elements.single { it.name == name }

    @Test
    fun `a value-class field records the underlying primitive type`() {
        val snapshot = extract(serializer<Account>().descriptor)
        assertEquals("kotlin.Int", snapshot.element("Account", "id").type)
        assertEquals("kotlin.String", snapshot.element("Account", "contact").type)
    }

    @Test
    fun `swapping a raw primitive for a wire-identical value class is not a type change`() {
        val withValueClasses = extract(serializer<Account>().descriptor)
        val withRawPrimitives = extract(serializer<AccountRaw>().descriptor)

        val backward = SnapshotDiffer.diff(withRawPrimitives, withValueClasses)
        val forward = SnapshotDiffer.diff(withValueClasses, withRawPrimitives)

        assertTrue(
            backward.none { it is Change.ElementTypeChanged },
            "value-class <-> raw primitive is wire-identical; got $backward",
        )
        assertTrue(
            forward.none { it is Change.ElementTypeChanged },
            "value-class <-> raw primitive is wire-identical; got $forward",
        )
    }

    @Test
    fun `a value class used as a Map key records the underlying key type`() {
        val snapshot = extract(serializer<Keyed>().descriptor)
        assertEquals("Map<kotlin.Int,kotlin.String>", snapshot.element("Keyed", "byUser").type)
    }

    @Test
    fun `a nullable value-class field keeps nullability and unwraps the type`() {
        val snapshot = extract(serializer<NullableHolder>().descriptor)
        val id = snapshot.element("Nullable", "id")
        assertEquals("kotlin.Int", id.type)
        assertEquals(true, id.nullable)
    }

    @Test
    fun `value class and raw primitive produce byte-identical JSON (oracle)`() {
        // Ground-truth against the real library: the whole reason unwrapping is safe is that the
        // two shapes are indistinguishable on the wire. Prove it, and prove each decodes under the
        // other's schema (the exact cross-version reads the gate must not flag as breaking).
        val json = Json
        val fromValueClasses = json.encodeToString(Account.serializer(), Account(UserId(7), Email("a@b.co")))
        val fromRawPrimitives = json.encodeToString(AccountRaw.serializer(), AccountRaw(7, "a@b.co"))
        assertEquals(fromRawPrimitives, fromValueClasses)

        // Cross-decode both directions: neither read throws.
        json.decodeFromString(AccountRaw.serializer(), fromValueClasses)
        json.decodeFromString(Account.serializer(), fromRawPrimitives)
    }

    @Test
    fun `a value class is not walked as a spurious named contract`() {
        val snapshot = extract(serializer<Account>().descriptor)
        // The value classes are transparent — they must not appear as their own contracts.
        val names = snapshot.contracts.map { it.serialName }
        assertTrue(
            names.none { it.endsWith("UserId") || it.endsWith("Email") },
            "value classes should be inlined, not emitted as contracts; got $names",
        )
        // Only Account itself is a contract.
        assertEquals(setOf("Account"), names.toSet())
        assertEquals(ContractKind.CLASS, snapshot.contracts.single().kind)
    }
}
