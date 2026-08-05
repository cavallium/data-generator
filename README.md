# Data Generator

Data Generator compiles a versioned YAML schema into immutable Java values, exact wire codecs,
object-to-object upgraders, projections, and allocation-minimal readers that decode any historical
wire version directly into the current object graph.

The format has no field metadata. Field order, length prefixes, nullable markers, union
discriminators, byte order, and every historical layout are therefore part of the permanent wire
contract. The current read engine changes generated code and in-memory representation, not those
bytes.

The project targets Java 25.

## Modules

- `datagen-plugin` compiles schemas and emits Java source.
- `datagen` is the stable runtime: buffers, cursors, codecs, native values, and read support.
- `datagen-benchmark` is an opt-in generated JMH matrix (`-Pbenchmark`).
- `datagen-vector` is an opt-in incubator Vector API accelerator (`-Pvector`). Stable runtime code
  has no dependency on `jdk.incubator.vector`.

## Maven plugin

```xml
<plugin>
  <groupId>it.cavallium</groupId>
  <artifactId>datagen-plugin</artifactId>
  <version>${datagen.version}</version>
  <executions>
    <execution>
      <goals><goal>run</goal></goals>
      <configuration>
        <configPath>${project.basedir}/src/main/datagen/model.yaml</configPath>
        <basePackageName>com.example.model</basePackageName>
        <generateOldSerializers>false</generateOldSerializers>
        <binaryStrings>false</binaryStrings>
        <vectorKernels>false</vectorKernels>
      </configuration>
    </execution>
  </executions>
</plugin>
```

`generateOldSerializers=false` removes unnecessary historical serialization entry points; fused
historical reads are still generated. `vectorKernels=true` is only valid when the consuming module
also depends on `datagen-vector` and compiles/runs with
`--add-modules jdk.incubator.vector`.

Generation is content-hashed. All flags that affect output, including Vector lowering, participate
in the cache key.

## Schema basics

```yaml
currentVersion: v3

interfacesData:
  Identified:
    commonGetters:
      id: long

baseTypesData:
  User:
    data:
      id: long
      handle: String
      reputation: -int
      aliases: String[]

superTypesData:
  User: [Identified]

versions:
  v1: {}
  v2:
    previousVersion: v1
    transformations:
      - moveData:
          transformClass: User
          from: username
          to: handle
      - newData:
          transformClass: User
          to: reputation
          type: -int
          initializer: com.example.model.ReputationInitializer
  v3:
    previousVersion: v2
```

Type notation:

- primitives: `boolean`, `byte`, `short`, `char`, `int`, `long`, `float`, `double`, `Int52`;
- native references: `String`, `BinaryString`;
- schema/custom types by name;
- nullable values with `-`, for example `-int` and `-User`;
- owned arrays with `[]`, for example `long[]` and `User[]`.

Transformations are `moveData`, `removeData`, `newData`, and `upgradeData`. Their declared object
initializer/upgrader remains the semantic path for already-materialized values and ordinary
non-random-access input.

## Codec contract

Serialization, reading, and skipping are one indivisible wire contract:

```java
public interface DataCodec<T> {
    void serialize(SafeDataOutput output, T data);
    T read(SafeDataInput input);
    void skip(SafeDataInput input);
    default Reader<T> newReader();
}
```

`DataCodec.Reader<T>` is reusable and thread-confined. Its `read(Buf)` and
`read(Buf, offset, length)` methods require complete bounded-region consumption, reject trailing
bytes, and clear the source in `finally`. Create one reader per worker lane; there is deliberately
no one-shot `Buf` convenience method that hides cursor allocation.

Custom types declare the codec once:

```yaml
customTypesData:
  Money:
    javaClass: com.example.types.Money
    codec: com.example.types.MoneyCodec
    fixedSize: 16       # optional; only when every encoding is exactly 16 bytes
```

The codec must not retain its input or source buffer. `skip` must consume exactly one value.
`fixedSize` lets the compiler merge custom fields into adjacent one-check fixed blocks.

There are no serializer/skipper aliases and no `deserialize` API.

## Generated immutable values

Current and historical schema records are generated as final immutable classes:

- nullable references are stored as `null` and exposed through `hasX()`, `x()`, and `xOrNull()`;
- nullable primitives use a boolean presence field plus an unboxed primitive and expose `hasX()`
  and `x()`;
- arrays are stored directly as owned primitive/reference arrays, not lists;
- an array field `items` exposes `itemsSize()`, `items(index)`, `itemsCopy()`, and
  `itemsUnsafeArray()`;
- public `of(...)`, builders, and withers copy caller arrays;
- generated readers call `unsafeOfOwned(...)` only for freshly decoded arrays whose ownership is
  transferred to the value;
- equality, hashing, and string rendering use deep array semantics;
- zero-field values and all empty arrays are canonical singletons.

`unsafeOfOwned(...)` and `xUnsafeArray()` are explicit expert APIs. Mutating a transferred or
exposed backing array violates the value's immutability contract.

## Reading historical rows directly into the current version

Generated `CurrentVersion` exposes three paths:

```java
Current current = CurrentVersion.read(version, BaseType.Current, safeDataInput);

CurrentVersion.Reader<Current> mixed = CurrentVersion.newReader(BaseType.Current);
Current a = mixed.read(version, source);

CurrentVersion.BoundReader<Current> bound =
        CurrentVersion.newReader(version, BaseType.Current);
Current b = bound.read(source);
Current c = bound.read(container, offset, length);
```

Use a bound reader when a worker lane processes one type/version. It selects both once and exposes a
stable hot method with no row-level version switch. A mixed reader selects storage and version once
per row. Both select heap-array, native `MemorySegment`, or generic-`Buf` storage without creating a
slice, stream, byte-buffer view, or whole-payload copy.

`CurrentVersion.upgradeDataToLatestVersion(version, oldObject)` remains available for an already
materialized historical value. For serialized data, use `CurrentVersion.read` or a reusable reader;
materialize-then-upgrade is intentionally not the normal path.

The generated read compiler performs dependency liveness, dead historical field elimination,
recursive structural fusion, transform fusion, fixed-block scheduling, and structural
canonicalization. Adjacent retained and skipped fixed fields share one bounds check. Historical
records, nullable carriers, list wrappers, context records, and structural upgrade chains are not
created unless an opaque user boundary explicitly requires a historical object.

## Allocation-minimal `readTransform`

`newData` and `upgradeData` may add a nested `readTransform`. It is used only by the fused
serialized-data path; the declared `initializer` or `upgrader` remains required for object semantics.
Exactly one operation is allowed at each transform node:

- `constant`;
- `identity`;
- `invokeStatic`;
- `construct`;
- recursive `mapNullable`;
- recursive `mapArray`;
- `custom`.

Declarative operations emit direct typed Java calls. They create no generic transform frame,
lambda, boxed primitive argument, or interface dispatch. For example:

```yaml
- upgradeData:
    transformClass: Metric
    from: samples
    type: long[]
    upgrader: com.example.SamplesObjectUpgrader
    readTransform:
      mapArray:
        source:
          identity: { source: value }
        transform:
          invokeStatic:
            method: com.example.Conversions.widen
            arguments:
              - identity: { source: value }

- newData:
    transformClass: Metric
    to: sourceKind
    type: int
    initializer: com.example.SourceKindInitializer
    readTransform:
      constant: { value: 7 }
```

Built-in references are statically typed:

- `value` and `value.<path>`;
- `currentValue` and `currentValue.<path>`;
- `context.<field>` and deeper paths;
- `currentContext.<field>` and deeper paths;
- literals through `constant`.

An optional root `readTransform.type` says that the transform directly returns that later/final
schema type. The compiler validates it and fuses the remaining structural evolution rather than
building intermediate historical values.

### Custom transforms and wire views

```yaml
- upgradeData:
    transformClass: Event
    from: payload
    type: CurrentPayload
    upgrader: com.example.PayloadObjectUpgrader
    contextParameters: [header]
    readTransform:
      type: CurrentPayload
      custom:
        className: com.example.PayloadWireUpgrader
```

The generator emits an exact primitive-specialized custom interface and an ephemeral typed input.
Structural values are exposed as generated wire views:

- records lazily expose typed fields;
- unions expose an exact `Kind` enum and typed variant views;
- nullable views expose presence plus a typed value view;
- arrays expose size, indexed materialization, a reusable element view, and sequential iteration;
- historical and current structural accessors are available when required by the transform;
- context parameters have historical, current, and wire-view accessors.

The main record scan captures only required variable-field regions in reader-owned reusable state;
view getters do not rescan their owner. Recursive view chains grow lazily to the observed depth.
Views, cursors, and inputs are valid only during the custom call and must never be retained.

Opaque codecs may expose a bounded raw cursor. The custom code must consume it completely.
`value()`, `currentValue()`, and the raw serialized cursor are mutually exclusive for one binding.
All bindings and cached graphs are cleared after success or failure.

## Primitive-array storage kernels

The stable runtime validates array length arithmetic and reserves the complete payload before
allocating the result. Heap kernels use bulk copies and big-endian VarHandles; native kernels use
finalized FFM layouts; fallback kernels use direct `Buf` access. Each nonempty primitive array is
allocated once.

The optional `datagen-vector` artifact adds preferred-species kernels for boolean unpacking,
big-endian conversion, floating-point bit preservation, and packed seven-byte Int52 blocks, with
scalar tails. Byte arrays retain the faster bulk-copy kernel. Crossover constants are public named
constants in `VectorArraySupport` and are exercised by the generated size/storage JMH matrix.
Kernel selection is range-based where the measurements are non-monotonic: heap `int` and `long`
return to HotSpot's scalar VarHandle loop above their measured upper crossover. The `char` kernel
stores `ShortVector` values directly into the owned `char[]` segment for its measured range and
returns to scalar above it, avoiding both per-lane extraction and a large-array segment-view escape.

To build Vector-generated code:

```sh
mvn -Pvector -pl datagen-vector -am verify
mvn -Pbenchmark,vector -pl datagen-benchmark -am verify
```

Consumers must add `datagen-vector` and pass `--add-modules jdk.incubator.vector` at compile and run
time. A normal scalar build neither resolves nor links any incubator class.

## Projection readers

Projections retain their early-stop semantics for callers that intentionally need only selected
fields:

```yaml
projectionsData:
  ImportedMessageSender:
    sourceType: ImportedMessage
    fields:
      messageId: messageId
      senderId: sender.id
      chatEntityId: chatEntityId
```

Generated projections expose `read(version, input)`, `readInto(version, input, sink)`, and a reusable
bounded reader. Normal current-version readers, unlike projections, always consume the complete
bounded object.

## Verification and benchmarks

Stable release gate:

```sh
mvn clean verify
```

Optional generated benchmark build:

```sh
mvn -Pbenchmark -pl datagen-benchmark -am verify
```

The generated matrix uses actual `CurrentVersion` code for bound/mixed historical and current
readers, one-shot `BufDataInput`, the prior stream-backed shape, materialize-then-upgrade controls,
declarative/custom transforms, primitive-dense records, all primitive arrays at sizes
`0, 1, 2, 8, 32, 256, 4096`, strings, nullable-heavy graphs, large unions, and every storage kind.
It also verifies classfiles for direct bound kernel calls, no bound version switch, no
`invokedynamic`, and bounded method size.

`GeneratedPrimitiveArrayThresholdBench` isolates every primitive-array type in a monomorphic
generated bound reader over heap and native storage. It adds sizes `16`, `64`, and `128` around the
candidate crossovers; sliced heap shares the heap kernel, while generic fallback never enters the
Vector module and remains covered by the complete matrix.

Run the complete scalar matrix with allocation profiling and JIT logs:

```sh
datagen-benchmark/run-generated-matrix.sh
```

Run the same generated matrix with Vector lowering:

```sh
DATAGEN_VECTOR=1 datagen-benchmark/run-generated-matrix.sh
```

Run paired per-type threshold matrices without overwriting the complete reports:

```sh
DATAGEN_REPORT_NAME=scalar-thresholds \
  datagen-benchmark/run-generated-matrix.sh \
  it.cavallium.datagen.benchmark.GeneratedPrimitiveArrayThresholdBench

DATAGEN_VECTOR=1 DATAGEN_REPORT_NAME=vector-thresholds \
  datagen-benchmark/run-generated-matrix.sh \
  it.cavallium.datagen.benchmark.GeneratedPrimitiveArrayThresholdBench
```

Reports include build time/RSS, generated source and bytecode size, maximum method size, JMH JSON,
GC allocation data, HotSpot compilation logs, and code-cache output under
`datagen-benchmark/target/generated-reader-reports-<name>`. `DATAGEN_REPORT_NAME` selects `<name>`;
it defaults to `scalar` or `vector`, and the launcher preserves other named report directories
across its clean build.

## Compatibility model

- Wire compatibility is permanent and covered by frozen format-1 golden payloads.
- Generated Java source/binary compatibility, record identity, Java serialization identity, and
  obsolete wrapper APIs are not preserved.
- Readers are thread-confined.
- Decoded values own their arrays.
- Custom codecs and transforms must not retain inputs, sources, cursors, or ephemeral views.
