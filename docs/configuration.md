# Configuration

The full `serialkompat { }` extension reference: every property, its default, and
what it controls. See [Quick start](quickstart.md) for the minimal version and
[Setup](setup.md) for the CLI/Action equivalents.

## `serialkompat { }` reference

| Property | Type | Default | Purpose |
|---|---|---|---|
| `types` | `ListProperty<String>` | `[]` (required under `EXPLICIT` discovery) | FQNs of `@Serializable` root types to check |
| `discovery` | `Property<DiscoveryMode>` | `EXPLICIT` | How checked types are found when `types` is empty: `EXPLICIT` (only `types`), `OPT_OUT` (everything discovered minus `@SerialkompatIgnore`), `OPT_IN` (only `@SerialkompatChecked`) |
| `jsonInstance` | `Property<String>` | empty | FQN of a `Json` instance describing the wire (e.g. `com.example.WireJson.instance`); empty = default `Json{}` |
| `baselineRef` | `Property<String>` | auto-detected | Git ref the current schema is checked against. Unset ⇒ auto-detect the default branch (`origin/HEAD` → `origin/main` → `origin/master` → local `main`/`master`) |
| `direction` | `Property<CompatibilityDirection>` | `FULL` | `BACKWARD`, `FORWARD`, or `FULL` |
| `failOnBreaking` | `Property<Boolean>` | `true` | A `BREAK` finding fails the build |
| `failOnEmptyBaseline` | `Property<Boolean>` | `true` | Empty baseline fails the build (prevents silently masking removed types); set `false` for first adoption |
| `include` | `ListProperty<String>` | `[""]` | Serial-name prefixes in scope (`""` = all) |
| `exclude` | `ListProperty<String>` | `[]` | Serial-name prefixes excluded |
| `acceptedBreaks` | `ListProperty<String>` | `[]` | Sanctioned breaks, format `"<serialName> <RULE> [DIRECTION]"` |
| `renames` | `MapProperty<String,String>` | `{}` | Declared serial-name moves old→new (avoids a remove+add pair reading as a break) |
| `history.dir` | `DirectoryProperty` | `serialkompat/history` | Source-controlled dir of recorded per-version snapshots for the transitive check ([Recipes](recipes.md#persisted-data-horizon-multi-version-history)) |
| `history.sinceVersion` | `Property<String>` | unset | Retention: only check against versions `>=` this (semver) |
| `history.depth` | `Property<Int>` | unset | Retention: only check against the newest N recorded versions |
| `history.maxAge` | `Property<Duration>` | unset | Retention: only check against versions recorded within this window. Combining bounds is most-permissive (union) |

The extension is config-cache safe — everything above is captured at configuration
time. `baselineRef` isn't a file on disk: the baseline schema is recomputed live
from that ref via a temporary git worktree on every run, so there's nothing to
regenerate or go stale.

## Discovery modes

`discovery` only matters when `types` is empty — it decides which of the
scanned/discovered `@Serializable` types actually get checked:

| Mode | Checked types | Use when |
|---|---|---|
| `EXPLICIT` (default) | Only `types` | You maintain an explicit root-type list — the default, unchanged behavior |
| `OPT_OUT` | Everything discovered, minus types annotated `@SerialkompatIgnore` | Most types are wire contracts; a few (internal-only, unstable) opt out |
| `OPT_IN` | Only types annotated `@SerialkompatChecked` | Gradual adoption — nothing is checked until you annotate it |

The annotations live in a small multiplatform artifact:

```kotlin
dependencies {
    implementation("com.chrisjenx:serialkompat-annotations:{{ skversion }}")
}
```

`com.chrisjenx.serialkompat.annotations.SerialkompatIgnore` and
`com.chrisjenx.serialkompat.annotations.SerialkompatChecked` go on the
`@Serializable` class itself:

```kotlin
@Serializable
@SerialkompatChecked
data class OrderEvent(val id: String)
```

**Precedence**, applied in this order, in every mode:

1. A non-empty `types` list always wins — `discovery` is only consulted when
   `types` is empty.
2. Annotations refine the **scanned** set only; classpath-manifest entries
   (`META-INF/serialkompat/serializable-types.txt`) bypass annotation
   filtering and are always included in `OPT_OUT`/`OPT_IN`.
3. `include`/`exclude` serial-name prefixes apply after discovery, in all
   modes — unchanged from before this feature.

```kotlin title="build.gradle.kts"
serialkompat {
    discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_OUT)
}
```

**KMP:** a Kotlin Multiplatform module is supported for discovery/extraction
when it declares a `jvm()` target — extraction reads compiled JVM descriptors,
so a JVM target is the floor, not an oversight. Annotate models in
`commonMain`; `serialkompat-annotations` is itself multiplatform, so the
annotation is visible there.

## Annotated example

!!! note
    serialkompat isn't on the Gradle Plugin Portal yet, so `plugins { id(…) }` won't
    resolve on its own — see [Setup](setup.md#gradle-plugin) for the `pluginManagement`
    block that points Gradle at Maven Central.

```kotlin title="build.gradle.kts"
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat") version "{{ skversion }}"
}

serialkompat {
    types.set(listOf( // (1)!
        "com.example.wire.OrderEvent",
        "com.example.wire.Payment",
    ))
    jsonInstance.set("com.example.wire.WireJson.instance") // (2)!
    baselineRef.set("origin/main") // (3)!
    direction.set(CompatibilityDirection.FULL) // (4)!
    failOnBreaking.set(true) // (5)!
    failOnEmptyBaseline.set(true) // (6)!
    include.set(listOf("com.example.wire")) // (7)!
    exclude.set(listOf("com.example.wire.internal")) // (8)!
    renames.put("com.example.wire.LegacyOrder", "com.example.wire.OrderEvent") // (9)!
    acceptedBreaks.set(listOf( // (10)!
        "com.example.wire.Payment PROPERTY_REMOVED BACKWARD",
    ))
}
```

1. Root types to walk. Nested types and sealed subtypes reachable from these are
   included automatically — you don't list every type in the graph.
2. Points at a `Json { ... }` instance in your codebase so the classifier judges
   changes against your *actual* wire config (`ignoreUnknownKeys`,
   `encodeDefaults`, `explicitNulls`, etc.), not kotlinx-serialization's defaults.
   Leave empty only if you truly serialize with a bare `Json { }`.
3. The git ref extracted as the "old" schema to diff against. Any ref `git`
   resolves — a branch, tag, or commit SHA. **Optional:** leave it unset and
   serialkompat auto-detects your default branch (`origin/HEAD`, falling back to
   `origin/main`/`origin/master`, then a local `main`/`master`), so a `master`-default
   repo works without configuration. Set it explicitly to pin a specific ref.
   Overridable per-invocation with `-Pserialkompat.ref=<ref>` on the
   `serialkompatCheckAgainst` task without touching this file.
4. See [Choosing a direction](#choosing-a-direction) below.
5. When `false`, `BREAK` findings are reported but don't fail the build — useful
   for a soft-launch/audit period, not recommended long-term.
6. Guards against a silent no-op: if the baseline extraction comes back empty
   (wrong ref, types not yet on that ref, etc.), that's almost always a
   misconfiguration, not "everything's compatible." Set `false` only while
   adopting serialkompat on a repo where the baseline ref genuinely predates
   these types.
7. Restricts the check to serial names under this prefix. Default `[""]` (empty
   string) matches everything.
8. Prefixes to drop even if they matched `include` — for types that are
   intentionally unstable (internal-only, no cross-version contract).
9. Declares that `LegacyOrder`'s serial name became `OrderEvent`. Without this,
   the differ sees a type removed and a type added — two `BREAK` findings — where
   only one intentional rename occurred.
10. Format is `"<serialName> <RULE> [DIRECTION]"`. `DIRECTION` is optional — omit
    it to accept the break in every direction being checked; include it
    (`BACKWARD`/`FORWARD`) to accept it in only one. Each entry silences one
    specific finding; unrelated findings on the same type still fail normally.

## Choosing a direction

`direction` tells the classifier which reader/writer pairing has to survive the
change. Pick based on how the schema is actually used, not by default:

| Direction | Guarantees | Use when |
|---|---|---|
| `BACKWARD` | New code can read data written by old code | Rolling deploys — a newer service version must decode messages/events produced by instances still running the old version |
| `FORWARD` | Old code can read data written by new code | Persisted data with slow migrations, or mixed-version consumers — an older reader (a replica, a cached job, a client that hasn't upgraded yet) must decode records a newer writer just produced |
| `FULL` | Both | Public APIs, shared wire formats, or persisted data with no controlled rollout order — the safest default when you don't control both ends |

`FULL` is the default and the right choice unless you specifically know only one
direction matters. A queue where producers and consumers deploy independently, or
long-lived persisted rows read by code from any past version, need `FULL`. A
same-service rolling deploy where old instances are drained within minutes only
needs `BACKWARD` for that window — but relaxing to `BACKWARD` or `FORWARD` narrows
the guarantee, so only do it deliberately, not as a way to silence findings.

## Next

- [Rules](rules.md) — the full rule table each `direction` and `Json` config draws from.
- [CI setup](ci.md) — wiring the check (and `-Pserialkompat.ref`) into a pipeline.
