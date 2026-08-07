package it.cavallium.datagen.plugin.classgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeNative;
import it.cavallium.datagen.plugin.ComputedVersion;
import it.cavallium.datagen.plugin.CustomTypesConfiguration;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.ReadTransformConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.VersionTransformation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Deep grammar fuzzing of the source-independent historical read-plan compiler. */
class ReadPlanCompilerDeepFuzzTest {

	private static final long PLAN_SEED = 0x43E9_18B7_C2D0_6A5FL;
	private static final int PLAN_CASES = 2_000;

	@Test
	void randomizedEvolutionPlansAreCanonicalDeterministicAndScanEveryWireFieldExactlyOnce() {
		var random = new Random(PLAN_SEED);
		for (int caseIndex = 0; caseIndex < PLAN_CASES; caseIndex++) {
			Schema schema = randomSchema(random, caseIndex);
			DataModel model = schema.configuration().buildDataModel(random.nextBoolean());
			ReadPlanCompiler compiler = new ReadPlanCompiler(model,
					message -> new IllegalArgumentException("fuzz-plan: " + message));
			ReadPlanCompiler secondCompiler = new ReadPlanCompiler(model,
					message -> new IllegalArgumentException("second-plan: " + message));
			ComputedTypeBase target = (ComputedTypeBase) model.getComputedTypes(model.getCurrentVersion()).get("Root");
			String diagnostic = "seed=" + PLAN_SEED + ", case=" + caseIndex
					+ ", versions=" + model.getVersionsSet().size();

			for (int logicalVersion = 0; logicalVersion <= model.getCurrentVersionNumber(); logicalVersion++) {
				ComputedVersion version = model.getVersion(logicalVersion);
				ComputedTypeBase input = compiler.requireBase(logicalVersion, "Root");
				ReadPlanCompiler.Plan first = compiler.compile(input, target);
				ReadPlanCompiler.Plan interned = compiler.compile(input, target);
				ReadPlanCompiler.Plan independentlyCompiled = secondCompiler.compile(input, target);
				assertSame(first, interned, diagnostic + ", version=" + logicalVersion);
				assertEquals(first, independentlyCompiled, diagnostic + ", version=" + logicalVersion);
				assertEquals(target.getName(),
						((ReadPlanCompiler.RecordShape) first.construction().resultShape()).name(), diagnostic);
				assertScanPartition(first.scan(), input.getData().size(), diagnostic + ", version=" + logicalVersion);
				assertExpressionTree(first.construction(), input.getData().size(), diagnostic);

				for (String field : compiler.requireBase(logicalVersion, "Root").getData().keySet()) {
					if (logicalVersion > 0) {
						ReadPlanCompiler.FieldOrigin origin = compiler.traceFieldOrigin(logicalVersion, "Root", field);
						assertTrue(origin.previousName() != null || origin.initializer() != null,
								diagnostic + ", field=" + field + ", version=" + logicalVersion);
					}
				}
				assertSame(model.getComputedTypes(version).get("Root"), compiler.typeNamed(logicalVersion, "Root"));
			}

			for (ComputedVersion version : model.getVersionsSet()) {
				for (ComputedType type : model.getComputedTypes(version).values()) {
					int minimum = compiler.minimumSerializedSize(type);
					assertTrue(minimum >= 0, diagnostic + ", type=" + type + ", minimum=" + minimum);
					assertEquals(minimum, compiler.minimumSerializedSize(type), diagnostic);
					if (type instanceof ComputedTypeNative nativeType) {
						assertEquals(nativeMinimum(nativeType.getName()), minimum,
								diagnostic + ", native=" + nativeType.getName());
					}
				}
			}

			String missingType = "Missing" + caseIndex;
			IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
					() -> compiler.typeNamed(0, missingType));
			assertTrue(unknown.getMessage().startsWith("fuzz-plan: unknown type"));
			assertThrows(IllegalArgumentException.class, () -> compiler.requireBase(0, "int"));
			ComputedTypeBase leaf = compiler.requireBase(model.getCurrentVersionNumber(), "Leaf");
			assertThrows(IllegalArgumentException.class, () -> compiler.compile(target, leaf));
		}
	}

	@Test
	void fixedAndDynamicSchedulingCoalescesMaximalRunsForHostileFieldOrders() {
		var random = new Random(PLAN_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < PLAN_CASES; caseIndex++) {
			SourcesGeneratorConfiguration configuration = schedulingSchema(random, caseIndex);
			DataModel model = configuration.buildDataModel(false);
			var compiler = new ReadPlanCompiler(model, IllegalArgumentException::new);
			ComputedTypeBase root = compiler.requireBase(0, "Root");
			ReadPlanCompiler.Plan plan = compiler.compile(root, root);
			List<ComputedType> fields = List.copyOf(root.getData().values());
			int operationIndex = 0;
			int fieldIndex = 0;
			while (fieldIndex < fields.size()) {
				ComputedType field = fields.get(fieldIndex);
				boolean fixed = fixedSize(field) != null;
				ReadPlanCompiler.ScanOperation operation = plan.scan().get(operationIndex++);
				if (!fixed) {
					assertTrue(operation instanceof ReadPlanCompiler.ReadDynamic,
							"case=" + caseIndex + ", field=" + fieldIndex + ", op=" + operation);
					assertEquals(fieldIndex, ((ReadPlanCompiler.ReadDynamic) operation).fieldIndex());
					fieldIndex++;
				} else {
					assertTrue(operation instanceof ReadPlanCompiler.FixedBlock,
							"case=" + caseIndex + ", field=" + fieldIndex + ", op=" + operation);
					ReadPlanCompiler.FixedBlock block = (ReadPlanCompiler.FixedBlock) operation;
					int bytes = 0;
					for (ReadPlanCompiler.FixedField fixedField : block.fields()) {
						assertEquals(fieldIndex, fixedField.fieldIndex());
						assertEquals(bytes, fixedField.byteOffset());
						assertEquals(fixedSize(fields.get(fieldIndex)).intValue(), fixedField.byteSize());
						assertEquals(ReadPlanCompiler.FieldUse.READ, fixedField.use());
						bytes += fixedField.byteSize();
						fieldIndex++;
					}
					assertEquals(bytes, block.byteSize());
					assertTrue(fieldIndex == fields.size() || fixedSize(fields.get(fieldIndex)) == null,
							"fixed run was split at case=" + caseIndex);
				}
			}
			assertEquals(plan.scan().size(), operationIndex);
		}
	}

	private static void assertScanPartition(List<ReadPlanCompiler.ScanOperation> scan,
			int fieldCount,
			String diagnostic) {
		var seen = new ArrayList<Integer>();
		for (ReadPlanCompiler.ScanOperation operation : scan) {
			switch (operation) {
				case ReadPlanCompiler.FixedBlock block -> {
					int offset = 0;
					for (ReadPlanCompiler.FixedField field : block.fields()) {
						assertEquals(offset, field.byteOffset(), diagnostic);
						assertTrue(field.byteSize() >= 0, diagnostic);
						offset += field.byteSize();
						seen.add(field.fieldIndex());
					}
					assertEquals(offset, block.byteSize(), diagnostic);
				}
				case ReadPlanCompiler.ReadDynamic dynamic -> seen.add(dynamic.fieldIndex());
				case ReadPlanCompiler.SkipDynamic dynamic -> seen.add(dynamic.fieldIndex());
			}
		}
		assertEquals(java.util.stream.IntStream.range(0, fieldCount).boxed().toList(), seen, diagnostic);
	}

	private static void assertExpressionTree(ReadPlanCompiler.Expression expression,
			int inputFields,
			String diagnostic) {
		assertNotNull(expression.resultShape(), diagnostic);
		assertTrue(expression.resultShape().minimumSerializedSize() >= 0, diagnostic);
		switch (expression) {
			case ReadPlanCompiler.Source source -> assertTrue(source.fieldIndex() >= 0
					&& source.fieldIndex() < inputFields, diagnostic + ", source=" + source.fieldIndex());
			case ReadPlanCompiler.Constant ignored -> { }
			case ReadPlanCompiler.Initialize initialize -> initialize.context()
					.forEach(value -> assertExpressionTree(value, inputFields, diagnostic));
			case ReadPlanCompiler.Transform transform -> {
				assertExpressionTree(transform.value(), inputFields, diagnostic);
				transform.context().forEach(value -> assertExpressionTree(value, inputFields, diagnostic));
			}
			case ReadPlanCompiler.Convert convert -> assertExpressionTree(convert.value(), inputFields, diagnostic);
			case ReadPlanCompiler.MapNullable map -> {
				assertFalse(map.path().isEmpty(), diagnostic);
				assertExpressionTree(map.value(), inputFields, diagnostic);
			}
			case ReadPlanCompiler.MapArray map -> {
				assertFalse(map.path().isEmpty(), diagnostic);
				assertExpressionTree(map.value(), inputFields, diagnostic);
			}
			case ReadPlanCompiler.MapRecord map -> {
				assertFalse(map.path().isEmpty(), diagnostic);
				assertExpressionTree(map.value(), inputFields, diagnostic);
			}
			case ReadPlanCompiler.MapUnion map -> {
				assertFalse(map.path().isEmpty(), diagnostic);
				assertExpressionTree(map.value(), inputFields, diagnostic);
			}
			case ReadPlanCompiler.Construct construct -> construct.fields()
					.forEach(value -> assertExpressionTree(value, inputFields, diagnostic));
		}
	}

	private static int nativeMinimum(String name) {
		return switch (name) {
			case "boolean", "byte" -> 1;
			case "short", "char" -> 2;
			case "int", "float", "String" -> 4;
			case "long", "double" -> 8;
			case "Int52" -> 7;
			default -> throw new AssertionError(name);
		};
	}

	private static Integer fixedSize(ComputedType type) {
		if (type instanceof ComputedTypeNative nativeType) {
			return switch (nativeType.getName()) {
				case "boolean", "byte" -> 1;
				case "short", "char" -> 2;
				case "int", "float" -> 4;
				case "long", "double" -> 8;
				case "Int52" -> 7;
				case "String" -> null;
				default -> throw new AssertionError(nativeType.getName());
			};
		}
		if (type instanceof it.cavallium.datagen.plugin.ComputedTypeCustom custom) return custom.getFixedSize();
		return null;
	}

	private static Schema randomSchema(Random random, int caseIndex) {
		var configuration = baseConfiguration();
		var active = new LinkedHashMap<String, String>();
		active.put("anchor", "int");
		active.put("fixed", "Fixed");
		active.put("text", "String");
		active.put("ints", "int[]");
		active.put("maybe", "-int");
		active.put("leaf", "Leaf");
		active.put("leaves", "Leaf[]");
		active.put("maybeLeaf", "-Leaf");
		active.put("choice", "Choice");
		active.put("choices", "Choice[]");
		active.put("maybeChoice", "-Choice");
		for (int field = 0; field < 6; field++) active.put("f" + field,
				field % 2 == 0 ? "int" : "String");
		configuration.baseTypesData.get("Root").data.putAll(active);

		int versions = 2 + random.nextInt(8);
		var rawVersions = new LinkedHashMap<String, VersionConfiguration>();
		rawVersions.put("v1", new VersionConfiguration());
		int nextName = 100;
		for (int version = 2; version <= versions; version++) {
			var value = new VersionConfiguration();
			value.previousVersion = "v" + (version - 1);
			var transformations = new ArrayList<VersionTransformation>();

			NewDataConfiguration leafAdd = new NewDataConfiguration();
			leafAdd.transformClass = "Leaf";
			leafAdd.to = "generation" + version;
			leafAdd.type = "long";
			leafAdd.initializer = "org.example.LongInitializer";
			leafAdd.readTransform = constantTransform(version);
			transformations.add(wrap(leafAdd));

			int actions = 1 + random.nextInt(4);
			for (int action = 0; action < actions; action++) {
				List<String> mutable = active.keySet().stream().filter(name -> name.startsWith("f")
						|| name.startsWith("added") || name.startsWith("moved")).toList();
				int kind = random.nextInt(4);
				if (kind == 0 && !mutable.isEmpty()) {
					String from = mutable.get(random.nextInt(mutable.size()));
					String to = "moved" + nextName++;
					MoveDataConfiguration move = new MoveDataConfiguration();
					move.transformClass = "Root";
					move.from = from;
					move.to = to;
					move.index = random.nextInt(active.size());
					transformations.add(wrap(move));
					String type = active.remove(from);
					insert(active, move.index, to, type);
				} else if (kind == 1 && !mutable.isEmpty() && active.size() > 12) {
					String from = mutable.get(random.nextInt(mutable.size()));
					RemoveDataConfiguration remove = new RemoveDataConfiguration();
					remove.transformClass = "Root";
					remove.from = from;
					transformations.add(wrap(remove));
					active.remove(from);
				} else if (kind == 2) {
					String name = "added" + nextName++;
					NewDataConfiguration add = new NewDataConfiguration();
					add.transformClass = "Root";
					add.to = name;
					add.type = random.nextBoolean() ? "long" : "String";
					add.initializer = "org.example.Initializer";
					add.readTransform = constantTransform(random.nextBoolean() ? version : "v" + version);
					add.index = random.nextInt(active.size() + 1);
					transformations.add(wrap(add));
					insert(active, add.index, name, add.type);
				} else {
					List<String> ints = active.entrySet().stream().filter(entry -> entry.getValue().equals("int"))
							.map(Map.Entry::getKey).filter(name -> !name.equals("anchor")).toList();
					if (!ints.isEmpty()) {
						String field = ints.get(random.nextInt(ints.size()));
						UpgradeDataConfiguration upgrade = new UpgradeDataConfiguration();
						upgrade.transformClass = "Root";
						upgrade.from = field;
						upgrade.type = "long";
						upgrade.upgrader = "org.example.IntToLong";
						upgrade.readTransform = identityTransform();
						transformations.add(wrap(upgrade));
						active.put(field, "long");
					}
				}
			}
			value.transformations = transformations;
			rawVersions.put("v" + version, value);
		}
		configuration.currentVersion = "v" + versions;
		configuration.versions = rawVersions;
		return new Schema(configuration);
	}

	private static SourcesGeneratorConfiguration schedulingSchema(Random random, int caseIndex) {
		var configuration = baseConfiguration();
		ClassConfiguration root = configuration.baseTypesData.get("Root");
		var types = new ArrayList<>(List.of("boolean", "byte", "short", "char", "int", "long",
				"float", "double", "Int52", "Fixed", "String", "int[]", "-int", "Opaque"));
		Collections.shuffle(types, random);
		for (int field = 0; field < 64; field++) root.data.put("f" + field,
				types.get((field + caseIndex) % types.size()));
		configuration.currentVersion = "v1";
		configuration.versions = Map.of("v1", new VersionConfiguration());
		return configuration;
	}

	private static SourcesGeneratorConfiguration baseConfiguration() {
		var configuration = new SourcesGeneratorConfiguration();
		configuration.interfacesData = Map.of();
		configuration.baseTypesData = new LinkedHashMap<>();
		configuration.superTypesData = new LinkedHashMap<>();
		configuration.customTypesData = new LinkedHashMap<>();
		configuration.projectionsData = Map.of();

		ClassConfiguration leaf = new ClassConfiguration();
		leaf.data = new LinkedHashMap<>();
		leaf.data.put("code", "int");
		leaf.data.put("label", "String");
		configuration.baseTypesData.put("Leaf", leaf);
		ClassConfiguration other = new ClassConfiguration();
		other.data = new LinkedHashMap<>();
		other.data.put("code", "int");
		other.data.put("label", "String");
		configuration.baseTypesData.put("Other", other);
		ClassConfiguration root = new ClassConfiguration();
		root.data = new LinkedHashMap<>();
		configuration.baseTypesData.put("Root", root);
		configuration.superTypesData.put("Choice", List.of("Leaf", "Other"));

		CustomTypesConfiguration fixed = new CustomTypesConfiguration();
		fixed.setJavaClass("java.lang.Integer");
		fixed.codec = "org.example.FixedCodec";
		fixed.fixedSize = 4;
		configuration.customTypesData.put("Fixed", fixed);
		CustomTypesConfiguration opaque = new CustomTypesConfiguration();
		opaque.setJavaClass("java.lang.String");
		opaque.codec = "org.example.OpaqueCodec";
		configuration.customTypesData.put("Opaque", opaque);
		return configuration;
	}

	private static ReadTransformConfiguration constantTransform(Object value) {
		var transform = new ReadTransformConfiguration();
		transform.constant = new ReadTransformConfiguration.Constant();
		transform.constant.value = value;
		return transform;
	}

	private static ReadTransformConfiguration identityTransform() {
		var transform = new ReadTransformConfiguration();
		transform.identity = new ReadTransformConfiguration.Identity();
		transform.identity.source = "value";
		return transform;
	}

	private static VersionTransformation wrap(MoveDataConfiguration value) {
		var result = new VersionTransformation();
		result.moveData = value;
		return result;
	}

	private static VersionTransformation wrap(RemoveDataConfiguration value) {
		var result = new VersionTransformation();
		result.removeData = value;
		return result;
	}

	private static VersionTransformation wrap(NewDataConfiguration value) {
		var result = new VersionTransformation();
		result.newData = value;
		return result;
	}

	private static VersionTransformation wrap(UpgradeDataConfiguration value) {
		var result = new VersionTransformation();
		result.upgradeData = value;
		return result;
	}

	private static <K, V> void insert(LinkedHashMap<K, V> map, int index, K key, V value) {
		var entries = new ArrayList<>(map.entrySet());
		map.clear();
		for (int position = 0; position <= entries.size(); position++) {
			if (position == index) map.put(key, value);
			if (position < entries.size()) map.put(entries.get(position).getKey(), entries.get(position).getValue());
		}
	}

	private record Schema(SourcesGeneratorConfiguration configuration) {}
}
