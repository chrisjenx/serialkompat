package com.chrisjenx.serialkompat.extractor

/**
 * How the set of checked root types is decided when none are listed explicitly
 * (issue #115). Filtering applies to *scanned* types only: classpath-manifest
 * entries and explicit type lists are deliberate acts and always win.
 */
public enum class DiscoveryMode {
    /** Only explicitly-listed types are checked — the compatible default. */
    EXPLICIT,

    /** Every discovered type is checked, minus scanned types carrying `@SerialkompatIgnore`. */
    OPT_OUT,

    /** Only scanned types carrying `@SerialkompatChecked` are checked (gradual adoption). */
    OPT_IN,

    ;

    public companion object {
        /** Parses the CLI form (`explicit|opt-out|opt-in`); fails loudly on anything else. */
        public fun fromCli(value: String): DiscoveryMode =
            when (value) {
                "explicit" -> EXPLICIT
                "opt-out" -> OPT_OUT
                "opt-in" -> OPT_IN
                else -> throw IllegalArgumentException(
                    "serialkompat: unknown --discovery '$value' (expected explicit|opt-out|opt-in)",
                )
            }
    }
}
