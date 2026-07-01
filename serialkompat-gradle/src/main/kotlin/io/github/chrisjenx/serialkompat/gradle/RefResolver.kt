package io.github.chrisjenx.serialkompat.gradle

/**
 * Resolves the baseline ref to check against: an explicit `-Pserialkompat.ref`
 * value (as passed to `serialkompatCheckAgainst`) wins; a null or blank value
 * falls back to the [configured] `baselineRef`.
 */
internal fun resolveBaselineRef(
    property: Any?,
    configured: String,
): String = property?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: configured
