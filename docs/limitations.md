# Limitations

What serialkompat cannot analyse, what to do about it, and the known limits.

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
addition. The reverse, analysed to opaque, is still a `CONTRACT_REMOVED` `BREAK` plus the
gap, so lost coverage stays loud.

!!! note
    A broken *configuration* is different from a hard-to-analyse model. If you set
    `jsonInstance` and it can't be loaded, extraction fails instead of guessing with the
    default `Json` config.
