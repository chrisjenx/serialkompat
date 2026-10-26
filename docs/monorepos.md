# Monorepos

Scope each module's check so it only grades the wire types it owns. It is for multi-module
builds where several modules apply the plugin.

## Monorepo scoping { #monorepo-scoping }

Restrict each module's check to serial-name prefixes with `include` and `exclude`. Where both
match, `exclude` wins:

```kotlin title="modules/orders/build.gradle.kts"
serialkompat {
    types.set(listOf("com.example.wire.OrderEvent"))
    include.set(listOf("com.example.wire.orders"))
    exclude.set(listOf("com.example.wire.orders.internal"))
}
```

`include` defaults to `[""]`, which matches everything. Set it when a module should ignore
types outside its own package. Use `exclude` to carve out an intentionally unstable subtree
(internal-only types with no cross-version contract) that `include` would otherwise match.

Each module has its own `types`, `baselineRef`, and scope. There's no repo-wide config, so a
change to one module's wire types can't widen or narrow another module's check.

## Many modules { #many-modules }

Multi-module builds work with `--parallel`. Modules take turns extracting their baselines, so
they don't compete for the same git repository.

To avoid listing types per module, combine this with discovery: see
[Adopting gradually](adopting.md#discovery-modes). The full option list is in
[Configuration](configuration.md).

## The Action in a multi-module build { #the-action }

The GitHub Action's sticky comment covers one report. In a multi-module build, point
`report-path` at the module that matters most, for example
`shared/api/build/serialkompat/report.json`. See the [Action inputs](ci.md#inputs) and
[the sticky comment](ci.md#the-sticky-comment).
