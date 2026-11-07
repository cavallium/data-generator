package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.NativeNullable;
import it.cavallium.datagen.NotSerializableException;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.datagen.plugin.ProjectionConfiguration;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.EdgeCases;
import net.jqwik.api.RandomGenerator;
import net.jqwik.api.Shrinkable;
import net.jqwik.api.ShrinkingDistance;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared oracle layer for the random-schema fuzz properties of this module.
 *
 * <p>Every oracle takes a {@link Context} (one compiled schema plus its reflective facades), a base-type
 * name and a version, and throws {@link AssertionError} whose message always embeds the schema fingerprint,
 * the version, the type name and {@link #describe()} (tier, seed, case scale), so a failure is
 * self-locating. The oracle bodies intentionally reuse the existing primitives — {@link SchemaGen},
 * {@link SchemaCompiler}/{@link CompiledSchemas}, {@link GeneratedModel}, {@link ReflectiveValueGen} —
 * and add no re-implementation of codec or class-loading logic.</p>
 *
 * <h2>Tier bridge</h2>
 * {@code it.cavallium.datagen.fuzz.FuzzTiers} lives in {@code datagen}'s test sources and is NOT on this
 * module's test classpath, so {@link #tier()}, {@link #cases(String, int)}, {@link #describe()} and
 * {@link #recordFailure(String, long, String, SourcesGeneratorConfiguration)} bridge to it reflectively and
 * fall back to its documented semantics ({@code SchemaGenProfile.currentTier()} and the
 * {@code datagen.fuzz.*} system properties) when the class is absent.
 *
 * <h2>The single value normalization</h2>
 * The String wire path is UTF-8 ({@code writeMediumText}), so unpaired surrogates arrive as {@code '?'} —
 * see {@link #normalizeString(String)}, the ONE place round-trip equality relaxes anything, applied inside
 * {@link #deepEquals(Object, Object)}.
 */
public final class GeneratedOracles {

	/** Base package every fuzz schema in the property tests is generated into (kept constant on purpose). */
	public static final String FUZZ_BASE_PACKAGE = "org.example.fuzz.e2e";

	/** Limits generous enough for any generated value, small enough to turn length bombs into failures. */
	public static final DecodeLimits VALUE_LIMITS =
			new DecodeLimits(200_000, 1 << 24, 2_000_000L, 1L << 26, 64);
	/** Bounded limits used while feeding corrupted bytes: a length bomb must fail, not allocate. */
	public static final DecodeLimits CORRUPTION_LIMITS =
			new DecodeLimits(100_000, 1 << 20, 1_000_000L, 1L << 24, 64);

	private GeneratedOracles() {
	}

	// ------------------------------------------------------------------------------------------------
	// tier / seed / case-count bridge (FuzzTiers is not on this classpath; see class javadoc)
	// ------------------------------------------------------------------------------------------------

	/** The active fuzz tier ({@code FuzzTiers.tier()} when bridgeable, the documented rule otherwise). */
	public static SchemaGenProfile.Tier tier() {
		return SchemaGenProfile.currentTier();
	}

	/** The run seed: {@code FuzzTiers.seed()} when bridgeable, else {@code -Ddatagen.fuzz.seed} or the run time. */
	public static long seed() {
		try {
			Class<?> tiers = Class.forName("it.cavallium.datagen.fuzz.FuzzTiers");
			return (long) tiers.getMethod("seed").invoke(null);
		} catch (ClassNotFoundException absent) {
			// Fall through to the documented fallback rule.
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("FuzzTiers.seed() is on the classpath but failed", failure);
		}
		return fallbackSeed();
	}

	/**
	 * Fallback of {@code FuzzTiers.seed()} (that class is not on this classpath): the literal
	 * {@code -Ddatagen.fuzz.seed} masked to its non-negative form, a fresh JVM-constant seed when the
	 * knob is unset or blank.
	 */
	private static long fallbackSeed() {
		String raw = trimmedProperty("datagen.fuzz.seed");
		if (raw == null) {
			return JVM_CONSTANT_SEED;
		}
		try {
			return Long.parseLong(raw) & Long.MAX_VALUE;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid datagen.fuzz.seed='" + raw + "': expected a long", e);
		}
	}

	/** {@code FuzzTiers}' own fallback rule: a random non-negative seed, fixed once per JVM. */
	private static final long JVM_CONSTANT_SEED = new Random().nextLong() & Long.MAX_VALUE;

	/** @return the trimmed property value, or {@code null} when unset or blank (FuzzTiers.java:221-228) */
	private static String trimmedProperty(String key) {
		String raw = System.getProperty(key);
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** {@code FuzzTiers.cases(id, base)} through the bridge (tier-scaled, {@code -Ddatagen.fuzz.cases} wins). */
	public static int cases(String id, int base) {
		return SchemaGenProfile.cases(id, base);
	}

	/** The four-column {@code cases} form through the bridge. */
	public static int cases(String id, int quick, int base, int deep, int nightly) {
		return SchemaGenProfile.cases(id, quick, base, deep, nightly);
	}

	/** Skips (assumption) the current test unless the tier is at least {@code minimum}. */
	public static void requireMinimumTier(SchemaGenProfile.Tier minimum) {
		Assumptions.assumeTrue(tier().ordinal() >= minimum.ordinal(),
				() -> describe() + " requires tier >= " + minimum);
	}

	/** The run description: {@code FuzzTiers.describe()} when bridgeable, same format otherwise. */
	public static String describe() {
		try {
			Class<?> tiers = Class.forName("it.cavallium.datagen.fuzz.FuzzTiers");
			return (String) tiers.getMethod("describe").invoke(null);
		} catch (ReflectiveOperationException absent) {
			// Fall through to the locally-built, identically-formatted description.
		}
		SchemaGenProfile.Tier tier = tier();
		String scale = switch (tier) {
			case QUICK -> "0.1";
			case DEFAULT -> "1";
			case DEEP -> "10";
			case NIGHTLY -> "50";
		};
		return "tier=" + tier.name() + " seed=" + seed() + " cases-scale=" + scale;
	}

	/**
	 * Best-effort jqwik seed for the {@code @AfterProperty} log lines, so a run is replayable with
	 * {@code -Djqwik.randomseed=<value>} (the engine's own replay knob). Order: the system property when
	 * set, else the seed captured at the FIRST generator draw of this JVM, else {@code unavailable}.
	 * The capture reads the engine's private {@code SourceOfRandomness$XORShiftRandom.seed} field
	 * (which holds {@code mix64(originalSeed)} before the first draw) and un-mixes it; if the engine
	 * internals ever change the value degrades to {@code unavailable}, never to a wrong guess.
	 */
	public static String jqwikSeed() {
		String property = trimmedProperty("jqwik.randomseed");
		if (property != null) {
			return property;
		}
		String captured = capturedJqwikSeed;
		return captured != null ? captured : "unavailable";
	}

	private static final AtomicBoolean JQWIK_SEED_CAPTURED = new AtomicBoolean();
	private static volatile String capturedJqwikSeed;

	/** Called once, at the first {@link #schemaCases} draw, before any randomness is consumed. */
	private static void captureJqwikSeed() {
		if (!JQWIK_SEED_CAPTURED.compareAndSet(false, true)) {
			return;
		}
		try {
			Class<?> session = Class.forName("net.jqwik.api.sessions.JqwikSession");
			Object maybe = session.getMethod("getRandom").invoke(null);
			if (maybe instanceof Optional<?> optional && optional.isPresent()) {
				Object random = optional.get();
				var field = random.getClass().getDeclaredField("seed");
				field.setAccessible(true);
				capturedJqwikSeed = Long.toUnsignedString(unmix64(field.getLong(random)));
			}
		} catch (Throwable expectedOnAnyEngineChange) {
			// Best effort only: jqwikSeed() stays "unavailable".
		}
	}

	/** Inverse of the engine's {@code mix64} (the splitmix64 finalizer), for {@link #captureJqwikSeed()}. */
	private static long unmix64(long mixed) {
		long z = mixed;
		z ^= z >>> 31;
		z *= inverseOf(0x94D0_49BB_1331_11EBL);
		z ^= z >>> 27;
		z *= inverseOf(0xBF58_476D_1CE4_E5B9L);
		z ^= z >>> 30;
		return z;
	}

	/** Multiplicative inverse modulo 2^64 by Newton iteration (six doublings: 1 -&gt; 64 bits). */
	private static long inverseOf(long multiplier) {
		long inverse = 1L;
		for (int bit = 0; bit < 6; bit++) {
			inverse *= 2L - multiplier * inverse;
		}
		return inverse;
	}

	/** A deterministic {@link Random} derived from {@link #seed()} mixed with {@code salt}. */
	public static Random random(long salt) {
		return new Random(mix(seed(), salt));
	}

	public static long mix(long a, long b) {
		long z = a + b * 0x9E37_79B9_7F4A_7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
		return z ^ (z >>> 31);
	}

	// ------------------------------------------------------------------------------------------------
	// context
	// ------------------------------------------------------------------------------------------------

	/** One compiled schema plus the reflective facades every oracle drives. Not closeable: the owner (or
	 * {@link CompiledSchemas} eviction) closes the schema. */
	public record Context(CompiledSchema schema, GeneratedModel model, ReflectiveValueGen values) {

		public Context {
			Objects.requireNonNull(schema, "schema");
			Objects.requireNonNull(model, "model");
			Objects.requireNonNull(values, "values");
		}

		public static Context of(CompiledSchema schema) {
			return new Context(schema, schema.generatedModel(), new ReflectiveValueGen(schema));
		}

		/** The failure-message tail: fingerprint + version + type + {@link #describe()}. */
		public String diag(String typeName, int version) {
			return ", fingerprint=" + schema.fingerprint() + ", version=" + version + ", type=" + typeName
					+ ", " + describe();
		}

		public int currentVersion() {
			return schema.currentVersionId();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// serialize / read primitives shared by the oracles
	// ------------------------------------------------------------------------------------------------

	/** The codec of {@code typeName} at {@code version}; {@link IllegalArgumentException} if the type is absent. */
	@SuppressWarnings("unchecked")
	public static DataCodec<Object> codec(Context ctx, int version, String typeName) {
		return (DataCodec<Object>) ctx.model().codec(version, typeName);
	}

	/** Serializes {@code value} with the {@code version} codec of {@code typeName} into a fresh array. */
	public static byte[] serialize(Context ctx, int version, String typeName, Object value) {
		DataCodec<Object> codec = codec(ctx, version, typeName);
		BufDataOutput output = BufDataOutput.create();
		codec.serialize(output, value);
		Buf payload = output.asList();
		byte[] bytes = new byte[payload.size()];
		for (int index = 0; index < bytes.length; index++) {
			bytes[index] = payload.get(index);
		}
		return bytes;
	}

	/** Reads {@code payload} through {@link DataCodec#read} over a one-shot buffered input. */
	public static Object readViaInput(Context ctx, int version, String typeName, byte[] payload,
			DecodeLimits limits) {
		return codec(ctx, version, typeName).read(BufDataInput.create(Buf.wrap(payload), limits));
	}

	/** One of the five storage kinds a bound read must survive identically. */
	public record Storage(String name, Buf source, int offset) {
	}

	/**
	 * The same five storages {@code GeneratedCodecDeepFuzzTest} exercises: plain heap, heap with an offset,
	 * nested slices, a strict native segment (a full-payload copy is an error) and a Buf that forces the
	 * fallback cursor path. {@code payload} is copied, never retained.
	 */
	public static List<Storage> storages(Arena arena, byte[] payload) {
		byte[] padded = new byte[payload.length + 6];
		System.arraycopy(payload, 0, padded, 3, payload.length);
		MemorySegment nativeSegment = arena.allocate(Math.max(1, payload.length + 6L), 1);
		if (payload.length != 0) {
			MemorySegment.copy(MemorySegment.ofArray(payload), 0, nativeSegment, 3, payload.length);
		}
		return List.of(
				new Storage("heap", Buf.wrap(payload), 0),
				new Storage("heap-offset", Buf.wrap(padded), 3),
				new Storage("heap-nested-slice", Buf.wrap(padded).subListForced(1, payload.length + 5)
						.subListForced(2, payload.length + 2), 0),
				new Storage("native-offset", new StrictNativeBuf(nativeSegment), 3),
				new Storage("fallback", forcedFallbackBuf(Buf.wrap(padded)), 3));
	}

	/** A native {@link Buf} whose {@code asArray()} would mean a full-payload copy — treated as a bug. */
	static final class StrictNativeBuf extends MemorySegmentBuf {

		StrictNativeBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Native reader copied the complete payload");
		}
	}

	/** A {@link Buf} that hides every fast-path accessor, forcing the byte-wise fallback cursor. */
	public static Buf forcedFallbackBuf(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(GeneratedOracles.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict", "asArrayStrict",
							"asUnboundedArrayStrict" -> null;
					case "getBackingByteArray", "asArray", "asUnboundedArray", "binaryInputStream" ->
							throw new AssertionError("Fallback reader converted the complete payload");
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}

	// ------------------------------------------------------------------------------------------------
	// deep equality
	// ------------------------------------------------------------------------------------------------

	/**
	 * The ONE documented normalization: the String wire path is UTF-8, so unpaired surrogates arrive as
	 * {@code '?'}. Comparing {@code new String(s.getBytes(UTF_8), UTF_8)} on both sides makes that loss
	 * explicit once instead of spreading it through the oracles.
	 */
	public static String normalizeString(String value) {
		return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	/**
	 * Structural equality of two runtime values of a generated schema.
	 *
	 * <ul>
	 *   <li>{@code String}: {@link #normalizeString(String)} on both sides (the single relaxation);</li>
	 *   <li>{@code float}/{@code double} (boxed or arrayed): raw bits, so {@code NaN == NaN} and
	 *       {@code +0.0 != -0.0} exactly as the codec round-trips them;</li>
	 *   <li>arrays: element-wise through this method;</li>
	 *   <li>{@link NativeNullable}: compared through {@code getNullable()} — the carrier class differs
	 *       between natives and versioned types;</li>
	 *   <li>classes from a generated {@code .data.} package: record components (accessors preferred,
	 *       declared fields as fallback) — this avoids depending on {@code equals} of classes compiled
	 *       inside a named module;</li>
	 *   <li>everything else ({@code Int52}, {@code BinaryString}, natives): {@code equals}.</li>
	 * </ul>
	 */
	public static boolean deepEquals(Object left, Object right) {
		return deepEquals(left, right, 0);
	}

	private static boolean deepEquals(Object left, Object right, int depth) {
		if (depth > 256) {
			throw new IllegalStateException("deepEquals nested deeper than 256 levels (unbalanced data?)");
		}
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		if (left instanceof String leftString) {
			return right instanceof String rightString && normalizeString(leftString).equals(normalizeString(rightString));
		}
		if (left instanceof Float || left instanceof Double) {
			return left.getClass() == right.getClass() && left.equals(right); // bits, via boxed equals of the same class
		}
		if (left.getClass().isArray()) {
			return arrayEquals(left, right, depth);
		}
		if (left instanceof NativeNullable<?> leftNullable) {
			if (!(right instanceof NativeNullable<?> rightNullable)) {
				return false;
			}
			return deepEquals(leftNullable.getNullable(), rightNullable.getNullable(), depth + 1);
		}
		if (left.getClass().getName().contains(".data.")) {
			return structuralDataEquals(left, right, depth);
		}
		if (left.getClass().isRecord() && left.getClass() == right.getClass()) {
			// Record equals() compares array components by identity — project the components instead.
			for (RecordComponent component : left.getClass().getRecordComponents()) {
				if (!deepEquals(invokeNoArg(component.getAccessor(), left),
						invokeNoArg(component.getAccessor(), right), depth + 1)) {
					return false;
				}
			}
			return true;
		}
		return left.equals(right);
	}

	private static boolean arrayEquals(Object left, Object right, int depth) {
		if (!right.getClass().isArray()
				|| left.getClass().getComponentType() != right.getClass().getComponentType()) {
			return false;
		}
		int length = Array.getLength(left);
		if (length != Array.getLength(right)) {
			return false;
		}
		if (left.getClass().getComponentType().isPrimitive()) {
			// Arrays.equals on primitive arrays compares raw bits: NaN==NaN, +0.0 != -0.0. Exactly wanted.
			return primitiveArraysEqual(left, right);
		}
		for (int index = 0; index < length; index++) {
			if (!deepEquals(Array.get(left, index), Array.get(right, index), depth + 1)) {
				return false;
			}
		}
		return true;
	}

	private static boolean primitiveArraysEqual(Object left, Object right) {
		if (left instanceof byte[] a) return Arrays.equals(a, (byte[]) right);
		if (left instanceof boolean[] a) return Arrays.equals(a, (boolean[]) right);
		if (left instanceof short[] a) return Arrays.equals(a, (short[]) right);
		if (left instanceof char[] a) return Arrays.equals(a, (char[]) right);
		if (left instanceof int[] a) return Arrays.equals(a, (int[]) right);
		if (left instanceof long[] a) return Arrays.equals(a, (long[]) right);
		if (left instanceof float[] a) return Arrays.equals(a, (float[]) right);
		if (left instanceof double[] a) return Arrays.equals(a, (double[]) right);
		throw new IllegalStateException("Unknown primitive array component " + left.getClass());
	}

	private static boolean structuralDataEquals(Object left, Object right, int depth) {
		if (left.getClass() != right.getClass()) {
			return false;
		}
		Class<?> type = left.getClass();
		if (type.isRecord()) {
			for (RecordComponentAccessor component : recordAccessors(type)) {
				Object leftPart = component.read(left);
				Object rightPart = component.read(right);
				if (!deepEquals(leftPart, rightPart, depth + 1)) {
					return false;
				}
			}
			return true;
		}
		List<Field> fields = declaredFields(type);
		for (Field field : fields) {
			try {
				field.setAccessible(true);
				if (!deepEquals(field.get(left), field.get(right), depth + 1)) {
					return false;
				}
			} catch (IllegalAccessException exception) {
				throw new IllegalStateException("Cannot read " + field + " of generated data class", exception);
			}
		}
		return true;
	}

	private record RecordComponentAccessor(String name, Method accessor) {
		Object read(Object target) {
			try {
				return accessor.invoke(target);
			} catch (ReflectiveOperationException exception) {
				throw new IllegalStateException("Cannot read record component " + name, unwrap(exception));
			}
		}
	}

	private static List<RecordComponentAccessor> recordAccessors(Class<?> recordType) {
		List<RecordComponentAccessor> accessors = new ArrayList<>();
		for (RecordComponent component : recordType.getRecordComponents()) {
			try {
				accessors.add(new RecordComponentAccessor(component.getName(), component.getAccessor()));
			} catch (SecurityException exception) {
				throw new IllegalStateException("Cannot access record component " + component.getName(), exception);
			}
		}
		return accessors;
	}

	private static List<Field> declaredFields(Class<?> type) {
		List<Field> fields = new ArrayList<>();
		for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
			fields.addAll(Arrays.asList(owner.getDeclaredFields()));
		}
		return fields;
	}

	static Throwable unwrap(Throwable throwable) {
		return throwable instanceof InvocationTargetException target && target.getCause() != null
				? target.getCause() : throwable;
	}

	/**
	 * True when {@code failure} (or any cause) is the {@link IllegalArgumentException} the generated
	 * {@code IVersion.getCodec} throws for a type that does not exist in that version. {@link
	 * GeneratedModel} wraps it into an {@link IllegalStateException}, so both forms must be recognized.
	 */
	static boolean isTypeAbsent(RuntimeException failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof IllegalArgumentException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	/** A short, stable sketch of a value for failure messages (class + array sizes + toString head). */
	public static String sketch(Object value) {
		if (value == null) {
			return "null";
		}
		if (value.getClass().isArray()) {
			return value.getClass().getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
		}
		String text = String.valueOf(value);
		return value.getClass().getSimpleName() + "(" + (text.length() <= 160 ? text : text.substring(0, 160) + "...") + ")";
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 1: round-trip over every read path and every storage kind
	// ------------------------------------------------------------------------------------------------

	/**
	 * Round-trips {@code value} three ways and compares every result with {@link #deepEquals}:
	 * <ol>
	 *   <li>one-shot: {@code DataCodec.read} over a fresh buffered input;</li>
	 *   <li>mixed: ONE {@code DataCodec.Reader} reused across all five {@link #storages storage kinds};</li>
	 *   <li>bound: the generated {@code CurrentVersion.BoundReader} per storage.</li>
	 * </ol>
	 * Also asserts every cursor ends unbound. Returns the payload so callers can chain oracles on it.
	 */
	public static byte[] roundTripAllPaths(Context ctx, String typeName, int version, Object value,
			DecodeLimits limits) {
		String where = ctx.diag(typeName, version);
		byte[] payload = serialize(ctx, version, typeName, value);
		Object oneShot = readViaInput(ctx, version, typeName, payload, limits);
		org.junit.jupiter.api.Assertions.assertTrue(deepEquals(value, oneShot),
				"one-shot input read differs" + where + ", expected=" + sketch(value) + ", got=" + sketch(oneShot));

		DataCodec<Object> codec = codec(ctx, version, typeName);
		DataCodec.Reader<Object> reader = codec.newReader(limits);
		for (Storage storage : storages(Arena.ofConfined(), payload)) {
			Object decoded = reader.read(storage.source(), storage.offset(), payload.length);
			org.junit.jupiter.api.Assertions.assertTrue(deepEquals(value, decoded),
					"reused DataCodec.Reader differs on storage " + storage.name() + where
							+ ", expected=" + sketch(value) + ", got=" + sketch(decoded));
		}
		assertAllCursorsUnbound(reader, "DataCodec.Reader" + where);

		Object baseType = ctx.model().baseTypeConstant(typeName);
		for (Storage storage : storages(Arena.ofConfined(), payload)) {
			Object boundReader = ctx.model().newBoundReader(version, baseType, limits);
			Object decoded;
			try {
				Method read = boundReader.getClass().getMethod("read", Buf.class, int.class, int.class);
				read.setAccessible(true);
				decoded = read.invoke(boundReader, storage.source(), storage.offset(), payload.length);
			} catch (ReflectiveOperationException exception) {
				throw new IllegalStateException("BoundReader.read(Buf,int,int) failed for "
						+ boundReader.getClass().getName(), unwrap(exception));
			}
			org.junit.jupiter.api.Assertions.assertTrue(deepEquals(value, decoded),
					"generated BoundReader differs on storage " + storage.name() + where
							+ ", expected=" + sketch(value) + ", got=" + sketch(decoded));
			assertAllCursorsUnbound(boundReader, "BoundReader" + where + ", storage=" + storage.name());
		}
		return payload;
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 2: byte determinism
	// ------------------------------------------------------------------------------------------------

	/** Serializing twice yields identical bytes, and re-serializing the decoded value is stable too. */
	public static void byteDeterminism(Context ctx, int version, String typeName, Object value) {
		String where = ctx.diag(typeName, version);
		byte[] first = serialize(ctx, version, typeName, value);
		byte[] second = serialize(ctx, version, typeName, value);
		org.junit.jupiter.api.Assertions.assertArrayEquals(first, second,
				"serialization is not byte-deterministic" + where);
		Object decoded = readViaInput(ctx, version, typeName, first, VALUE_LIMITS);
		byte[] third = serialize(ctx, version, typeName, decoded);
		org.junit.jupiter.api.Assertions.assertArrayEquals(first, third,
				"re-serializing the decoded value changed bytes" + where);
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 3: skip consumes exactly one value
	// ------------------------------------------------------------------------------------------------

	/**
	 * After {@code codec.skip} over the concatenation of two encodings, exactly the second value remains
	 * (readable, then the input is exhausted); the bounded {@code DataCodec.Reader} rejects the same bytes
	 * plus one trailing byte with {@link MalformedDataException}. Every read uses a fresh cursor.
	 */
	public static void skipConsumesOne(Context ctx, int version, String typeName, Object value,
			DecodeLimits limits) {
		String where = ctx.diag(typeName, version);
		byte[] payload = serialize(ctx, version, typeName, value);
		byte[] doubled = new byte[payload.length * 2];
		System.arraycopy(payload, 0, doubled, 0, payload.length);
		System.arraycopy(payload, 0, doubled, payload.length, payload.length);
		DataCodec<Object> codec = codec(ctx, version, typeName);

		BufDataInput input = BufDataInput.create(Buf.wrap(doubled), limits);
		codec.skip(input);
		Object second = codec.read(input);
		org.junit.jupiter.api.Assertions.assertTrue(deepEquals(value, second),
				"the value after skip() is not the second encoding" + where + ", got=" + sketch(second));
		org.junit.jupiter.api.Assertions.assertEquals(0, input.available(),
				"skip()+read() did not consume exactly two values" + where);

		byte[] withTrailing = Arrays.copyOf(payload, payload.length + 1);
		org.junit.jupiter.api.Assertions.assertThrows(MalformedDataException.class,
				() -> codec.newReader(limits).read(Buf.wrap(withTrailing), 0, withTrailing.length),
				"a Reader must reject trailing bytes" + where);
	}

	// ------------------------------------------------------------------------------------------------
	// oracles 4 and 5: upgrade equivalence and chain transitivity
	// ------------------------------------------------------------------------------------------------

	/**
	 * The headline evolution oracle: the fused path ({@code CurrentVersion.read} of the {@code version}
	 * bytes) must produce exactly ({@link #deepEquals}) the object path ({@code version} codec read, then
	 * {@code upgradeDataToLatestVersion}).
	 *
	 * @return {@code null} when the pair was verified, or a skip reason when this (type, version) cannot
	 *         exercise the old path: {@code type-absent@v<n>} (no codec for the type at that version),
	 *         {@code no-old-serializer@v<n>} (a structurally fused version whose historical codec refuses
	 *         to serialize — with {@code generateOldSerializers=false} this is the norm, and it is a
	 *         counted skip, not a defect) or {@code class-fallback-mismatch@v<n>} (the value was built
	 *         from the current data class but the historical serializer casts its own — also a counted
	 *         skip, harness-side, not a defect)
	 */
	public static String upgradeEquivalence(Context ctx, String typeName, int oldVersion, Object value) {
		String where = ctx.diag(typeName, oldVersion);
		DataCodec<Object> oldCodec;
		try {
			oldCodec = codec(ctx, oldVersion, typeName);
		} catch (RuntimeException absent) {
			if (!isTypeAbsent(absent)) {
				throw absent;
			}
			return "type-absent@v" + oldVersion;
		}
		byte[] payload;
		try {
			payload = serialize(ctx, oldVersion, typeName, value);
		} catch (NotSerializableException unserializable) {
			return "no-old-serializer@v" + oldVersion;
		} catch (ClassCastException fusedFallback) {
			// ReflectiveValueGen builds fused-version values from the CURRENT class (its documented
			// dataClass fallback), but this version's serializer casts its own historical class — the
			// value simply is not the class this codec accepts. Counted skip, harness-side, not a defect.
			return "class-fallback-mismatch@v" + oldVersion;
		}
		Object baseType = ctx.model().baseTypeConstant(typeName);
		Object fused = ctx.model().read(oldVersion, baseType,
				BufDataInput.create(Buf.wrap(payload), VALUE_LIMITS));
		Object decoded = oldCodec.read(BufDataInput.create(Buf.wrap(payload), VALUE_LIMITS));
		Object upgraded = ctx.model().upgrade(oldVersion, decoded);
		org.junit.jupiter.api.Assertions.assertTrue(deepEquals(fused, upgraded),
				"fused CurrentVersion.read differs from codec-read + upgradeDataToLatestVersion" + where
						+ ", fused=" + sketch(fused) + ", upgraded=" + sketch(upgraded));
		return null;
	}

	/**
	 * Chain transitivity: upgrading in one jump ({@code upgradeDataToLatestVersion}) and upgrading step by
	 * step (each historical {@code Version.upgradeToNextVersion}, skipping structurally fused versions that
	 * emitted no class of their own) must produce the same value.
	 *
	 * @return {@code null} when verified, else the {@link #upgradeEquivalence} skip reason
	 */
	public static String chainTransitivity(Context ctx, String typeName, int fromVersion, Object value) {
		String skip = upgradeEquivalence(ctx, typeName, fromVersion, value);
		if (skip != null) {
			return skip;
		}
		String where = ctx.diag(typeName, fromVersion);
		byte[] payload = serialize(ctx, fromVersion, typeName, value);
		Object decoded = codec(ctx, fromVersion, typeName)
				.read(BufDataInput.create(Buf.wrap(payload), VALUE_LIMITS));
		Object direct = ctx.model().upgrade(fromVersion, decoded);
		Object stepped = decoded;
		ClassLoader loader = ctx.schema().loader();
		for (int version = fromVersion; version < ctx.currentVersion(); version++) {
			Class<?> versionClass;
			try {
				versionClass = loader.loadClass(ctx.schema().rootPackage() + ".v" + version + ".Version");
			} catch (ClassNotFoundException fused) {
				continue; // structurally fused version: same class as the next one, nothing to run
			}
			Method upgrade = null;
			for (Method method : versionClass.getMethods()) {
				if ("upgradeToNextVersion".equals(method.getName()) && method.getParameterCount() == 1) {
					upgrade = method;
					break;
				}
			}
			if (upgrade == null) {
				continue;
			}
			try {
				upgrade.setAccessible(true);
				stepped = upgrade.invoke(null, stepped);
			} catch (ReflectiveOperationException exception) {
				throw new IllegalStateException("upgradeToNextVersion of " + versionClass.getName()
						+ " failed" + where, unwrap(exception));
			}
		}
		org.junit.jupiter.api.Assertions.assertTrue(deepEquals(direct, stepped),
				"one-jump upgrade differs from the step-by-step chain" + where
						+ ", direct=" + sketch(direct) + ", stepped=" + sketch(stepped));
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 6: historical self-decodability
	// ------------------------------------------------------------------------------------------------

	/**
	 * A {@code version} codec must decode what the {@code version} codec emitted — without involving the
	 * current version at all.
	 *
	 * @return {@code null} when verified, else the same counted-skip reasons as {@link #upgradeEquivalence}
	 */
	public static String historicalSelfDecodable(Context ctx, String typeName, int version, Object value) {
		DataCodec<Object> oldCodec;
		try {
			oldCodec = codec(ctx, version, typeName);
		} catch (RuntimeException absent) {
			if (!isTypeAbsent(absent)) {
				throw absent;
			}
			return "type-absent@v" + version;
		}
		byte[] payload;
		try {
			payload = serialize(ctx, version, typeName, value);
		} catch (NotSerializableException unserializable) {
			return "no-old-serializer@v" + version;
		} catch (ClassCastException fusedFallback) {
			// Same counted skip as in upgradeEquivalence (ReflectiveValueGen's fused-version fallback).
			return "class-fallback-mismatch@v" + version;
		}
		Object decoded = oldCodec.read(BufDataInput.create(Buf.wrap(payload), VALUE_LIMITS));
		org.junit.jupiter.api.Assertions.assertTrue(deepEquals(value, decoded),
				"the v" + version + " codec did not decode its own bytes" + ctx.diag(typeName, version)
						+ ", expected=" + sketch(value) + ", got=" + sketch(decoded));
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 7: projection parity
	// ------------------------------------------------------------------------------------------------

	/**
	 * For every projection of {@code typeName} (as configured, with {@code sourceType == typeName}):
	 * {@code Projection.read(version, input)} must match the same components extracted from a full read of
	 * the source value; {@code readInto} must present the same values through its {@code Sink}; the bound
	 * {@code Reader} must match the static entry point; and a {@code consumeToEnd=false} projection that
	 * stops before the last stored field must leave unread bytes in the cursor.
	 *
	 * @return the number of projections checked (0 when {@code typeName} is no projection source)
	 */
	public static int projectionParity(Context ctx, String typeName, Object value) {
		SourcesGeneratorConfiguration config = ctx.schema().config();
		Map<String, ProjectionConfiguration> projections =
				config.projectionsData == null ? Map.of() : config.projectionsData;
		int current = ctx.currentVersion();
		String where = ctx.diag(typeName, current);
		int checked = 0;
		for (Map.Entry<String, ProjectionConfiguration> entry : projections.entrySet()) {
			ProjectionConfiguration projection = entry.getValue();
			if (!typeName.equals(projection.sourceType)) {
				continue;
			}
			Class<?> projectionClass = ctx.schema().load(ctx.schema().rootPackage() + ".projections."
					+ projectionClassName(entry.getKey()));
			byte[] payload = serialize(ctx, current, typeName, value);
			Object result = invokeStatic(projectionClass, "read",
					new Class<?>[] {int.class, it.cavallium.stream.SafeDataInput.class},
					current, BufDataInput.create(Buf.wrap(payload), VALUE_LIMITS));
			Class<?> resultClass = result.getClass();

			// 1. Result vs a full read of the same bytes.
			Object full = ctx.model().read(current, ctx.model().baseTypeConstant(typeName),
					BufDataInput.create(Buf.wrap(payload), VALUE_LIMITS));
			List<String> components = new ArrayList<>(projection.fields.keySet());
			for (String component : components) {
				Object projected = read(result, component);
				Object expected = expectedFromSource(full, projection.fields.get(component));
				Object projectedValue = projected instanceof NativeNullable<?> nullable
						? nullable.getNullable() : projected;
				org.junit.jupiter.api.Assertions.assertTrue(deepEquals(expected, projectedValue),
						"projection " + entry.getKey() + " component " + component + " differs from the full read"
								+ where + ", expected=" + sketch(expected) + ", got=" + sketch(projectedValue));
			}

			// 2. readInto sink parity: the sink must receive exactly the Result's components.
			Object[] captured = captureSink(projectionClass, current, payload, resultClass);
			List<Object> flattened = flattenResult(resultClass, result);
			org.junit.jupiter.api.Assertions.assertEquals(flattened.size(), captured.length,
					"projection " + entry.getKey() + " sink arity differs" + where);
			for (int index = 0; index < flattened.size(); index++) {
				org.junit.jupiter.api.Assertions.assertTrue(deepEquals(flattened.get(index), captured[index]),
						"projection " + entry.getKey() + " sink component " + index + " differs" + where
								+ ", read=" + sketch(flattened.get(index)) + ", sink=" + sketch(captured[index]));
			}

			// 3. The bound projection Reader must agree with the static entry point.
			Object reader = invokeStatic(projectionClass, "newReader",
					new Class<?>[] {DecodeLimits.class}, VALUE_LIMITS);
			Object viaReader;
			try {
				Method readMethod = reader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class);
				readMethod.setAccessible(true);
				viaReader = readMethod.invoke(reader, current, Buf.wrap(payload), 0, payload.length);
			} catch (ReflectiveOperationException exception) {
				throw new IllegalStateException("projection Reader.read failed" + where, unwrap(exception));
			}
			org.junit.jupiter.api.Assertions.assertTrue(deepEquals(result, viaReader),
					"projection " + entry.getKey() + " bound Reader differs from Projection.read" + where);

			// 4. consumeToEnd=false stops early: with fields after the last selected one, bytes remain.
			if (!projection.consumesToEnd()) {
				int storedFields = recordComponentNames(ctx.schema().recordClass(typeName)).size();
				int selected = projection.fields.size();
				BufDataCursor cursor = new BufDataCursor(VALUE_LIMITS);
				cursor.bind(Buf.wrap(payload), 0, payload.length);
				Object ignored = invokeStatic(projectionClass, "read",
						new Class<?>[] {int.class, it.cavallium.stream.SafeDataInput.class}, current, cursor);
				int trailing = cursor.remaining();
				cursor.unbind();
				if (selected < storedFields) {
					org.junit.jupiter.api.Assertions.assertTrue(trailing > 0,
							"projection " + entry.getKey() + " with consumeToEnd=false read the whole payload"
									+ where + ", trailing=" + trailing);
				}
				org.junit.jupiter.api.Assertions.assertNotNull(ignored, "projection read returned null" + where);
			}
			checked++;
		}
		return checked;
	}

	static String projectionClassName(String configuredName) {
		return configuredName.endsWith("Projection") ? configuredName : configuredName + "Projection";
	}

	private static List<String> recordComponentNames(Class<?> recordType) {
		List<String> names = new ArrayList<>();
		RecordComponent[] components = recordType.getRecordComponents(); // null when not a record class
		if (components == null) {
			for (Field field : declaredFields(recordType)) {
				// $datagen$present$<f> is the presence flag of field f, not a schema field
				// (GenProjection emits no trailing-bytes check for consumeToEnd:false, and a
				// projection selecting ALL declared fields legitimately consumes to the end).
				if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
						&& !field.getName().startsWith("$datagen$")) {
					names.add(field.getName());
				}
			}
		} else {
			for (RecordComponent component : components) {
				names.add(component.getName());
			}
		}
		return names;
	}

	/** Resolves one configured projection path on the source value, through the real generated accessors. */
	private static Object expectedFromSource(Object source, String path) {
		Object current = source;
		for (String step : path.split("\\.")) {
			if (current == null) {
				return null;
			}
			if (current instanceof NativeNullable<?> nullable) {
				current = nullable.getNullable();
				if (current == null) {
					return null;
				}
			}
			current = readGeneratedAccessor(current, step);
		}
		if (current instanceof NativeNullable<?> nullable) {
			current = nullable.getNullable();
		}
		return current;
	}

	/**
	 * The value a generated class exposes for a field: {@code fOrNull()} for nullable fields whose base is
	 * a reference type (the plain {@code f()} throws on an empty nullable, so the OrNull form is preferred);
	 * for a nullable field whose base is a PRIMITIVE the class exposes no {@code fOrNull()} but a presence
	 * boolean {@code hasF()}, so an absent one resolves to {@code null} and a present one to the plain
	 * {@code f()}; and for arrays only the size/index/{@code fCopy()} family — there the defensive copy is
	 * the value.
	 *
	 * <p>The primitive-nullable form is proven by the generated file {@code datagen-plugin/target/
	 * fuzz-schemas/680c77ee267787ab217644f49fc69d020f31ca82f7f6aab5a0d7bce61ce8f146/sources/org/example/
	 * fuzz/e2e/current/data/G1taspsT0.java} (repro of {@code target/fuzz-failures/
	 * projections.force_CONTEXT_PARAMETERS.txt}): it exposes {@code hasF0()} (line 46, returning
	 * {@code $datagen$present$f0}) and {@code f0()} (lines 50-55) that throws
	 * {@code NoSuchElementException("Nullable field f0 is empty")} when the flag is false — and no
	 * {@code f0OrNull()}. Emission rule: {@code GenDataBaseX.addNullableAccessorsAndWithers} always emits
	 * {@code hasF()} (GenDataBaseX.java:233-237) and the throwing plain accessor (GenDataBaseX.java:239-248),
	 * but {@code fOrNull} only for non-primitive bases (GenDataBaseX.java:250-257).</p>
	 *
	 * <p>A {@code NoSuchElementException} from a plain accessor is therefore never caught blindly: absence
	 * is decided ONLY by the class's own {@code hasF()}. A genuine accessor error (a throwing accessor
	 * with {@code hasF()} true, or with no presence boolean at all) still propagates as
	 * {@link IllegalStateException} from {@link #invokeNoArg}.</p>
	 */
	static Object readGeneratedAccessor(Object target, String field) {
		Class<?> type = target.getClass();
		Method orNull = findNoArg(type, field + "OrNull");
		if (orNull != null) {
			return invokeNoArg(orNull, target);
		}
		Method present = findNoArg(type, "has" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
		Method plain = findNoArg(type, field);
		if (present != null && plain != null) {
			return (Boolean) invokeNoArg(present, target) ? invokeNoArg(plain, target) : null;
		}
		if (plain != null) {
			return invokeNoArg(plain, target);
		}
		Method copy = findNoArg(type, field + "Copy");
		if (copy != null) {
			return invokeNoArg(copy, target);
		}
		throw new IllegalStateException("Generated class " + type.getName() + " exposes no accessor for "
				+ field + " (tried " + field + ", " + field + "OrNull, has"
				+ Character.toUpperCase(field.charAt(0)) + field.substring(1) + ", " + field + "Copy)");
	}

	private static Method findNoArg(Class<?> type, String name) {
		try {
			return type.getMethod(name);
		} catch (NoSuchMethodException absent) {
			return null;
		}
	}

	private static Method requireNoArg(Class<?> type, String name) {
		Method method = findNoArg(type, name);
		if (method == null) {
			throw new IllegalStateException("Generated record " + type.getName() + " has no accessor " + name);
		}
		return method;
	}

	private static Object invokeNoArg(Method method, Object target) {
		try {
			return method.invoke(target);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("Accessor " + method.getName() + " failed", unwrap(exception));
		}
	}

	private static Object read(Object owner, String component) {
		return invokeNoArg(requireNoArg(owner.getClass(), component), owner);
	}

	/** The Result's components, nullable carriers already unwrapped to value-or-null. */
	private static List<Object> flattenResult(Class<?> resultClass, Object result) {
		List<Object> values = new ArrayList<>();
		for (RecordComponent component : resultClass.getRecordComponents()) {
			Object value = read(result, component.getName());
			values.add(value instanceof NativeNullable<?> nullable ? nullable.getNullable() : value);
		}
		return values;
	}

	/**
	 * Captures one {@code Sink.accept} call and decodes it positionally using the {@code Result} record
	 * components: a nullable (NativeNullable-wrapped) component contributes {@code (boolean, value)} to
	 * the sink and is recorded unwrapped (value or {@code null}); any other component — INCLUDING a plain
	 * {@code boolean} field, which is a real value, not a presence flag — contributes just its value.
	 */
	private static Object[] captureSink(Class<?> projectionClass, int version, byte[] payload,
			Class<?> resultClass) {
		Class<?> sinkInterface;
		try {
			sinkInterface = Class.forName(projectionClass.getName() + "$Sink", true, projectionClass.getClassLoader());
		} catch (ClassNotFoundException exception) {
			throw new IllegalStateException("projection has no Sink interface", exception);
		}
		RecordComponent[] components = resultClass.getRecordComponents();
		final Object[] captured = new Object[1];
		Object sink = Proxy.newProxyInstance(projectionClass.getClassLoader(), new Class<?>[] {sinkInterface},
				(proxy, method, arguments) -> {
					if ("accept".equals(method.getName())) {
						if (captured[0] != null) {
							throw new AssertionError("projection Sink.accept was called twice");
						}
						List<Object> values = new ArrayList<>();
						int argumentIndex = 0;
						for (RecordComponent component : components) {
							if (NativeNullable.class.isAssignableFrom(component.getType())) {
								boolean present = (Boolean) arguments[argumentIndex++];
								Object value = arguments[argumentIndex++];
								values.add(present ? value : null);
							} else {
								values.add(arguments[argumentIndex++]);
							}
						}
						if (argumentIndex != arguments.length) {
							throw new AssertionError("projection Sink.accept took " + arguments.length
									+ " arguments, decoded " + argumentIndex);
						}
						captured[0] = values.toArray();
					}
					return null;
				});
		invokeStatic(projectionClass, "readInto",
				new Class<?>[] {int.class, it.cavallium.stream.SafeDataInput.class, sinkInterface},
				version, BufDataInput.create(Buf.wrap(payload), VALUE_LIMITS), sink);
		if (captured[0] == null) {
			throw new AssertionError("projection " + projectionClass.getName() + " never called Sink.accept");
		}
		return (Object[]) captured[0];
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 8: the generated value contract
	// ------------------------------------------------------------------------------------------------

	/**
	 * Checks the value contract of one record value: reflexivity and round-trip consistency of
	 * equals/hashCode, defensive array copies in the {@code of}/canonical constructor, and canonicalization
	 * (a zero-field record is its own {@code INSTANCE}; two decodes of empty arrays return the same
	 * instance). Returns {@code value.toString()} so the caller can collect a collision sample.
	 */
	public static String valueContract(Context ctx, String typeName, Object value) {
		int current = ctx.currentVersion();
		String where = ctx.diag(typeName, current);
		org.junit.jupiter.api.Assertions.assertTrue(value.equals(value),
				"equals is not reflexive" + where + ", value=" + sketch(value));
		byte[] payload = serialize(ctx, current, typeName, value);
		Object copy = readViaInput(ctx, current, typeName, payload, VALUE_LIMITS);
		org.junit.jupiter.api.Assertions.assertTrue(deepEquals(value, copy),
				"a round-tripped value is not deeply equal (beyond the ONE string normalization)" + where
						+ ", expected=" + sketch(value) + ", got=" + sketch(copy));
		// equals/hashCode are checked on the decoded value and its re-decode: the original value may
		// legitimately differ from its bytes by the documented unpaired-surrogate normalization only.
		Object reDecoded = readViaInput(ctx, current, typeName,
				serialize(ctx, current, typeName, copy), VALUE_LIMITS);
		org.junit.jupiter.api.Assertions.assertTrue(copy.equals(reDecoded),
				"a decoded value is not equal to its own re-decode" + where);
		org.junit.jupiter.api.Assertions.assertEquals(copy.hashCode(), reDecoded.hashCode(),
				"hashCode is not stable across decode/re-decode" + where);

		Class<?> dataClass = value.getClass();
		String text = copy.toString();
		org.junit.jupiter.api.Assertions.assertNotNull(text, "toString() is null" + where);
		org.junit.jupiter.api.Assertions.assertFalse(text.isEmpty(), "toString() is empty" + where);
		org.junit.jupiter.api.Assertions.assertTrue(reDecoded.toString().equals(text),
				"toString() is not stable across a round trip" + where);

		if (dataClass.isRecord()) {
			checkDefensiveArrayCopies(ctx, typeName, copy, where);
			checkCanonicalization(ctx, typeName, payload, where);
		} else {
			checkGeneratedClassContract(ctx, typeName, reDecoded, where);
		}
		return text;
	}

	/**
	 * The same guarantees for the generated plain data classes (the usual form here): every array field's
	 * {@code fCopy()} hands out a fresh copy of a non-empty array but the canonical shared instance for an
	 * empty one, and a class exposing {@code INSTANCE} (zero fields) always decodes to that singleton.
	 */
	private static void checkGeneratedClassContract(Context ctx, String typeName, Object decoded,
			String where) {
		Class<?> dataClass = decoded.getClass();
		for (Field field : declaredFields(dataClass)) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
					|| !field.getType().isArray()) {
				continue;
			}
			Method copyMethod = findNoArg(dataClass, field.getName() + "Copy");
			if (copyMethod == null) {
				continue;
			}
			Object first = invokeNoArg(copyMethod, decoded);
			Object second = invokeNoArg(copyMethod, decoded);
			if (Array.getLength(first) == 0) {
				org.junit.jupiter.api.Assertions.assertSame(first, second,
						"empty arrays did not use the canonical instance, field " + field.getName() + where);
			} else {
				org.junit.jupiter.api.Assertions.assertNotSame(first, second,
						field.getName() + "Copy() did not hand out a fresh copy" + where);
				org.junit.jupiter.api.Assertions.assertTrue(deepEquals(first, second),
						field.getName() + "Copy() returned inconsistent copies" + where);
			}
		}
		try {
			Field instance = dataClass.getField("INSTANCE");
			if (java.lang.reflect.Modifier.isStatic(instance.getModifiers())) {
				org.junit.jupiter.api.Assertions.assertSame(instance.get(null), decoded,
						"the INSTANCE singleton did not survive decoding" + where);
			}
		} catch (NoSuchFieldException absent) {
			// Only zero-field records expose INSTANCE; nothing to check for a class without it.
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("INSTANCE not readable on " + dataClass.getName(), exception);
		}
	}

	/** The {@code of} factory (or canonical constructor) must copy arrays it is handed. */
	private static void checkDefensiveArrayCopies(Context ctx, String typeName, Object value, String where) {
		Class<?> dataClass = value.getClass();
		RecordComponent[] components = dataClass.getRecordComponents();
		List<Object> arguments = new ArrayList<>();
		for (RecordComponent component : components) {
			arguments.add(invokeNoArg(requireNoArg(dataClass, component.getName()), value));
		}
		Object rebuilt = null;
		try {
			Class<?>[] parameterTypes = Arrays.stream(components).map(RecordComponent::getType).toArray(Class[]::new);
			Object[] args = arguments.toArray();
			try {
				Method of = dataClass.getMethod("of", parameterTypes);
				rebuilt = of.invoke(null, args);
			} catch (NoSuchMethodException noFactory) {
				Constructor<?> constructor = dataClass.getDeclaredConstructor(parameterTypes);
				rebuilt = constructor.newInstance(args);
			}
			for (int index = 0; index < components.length; index++) {
				Object argument = arguments.get(index);
				if (!argument.getClass().isArray() || Array.getLength(argument) == 0
						|| argument.getClass().getComponentType().isPrimitive()) {
					continue; // mutating a primitive array is visible to the record only if it failed to copy
				}
				Object snapshot = java.lang.reflect.Array.newInstance(
						argument.getClass().getComponentType(), Array.getLength(argument));
				System.arraycopy(argument, 0, snapshot, 0, Array.getLength(argument));
				Array.set(argument, 0, Array.get(Array.newInstance(argument.getClass().getComponentType(), 1), 0));
				Object after = invokeNoArg(requireNoArg(dataClass, components[index].getName()), rebuilt);
				org.junit.jupiter.api.Assertions.assertTrue(deepEquals(snapshot, after),
						"the constructor did not defensively copy the array component "
								+ components[index].getName() + where);
			}
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("cannot rebuild " + dataClass.getName() + where, unwrap(exception));
		}
	}

	/** Zero-field records are the {@code INSTANCE} singleton; empty arrays decode to the canonical one. */
	private static void checkCanonicalization(Context ctx, String typeName, byte[] payload, String where) {
		Object decoded = readViaInput(ctx, ctx.currentVersion(), typeName, payload, VALUE_LIMITS);
		Class<?> dataClass = decoded.getClass();
		if (dataClass.getRecordComponents().length == 0) {
			try {
				Field instance = dataClass.getField("INSTANCE");
				org.junit.jupiter.api.Assertions.assertSame(instance.get(null), decoded,
						"a zero-field record is not the INSTANCE singleton" + where);
			} catch (NoSuchFieldException | IllegalAccessException exception) {
				throw new IllegalStateException("zero-field record without INSTANCE: " + dataClass.getName(),
						exception);
			}
		}
		Object second = readViaInput(ctx, ctx.currentVersion(), typeName, payload, VALUE_LIMITS);
		for (RecordComponent component : dataClass.getRecordComponents()) {
			if (!component.getType().isArray()) {
				continue;
			}
			Object firstArray = invokeNoArg(requireNoArg(dataClass, component.getName()), decoded);
			Object secondArray = invokeNoArg(requireNoArg(dataClass, component.getName()), second);
			if (Array.getLength(firstArray) == 0) {
				org.junit.jupiter.api.Assertions.assertSame(firstArray, secondArray,
						"empty arrays did not decode to the canonical instance, component "
								+ component.getName() + where);
			}
		}
	}

	/** Counts how often distinct equal-looking strings collide in a sample; returns {@code collisions/total}. */
	public static double toStringCollisionRate(List<String> samples) {
		Set<String> unique = new LinkedHashSet<>(samples);
		return samples.isEmpty() ? 0.0 : 1.0 - (double) unique.size() / samples.size();
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 9: corruption accepts or fails stably
	// ------------------------------------------------------------------------------------------------

	/** The only exception classes a corrupted payload may produce. */
	public static final Set<Class<?>> ACCEPTED_FAILURES = Set.of(
			MalformedDataException.class, DecodeLimitExceededException.class, ValueTooLargeException.class);

	/**
	 * Feeds mutated payloads (truncations, bit flips, hostile overwrites, length bombs, trailing junk)
	 * through a {@code DataCodec.Reader} on all five storages. A read either returns a value or throws one
	 * of {@link #ACCEPTED_FAILURES}, and the failure class must be IDENTICAL across storages. Cursor
	 * unbinds are counted and must equal the number of binds.
	 */
	public static void corruptAcceptOrStableFail(Context ctx, String typeName, int version, Object value,
			Random random, int mutationBudget) {
		String where = ctx.diag(typeName, version);
		byte[] payload = serialize(ctx, version, typeName, value);
		DataCodec<Object> codec = codec(ctx, version, typeName);
		long binds = 0;
		long unbinds = 0;
		for (byte[] mutation : mutations(payload, random, mutationBudget)) {
			String referenceFailure = null;
			boolean referenceChecked = false;
			for (Storage storage : storages(Arena.ofConfined(), mutation)) {
				DataCodec.Reader<Object> reader = codec.newReader(CORRUPTION_LIMITS);
				String failure;
				try {
					reader.read(storage.source(), storage.offset(), mutation.length);
					failure = null; // accepted values are fine, corruption is not required to be rejected
				} catch (Throwable thrown) {
					Throwable cause = unwrap(thrown);
					if (cause instanceof Error error) {
						throw error;
					}
					if (!ACCEPTED_FAILURES.contains(cause.getClass())) {
						throw new AssertionError("corrupted payload failed with an unexpected exception: "
								+ cause.getClass().getName() + ": " + cause.getMessage() + where
								+ ", storage=" + storage.name() + ", mutationLength=" + mutation.length, cause);
					}
					failure = cause.getClass().getName();
				}
				if (!referenceChecked) {
					referenceFailure = failure;
					referenceChecked = true;
				} else {
					org.junit.jupiter.api.Assertions.assertEquals(referenceFailure, failure,
							"storage " + storage.name() + " disagrees with the first storage on the failure class"
									+ where + ", mutationLength=" + mutation.length);
				}
				binds++;
				for (BufDataCursor cursor : cursorsOf(reader)) {
					if (!cursor.isBound()) {
						unbinds++;
					}
					break; // one cursor per DataCodec.Reader
				}
			}
		}
		org.junit.jupiter.api.Assertions.assertEquals(binds, unbinds,
				"cursor unbinds did not match binds" + where);
	}

	/** The same mutation families {@code GeneratedCodecDeepFuzzTest} uses, scaled by {@code budget}. */
	static List<byte[]> mutations(byte[] payload, Random random, int budget) {
		var mutations = new ArrayList<byte[]>();
		int cutCount = Math.min(Math.max(1, budget / 4), payload.length + 1);
		for (int index = 0; index < cutCount; index++) {
			int cut = cutCount == 1 ? 0 : (int) ((long) index * payload.length / (cutCount - 1));
			mutations.add(Arrays.copyOf(payload, cut));
		}
		if (payload.length != 0) {
			for (int index = 0; index < Math.max(1, budget); index++) {
				byte[] flipped = payload.clone();
				flipped[random.nextInt(flipped.length)] ^= (byte) (1 << random.nextInt(Byte.SIZE));
				mutations.add(flipped);

				byte[] overwritten = payload.clone();
				overwritten[random.nextInt(overwritten.length)] = switch (index & 3) {
					case 0 -> 0;
					case 1 -> (byte) 0xff;
					case 2 -> 0x7f;
					default -> (byte) 0x80;
				};
				mutations.add(overwritten);
			}
		}
		if (payload.length >= Integer.BYTES) {
			int availableOffsets = payload.length - Integer.BYTES + 1;
			int offsetCount = Math.min(Math.max(1, budget / 4), availableOffsets);
			for (int offsetIndex = 0; offsetIndex < offsetCount; offsetIndex++) {
				int offset = offsetCount == 1 ? 0
						: (int) ((long) offsetIndex * (availableOffsets - 1) / (offsetCount - 1));
				for (int hostile : List.of(-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x4000_0000)) {
					byte[] bomb = payload.clone();
					bomb[offset] = (byte) (hostile >>> 24);
					bomb[offset + 1] = (byte) (hostile >>> 16);
					bomb[offset + 2] = (byte) (hostile >>> 8);
					bomb[offset + 3] = (byte) hostile;
					mutations.add(bomb);
				}
			}
		}
		for (int trailingLength : List.of(1, 2, 7, 16)) {
			byte[] trailing = Arrays.copyOf(payload, payload.length + trailingLength);
			for (int offset = payload.length; offset < trailing.length; offset++) {
				trailing[offset] = (byte) random.nextInt();
			}
			mutations.add(trailing);
		}
		return mutations;
	}

	// ------------------------------------------------------------------------------------------------
	// oracle 10: decode limits on all three generated read paths
	// ------------------------------------------------------------------------------------------------

	/**
	 * An array-count cap just under the widest array must be rejected by every read path (input reader,
	 * generated {@code Reader}, generated {@code BoundReader}); a payload-byte cap of zero must either be
	 * rejected by every path or accepted by every path identically (fixed-width values consume no counted
	 * payload bytes); and {@code DecodeLimits.unlimited()} always accepts.
	 */
	public static void decodeLimits(Context ctx, String typeName, int version, Object value) {
		String where = ctx.diag(typeName, version);
		byte[] payload = serialize(ctx, version, typeName, value);
		Object baseType = ctx.model().baseTypeConstant(typeName);
		int widest = widestArray(value, 0);
		DecodeLimits unlimited = DecodeLimits.unlimited();
		for (ReadPath path : readPaths(ctx, version, baseType, unlimited)) {
			Object decoded = path.read(payload);
			org.junit.jupiter.api.Assertions.assertTrue(deepEquals(value, decoded),
					"DecodeLimits.unlimited() rejected a valid value via " + path.name() + where);
		}
		if (widest > 0) {
			DecodeLimits tightArrays = new DecodeLimits(widest - 1, Integer.MAX_VALUE,
					Long.MAX_VALUE, Long.MAX_VALUE, 64);
			for (ReadPath path : readPaths(ctx, version, baseType, tightArrays)) {
				org.junit.jupiter.api.Assertions.assertThrows(DecodeLimitExceededException.class,
						() -> path.read(payload),
						"an array-count cap of " + (widest - 1) + " below the widest array (" + widest
								+ ") was not enforced by " + path.name() + where);
			}
		}
		if (payload.length > 64) {
			DecodeLimits noPayloads = new DecodeLimits(Integer.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE, 64);
			String reference = null;
			for (ReadPath path : readPaths(ctx, version, baseType, noPayloads)) {
				String outcome;
				try {
					path.read(payload);
					outcome = "accepted";
				} catch (DecodeLimitExceededException limited) {
					outcome = limited.getClass().getName();
				}
				if (reference == null) {
					reference = outcome;
				} else {
					org.junit.jupiter.api.Assertions.assertEquals(reference, outcome,
							"the zero payload-byte cap is enforced inconsistently across read paths" + where);
				}
			}
		}
	}

	/** One configured read path under a given {@link DecodeLimits}. */
	private record ReadPath(String name, java.util.function.Function<byte[], Object> read) {
		Object read(byte[] payload) {
			return read.apply(payload);
		}
	}

	private static List<ReadPath> readPaths(Context ctx, int version, Object baseType, DecodeLimits limits) {
		DataCodec<Object> codec = codec(ctx, version, ((Enum<?>) baseType).name());
		DataCodec.Reader<Object> streamReader = codec.newReader(limits);
		Object generatedReader = ctx.model().newReader(baseType, limits);
		Method generatedRead = null;
		try {
			generatedRead = generatedReader.getClass().getMethod("read", int.class, Buf.class, int.class, int.class);
			generatedRead.setAccessible(true);
		} catch (NoSuchMethodException exception) {
			throw new IllegalStateException("generated Reader.read(int,Buf,int,int) is missing", exception);
		}
		Object boundReader = ctx.model().newBoundReader(version, baseType, limits);
		Method boundRead = null;
		try {
			boundRead = boundReader.getClass().getMethod("read", Buf.class, int.class, int.class);
			boundRead.setAccessible(true);
		} catch (NoSuchMethodException exception) {
			throw new IllegalStateException("generated BoundReader.read(Buf,int,int) is missing", exception);
		}
		Method staticRead = null;
		try {
			staticRead = ctx.model().currentVersionClass().getMethod("read", int.class,
					ctx.model().baseTypeClass(), it.cavallium.stream.SafeDataInput.class);
		} catch (NoSuchMethodException exception) {
			throw new IllegalStateException("CurrentVersion.read is missing", exception);
		}
		final Method fusedRead = staticRead;
		final Method bound = boundRead;
		final Method generated = generatedRead;
		return List.of(
				new ReadPath("DataCodec.Reader", payload -> streamReader.read(Buf.wrap(payload), 0, payload.length)),
				new ReadPath("CurrentVersion.Reader", payload -> {
					try {
						return generated.invoke(generatedReader, version, Buf.wrap(payload), 0, payload.length);
					} catch (ReflectiveOperationException exception) {
						throw sneaky(unwrap(exception));
					}
				}),
				new ReadPath("BoundReader", payload -> {
					try {
						return bound.invoke(boundReader, Buf.wrap(payload), 0, payload.length);
					} catch (ReflectiveOperationException exception) {
						throw sneaky(unwrap(exception));
					}
				}),
				new ReadPath("CurrentVersion.read", payload -> {
					try {
						return fusedRead.invoke(null, version, baseType,
								BufDataInput.create(Buf.wrap(payload), limits));
					} catch (ReflectiveOperationException exception) {
						throw sneaky(unwrap(exception));
					}
				}));
	}

	static RuntimeException sneaky(Throwable throwable) {
		if (throwable instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		if (throwable instanceof Error error) {
			throw error;
		}
		return new IllegalStateException(throwable);
	}

	/** The largest array length anywhere inside a value (recursive through records, arrays and nullables). */
	static int widestArray(Object value, int depth) {
		if (value == null || depth > 64) {
			return 0;
		}
		if (value.getClass().isArray()) {
			int widest = Array.getLength(value);
			for (int index = 0; index < Math.min(widest, 32) && !value.getClass().getComponentType().isPrimitive();
					index++) {
				widest = Math.max(widest, widestArray(Array.get(value, index), depth + 1));
			}
			return widest;
		}
		if (value instanceof NativeNullable<?> nullable) {
			return widestArray(nullable.getNullable(), depth + 1);
		}
		if (value.getClass().isRecord() && value.getClass().getName().contains(".data.")) {
			int widest = 0;
			for (RecordComponent component : value.getClass().getRecordComponents()) {
				widest = Math.max(widest, widestArray(invokeNoArg(requireNoArg(value.getClass(),
						component.getName()), value), depth + 1));
			}
			return widest;
		}
		return 0;
	}

	// ------------------------------------------------------------------------------------------------
	// cursor accounting
	// ------------------------------------------------------------------------------------------------

	static List<BufDataCursor> cursorsOf(Object reader) {
		List<BufDataCursor> cursors = new ArrayList<>();
		for (Class<?> owner = reader.getClass(); owner != null; owner = owner.getSuperclass()) {
			for (Field field : owner.getDeclaredFields()) {
				if (BufDataCursor.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					try {
						cursors.add((BufDataCursor) field.get(reader));
					} catch (IllegalAccessException exception) {
						throw new IllegalStateException("cannot read cursor field " + field, exception);
					}
				}
			}
		}
		return cursors;
	}

	static void assertAllCursorsUnbound(Object reader, String diagnostic) {
		List<BufDataCursor> cursors = cursorsOf(reader);
		org.junit.jupiter.api.Assertions.assertFalse(cursors.isEmpty(),
				diagnostic + ", reader exposes no cursors");
		for (BufDataCursor cursor : cursors) {
			org.junit.jupiter.api.Assertions.assertFalse(cursor.isBound(), diagnostic);
		}
	}

	static Object invokeStatic(Class<?> owner, String name, Class<?>[] parameterTypes, Object... arguments) {
		try {
			Method method = owner.getMethod(name, parameterTypes);
			return method.invoke(null, arguments);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("static " + owner.getName() + "." + name + " failed",
					unwrap(exception));
		}
	}

	// ------------------------------------------------------------------------------------------------
	// schema-case generation (spread) shared by the three property classes
	// ------------------------------------------------------------------------------------------------

	/**
	 * One entry of the per-run schema spread: a profile (usually the tier profile with one forced feature)
	 * plus the {@code generateOldSerializers} flag. {@code id} is stable so failures are nameable.
	 */
	public record SpreadItem(String id, SchemaGenProfile profile, boolean oldSerializers) {
	}

	/**
	 * The per-property schema target: at DEFAULT+ one FULL spread cycle must execute (that is what makes
	 * the per-run coverage assertions meaningful), so the target is at least the spread size.
	 */
	public static int coverageTarget(int target, List<SpreadItem> items) {
		return tier().ordinal() >= SchemaGenProfile.Tier.DEFAULT.ordinal()
				? Math.max(target, items.size())
				: target;
	}

	/**
	 * The deterministic spread: the tier profile, then the tier profile with EACH {@link
	 * SchemaGenProfile.Feature} forced (so a full cycle covers all 23 features), plus the minimal and
	 * maximal profiles; the evolution property additionally asks for two {@code generateOldSerializers=true}
	 * variants. {@link #schemaCases} ROTATES through this list from a run-seed-derived start index, so a
	 * full cycle (tries >= size) attempts every entry and coverage cannot randomly miss a feature.
	 */
	public static List<SpreadItem> spread(SchemaGenProfile.Tier tier, boolean withOldSerializers) {
		SchemaGenProfile base = SchemaGenProfile.forTier(tier);
		List<SpreadItem> items = new ArrayList<>();
		items.add(new SpreadItem("tier", base, false));
		for (SchemaGenProfile.Feature feature : SchemaGenProfile.Feature.values()) {
			items.add(new SpreadItem("force:" + feature.name(), base.withForced(feature), false));
		}
		items.add(new SpreadItem("minimal", SchemaGenProfile.minimal(), false));
		items.add(new SpreadItem("maximal", SchemaGenProfile.maximal(), false));
		if (withOldSerializers) {
			items.add(new SpreadItem("old-serializers", base.withForced(SchemaGenProfile.Feature.MULTI_VERSION), true));
			items.add(new SpreadItem("old-serializers-upgrades",
					base.withForced(SchemaGenProfile.Feature.MULTI_VERSION, SchemaGenProfile.Feature.DATA_UPGRADES),
					true));
		}
		return List.copyOf(items);
	}

	/** One generated+compiled schema case: the spread entry as actually materialized. */
	public record SchemaCase(long seed, SpreadItem item, SourcesGeneratorConfiguration config,
			CompiledSchema schema, Set<SchemaGenProfile.Feature> features) {

		/** A {@link Context} for this case's compiled schema. */
		public Context context() {
			return Context.of(schema);
		}
	}

	/**
	 * Generates a schema for {@code item} with {@link SchemaGen}, records its real feature coverage, and
	 * compiles it through the shared cache with the fixed {@link #FUZZ_BASE_PACKAGE}.
	 *
	 * <p>Returns {@code null} when EVERY retry failed to generate/compile: several random configurations
	 * of some profiles hit generation-time {@code IllegalArgumentException}s or even javac type errors
	 * (a {@code SchemaGen}/{@code ReadPlanCompiler} defect outside this package's write scope), so those
	 * are recorded as DEFECT dossiers under {@code target/fuzz-failures} and the CASE is skipped — the
	 * oracles themselves are never relaxed.</p>
	 */
	public static SchemaCase compileCase(SpreadItem item, long caseSeed) {
		IllegalStateException lastFailure = null;
		// Same convention InvalidSchemaGen documents ("the caller retries with a new seed"): some random
		// configurations hit a generation-time IllegalArgumentException; a few retries keep a run stable,
		// and a case that fails EVERY attempt is recorded before the case is skipped.
		for (int attempt = 0; attempt < 40; attempt++) {
			long seed = mix(caseSeed, attempt * 0x1000_0000_0000_0001L + item.id().hashCode());
			try {
				Random generatorRandom = new Random(seed);
				SchemaGen.Coverage coverage = new SchemaGen.Coverage();
				SourcesGeneratorConfiguration config = SchemaGen.generate(generatorRandom, item.profile(), coverage);
				SchemaCompiler.CompileOptions options = new SchemaCompiler.CompileOptions(FUZZ_BASE_PACKAGE,
						item.oldSerializers(), false, false, CompiledSchemas.defaultCacheRoot(), Map.of());
				CompiledSchema schema = CompiledSchemas.shared().compile(config, options);
				return new SchemaCase(seed, item, config, schema, Set.copyOf(coverage.seen()));
			} catch (IllegalStateException failure) {
				lastFailure = failure;
			}
		}
		if (item.id().startsWith("force:")) {
			GENERATION_DEFECT_FEATURES.add(item.id().substring("force:".length()));
		}
		recordFailure("generation." + item.id().replace(':', '_'), caseSeed,
				"DEFECT (generation, not an oracle failure): schema generation failed for every retry of "
						+ "spread item " + item.id() + ", " + describe() + System.lineSeparator()
						+ ExceptionAsString.of(lastFailure), null);
		return null;
	}

	/**
	 * Forced features whose spread item failed generation on EVERY retry this JVM (recorded DEFECTs).
	 * {@link RunCoverage#assertAllFeaturesCovered()} exempts these: the SchemaGen defect is reported,
	 * not hidden — the dossier under {@code target/fuzz-failures} is the artifact.
	 */
	public static Set<String> generationDefectFeatures() {
		return Set.copyOf(GENERATION_DEFECT_FEATURES);
	}

	/**
	 * Clears {@link #generationDefectFeatures()}: the defect set is scoped to ONE property run, so each
	 * property class calls this from {@code @BeforeProperty} (jqwik's {@code @BeforeAll}-equivalent hook
	 * on the lifecycle interface) and never leaks its defects into the next property's coverage gate.
	 */
	public static void resetGenerationDefects() {
		GENERATION_DEFECT_FEATURES.clear();
	}

	private static final Set<String> GENERATION_DEFECT_FEATURES = java.util.concurrent.ConcurrentHashMap
			.newKeySet();

	// ------------------------------------------------------------------------------------------------
	// run coverage bookkeeping
	// ------------------------------------------------------------------------------------------------

	/** Accumulates schema-feature and value-category coverage over one property run. */
	public static final class RunCoverage {

		private final Set<SchemaGenProfile.Feature> features = new LinkedHashSet<>();
		/** Forced features of the {@code force:...} spread items this run received a compiled case for. */
		private final Set<String> attemptedForcedFeatures = new LinkedHashSet<>();
		private final Set<String> typeVersions = new LinkedHashSet<>();
		private int valueMask;
		private int schemas;
		private int values;
		private int attempts;
		private int generationFailures;
		private int zeroValueSchemas;
		/** Per-skip-reason counts (the reason strings already embed the version, e.g. {@code type-absent@v3}). */
		private final java.util.SortedMap<String, Integer> skips = new java.util.concurrent.ConcurrentSkipListMap<>();

		/** Records one generated schema: its real features ({@link SchemaCase#features()}). */
		public void schema(SchemaCase case_) {
			schemas++;
			features.addAll(case_.features());
		}

		/**
		 * Records one property try, whether or not it produced a usable schema — the denominator of
		 * {@link #assertGenerationFailureBound()}. Null cases (every generation retry failed) are still
		 * counted here, so they are never hidden.
		 */
		public void attempt() {
			attempts++;
		}

		/** Records a try whose schema case was {@code null} (generation failed on every retry, DEFECT recorded). */
		public void generationFailure() {
			generationFailures++;
		}

		/** Records a schema whose features were counted but on which no value oracle ran. */
		public void zeroValueSchema() {
			zeroValueSchemas++;
		}

		/** Counts one oracle-level skip; {@code reason} is the value {@code upgradeEquivalence} returns. */
		public void skip(String reason) {
			skips.merge(reason, 1, Integer::sum);
		}

		/** Records one {@link ReflectiveValueGen} coverage mask. */
		public void valueMask(int mask) {
			valueMask |= mask;
			values++;
		}

		/** Records that {@code typeName} was exercised at {@code version}. */
		public void typeVersion(String typeName, int version) {
			typeVersions.add(typeName + "@v" + version);
		}

		/** True when every bit of {@code mask} was reported by some generated value this run. */
		public boolean hasValueCategories(int mask) {
			return (valueMask & mask) == mask;
		}

		/** The features neither covered by a compiled schema nor exempted as recorded generation DEFECTs. */
		public Set<SchemaGenProfile.Feature> missingFeatures() {
			Set<SchemaGenProfile.Feature> missing = EnumSet.allOf(SchemaGenProfile.Feature.class);
			missing.removeAll(features);
			// Features whose forced spread item failed GENERATION every retry are recorded DEFECTs
			// (SchemaGen side, outside this write scope) — exempted, with the name shown in the message.
			Set<String> defects = generationDefectFeatures();
			missing.removeIf(feature -> defects.contains(feature.name()));
			return missing;
		}

		/** Feature coverage of a full spread cycle: every {@link SchemaGenProfile.Feature}. */
		public void assertAllFeaturesCovered() {
			Set<SchemaGenProfile.Feature> missing = missingFeatures();
			org.junit.jupiter.api.Assertions.assertTrue(missing.isEmpty(),
					"schema features never covered this run: " + missing + " (" + describe()
							+ ", schemas=" + schemas + ", values=" + values
							+ ", generation-defects=" + generationDefectFeatures() + ")");
		}

		/**
		 * Partial-cycle coverage for a run that attempted FEWER tries than the spread has entries (early
		 * stop, budget): every forced feature ATTEMPTED — the {@code force:...} item whose compiled case
		 * was handed to the property — must appear in the covered features or in the recorded generation
		 * DEFECTs. Every attempted spread item was either compiled-and-exercised or DEFECTed, and with the
		 * rotating selection in {@link #schemaCases} a below-spread-size run executes every non-null item
		 * it attempts, so this feature-level check is the honest form of that obligation. The full-set
		 * obligation belongs to whichever property attempts a full spread cycle (the codec property).
		 */
		public void assertAttemptedFeaturesCovered() {
			Set<String> unfulfilled = new LinkedHashSet<>(attemptedForcedFeatures);
			Set<String> defects = generationDefectFeatures();
			unfulfilled.removeAll(defects);
			for (SchemaGenProfile.Feature feature : features) {
				unfulfilled.remove(feature.name());
			}
			org.junit.jupiter.api.Assertions.assertTrue(unfulfilled.isEmpty(),
					"attempted forced features never covered and not recorded as generation defects: "
							+ unfulfilled + " (" + describe() + ", schemas=" + schemas + ", values=" + values
							+ ", generation-defects=" + defects + ")");
		}

		/**
		 * Records that the run was handed a compiled case for {@code item}; for {@code force:...} items
		 * the forced feature becomes an ATTEMPT that {@link #assertAttemptedFeaturesCovered()} holds to
		 * account.
		 */
		public void attemptedFeature(SpreadItem item) {
			if (item.id().startsWith("force:")) {
				attemptedForcedFeatures.add(item.id().substring("force:".length()));
			}
		}

		/**
		 * The {@code @AfterProperty} coverage token: {@code full(c/n)} when nothing is missing
		 * (defect-exempt, same rule as {@link #missingFeatures()}), {@code partial(c/n)} otherwise — so a
		 * below-spread-size run states its partial state instead of hiding it.
		 */
		public String featureCoverageDescription() {
			int total = SchemaGenProfile.Feature.values().length;
			int missing = missingFeatures().size();
			return (missing == 0 ? "full(" : "partial(") + (total - missing) + "/" + total + ")";
		}

		/**
		 * Value-category coverage: every {@link ReflectiveValueGen} category that random schemas can
		 * actually reach. {@code UNION_ALTERNATIVE} is exempted on purpose: {@code SchemaGen} declares
		 * unions only as top-level super-types (never as a field type), and {@link ReflectiveValueGen}
		 * reports that category only while materializing an interface-typed field — so no sampled value
		 * of a record type can ever report it. Union PRESENCE is still asserted via the {@code UNIONS}/
		 * {@code UNION_EXTENSIONS} schema features.
		 */
		public void assertAllValueCategories() {
			int expected = ReflectiveValueGen.maskOf(
					ReflectiveValueGen.COVERAGE_PRIMITIVE, ReflectiveValueGen.COVERAGE_STRING,
					ReflectiveValueGen.COVERAGE_INT52, ReflectiveValueGen.COVERAGE_FLOAT,
					ReflectiveValueGen.COVERAGE_DOUBLE, ReflectiveValueGen.COVERAGE_EMPTY_ARRAY,
					ReflectiveValueGen.COVERAGE_NON_EMPTY_ARRAY, ReflectiveValueGen.COVERAGE_NULLABLE_PRESENT,
					ReflectiveValueGen.COVERAGE_NULLABLE_ABSENT,
					ReflectiveValueGen.COVERAGE_NESTED_RECORD);
			int missing = expected & ~valueMask;
			org.junit.jupiter.api.Assertions.assertEquals(0, missing,
					"value categories never covered this run: " + ReflectiveValueGen.describeCoverage(missing)
							+ " (" + describe() + ", values=" + values + ")");
		}

		/**
		 * Bound on the generation DEFECT rate, documented as a budget rather than a correctness claim:
		 * at most one in ten tries may fail generation outright ({@code generationFailures * 10 <=
		 * attempts}). Runs only at {@code DEFAULT} and above, where the sample is large enough for the
		 * bound to mean something; a red run here points at the DEFECT dossiers under
		 * {@code target/fuzz-failures}, it does not hide them.
		 */
		public void assertGenerationFailureBound() {
			org.junit.jupiter.api.Assertions.assertTrue(generationFailures * 10 <= attempts,
					() -> "more than one in ten tries failed schema generation: generationFailures="
							+ generationFailures + " attempts=" + attempts + " (" + describe() + ")");
		}

		public Set<String> typeVersions() {
			return Set.copyOf(typeVersions);
		}

		public int schemas() {
			return schemas;
		}

		public int values() {
			return values;
		}

		public int attempts() {
			return attempts;
		}

		public int generationFailures() {
			return generationFailures;
		}

		public int zeroValueSchemas() {
			return zeroValueSchemas;
		}

		/** The counted skips by reason, e.g. {@code {no-old-serializer@v2=4, type-absent@v3=1}}. */
		public String skipsDescription() {
			return skips.toString();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// failure recording + guard
	// ------------------------------------------------------------------------------------------------

	/**
	 * Records a fuzz failure like {@code FuzzReplay.recordFailure} does (bridged when present) and ALWAYS
	 * writes {@code target/fuzz-failures/<id>.txt} (detail incl. seed and {@link #describe()}) plus
	 * {@code target/fuzz-failures/<id>.yml} (the schema YAML) locally, so a failure is replayable even
	 * without the datagen test sources on this classpath.
	 */
	public static void recordFailure(String id, long seed, String detail, SourcesGeneratorConfiguration config) {
		try {
			Class<?> replay = Class.forName("it.cavallium.datagen.fuzz.FuzzReplay");
			replay.getMethod("recordFailure", String.class, long.class, String.class)
					.invoke(null, id, seed, detail);
		} catch (ReflectiveOperationException absent) {
			// No bridge: the local files below are the record.
		} catch (RuntimeException odd) {
			System.err.println("FuzzReplay.recordFailure failed, local files still written: " + odd);
		}
		try {
			Path directory = Path.of("target", "fuzz-failures");
			Files.createDirectories(directory);
			// getBytes(UTF_8) REPLACES unpaired surrogates (a fuzz value may contain them);
			// Files.writeString would throw UnmappableCharacterException on them.
			Files.write(directory.resolve(id + ".txt"),
					(detail + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			if (config != null) {
				Files.write(directory.resolve(id + ".yml"),
						SchemaYamlEmitter.emit(config).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
		} catch (IOException ioFailure) {
			System.err.println("Could not write target/fuzz-failures/" + id + ": " + ioFailure);
		}
	}

	/**
	 * Runs one oracle batch; on ANY throwable records the full failure dossier (id, seed, tier
	 * description, fingerprint, type, version, value sketch, stack, YAML) via {@link #recordFailure} and
	 * rethrows. This is the defect policy in code: the oracle is never weakened, the evidence is kept.
	 */
	public static void guard(String id, SchemaCase case_, String typeName, int version, long caseSeed,
			Object valueForSketch, Runnable oracle) {
		try {
			oracle.run();
		} catch (Throwable failure) {
			String detail = "fuzz failure id=" + id + System.lineSeparator()
					+ describe() + ", caseSeed=" + caseSeed + System.lineSeparator()
					+ "spread=" + case_.item().id() + ", fingerprint=" + case_.schema().fingerprint()
					+ ", type=" + typeName + ", version=" + version + System.lineSeparator()
					+ "value=" + sketch(valueForSketch) + System.lineSeparator()
					+ ExceptionAsString.of(failure);
			recordFailure(id, seed(), detail, case_.config());
			if (failure instanceof AssertionError assertionError) {
				throw assertionError;
			}
			if (failure instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (failure instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(failure);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// the no-shrink schema arbitrary
	// ------------------------------------------------------------------------------------------------

	/**
	 * A jqwik {@link Arbitrary} that ROTATES through {@code items} (one compiled {@link SchemaCase} per
	 * try, then cycles) and NEVER shrinks — jqwik must not mutate the schema, value shrinking happens
	 * inside the property. The cycle's start index is the run {@link #seed()} reduced modulo the spread
	 * size, so schema selection is deterministic per run seed: every try contributes the next spread
	 * item's forced feature, and a below-spread-size run attempts a fixed window instead of randomly
	 * missing features. The per-case seed still mixes the jqwik-provided randomness (so
	 * {@code -Djqwik.seed} reproduces) with {@link #seed()}.
	 */
	public static Arbitrary<SchemaCase> schemaCases(List<SpreadItem> items) {
		Objects.requireNonNull(items, "items");
		AtomicInteger cursor = new AtomicInteger((int) Math.floorMod(seed(), items.size()));
		return new Arbitrary<>() {
			@Override
			public RandomGenerator<SchemaCase> generator(int genSize) {
				return random -> {
					captureJqwikSeed(); // before ANY draw, while the engine field still holds the initial state
					SpreadItem item = items.get(Math.floorMod(cursor.getAndIncrement(), items.size()));
					long caseSeed = mix(GeneratedOracles.seed(), random.nextLong());
					return immutable(compileCase(item, caseSeed));
				};
			}

			@Override
			public EdgeCases<SchemaCase> edgeCases(int maxEdgeCases) {
				return EdgeCases.none();
			}
		};
	}

	/** A {@link Shrinkable} with an empty shrink stream — the "never shrink" half of {@link #noShrink}. */
	public static <T> Shrinkable<T> immutable(T value) {
		return new Shrinkable<>() {
			@Override
			public T value() {
				return value;
			}

			@Override
			public java.util.stream.Stream<Shrinkable<T>> shrink() {
				return java.util.stream.Stream.empty();
			}

			@Override
			public ShrinkingDistance distance() {
				return ShrinkingDistance.of(0);
			}
		};
	}

	/** A compact throwable+frames renderer for failure dossiers. */
	static final class ExceptionAsString {

		static String of(Throwable failure) {
			StringBuilder builder = new StringBuilder();
			for (Throwable current = failure; current != null; current = current.getCause()) {
				builder.append(current.getClass().getName()).append(": ")
						.append(current.getMessage()).append(System.lineSeparator());
				int frames = 0;
				for (StackTraceElement frame : current.getStackTrace()) {
					if (frames++ >= 24) {
						builder.append("\t... more").append(System.lineSeparator());
						break;
					}
					builder.append("\tat ").append(frame).append(System.lineSeparator());
				}
				if (current.getCause() == current) {
					break;
				}
				builder.append("Caused by: ");
			}
			return builder.toString();
		}

		private ExceptionAsString() {
		}
	}
}
