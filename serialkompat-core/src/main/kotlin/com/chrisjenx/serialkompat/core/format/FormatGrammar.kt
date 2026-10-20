package com.chrisjenx.serialkompat.core.format

/** The canonical text grammar's literal tokens, shared by the writer, reader, and mappers so a change to any marker/separator/key name cannot drift between emit and parse. */
internal object FormatGrammar {
    const val CONTRACT_MARKER = "@contract"
    const val CONFIG_MARKER = "@config"
    const val SUBTYPES_MARKER = "subtypes:"
    const val FIELD_SEP = ": "
    const val ARROW = " -> "
    const val KEY_KIND = "kind"
    const val KEY_BASE = "base"
    const val KEY_DISCRIMINATOR = "discriminator"
    const val KEY_POLYMORPHIC_DEFAULT = "polymorphicDefault"
    const val KEY_ENCODE_DEFAULT = "encodeDefault"
    const val KEY_VALUES = "values"
    const val KEY_JSON_NAMES = "jsonNames"

    /** `@config` key naming the wire format; absent means JSON (every pre-CBOR snapshot). */
    const val KEY_FORMAT = "format"

    // CBOR facts, emitted only when set so non-CBOR snapshots are unchanged.
    const val KEY_CBOR_ARRAY = "cborArray"
    const val KEY_OBJECT_TAGS = "objectTags"
    const val KEY_CBOR_LABEL = "cborLabel"
    const val KEY_KEY_TAGS = "keyTags"
    const val KEY_VALUE_TAGS = "valueTags"
    const val FLAG_BYTE_STRING = "byteString"

    /** Header keys whose value is a `[a,b]` list rather than a single value. */
    val HEADER_LIST_KEYS = setOf(KEY_CBOR_ARRAY, KEY_OBJECT_TAGS)

    /** Element keys whose value is a `[a,b]` list rather than a single value. */
    val ELEMENT_LIST_KEYS = setOf(KEY_JSON_NAMES, KEY_KEY_TAGS, KEY_VALUE_TAGS)
    const val FLAG_OPTIONAL = "optional"
    const val FLAG_NULLABLE = "nullable"
}
