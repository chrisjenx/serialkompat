package com.chrisjenx.serialkompat.core

/**
 * A single element (field) of a [ContractKind.CLASS] contract, recording the
 * compatibility-bearing facts about how it appears on the wire.
 *
 * `jsonNames` is normalized to sorted order so that equality is independent of
 * annotation declaration order. Tag lists keep their order: CBOR writes and verifies tags in
 * sequence.
 *
 * @property name the wire key, post-`@SerialName`. A `Json` `namingStrategy` is recorded on the
 *   config, not applied here.
 * @property type the canonical type-reference string (a primitive/structural
 *   type such as `List<String>`, or the serial name of a referenced contract).
 * @property optional whether the element may be omitted from the wire —
 *   straight from `SerialDescriptor.isElementOptional`, never re-derived.
 * @property nullable whether the element accepts a `null`.
 * @property jsonNames additional accepted input keys from `@JsonNames` (JSON only).
 * @property encodeDefault the field's `@EncodeDefault` mode ([EncodeDefaultMode.ABSENT] when
 *   verified to carry none), or `null` when it was not recorded and is therefore unknown (#158).
 * @property cborLabel CBOR: the field's `@CborLabel` integer key, or `null` for none.
 * @property byteString CBOR: whether the field carries `@ByteString` (a `ByteArray` as a byte string).
 * @property keyTags CBOR: the field's `@KeyTags`, in order.
 * @property valueTags CBOR: the field's `@ValueTags`, in order.
 */
public class Element(
    public val name: String,
    public val type: String,
    public val optional: Boolean = false,
    public val nullable: Boolean = false,
    jsonNames: List<String> = emptyList(),
    public val encodeDefault: EncodeDefaultMode? = null,
    public val cborLabel: Long? = null,
    public val byteString: Boolean = false,
    public val keyTags: List<ULong> = emptyList(),
    public val valueTags: List<ULong> = emptyList(),
) {
    public val jsonNames: List<String> = jsonNames.sorted()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Element) return false
        return name == other.name &&
            type == other.type &&
            optional == other.optional &&
            nullable == other.nullable &&
            jsonNames == other.jsonNames &&
            encodeDefault == other.encodeDefault &&
            cborLabel == other.cborLabel &&
            byteString == other.byteString &&
            keyTags == other.keyTags &&
            valueTags == other.valueTags
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + optional.hashCode()
        result = 31 * result + nullable.hashCode()
        result = 31 * result + jsonNames.hashCode()
        result = 31 * result + (encodeDefault?.hashCode() ?: 0)
        result = 31 * result + (cborLabel?.hashCode() ?: 0)
        result = 31 * result + byteString.hashCode()
        result = 31 * result + keyTags.hashCode()
        result = 31 * result + valueTags.hashCode()
        return result
    }

    override fun toString(): String =
        "Element(name=$name, type=$type, optional=$optional, nullable=$nullable, " +
            "jsonNames=$jsonNames, encodeDefault=$encodeDefault, cborLabel=$cborLabel, " +
            "byteString=$byteString, keyTags=$keyTags, valueTags=$valueTags)"
}
