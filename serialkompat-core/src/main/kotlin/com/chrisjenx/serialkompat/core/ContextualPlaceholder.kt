package com.chrisjenx.serialkompat.core

/**
 * The prefix of kotlinx-serialization's internal serial name for an unresolved `@Contextual` type
 * (`kotlinx.serialization.ContextualSerializer<Stamp>`). Snapshots recorded before contextual
 * resolution used it as the field type for every contextual field, plus an OPAQUE contract of that
 * name. Now it appears only for a type with no module entry and no fallback serializer.
 */
internal const val CONTEXTUAL_PLACEHOLDER: String = "kotlinx.serialization.ContextualSerializer<"

/** Whether [name] is an unresolved-contextual placeholder rather than a real wire type. */
internal fun isContextualPlaceholder(name: String): Boolean = name.startsWith(CONTEXTUAL_PLACEHOLDER)

/**
 * Whether a type change from [oldType] to [newType] is a placeholder being resolved: [oldType]
 * names a placeholder that [newType] no longer does. The baseline shape was never seen, so this is
 * a coverage gain, not a wire change. The reverse (resolved → placeholder) is a real break: the
 * reader lost its serializer.
 */
internal fun isContextualResolution(
    oldType: String,
    newType: String,
): Boolean {
    val placeholders = PLACEHOLDER_TOKEN.findAll(oldType).map { it.value }.toList()
    return placeholders.isNotEmpty() && placeholders.none { it in newType }
}

/** A placeholder token, e.g. `kotlinx.serialization.ContextualSerializer<Stamp>`. */
private val PLACEHOLDER_TOKEN = Regex(Regex.escape(CONTEXTUAL_PLACEHOLDER) + "[^<>,]*>")
