package it.cavallium.datagen.plugin.classgen;

import it.cavallium.datagen.plugin.ComputedType;
import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import it.cavallium.datagen.plugin.ComputedTypeArray;
import it.cavallium.datagen.plugin.ComputedTypeBase;
import it.cavallium.datagen.plugin.ComputedTypeCustom;
import it.cavallium.datagen.plugin.ComputedTypeNative;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.ComputedTypeSuper;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.MoveDataConfiguration;
import it.cavallium.datagen.plugin.NewDataConfiguration;
import it.cavallium.datagen.plugin.RemoveDataConfiguration;
import it.cavallium.datagen.plugin.TransformationConfiguration;
import it.cavallium.datagen.plugin.UpgradeDataConfiguration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared schema-version compiler for projections and normal reads.
 *
 * <p>The immutable IR deliberately contains no Java source fragments. Its record equality is the
 * canonical plan identity used for cross-version hash-consing; emission is a later concern.</p>
 */
final class ReadPlanCompiler {

	private final DataModel dataModel;
	private final Function<String, IllegalArgumentException> errorFactory;
	private final ConcurrentHashMap<Plan, Plan> canonicalPlans = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<ValueShape, ValueShape> canonicalShapes = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Expression, Expression> canonicalExpressions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<ScanOperation, ScanOperation> canonicalScanOperations = new ConcurrentHashMap<>();
	private final IdentityHashMap<ComputedType, ValueShape> shapesByType = new IdentityHashMap<>();

	ReadPlanCompiler(DataModel dataModel, Function<String, IllegalArgumentException> errorFactory) {
		this.dataModel = dataModel;
		this.errorFactory = errorFactory;
	}

	ComputedType typeNamed(int logicalVersion, String name) {
		ComputedType type = dataModel.getComputedTypes(dataModel.getVersion(logicalVersion)).get(name);
		if (type == null) throw errorFactory.apply("unknown type " + name + " in version " + logicalVersion);
		return type;
	}

	ComputedTypeBase requireBase(int logicalVersion, String name) {
		ComputedType type = typeNamed(logicalVersion, name);
		if (type instanceof ComputedTypeBase base) return base;
		throw errorFactory.apply(name + " is not a base record in version " + logicalVersion);
	}

	FieldOrigin traceFieldOrigin(int targetVersion, String ownerName, String targetField) {
		String name = targetField;
		NewDataConfiguration initializer = null;
		Deque<UpgradeDataConfiguration> upgrades = new ArrayDeque<>();
		List<TransformationConfiguration> changes = dataModel.getChanges(targetVersion, ownerName);
		for (int i = changes.size() - 1; i >= 0; i--) {
			TransformationConfiguration change = changes.get(i);
			if (change instanceof MoveDataConfiguration move && name.equals(move.to)) {
				name = move.from;
			} else if (change instanceof UpgradeDataConfiguration upgrade && name.equals(upgrade.from)) {
				upgrades.addFirst(upgrade);
			} else if (change instanceof NewDataConfiguration added && name.equals(added.to)) {
				initializer = added;
				name = null;
				break;
			} else if (change instanceof RemoveDataConfiguration removed && name.equals(removed.from)) {
				throw errorFactory.apply("field " + ownerName + "." + targetField
						+ " cannot originate from removed field " + removed.from);
			}
		}
		return new FieldOrigin(name, initializer, List.copyOf(upgrades));
	}

	/** Compiles, optimizes, and globally interns a direct historical-to-current record plan. */
	Plan compile(ComputedTypeBase input, ComputedTypeBase target) {
		if (!input.getName().equals(target.getName())) {
			throw errorFactory.apply("record identity changed from " + input.getName() + " to " + target.getName());
		}
		PlanBuilder builder = new PlanBuilder(input, target);
		Plan plan = builder.compile();
		return canonicalPlans.computeIfAbsent(plan, ignored -> plan);
	}

	private final class PlanBuilder {

		private final ComputedTypeBase input;
		private final ComputedTypeBase target;
		private final int inputVersion;
		private final int targetVersion;
		private final List<String> inputFields;
		private final Map<ResolveKey, Expression> resolved = new HashMap<>();
		private final Set<ResolveKey> resolving = new LinkedHashSet<>();

		private PlanBuilder(ComputedTypeBase input, ComputedTypeBase target) {
			this.input = input;
			this.target = target;
			this.inputVersion = input.getVersion().getVersion();
			this.targetVersion = target.getVersion().getVersion();
			this.inputFields = List.copyOf(input.getData().keySet());
		}

		private Plan compile() {
			List<Expression> fields = target.getData().keySet().stream()
					.map(field -> resolve(targetVersion, field))
					.toList();
			Expression construction = canonicalize(optimize(
					new Construct(target.getName(), fields, shape(target))));
			Set<Integer> liveFields = new LinkedHashSet<>();
			collectSources(construction, liveFields);
			List<ScanOperation> scan = scheduleScan(liveFields).stream()
					.map(operation -> canonicalScanOperations.computeIfAbsent(operation, ignored -> operation))
					.toList();
			return new Plan(construction, scan);
		}

		private Expression resolve(int logicalVersion, String fieldName) {
			ResolveKey key = new ResolveKey(logicalVersion, fieldName);
			Expression cached = resolved.get(key);
			if (cached != null) return cached;
			if (!resolving.add(key)) {
				throw errorFactory.apply("cyclic transform dependency while resolving " + target.getName()
						+ "." + fieldName + " at version " + logicalVersion);
			}
			try {
				Expression value;
				if (logicalVersion == inputVersion) {
					int fieldIndex = inputFields.indexOf(fieldName);
					if (fieldIndex < 0) {
						throw errorFactory.apply("missing input field " + input.getName() + "." + fieldName);
					}
					value = new Source(fieldIndex, shape(input.getData().get(fieldName)));
				} else {
					ComputedTypeBase nextOwner = requireBase(logicalVersion, input.getName());
					ComputedTypeBase previousOwner = requireBase(logicalVersion - 1, input.getName());
					ComputedType nextType = nextOwner.getData().get(fieldName);
					if (nextType == null) {
						throw errorFactory.apply("missing target field " + nextOwner.getName() + "." + fieldName);
					}
					FieldOrigin origin = traceFieldOrigin(logicalVersion, input.getName(), fieldName);
					ComputedType operationType;
					if (origin.initializer() != null) {
						NewDataConfiguration initializer = origin.initializer();
						operationType = initializer.hasReadTransform()
								? typeNamed(initializer.hasReadTransformTypeOverride()
										? dataModel.getCurrentVersion().getVersion() : logicalVersion,
										DataModel.fixType(initializer.getReadTransformType()))
								: typeNamed(logicalVersion, DataModel.fixType(initializer.type));
						value = new Initialize(
								new TransformDescriptor(initializer.hasReadTransform() ? "read-initialize" : "initialize",
										initializer.hasReadTransform() ? initializer.getReadTransform()
												: initializer.getInitializerLocation().getIdentifier(),
										logicalVersion, initializer.to, operationType.getName()),
								resolveContexts(logicalVersion - 1, previousOwner, initializer.getContextParameters()),
								shape(operationType));
					} else {
						operationType = previousOwner.getData().get(origin.previousName());
						if (operationType == null) {
							throw errorFactory.apply("missing previous field " + previousOwner.getName() + "."
									+ origin.previousName());
						}
						value = resolve(logicalVersion - 1, origin.previousName());
					}

					for (UpgradeDataConfiguration upgrade : origin.upgrades()) {
						ComputedType upgradedType = typeNamed(logicalVersion, DataModel.fixType(upgrade.type));
						String kind = upgrade.hasReadTransform() ? "read-transform" : "object-transform";
						Object implementation = upgrade.hasReadTransform()
								? upgrade.getReadTransform()
								: upgrade.getUpgraderLocation().getIdentifier();
						ComputedType resultType = upgrade.hasReadTransformTypeOverride()
								? typeNamed(dataModel.getCurrentVersion().getVersion(),
										DataModel.fixType(upgrade.getReadTransformType()))
								: upgradedType;
						value = new Transform(new TransformDescriptor(kind, implementation, logicalVersion,
								upgrade.from, resultType.getName()), value,
								resolveContexts(logicalVersion - 1, previousOwner, upgrade.getContextParameters()),
								shape(resultType));
						operationType = resultType;
					}
					value = structural(value, operationType, nextType);
				}
				resolved.put(key, value);
				return value;
			} finally {
				resolving.remove(key);
			}
		}

		private List<Expression> resolveContexts(int version,
				ComputedTypeBase owner,
				List<String> contextFields) {
			var result = new ArrayList<Expression>(contextFields.size());
			for (String field : contextFields) {
				if (!owner.getData().containsKey(field)) {
					throw errorFactory.apply("unknown context field " + owner.getName() + "." + field);
				}
				result.add(resolve(version, field));
			}
			return List.copyOf(result);
		}

		private List<ScanOperation> scheduleScan(Set<Integer> liveFields) {
			var result = new ArrayList<ScanOperation>();
			var fixedEntries = new ArrayList<FixedField>();
			int fixedBytes = 0;
			int index = 0;
			for (ComputedType fieldType : input.getData().values()) {
				Integer size = fixedSerializedSize(fieldType);
				if (size == null) {
					if (!fixedEntries.isEmpty()) {
						result.add(new FixedBlock(fixedBytes, List.copyOf(fixedEntries)));
						fixedEntries.clear();
						fixedBytes = 0;
					}
					result.add(liveFields.contains(index)
							? new ReadDynamic(index, shape(fieldType))
							: new SkipDynamic(index, shape(fieldType)));
				} else {
					fixedEntries.add(new FixedField(index, fixedBytes, size, shape(fieldType),
							liveFields.contains(index) ? FieldUse.READ : FieldUse.SKIP));
					fixedBytes = Math.addExact(fixedBytes, size);
				}
				index++;
			}
			if (!fixedEntries.isEmpty()) result.add(new FixedBlock(fixedBytes, List.copyOf(fixedEntries)));
			return List.copyOf(result);
		}
	}

	private Expression structural(Expression value, ComputedType oldType, ComputedType newType) {
		if (oldType.equals(newType)) return value;
		ValueShape targetShape = shape(newType);
		StructuralDescriptor step = new StructuralDescriptor(oldType.getName(), versionOf(oldType),
				newType.getName(), versionOf(newType));
		if (oldType instanceof ComputedTypeNullable && newType instanceof ComputedTypeNullable) {
			return new MapNullable(value, List.of(step), targetShape);
		}
		if (oldType instanceof ComputedTypeArray && newType instanceof ComputedTypeArray) {
			return new MapArray(value, List.of(step), targetShape);
		}
		if (oldType instanceof ComputedTypeBase && newType instanceof ComputedTypeBase) {
			return new MapRecord(value, List.of(step), targetShape);
		}
		if (oldType instanceof ComputedTypeSuper && newType instanceof ComputedTypeSuper) {
			return new MapUnion(value, List.of(step), targetShape);
		}
		if (value.resultShape().equals(targetShape)) return value;
		return new Convert(value, targetShape);
	}

	synchronized int minimumSerializedSize(ComputedType type) {
		return shape(type).minimumSerializedSize();
	}

	private synchronized ValueShape shape(ComputedType type) {
		ValueShape cached = shapesByType.get(type);
		if (cached != null) return cached;
		return shape(type, new IdentityHashMap<>());
	}

	private ValueShape canonicalize(ValueShape shape) {
		ValueShape normalized = switch (shape) {
			case NullableShape nullable -> new NullableShape(canonicalize(nullable.value()),
					nullable.minimumSerializedSize());
			case ArrayShape array -> new ArrayShape(canonicalize(array.element()),
					array.minimumSerializedSize());
			case RecordShape record -> new RecordShape(record.name(), record.fields().stream()
					.map(this::canonicalize).toList(), record.minimumSerializedSize());
			case UnionShape union -> new UnionShape(union.name(), union.alternatives().stream()
					.map(this::canonicalize).toList(), union.minimumSerializedSize());
			case NativeShape nativeShape -> nativeShape;
			case CustomShape custom -> custom;
			case RecursiveShape recursive -> recursive;
		};
		return canonicalShapes.computeIfAbsent(normalized, ignored -> normalized);
	}

	private Expression canonicalize(Expression expression) {
		Expression normalized = switch (expression) {
			case Source source -> new Source(source.fieldIndex(), canonicalize(source.resultShape()));
			case Constant constant -> new Constant(constant.literal(), canonicalize(constant.resultShape()));
			case Initialize initialize -> new Initialize(initialize.descriptor(), initialize.context().stream()
					.map(this::canonicalize).toList(), canonicalize(initialize.resultShape()));
			case Transform transform -> new Transform(transform.descriptor(), canonicalize(transform.value()),
					transform.context().stream().map(this::canonicalize).toList(),
					canonicalize(transform.resultShape()));
			case Convert convert -> new Convert(canonicalize(convert.value()), canonicalize(convert.resultShape()));
			case MapNullable map -> new MapNullable(canonicalize(map.value()), map.path(),
					canonicalize(map.resultShape()));
			case MapArray map -> new MapArray(canonicalize(map.value()), map.path(),
					canonicalize(map.resultShape()));
			case MapRecord map -> new MapRecord(canonicalize(map.value()), map.path(),
					canonicalize(map.resultShape()));
			case MapUnion map -> new MapUnion(canonicalize(map.value()), map.path(),
					canonicalize(map.resultShape()));
			case Construct construct -> new Construct(construct.type(), construct.fields().stream()
					.map(this::canonicalize).toList(), canonicalize(construct.resultShape()));
		};
		return canonicalExpressions.computeIfAbsent(normalized, ignored -> normalized);
	}

	/** Runs the source-independent constant/identity and structural-transform fusion passes. */
	private Expression optimize(Expression expression) {
		return switch (expression) {
			case Source source -> source;
			case Constant constant -> constant;
			case Initialize initialize -> new Initialize(initialize.descriptor(), initialize.context().stream()
					.map(this::optimize).toList(), initialize.resultShape());
			case Transform transform -> new Transform(transform.descriptor(), optimize(transform.value()),
					transform.context().stream().map(this::optimize).toList(), transform.resultShape());
			case Convert convert -> optimizeConvert(convert);
			case MapNullable map -> optimizeNullable(map);
			case MapArray map -> optimizeArray(map);
			case MapRecord map -> optimizeRecord(map);
			case MapUnion map -> optimizeUnion(map);
			case Construct construct -> new Construct(construct.type(), construct.fields().stream()
					.map(this::optimize).toList(), construct.resultShape());
		};
	}

	private Expression optimizeConvert(Convert convert) {
		Expression value = optimize(convert.value());
		if (value.resultShape().equals(convert.resultShape())) return value;
		if (value instanceof Constant constant) return new Constant(constant.literal(), convert.resultShape());
		if (value instanceof Convert previous) return new Convert(previous.value(), convert.resultShape());
		return new Convert(value, convert.resultShape());
	}

	private Expression optimizeNullable(MapNullable map) {
		Expression value = optimize(map.value());
		if (value instanceof MapNullable previous) {
			return new MapNullable(previous.value(), concat(previous.path(), map.path()), map.resultShape());
		}
		return new MapNullable(value, map.path(), map.resultShape());
	}

	private Expression optimizeArray(MapArray map) {
		Expression value = optimize(map.value());
		if (value instanceof MapArray previous) {
			return new MapArray(previous.value(), concat(previous.path(), map.path()), map.resultShape());
		}
		return new MapArray(value, map.path(), map.resultShape());
	}

	private Expression optimizeRecord(MapRecord map) {
		Expression value = optimize(map.value());
		if (value instanceof MapRecord previous) {
			return new MapRecord(previous.value(), concat(previous.path(), map.path()), map.resultShape());
		}
		return new MapRecord(value, map.path(), map.resultShape());
	}

	private Expression optimizeUnion(MapUnion map) {
		Expression value = optimize(map.value());
		if (value instanceof MapUnion previous) {
			return new MapUnion(previous.value(), concat(previous.path(), map.path()), map.resultShape());
		}
		return new MapUnion(value, map.path(), map.resultShape());
	}

	private static <T> List<T> concat(List<T> first, List<T> second) {
		var result = new ArrayList<T>(first.size() + second.size());
		result.addAll(first);
		result.addAll(second);
		return List.copyOf(result);
	}

	private ValueShape shape(ComputedType type, IdentityHashMap<ComputedType, Boolean> visiting) {
		ValueShape cached = shapesByType.get(type);
		if (cached != null) return cached;
		ValueShape computed;
		if (type instanceof ComputedTypeNative nativeType) {
			Integer fixedSize = fixedSerializedSize(nativeType);
			int minimumSize = fixedSize == null ? Integer.BYTES : fixedSize;
			computed = new NativeShape(nativeType.getName(), nativeType.getJTypeName("").toString(),
					fixedSize, minimumSize);
		} else if (type instanceof ComputedTypeCustom custom) {
			computed = new CustomShape(custom.getName(), custom.getJavaClass(), custom.getCodec(),
					custom.getFixedSize(), custom.getFixedSize() == null ? 0 : custom.getFixedSize());
		} else if (visiting.put(type, Boolean.TRUE) != null) {
			return new RecursiveShape(type.getName(), versionOf(type), 0);
		} else {
			try {
				if (type instanceof ComputedTypeNullable nullable) {
					computed = new NullableShape(shape(nullable.getBase(), visiting), 1);
				} else if (type instanceof ComputedTypeArray array) {
					computed = new ArrayShape(shape(array.getBase(), visiting), Integer.BYTES);
				} else if (type instanceof ComputedTypeBase base) {
					List<ValueShape> fields = base.getData().values().stream()
							.map(field -> shape(field, visiting)).toList();
					int minimumSize = 0;
					for (ValueShape field : fields) {
						minimumSize = Math.addExact(minimumSize, field.minimumSerializedSize());
					}
					computed = new RecordShape(base.getName(), fields, minimumSize);
				} else if (type instanceof ComputedTypeSuper union) {
					List<ValueShape> alternatives = union.subTypes().stream()
							.map(subtype -> shape(subtype, visiting)).toList();
					if (alternatives.isEmpty()) {
						throw errorFactory.apply("union " + union.getName() + " has no alternatives");
					}
					int payloadMinimum = alternatives.stream()
							.mapToInt(ValueShape::minimumSerializedSize).min().orElseThrow();
					computed = new UnionShape(union.getName(), alternatives,
							Math.addExact(1, payloadMinimum));
				} else {
					throw errorFactory.apply("unsupported type in read-plan IR: " + type);
				}
			} finally {
				visiting.remove(type);
			}
		}
		ValueShape canonical = canonicalize(computed);
		shapesByType.put(type, canonical);
		return canonical;
	}

	private Integer fixedSerializedSize(ComputedType type) {
		return fixedSerializedSize(type, new IdentityHashMap<>());
	}

	private Integer fixedSerializedSize(ComputedType type, IdentityHashMap<ComputedType, Boolean> visiting) {
		if (type instanceof ComputedTypeNative nativeType) {
			return switch (nativeType.getName()) {
				case "boolean", "byte" -> 1;
				case "short", "char" -> 2;
				case "int", "float" -> 4;
				case "long", "double" -> 8;
				case "Int52" -> 7;
				case "String" -> null;
				default -> throw errorFactory.apply("unknown native type " + nativeType.getName());
			};
		}
		if (type instanceof ComputedTypeCustom custom) return custom.getFixedSize();
		if (type instanceof ComputedTypeNullable || type instanceof ComputedTypeArray) return null;
		if (visiting.put(type, Boolean.TRUE) != null) return null;
		try {
			if (type instanceof ComputedTypeBase base) {
				int size = 0;
				for (ComputedType field : base.getData().values()) {
					Integer fieldSize = fixedSerializedSize(field, visiting);
					if (fieldSize == null) return null;
					size = Math.addExact(size, fieldSize);
				}
				return size;
			}
			if (type instanceof ComputedTypeSuper union) {
				Integer payload = null;
				for (ComputedType subtype : union.subTypes()) {
					Integer size = fixedSerializedSize(subtype, visiting);
					if (size == null || payload != null && !payload.equals(size)) return null;
					payload = size;
				}
				return payload == null ? null : Math.addExact(1, payload);
			}
			return null;
		} finally {
			visiting.remove(type);
		}
	}

	private static void collectSources(Expression expression, Set<Integer> fields) {
		switch (expression) {
			case Source source -> fields.add(source.fieldIndex());
			case Initialize initialize -> initialize.context().forEach(value -> collectSources(value, fields));
			case Transform transform -> {
				collectSources(transform.value(), fields);
				transform.context().forEach(value -> collectSources(value, fields));
			}
			case Convert convert -> collectSources(convert.value(), fields);
			case MapNullable map -> collectSources(map.value(), fields);
			case MapArray map -> collectSources(map.value(), fields);
			case MapRecord map -> collectSources(map.value(), fields);
			case MapUnion map -> collectSources(map.value(), fields);
			case Construct construct -> construct.fields().forEach(value -> collectSources(value, fields));
			case Constant ignored -> { }
		}
	}

	private static int versionOf(ComputedType type) {
		return type instanceof VersionedComputedType versioned ? versioned.getVersion().getVersion() : -1;
	}

	record Plan(Expression construction, List<ScanOperation> scan) {
		Plan {
			scan = List.copyOf(scan);
		}
	}

	sealed interface ValueShape permits NativeShape, CustomShape, NullableShape, ArrayShape,
			RecordShape, UnionShape, RecursiveShape {
		int minimumSerializedSize();
	}

	record NativeShape(String name, String javaType, Integer fixedSize,
			int minimumSerializedSize) implements ValueShape { }

	record CustomShape(String name, String javaType, String codec, Integer fixedSize,
			int minimumSerializedSize) implements ValueShape { }

	record NullableShape(ValueShape value, int minimumSerializedSize) implements ValueShape { }

	record ArrayShape(ValueShape element, int minimumSerializedSize) implements ValueShape { }

	record RecordShape(String name, List<ValueShape> fields,
			int minimumSerializedSize) implements ValueShape {
		RecordShape { fields = List.copyOf(fields); }
	}

	record UnionShape(String name, List<ValueShape> alternatives,
			int minimumSerializedSize) implements ValueShape {
		UnionShape { alternatives = List.copyOf(alternatives); }
	}

	record RecursiveShape(String name, int version, int minimumSerializedSize) implements ValueShape { }

	sealed interface Expression permits Source, Constant, Initialize, Transform, Convert,
			MapNullable, MapArray, MapRecord, MapUnion, Construct {
		ValueShape resultShape();
	}

	record Source(int fieldIndex, ValueShape resultShape) implements Expression { }

	record Constant(String literal, ValueShape resultShape) implements Expression { }

	record Initialize(TransformDescriptor descriptor,
			List<Expression> context,
			ValueShape resultShape) implements Expression {
		Initialize { context = List.copyOf(context); }
	}

	record Transform(TransformDescriptor descriptor,
			Expression value,
			List<Expression> context,
			ValueShape resultShape) implements Expression {
		Transform { context = List.copyOf(context); }
	}

	record Convert(Expression value, ValueShape resultShape) implements Expression { }

	record MapNullable(Expression value,
			List<StructuralDescriptor> path,
			ValueShape resultShape) implements Expression {
		MapNullable { path = List.copyOf(path); }
	}

	record MapArray(Expression value,
			List<StructuralDescriptor> path,
			ValueShape resultShape) implements Expression {
		MapArray { path = List.copyOf(path); }
	}

	record MapRecord(Expression value,
			List<StructuralDescriptor> path,
			ValueShape resultShape) implements Expression {
		MapRecord { path = List.copyOf(path); }
	}

	record MapUnion(Expression value,
			List<StructuralDescriptor> path,
			ValueShape resultShape) implements Expression {
		MapUnion { path = List.copyOf(path); }
	}

	record StructuralDescriptor(String sourceType,
			int sourceVersion,
			String targetType,
			int targetVersion) { }

	record Construct(String type, List<Expression> fields, ValueShape resultShape) implements Expression {
		Construct { fields = List.copyOf(fields); }
	}

	record TransformDescriptor(String kind,
			Object implementation,
			int logicalVersion,
			String field,
			String resultType) { }

	sealed interface ScanOperation permits FixedBlock, ReadDynamic, SkipDynamic { }

	record FixedBlock(int byteSize, List<FixedField> fields) implements ScanOperation {
		FixedBlock { fields = List.copyOf(fields); }
	}

	record FixedField(int fieldIndex,
			int byteOffset,
			int byteSize,
			ValueShape wireShape,
			FieldUse use) { }

	record ReadDynamic(int fieldIndex, ValueShape wireShape) implements ScanOperation { }

	record SkipDynamic(int fieldIndex, ValueShape wireShape) implements ScanOperation { }

	enum FieldUse { READ, SKIP }

	record FieldOrigin(String previousName,
		NewDataConfiguration initializer,
		List<UpgradeDataConfiguration> upgrades) {
		FieldOrigin { upgrades = List.copyOf(upgrades); }
	}

	private record ResolveKey(int version, String field) { }
}
