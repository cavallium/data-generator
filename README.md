Data Generator
==============

Data Generator is a Maven plugin and small runtime library that turns a single YAML schema into:
- Versioned, strongly‑typed Java data classes and interfaces
- Compact, allocation‑aware binary serializers/deserializers
- Automatic upgraders that can read any historical version and upgrade it to the latest

You define your types, interfaces, versions, and in‑schema transformations. The plugin generates
lightweight code that knows how to serialize/deserialize and evolve your models across versions.

Why this library
----------------
- Zero metadata on the wire: the binary format contains only the data — no field names, no tags
- Version upgrades by construction: describe transformations in YAML, get upgraders for free
- High performance: uses precomputed serializers and native‑like buffers in the runtime
- Java‑first: outputs idiomatic Java, with optional record builders
- Extensible: plug in custom types and custom serializers

Typical use cases
-----------------
- Persisted event/data logs that must remain readable and upgradeable over time
- Network protocols between services where payloads should be small and schema‑driven
- Snapshot files or caches that must be migrated to newer schema versions without replay

Project modules
---------------
- datagen-plugin: the Maven plugin that reads your YAML and generates sources
- datagen: the tiny runtime with buffer utilities and primitive/array serializers

Quick start
-----------
1) Add the plugin to your project

```xml
<build>
  <plugins>
    <plugin>
      <groupId>it.cavallium</groupId>
      <artifactId>datagen-plugin</artifactId>
      <version>${datagen.version}</version>
      <executions>
        <execution>
          <goals>
            <goal>run</goal>
          </goals>
          <phase>generate-sources</phase>
          <configuration>
            <!-- Path to your YAML schema -->
            <configPath>${project.basedir}/src/main/resources/model.yaml</configPath>
            <!-- Base Java package for generated code -->
            <basePackageName>com.example.model</basePackageName>
            <!-- Optional flags -->
            <useRecordBuilder>false</useRecordBuilder>
            <generateOldSerializers>false</generateOldSerializers>
            <deepCheckBeforeCreatingNewEqualInstances>true</deepCheckBeforeCreatingNewEqualInstances>
            <binaryStrings>false</binaryStrings>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
  <!-- Generated sources are added automatically by the plugin -->
  <pluginManagement/>
</build>
```

2) Author your YAML schema
--------------------------
YAML structure at a glance (keys map 1:1 to plugin config classes):
- `currentVersion`: the version key that is the current/target schema
- `interfacesData`: reusable interface fragments (common getters/data)
- `baseTypesData`: concrete types and their fields
- `superTypesData`: map of type → list of interfaces it implements
- `customTypesData`: map of logical type → custom Java class and its serializer
- `versions`: ordered map of versionKey → version definition

Primitive type notation and modifiers:
- Plain type: `int`, `long`, `boolean`, `double`, `String`, other base/custom type names
- Nullable: prefix with `-` (e.g., `-String`)
- Arrays: suffix `[]` (e.g., `int[]`, `User[]`)
  - Arrays cannot be nullable (i.e., `-X[]` is not allowed)

Example: minimal schema with two versions
-----------------------------------------
```yaml
# src/main/resources/model.yaml

currentVersion: v2

interfacesData:
  Identified:
    commonGetters:
      id: long

baseTypesData:
  User:
    stringRepresenter: username         # which field represents this object as string (optional)
    data:
      id: long
      username: String
      age: int

superTypesData:
  User: [Identified]

versions:
  v1:
    details:
      description: "Initial version"
    # First version has no previousVersion and typically no transformations

  v2:
    previousVersion: v1
    details:
      description: "Rename username→handle and add createdAt"
    transformations:
      - moveData:            # Applies to a single class
          transformClass: User
          from: username
          to: handle
      - newData:
          transformClass: User
          name: createdAt
          type: long
          defaultValue: 0
```

Supported transformations
-------------------------
In `versions.<ver>.transformations` you can specify exactly one of these per entry:
- `moveData`: rename/move a field, optionally reordering with `index`
- `removeData`: drop a field
- `newData`: introduce a new field with a default
- `upgradeData`: change a field type with an upgrade expression

Example transformations entries (structure mirrors `VersionTransformation` and friends):
```yaml
transformations:
  - moveData:
      transformClass: User
      from: name
      to: fullName
      index: 0          # optional placement index
  - removeData:
      transformClass: User
      from: deprecatedField
  - newData:
      transformClass: User
      name: tags
      type: String[]
      defaultValue: []
  - upgradeData:
      transformClass: User
      field: age
      fromType: int
      toType: long
      expression: "(long)age"   # simple Java expression evaluated in upgrader
```

Custom types
------------
You can map logical types to your own Java classes and provide a serializer:
```yaml
customTypesData:
  Money:
    javaClass: com.example.types.Money
    serializer: com.example.types.MoneySerializer

baseTypesData:
  Invoice:
    data:
      total: Money
      items: Money[]
```

Interfaces and shared data
--------------------------
Interfaces can define shared getters and data chunks. Types listed in `superTypesData` implement them.
```yaml
interfacesData:
  Audited:
    extendInterfaces: []
    commonData:
      createdAt: long
      updatedAt: long
    commonGetters:
      createdAt: long
      updatedAt: long

superTypesData:
  User: [Audited]
```

What is generated
-----------------
For each version, the plugin generates a package under your `basePackageName`, for example:
- `com.example.model.v1` and `com.example.model.v2`
- Base type interfaces and data classes (optionally records/builders)
- Per‑type serializers/deserializers
- Upgraders to move from `v(n)` to `v(n+1)`
- A `CurrentVersion` utility with shortcuts to current types/serializers

Example generated code (simplified)
-----------------------------------
```java
// com.example.model.v2.User
public final class User implements IBaseType {
  private final long id;
  private final String handle;
  private final long createdAt;
  // getters, equals/hashCode, toString (using stringRepresenter if present)
}

// com.example.model.v2.serializers.UserSerializer
public final class UserSerializer {
  public static void serialize(SafeDataOutput out, User data) {
    Serializers.longSerializer().serialize(out, data.getId());
    Serializers.stringSerializer().serialize(out, data.getHandle());
    Serializers.longSerializer().serialize(out, data.getCreatedAt());
  }
  public static User deserialize(SafeByteArrayInputStream in) {
    long id = Serializers.longSerializer().deserialize(in);
    String handle = Serializers.stringSerializer().deserialize(in);
    long createdAt = Serializers.longSerializer().deserialize(in);
    return new User(id, handle, createdAt);
  }
}

// com.example.model.v1to2.UserUpgrader (conceptually)
public final class UserUpgrader {
  public static com.example.model.v2.User upgrade(com.example.model.v1.User old) {
    return new com.example.model.v2.User(
      old.getId(),
      old.getUsername(),     // moved to handle
      0L                     // default createdAt
    );
  }
}
```

Reading and upgrading data
--------------------------
Generated code can:
- Serialize a current type to bytes
- Deserialize older versions and upgrade to current automatically

Example usage (runtime helpers come from the `datagen` module):
```java
// Serialize
var out = new ByteArrayOutputStream();
var dataOut = new SafeDataOutput(out);
com.example.model.v2.serializers.UserSerializer.serialize(dataOut, user);
byte[] bytes = out.toByteArray();

// Deserialize (current)
var in = new SafeByteArrayInputStream(bytes);
User user2 = com.example.model.v2.serializers.UserSerializer.deserialize(in);

// If you store version tags externally, you can route to the right deserializer
// and then call the generated upgrader to move to the current version.
```

Binary format and performance
-----------------------------
- The binary format is field‑position based and schema‑driven; no names/tags are written
- Primitive and array serializers are hand‑written; strings can be encoded as binary (flag `binaryStrings`)
- Buffers and serializers try to minimize intermediate copies

Plugin parameters reference
---------------------------
- `configPath` (required): path to YAML file
- `basePackageName` (required): base Java package for generated sources
- `useRecordBuilder` (default false): generate record builder style classes
- `generateOldSerializers` (default false): also generate serializers for old versions
- `deepCheckBeforeCreatingNewEqualInstances` (default true): extra equality checks during generation
- `generateTestResources` (default false): place generated sources under test‑sources
- `binaryStrings` (default false): use a compact binary string encoding in runtime

YAML reference (high level)
---------------------------
- `currentVersion`: string key of the current version (must exist in `versions`)
- `versions.<ver>`:
  - `previousVersion`: points to the previous version key (except for the first)
  - `transformations`: list of `moveData` | `removeData` | `newData` | `upgradeData` entries
  - `typeVersions` and `dependentTypes`: advanced controls for per‑type activation and ordering
- `baseTypesData.<Type>`:
  - `stringRepresenter`: optional, designates which field is used in `toString`
  - `data`: ordered map of `field: type`
- `interfacesData.<Interface>`:
  - `extendInterfaces`: list of interface names to extend
  - `commonData`: shared `field: type` pairs
  - `commonGetters`: shared getters `name: type`
- `superTypesData`:
  - map of `Type: [Interface, ...]` that the type implements
- `customTypesData.<LogicalType>`:
  - `javaClass`: fully‑qualified class name for the custom type
  - `serializer`: fully‑qualified class name implementing its serializer

Limitations and notes
---------------------
- Arrays cannot be nullable (`-X[]` is rejected)
- When changing field order, prefer `moveData` with `index` to keep binary compatibility expectations explicit
- Keep `typeVersions`/`dependentTypes` for advanced multi‑version layouts only

Building and running
--------------------
- Java 25 toolchain (see module POMs)
- Run `mvn clean package` to build
- The plugin will generate code under `target/generated-sources/database-classes/java`

Examples directory
------------------
Minimal YAML lives in your project; a placeholder test resource exists in this repo under
`datagen-plugin/src/test/resources/test.yaml`.

License
-------
See project licensing terms in the repository. If missing, assume All Rights Reserved by the author unless stated otherwise.
