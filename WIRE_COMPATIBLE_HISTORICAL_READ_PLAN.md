# Wire-Compatible, Allocation-Minimal Historical Read Engine

This document is the authoritative implementation plan and continuation ledger for the overhaul in this worktree. It must be re-read after context compaction and updated whenever a phase or validation gate changes state.

## 2026-08-05 correctness and codec-session repair

This section supersedes every conflicting API or implementation statement below. The earlier ledger remains as historical evidence for the implementation being repaired.

### Repair scope and invariants

- Work only in `/tmp/data-generator-bulk-projection`; leave Yotsuba untouched.
- Preserve every valid wire encoding byte-for-byte. API compatibility remains disposable.
- Fix nullable framing, allocation limits, codec sessions, generated-member hygiene, deterministic unions/cache, and fixed-run generation together because they share the read compiler.
- Require explicit decode limits. No reader or stream-input API silently selects an unlimited policy.

### Public API and runtime contracts

- Add immutable `DecodeLimits` with:
  - maximum elements in one array;
  - maximum bytes in one String/BinaryString payload;
  - cumulative array elements per root value;
  - cumulative payload bytes per root value;
  - maximum structural nesting depth.
- Provide explicitly named `DecodeLimits.unlimited()` only as a deliberate trusted-input opt-out.
- Every `SafeDataInput` exposes its reader-owned `DecodeBudget` and `remainingBytesIfKnown()`, returning `-1` only for genuinely forward-only inputs.
- Require limits in `BufDataInput.create`, `SafeDataInputStream`, `DataCodec.newReader`, projection readers, and both generated `CurrentVersion.newReader` factories. Remove limit-free overloads.
- Rework `DataCodec<T>` around reader-owned sessions:

```java
public interface DataCodec<T> {
    void serialize(SafeDataOutput output, T value);
    ReadSession<T> newReadSession();
    T read(SafeDataInput input);
    void skip(SafeDataInput input);
    Reader<T> newReader(DecodeLimits limits);
}
```

- `ReadSession<T>` is thread-confined and reusable. It provides final `read`, `skip`, and `readReserved(RandomAccessDataInput, offset, length)` entry points, protected decode implementations, and mandatory transient-state cleanup in `finally`.
- Its default reserved implementation uses one lazily allocated reusable child cursor; specialized custom sessions may override it with direct absolute loads.
- Add `FixedDataCodec<T>` with `fixedSize()`. A custom type declaring YAML `fixedSize` must implement it, and generated initialization verifies the runtime and configured sizes agree.
- Add stable `MalformedDataException`, `DecodeLimitExceededException`, and `ValueTooLargeException`, all derived from `IllegalArgumentException`.

### Nullable framing

- Add one generator-side `WireLayout` classification:
  - ordinary boolean-tagged nullable;
  - boolean-tagged short String/BinaryString;
  - Int52 high-bit sentinel.
- Add shared JavaPoet emitters for decoding, skipping, presence-only reads, and captured wire regions.
- Replace every private nullable implementation in serializers, projections, fused readers, skippers, wire views, and `mapNullable`.
- Ensure short String paths consume an unsigned-short length and Int52 paths preserve the first byte for `Int52Serializer.readValue`.
- Keep flattened presence/value locals; introduce no nullable-wrapper allocation.

### Deterministic unions and cache

- Change `superTypesData` from `Set<String>` to ordered `List<String>`. Preserve YAML order exactly because list index is the wire discriminator.
- Reject null, empty, blank, duplicate, or unknown alternatives.
- Accept at most 256 alternatives and allow IDs `0..255`; reject 257 before creating output directories.
- Replace the additive integer cache hash with SHA-256 over length-prefixed generator version, package, flags, and exact YAML bytes.
- Replace `.hash` with a versioned manifest containing the generation fingerprint and SHA-256 for every generated file.
- Skip generation only when the fingerprint matches and every listed file exists with the recorded digest. Old manifests, missing files, or edited generated files force regeneration.
- Write the manifest atomically only after successful generation and bump the generator serial.

### Generated member hygiene

- Introduce a deterministic generated-name allocator seeded with every schema field and fixed generated identifier.
- Use hygienic private names for nullable presence fields, constructor parameters, serializer locals, and builder storage. Schemas such as `maybe`, `maybePresent`, and `maybeFirst` must compile.
- Add a public-surface validator for every current and historical record, union interface, and builder.
- Compare Java method signatures by name and erased parameter types; allow legal overloads while rejecting collisions such as:
  - `items:int[]` with `itemsSize:int`;
  - `maybe:-String` with `hasMaybe:boolean`;
  - `x:int` with `X:int`;
  - fields colliding with `toString`, `hashCode`, `getClass`, `getBaseType$`, `builder`, or union metadata methods.
- Report both schema origins and the affected version before emitting files.

### Length and allocation safety

- Validate short BinaryString payloads against unsigned `0xffff`, not `Short.MAX_VALUE`.
- `writeShort` validates before writing; nullable binary validates before its presence byte; binary arrays prevalidate all elements before their array prefix.
- For every prefix-driven allocation:
  1. validate sign;
  2. use checked arithmetic;
  3. exact-reserve fixed payloads when remaining bytes are known;
  4. validate the recursive minimum structural size for variable elements;
  5. claim the decode budget;
  6. allocate;
  7. consume exactly the declared body.
- Compute minimum serialized sizes once in the typed read-plan IR. Variable customs and zero-width records have minimum zero and therefore rely on the mandatory budget.
- Fixed custom and Int52 arrays reserve `count * fixedSize` before allocating.
- Forward streams enforce limits before allocation and exact reads afterward. Fix truncated String reads, partial `ByteBuffer` reads, and zero-progress `readFully` loops.
- Fail bounded truncations after the prefix without partially advancing through the body; readers remain reusable after every exception.

### Per-reader custom state and fixed runs

- Generate one `CodecReadState` per outer reader lane, lazily containing one session per logical custom type.
- Share it across nested fused-plan states, exact generated codecs, projections, arrays, and nullable codecs.
- Static codec objects remain immutable factories; mutable scratch belongs only to sessions.
- Generated exact codecs get nested reusable sessions and state-accepting structural helpers, eliminating per-field session creation.
- Extend fixed-run scheduling to retained fixed custom values:
  - reserve one adjacent primitive/custom/skipped/captured run;
  - decode primitives through constant-offset getters;
  - call the custom session's `readReserved` at its constant offset;
  - preserve the sequential stream fallback.
- Add `bindReservedRegion` to propagate the selected heap, MemorySegment, or fallback storage and shared budget into a child cursor without slicing, copying, storage reprobe, or another parent cursor-advance check.
- Fixed-custom arrays reserve once and decode elements at constant offsets. Nullable fixed customs reserve only after a present tag.
- Clear custom input, graph, cursor, and view references after success or failure while retaining warmed scratch capacity.

### Tests and acceptance

- Add independent literal-golden fixtures for nullable String and Int52 containing removed, selected, mapped, captured-view, presence-only, and trailing fields. Test all presence combinations with binary Strings on/off and old serializers on/off.
- Cover exact codecs, fused reads, projections, wire views, `mapNullable`, heap, sliced heap, native, unaligned native, fallback, streams, truncation at every boundary, trailing bytes, and reader reuse.
- Cache tests must cover union reordering in the same output directory, identical-input cache hits, flag changes, missing files, modified files, and deterministic source/fingerprint output.
- Union tests: duplicates, unknown types, 256 alternatives with subtype 255 encoded as `0xff`, and 257 rejected before output.
- Member tests: all public collision classes, historical-only collisions, legal overloads, and hygienic private-name schemas.
- Length tests:
  - BinaryString sizes `0`, `32767`, `32768`, `65535`, and rejected `65536`;
  - every primitive/reference/custom/nested/zero-width array;
  - exact limit and one-over-limit;
  - negative, overflowed, truncated, and zero-progress stream input.
- Session tests prove distinct lanes receive distinct sessions, repeated rows reuse sessions, custom failures clear state, and singleton codecs contain no mutable lane state.
- Generated-source and ClassFile assertions require one fixed-run reservation, constant offsets, no slices/streams/lambdas/per-element cursor allocation, and bounds/budget checks before every generated array allocation.
- Run:
  - focused runtime/plugin tests;
  - `mvn clean verify`;
  - `mvn -Pvector clean verify`;
  - `mvn -Pbenchmark clean verify`;
  - focused scalar and Vector JMH for adjacent fixed customs and fixed-custom arrays;
  - the complete generated scalar and Vector benchmark matrices.
- Benchmark acceptance: after reader/session warmup, infrastructure allocates `0 B/op`; only the returned graph and owned arrays may allocate. Archive reports outside `target` with worktree diff digest, JDK, CPU, commands, and checksums.

### Repair assumptions

- Valid historical payload bytes, ordering, prefixes, and discriminators never change.
- Limits are explicitly selected by every caller; there is no compatibility overload or implicit unlimited behavior.
- Custom codecs use the supplied budget for their own variable allocations and never retain an input/source/result after session cleanup.
- No Yotsuba migration, publication, commit, or release is included.

### Repair ledger

| Repair phase | Status | Deliverable |
|---|---|---|
| R0 | complete | Persist this superseding repair specification before implementation edits |
| R1 | complete | Decode limits, budgets, stable exceptions, exact stream/bounded-input safety |
| R2 | complete | Reader-owned codec sessions, fixed codec contract, shared generated codec state |
| R3 | complete | Unified nullable wire-layout lowering across all generated paths |
| R4 | complete | Deterministic union validation, digest manifest cache, generated-name/public-surface hygiene |
| R5 | complete | Prefix/allocation ordering, typed minimum sizes, fixed-custom reservation scheduling |
| R6 | blocked | Focused regression, generated-source/ClassFile, scalar/Vector/full acceptance and archived evidence |

### 2026-08-05 repair progress

- R1-R5 are complete. Runtime inputs now require explicit immutable limits and share a root-owned budget; stable malformed/limit/value-size failures cover bounded and forward-only inputs. `DataCodec` is an immutable session factory, `ReadSession` owns reusable thread-confined scratch, fixed codecs are checked against configured sizes, and each outer lane shares one lazy `CodecReadState` across exact, fused, projection, nullable, array, and custom paths.
- Nullable decoding, skipping, presence-only reads, region capture, projections, wire views, and nullable maps now lower through `WireLayout` and `NullableWireEmitter`. Ordered union discriminators, 256-alternative validation, SHA-256 generation manifests, hygienic private-name allocation, recursive union metadata checks, and record/union/builder erased-signature validation all run before output emission. Literal String/Int52 goldens and cache, union, member, limit, session, generated-source, and ClassFile regressions are included.
- Prefix-driven allocations validate sign and checked byte size, reserve known bodies, validate typed recursive minima, claim budgets, and only then allocate. Primitive-array returned-value kernels use the reserved absolute body offset on sliced as well as unsliced storage. Fixed customs join retained fixed runs and fixed arrays through `readReserved`; nullable fixed customs reserve inside the present branch in exact codecs, fused plans, projections, and reusable readers, with sequential stream fallback.
- The final correctness/build gates pass: the focused repair gate ran 16 runtime tests plus 4 targeted generator tests; `mvn -B clean verify`, `mvn -B -Pvector clean verify`, `mvn -B -Pbenchmark clean verify`, and `mvn -B -Pbenchmark,vector clean verify` each pass. Their stable suites contain 2,703 runtime tests, 46 `SourcesGeneratorTest` cases plus the adversarial evolution test, 3 Vector tests, and 5 generated benchmark-shape tests. `git diff --check`, shell syntax, removed-API, explicit-limit, and stable-core incubator-linkage audits pass.
- Two checksum-valid scalar JMH archives exist outside `target`: the focused 8-case fixed-custom run/array report and the complete 324-case scalar matrix. Focused normalized allocation is returned-data-only: about 48 B/op for the record, 104 B/op for a 16-element fixed-custom array result, and 16,424 B/op for a 4,096-element result on heap and native storage. These archives fingerprint an earlier worktree diff, so they are supporting evidence rather than final acceptance evidence.
- R6 is blocked only at the timed acceptance boundary. A final-diff scalar rerun and both focused and complete Vector JMH matrices require an unsandboxed forked-JVM execution approval whose quota is unavailable until 2026-08-11. No alternate or weakened run was substituted. Formal benchmark acceptance remains incomplete until matching-diff scalar and Vector archives are produced and checksummed.

## Non-negotiable invariants

- Continue from the unfinished implementation in `/tmp/data-generator-bulk-projection`.
- Preserve every existing wire byte exactly for every historical and current version. Introduce no new serialization format.
- Source compatibility, binary compatibility, generated runtime-class identity, record shape, and Java serialization compatibility are intentionally disposable.
- Optimize for permanently reading enormous historical datasets directly into the current graph. No rewrite or background migration is assumed.
- Ignore Yotsuba completely. Do not read, generate, modify, or validate any Yotsuba file.
- Readers are thread-confined and reused once per worker lane. Decoded values and arrays are owned. Unsafe array access requires callers not to mutate the backing array.
- Stable core code uses finalized Java 25 APIs. Incubator Vector API acceleration is isolated behind an explicit optional artifact/profile.

## Target public API

Replace separate normal serializer and skipper contracts with a hard-break codec:

```java
public interface DataCodec<T> {

    void serialize(SafeDataOutput output, T data);

    T read(SafeDataInput input);

    void skip(SafeDataInput input);

    default Reader<T> newReader();

    interface Reader<T> {

        T read(Buf source);

        T read(Buf source, int offset, int length);
    }
}
```

- Custom types declare one `codec` and an optional compile-time `fixedSize`. Remove serializer/skipper compatibility aliases.
- Retain generated version APIs:
  - `CurrentVersion.read(version, type, input)` for stream/random-access input.
  - `CurrentVersion.newReader(type)` for mixed versions.
  - `CurrentVersion.newReader(version, type)` for a monomorphic type/version lane.
- Do not add a one-shot `Buf` API that allocates a cursor per row.
- Reusable bounded readers reject trailing bytes and clear every source/cache/view reference in `finally`, including failure paths.

## Generated value model

Replace wrapper-heavy generated records with immutable final classes for both current and historical versions.

- Nullable references are stored as `null` and exposed with `hasX()`, `x()`, and `xOrNull()`.
- Nullable primitives are flattened into a presence bit plus a primitive field. No boxed payload or nullable wrapper is allocated.
- Arrays are stored as owned primitive/reference arrays with no list wrapper.
- Array fields expose `xSize()`, `x(index)`, `xCopy()`, and clearly named `xUnsafeArray()`.
- Safe public factories/builders copy caller arrays. Generated readers use an explicit `unsafeOfOwned(...)` ownership-transfer factory for freshly decoded arrays.
- Equality, hashing, and string rendering use deep array semantics.
- Zero-field records, empty nullable values, and empty arrays are canonical singletons.
- Object-to-object historical upgraders operate on the flattened models. No bridge to the old wrapper model is retained.

## Read compiler

Replace emitted-source-string comparison with a typed immutable global read-plan IR covering primitives, records, unions, nullable values, arrays, skips, transformations, and construction.

Optimization passes, in order:

1. Dependency/liveness analysis.
2. Dead historical field elimination.
3. Recursive structural fusion to current values.
4. Constant folding and initializer fusion.
5. Declarative transformation fusion.
6. Fixed-width block scheduling.
7. Structural hash-consing across versions and base types.
8. Storage-kernel lowering and Java emission.

Generate direct canonical kernels for heap-array, `MemorySegment`, and generic-`Buf` fallback storage. Nested reads must retain the selected storage kernel.

- Version-bound readers call canonical kernels directly. Remove `Function`, lambdas, decoder interfaces, and row-level type/version switches from bound hot paths.
- Mixed readers may dispatch by version once per row.
- Coalesce adjacent retained and skipped fixed-width fields:
  - reserve a full run with one bounds check/cursor advance;
  - decode retained fields at compile-time offsets;
  - coalesce fixed-width arrays into a single checked reservation.
- Validate array length arithmetic before reservation.
- Primitive-array kernels:
  - heap: bulk byte copy where wire-compatible, otherwise endian VarHandle loops;
  - native: finalized FFM layout/array copies with existing big-endian wire order;
  - fallback: direct `Buf` access with one region validation;
  - scalar tails for small/irregular arrays.
- Reader frames, nested state, cursors, scratch arrays, offset slots, and view indexes are lazy and reader-owned, with no per-row infrastructure allocation after warmup.

## Transformation API

Replace unfinished flat `readUpgrader*` configuration with a nested `readTransform` used by both `upgradeData` and `newData`.

Supported transforms:

- `custom`: generated primitive-specialized read-upgrader or read-initializer interface.
- `constant`.
- `identity`.
- `invokeStatic`.
- `construct`.
- recursively compiled `mapNullable`.
- recursively compiled `mapArray`.

Built-ins accept typed references to `value`, `value.<path>`, `currentValue.<path>`, `context.<path>`, `currentContext.<path>`, and literals. They emit direct typed calls with no generic boxing, context object, frame object, or interface dispatch.

Custom transforms receive ephemeral typed wire views generated only where configured:

- record views lazily expose primitive/reference fields plus historical/current structural forms;
- union views expose an exact generated `Kind` enum and typed subtype views;
- array views expose size, element materialization, reusable element views, and sequential cursors;
- context dependencies expose historical values, current values, and wire views without allocating context records.

The parent scan captures required variable-field regions into reusable reader-owned frame slots. View getters do not rescan the owner. Opaque custom codecs can receive a raw bounded cursor when no generated structural view is possible; complete consumption is mandatory.

Non-random stream reads and already-materialized object upgrades continue through the declared object initializer/upgrader fallback.

## Optional Vector module/profile

- Add an optional `datagen-vector` artifact or Maven profile requiring `--add-modules jdk.incubator.vector`.
- The stable core must not link against incubator classes.
- Vector kernels may accelerate endian conversion, boolean unpacking, and Int52 blocks, with scalar tails.
- Per-type/storage scalar, FFM, and Vector crossover thresholds must come from generated JMH evidence and be checked in as named constants.

## Verification requirements

### Wire compatibility

- Produce golden format-1 payload fixtures with the pre-overhaul codec.
- Prove byte-identical serialization and equivalent current reads for native values, nullable values, arrays, records, unions, custom fields, moves, removals, initializers, and upgraders.

### Correctness and cleanup

- Compare fused direct-to-current reads with exact historical reads followed by object upgrade for every fixture version.
- Cover heap, sliced heap, unaligned native regions, generic fallback buffers, bounded offsets, truncation, trailing bytes, overflowed lengths, invalid union IDs, custom exceptions, recursion, cursor closure, and reader reuse after every failure.
- Verify operation with old serializers disabled.
- Verify every source, cached graph, nested cursor, and view binding is cleared after success and failure.

### Allocation shape

- No historical objects, context objects, nullable wrappers, list wrappers, boxed primitive intermediates, slices, streams, lambdas, collection copies, or per-row scratch objects on fused hot paths.
- Exactly one returned object per required nonempty record and one owned raw array per nonempty array.
- Empty values use canonical singletons.
- Reused infrastructure is `0 B/op` after scratch warmup, excluding the returned current graph.

### Generated-code and JIT shape

- Use Java ClassFile API and JIT diagnostics to assert version-bound hot paths contain direct kernel calls, no `invokedynamic`/lambda dispatch, no version switch, and bounded hot-method sizes.
- Report generated-source size, bytecode size, compilation time, code-cache footprint, throughput, and allocation. Maximum specialization is retained even where code size grows.

### JMH matrix

- Primitive-dense records.
- Every primitive array at sizes `0, 1, 2, 8, 32, 256, 4096`.
- Strings, nullable-heavy graphs, large unions, typed-view branches, declarative built-ins, opaque custom transforms, bound readers, and mixed readers.
- Heap, sliced heap, native segment, and generic fallback storage.
- Structural deserialize-and-upgrade and the prior stream-backed input as baselines.

### Release gates

- Scalar `mvn clean verify`.
- Optional Vector build and tests.
- Full generated JMH suite, including allocation-profiler runs.
- `git diff --check` and hard-rename/API audits.

## Implementation phases and live ledger

The status values are `pending`, `in progress`, `blocked`, and `complete`. A phase is complete only after its focused validation passes.

| Phase | Status | Deliverable | Focused gate |
|---|---|---|---|
| 0 | complete | Preserve baseline, inventory existing unfinished work, establish this continuation ledger | Existing scalar test suite passes before the next hard break |
| 1 | complete | Introduce `DataCodec`, codec-owned skipping, custom `codec`/`fixedSize` config, migrate native/generated codecs | Runtime and generator codec tests |
| 2 | complete | Flatten generated current and historical values, arrays, nullables, ownership factories, singletons | Generated source compile + equality/ownership/allocation tests |
| 3 | complete | Add absolute/reservation cursor primitives and bulk primitive-array operations for all storage kinds | Cursor/property/malformed-input tests |
| 4 | complete | Introduce typed global read-plan IR, optimization passes, canonicalization, fixed-block scheduling | Golden generated-plan and cross-version equivalence tests |
| 5 | complete | Emit heap/native/fallback kernels and direct monomorphic bound dispatch | ClassFile/call-graph tests + all-storage reader tests |
| 6 | complete | Replace flat read-upgrader config with nested built-ins and custom read initializers | Config validation + transform semantic tests |
| 7 | complete | Add typed ephemeral wire views and reusable capture frames | View/context/failure-cleanup/allocation tests |
| 8 | complete | Add golden wire corpus and exhaustive historical equivalence/failure matrix | Golden byte and synthetic fixture suite |
| 9 | complete | Expand real generated-code JMH and allocation/JIT/code-size reporting | Benchmark compile + smoke matrix |
| 10 | complete | Isolate optional Vector kernels/profile and tune thresholds | Scalar build without incubator + Vector build/tests |
| 11 | complete | Documentation, strict audits, full scalar/Vector verification | All release gates pass |

## Progress log

- 2026-07-31: Plan persisted from the user-provided uncompacted design. Worktree is based on `9199804` and contains the earlier unfinished direct-input/fused-reader implementation. Yotsuba is explicitly out of scope.
- 2026-07-31: Phase 0 complete. `mvn clean verify` passed with 2,690 runtime tests and 23 generator tests (2,713 total). Inventory confirmed the former split serializer/skipper contract, wrapper-heavy generated models, source-string plan coalescing, and lambda-based bound dispatch remained to be replaced.
- 2026-07-31: Phase 1 complete. Added the hard-break `DataCodec<T>` contract and removed `DataSerializer`/`DataSkipper`; all native and generated codecs now own exact allocation-free skipping. Custom configuration is `codec` plus validated optional `fixedSize`, fixed custom spans participate in coalescing, generated `IVersion` exposes `getCodec`, cache serial is 14, and no compatibility keys or APIs remain. Focused runtime tests (2,690) and generator tests (25) pass; `git diff --check` and the hard-rename content audit are clean.
- 2026-07-31: Phase 2 complete. Generated current and historical schema values are final immutable classes with flattened primitive/reference nullables, owned raw arrays, safe copying factories/builders, explicit `unsafeOfOwned`, deep array value semantics, canonical zero-field/empty-array values, and no generated Java-serialization inheritance. Nullable boundary carriers are final classes with canonical empties. Array codecs/upgraders allocate the correctly typed final array once and reuse canonical empties. A clean focused gate passed 2,691 runtime tests plus 26 generated-source tests, including reflection-level storage, ownership, equality, codec-round-trip, and historical object-upgrade checks; `git diff --check` is clean.
- 2026-07-31: Phase 3 complete. `RandomAccessDataInput` and the shared direct cursor now expose one-check fixed-span reservation, unchecked absolute primitive access within reserved spans, and validate-before-allocation bulk kernels for every Java primitive array. Heap uses endian VarHandles and bulk byte copies, native uses finalized FFM layout-to-array conversion, and generic `Buf` uses direct fallback access. Native array codecs use these kernels. Unaligned heap, sliced-heap, native, and forced-fallback property tests plus overflow/truncation/atomic-position tests pass in a clean focused gate of 2,693 runtime tests and 26 generator tests; `git diff --check` is clean.
- 2026-07-31: Phase 4 complete. `ReadPlanCompiler` now produces immutable source-independent IR for native/custom/record/union/nullable/array shapes, live/skip scans, constructors, initializers, opaque transforms, constants, and recursively fused structural maps. It runs identity/constant/structural fusion, fixed-block scheduling, and concurrent structural hash-consing across generated types and versions. `GenReadPlan` coalesces versions by canonical IR identity and uses an explicit reader dependency graph; generated-source string comparison/search is gone. Mixed retained/skipped primitive fields emit one reserved fixed run with constant-offset loads and a wire-equivalent stream fallback. A clean focused gate passed 2,693 runtime tests and 26 cross-version generated-reader tests; `git diff --check` and the source-string-planning audit are clean.
- 2026-07-31: Phase 5 complete. The direct input core selects one heap, `MemorySegment`, or fallback storage strategy per binding, and concrete reusable cursors expose constant storage targets without slices, streams, or payload conversion. Every canonical fused plan is emitted as generic plus heap/native/fallback overloads; nested fused calls preserve the concrete cursor type. Mixed readers perform one storage and version selection per row, while each generated type/version-bound reader contains three final direct `readV*` call sites and no lambda/function decoder. Java 25 ClassFile assertions prove the bound class has no `invokedynamic` or version switch and calls the descriptor-matched concrete plan overload. Heap, sliced-heap, unaligned native, and forced-fallback success, truncation, trailing-byte, cleanup, and reuse checks pass in the clean focused gate of 2,693 runtime tests plus 26 generator tests; `git diff --check` and dispatch audits are clean.
- 2026-07-31: Phase 6 complete. `upgradeData` and `newData` now share a strict recursive `readTransform` tree with custom, constant, identity, direct static invocation, construction, counted `mapArray`, and branched `mapNullable` lowering. Nested schema types and malformed/ambiguous nodes are validated before emission; removed flat keys have no compatibility path. Custom read initializers and upgraders use primitive-specialized generated interfaces and reusable failure-safe frames, while plain stream reads retain the declared object transformation fallback. Declarative container maps emit typed loops with no lambdas, generic frames, or boxing dispatch. The focused gate passed 2,693 runtime tests and 29 generated-source tests; it also found and fixed an object-upgrader nullable conditional cast-precedence defect. `git diff --check` is clean.
- 2026-07-31: Phase 7 complete. Custom transforms now receive recursively typed reader-owned wire views for records, nullable values, unions, and arrays. Record fields, nullable payloads, and union variants expose structural subviews; arrays expose indexed materialization, one reusable rebinding element view, and a primitive-specialized sequential cursor. Direct views retain bounded field regions and lazy caches only, while delegating adapters preserve the same API after opaque boundaries. Every nested cursor, source, cache, parent/state binding, and child view is cleared on success and failure. The focused gate passed 2,693 runtime tests plus 33 generated-source tests, including nested record/nullable/union traversal, structural array cursor reuse, custom exceptions, and post-failure reader reuse; `git diff --check` is clean.
- 2026-07-31: Phase 8 complete. Added a frozen format-1 native corpus covering every scalar, nullable native, primitive array, and Int52 array, with byte-exact historical serialization, exact historical round-trip, and bound fused-current equivalence. The malformed-input matrix now covers negative/overflowed/truncated lengths and invalid union IDs before allocation. Recursive wire views are cycle-aware in both direct bounded-region and post-opaque delegating modes: child chains grow lazily to the observed depth, remain reader-owned for reuse, and clear every recursive source/value binding after success or failure. The clean focused gate passed 2,693 runtime tests plus 39 generated-source tests; `git diff --check` is clean.
- 2026-07-31: Phase 9 complete. Added the opt-in `datagen-benchmark` module whose JMH methods execute actual generated `CurrentVersion` bound/mixed/current readers, one-shot `BufDataInput`, the prior stream-backed shape, and materialize-then-upgrade controls across heap, sliced heap, native, and forced fallback storage. The matrix covers every primitive array size required by this plan, primitive-dense/string/nullable graphs, 16-way unions, custom/opaque transforms, context transforms, wire views, and all declarative built-ins. Java 25 ClassFile inspection verifies all 35 V0 bound readers have direct heap/native/fallback plan calls, no switch or `invokedynamic`, and a maximum generated reader method of 516 bytes; the scalar report is 213 generated files, 945,579 source bytes, 449,114 reader-class bytes, and 30,532 reader code bytes. Scalar and Vector generated smoke matrices pass. The full timed JMH/profiler execution had not run at this checkpoint; its completed evidence is recorded in the 2026-08-01 entries below.
- 2026-07-31: Phase 10 implementation and correctness gates complete. Added the isolated `datagen-vector` profile/artifact, generator/cache flag (cache serial 17), direct ephemeral storage access, and generated exact/fused array lowering. Preferred-species kernels cover boolean unpacking, big-endian short/char/int/long/float/double conversion, and packed seven-byte Int52 shuffles with scalar tails; byte arrays retain bulk copy. Exhaustive sizes/tails run over heap, sliced heap, unaligned native, and forced fallback storage, including truncation/overflow/reuse and no native payload conversion. `mvn -Pvector clean verify` passes 2,693 runtime, 39 generator, and 3 Vector matrix tests. Stable scalar classfiles contain no incubator linkage. The initial named heap/segment thresholds were deliberately conservative pending the timed per-type matrix completed below.
- 2026-07-31: Phase 11 implementation and every then-available gate complete. Rewrote the README for `DataCodec`, flattened owned values, mixed/version-bound readers, nested `readTransform`, typed recursive wire views, storage kernels, Vector isolation, and benchmark operation. Removed the obsolete no-op `useRecordBuilder` and `deepCheckBeforeCreatingNewEqualInstances` generator/Maven/cache options; builders are now unconditional, Maven flags are typed booleans, and the unused legacy list-wrapper plus RecordBuilder/Log4j/Commons-IO plugin dependencies are gone. Added scalar/Vector source-isolation checks and an executable full generated-reader smoke matrix; that smoke test found and fixed incorrect benchmark short-string prefixes and nullable Int52 framing. `mvn clean verify`, `mvn -Pvector clean verify`, scalar and Vector benchmark clean verifies, hard API/config audits, scalar incubator-linkage audit, script syntax validation, and `git diff --check` all pass. The full timed allocation/JIT and crossover evidence was still pending at this checkpoint and is completed below.
- 2026-08-01: The previously unavailable full scalar JMH gate completed on JDK 25 with all 104 parameter cases, one warmup fork plus one measured fork, five two-second warmup and measurement iterations, GC allocation profiling, and per-fork HotSpot compilation logs. The version-bound historical reader allocates exactly the same 416 B/op returned graph as the equivalent current reader on heap, sliced heap, native, and corrected fallback storage, versus 664 B/op for materialize-then-upgrade. It measured 23.02M versus 15.50M ops/s on heap, 23.07M versus 14.24M on sliced heap, and 12.73M versus 11.18M on native storage; version binding removed a measured 17.4% native dispatch penalty. Fused value/context transforms allocate 424 B/op versus 840 B/op at opaque boundaries. The corrected 104-entry dataset is `datagen-benchmark/target/generated-reader-reports-scalar/jmh-results-corrected.json`.
- 2026-08-01: JMH exposed and drove two follow-up fixes. The launcher now works without GNU `/usr/bin/time` and preserves its timing report across `mvn clean`; the forced-fallback benchmark uses a static storage-hiding `MemorySegmentBuf` subclass instead of an allocation-heavy dynamic proxy, and a 26-case fallback rerun proves returned-graph-only allocation. More importantly, native/fallback nullable-heavy reads exposed historical nullable carrier materialization hidden by heap escape analysis. `GenReadPlan` now lowers direct nullable fields into primitive `present`/value locals or nullable references, including the special Int52 sentinel and short String/BinaryString payload encodings; wrappers remain only where an opaque/declarative API requests them. Cache serial 18 forces regeneration. The affected nullable-heavy matrix now allocates 296 B/op identically on all four storage kinds (formerly 336 heap and 552 native/fallback), and generated reader class bytes fell from 449,114 to 425,652. Fresh `mvn clean verify`, `mvn -Pvector clean verify`, scalar benchmark clean verify, Vector benchmark verify, and `git diff --check` pass. Phases 10 and 11 now await the full timed Vector matrix and evidence-based threshold selection rather than an environment block.
- 2026-08-01: The full Vector generated-reader matrix completed all 104 unique cases on JDK 25 with the incubator module enabled, a warmup fork plus measured fork, five two-second warmup/measurement iterations, GC profiling, and per-fork HotSpot compilation logs. Allocation is identical to the corrected scalar graph in every fused path: bound current/historical readers are 416 B/op, fused custom/context transforms are 424 B/op versus 840 B/op at opaque boundaries, nullable-heavy reads are 296 B/op on every storage kind, and primitive arrays allocate only their returned graph. The report is `datagen-benchmark/target/generated-reader-reports-vector/jmh-results.json`.
- 2026-08-01: Phase 10 complete. Added nine isolated generated primitive-array fixtures plus `GeneratedPrimitiveArrayThresholdBench`, covering every type over heap/native sizes `0, 1, 2, 8, 16, 32, 64, 128, 256, 4096`; sliced heap shares the heap kernel and fallback never vectors. Paired scalar and final Vector runs each completed 180/180 unique cases with five one-second measured iterations. The first evidence pass found that per-lane `ShortVector` extraction made `char[]` 2-5x slower; `VectorArraySupport` now stores directly into the owned `char[]` memory segment. It also found non-monotonic heap crossovers and a large-char segment-view escape, so selection is range-specialized: boolean `128+`, short `64+`, char `64..256`, heap int `32..128`, native int `32+`, heap long `16..64`, native long `16+`, float `32+`, double `16+`, and Int52 `16+`; byte remains bulk scalar. Every measured point inside an enabled range is a win or confidence-interval overlap, with no loss; decisive ranges span 1.20-6.59x on heap and 1.05-42.56x on native. All 180 final Vector cases preserve scalar allocation shape (maximum normalized difference below 1 B/op measurement resolution), including exact 8,224 B/op graph allocation for 4,096-element char arrays. Final generated shape is 240 source files/1,064,941 bytes, 88 reader classes/508,725 bytes, 1,625 methods/34,519 code bytes, 516-byte maximum method, and 44 verified direct V0 bound readers. Reports are `generated-reader-reports-scalar-thresholds/jmh-results.json` and `generated-reader-reports-vector-thresholds-final/jmh-results.json`.
- 2026-08-01: Phase 11 complete. README documentation now covers the hard-break `DataCodec`/flattened-value/read-transform APIs, storage-specialized and version-bound readers, bounded Vector ranges, the isolated threshold fixture, named report preservation, and exact benchmark commands. Final `mvn clean verify` passes 2,693 runtime and 39 generator tests; final `mvn -Pvector clean verify` passes the same suites plus all 3 Vector tests. The complete scalar and Vector 104-case reports, paired 180-case threshold reports, GC profiles, generated-code reports, and HotSpot logs are retained under `datagen-benchmark/target/generated-reader-reports-*`. Shell syntax, hard `deserialize`/`DataSerializer`/`DataSkipper` and removed production-config audits, native whole-payload-conversion audit, and `git diff --check` all pass.
- 2026-08-01: Added `adversarial-evolution.yaml`, a 32-version, 11-base-type, 3-custom-codec history containing 113 moves/removals/initializers/upgrades. It deliberately combines fixed and variable skips, context-dependent transforms, recursive array/nullable maps, four-way unions, projections, no-op version coalescing, and an opaque same-type upgrader followed by a fused structural tail. `AdversarialEvolutionTest` generates and compiles both writable-history and `generateOldSerializers=false` trees, constructs real generated values for every version, freezes each exact codec payload, and compares exact-read-plus-object-upgrade against stream, mixed, version-bound, heap, sliced-heap, native, native-slice, and forced-fallback fused reads. It also verifies projection equivalence, all union variants and nullable/container states, per-version truncation/trailing/discriminator/custom-codec failure recovery, exact codec read/skip rejection, full cursor/frame cleanup, 32 direct no-switch/no-`invokedynamic` bound call graphs, and a 32,768-byte generated-method ceiling. The suite exposed raw `IndexOutOfBoundsException` leakage for malformed union IDs; all generated codec, fused read/skip, projection, and object-union upgrade paths now report an explicit `IllegalArgumentException`. Fresh scalar and Vector `clean verify` gates pass 2,693 runtime tests, 40 generator tests, and 3 Vector tests; `git diff --check` is clean.

## Continuation protocol

After any context compaction:

1. Read this file completely.
2. Inspect `git status --short` without resetting or discarding changes.
3. Resume the first non-complete phase in the ledger.
4. Re-run that phase's focused gate before marking it complete.
5. Append a concise progress-log entry naming implemented files and test evidence.
6. Never claim a pending phase is implemented.
