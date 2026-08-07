package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BufDataIoDeepFuzzTest {

	private static final DecodeLimits UNLIMITED = DecodeLimits.unlimited();
	private static final long CODEC_SEED = 0x6B12_8CF4_39A0_57DEL;
	private static final long CURSOR_SEED = 0x19E4_A72B_60CD_835FL;
	private static final long OUTPUT_SEED = 0x74B0_3F2D_91CE_68A5L;
	private static final long ARRAY_SEED = 0x42D8_17C9_E30A_65BFL;

	@Test
	void randomizedCodecTracesMatchDataOutputAndRoundTripEveryStorageKind() throws Exception {
		var random = new Random(CODEC_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < 128; caseIndex++) {
				var operations = new ArrayList<WireOperation>();
				var actual = BufDataOutput.create(random.nextInt(257));
				var expectedBytes = new ByteArrayOutputStream();
				try (var expected = new DataOutputStream(expectedBytes)) {
					int operationCount = 64 + random.nextInt(193);
					for (int operationIndex = 0; operationIndex < operationCount; operationIndex++) {
						WireOperation operation = WireOperation.random(random);
						operation.write(actual);
						operation.write(expected);
						operations.add(operation);
					}
				}

				byte[] expected = expectedBytes.toByteArray();
				String diagnostic = diagnostic(CODEC_SEED, caseIndex, -1, expected.length);
				assertArrayEquals(expected, actual.asList().asArray(), diagnostic);
				assertArrayEquals(expected, actual.toList().asArray(), diagnostic);

				for (BoundSource bound : storageKinds(arena, expected)) {
					var cursor = new BufDataCursor(UNLIMITED);
					cursor.bind(bound.source(), bound.offset(), expected.length);
					for (int operationIndex = 0; operationIndex < operations.size(); operationIndex++) {
						operations.get(operationIndex).readAndAssert(cursor,
								diagnostic(CODEC_SEED, caseIndex, operationIndex, expected.length)
										+ ", storage=" + bound.name());
					}
					assertEquals(0, cursor.remaining(), diagnostic + ", storage=" + bound.name());
					cursor.unbind();
				}
			}
		}
	}

	@Test
	void cursorStateMachineMatchesAByteArrayAndKeepsFailuresAtomic() {
		var random = new Random(CURSOR_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < 96; caseIndex++) {
				byte[] payload = new byte[random.nextInt(513)];
				random.nextBytes(payload);
				long caseSeed = random.nextLong();
				for (BoundSource bound : storageKinds(arena, payload)) {
					exerciseCursorStateMachine(payload, bound, caseSeed, caseIndex);
				}
			}
		}
	}

	@Test
	void limitedOutputStateMachineIsAtomicAndMatchesReferenceBytes() throws Exception {
		var random = new Random(OUTPUT_SEED);
		for (int caseIndex = 0; caseIndex < 96; caseIndex++) {
			int limit = random.nextInt(1025);
			int hint = switch (random.nextInt(5)) {
				case 0 -> -1;
				case 1 -> 0;
				case 2 -> limit;
				default -> random.nextInt(2049);
			};
			BufDataOutput output = BufDataOutput.createLimited(limit, hint);
			var model = new OutputModel();

			for (int operationIndex = 0; operationIndex < 750; operationIndex++) {
				int kind = random.nextInt(15);
				String diagnostic = diagnostic(OUTPUT_SEED, caseIndex, operationIndex, model.position())
						+ ", limit=" + limit + ", kind=" + kind;
				if (kind <= 11) {
					WireOperation operation = WireOperation.randomOfKind(random, kind);
					byte[] encoded = operation.encode();
					byte[] before = model.snapshot();
					if (encoded.length > limit - model.position()) {
						assertThrows(IndexOutOfBoundsException.class, () -> operation.write(output), diagnostic);
						assertArrayEquals(before, output.asList().asArray(), diagnostic);
					} else {
						operation.write(output);
						model.write(encoded);
					}
				} else if (kind == 12) {
					output.resetUnderlyingBuffer();
					model.reset();
				} else if (kind == 13) {
					int count = random.nextInt(model.position() + 1);
					output.rewindPosition(count);
					model.rewind(count);
				} else {
					byte[] before = model.snapshot();
					int invalidCount = model.position() + 1 + random.nextInt(16);
					assertThrows(IndexOutOfBoundsException.class,
							() -> output.rewindPosition(invalidCount), diagnostic);
					assertArrayEquals(before, output.asList().asArray(), diagnostic);
					byte[] source = new byte[8];
					assertThrows(IndexOutOfBoundsException.class,
							() -> output.write(source, -1, 1), diagnostic);
					assertArrayEquals(before, output.asList().asArray(), diagnostic);
				}

				assertEquals(model.position(), output.size(), diagnostic);
				assertEquals(model.position(), output.position(), diagnostic);
				assertArrayEquals(model.snapshot(), output.asList().asArray(), diagnostic);
			}
		}
	}

	@Test
	void randomizedPrimitiveArraysBulkReadIdenticallyAcrossStorageKinds() {
		var random = new Random(ARRAY_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < 256; caseIndex++) {
				boolean[] booleans = randomBooleans(random);
				byte[] bytes = randomBytes(random);
				short[] shorts = randomShorts(random);
				char[] chars = randomChars(random);
				int[] ints = randomInts(random);
				long[] longs = randomLongs(random);
				float[] floats = randomFloats(random);
				double[] doubles = randomDoubles(random);
				BufDataOutput output = BufDataOutput.create();
				for (boolean value : booleans) output.writeBoolean(value);
				output.write(bytes);
				for (short value : shorts) output.writeShort(value);
				for (char value : chars) output.writeChar(value);
				for (int value : ints) output.writeInt(value);
				for (long value : longs) output.writeLong(value);
				for (float value : floats) output.writeFloat(value);
				for (double value : doubles) output.writeDouble(value);
				byte[] payload = output.asList().asArray().clone();

				for (BoundSource bound : storageKinds(arena, payload)) {
					String diagnostic = diagnostic(ARRAY_SEED, caseIndex, -1, payload.length)
							+ ", storage=" + bound.name();
					var cursor = new BufDataCursor(UNLIMITED);
					cursor.bind(bound.source(), bound.offset(), payload.length);
					assertArrayEquals(booleans, cursor.readBooleanArray(booleans.length), diagnostic);
					assertArrayEquals(bytes, cursor.readByteArray(bytes.length), diagnostic);
					assertArrayEquals(shorts, cursor.readShortArray(shorts.length), diagnostic);
					assertArrayEquals(chars, cursor.readCharArray(chars.length), diagnostic);
					assertArrayEquals(ints, cursor.readIntArray(ints.length), diagnostic);
					assertArrayEquals(longs, cursor.readLongArray(longs.length), diagnostic);
					assertFloatBits(floats, cursor.readFloatArray(floats.length), diagnostic);
					assertDoubleBits(doubles, cursor.readDoubleArray(doubles.length), diagnostic);
					assertEquals(0, cursor.remaining(), diagnostic);
					cursor.unbind();
				}
			}
		}
	}

	private static void exerciseCursorStateMachine(byte[] payload,
			BoundSource bound,
			long seed,
			int caseIndex) {
		var random = new Random(seed);
		var cursor = new BufDataCursor(UNLIMITED);
		cursor.bind(bound.source(), bound.offset(), payload.length);
		int position = 0;
		int mark = 0;
		for (int operationIndex = 0; operationIndex < 750; operationIndex++) {
			int kind = random.nextInt(15);
			String diagnostic = diagnostic(seed, caseIndex, operationIndex, payload.length)
					+ ", storage=" + bound.name() + ", kind=" + kind + ", position=" + position;
			switch (kind) {
				case 0 -> {
					int expected = position == payload.length ? -1 : Byte.toUnsignedInt(payload[position++]);
					assertEquals(expected, cursor.read(), diagnostic);
				}
				case 1 -> {
					byte[] destination = new byte[2 + random.nextInt(65)];
					Arrays.fill(destination, (byte) 0x5a);
					int offset = random.nextInt(destination.length + 1);
					int length = random.nextInt(destination.length - offset + 1);
					int count = length == 0 ? 0 : position == payload.length ? -1
							: Math.min(length, payload.length - position);
					byte[] expected = destination.clone();
					if (count > 0) {
						System.arraycopy(payload, position, expected, offset, count);
						position += count;
					}
					assertEquals(count, cursor.read(destination, offset, length), diagnostic);
					assertArrayEquals(expected, destination, diagnostic);
				}
				case 2 -> {
					int requested = random.nextInt(payload.length + 17);
					int count = Math.min(requested, payload.length - position);
					assertArrayEquals(Arrays.copyOfRange(payload, position, position + count),
							cursor.readNBytes(requested), diagnostic);
					position += count;
				}
				case 3 -> {
					long requested = random.nextInt(payload.length + 33) - 16L;
					int count = requested <= 0 ? 0
							: (int) Math.min(requested, payload.length - position);
					assertEquals(count, cursor.skip(requested), diagnostic);
					position += count;
				}
				case 4 -> {
					cursor.mark(random.nextInt(1025));
					mark = position;
				}
				case 5 -> {
					cursor.reset();
					position = mark;
				}
				case 6 -> {
					position = random.nextInt(payload.length + 1);
					cursor.position(position);
				}
				case 7 -> {
					int requested = random.nextInt(payload.length + 17);
					int before = position;
					byte[] destination = new byte[requested];
					if (requested > payload.length - position) {
						assertThrows(MalformedDataException.class, () -> cursor.readFully(destination), diagnostic);
						assertEquals(before, cursor.position(), diagnostic);
					} else {
						cursor.readFully(destination);
						assertArrayEquals(Arrays.copyOfRange(payload, position, position + requested),
								destination, diagnostic);
						position += requested;
					}
				}
				case 8 -> position = readRandomPrimitive(payload, cursor, position, random, diagnostic);
				case 9 -> {
					int capacity = random.nextInt(65);
					ByteBuffer destination = ByteBuffer.allocate(capacity + 4);
					destination.position(2);
					int requested = random.nextInt(capacity + 1);
					int before = position;
					if (requested > payload.length - position) {
						assertThrows(MalformedDataException.class,
								() -> cursor.readFully(destination, requested), diagnostic);
						assertEquals(2, destination.position(), diagnostic);
						assertEquals(before, cursor.position(), diagnostic);
					} else {
						cursor.readFully(destination, requested);
						assertEquals(2 + requested, destination.position(), diagnostic);
						assertArrayEquals(Arrays.copyOfRange(payload, position, position + requested),
								Arrays.copyOfRange(destination.array(), 2, 2 + requested), diagnostic);
						position += requested;
					}
				}
				case 10 -> {
					int before = position;
					assertThrows(IndexOutOfBoundsException.class,
							() -> cursor.readFully(new byte[4], 3, 2), diagnostic);
					assertEquals(before, cursor.position(), diagnostic);
				}
				case 11 -> {
					int requested = switch (random.nextInt(5)) {
						case 0 -> -1;
						case 1 -> Integer.MAX_VALUE;
						default -> random.nextInt(payload.length + 17);
					};
					int before = position;
					if (requested < 0 || requested > payload.length - position) {
						assertThrows(MalformedDataException.class, () -> cursor.reserve(requested), diagnostic);
						assertEquals(before, cursor.position(), diagnostic);
					} else {
						assertEquals(position, cursor.reserve(requested), diagnostic);
						position += requested;
					}
				}
				case 12 -> {
					int before = position;
					assertThrows(IndexOutOfBoundsException.class,
							() -> cursor.position(payload.length + 1), diagnostic);
					assertEquals(before, cursor.position(), diagnostic);
				}
				case 13 -> {
					int from = random.nextInt(payload.length + 1);
					int length = random.nextInt(payload.length - from + 1);
					int before = position;
					var child = BufDataCursor.borrowed();
					cursor.bindRegion(child, from, length);
					assertArrayEquals(Arrays.copyOfRange(payload, from, from + length), child.readAllBytes(), diagnostic);
					child.unbind();
					assertEquals(before, cursor.position(), diagnostic);
				}
				case 14 -> {
					int before = position;
					assertThrows(IllegalArgumentException.class, () -> cursor.mark(-1), diagnostic);
					assertEquals(before, cursor.position(), diagnostic);
				}
				default -> throw new AssertionError(kind);
			}
			assertEquals(position, cursor.position(), diagnostic);
			assertEquals(payload.length - position, cursor.remaining(), diagnostic);
		}
		cursor.unbind();
	}

	private static int readRandomPrimitive(byte[] payload,
			BufDataCursor cursor,
			int position,
			Random random,
			String diagnostic) {
		int primitive = random.nextInt(9);
		int width = switch (primitive) {
			case 0, 1 -> 1;
			case 2, 3 -> 2;
			case 4, 5 -> 4;
			case 6, 8 -> 8;
			case 7 -> 7;
			default -> throw new AssertionError(primitive);
		};
		int before = position;
		if (width > payload.length - position) {
			assertThrows(MalformedDataException.class,
					() -> readPrimitive(cursor, primitive), diagnostic);
			assertEquals(before, cursor.position(), diagnostic);
			return position;
		}
		ByteBuffer expected = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
		switch (primitive) {
			case 0 -> assertEquals(payload[position] != 0, cursor.readBoolean(), diagnostic);
			case 1 -> assertEquals(Byte.toUnsignedInt(payload[position]), cursor.readUnsignedByte(), diagnostic);
			case 2 -> assertEquals(expected.getShort(position), cursor.readShort(), diagnostic);
			case 3 -> assertEquals(expected.getChar(position), cursor.readChar(), diagnostic);
			case 4 -> assertEquals(expected.getInt(position), cursor.readInt(), diagnostic);
			case 5 -> assertEquals(expected.getInt(position),
					Float.floatToRawIntBits(cursor.readFloat()), diagnostic);
			case 6 -> assertEquals(expected.getLong(position), cursor.readLong(), diagnostic);
			case 7 -> assertEquals(readInt52(payload, position), cursor.readInt52(), diagnostic);
			case 8 -> assertEquals(expected.getLong(position),
					Double.doubleToRawLongBits(cursor.readDouble()), diagnostic);
			default -> throw new AssertionError(primitive);
		}
		return position + width;
	}

	private static void readPrimitive(BufDataCursor cursor, int primitive) {
		switch (primitive) {
			case 0 -> cursor.readBoolean();
			case 1 -> cursor.readUnsignedByte();
			case 2 -> cursor.readShort();
			case 3 -> cursor.readChar();
			case 4 -> cursor.readInt();
			case 5 -> cursor.readFloat();
			case 6 -> cursor.readLong();
			case 7 -> cursor.readInt52();
			case 8 -> cursor.readDouble();
			default -> throw new AssertionError(primitive);
		}
	}

	private static List<BoundSource> storageKinds(Arena arena, byte[] payload) {
		byte[] paddedHeap = new byte[payload.length + 6];
		System.arraycopy(payload, 0, paddedHeap, 3, payload.length);
		MemorySegment paddedNative = arena.allocate(Math.max(1, payload.length + 6L), 1);
		if (payload.length != 0) {
			MemorySegment.copy(MemorySegment.ofArray(payload), 0, paddedNative, 3, payload.length);
		}
		return List.of(
				new BoundSource("heap-offset", Buf.wrap(paddedHeap), 3),
				new BoundSource("heap-nested-slice",
						Buf.wrap(paddedHeap).subListForced(1, payload.length + 5)
								.subListForced(2, payload.length + 2), 0),
				new BoundSource("native-offset", new StrictNativeBuf(paddedNative), 3),
				new BoundSource("fallback", new FallbackBuf(paddedHeap), 3));
	}

	private static boolean[] randomBooleans(Random random) {
		boolean[] result = new boolean[random.nextInt(65)];
		for (int index = 0; index < result.length; index++) result[index] = random.nextBoolean();
		return result;
	}

	private static byte[] randomBytes(Random random) {
		byte[] result = new byte[random.nextInt(65)];
		random.nextBytes(result);
		return result;
	}

	private static short[] randomShorts(Random random) {
		short[] result = new short[random.nextInt(65)];
		for (int index = 0; index < result.length; index++) result[index] = (short) random.nextInt();
		return result;
	}

	private static char[] randomChars(Random random) {
		char[] result = new char[random.nextInt(65)];
		for (int index = 0; index < result.length; index++) result[index] = (char) random.nextInt();
		return result;
	}

	private static int[] randomInts(Random random) {
		int[] result = new int[random.nextInt(65)];
		for (int index = 0; index < result.length; index++) result[index] = random.nextInt();
		return result;
	}

	private static long[] randomLongs(Random random) {
		long[] result = new long[random.nextInt(65)];
		for (int index = 0; index < result.length; index++) result[index] = random.nextLong();
		return result;
	}

	private static float[] randomFloats(Random random) {
		float[] result = new float[random.nextInt(65)];
		for (int index = 0; index < result.length; index++) {
			result[index] = Float.intBitsToFloat(random.nextInt());
		}
		return result;
	}

	private static double[] randomDoubles(Random random) {
		double[] result = new double[random.nextInt(65)];
		for (int index = 0; index < result.length; index++) {
			result[index] = Double.longBitsToDouble(random.nextLong());
		}
		return result;
	}

	private static void assertFloatBits(float[] expected, float[] actual, String diagnostic) {
		assertEquals(expected.length, actual.length, diagnostic);
		for (int index = 0; index < expected.length; index++) {
			assertEquals(Float.floatToIntBits(expected[index]), Float.floatToRawIntBits(actual[index]),
					diagnostic + ", element=" + index);
		}
	}

	private static void assertDoubleBits(double[] expected, double[] actual, String diagnostic) {
		assertEquals(expected.length, actual.length, diagnostic);
		for (int index = 0; index < expected.length; index++) {
			assertEquals(Double.doubleToLongBits(expected[index]), Double.doubleToRawLongBits(actual[index]),
					diagnostic + ", element=" + index);
		}
	}

	private static long readInt52(byte[] bytes, int offset) {
		return ((long) bytes[offset] & 0x0fL) << 48
				| ((long) bytes[offset + 1] & 0xffL) << 40
				| ((long) bytes[offset + 2] & 0xffL) << 32
				| ((long) bytes[offset + 3] & 0xffL) << 24
				| ((long) bytes[offset + 4] & 0xffL) << 16
				| ((long) bytes[offset + 5] & 0xffL) << 8
				| ((long) bytes[offset + 6] & 0xffL);
	}

	private static byte[] toByteArray(List<Byte> values) {
		byte[] result = new byte[values.size()];
		for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
		return result;
	}

	private static final class OutputModel {

		private final ArrayList<Byte> bytes = new ArrayList<>();
		private int position;

		private int position() {
			return position;
		}

		private void write(byte[] encoded) {
			for (byte value : encoded) {
				if (position < bytes.size()) {
					bytes.set(position, value);
				} else {
					bytes.add(value);
				}
				position++;
			}
		}

		private void rewind(int count) {
			position -= count;
		}

		private void reset() {
			bytes.clear();
			position = 0;
		}

		private byte[] snapshot() {
			return toByteArray(bytes);
		}
	}

	private static String randomString(Random random) {
		int length = random.nextInt(65);
		var result = new StringBuilder(length);
		for (int index = 0; index < length; index++) {
			int kind = random.nextInt(8);
			result.append(switch (kind) {
				case 0 -> '\0';
				case 1 -> (char) (0x80 + random.nextInt(0x780));
				case 2 -> (char) (0xd800 + random.nextInt(0x800));
				default -> (char) (32 + random.nextInt(95));
			});
		}
		return result.toString();
	}

	private static String diagnostic(long seed, int caseIndex, int operation, int size) {
		return "seed=" + seed + ", case=" + caseIndex + ", operation=" + operation + ", size=" + size;
	}

	private record BoundSource(String name, Buf source, int offset) {}

	private record WireOperation(int kind, long bits, byte[] bytes, String text) {

		private static WireOperation random(Random random) {
			return randomOfKind(random, random.nextInt(12));
		}

		private static WireOperation randomOfKind(Random random, int kind) {
			return switch (kind) {
				case 0 -> new WireOperation(kind, random.nextBoolean() ? 1 : 0, null, null);
				case 1, 2, 3, 4, 7 -> new WireOperation(kind, random.nextLong(), null, null);
				case 5, 6, 8 -> new WireOperation(kind, random.nextLong(), null, null);
				case 9 -> {
					byte[] bytes = new byte[random.nextInt(129)];
					random.nextBytes(bytes);
					yield new WireOperation(kind, 0, bytes, null);
				}
				case 10, 11 -> new WireOperation(kind, 0, null, randomString(random));
				default -> throw new AssertionError(kind);
			};
		}

		private void write(BufDataOutput output) {
			switch (kind) {
				case 0 -> output.writeBoolean(bits != 0);
				case 1 -> output.writeByte((int) bits);
				case 2 -> output.writeShort((int) bits);
				case 3 -> output.writeChar((int) bits);
				case 4 -> output.writeInt((int) bits);
				case 5 -> output.writeLong(bits);
				case 6 -> output.writeInt52(bits);
				case 7 -> output.writeFloat(Float.intBitsToFloat((int) bits));
				case 8 -> output.writeDouble(Double.longBitsToDouble(bits));
				case 9 -> output.write(bytes);
				case 10 -> output.writeShortText(text, StandardCharsets.UTF_8);
				case 11 -> output.writeMediumText(text, StandardCharsets.UTF_8);
				default -> throw new AssertionError(kind);
			}
		}

		private void write(DataOutputStream output) throws Exception {
			switch (kind) {
				case 0 -> output.writeBoolean(bits != 0);
				case 1 -> output.writeByte((int) bits);
				case 2 -> output.writeShort((int) bits);
				case 3 -> output.writeChar((int) bits);
				case 4 -> output.writeInt((int) bits);
				case 5 -> output.writeLong(bits);
				case 6 -> {
					output.writeByte((int) (bits >>> 48) & 0x0f);
					output.writeByte((int) (bits >>> 40));
					output.writeByte((int) (bits >>> 32));
					output.writeByte((int) (bits >>> 24));
					output.writeByte((int) (bits >>> 16));
					output.writeByte((int) (bits >>> 8));
					output.writeByte((int) bits);
				}
				case 7 -> output.writeFloat(Float.intBitsToFloat((int) bits));
				case 8 -> output.writeDouble(Double.longBitsToDouble(bits));
				case 9 -> output.write(bytes);
				case 10 -> {
					byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
					output.writeShort(encoded.length);
					output.write(encoded);
				}
				case 11 -> {
					byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
					output.writeInt(encoded.length);
					output.write(encoded);
				}
				default -> throw new AssertionError(kind);
			}
		}

		private byte[] encode() throws Exception {
			var bytes = new ByteArrayOutputStream();
			try (var output = new DataOutputStream(bytes)) {
				write(output);
			}
			return bytes.toByteArray();
		}

		private void readAndAssert(BufDataCursor input, String diagnostic) {
			switch (kind) {
				case 0 -> assertEquals(bits != 0, input.readBoolean(), diagnostic);
				case 1 -> assertEquals((byte) bits, input.readByte(), diagnostic);
				case 2 -> assertEquals((short) bits, input.readShort(), diagnostic);
				case 3 -> assertEquals((char) bits, input.readChar(), diagnostic);
				case 4 -> assertEquals((int) bits, input.readInt(), diagnostic);
				case 5 -> assertEquals(bits, input.readLong(), diagnostic);
				case 6 -> assertEquals(bits & 0x000f_ffff_ffff_ffffL, input.readInt52(), diagnostic);
				case 7 -> assertEquals(Float.floatToIntBits(Float.intBitsToFloat((int) bits)),
						Float.floatToRawIntBits(input.readFloat()), diagnostic);
				case 8 -> assertEquals(Double.doubleToLongBits(Double.longBitsToDouble(bits)),
						Double.doubleToRawLongBits(input.readDouble()), diagnostic);
				case 9 -> {
					byte[] actual = new byte[bytes.length];
					input.readFully(actual);
					assertArrayEquals(bytes, actual, diagnostic);
				}
				case 10 -> assertEquals(utf8RoundTrip(text),
						input.readShortText(StandardCharsets.UTF_8), diagnostic);
				case 11 -> assertEquals(utf8RoundTrip(text),
						input.readMediumText(StandardCharsets.UTF_8), diagnostic);
				default -> throw new AssertionError(kind);
			}
		}

		private static String utf8RoundTrip(String value) {
			return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		}
	}

	private static final class StrictNativeBuf extends MemorySegmentBuf {

		private StrictNativeBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Native cursor copied the complete payload");
		}
	}

	private static final class FallbackBuf extends ByteListBuf {

		private FallbackBuf(byte[] data) {
			super(data);
		}

		@Override
		public byte[] getBackingByteArrayStrict() {
			return null;
		}

		@Override
		public MemorySegment asMemorySegmentStrict() {
			return null;
		}

		@Override
		public byte[] getBackingByteArray() {
			throw new AssertionError("Fallback cursor requested heap storage");
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("Fallback cursor copied the complete payload");
		}
	}
}
