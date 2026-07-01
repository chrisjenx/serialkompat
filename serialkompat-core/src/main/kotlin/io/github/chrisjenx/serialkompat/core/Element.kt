package io.github.chrisjenx.serialkompat.core

/**
 * A single element (field) of a [ContractKind.CLASS] contract, recording the
 * compatibility-bearing facts about how it appears on the JSON wire.
 *
 * `jsonNames` is normalized to sorted order so that equality is independent of
 * annotation declaration order.
 *
 * @property name the JSON key, post-`@SerialName` and post-`namingStrategy`.
 * @property type the canonical type-reference string (a primitive/structural
 *   type such as `List<String>`, or the serial name of a referenced contract).
 * @property optional whether the element may be omitted from the wire —
 *   straight from `SerialDescriptor.isElementOptional`, never re-derived.
 * @property nullable whether the element accepts a JSON `null`.
 * @property jsonNames additional accepted input keys from `@JsonNames`.
 * @property encodeDefault an explicit `@EncodeDefault` mode, or `null` if absent.
 */
public class Element(
    public val name: String,
    public val type: String,
    public val optional: Boolean = false,
    public val nullable: Boolean = false,
    jsonNames: List<String> = emptyList(),
    public val encodeDefault: EncodeDefaultMode? = null,
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
            encodeDefault == other.encodeDefault
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + optional.hashCode()
        result = 31 * result + nullable.hashCode()
        result = 31 * result + jsonNames.hashCode()
        result = 31 * result + (encodeDefault?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "Element(name=$name, type=$type, optional=$optional, nullable=$nullable, " +
            "jsonNames=$jsonNames, encodeDefault=$encodeDefault)"
}
