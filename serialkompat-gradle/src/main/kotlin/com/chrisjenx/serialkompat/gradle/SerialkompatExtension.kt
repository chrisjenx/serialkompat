package com.chrisjenx.serialkompat.gradle

import com.chrisjenx.serialkompat.core.CompatibilityDirection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configures the `serialkompat { … }` block (design §6, §9). Policy that can't be
 * inferred from code (direction, baseline ref, scope) stays declared here; the
 * wire config itself is read from the real [jsonInstance].
 */
public abstract class SerialkompatExtension {
    /** Fully-qualified names of the `@Serializable` root types to check. */
    public abstract val types: ListProperty<String>

    /** FQN of a `Json` instance whose configuration describes the wire (e.g. `com.acme.WireJson.instance`). */
    public abstract val jsonInstance: Property<String>

    /** The git ref the current schema is checked against (e.g. `origin/main`). */
    public abstract val baselineRef: Property<String>

    /** Direction(s) of compatibility to enforce. Defaults to `FULL`. */
    public abstract val direction: Property<CompatibilityDirection>

    /** Whether a breaking change fails the build. Defaults to `true`. */
    public abstract val failOnBreaking: Property<Boolean>

    /**
     * Whether a baseline that produced **zero** contracts (while the current schema has some) fails
     * the build. Defaults to `true`: a degenerate baseline would otherwise diff as "everything added
     * → safe" and silently mask removals. Set `false` for first-time adoption, where the baseline ref
     * legitimately has no `@Serializable` types yet.
     */
    public abstract val failOnEmptyBaseline: Property<Boolean>

    /** Serial-name prefixes to include in the check (default: everything). */
    public abstract val include: ListProperty<String>

    /** Serial-name prefixes to exclude from the check. */
    public abstract val exclude: ListProperty<String>

    /**
     * Breaks the team has explicitly sanctioned — each downgraded from failing to
     * *acknowledged*. Each entry is `"<serialName> <RULE> [DIRECTION]"`, e.g.
     * `"com.example.Order PROPERTY_REMOVED"` or
     * `"com.example.Order PROPERTY_REMOVED FORWARD"` (omit the direction to accept both).
     */
    public abstract val acceptedBreaks: ListProperty<String>

    /**
     * Declared serial-name moves, old → new, so a rename is diffed as a moved
     * contract (its contents compared) rather than a spurious remove + add.
     */
    public abstract val renames: MapProperty<String, String>
}
