package io.github.chrisjenx.serialkompat.gradle

import io.github.chrisjenx.serialkompat.core.AcceptedBreak
import io.github.chrisjenx.serialkompat.core.CompatibilityDirection

/**
 * Resolves the baseline ref to check against: an explicit `-Pserialkompat.ref`
 * value (as passed to `serialkompatCheckAgainst`) wins; a null or blank value
 * falls back to the [configured] `baselineRef`.
 */
internal fun resolveBaselineRef(
    property: Any?,
    configured: String,
): String = property?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: configured

/**
 * Parses an accepted-break spec from the `serialkompat { acceptedBreaks }` extension.
 * Format: `"<serialName> <RULE> [DIRECTION]"` — e.g. `"com.example.Order PROPERTY_REMOVED"`
 * or `"com.example.Order PROPERTY_REMOVED FORWARD"`. An omitted direction accepts both.
 */
internal fun parseAcceptedBreak(spec: String): AcceptedBreak {
    val parts = spec.trim().split(Regex("\\s+"))
    require(parts.size >= 2) {
        "serialkompat: acceptedBreak '$spec' must be '<serialName> <RULE> [BACKWARD|FORWARD]'"
    }
    val direction = parts.getOrNull(2)?.let { CompatibilityDirection.valueOf(it.uppercase()) }
    return AcceptedBreak(type = parts[0], rule = parts[1], direction = direction)
}
