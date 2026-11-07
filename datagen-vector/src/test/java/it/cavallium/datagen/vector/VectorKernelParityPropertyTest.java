package it.cavallium.datagen.vector;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.buffer.VectorFallbackBuf;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.nativedata.ArrayInt52Serializer;
import it.cavallium.datagen.nativedata.ArraybooleanSerializer;
import it.cavallium.datagen.nativedata.ArraybyteSerializer;
import it.cavallium.datagen.nativedata.ArraycharSerializer;
import it.cavallium.datagen.nativedata.ArraydoubleSerializer;
import it.cavallium.datagen.nativedata.ArrayfloatSerializer;
import it.cavallium.datagen.nativedata.ArrayintSerializer;
import it.cavallium.datagen.nativedata.ArraylongSerializer;
import it.cavallium.datagen.nativedata.ArrayshortSerializer;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.AssertionFailedError;

/**
 * Scalar-vs-vector kernel parity fuzz for {@link VectorArraySupport}.
 *
 * <p>The oracle is parity, not performance. Every case builds one hostile big-endian wire payload, then reads the
 * same bytes through three paths and requires identical values:</p>
 * <ol>
 *     <li>an independent big-endian scalar decoder inside this test (the reference oracle);</li>
 *     <li>{@code VectorArraySupport.readXxxArray(...)} on a direct cursor, i.e. the incubator kernel whenever the
 *     published crossover says it must run, and the runtime fallback when it must not;</li>
 *     <li>the stable runtime scalar codec ({@code Array*Serializer#read}) plus {@code VectorArraySupport.readXxxArray}
 *     on a forward-only {@link SafeDataInputStream}, which is that same API's documented scalar branch
 *     ({@code !(input instanceof RandomAccessDataInput)}).</li>
 * </ol>
 *
 * <p>Sizes deliberately straddle every published crossover constant ({@code 0}, {@code 1}, {@code crossover-1},
 * {@code crossover}, {@code crossover+1}, {@code crossover*2}, the upper crossovers, {@code FuzzValues.SIZE_LADDER}
 * values and seeded random sizes above the largest crossover), plus the vector lane boundaries, so the scalar
 * <em>tail</em> of a partially-filled vector is always covered — that tail is where these kernels break.</p>
 *
 * <p>Which path actually ran is observable through {@link Probe}: the scalar branch always goes through
 * {@code RandomAccessDataInput#readXxxArray(int)} while the vector branch never does (it reads the direct heap array
 * or memory segment and finishes the remainder with the absolute {@code getXxxAt} getters). Floats and doubles are
 * compared bit-exactly through {@code floatToRawIntBits}/{@code doubleToRawLongBits}.</p>
 *
 * <p>{@code FuzzTiers}/{@code FuzzValues} live in {@code datagen}'s test sources and are not on this module's test
 * classpath, so they are reached through the same reflective bridge with the same documented fallback that
 * {@code datagen-plugin} uses; see {@link FuzzBridge}.</p>
 */
class VectorKernelParityPropertyTest {

	/** Bodies larger than this are only fuzzed at the {@code DEEP} tier. */
	private static final int DEEP_BODY_BYTES = 64 * 1024;

	@TestFactory
	List<DynamicTest> crossoverAndTailSizesMatchScalarReference() {
		return cases(false);
	}

	@TestFactory
	List<DynamicTest> largeSizesMatchScalarReferenceInDeepTier() {
		return cases(true);
	}

	private static List<DynamicTest> cases(boolean deep) {
		List<DynamicTest> tests = new ArrayList<>();
		for (Kind kind : Kind.values()) {
			int[] sizes = sizes(kind, deep);
			for (Storage storage : Storage.values()) {
				tests.add(DynamicTest.dynamicTest(
						kind.id + "/" + storage.id + (deep ? "/deep" : "/default") + "[" + sizes.length + " sizes]",
						() -> assertKind(kind, storage, sizes, deep)));
			}
		}
		return tests;
	}

	private static void assertKind(Kind kind, Storage storage, int[] sizes, boolean deep) throws IOException {
		if (sizes.length == 0) return;
		if (deep) FuzzBridge.requireMinimumTier("DEEP");
		long seed = FuzzBridge.seed();
		try (Arena arena = Arena.ofConfined()) {
			assertSizes(kind, storage, sizes, seed, arena);
		}
	}

	private static void assertSizes(Kind kind, Storage storage, int[] sizes, long seed, Arena arena) throws IOException {
		for (int size : sizes) {
			byte[] body = kind.hostileBody(FuzzBridge.random("body:" + kind.id + ":" + storage.id + ":" + size), size);
			byte[] payload = payload(size, body);
			long[] expected = kind.oracle.decode(body, size);
			String context = FuzzBridge.describe() + " seed=" + seed + " kind=" + kind.id + " storage=" + storage.id
					+ " size=" + size;

			// 1) The module entry point on a direct cursor, with the path probe attached.
			Probe probe = new Probe();
			Object kernel = readBound(kind, storage, payload, arena, probe);
			assertFingerprint(context + " path=vector-or-fallback", kind, expected, body, size,
					kind.fingerprint.of(kernel));
			if (size == 0) {
				Assertions.assertSame(kind.empty.get(), kernel, context + " empty array is not canonical");
				Assertions.assertEquals(0, probe.probed, context + " size 0 must short-circuit before probing storage");
			} else {
				assertObservedShape(context, storage, probe);
				assertPathRan(context, kind, size, probe);
			}

			// 2) Stable runtime scalar codec on the identical bytes (the scalar reference the kernel falls back to).
			Object scalarCodec = readForwardOnly(kind.scalarCodec, payload);
			assertFingerprint(context + " path=runtime-scalar-codec", kind, expected, body, size,
					kind.fingerprint.of(scalarCodec));

			// 3) The same module API on a non-random-access input: its documented scalar branch.
			Object scalarBranch = readForwardOnly(kind.kernelRead, payload);
			assertFingerprint(context + " path=module-scalar-branch", kind, expected, body, size,
					kind.fingerprint.of(scalarBranch));
		}
	}

	// --------------------------------------------------------------------------------------------- path oracle

	/**
	 * Asserts that the path the published crossover constants promise is the path that actually ran.
	 *
	 * <p>The vector branch never calls {@code readXxxArray(int)}; the scalar branch always does (except Int52, whose
	 * random-access branch decodes element-wise either way, so its scalar shape is detected by the element getter
	 * being called once per element). The vector branch additionally resolves the direct storage exactly once and
	 * finishes only {@code size - loopBound(size)} elements through the absolute getters.</p>
	 */
	private static void assertPathRan(String context, Kind kind, int size, Probe probe) {
		boolean direct = probe.heap != null || probe.segment != null;
		boolean vector = kind.hasVectorKernel && direct
				&& size >= kind.threshold(probe) && size <= kind.maxLength(probe);
		if (vector) {
			Assertions.assertFalse(probe.bulk[kind.ordinal()],
					context + " expected the VECTOR kernel but readByteArray-style scalar bulk path was used");
			Assertions.assertEquals(1, probe.storageOffsetCalls,
					context + " vector kernel must resolve direct storage exactly once");
			int expectedTail = size - kind.loopBound.of(size);
			Assertions.assertEquals(expectedTail, probe.tail[kind.ordinal()],
					context + " vector kernel must leave exactly size-loopBound(size) elements to the scalar tail");
			if (expectedTail > 0) {
				Assertions.assertTrue(kind.loopBound.of(size) > 0,
						context + " vector loop made no progress while a tail was reported");
			}
		} else if (kind == Kind.BYTE) {
			Assertions.assertTrue(probe.bulk[kind.ordinal()],
					context + " byte arrays must use the runtime bulk-copy scalar path");
		} else if (kind == Kind.INT52) {
			Assertions.assertEquals(size, probe.tail[kind.ordinal()],
					context + " expected the SCALAR Int52 loop: every element must come from getInt52At");
		} else {
			Assertions.assertTrue(probe.bulk[kind.ordinal()],
					context + " expected the SCALAR fallback (read" + kind.arrayMethod + ") but it was not used");
			Assertions.assertEquals(0, probe.tail[kind.ordinal()],
					context + " scalar fallback must not also use the absolute element getters");
			Assertions.assertEquals(0, probe.storageOffsetCalls,
					context + " scalar fallback must not resolve direct storage");
		}
	}

	private static void assertObservedShape(String context, Storage storage, Probe probe) {
		switch (storage) {
			case HEAP, HEAP_OFFSET, HEAP_SUBLIST -> {
				if (probe.probed > 0) {
					Assertions.assertNotNull(probe.heap, context + " expected direct heap storage");
					Assertions.assertNull(probe.segment, context + " heap storage also reported a memory segment");
				}
			}
			case NATIVE_SEGMENT -> {
				if (probe.probed > 0) {
					Assertions.assertNotNull(probe.segment, context + " expected direct memory-segment storage");
					Assertions.assertNull(probe.heap, context + " native storage also reported a heap array");
				}
			}
			case FALLBACK -> Assertions.assertNull(probe.heap, context + " fallback storage exposed a heap array");
		}
		if (storage == Storage.FALLBACK && probe.probed > 0) {
			Assertions.assertNull(probe.segment, context + " fallback storage exposed a memory segment");
		}
	}

	// --------------------------------------------------------------------------------------------- sizes

	/**
	 * Sizes that straddle every published crossover for this kind, the lane boundaries (so the scalar tail of a
	 * partially-filled vector is covered), the shared ladder and seeded random sizes above the largest crossover.
	 */
	private static int[] sizes(Kind kind, boolean deep) {
		TreeSet<Integer> small = new TreeSet<>();
		TreeSet<Integer> large = new TreeSet<>();
		Random rnd = FuzzBridge.random("sizes:" + kind.id);
		add(small, large, kind, 0);
		add(small, large, kind, 1);
		for (int crossover : kind.crossovers()) {
			add(small, large, kind, crossover - 1);
			add(small, large, kind, crossover);
			add(small, large, kind, crossover + 1);
			add(small, large, kind, crossover * 2);
		}
		int lanes = kind.lanes();
		for (int multiple = 1; multiple <= 3; multiple++) {
			add(small, large, kind, multiple * lanes);
			add(small, large, kind, multiple * lanes + 1);
			add(small, large, kind, multiple * lanes - 1);
		}
		for (int ladder : FuzzBridge.sizeLadder()) {
			add(small, large, kind, ladder);
		}
		int largest = kind.largestCrossover();
		for (int i = 0; i < 3; i++) {
			add(small, large, kind, largest + 2 + rnd.nextInt(1024));
		}
		if (deep) {
			add(small, large, kind, largest + 2 + rnd.nextInt(4096));
			add(small, large, kind, DEEP_BODY_BYTES / kind.elementBytes + 1 + rnd.nextInt(64));
		}
		TreeSet<Integer> selected = deep ? large : small;
		int[] result = new int[selected.size()];
		int index = 0;
		for (int size : selected) result[index++] = size;
		return result;
	}

	private static void add(TreeSet<Integer> small, TreeSet<Integer> large, Kind kind, int size) {
		if (size < 0) return;
		(size * (long) kind.elementBytes > DEEP_BODY_BYTES ? large : small).add(size);
	}

	// --------------------------------------------------------------------------------------------- payloads

	private static byte[] payload(int size, byte[] body) {
		ByteBuffer buffer = ByteBuffer.allocate(4 + body.length);
		buffer.putInt(size);
		buffer.put(body);
		return buffer.array();
	}

	private static Object readForwardOnly(Read read, byte[] payload) throws IOException {
		SafeDataInputStream input = new SafeDataInputStream(new SafeByteArrayInputStream(payload),
				DecodeLimits.unlimited());
		try {
			Object value = read.apply(input);
			Assertions.assertEquals(-1, input.read(), "scalar reference left trailing bytes unread");
			return value;
		} finally {
			input.close();
		}
	}

	private static Object readBound(Kind kind, Storage storage, byte[] payload, Arena arena, Probe probe)
			{
		BufSource bound = storage.bind(payload, arena);
		BufDataCursor cursor = new BufDataCursor(DecodeLimits.unlimited());
		probe.delegate = cursor;
		cursor.bind(bound.buf(), bound.offset(), payload.length);
		try {
			Object value = kind.kernelRead.apply(probe);
			Assertions.assertEquals(0, cursor.remaining(), "kernel left trailing bytes unread");
			return value;
		} finally {
			cursor.unbind();
		}
	}

	// --------------------------------------------------------------------------------------------- comparison

	private static void assertFingerprint(String context,
			Kind kind,
			long[] expected,
			byte[] body,
			int size,
			long[] actual) {
		if (expected.length != actual.length) {
			throw new AssertionFailedError(context + " :: length mismatch expected=" + expected.length
					+ " actual=" + actual.length);
		}
		for (int i = 0; i < expected.length; i++) {
			if (expected[i] != actual[i]) {
				throw new AssertionFailedError(context + " :: first mismatch index=" + i
						+ " expected=" + hex(expected[i]) + " actual=" + hex(actual[i])
						+ " wireBytes=" + wireBytes(kind, body, i)
						+ " (expected is the independent big-endian scalar decode of the same payload)");
			}
		}
	}

	private static String wireBytes(Kind kind, byte[] body, int index) {
		int from = index * kind.elementBytes;
		if (from + kind.elementBytes > body.length) return "<out of range>";
		StringBuilder builder = new StringBuilder(kind.elementBytes * 3);
		for (int i = 0; i < kind.elementBytes; i++) {
			if (i > 0) builder.append(' ');
			builder.append(String.format("%02x", body[from + i]));
		}
		return builder.toString();
	}

	private static String hex(long value) {
		return "0x" + Long.toHexString(value) + " (" + value + ")";
	}

	// --------------------------------------------------------------------------------------------- kinds

	@FunctionalInterface
	private interface Read {
		Object apply(SafeDataInput input);
	}

	@FunctionalInterface
	private interface BodyOracle {
		long[] decode(byte[] body, int size);
	}

	@FunctionalInterface
	private interface Fingerprint {
		long[] of(Object array);
	}

	@FunctionalInterface
	private interface LoopBound {
		int of(int size);
	}

	/** Every primitive-array kind {@link VectorArraySupport} exposes, with its published crossovers. */
	private enum Kind {
		BOOLEAN("boolean", 1, true,
				VectorArraySupport.BOOLEAN_HEAP_THRESHOLD, Integer.MAX_VALUE,
				VectorArraySupport.BOOLEAN_SEGMENT_THRESHOLD, Integer.MAX_VALUE,
				"BooleanArray", ByteVector.SPECIES_PREFERRED::length, ByteVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 1, (bytes, at) -> bytes[at] != 0 ? 1L : 0L),
				(array) -> bits((boolean[]) array),
				VectorArraySupport::readBooleanArray, in -> new ArraybooleanSerializer().read(in),
				ArraybooleanSerializer::emptyArray),
		BYTE("byte", 1, false,
				0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE,
				"ByteArray", ByteVector.SPECIES_PREFERRED::length, ByteVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 1, (bytes, at) -> bytes[at]),
				(array) -> bits((byte[]) array),
				VectorArraySupport::readByteArray, in -> new ArraybyteSerializer().read(in),
				ArraybyteSerializer::emptyArray),
		SHORT("short", 2, true,
				VectorArraySupport.SHORT_HEAP_THRESHOLD, Integer.MAX_VALUE,
				VectorArraySupport.SHORT_SEGMENT_THRESHOLD, Integer.MAX_VALUE,
				"ShortArray", ShortVector.SPECIES_PREFERRED::length, ShortVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 2, (bytes, at) -> bigEndianShort(bytes, at)),
				(array) -> bits((short[]) array),
				VectorArraySupport::readShortArray, in -> new ArrayshortSerializer().read(in),
				ArrayshortSerializer::emptyArray),
		CHAR("char", 2, true,
				VectorArraySupport.CHAR_HEAP_THRESHOLD, VectorArraySupport.CHAR_HEAP_MAX_VECTOR_LENGTH,
				VectorArraySupport.CHAR_SEGMENT_THRESHOLD, VectorArraySupport.CHAR_SEGMENT_MAX_VECTOR_LENGTH,
				"CharArray", ShortVector.SPECIES_PREFERRED::length, ShortVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 2, (bytes, at) -> bigEndianShort(bytes, at) & 0xffffL),
				(array) -> bits((char[]) array),
				VectorArraySupport::readCharArray, in -> new ArraycharSerializer().read(in),
				ArraycharSerializer::emptyArray),
		INT("int", 4, true,
				VectorArraySupport.INT_HEAP_THRESHOLD, VectorArraySupport.INT_HEAP_MAX_VECTOR_LENGTH,
				VectorArraySupport.INT_SEGMENT_THRESHOLD, Integer.MAX_VALUE,
				"IntArray", IntVector.SPECIES_PREFERRED::length, IntVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 4, VectorKernelParityPropertyTest::bigEndianInt),
				(array) -> bits((int[]) array),
				VectorArraySupport::readIntArray, in -> new ArrayintSerializer().read(in),
				ArrayintSerializer::emptyArray),
		LONG("long", 8, true,
				VectorArraySupport.LONG_HEAP_THRESHOLD, VectorArraySupport.LONG_HEAP_MAX_VECTOR_LENGTH,
				VectorArraySupport.LONG_SEGMENT_THRESHOLD, Integer.MAX_VALUE,
				"LongArray", LongVector.SPECIES_PREFERRED::length, LongVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 8, VectorKernelParityPropertyTest::bigEndianLong),
				(array) -> bits((long[]) array),
				VectorArraySupport::readLongArray, in -> new ArraylongSerializer().read(in),
				ArraylongSerializer::emptyArray),
		FLOAT("float", 4, true,
				VectorArraySupport.FLOAT_HEAP_THRESHOLD, Integer.MAX_VALUE,
				VectorArraySupport.FLOAT_SEGMENT_THRESHOLD, Integer.MAX_VALUE,
				"FloatArray", FloatVector.SPECIES_PREFERRED::length, FloatVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 4, VectorKernelParityPropertyTest::bigEndianInt),
				(array) -> bits((float[]) array),
				VectorArraySupport::readFloatArray, in -> new ArrayfloatSerializer().read(in),
				ArrayfloatSerializer::emptyArray),
		DOUBLE("double", 8, true,
				VectorArraySupport.DOUBLE_HEAP_THRESHOLD, Integer.MAX_VALUE,
				VectorArraySupport.DOUBLE_SEGMENT_THRESHOLD, Integer.MAX_VALUE,
				"DoubleArray", DoubleVector.SPECIES_PREFERRED::length, DoubleVector.SPECIES_PREFERRED::loopBound,
				(body, size) -> map(body, size, 8, VectorKernelParityPropertyTest::bigEndianLong),
				(array) -> bits((double[]) array),
				VectorArraySupport::readDoubleArray, in -> new ArraydoubleSerializer().read(in),
				ArraydoubleSerializer::emptyArray),
		INT52("int52", Int52.BYTES, true,
				VectorArraySupport.INT52_HEAP_THRESHOLD, Integer.MAX_VALUE,
				VectorArraySupport.INT52_SEGMENT_THRESHOLD, Integer.MAX_VALUE,
				// Int52 has no readInt52Array on RandomAccessDataInput: the kernel consumes whole
				// INT52_VECTOR_LANES blocks of Int52.BYTES bytes and the remainder is the scalar tail.
				"Int52Array", () -> ByteVector.SPECIES_PREFERRED.length() / Long.BYTES,
				size -> size - size % Math.max(1, ByteVector.SPECIES_PREFERRED.length() / Long.BYTES),
				(body, size) -> map(body, size, Int52.BYTES, VectorKernelParityPropertyTest::bigEndianInt52),
				(array) -> bits((Int52[]) array),
				VectorArraySupport::readInt52Array, in -> new ArrayInt52Serializer().read(in),
				ArrayInt52Serializer::emptyArray);

		private final String id;
		private final int elementBytes;
		private final boolean hasVectorKernel;
		private final int heapThreshold;
		private final int heapMaxLength;
		private final int segmentThreshold;
		private final int segmentMaxLength;
		private final String arrayMethod;
		private final java.util.function.IntSupplier laneCount;
		private final LoopBound loopBound;
		private final BodyOracle oracle;
		private final Fingerprint fingerprint;
		private final Read kernelRead;
		private final Read scalarCodec;
		private final java.util.function.Supplier<Object> empty;

		Kind(String id,
				int elementBytes,
				boolean hasVectorKernel,
				int heapThreshold,
				int heapMaxLength,
				int segmentThreshold,
				int segmentMaxLength,
				String arrayMethod,
				java.util.function.IntSupplier laneCount,
				LoopBound loopBound,
				BodyOracle oracle,
				Fingerprint fingerprint,
				Read kernelRead,
				Read scalarCodec,
				java.util.function.Supplier<Object> empty) {
			this.id = id;
			this.elementBytes = elementBytes;
			this.hasVectorKernel = hasVectorKernel;
			this.heapThreshold = heapThreshold;
			this.heapMaxLength = heapMaxLength;
			this.segmentThreshold = segmentThreshold;
			this.segmentMaxLength = segmentMaxLength;
			this.arrayMethod = arrayMethod;
			this.laneCount = laneCount;
			this.loopBound = loopBound;
			this.oracle = oracle;
			this.fingerprint = fingerprint;
			this.kernelRead = kernelRead;
			this.scalarCodec = scalarCodec;
			this.empty = empty;
		}

		int lanes() {
			return Math.max(1, laneCount.getAsInt());
		}

		/** The published crossover constants of this kind; lower bound for {@link #largestCrossover()}. */
		int[] crossovers() {
			int[] published = {heapThreshold, heapMaxLength, segmentThreshold, segmentMaxLength};
			TreeSet<Integer> finite = new TreeSet<>();
			for (int value : published) {
				if (value >= 1 && value < Integer.MAX_VALUE) finite.add(value);
			}
			if (finite.isEmpty()) {
				// BYTE has no vector kernel: still straddle a plausible bulk-copy length.
				finite.add(256);
			}
			int[] result = new int[finite.size()];
			int index = 0;
			for (int value : finite) result[index++] = value;
			return result;
		}

		int largestCrossover() {
			int largest = 0;
			for (int crossover : crossovers()) largest = Math.max(largest, crossover);
			return largest;
		}

		int threshold(Probe probe) {
			return probe.heap != null ? heapThreshold : segmentThreshold;
		}

		int maxLength(Probe probe) {
			return probe.heap != null ? heapMaxLength : segmentMaxLength;
		}

		/** Hostile body bytes: uniform random plus the shared {@code HOSTILE_BYTES} pool. */
		byte[] hostileBody(Random rnd, int size) {
			byte[] body = new byte[size * elementBytes];
			rnd.nextBytes(body);
			int[] hostile = FuzzBridge.hostileBytes();
			int injections = Math.min(body.length, Math.max(1, body.length / 8));
			for (int i = 0; i < injections; i++) {
				body[rnd.nextInt(body.length)] = (byte) hostile[rnd.nextInt(hostile.length)];
			}
			return body;
		}
	}

	private interface Element {
		long read(byte[] body, int at);
	}

	private static long[] map(byte[] body, int size, int elementBytes, Element element) {
		long[] result = new long[size];
		for (int i = 0; i < size; i++) result[i] = element.read(body, i * elementBytes);
		return result;
	}

	private static long bigEndianShort(byte[] b, int at) {
		return (short) (((b[at] & 0xff) << 8) | (b[at + 1] & 0xff));
	}

	private static long bigEndianInt(byte[] b, int at) {
		return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
	}

	private static long bigEndianLong(byte[] b, int at) {
		long value = 0;
		for (int i = 0; i < Long.BYTES; i++) value = (value << 8) | (b[at + i] & 0xffL);
		return value;
	}

	/** Same 7-byte big-endian layout and same top-nibble masking as {@code BufDataInputCore#getInt52}. */
	private static long bigEndianInt52(byte[] b, int at) {
		return ((long) (b[at] & 0x0f) << 48)
				| ((long) (b[at + 1] & 0xff) << 40)
				| ((long) (b[at + 2] & 0xff) << 32)
				| ((long) (b[at + 3] & 0xff) << 24)
				| ((long) (b[at + 4] & 0xff) << 16)
				| ((long) (b[at + 5] & 0xff) << 8)
				| ((long) (b[at + 6] & 0xff));
	}

	private static long[] bits(boolean[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = values[i] ? 1L : 0L;
		return result;
	}

	private static long[] bits(byte[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = values[i];
		return result;
	}

	private static long[] bits(short[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = values[i];
		return result;
	}

	private static long[] bits(char[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = values[i];
		return result;
	}

	private static long[] bits(int[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = values[i];
		return result;
	}

	private static long[] bits(long[] values) {
		return values;
	}

	/** Bit-exact: raw bits, never {@code ==} on floats. */
	private static long[] bits(float[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = Float.floatToRawIntBits(values[i]);
		return result;
	}

	/** Bit-exact: raw bits, never {@code ==} on doubles. */
	private static long[] bits(double[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = Double.doubleToRawLongBits(values[i]);
		return result;
	}

	private static long[] bits(Int52[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = values[i].longValue();
		return result;
	}

	// --------------------------------------------------------------------------------------------- storages

	private record BufSource(Buf buf, int offset) {}

	/** The four storage shapes {@link VectorArraySupport} distinguishes, plus two offset heap shapes. */
	private enum Storage {
		HEAP("heap") {
			@Override
			BufSource bind(byte[] payload, Arena arena) {
				return new BufSource(Buf.wrap(payload), 0);
			}
		},
		HEAP_OFFSET("heap-offset") {
			@Override
			BufSource bind(byte[] payload, Arena arena) {
				return new BufSource(Buf.wrap(padded(payload, 11)), 11);
			}
		},
		HEAP_SUBLIST("heap-sublist") {
			@Override
			BufSource bind(byte[] payload, Arena arena) {
				return new BufSource(Buf.wrap(padded(payload, 11)).subList(11, 11 + payload.length), 0);
			}
		},
		NATIVE_SEGMENT("native-segment") {
			@Override
			BufSource bind(byte[] payload, Arena arena) {
				MemorySegment storage = arena.allocate(payload.length + 9L, 1);
				MemorySegment.copy(MemorySegment.ofArray(payload), 0, storage.asSlice(3), 0, payload.length);
				return new BufSource(new NoCopyMemorySegmentBuf(storage), 3);
			}
		},
		FALLBACK("fallback") {
			@Override
			BufSource bind(byte[] payload, Arena arena) {
				return new BufSource(new VectorFallbackBuf(payload.clone()), 0);
			}
		};

		private final String id;

		Storage(String id) {
			this.id = id;
		}

		abstract BufSource bind(byte[] payload, Arena arena);

		static byte[] padded(byte[] payload, int pad) {
			byte[] padded = new byte[payload.length + pad * 2];
			System.arraycopy(payload, 0, padded, pad, payload.length);
			return padded;
		}
	}

	/** Proves the native kernel never falls back to copying the payload into a heap array. */
	private static final class NoCopyMemorySegmentBuf extends MemorySegmentBuf {
		private NoCopyMemorySegmentBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Parity fuzz: native storage must not copy the payload into a byte array");
		}

		@Override
		public SafeByteArrayInputStream binaryInputStream() {
			throw new AssertionError("Parity fuzz: native storage must not open a payload stream");
		}
	}

	// --------------------------------------------------------------------------------------------- probe

	/**
	 * Pass-through {@link RandomAccessDataInput} that records which path the kernel took.
	 *
	 * <p>{@code BufDataInputCore} declares {@code readXxxArray(int)}, {@code directHeapArray()},
	 * {@code directMemorySegment()} and {@code directStorageOffset(int)} {@code final}, so a cursor subclass cannot
	 * intercept them; delegation is the only non-invasive way to observe path selection.</p>
	 */
	private static final class Probe implements RandomAccessDataInput {

		private static final int KINDS = Kind.values().length;

		private BufDataCursor delegate;
		private final boolean[] bulk = new boolean[KINDS];
		private final int[] tail = new int[KINDS];
		private int storageOffsetCalls;
		private int probed;
		private byte[] heap;
		private MemorySegment segment;

		private void bulk(Kind kind) {
			bulk[kind.ordinal()] = true;
		}

		private void tail(Kind kind) {
			tail[kind.ordinal()]++;
		}

		@Override
		public boolean[] readBooleanArray(int length) {
			bulk(Kind.BOOLEAN);
			return delegate.readBooleanArray(length);
		}

		@Override
		public byte[] readByteArray(int length) {
			bulk(Kind.BYTE);
			return delegate.readByteArray(length);
		}

		@Override
		public short[] readShortArray(int length) {
			bulk(Kind.SHORT);
			return delegate.readShortArray(length);
		}

		@Override
		public char[] readCharArray(int length) {
			bulk(Kind.CHAR);
			return delegate.readCharArray(length);
		}

		@Override
		public int[] readIntArray(int length) {
			bulk(Kind.INT);
			return delegate.readIntArray(length);
		}

		@Override
		public long[] readLongArray(int length) {
			bulk(Kind.LONG);
			return delegate.readLongArray(length);
		}

		@Override
		public float[] readFloatArray(int length) {
			bulk(Kind.FLOAT);
			return delegate.readFloatArray(length);
		}

		@Override
		public double[] readDoubleArray(int length) {
			bulk(Kind.DOUBLE);
			return delegate.readDoubleArray(length);
		}

		@Override
		public boolean getBooleanAt(int offset) {
			tail(Kind.BOOLEAN);
			return delegate.getBooleanAt(offset);
		}

		@Override
		public short getShortAt(int offset) {
			tail(Kind.SHORT);
			return delegate.getShortAt(offset);
		}

		@Override
		public char getCharAt(int offset) {
			tail(Kind.CHAR);
			return delegate.getCharAt(offset);
		}

		@Override
		public int getIntAt(int offset) {
			tail(Kind.INT);
			return delegate.getIntAt(offset);
		}

		@Override
		public long getLongAt(int offset) {
			tail(Kind.LONG);
			return delegate.getLongAt(offset);
		}

		@Override
		public float getFloatAt(int offset) {
			tail(Kind.FLOAT);
			return delegate.getFloatAt(offset);
		}

		@Override
		public double getDoubleAt(int offset) {
			tail(Kind.DOUBLE);
			return delegate.getDoubleAt(offset);
		}

		@Override
		public long getInt52At(int offset) {
			tail(Kind.INT52);
			return delegate.getInt52At(offset);
		}

		@Override
		public byte[] directHeapArray() {
			probed++;
			heap = delegate.directHeapArray();
			return heap;
		}

		@Override
		public MemorySegment directMemorySegment() {
			probed++;
			segment = delegate.directMemorySegment();
			return segment;
		}

		@Override
		public long directStorageOffset(int relativeOffset) {
			storageOffsetCalls++;
			return delegate.directStorageOffset(relativeOffset);
		}

		@Override
		public int reserve(int byteLength) {
			return delegate.reserve(byteLength);
		}

		@Override
		public byte getByteAt(int offset) {
			return delegate.getByteAt(offset);
		}

		@Override
		public int getUnsignedByteAt(int offset) {
			return delegate.getUnsignedByteAt(offset);
		}

		@Override
		public int getUnsignedShortAt(int offset) {
			return delegate.getUnsignedShortAt(offset);
		}

		@Override
		public void readBooleans(boolean[] destination, int offset, int length) {
			delegate.readBooleans(destination, offset, length);
		}

		@Override
		public void readBytes(byte[] destination, int offset, int length) {
			delegate.readBytes(destination, offset, length);
		}

		@Override
		public void readShorts(short[] destination, int offset, int length) {
			delegate.readShorts(destination, offset, length);
		}

		@Override
		public void readChars(char[] destination, int offset, int length) {
			delegate.readChars(destination, offset, length);
		}

		@Override
		public void readInts(int[] destination, int offset, int length) {
			delegate.readInts(destination, offset, length);
		}

		@Override
		public void readLongs(long[] destination, int offset, int length) {
			delegate.readLongs(destination, offset, length);
		}

		@Override
		public void readFloats(float[] destination, int offset, int length) {
			delegate.readFloats(destination, offset, length);
		}

		@Override
		public void readDoubles(double[] destination, int offset, int length) {
			delegate.readDoubles(destination, offset, length);
		}

		@Override
		public void skipExact(int length) {
			delegate.skipExact(length);
		}

		@Override
		public void bindRegion(BufDataCursor target, int offset, int length) {
			delegate.bindRegion(target, offset, length);
		}

		@Override
		public void bindReservedRegion(BufDataCursor target, int offset, int length) {
			delegate.bindReservedRegion(target, offset, length);
		}

		@Override
		public int position() {
			return delegate.position();
		}

		@Override
		public void position(int newPosition) {
			delegate.position(newPosition);
		}

		@Override
		public int length() {
			return delegate.length();
		}

		@Override
		public DecodeBudget decodeBudget() {
			return delegate.decodeBudget();
		}

		@Override
		public long remainingBytesIfKnown() {
			return delegate.remainingBytesIfKnown();
		}

		@Override
		public int read(byte[] b) {
			return delegate.read(b);
		}

		@Override
		public int read(byte[] b, int off, int len) {
			return delegate.read(b, off, len);
		}

		@Override
		public void readFully(ByteBuffer dst) {
			delegate.readFully(dst);
		}

		@Override
		public void readFully(ByteBuffer dst, int len) {
			delegate.readFully(dst, len);
		}

		@Override
		public void readFully(byte[] b) {
			delegate.readFully(b);
		}

		@Override
		public void readFully(byte[] b, int off, int len) {
			delegate.readFully(b, off, len);
		}

		@Override
		public int skipBytes(int n) {
			return delegate.skipBytes(n);
		}

		@Override
		public boolean readBoolean() {
			return delegate.readBoolean();
		}

		@Override
		public byte readByte() {
			return delegate.readByte();
		}

		@Override
		public int readUnsignedByte() {
			return delegate.readUnsignedByte();
		}

		@Override
		public short readShort() {
			return delegate.readShort();
		}

		@Override
		public int readUnsignedShort() {
			return delegate.readUnsignedShort();
		}

		@Override
		public char readChar() {
			return delegate.readChar();
		}

		@Override
		public int readInt() {
			return delegate.readInt();
		}

		@Override
		public long readLong() {
			return delegate.readLong();
		}

		@Override
		public long readInt52() {
			return delegate.readInt52();
		}

		@Override
		public float readFloat() {
			return delegate.readFloat();
		}

		@Override
		public double readDouble() {
			return delegate.readDouble();
		}

		@Override
		public String readLine() {
			return delegate.readLine();
		}

		@Override
		public String readString(int length, Charset charset) {
			return delegate.readString(length, charset);
		}

		@Override
		public void close() throws IOException {
			delegate.close();
		}
	}

	// --------------------------------------------------------------------------------------------- tier bridge

	/**
	 * Reflective bridge to {@code it.cavallium.datagen.fuzz.FuzzTiers}/{@code FuzzValues}.
	 *
	 * <p>Those are {@code datagen} <em>test</em> sources; this module depends only on the {@code datagen} jar, so
	 * neither class is on this classpath and the documented fallback semantics below are what actually run here —
	 * the same approach {@code datagen-plugin}'s {@code CompiledSchemas}/{@code ReflectiveValueGen} use.</p>
	 */
	private static final class FuzzBridge {

		private static final String TIER_PROPERTY = "datagen.fuzz.tier";
		private static final String SEED_PROPERTY = "datagen.fuzz.seed";
		private static final String[] TIER_NAMES = {"QUICK", "DEFAULT", "DEEP", "NIGHTLY"};
		private static final int[] LOCAL_SIZE_LADDER = {0, 1, 2, 8, 17, 32, 64, 255, 256, 65535, 65536};
		private static final int[] LOCAL_HOSTILE_BYTES = {0x00, 0x01, 0x7F, 0x80, 0xFF};
		private static final long JVM_CONSTANT = new Random().nextLong() & Long.MAX_VALUE;

		private FuzzBridge() {}

		static String tierName() {
			Object tier = invoke("it.cavallium.datagen.fuzz.FuzzTiers", "tier");
			if (tier instanceof Enum<?> enumValue) return enumValue.name();
			String raw = System.getProperty(TIER_PROPERTY);
			if (raw == null) {
				return System.getProperty(SEED_PROPERTY) == null ? "DEFAULT" : "DEEP";
			}
			for (String candidate : TIER_NAMES) {
				if (candidate.equalsIgnoreCase(raw)) return candidate;
			}
			throw new IllegalArgumentException("Invalid " + TIER_PROPERTY + "='" + raw + "'");
		}

		static long seed() {
			Object seed = invoke("it.cavallium.datagen.fuzz.FuzzTiers", "seed");
			if (seed instanceof Long longSeed) return longSeed;
			String raw = System.getProperty(SEED_PROPERTY);
			if (raw == null) return JVM_CONSTANT;
			try {
				return Long.parseLong(raw) & Long.MAX_VALUE;
			} catch (NumberFormatException exception) {
				throw new IllegalArgumentException("Invalid " + SEED_PROPERTY + "='" + raw + "'", exception);
			}
		}

		static Random random(String salt) {
			Object random = invokeWith("it.cavallium.datagen.fuzz.FuzzTiers", "random", salt);
			if (random instanceof Random seeded) return seeded;
			long saltMix = salt == null ? 0L : (long) salt.hashCode() * 0x9E3779B97F4A7C15L;
			return new Random(Long.rotateLeft(seed(), 17) ^ saltMix);
		}

		static void requireMinimumTier(String required) {
			Object aborted = invokeWith("it.cavallium.datagen.fuzz.FuzzTiers", "requireMinimumTier", required);
			if (aborted != null) return;
			int current = order(tierName());
			// Same engine-agnostic abort as FuzzTiers.requireMinimumTier: a lower tier skips, it never fails.
			org.junit.jupiter.api.Assumptions.assumeTrue(current >= order(required),
					"requires -" + TIER_PROPERTY + ">=" + required + " (current=" + tierName() + ")");
		}

		static String describe() {
			Object described = invoke("it.cavallium.datagen.fuzz.FuzzTiers", "describe");
			if (described instanceof String text) return text;
			return "tier=" + tierName() + " bridge=local";
		}

		static int[] sizeLadder() {
			Object ladder = field("it.cavallium.datagen.fuzz.FuzzValues", "SIZE_LADDER");
			return ladder instanceof int[] values && values.length > 0 ? values : LOCAL_SIZE_LADDER;
		}

		static int[] hostileBytes() {
			Object hostile = field("it.cavallium.datagen.fuzz.FuzzValues", "HOSTILE_BYTES");
			return hostile instanceof int[] values && values.length > 0 ? values : LOCAL_HOSTILE_BYTES;
		}

		private static int order(String tier) {
			for (int index = 0; index < TIER_NAMES.length; index++) {
				if (TIER_NAMES[index].equals(tier)) return index;
			}
			throw new IllegalArgumentException("Unknown tier: " + tier);
		}

		private static Object invoke(String className, String method) {
			try {
				return Class.forName(className).getMethod(method).invoke(null);
			} catch (ReflectiveOperationException | RuntimeException exception) {
				return null;
			}
		}

		private static Object invokeWith(String className, String method, String argument) {
			try {
				Class<?> owner = Class.forName(className);
				for (var candidate : owner.getMethods()) {
					if (!candidate.getName().equals(method) || candidate.getParameterCount() != 1) continue;
					Class<?> parameter = candidate.getParameterTypes()[0];
					Object value = parameter.isEnum() ? enumByName(parameter, argument) : argument;
					candidate.invoke(null, value);
					return candidate;
				}
				return null;
			} catch (ReflectiveOperationException | RuntimeException exception) {
				return null;
			}
		}

		private static Object enumByName(Class<?> enumClass, String name) {
			for (Object constant : enumClass.getEnumConstants()) {
				if (((Enum<?>) constant).name().equals(name)) return constant;
			}
			throw new IllegalArgumentException("Unknown " + enumClass.getName() + ": " + name);
		}

		private static Object field(String className, String name) {
			try {
				return Class.forName(className).getField(name).get(null);
			} catch (ReflectiveOperationException | RuntimeException exception) {
				return null;
			}
		}
	}
}
