package com.chrisjenx.serialkompat.gradle.history

/**
 * Orders dotted version strings by **semantic version**, not lexicographically
 * (so `1.9.0` < `1.10.0`). Shared by the history store's load ordering and by
 * retention's `sinceVersion` bound so both agree on what "older" means.
 *
 * It is a **total order**: each dotted component is compared numeric-vs-numeric,
 * else a numeric component precedes a non-numeric one, else lexically — so a set
 * mixing numeric and alphanumeric components can't produce a cycle (which would
 * trip TimSort's contract check). Missing trailing components count as `0`, so
 * `1.0` and `1.0.0` compare equal. A prerelease (`-suffix`) sorts *before* its
 * final release.
 */
internal object VersionOrder : Comparator<String> {
    override fun compare(
        a: String,
        b: String,
    ): Int {
        val (aCore, aPre) = splitPrerelease(a)
        val (bCore, bPre) = splitPrerelease(b)
        val coreCmp = compareCore(aCore, bCore)
        return when {
            coreCmp != 0 -> coreCmp
            aPre == null && bPre == null -> 0
            aPre == null -> 1 // a is the final release, b is a prerelease ⇒ a is greater
            bPre == null -> -1
            else -> aPre.compareTo(bPre)
        }
    }

    private fun splitPrerelease(version: String): Pair<String, String?> {
        val dash = version.indexOf('-')
        return if (dash < 0) version to null else version.substring(0, dash) to version.substring(dash + 1)
    }

    private fun compareCore(
        a: String,
        b: String,
    ): Int {
        val aParts = a.split(".")
        val bParts = b.split(".")
        for (i in 0 until maxOf(aParts.size, bParts.size)) {
            val cmp = compareComponent(aParts.getOrNull(i) ?: "0", bParts.getOrNull(i) ?: "0")
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** Total order on one component: numeric < numeric, numeric before non-numeric, else lexical. */
    private fun compareComponent(
        a: String,
        b: String,
    ): Int {
        val an = a.toIntOrNull()
        val bn = b.toIntOrNull()
        return when {
            an != null && bn != null -> an.compareTo(bn)
            an != null -> -1
            bn != null -> 1
            else -> a.compareTo(b)
        }
    }
}
