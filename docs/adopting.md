# Adopting gradually

Bring serialkompat into an existing codebase without listing every wire type up front, using
discovery modes and two annotations. It is for teams that don't yet know which `@Serializable`
types are wire contracts. On your very first PR, start at [Your first PR](quickstart.md#first-time-adoption).

## Discovery modes { #discovery-modes }

`discovery` only matters when `types` is empty. It decides which of the discovered
`@Serializable` types get checked:

| Mode | Checked types | Use when |
|---|---|---|
| `EXPLICIT` (default) | Only `types` | You maintain an explicit list of root types |
| `OPT_OUT` | Everything discovered, minus types annotated `@SerialkompatIgnore` | Most types are wire contracts; a few (internal-only, unstable) opt out |
| `OPT_IN` | Only types annotated `@SerialkompatChecked` | Gradual adoption. Nothing is checked until you annotate it |

Discovery scans your module's compiled classes for class-level `@Serializable`. The
annotations live in a small multiplatform artifact:

```kotlin
dependencies {
    implementation("com.chrisjenx:serialkompat-annotations:{{ skversion }}")
}
```

Put `com.chrisjenx.serialkompat.annotations.SerialkompatIgnore` or
`com.chrisjenx.serialkompat.annotations.SerialkompatChecked` on the `@Serializable` class
itself.

**Precedence.** These rules apply in this order, in every mode:

1. A non-empty `types` list always wins. `discovery` is only consulted when `types` is empty.
2. Annotations filter the **scanned** set only. Types listed in a classpath manifest
   (`META-INF/serialkompat/serializable-types.txt`) skip annotation filtering and are always
   included in `OPT_OUT` and `OPT_IN`.
3. `include` and `exclude` prefixes apply after discovery, in all modes. See
   [Monorepos](monorepos.md).

**Kotlin Multiplatform:** a KMP module works with discovery and extraction as long as it
declares a `jvm()` target. Extraction reads compiled JVM serializers, so a JVM target is
required. Annotate your models in `commonMain`. `serialkompat-annotations` is itself
multiplatform, so the annotations are available there.

## Start with nothing checked { #gradual-adoption-with-discovery-modes }

Switch to `OPT_IN` with no `types` list:

```kotlin title="build.gradle.kts"
--8<-- "docs-samples/snippets/adopting-opt-in.gradle.kts"
```

Nothing is checked yet. As you review each wire type, annotate it:

```kotlin
--8<-- "docs-samples/src/main/kotlin/com/example/OrderEvent.kt:order-event"
```

Each newly annotated type joins the gate on its next run. You don't touch the plugin config
or maintain a `types` list by hand.

!!! note "The first annotated type"
    The PR that annotates your *first* type hits the empty-baseline guard from
    [Your first PR](quickstart.md#first-time-adoption). The baseline on `main` still has zero
    types, so the change looks like "everything was just added". Use the same fix: set
    `failOnEmptyBaseline.set(false)` for that one PR, then remove it once `baselineRef`
    includes it.

## Flip to opt-out { #flip-to-opt-out }

When coverage is essentially complete, switch to `DiscoveryMode.OPT_OUT` so every discovered
type is checked, and mark the few intentionally unstable types with `@SerialkompatIgnore`:

```kotlin title="build.gradle.kts"
--8<-- "docs-samples/snippets/adopting-opt-out.gradle.kts"
```

```kotlin
--8<-- "docs-samples/src/main/kotlin/com/example/DebugDump.kt:debug-dump"
```

Both modes fail safe. `OPT_IN` never checks a type you haven't reviewed. `OPT_OUT` never
skips a type just because you forgot to annotate it. Adding or removing an annotation takes
effect on the next run.

## Next { #next }

- [Configuration](configuration.md): the full `serialkompat { }` DSL.
- [Monorepos](monorepos.md): scope each module's check.
- [Persisted data & history](history.md): check against every recorded release.
