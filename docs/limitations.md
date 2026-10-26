# Limitations

What serialkompat cannot analyse, what to do about it, and the known limits. It is for
anyone who sees a `COVERAGE_GAP` warning, or wonders why a type isn't being checked.
Findings are judged per direction: [backward (new code reads old data)](concepts.md#backward-vs-forward)
and [forward (old code reads new data)](concepts.md#backward-vs-forward). A type that can't
be analysed can't be judged in either.

## When a type can't be analysed { #when-a-type-cant-be-analysed }

Some types can't be analysed. The extractor doesn't skip these or guess. It records each
one as an `OPAQUE` contract: present in the snapshot, diffable, and round-trippable, but
flagged. Examples:

- a `@Contextual` field whose type has no serializer in the module you pointed
  serialkompat at, and no fallback (a builtin like `Instant`, or a `@Serializable`
  class). serialkompat resolves contextual fields the way the runtime does, so a
  registered or fallback serializer is analysed normally; with neither, a real encode
  throws too.
- an open polymorphic base with no visible subtypes, for example because it isn't
  registered in the `SerializersModule` you pointed serialkompat at. Its subtypes are only
  knowable from the module, so an empty list can't be trusted.
- a type whose serializer can't load, such as a field type missing from the runtime
  classpath (`NoClassDefFoundError`) or a serializer whose static initializer throws.
- a generic type used with different type arguments in the same graph, such as
  `Page<User>` and `Page<Order>`. Type arguments aren't part of a contract's identity
  yet, so serialkompat can't check each instantiation separately.
- a generic sealed hierarchy, an unrecognized `SerialKind`, or a class file that can't be
  read.

This is a core guarantee: **the extractor never throws on a model it can't fully
analyse, and unanalysable never means safe.** The classifier reports every opaque type as
a `COVERAGE_GAP` `WARN`, never a silent pass.

A type that was opaque in the baseline but is analysed now (after a code change, or a
newer serialkompat) has no old shape to compare with, so the change can't be verified
either way. It is reported as one `COVERAGE_GAP` `WARN`, not as a removal plus an
addition. That includes a listed type that didn't exist yet at `baselineRef`, unless its
`@SerialName` differs from its class name (see
[Quick start → Your first PR](quickstart.md#first-time-adoption)). The reverse, analysed to opaque, is still a `CONTRACT_REMOVED` `BREAK` plus the
gap, so lost coverage stays loud.

!!! note
    A broken *configuration* is different from a hard-to-analyse model. If you set
    `jsonInstance` and it can't be loaded, extraction fails instead of guessing with the
    default `Json` config.

## What to do about an opaque type { #resolving-opaque }

Each opaque type shows up as a `COVERAGE_GAP` `WARN` naming it. A warning doesn't fail the
build, but that type isn't being checked: treat it as work to do, not as a pass. To close
the gap, give serialkompat what it needs:

- **`@Contextual` field:** see [`@Contextual` resolution](#contextual-resolution).
- **Open polymorphic base:** register its subtypes in that same module.
- **Serializer that can't load:** put the missing class on the module's runtime classpath, or
  fix the failing static initializer.
- **Generic type with several instantiations:** check a concrete wrapper type per
  instantiation until type arguments are part of a contract's identity.

A hand-written `KSerializer` whose descriptor the extractor can't see into, such as a custom
primitive (`PrimitiveSerialDescriptor("Money", PrimitiveKind.STRING)`), is also opaque, for
JSON and CBOR alike. If a type can't be
made analysable, record that decision by adding the `COVERAGE_GAP` to
[`acceptedBreaks`](got-a-break.md#accept-it) like any finding.

## `@Contextual` resolution { #contextual-resolution }

Since 0.3.1, serialkompat resolves each `@Contextual` field the way the runtime does:

<div class="sk-steps" markdown>
<div class="sk-step" markdown>
<span class="sk-step-n">1</span>
**The module entry.** It looks the type up in the `SerializersModule` of the `Json` (or
`Cbor`) instance you point `jsonInstance` / `cborInstance` at.
</div>
<div class="sk-step" markdown>
<span class="sk-step-n">2</span>
**The compiler's fallback.** If the module has no entry, it uses the serializer the compiler
captured: a builtin such as `kotlin.time.Instant`, or the class's own `@Serializable`
serializer.
</div>
<div class="sk-step" markdown>
<span class="sk-step-n">3</span>
**Neither:** the field is `OPAQUE` and reported as a `COVERAGE_GAP`. A real encode would
throw here too.
</div>
</div>

Because the module entry wins, swapping a registered contextual serializer is a
`PROPERTY_TYPE_CHANGED` `BREAK`, not a warning.

!!! note "Upgrading from 0.3.0 or earlier"
    Expect one `COVERAGE_GAP` `WARN` for each `@Contextual` field that is now resolved, the
    first time you check against a baseline recorded by 0.3.0 or earlier. That baseline has
    no analysed shape to compare with, so the warning replaces a false type change or
    removal. It clears once the baseline is recorded by 0.3.1 or later. See the
    [changelog](changelog.md).

## Known limits { #known-limits }

- **One wire format per module.** A snapshot describes JSON or CBOR, never both
  ([CBOR → One format per module](cbor.md#one-format-per-module)). Tracked in
  [#226](https://github.com/chrisjenx/serialkompat/issues/226).
- **Per-class `@JsonIgnoreUnknownKeys` is not read.** Only the `Json` instance's
  `ignoreUnknownKeys` is. Tracked in [#227](https://github.com/chrisjenx/serialkompat/issues/227).
- **Coarse CBOR tag config.** `CONFIG_CBOR_TAGS` is coarser than `CBOR_TAGS_CHANGED`. Tracked
  in [#225](https://github.com/chrisjenx/serialkompat/issues/225).
- **JVM only.** Extraction reads compiled JVM serializers, so a Kotlin Multiplatform module
  needs a `jvm()` target.
- **No source locations.** Findings carry no file or line, so GitHub annotations attach to
  the run rather than a diff line (see [Report formats](report-formats.md#github-annotations)).
