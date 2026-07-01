package io.github.chrisjenx.serialkompat.core

/**
 * Named compatibility rules. Each maps a kind of [Change] to a greppable,
 * individually-suppressible identifier used in findings and the exceptions file
 * (design §7).
 */
public object Rules {
    public const val CONTRACT_REMOVED: String = "CONTRACT_REMOVED"
    public const val PROPERTY_ADDED: String = "PROPERTY_ADDED"
    public const val PROPERTY_REMOVED: String = "PROPERTY_REMOVED"
    public const val PROPERTY_OPTIONALITY: String = "PROPERTY_OPTIONALITY"
    public const val PROPERTY_NULLABILITY: String = "PROPERTY_NULLABILITY"
    public const val PROPERTY_TYPE_CHANGED: String = "PROPERTY_TYPE_CHANGED"
    public const val ENUM_VALUE_ADDED: String = "ENUM_VALUE_ADDED"
    public const val ENUM_VALUE_REMOVED: String = "ENUM_VALUE_REMOVED"
    public const val SUBTYPE_ADDED: String = "SUBTYPE_ADDED"
    public const val SUBTYPE_REMOVED: String = "SUBTYPE_REMOVED"
    public const val DISCRIMINATOR_CHANGED: String = "DISCRIMINATOR_CHANGED"
    public const val CONFIG_CHANGED: String = "CONFIG_CHANGED"
}

/**
 * A classified compatibility judgment about a single [Change] in a single
 * [direction]: the [rule] it triggered, its [severity], and human-facing text.
 *
 * [direction] is always [CompatibilityDirection.BACKWARD] or
 * [CompatibilityDirection.FORWARD] — never `FULL` — because a `FULL` check is
 * evaluated as its two constituent directions, each of which can differ.
 */
public data class Finding(
    val rule: String,
    val direction: CompatibilityDirection,
    val severity: Severity,
    /** Serial name of the affected contract (or the config key for config changes). */
    val contract: String,
    /** What changed, e.g. `field 'amount'`. */
    val detail: String,
    /** Human explanation of the change and why it matters in this direction. */
    val message: String,
    /** A concrete suggestion for keeping compatibility, if any. */
    val fixHint: String?,
    /** The underlying structural change this finding was derived from. */
    val change: Change,
)
