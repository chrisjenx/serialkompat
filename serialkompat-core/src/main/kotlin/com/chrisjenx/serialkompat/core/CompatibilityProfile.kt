package com.chrisjenx.serialkompat.core

/**
 * How much unknown-key tolerance to assume for the *reader* when classifying
 * (design §7). Reading the real `Json` config describes the sides you own; a
 * non-Kotlin peer's tolerance is unknown, so externally-facing scopes can pin
 * [STRICT] to override "what my own Json does".
 */
public enum class ReaderTolerance {
    /** Use the reader's actual `ignoreUnknownKeys` from its [SnapshotConfig]. */
    FROM_CONFIG,

    /** Assume a strict reader (throws on unknown keys) regardless of config. */
    STRICT,
}

/**
 * The policy a scope is checked under (design §7): which [direction] of
 * compatibility to enforce, and how tolerant the reader is assumed to be. This
 * is policy that cannot be inferred from code, so it stays declared.
 */
public data class CompatibilityProfile(
    val direction: CompatibilityDirection = CompatibilityDirection.FULL,
    val readerTolerance: ReaderTolerance = ReaderTolerance.FROM_CONFIG,
)
