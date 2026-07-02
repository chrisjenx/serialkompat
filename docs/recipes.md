# Recipes

Task-oriented answers for specific situations. For the full DSL see
[Configuration](configuration.md); for CLI/Action install paths see
[Setup](setup.md).

## Cross-repo diff with the CLI

Two services (or two checkouts of a monorepo) that don't share a Gradle build
still need to agree on the wire. Extract a snapshot on each side with the
Gradle plugin, then diff the files with the standalone CLI — no Gradle build
needs to see both sides at once.

```console
$ ./gradlew serialkompatExtract   # in repo/checkout A -> build/serialkompat/current.snapshot
$ cp build/serialkompat/current.snapshot /tmp/producer.snapshot

$ ./gradlew serialkompatExtract   # in repo/checkout B -> build/serialkompat/current.snapshot
$ cp build/serialkompat/current.snapshot /tmp/consumer.snapshot

$ serialkompat diff /tmp/producer.snapshot /tmp/consumer.snapshot
```

`serialkompat diff <baseline.snapshot> <current.snapshot>` treats the first
argument as the old schema, the second as the new one — same semantics as the
Gradle task's `baselineRef` vs. the current classpath. Add
`--direction=BACKWARD|FORWARD|FULL` to narrow the check, or `--no-fail` to
print findings without failing the invocation. Exit codes: `0` ok, `1`
breaking, `2` usage error.

## First-time adoption

Turning the gate on for the first time, the configured `baselineRef` almost
always predates the `@Serializable` types you're checking — the baseline
extraction comes back with zero contracts. By default that's treated as a
misconfiguration and fails the build (a genuinely empty baseline would
otherwise make every type look "newly added, therefore safe," silently
masking real removals on the *next* run).

For the one run where an empty baseline is expected, opt out explicitly:

```kotlin title="build.gradle.kts"
serialkompat {
    types.set(listOf("com.example.wire.OrderEvent"))
    failOnEmptyBaseline.set(false) // only while baselineRef predates these types
}
```

Once `baselineRef` (e.g. `origin/main`) has moved past the commit that
introduced these types, remove the override — `failOnEmptyBaseline` should go
back to its default `true` so a real misconfiguration doesn't slip through
unnoticed.

## Sanctioning a deliberate break

Sometimes a break is intentional — a major version bump, a field you know
every consumer has migrated off. `acceptedBreaks` downgrades a specific
finding from failing to *acknowledged*: it still shows up in the report, it
just no longer fails the build.

```kotlin title="build.gradle.kts"
serialkompat {
    acceptedBreaks.set(listOf(
        "com.example.wire.Payment PROPERTY_REMOVED BACKWARD",
    ))
}
```

The format is `"<serialName> <RULE> [DIRECTION]"` — the contract's serial
name, the exact rule ID (see [Rules](rules.md)), and an optional direction.
Omit the direction to accept the finding in every direction being checked;
include `BACKWARD`/`FORWARD` to accept it in only one. Each entry silences
exactly the one finding it names — every other finding on that type, or that
rule, still fails normally. The console and JSON reports both keep showing
acknowledged findings (counted separately from active ones), so accepting a
break is visible, not silent.

## Monorepo scoping

Running serialkompat per-module in a monorepo, each module should only be
graded on the types it actually owns. `include`/`exclude` restrict a check to
serial-name prefixes — `exclude` wins where both match:

```kotlin title="modules/orders/build.gradle.kts"
serialkompat {
    types.set(listOf("com.example.wire.OrderEvent"))
    include.set(listOf("com.example.wire.orders"))
    exclude.set(listOf("com.example.wire.orders.internal"))
}
```

`include` defaults to `[""]` (matches everything); set it once you want a
module to ignore contracts outside its own package. `exclude` is for carving
out a subtree that's intentionally unstable (internal-only types with no
cross-version contract) even though it matches `include`. Each module keeps
its own `types`, `baselineRef`, and scope — there's no repo-wide config to
share, so a change in one module's wire types can't accidentally widen or
narrow another module's check.

## Roadmap: multi-version history

Checking only against `baselineRef` catches a break against the *last*
version — not against every version still in the field. An append-only
published-schema history (checking a change against every prior release, not
just the latest) is on the roadmap but not implemented; there's no recipe for
it yet.

## Next

- [Configuration](configuration.md) — full `serialkompat { }` reference.
- [Deep dive](deep-dive.md) — how extraction, classification, and the git-ref baseline actually work.
