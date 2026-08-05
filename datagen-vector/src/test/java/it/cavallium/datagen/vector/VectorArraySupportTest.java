package it.cavallium.datagen.vector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.buffer.VectorFallbackBuf;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.MalformedDataException;
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
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class VectorArraySupportTest {

	private static final int[] SIZES = {0, 1, 2, 8, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 256};

	@Test
	void matchesScalarWireForEveryPrimitiveArrayAndStorageShape() {
		try (Arena arena = Arena.ofConfined()) {
			for (int size : SIZES) {
				boolean[] booleans = booleans(size);
				assertAllStorages(arena, encode(new ArraybooleanSerializer(), booleans), booleans,
						VectorArraySupport::readBooleanArray, VectorArraySupportTest::assertBooleanArray);

				byte[] bytes = bytes(size);
				assertAllStorages(arena, encode(new ArraybyteSerializer(), bytes), bytes,
						VectorArraySupport::readByteArray, VectorArraySupportTest::assertByteArray);

				short[] shorts = shorts(size);
				assertAllStorages(arena, encode(new ArrayshortSerializer(), shorts), shorts,
						VectorArraySupport::readShortArray, VectorArraySupportTest::assertShortArray);

				char[] chars = chars(size);
				assertAllStorages(arena, encode(new ArraycharSerializer(), chars), chars,
						VectorArraySupport::readCharArray, VectorArraySupportTest::assertCharArray);

				int[] ints = ints(size);
				assertAllStorages(arena, encode(new ArrayintSerializer(), ints), ints,
						VectorArraySupport::readIntArray, VectorArraySupportTest::assertIntArray);

				long[] longs = longs(size);
				assertAllStorages(arena, encode(new ArraylongSerializer(), longs), longs,
						VectorArraySupport::readLongArray, VectorArraySupportTest::assertLongArray);

				float[] floats = floats(size);
				assertAllStorages(arena, encode(new ArrayfloatSerializer(), floats), floats,
						VectorArraySupport::readFloatArray, VectorArraySupportTest::assertFloatArrayBits);

				double[] doubles = doubles(size);
				assertAllStorages(arena, encode(new ArraydoubleSerializer(), doubles), doubles,
						VectorArraySupport::readDoubleArray, VectorArraySupportTest::assertDoubleArrayBits);

				Int52[] int52s = int52s(size);
				assertAllStorages(arena, encode(new ArrayInt52Serializer(), int52s), int52s,
						VectorArraySupport::readInt52Array, VectorArraySupportTest::assertInt52Array);
			}
		}
	}

	@Test
	void canonicalizesEveryEmptyArray() {
		BufDataOutput output = BufDataOutput.create(Integer.BYTES);
		output.writeInt(0);
		Buf empty = output.asList();
		assertSame(ArraybooleanSerializer.emptyArray(), read(empty, VectorArraySupport::readBooleanArray));
		assertSame(ArraybyteSerializer.emptyArray(), read(empty, VectorArraySupport::readByteArray));
		assertSame(ArrayshortSerializer.emptyArray(), read(empty, VectorArraySupport::readShortArray));
		assertSame(ArraycharSerializer.emptyArray(), read(empty, VectorArraySupport::readCharArray));
		assertSame(ArrayintSerializer.emptyArray(), read(empty, VectorArraySupport::readIntArray));
		assertSame(ArraylongSerializer.emptyArray(), read(empty, VectorArraySupport::readLongArray));
		assertSame(ArrayfloatSerializer.emptyArray(), read(empty, VectorArraySupport::readFloatArray));
		assertSame(ArraydoubleSerializer.emptyArray(), read(empty, VectorArraySupport::readDoubleArray));
		assertSame(ArrayInt52Serializer.emptyArray(), read(empty, VectorArraySupport::readInt52Array));
	}

	@Test
	void validatesCompletePayloadBeforeAllocationAndCursorRemainsReusable() {
		BufDataOutput truncated = BufDataOutput.create(32);
		truncated.writeInt(VectorArraySupport.INT_HEAP_THRESHOLD);
		truncated.writeInt(1);
		BufDataCursor cursor = new BufDataCursor(DecodeLimits.unlimited());
		cursor.bind(truncated.asList(), 0, truncated.size());
		try {
			assertThrows(MalformedDataException.class, () -> VectorArraySupport.readIntArray(cursor));
			assertEquals(Integer.BYTES, cursor.position());
		} finally {
			cursor.unbind();
		}
		assertFalse(cursor.isBound());

		BufDataOutput overflow = BufDataOutput.create(Integer.BYTES);
		overflow.writeInt(Integer.MAX_VALUE);
		assertThrows(MalformedDataException.class,
				() -> read(overflow.asList(), VectorArraySupport::readDoubleArray));
		assertThrows(MalformedDataException.class,
				() -> read(overflow.asList(), VectorArraySupport::readInt52Array));

		BufDataOutput negative = BufDataOutput.create(Integer.BYTES);
		negative.writeInt(-1);
		assertThrows(MalformedDataException.class,
				() -> read(negative.asList(), VectorArraySupport::readBooleanArray));

		int[] expected = ints(VectorArraySupport.INT_HEAP_THRESHOLD + 1);
		assertArrayEquals(expected, read(encode(new ArrayintSerializer(), expected),
				VectorArraySupport::readIntArray));
		assertFalse(cursor.isBound());
		assertThrows(IllegalStateException.class, cursor::directHeapArray);
		assertThrows(IllegalStateException.class, cursor::directMemorySegment);
	}

	private static <T> void assertAllStorages(Arena arena,
			Buf payload,
			T expected,
			ReaderCall<T> call,
			ArrayAssertion<T> assertion) {
		assertion.assertArray(expected, read(payload, call));

		byte[] padded = new byte[payload.size() + 11];
		MemorySegment.copy(payload.asMemorySegment(), 0, MemorySegment.ofArray(padded), 5, payload.size());
		assertion.assertArray(expected, read(Buf.wrap(padded), 5, payload.size(), call));
		assertion.assertArray(expected, read(Buf.wrap(padded).subList(5, 5 + payload.size()), call));

		MemorySegment nativeStorage = arena.allocate(payload.size() + 9L, 1);
		MemorySegment.copy(payload.asMemorySegment(), 0, nativeStorage, 3, payload.size());
		Buf nativeBuf = new NoConversionMemorySegmentBuf(nativeStorage);
		assertion.assertArray(expected, read(nativeBuf, 3, payload.size(), call));
		assertion.assertArray(expected, read(nativeBuf.subList(3, 3 + payload.size()), call));

		assertion.assertArray(expected, read(new VectorFallbackBuf(payload.asArray()), call));
	}

	private static <T> Buf encode(DataCodec<T> codec, T value) {
		BufDataOutput output = BufDataOutput.create();
		codec.serialize(output, value);
		return output.asList();
	}

	private static <T> T read(Buf source, ReaderCall<T> call) {
		return read(source, 0, source.size(), call);
	}

	private static <T> T read(Buf source, int offset, int length, ReaderCall<T> call) {
		BufDataCursor cursor = new BufDataCursor(DecodeLimits.unlimited());
		cursor.bind(source, offset, length);
		try {
			T value = call.read(cursor);
			assertEquals(0, cursor.remaining());
			return value;
		} finally {
			cursor.unbind();
			assertFalse(cursor.isBound());
		}
	}

	private static boolean[] booleans(int size) {
		boolean[] result = new boolean[size];
		for (int i = 0; i < size; i++) result[i] = (i * 17 & 3) != 0;
		return result;
	}

	private static byte[] bytes(int size) {
		byte[] result = new byte[size];
		for (int i = 0; i < size; i++) result[i] = (byte) (i * 73 - 111);
		return result;
	}

	private static short[] shorts(int size) {
		short[] result = new short[size];
		for (int i = 0; i < size; i++) result[i] = (short) (i * 12347 - 30001);
		return result;
	}

	private static char[] chars(int size) {
		char[] result = new char[size];
		for (int i = 0; i < size; i++) result[i] = (char) (i * 8191 + 0x80ff);
		return result;
	}

	private static int[] ints(int size) {
		int[] result = new int[size];
		for (int i = 0; i < size; i++) result[i] = Integer.rotateLeft(0x81234567 ^ i * 0x9e3779b9, i & 31);
		return result;
	}

	private static long[] longs(int size) {
		long[] result = new long[size];
		for (int i = 0; i < size; i++) {
			result[i] = Long.rotateLeft(0x8123456789abcdefL ^ i * 0x9e3779b97f4a7c15L, i & 63);
		}
		return result;
	}

	private static float[] floats(int size) {
		float[] result = new float[size];
		int[] bits = ints(size);
		for (int i = 0; i < size; i++) {
			float value = Float.intBitsToFloat(bits[i]);
			result[i] = Float.intBitsToFloat(Float.floatToIntBits(value));
		}
		return result;
	}

	private static double[] doubles(int size) {
		double[] result = new double[size];
		long[] bits = longs(size);
		for (int i = 0; i < size; i++) {
			double value = Double.longBitsToDouble(bits[i]);
			result[i] = Double.longBitsToDouble(Double.doubleToLongBits(value));
		}
		return result;
	}

	private static Int52[] int52s(int size) {
		Int52[] result = new Int52[size];
		for (int i = 0; i < size; i++) {
			long value = (i * 0x01020304050607L ^ 0x000f0e0d0c0b0a09L) & Int52.MAX_VALUE_L;
			result[i] = Int52.fromLong(value);
		}
		return result;
	}

	private static void assertBooleanArray(boolean[] expected, boolean[] actual) { assertArrayEquals(expected, actual); }
	private static void assertByteArray(byte[] expected, byte[] actual) { assertArrayEquals(expected, actual); }
	private static void assertShortArray(short[] expected, short[] actual) { assertArrayEquals(expected, actual); }
	private static void assertCharArray(char[] expected, char[] actual) { assertArrayEquals(expected, actual); }
	private static void assertIntArray(int[] expected, int[] actual) { assertArrayEquals(expected, actual); }
	private static void assertLongArray(long[] expected, long[] actual) { assertArrayEquals(expected, actual); }
	private static void assertInt52Array(Int52[] expected, Int52[] actual) { assertArrayEquals(expected, actual); }

	private static void assertFloatArrayBits(float[] expected, float[] actual) {
		assertArrayEquals(toRawBits(expected), toRawBits(actual));
	}

	private static void assertDoubleArrayBits(double[] expected, double[] actual) {
		assertArrayEquals(toRawBits(expected), toRawBits(actual));
	}

	private static int[] toRawBits(float[] values) {
		int[] result = new int[values.length];
		for (int i = 0; i < values.length; i++) result[i] = Float.floatToRawIntBits(values[i]);
		return result;
	}

	private static long[] toRawBits(double[] values) {
		long[] result = new long[values.length];
		for (int i = 0; i < values.length; i++) result[i] = Double.doubleToRawLongBits(values[i]);
		return result;
	}

	@FunctionalInterface
	private interface ReaderCall<T> {
		T read(SafeDataInput input);
	}

	@FunctionalInterface
	private interface ArrayAssertion<T> {
		void assertArray(T expected, T actual);
	}

	private static final class NoConversionMemorySegmentBuf extends MemorySegmentBuf {
		private NoConversionMemorySegmentBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Vector native source copied the complete payload");
		}

		@Override
		public SafeByteArrayInputStream binaryInputStream() {
			throw new AssertionError("Vector native source opened a payload stream");
		}
	}
}
