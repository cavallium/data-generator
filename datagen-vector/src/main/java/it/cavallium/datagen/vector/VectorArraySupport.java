package it.cavallium.datagen.vector;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.datagen.ProjectionReadSupport;
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
import it.cavallium.datagen.nativedata.Int52Serializer;
import it.cavallium.stream.SafeDataInput;
import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Optional Vector API lowering for generated native-array codecs.
 *
 * <p>This class belongs to the incubator-dependent {@code datagen-vector} artifact. The stable
 * runtime and scalar generated sources never link to it. Every direct kernel reserves the complete
 * wire payload before allocating its returned array, and every non-vector or non-direct path falls
 * back to the stable scalar runtime.</p>
 */
public final class VectorArraySupport {

	private static final boolean REVERSE_VECTOR_BYTES = ByteOrder.nativeOrder() == LITTLE_ENDIAN;
	private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;
	private static final VectorSpecies<Short> SHORT_SPECIES = ShortVector.SPECIES_PREFERRED;
	private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;
	private static final VectorSpecies<Long> LONG_SPECIES = LongVector.SPECIES_PREFERRED;
	private static final VectorSpecies<Float> FLOAT_SPECIES = FloatVector.SPECIES_PREFERRED;
	private static final VectorSpecies<Double> DOUBLE_SPECIES = DoubleVector.SPECIES_PREFERRED;

	/*
	 * Checked-in crossovers selected from the generated per-type heap/native JMH matrix. Most
	 * kernels have a single lower crossover. Heap int/long and char have measured upper crossovers
	 * as well: HotSpot's scalar VarHandle loop catches the manual vector kernel for large int/long
	 * arrays, while a large char[] MemorySegment view can escape and violate allocation parity.
	 * Keep these named ranges so future machine-specific retuning remains an evidence-only change.
	 */
	public static final int BOOLEAN_HEAP_THRESHOLD = 128;
	public static final int BOOLEAN_SEGMENT_THRESHOLD = 128;
	public static final int SHORT_HEAP_THRESHOLD = 64;
	public static final int SHORT_SEGMENT_THRESHOLD = 64;
	public static final int CHAR_HEAP_THRESHOLD = 64;
	public static final int CHAR_SEGMENT_THRESHOLD = 64;
	public static final int CHAR_HEAP_MAX_VECTOR_LENGTH = 256;
	public static final int CHAR_SEGMENT_MAX_VECTOR_LENGTH = 256;
	public static final int INT_HEAP_THRESHOLD = 32;
	public static final int INT_SEGMENT_THRESHOLD = 32;
	public static final int INT_HEAP_MAX_VECTOR_LENGTH = 128;
	public static final int LONG_HEAP_THRESHOLD = 16;
	public static final int LONG_SEGMENT_THRESHOLD = 16;
	public static final int LONG_HEAP_MAX_VECTOR_LENGTH = 64;
	public static final int FLOAT_HEAP_THRESHOLD = 32;
	public static final int FLOAT_SEGMENT_THRESHOLD = 32;
	public static final int DOUBLE_HEAP_THRESHOLD = 16;
	public static final int DOUBLE_SEGMENT_THRESHOLD = 16;
	public static final int INT52_HEAP_THRESHOLD = 16;
	public static final int INT52_SEGMENT_THRESHOLD = 16;

	private static final int INT52_VECTOR_LANES = BYTE_SPECIES.length() / Long.BYTES;
	private static final int INT52_SOURCE_BYTES = INT52_VECTOR_LANES * Int52.BYTES;
	private static final VectorMask<Byte> INT52_SOURCE_MASK =
			BYTE_SPECIES.indexInRange(0, INT52_SOURCE_BYTES);
	private static final VectorMask<Byte> INT52_ZERO_PREFIX_MASK = createInt52ZeroPrefixMask();
	private static final VectorShuffle<Byte> INT52_EXPAND_SHUFFLE = createInt52ExpandShuffle();

	private VectorArraySupport() {}

	public static boolean[] readBooleanArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readBooleanArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static boolean[] readBooleanArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArraybooleanSerializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			return ProjectionReadSupport.readBooleanArray(input, length);
		}
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		if (!useVector(length, heap, segment, BOOLEAN_HEAP_THRESHOLD, BOOLEAN_SEGMENT_THRESHOLD)) {
			return ProjectionReadSupport.readBooleanArray(input, length);
		}
		int start = random.reserve(length);
		input.decodeBudget().claimArrayElements(length);
		boolean[] result = new boolean[length];
		int vectorBound = BYTE_SPECIES.loopBound(length);
		long storage = random.directStorageOffset(start);
		if (heap != null) {
			int source = Math.toIntExact(storage);
			for (int i = 0; i < vectorBound; i += BYTE_SPECIES.length()) {
				ByteVector.fromArray(BYTE_SPECIES, heap, source + i)
						.compare(VectorOperators.NE, (byte) 0)
						.intoArray(result, i);
			}
		} else {
			for (int i = 0; i < vectorBound; i += BYTE_SPECIES.length()) {
				ByteVector.fromMemorySegment(BYTE_SPECIES, segment, storage + i, BIG_ENDIAN)
						.compare(VectorOperators.NE, (byte) 0)
						.intoArray(result, i);
			}
		}
		for (int i = vectorBound; i < length; i++) result[i] = random.getBooleanAt(start + i);
		return result;
	}

	/** Byte arrays already use the optimal one-check bulk-copy scalar kernel. */
	public static byte[] readByteArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readByteArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static byte[] readByteArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArraybyteSerializer.emptyArray();
		}
		return ProjectionReadSupport.readByteArray(input, length);
	}

	public static short[] readShortArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readShortArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static short[] readShortArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArrayshortSerializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			return ProjectionReadSupport.readShortArray(input, length);
		}
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		if (!useVector(length, heap, segment, SHORT_HEAP_THRESHOLD, SHORT_SEGMENT_THRESHOLD)) {
			return ProjectionReadSupport.readShortArray(input, length);
		}
		int start = random.reserve(ProjectionReadSupport.checkedArrayBytes(length, Short.BYTES));
		input.decodeBudget().claimArrayElements(length);
		short[] result = new short[length];
		int vectorBound = SHORT_SPECIES.loopBound(length);
		long storage = random.directStorageOffset(start);
		if (heap != null) {
			int source = Math.toIntExact(storage);
			for (int i = 0; i < vectorBound; i += SHORT_SPECIES.length()) {
				ShortVector values = ByteVector.fromArray(BYTE_SPECIES, heap, source + i * Short.BYTES)
						.reinterpretAsShorts();
				if (REVERSE_VECTOR_BYTES) values = values.lanewise(VectorOperators.REVERSE_BYTES);
				values.intoArray(result, i);
			}
		} else {
			for (int i = 0; i < vectorBound; i += SHORT_SPECIES.length()) {
				ShortVector.fromMemorySegment(SHORT_SPECIES, segment, storage + (long) i * Short.BYTES, BIG_ENDIAN)
						.intoArray(result, i);
			}
		}
		for (int i = vectorBound; i < length; i++) result[i] = random.getShortAt(start + i * Short.BYTES);
		return result;
	}

	public static char[] readCharArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readCharArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static char[] readCharArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArraycharSerializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			return ProjectionReadSupport.readCharArray(input, length);
		}
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		if (!useVector(length, heap, segment,
				CHAR_HEAP_THRESHOLD, CHAR_HEAP_MAX_VECTOR_LENGTH,
				CHAR_SEGMENT_THRESHOLD, CHAR_SEGMENT_MAX_VECTOR_LENGTH)) {
			return ProjectionReadSupport.readCharArray(input, length);
		}
		int start = random.reserve(ProjectionReadSupport.checkedArrayBytes(length, Character.BYTES));
		input.decodeBudget().claimArrayElements(length);
		char[] result = new char[length];
		MemorySegment destination = MemorySegment.ofArray(result);
		int vectorBound = SHORT_SPECIES.loopBound(length);
		long storage = random.directStorageOffset(start);
		for (int i = 0; i < vectorBound; i += SHORT_SPECIES.length()) {
			ShortVector values;
			if (heap != null) {
				values = ByteVector.fromArray(BYTE_SPECIES, heap,
						Math.toIntExact(storage) + i * Character.BYTES).reinterpretAsShorts();
				if (REVERSE_VECTOR_BYTES) values = values.lanewise(VectorOperators.REVERSE_BYTES);
			} else {
				values = ShortVector.fromMemorySegment(SHORT_SPECIES, segment,
						storage + (long) i * Character.BYTES, BIG_ENDIAN);
			}
			values.intoMemorySegment(destination, (long) i * Character.BYTES, ByteOrder.nativeOrder());
		}
		for (int i = vectorBound; i < length; i++) result[i] = random.getCharAt(start + i * Character.BYTES);
		return result;
	}

	public static int[] readIntArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readIntArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static int[] readIntArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArrayintSerializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			return ProjectionReadSupport.readIntArray(input, length);
		}
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		if (!useVector(length, heap, segment,
				INT_HEAP_THRESHOLD, INT_HEAP_MAX_VECTOR_LENGTH,
				INT_SEGMENT_THRESHOLD, Integer.MAX_VALUE)) {
			return ProjectionReadSupport.readIntArray(input, length);
		}
		int start = random.reserve(ProjectionReadSupport.checkedArrayBytes(length, Integer.BYTES));
		input.decodeBudget().claimArrayElements(length);
		int[] result = new int[length];
		int vectorBound = INT_SPECIES.loopBound(length);
		long storage = random.directStorageOffset(start);
		if (heap != null) {
			int source = Math.toIntExact(storage);
			for (int i = 0; i < vectorBound; i += INT_SPECIES.length()) {
				IntVector values = ByteVector.fromArray(BYTE_SPECIES, heap, source + i * Integer.BYTES)
						.reinterpretAsInts();
				if (REVERSE_VECTOR_BYTES) values = values.lanewise(VectorOperators.REVERSE_BYTES);
				values.intoArray(result, i);
			}
		} else {
			for (int i = 0; i < vectorBound; i += INT_SPECIES.length()) {
				IntVector.fromMemorySegment(INT_SPECIES, segment, storage + (long) i * Integer.BYTES, BIG_ENDIAN)
						.intoArray(result, i);
			}
		}
		for (int i = vectorBound; i < length; i++) result[i] = random.getIntAt(start + i * Integer.BYTES);
		return result;
	}

	public static long[] readLongArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readLongArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static long[] readLongArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArraylongSerializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			return ProjectionReadSupport.readLongArray(input, length);
		}
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		if (!useVector(length, heap, segment,
				LONG_HEAP_THRESHOLD, LONG_HEAP_MAX_VECTOR_LENGTH,
				LONG_SEGMENT_THRESHOLD, Integer.MAX_VALUE)) {
			return ProjectionReadSupport.readLongArray(input, length);
		}
		int start = random.reserve(ProjectionReadSupport.checkedArrayBytes(length, Long.BYTES));
		input.decodeBudget().claimArrayElements(length);
		long[] result = new long[length];
		int vectorBound = LONG_SPECIES.loopBound(length);
		long storage = random.directStorageOffset(start);
		if (heap != null) {
			int source = Math.toIntExact(storage);
			for (int i = 0; i < vectorBound; i += LONG_SPECIES.length()) {
				LongVector values = ByteVector.fromArray(BYTE_SPECIES, heap, source + i * Long.BYTES)
						.reinterpretAsLongs();
				if (REVERSE_VECTOR_BYTES) values = values.lanewise(VectorOperators.REVERSE_BYTES);
				values.intoArray(result, i);
			}
		} else {
			for (int i = 0; i < vectorBound; i += LONG_SPECIES.length()) {
				LongVector.fromMemorySegment(LONG_SPECIES, segment, storage + (long) i * Long.BYTES, BIG_ENDIAN)
						.intoArray(result, i);
			}
		}
		for (int i = vectorBound; i < length; i++) result[i] = random.getLongAt(start + i * Long.BYTES);
		return result;
	}

	public static float[] readFloatArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readFloatArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static float[] readFloatArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArrayfloatSerializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			return ProjectionReadSupport.readFloatArray(input, length);
		}
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		if (!useVector(length, heap, segment, FLOAT_HEAP_THRESHOLD, FLOAT_SEGMENT_THRESHOLD)) {
			return ProjectionReadSupport.readFloatArray(input, length);
		}
		int start = random.reserve(ProjectionReadSupport.checkedArrayBytes(length, Float.BYTES));
		input.decodeBudget().claimArrayElements(length);
		float[] result = new float[length];
		int vectorBound = FLOAT_SPECIES.loopBound(length);
		long storage = random.directStorageOffset(start);
		if (heap != null) {
			int source = Math.toIntExact(storage);
			for (int i = 0; i < vectorBound; i += FLOAT_SPECIES.length()) {
				IntVector bits = ByteVector.fromArray(BYTE_SPECIES, heap, source + i * Float.BYTES)
						.reinterpretAsInts();
				if (REVERSE_VECTOR_BYTES) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
				bits.reinterpretAsFloats().intoArray(result, i);
			}
		} else {
			for (int i = 0; i < vectorBound; i += FLOAT_SPECIES.length()) {
				FloatVector.fromMemorySegment(FLOAT_SPECIES, segment, storage + (long) i * Float.BYTES, BIG_ENDIAN)
						.intoArray(result, i);
			}
		}
		for (int i = vectorBound; i < length; i++) result[i] = random.getFloatAt(start + i * Float.BYTES);
		return result;
	}

	public static double[] readDoubleArray(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readDoubleArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static double[] readDoubleArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArraydoubleSerializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			return ProjectionReadSupport.readDoubleArray(input, length);
		}
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		if (!useVector(length, heap, segment, DOUBLE_HEAP_THRESHOLD, DOUBLE_SEGMENT_THRESHOLD)) {
			return ProjectionReadSupport.readDoubleArray(input, length);
		}
		int start = random.reserve(ProjectionReadSupport.checkedArrayBytes(length, Double.BYTES));
		input.decodeBudget().claimArrayElements(length);
		double[] result = new double[length];
		int vectorBound = DOUBLE_SPECIES.loopBound(length);
		long storage = random.directStorageOffset(start);
		if (heap != null) {
			int source = Math.toIntExact(storage);
			for (int i = 0; i < vectorBound; i += DOUBLE_SPECIES.length()) {
				LongVector bits = ByteVector.fromArray(BYTE_SPECIES, heap, source + i * Double.BYTES)
						.reinterpretAsLongs();
				if (REVERSE_VECTOR_BYTES) bits = bits.lanewise(VectorOperators.REVERSE_BYTES);
				bits.reinterpretAsDoubles().intoArray(result, i);
			}
		} else {
			for (int i = 0; i < vectorBound; i += DOUBLE_SPECIES.length()) {
				DoubleVector.fromMemorySegment(DOUBLE_SPECIES, segment, storage + (long) i * Double.BYTES, BIG_ENDIAN)
						.intoArray(result, i);
			}
		}
		for (int i = vectorBound; i < length; i++) result[i] = random.getDoubleAt(start + i * Double.BYTES);
		return result;
	}

	public static Int52[] readInt52Array(SafeDataInput input) {
		input.decodeBudget().enterStructure();
		try {
			return readInt52ArrayBody(input);
		} finally {
			input.decodeBudget().exitStructure();
		}
	}

	private static Int52[] readInt52ArrayBody(SafeDataInput input) {
		int length = ProjectionReadSupport.readLength(input);
		if (length == 0) {
			input.decodeBudget().claimArrayElements(0);
			return ArrayInt52Serializer.emptyArray();
		}
		if (!(input instanceof RandomAccessDataInput random)) {
			ProjectionReadSupport.prepareArrayAllocation(input, length, Int52.BYTES);
			Int52[] result = new Int52[length];
			for (int i = 0; i < length; i++) result[i] = Int52Serializer.INSTANCE.read(input);
			return result;
		}
		int start = random.reserve(ProjectionReadSupport.checkedArrayBytes(length, Int52.BYTES));
		input.decodeBudget().claimArrayElements(length);
		Int52[] result = new Int52[length];
		byte[] heap = random.directHeapArray();
		MemorySegment segment = random.directMemorySegment();
		long storage = heap != null || segment != null ? random.directStorageOffset(start) : 0;
		boolean vector = useVector(length, heap, segment, INT52_HEAP_THRESHOLD, INT52_SEGMENT_THRESHOLD);
		int vectorBound = vector ? length - length % INT52_VECTOR_LANES : 0;
		int i = 0;
		for (; i < vectorBound; i += INT52_VECTOR_LANES) {
			long sourceOffset = storage + (long) i * Int52.BYTES;
			ByteVector source;
			if (heap != null) {
				source = ByteVector.fromArray(BYTE_SPECIES, heap, Math.toIntExact(sourceOffset), INT52_SOURCE_MASK);
			} else {
				source = ByteVector.fromMemorySegment(BYTE_SPECIES, segment, sourceOffset, BIG_ENDIAN,
						INT52_SOURCE_MASK);
			}
			ByteVector expanded = source.rearrange(INT52_EXPAND_SHUFFLE)
					.blend((byte) 0, INT52_ZERO_PREFIX_MASK);
			LongVector values = expanded.reinterpretAsLongs();
			if (REVERSE_VECTOR_BYTES) values = values.lanewise(VectorOperators.REVERSE_BYTES);
			for (int lane = 0; lane < INT52_VECTOR_LANES; lane++) {
				result[i + lane] = Int52.fromLong(values.lane(lane) & Int52.MAX_VALUE_L);
			}
		}
		for (; i < length; i++) result[i] = Int52.fromLong(random.getInt52At(start + i * Int52.BYTES));
		return result;
	}

	private static boolean useVector(int length,
			byte[] heap,
			MemorySegment segment,
			int heapThreshold,
			int segmentThreshold) {
		return heap != null ? length >= heapThreshold : segment != null && length >= segmentThreshold;
	}

	private static boolean useVector(int length,
			byte[] heap,
			MemorySegment segment,
			int heapThreshold,
			int heapMaxLength,
			int segmentThreshold,
			int segmentMaxLength) {
		return heap != null
				? length >= heapThreshold && length <= heapMaxLength
				: segment != null && length >= segmentThreshold && length <= segmentMaxLength;
	}

	private static VectorMask<Byte> createInt52ZeroPrefixMask() {
		long bits = 0;
		for (int lane = 0; lane < BYTE_SPECIES.length(); lane += Long.BYTES) bits |= 1L << lane;
		return VectorMask.fromLong(BYTE_SPECIES, bits);
	}

	private static VectorShuffle<Byte> createInt52ExpandShuffle() {
		int[] indexes = new int[BYTE_SPECIES.length()];
		for (int lane = 0; lane < indexes.length; lane++) {
			int withinLong = lane % Long.BYTES;
			indexes[lane] = withinLong == 0 ? 0 : lane / Long.BYTES * Int52.BYTES + withinLong - 1;
		}
		return VectorShuffle.fromArray(BYTE_SPECIES, indexes, 0);
	}
}
