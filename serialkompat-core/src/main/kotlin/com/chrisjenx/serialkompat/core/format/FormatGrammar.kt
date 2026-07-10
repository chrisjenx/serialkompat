package com.chrisjenx.serialkompat.core.format

/** The canonical text grammar's literal tokens, shared by the writer, reader, and mappers so a change to any marker/separator/key name cannot drift between emit and parse. */
internal object FormatGrammar {
    const val CONTRACT_MARKER = "@contract"
    const val CONFIG_MARKER = "@config"
    const val SUBTYPES_MARKER = "subtypes:"
    const val FIELD_SEP = ": "
    const val ARROW = " -> "
    const val KEY_KIND = "kind"
    const val KEY_DISCRIMINATOR = "discriminator"
    const val KEY_POLYMORPHIC_DEFAULT = "polymorphicDefault"
    const val KEY_ENCODE_DEFAULT = "encodeDefault"
    const val KEY_VALUES = "values"
    const val KEY_JSON_NAMES = "jsonNames"
}
