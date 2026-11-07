package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.nativedata.Int52;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * Builds random values of a {@link CompiledSchema} entirely by reflection, so that a fuzz oracle can exercise
 * generated records, unions, nullables and arrays without a code-generated builder.
 *
 * <h2>What it produces</h2>
 * <ul>
 *   <li>arrays: both the empty array and non-empty ones (sizes 1..3, so a corpus always contains both);</li>
 *   <li>nullable fields: present sometimes and {@code null} (absent) sometimes — never a {@code NullableX}
 *   carrier, because a generated record stores an absent value as a plain {@code null} reference;</li>
 *   <li>every alternative of a union: the permitted subclasses are sorted by name and one is drawn, so a
 *   handful of draws covers a small union and a long run covers every alternative;</li>
 *   <li>strings from {@code FuzzValues.string(Random)} when {@code it.cavallium.datagen.fuzz.FuzzValues} is on
 *   the classpath (it lives in {@code datagen}'s test sources, which this module does not compile against), and
 *   from an equivalent hostile pool otherwise;</li>
 *   <li>{@link Int52} from {@code FuzzValues.int52(Random)} / the valid 52-bit range, i.e. always inside
 *   {@code [Int52.MIN_VALUE_L, Int52.MAX_VALUE_L]};</li>
 *   <li>{@code float} and {@code double} from a bit pool plus {@code Double.longBitsToDouble} /
 *   {@code Float.intBitsToFloat} of raw random bits, including {@code -0.0}, NaN and the infinities. Compare
 *   them with {@code floatToRawIntBits} / {@code doubleToRawLongBits} semantics, never with an equality
 *   assertion on the floating value.</li>
 * </ul>
 *
 * <h2>Cycle safety</h2>
 * <p>A schema may be self-referential, so construction carries both an explicit depth budget
 * ({@code maxDepth}) and the stack of data classes currently being built. Reaching the budget or revisiting a
 * class raises an internal {@link Budget exception}, which the nearest nullable field turns into {@code null}
 * and the nearest array field turns into an empty array. Only a cycle with no nullable or array escape and no
 * remaining budget fails, and then with an {@link IllegalStateException} that says so.</p>
 *
 * <h2>Coverage reporting</h2>
 * <p>The {@link IntConsumer} overload reports each category it actually reached, once per produced value, using
 * the {@code COVERAGE_*} constants below; {@link #describeCoverage(int)} renders the accumulated mask. See the
 * class report of the implementing package for which categories a run reached.</p>
 */
public final class ReflectiveValueGen {

	/** Coverage sink code: a primitive or boxed primitive value was produced. */
	public static final int COVERAGE_PRIMITIVE = 1 << 0;
	/** Coverage sink code: a string was produced. */
	public static final int COVERAGE_STRING = 1 << 1;
	/** Coverage sink code: an {@link Int52} was produced. */
	public static final int COVERAGE_INT52 = 1 << 2;
	/** Coverage sink code: a {@code float} was produced. */
	public static final int COVERAGE_FLOAT = 1 << 3;
	/** Coverage sink code: a {@code double} was produced. */
	public static final int COVERAGE_DOUBLE = 1 << 4;
	/** Coverage sink code: a zero-length array was produced. */
	public static final int COVERAGE_EMPTY_ARRAY = 1 << 5;
	/** Coverage sink code: an array with at least one element was produced. */
	public static final int COVERAGE_NON_EMPTY_ARRAY = 1 << 6;
	/** Coverage sink code: a nullable field was produced as present. */
	public static final int COVERAGE_NULLABLE_PRESENT = 1 << 7;
	/** Coverage sink code: a nullable field was produced as absent ({@code null}). */
	public static final int COVERAGE_NULLABLE_ABSENT = 1 << 8;
	/** Coverage sink code: one alternative of a union was produced. */
	public static final int COVERAGE_UNION_ALTERNATIVE = 1 << 9;
	/** Coverage sink code: a nested record was produced. */
	public static final int COVERAGE_NESTED_RECORD = 1 << 10;

	private static final int[] COVERAGE_CODES = {
			COVERAGE_PRIMITIVE, COVERAGE_STRING, COVERAGE_INT52, COVERAGE_FLOAT, COVERAGE_DOUBLE,
			COVERAGE_EMPTY_ARRAY, COVERAGE_NON_EMPTY_ARRAY, COVERAGE_NULLABLE_PRESENT, COVERAGE_NULLABLE_ABSENT,
			COVERAGE_UNION_ALTERNATIVE, COVERAGE_NESTED_RECORD,
	};

	private static final String NAMED_PARAMETER_PREFIX = "$datagen$parameter$";
	private static final String NULLABLE_SUFFIX = "OrNull";

	/** Pool the scalar floats are drawn from; index 0 keeps {@code -0.0} reachable. */
	private static final float[] FLOAT_POOL = {0.0f, -0.0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE,
			-Float.MAX_VALUE, Float.MIN_NORMAL, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
			1.5f, -1.5f};

	/** Pool the scalar doubles are drawn from. */
	private static final double[] DOUBLE_POOL = {0.0d, -0.0d, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE,
			-Double.MAX_VALUE, Double.MIN_NORMAL, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
			0.25d, -0.25d};

	/** Maximum array length this generator produces; small on purpose, oracles multiply it by their own cases. */
	private static final int MAX_ARRAY_LENGTH = 3;

	/** Probability that a nullable field is drawn absent. */
	private static final int ABSENT_PERCENT = 35;

	private final CompiledSchema schema;
	private final GeneratedModel model;
	private final Map<Class<?>, Factory> factories = new ConcurrentHashMap<>();

	public ReflectiveValueGen(CompiledSchema schema) {
		this.schema = Objects.requireNonNull(schema, "schema");
		this.model = schema.generatedModel();
	}

	/** The compiled schema this generator builds values for. */
	public CompiledSchema schema() {
		return schema;
	}

	/** True when {@code typeName} is a base type of this schema that this generator can construct. */
	public boolean supports(String typeName) {
		if (!schema.typeNames().contains(typeName)) {
			return false;
		}
		try {
			factoryOf(model.dataClass(typeName, schema.currentVersionId()));
			return true;
		} catch (IllegalStateException defect) {
			// Both failure modes here are real defects, not "unsupported": a missing data class
			// (GeneratedModel.failed) and a data class without its generated of(...) factory (buildFactory).
			// Swallowing them would silently shrink the spread, so they propagate to the caller's guard.
			throw defect;
		} catch (RuntimeException unsupported) {
			return false;
		}
	}

	/** Equivalent of {@link #value(String, int, Random, int, IntConsumer)} without a coverage sink. */
	public Object value(String typeName, int versionId, Random rnd, int maxDepth) {
		return value(typeName, versionId, rnd, maxDepth, null);
	}

	/**
	 * Builds a value of {@code typeName} as it exists at {@code versionId}.
	 *
	 * @param maxDepth how deep nested records may go; {@code 0} produces a value with no nested records
	 * @param coverage optional sink notified with a {@code COVERAGE_*} code for every category it reaches
	 * @throws IllegalStateException when the type is unknown, when a field type this generator cannot build
	 *                               appears, or when a recursive type has no nullable or array escape within
	 *                               {@code maxDepth}
	 */
	public Object value(String typeName, int versionId, Random rnd, int maxDepth, IntConsumer coverage) {
		Objects.requireNonNull(typeName, "typeName");
		Objects.requireNonNull(rnd, "rnd");
		if (maxDepth < 0) {
			throw new IllegalArgumentException("maxDepth must be >= 0, but was " + maxDepth);
		}
		Class<?> startType = model.dataClass(typeName, versionId);
		Set<Class<?>> stack = new HashSet<>();
		try {
			Object value = record(startType, rnd, maxDepth, coverage, stack);
			report(coverage, COVERAGE_NESTED_RECORD);
			return value;
		} catch (Budget exception) {
			throw new IllegalStateException("No value of " + typeName + " version " + versionId + " fits in "
					+ "maxDepth=" + maxDepth + " for fingerprint=" + schema.fingerprint() + ": " + exception.getMessage(),
					exception);
		}
	}

	/** Renders an accumulated coverage mask, e.g. {@code STRING|EMPTY_ARRAY|NULLABLE_ABSENT}. */
	public static String describeCoverage(int mask) {
		List<String> names = new ArrayList<>();
		for (int code : COVERAGE_CODES) {
			if ((mask & code) != 0) {
				names.add(categoryName(code));
			}
		}
		return names.isEmpty() ? "NONE" : String.join("|", names);
	}

	/** The name of one {@code COVERAGE_*} code. */
	public static String categoryName(int code) {
		return switch (code) {
			case COVERAGE_PRIMITIVE -> "PRIMITIVE";
			case COVERAGE_STRING -> "STRING";
			case COVERAGE_INT52 -> "INT52";
			case COVERAGE_FLOAT -> "FLOAT";
			case COVERAGE_DOUBLE -> "DOUBLE";
			case COVERAGE_EMPTY_ARRAY -> "EMPTY_ARRAY";
			case COVERAGE_NON_EMPTY_ARRAY -> "NON_EMPTY_ARRAY";
			case COVERAGE_NULLABLE_PRESENT -> "NULLABLE_PRESENT";
			case COVERAGE_NULLABLE_ABSENT -> "NULLABLE_ABSENT";
			case COVERAGE_UNION_ALTERNATIVE -> "UNION_ALTERNATIVE";
			case COVERAGE_NESTED_RECORD -> "NESTED_RECORD";
			default -> throw new IllegalArgumentException("Unknown coverage code: " + code);
		};
	}

	/** Folds coverage codes into a mask, for {@link #describeCoverage(int)}. */
	public static int maskOf(int... codes) {
		int mask = 0;
		for (int code : codes) {
			mask |= code;
		}
		return mask;
	}

	// ------------------------------------------------------------------------------------------------
	// internals
	// ------------------------------------------------------------------------------------------------

	/** Thrown when a value cannot be completed within the depth budget or without revisiting a class. */
	private static final class Budget extends RuntimeException {
		private Budget(String message) {
			super(message, null, false, false);
		}
	}

	private Object record(Class<?> type, Random rnd, int depth, IntConsumer coverage, Set<Class<?>> stack) {
		Factory factory = factoryOf(type);
		if (!stack.add(type)) {
			// Re-entrant type: only a budgeted caller can break the cycle.
			throw new Budget("cycle through " + type.getSimpleName() + " at depth " + depth);
		}
		try {
			Object[] arguments = new Object[factory.parameterTypes().length];
			for (int index = 0; index < arguments.length; index++) {
				boolean nullable = factory.nullable(index);
				if (nullable && rnd.nextInt(100) < ABSENT_PERCENT) {
					arguments[index] = null;
					report(coverage, COVERAGE_NULLABLE_ABSENT);
					continue;
				}
				Object value;
				try {
					value = valueOf(factory.parameterTypes()[index], rnd, depth, coverage, stack);
				} catch (Budget exception) {
					if (!nullable) {
						throw exception;
					}
					value = null;
					report(coverage, COVERAGE_NULLABLE_ABSENT);
				}
				if (value == null && !nullable) {
					throw new IllegalStateException("Generated field " + type.getSimpleName() + '.'
							+ factory.parameterNames()[index] + " is @NotNull but was produced as absent for "
							+ "fingerprint=" + schema.fingerprint());
				}
				if (nullable && value != null) {
					report(coverage, COVERAGE_NULLABLE_PRESENT);
				}
				arguments[index] = value;
			}
			return factory.create(arguments);
		} finally {
			stack.remove(type);
		}
	}

	private Object valueOf(Class<?> type, Random rnd, int depth, IntConsumer coverage, Set<Class<?>> stack) {
		if (type == String.class) {
			report(coverage, COVERAGE_STRING);
			return FuzzValuesBridge.string(rnd);
		}
		if (type == Int52.class) {
			report(coverage, COVERAGE_INT52);
			return Int52.fromLong(FuzzValuesBridge.int52(rnd));
		}
		if (type == float.class || type == Float.class) {
			report(coverage, COVERAGE_FLOAT);
			return randomFloat(rnd);
		}
		if (type == double.class || type == Double.class) {
			report(coverage, COVERAGE_DOUBLE);
			return randomDouble(rnd);
		}
		if (type.isArray()) {
			return array(type, rnd, depth, coverage, stack);
		}
		if (type.isInterface()) {
			List<Class<?>> alternatives = model.unionAlternatives(type.getSimpleName());
			if (alternatives.isEmpty()) {
				throw new IllegalStateException("Union " + type.getName() + " has no permitted subclasses for "
						+ "fingerprint=" + schema.fingerprint());
			}
			report(coverage, COVERAGE_UNION_ALTERNATIVE);
			return record(alternatives.get(rnd.nextInt(alternatives.size())), rnd, depth, coverage, stack);
		}
		if (isScalar(type)) {
			report(coverage, COVERAGE_PRIMITIVE);
			return scalar(type, rnd);
		}
		if (depth <= 0) {
			throw new Budget("depth budget exhausted at " + type.getSimpleName());
		}
		report(coverage, COVERAGE_NESTED_RECORD);
		return record(type, rnd, depth - 1, coverage, stack);
	}

	private Object array(Class<?> type, Random rnd, int depth, IntConsumer coverage, Set<Class<?>> stack) {
		// Both shapes always appear: a third of the arrays are empty, the rest hold 1..MAX_ARRAY_LENGTH items.
		int length = rnd.nextInt(3) == 0 ? 0 : 1 + rnd.nextInt(MAX_ARRAY_LENGTH);
		Class<?> component = type.componentType();
		Object array = java.lang.reflect.Array.newInstance(component, length);
		for (int index = 0; index < length; index++) {
			Object element;
			try {
				element = valueOf(component, rnd, Math.max(depth, 1), coverage, stack);
			} catch (Budget exception) {
				// An element that does not fit simply is not there: an empty array is a valid value.
				array = java.lang.reflect.Array.newInstance(component, 0);
				length = 0;
				break;
			}
			java.lang.reflect.Array.set(array, index, element);
		}
		report(coverage, length == 0 ? COVERAGE_EMPTY_ARRAY : COVERAGE_NON_EMPTY_ARRAY);
		return array;
	}

	private static boolean isScalar(Class<?> type) {
		return type == boolean.class || type == Boolean.class
				|| type == byte.class || type == Byte.class
				|| type == short.class || type == Short.class
				|| type == int.class || type == Integer.class
				|| type == long.class || type == Long.class
				|| type == char.class || type == Character.class;
	}

	private static Object scalar(Class<?> type, Random rnd) {
		if (type == boolean.class || type == Boolean.class) {
			return rnd.nextBoolean();
		}
		if (type == byte.class || type == Byte.class) {
			return (byte) rnd.nextInt();
		}
		if (type == short.class || type == Short.class) {
			return (short) rnd.nextInt();
		}
		if (type == int.class || type == Integer.class) {
			return rnd.nextInt();
		}
		if (type == long.class || type == Long.class) {
			return rnd.nextLong();
		}
		// char: stay inside the basic multilingual plane, avoiding lone surrogates in a single char field.
		return (char) ('a' + rnd.nextInt(26 * 10));
	}

	private static float randomFloat(Random rnd) {
		return rnd.nextInt(2) == 0 ? FLOAT_POOL[rnd.nextInt(FLOAT_POOL.length)] : Float.intBitsToFloat(rnd.nextInt());
	}

	private static double randomDouble(Random rnd) {
		return rnd.nextInt(2) == 0 ? DOUBLE_POOL[rnd.nextInt(DOUBLE_POOL.length)]
				: Double.longBitsToDouble(rnd.nextLong());
	}

	private static void report(IntConsumer coverage, int code) {
		if (coverage != null) {
			coverage.accept(code);
		}
	}

	/**
	 * The generated {@code of(...)} factory of a data class, plus the nullability of each of its parameters.
	 * Nullability comes from the generated {@code <field>OrNull()} accessor, which exists exactly for nullable
	 * fields; the parameter name is read from the compiled signature (the harness compiles with
	 * {@code -parameters}), and a name that is not available simply means "never absent".
	 */
	private Factory factoryOf(Class<?> type) {
		return factories.computeIfAbsent(type, this::buildFactory);
	}

	private Factory buildFactory(Class<?> type) {
		Method factory = Arrays.stream(type.getMethods())
				.filter(method -> method.getName().equals("of"))
				.filter(method -> Modifier.isStatic(method.getModifiers()))
				.filter(method -> method.getReturnType() == type)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No static of(...) factory on " + type.getName()
						+ " for fingerprint=" + schema.fingerprint()));
		String[] names = new String[factory.getParameterCount()];
		boolean[] nullable = new boolean[factory.getParameterCount()];
		var parameters = factory.getParameters();
		for (int index = 0; index < parameters.length; index++) {
			String raw = parameters[index].getName();
			String field = raw.startsWith(NAMED_PARAMETER_PREFIX)
					? raw.substring(NAMED_PARAMETER_PREFIX.length())
					: null;
			names[index] = field == null ? raw : field;
			nullable[index] = field != null && hasAccessor(type, field + NULLABLE_SUFFIX);
		}
		return new Factory(factory, factory.getParameterTypes(), names, nullable);
	}

	private boolean hasAccessor(Class<?> type, String methodName) {
		try {
			type.getMethod(methodName);
			return true;
		} catch (NoSuchMethodException exception) {
			return false;
		}
	}

	private record Factory(Method method,
			Class<?>[] parameterTypes,
			String[] parameterNames,
			boolean[] nullableParameters) {

		private boolean nullable(int index) {
			return nullableParameters[index];
		}

		private Object create(Object[] arguments) {
			try {
				return method.invoke(null, arguments);
			} catch (IllegalAccessException | InvocationTargetException exception) {
				Throwable cause = exception instanceof InvocationTargetException target && target.getCause() != null
						? target.getCause()
						: exception;
				throw new IllegalStateException("Unable to build " + method.getDeclaringClass().getSimpleName()
						+ " via of(...): " + cause, cause);
			}
		}
	}

	/**
	 * Bridge to {@code it.cavallium.datagen.fuzz.FuzzValues} (a {@code datagen} test-source class that this
	 * module cannot reference at compile time), with a local pool of the same hostile shape as fallback.
	 */
	private static final class FuzzValuesBridge {

		private static final Class<?> VALUES = load();

		/** Code points used by the fallback pool: NUL, ASCII, Latin-1, BMP, CJK, astral, surrogates, RTL. */
		private static final int[] FALLBACK_RANGES = {0x0000, 0x007F, 0x0080, 0x00FF, 0x0100, 0x024F, 0x0300, 0x036F,
				0x0590, 0x05FF, 0x3040, 0x30FF, 0x4E00, 0x9FFF, 0xD800, 0xDFFF, 0xFB00, 0xFBFF};

		private static Class<?> load() {
			try {
				return Class.forName("it.cavallium.datagen.fuzz.FuzzValues");
			} catch (ReflectiveOperationException exception) {
				return null;
			}
		}

		private static String string(Random rnd) {
			if (VALUES != null) {
				try {
					return (String) VALUES.getMethod("string", Random.class).invoke(null, rnd);
				} catch (ReflectiveOperationException | RuntimeException exception) {
					// Fall through to the local pool; a broken bridge must not stop a fuzz run.
				}
			}
			int length = 1 + rnd.nextInt(24);
			StringBuilder builder = new StringBuilder(length);
			for (int index = 0; index < length; index++) {
				builder.appendCodePoint(fallbackCodePoint(rnd));
			}
			return builder.toString();
		}

		private static long int52(Random rnd) {
			if (VALUES != null) {
				try {
					return (long) VALUES.getMethod("int52", Random.class).invoke(null, rnd);
				} catch (ReflectiveOperationException | RuntimeException exception) {
					// Fall through to the masked range.
				}
			}
			return rnd.nextLong() & Int52.MAX_VALUE_L;
		}

		private static int fallbackCodePoint(Random rnd) {
			if (rnd.nextInt(16) == 0) {
				// astral plane, as a properly paired surrogate pair when appended via appendCodePoint
				return 0x10000 + rnd.nextInt(0x1000);
			}
			if (rnd.nextInt(32) == 0) {
				return 0xFFFD;
			}
			int pair = 2 * rnd.nextInt(FALLBACK_RANGES.length / 2);
			int low = FALLBACK_RANGES[pair];
			int high = FALLBACK_RANGES[pair + 1];
			int codePoint = low + rnd.nextInt(high - low + 1);
			// A lone surrogate in a String is legal and hostile; keep it, everything else is fine too.
			return codePoint;
		}

	}
}
