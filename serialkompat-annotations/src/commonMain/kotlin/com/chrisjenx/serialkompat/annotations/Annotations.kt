package com.chrisjenx.serialkompat.annotations

/**
 * Excludes a discovered `@Serializable` type from the serialkompat compatibility gate
 * when discovery runs in `OPT_OUT` mode (issue #115).
 *
 * `RUNTIME` retention is load-bearing: the extractor's class-file scanner reads the
 * `RuntimeVisibleAnnotations` attribute directly — no classloading — so the marker
 * must survive into the compiled class file's visible-annotations table.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SerialkompatIgnore

/**
 * Opts a `@Serializable` type into the serialkompat compatibility gate when discovery
 * runs in `OPT_IN` mode — the gradual-adoption marker (issue #115).
 *
 * `RUNTIME` retention is load-bearing: see [SerialkompatIgnore].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class SerialkompatChecked
