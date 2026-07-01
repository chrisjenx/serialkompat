package io.github.chrisjenx.serialkompat.gradle

import io.github.chrisjenx.serialkompat.core.CompatibilityDirection
import org.gradle.api.provider.ListProperty
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

    /** Serial-name prefixes to include in the check (default: everything). */
    public abstract val include: ListProperty<String>

    /** Serial-name prefixes to exclude from the check. */
    public abstract val exclude: ListProperty<String>
}
