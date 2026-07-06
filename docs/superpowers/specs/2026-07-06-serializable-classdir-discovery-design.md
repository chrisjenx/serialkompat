# `@Serializable` discovery via class-dir scanning (issue #55, re-scoped)

**Date:** 2026-07-06
**Issue:** [#55](https://github.com/chrisjenx/serialkompat/issues/55) (re-scoped by this spec) — unblocks [#115](https://github.com/chrisjenx/serialkompat/issues/115)
**Module:** `serialkompat-extractor` only. No `-core`, no `-gradle`, no new module.

## 1. Context and re-scope decision

Discovery today needs either explicit `types` configuration or a hand-authored
classpath manifest (`META-INF/serialkompat/serializable-types.txt`,
`SchemaExtractionMain.TYPES_RESOURCE`). #55 originally tracked the automated
manifest *producer* as an opt-in Kotlin compiler plugin.

**This spec re-scopes #55: the producer is a class-dir scan inside the
extractor itself, not a compiler plugin.** Rationale:

- #22 rejected **KSP**, not classpath scanning — its own text treats KSP as the
  fallback "if JVM classpath scanning proves awkward". Scanning is not awkward:
  `@Serializable` is **RUNTIME-retained** (verified against
  `kotlinx-serialization-core-jvm`: `RetentionPolicy.RUNTIME`), so every
  annotated class carries it in the class file's `RuntimeVisibleAnnotations`.
- Extraction is JVM-only by design (design §4, Approach A), and the extractor
  already runs a JVM on the target project's runtime classpath. Scanning the
  project's compiled class dirs from there needs no new artifact, no Gradle
  sub-plugin, and no KMP story.
- The compiler plugin's two structural costs disappear: fragility across Kotlin
  releases, and side-output files breaking incremental compilation.

KSP remains rejected. The compiler-plugin producer is retired, not deferred —
design §4 is updated in the same PR (CLAUDE.md rule on design deviations).

## 2. Goals / non-goals

**Goals**

- The extractor and its CLI can discover `@Serializable` types by scanning
  compiled class directories, feeding the existing type-resolution loop.
- Discovery stays producer-agnostic: the manifest contract is untouched and
  scanned results union with it.
- Gate guarantees hold: the scanner never throws; unanalysable ≠ safe.

**Non-goals (explicitly out of scope)**

- Gradle DSL / discovery modes / `@SerialkompatIgnore` — that is #115, which
  builds on this capability.
- Jar scanning. Callers pass exploded class dirs (Gradle `classesDirs`); jars
  can be added later if a real consumer needs them.
- Non-JVM targets (extraction is JVM-only by design).
- Generic shape analysis (placeholder type arguments, `T`-holes as snapshot
  nodes) — tracked as a follow-up issue filed as part of this work (§5).

## 3. Design

### 3.1 `SerializableClassScanner` (new, internal)

An `internal object` in `serialkompat-extractor` with one entry point:

```kotlin
internal fun scan(roots: List<File>): ScanResult

internal data class ScanResult(
    /** Binary names (`com.foo.Bar$Baz`) of classes with a class-level @Serializable. */
    val typeNames: List<String>,
    /** Root-relative paths of class files that could not be parsed. */
    val unreadable: List<String>,
    /** Binary names of @Serializable classes skipped because they declare type parameters. */
    val skippedGenerics: List<String>,
)
```

For each root directory it walks `**/*.class` (deterministic order: sorted
paths) and parses each file with a hand-rolled, dependency-free class-file
reader:

1. magic + version check (`0xCAFEBABE`; any known-or-newer version accepted),
2. constant pool (all JVMS tags incl. `Long`/`Double` double-slot entries and
   the post-Java-6 tags `MethodHandle`/`MethodType`/`InvokeDynamic`/`Dynamic`/
   `Module`/`Package`; an unknown tag makes the file *unreadable*,
   never a crash),
3. access flags, `this_class`, super/interfaces (skipped over),
4. fields and methods (skipped structurally via attribute lengths),
5. class-level attributes: `RuntimeVisibleAnnotations` is searched for an
   annotation whose type descriptor is exactly
   `Lkotlinx/serialization/Serializable;`; the `Signature` attribute is read to
   detect type parameters (a class signature starting with `<`).

Classification per file:

| Condition | Result |
|---|---|
| Class-level `@Serializable`, no type parameters | → `typeNames` (binary name: `/` → `.`, `$` kept) |
| Class-level `@Serializable`, has type parameters | → `skippedGenerics` |
| No class-level `@Serializable` (incl. property-level-only usage, `module-info`, `package-info`, synthetics, anonymous classes) | ignored |
| Parse failure of any kind (truncated, corrupt, unknown constant-pool tag) | → `unreadable`; **never throws** |

The parser only matches **class-level** annotations. A property-level
`@Serializable(with = …)` puts the same UTF-8 descriptor string in the constant
pool; matching on the constant pool alone would false-positive. That is a
required regression test.

Detection is bytecode-only — no `Class.forName`, no linking, no static
initialization. Resolution of detected names stays in the existing loop.

### 3.2 Wiring in `SchemaExtractionMain`

`run` gains a `scanDirs` parameter (public API change → `apiDump`):

```kotlin
public fun run(
    typeNames: List<String>,
    jsonInstanceFqn: String?,
    output: File,
    scanDirs: List<File> = emptyList(),
)
```

Effective-type computation (precedence unchanged in spirit — explicit wins,
discovery fills the gap):

```kotlin
val scan = SerializableClassScanner.scan(scanDirs) // ScanResult.EMPTY when scanDirs is empty
val effectiveTypes = typeNames.ifEmpty { (discoverTypeNames() + scan.typeNames).distinct().sorted() }
```

- **Explicit `types` set** → used verbatim; scan results ignored for
  resolution (an explicitly listed generic keeps today's behavior: resolution
  fails → OPAQUE. The user asked; the gap surfaces).
- **No explicit types** → manifest ∪ scan, distinct + sorted.
- `scan.unreadable` entries become `Contract(path, ContractKind.OPAQUE)`
  appended to the snapshot **whenever scanning ran** (even alongside explicit
  `types`): an unreadable class file is an unanalysable input, and
  unanalysable ≠ safe.
- `scan.skippedGenerics` is logged to stderr, one summary line naming each
  type:
  `serialkompat: skipped N generic type(s) as scan roots (their shapes are checked at concrete use sites): a.b.C, d.e.F`.
  Skipped generics are *not* snapshot entries and do not WARN (decision §4).

CLI (`main`) gains `--scan-classes`, a `File.pathSeparator`-separated list of
directories, and the argument check relaxes from "`--types` is required" to
"`--types` or `--scan-classes` is required":

```
--types a,b,c | --scan-classes dir1:dir2   (at least one)
--out path                                  (required)
--json fqn                                  (optional)
```

The `TYPES_RESOURCE` / `discoverTypeNames` contract is byte-for-byte untouched;
its KDoc reference to a compiler-plugin producer is updated to name the scanner.

### 3.3 What discovery includes

Everything with a class-level `@Serializable` and no type parameters: classes,
data classes, objects, enums, sealed interfaces, value classes,
`@Serializable(with = …)` at class level, nested classes. Whether each
resolves to a descriptor is the existing loop's job — unresolvable → OPAQUE,
exactly as for manifest entries today.

## 4. Generics-as-roots policy (evidence-based)

Discovered generic `@Serializable` classes are **skipped as roots, loudly**
(stderr summary naming each skipped type), because:

- **Surveyed evidence.** Two production codebases were audited (2026-07-06).
  `mercury/android/core-types` (~200 `@Serializable` types, 9 generic
  families): every generic appears as a property of a concrete `@Serializable`
  host, so the descriptor walk already covers all of them ("Case 1").
  `haynet-app/shared/api` (~668 types, 2 generics): both are root-only wire
  envelopes (`BaseResponse<T>`, `ResultSet<T>`) never nested in another model
  ("Case 2").
- A generic class has no standalone wire shape — `serializer()` requires
  concrete type arguments — so including it as a root can only produce a
  permanent, unfixable OPAQUE WARN per generic type (alarm fatigue; the ignore
  annotation does not exist until #115).
- Case 2 is real but rare and is **no regression**: explicit `types` cannot
  express `BaseResponse<User>` either. The loud skip makes the gap visible
  instead of silent, and the follow-up issue (generic shape analysis with
  placeholder type arguments and `T`-holes as snapshot nodes) tracks the real
  fix. That issue is filed as part of this work and linked from the #55 close.

## 5. Bookkeeping shipped with the PR

- Design doc §4 rewritten: Approach C becomes class-dir scanning; compiler
  plugin retired; KSP still rejected; generics policy + evidence summarized.
- `serialkompat-extractor/api/serialkompat-extractor.api` regenerated
  (`./gradlew apiDump`) for the new `run` signature.
- `CHANGELOG.md` entry.
- Issue hygiene (after merge): comment + retitle #55 recording the re-scope
  and close it; comment on #115 that its blocker is resolved; file the
  generic-shape-analysis follow-up issue.

## 6. Error handling summary

| Failure | Behavior |
|---|---|
| Corrupt / truncated / unknown-format class file | `unreadable` → OPAQUE contract keyed by root-relative path; extraction continues |
| Scan dir does not exist / is empty | Empty contribution; not an error at this layer (`failOnEmptyBaseline` semantics unchanged) |
| Detected type fails to resolve (e.g. `@Serializable` on an unsupported shape) | Existing path: OPAQUE coverage gap |
| Anything else inside the scanner | Caught per-file; the scanner itself never throws |

## 7. Testing strategy (TDD, red → green per unit)

All tests live in `serialkompat-extractor`'s test source set, which the
serialization plugin already compiles — so parser tests run against **real
compiler output** by scanning the module's own test-classes dir (located via
`FixtureClass::class.java.protectionDomain.codeSource.location`).

**Scanner unit tests** (new fixtures in the test source set):

1. Annotated data class → detected.
2. Non-annotated class in the same package → not detected.
3. Nested annotated class → detected with `Outer$Inner` binary name.
4. Annotated `object`, `enum class`, and sealed interface → detected.
5. Generic annotated class → in `skippedGenerics`, not in `typeNames`.
6. Class with only a property-level `@Serializable(with = …)` → **not**
   detected (constant-pool false-positive regression test).
7. Temp dir with a garbage `.class` file → in `unreadable`, no throw.
8. Empty / missing root dir → empty result, no throw.

**`run()` integration tests:**

9. `scanDirs` pointing at the test-classes dir → snapshot contains the
   fixture contracts (and the OPAQUE entries for whatever cannot resolve).
10. Explicit `typeNames` + `scanDirs` → snapshot reflects the explicit list
    only (plus any `unreadable` OPAQUE entries).
11. Manifest resource + scan → union, distinct, sorted.
12. Unreadable file in a scan dir → OPAQUE contract present in the snapshot.

**CLI tests:**

13. `--scan-classes` alone (no `--types`) → runs; output file written.
14. Neither `--types` nor `--scan-classes` → fails with the updated message.

No new classification rules → no new round-trip oracle tests (the rule matrix
is untouched; this feature only changes *which types enter the snapshot*).

## 8. Acceptance criteria

- [ ] `SerializableClassScanner` detects class-level `@Serializable` in
      compiled class dirs with zero dependencies and zero classloading.
- [ ] `run(..., scanDirs)` and `--scan-classes` wire discovery per §3.2;
      precedence and manifest contract unchanged.
- [ ] Generics skipped loudly; unreadable files surface as OPAQUE.
- [ ] Tests 1–14 green; `./gradlew build` green (incl. `apiCheck` after
      `apiDump`).
- [ ] Design doc §4 updated in the same PR; CHANGELOG entry.
- [ ] Post-merge issue hygiene per §5.
