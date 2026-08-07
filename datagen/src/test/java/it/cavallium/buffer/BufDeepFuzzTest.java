package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BufDeepFuzzTest {

	private static final long FIXED_STATE_SEED = 0x46A1_7B2C_09DE_5834L;
	private static final long DYNAMIC_STATE_SEED = 0x71C5_3A80_2E4B_69DFL;
	private static final long RANGE_SEED = 0x2D90_64BE_173A_5CF8L;
	private static final int FIXED_CASES = 64;
	private static final int OPERATIONS_PER_FIXED_CASE = 1_000;
	private static final int DYNAMIC_OPERATIONS = 50_000;
	private static final int RANGE_CASES = 50_000;

	@Test
	void fixedSizeMutationStateMachineMatchesByteArrayForHeapAndNativeStorage() {
		var random = new Random(FIXED_STATE_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < FIXED_CASES; caseIndex++) {
				int size = 8 + random.nextInt(505);
				byte[] model = new byte[size];
				random.nextBytes(model);
				Buf heap = Buf.wrap(model.clone());
				Buf nativeBuf = nativeBuf(arena, model);

				for (int operation = 0; operation < OPERATIONS_PER_FIXED_CASE; operation++) {
					int kind = random.nextInt(14);
					String diagnostic = diagnostic(FIXED_STATE_SEED, caseIndex, operation, kind, size);
					switch (kind) {
						case 0 -> {
							int offset = random.nextInt(size);
							byte value = (byte) random.nextInt();
							heap.setByte(offset, value);
							nativeBuf.setByte(offset, value);
							model[offset] = value;
						}
						case 1 -> {
							int offset = offset(random, size, Short.BYTES);
							short value = (short) random.nextInt();
							heap.setShort(offset, value);
							nativeBuf.setShort(offset, value);
							ByteBuffer.wrap(model).putShort(offset, value);
						}
						case 2 -> {
							int offset = offset(random, size, Character.BYTES);
							char value = (char) random.nextInt();
							heap.setChar(offset, value);
							nativeBuf.setChar(offset, value);
							ByteBuffer.wrap(model).putChar(offset, value);
						}
						case 3 -> {
							int offset = offset(random, size, Integer.BYTES);
							int value = random.nextInt();
							heap.setInt(offset, value);
							nativeBuf.setInt(offset, value);
							ByteBuffer.wrap(model).putInt(offset, value);
						}
						case 4 -> {
							int offset = offset(random, size, Integer.BYTES);
							int value = random.nextInt();
							heap.setIntLE(offset, value);
							nativeBuf.setIntLE(offset, value);
							ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
						}
						case 5 -> {
							int offset = offset(random, size, Long.BYTES);
							long value = random.nextLong();
							heap.setLong(offset, value);
							nativeBuf.setLong(offset, value);
							ByteBuffer.wrap(model).putLong(offset, value);
						}
						case 6 -> {
							int offset = offset(random, size, Float.BYTES);
							float value = Float.intBitsToFloat(random.nextInt());
							heap.setFloat(offset, value);
							nativeBuf.setFloat(offset, value);
							ByteBuffer.wrap(model).putInt(offset, Float.floatToRawIntBits(value));
						}
						case 7 -> {
							int offset = offset(random, size, Double.BYTES);
							double value = Double.longBitsToDouble(random.nextLong());
							heap.setDouble(offset, value);
							nativeBuf.setDouble(offset, value);
							ByteBuffer.wrap(model).putLong(offset, Double.doubleToRawLongBits(value));
						}
						case 8 -> {
							int length = random.nextInt(size + 1);
							int sourceOffset = random.nextInt(size - length + 1);
							int targetOffset = random.nextInt(size - length + 1);
							heap.setBytesFromBuf(targetOffset, heap, sourceOffset, length);
							nativeBuf.setBytesFromBuf(targetOffset, nativeBuf, sourceOffset, length);
							System.arraycopy(model, sourceOffset, model, targetOffset, length);
						}
						case 9 -> {
							byte[] sourceBytes = new byte[random.nextInt(257)];
							random.nextBytes(sourceBytes);
							Buf source = randomView(random, arena, sourceBytes);
							int length = random.nextInt(Math.min(size, source.size()) + 1);
							int sourceOffset = random.nextInt(source.size() - length + 1);
							int targetOffset = random.nextInt(size - length + 1);
							heap.setBytesFromBuf(targetOffset, source, sourceOffset, length);
							nativeBuf.setBytesFromBuf(targetOffset, source, sourceOffset, length);
							System.arraycopy(sourceBytes, sourceOffset, model, targetOffset, length);
						}
						case 10 -> {
							int from = random.nextInt(size);
							int to = from + 1 + random.nextInt(size - from);
							int relativeOffset = random.nextInt(to - from);
							byte value = (byte) random.nextInt();
							heap.subList(from, to).setByte(relativeOffset, value);
							nativeBuf.subList(from, to).setByte(relativeOffset, value);
							model[from + relativeOffset] = value;
						}
						case 11 -> {
							int from = random.nextInt(size);
							int to = from + 1 + random.nextInt(size - from);
							int relativeOffset = random.nextInt(to - from);
							byte value = (byte) random.nextInt();
							ByteBuffer heapView = heap.subList(from, to).asByteBuffer();
							ByteBuffer nativeView = nativeBuf.subList(from, to).asByteBuffer();
							assertEquals(0, heapView.position(), diagnostic);
							assertEquals(to - from, heapView.remaining(), diagnostic);
							assertEquals(0, nativeView.position(), diagnostic);
							assertEquals(to - from, nativeView.remaining(), diagnostic);
							heapView.put(relativeOffset, value);
							nativeView.put(relativeOffset, value);
							model[from + relativeOffset] = value;
						}
						case 12 -> assertRandomRangeViews(model, heap, nativeBuf, random, diagnostic);
						case 13 -> assertRandomPrimitiveReads(model, heap, nativeBuf, random, diagnostic);
						default -> throw new AssertionError(kind);
					}
					assertArrayEquals(model, heap.asArray(), diagnostic);
					assertArrayEquals(model, nativeBuf.asArray(), diagnostic);
				}
			}
		}
	}

	@Test
	void nestedHeapSublistMutationStateMachineMatchesArrayList() {
		var random = new Random(DYNAMIC_STATE_SEED);
		byte[] initial = new byte[32];
		random.nextBytes(initial);
		Buf root = Buf.wrap(initial.clone());
		var model = new ArrayList<Byte>(initial.length);
		for (byte value : initial) model.add(value);

		for (int operation = 0; operation < DYNAMIC_OPERATIONS; operation++) {
			int kind = random.nextInt(9);
			int from = random.nextInt(model.size() + 1);
			int to = from + random.nextInt(model.size() - from + 1);
			Buf view = root.subListForced(from, to);
			List<Byte> modelView = model.subList(from, to);
			String diagnostic = diagnostic(DYNAMIC_STATE_SEED, 0, operation, kind, model.size());

			switch (kind) {
				case 0 -> {
					if (model.size() < 512) {
						int index = random.nextInt(view.size() + 1);
						byte value = (byte) random.nextInt();
						view.add(index, value);
						modelView.add(index, value);
					}
				}
				case 1 -> {
					if (!modelView.isEmpty()) {
						int index = random.nextInt(view.size());
						assertEquals(modelView.remove(index).byteValue(), view.removeByte(index), diagnostic);
					}
				}
				case 2 -> {
					if (!modelView.isEmpty()) {
						int index = random.nextInt(view.size());
						byte value = (byte) random.nextInt();
						assertEquals(modelView.set(index, value).byteValue(), view.set(index, value), diagnostic);
					}
				}
				case 3 -> {
					view.clear();
					modelView.clear();
				}
				case 4 -> {
					int newSize = random.nextInt(513);
					root.size(newSize);
					while (model.size() > newSize) model.removeLast();
					while (model.size() < newSize) model.add((byte) 0);
				}
				case 5 -> {
					if (!modelView.isEmpty()) {
						byte[] source = new byte[view.size()];
						random.nextBytes(source);
						int length = random.nextInt(source.length + 1);
						int sourceOffset = random.nextInt(source.length - length + 1);
						int targetOffset = random.nextInt(view.size() - length + 1);
						view.setBytesFromBuf(targetOffset, Buf.wrap(source), sourceOffset, length);
						for (int index = 0; index < length; index++) {
							modelView.set(targetOffset + index, source[sourceOffset + index]);
						}
					}
				}
				case 6, 7, 8 -> mutateNestedView(root, model, from, to, random, kind, diagnostic);
				default -> throw new AssertionError(kind);
			}

			assertArrayEquals(toByteArray(model), root.asArray(), diagnostic);
			if (!model.isEmpty()) {
				int checkFrom = random.nextInt(model.size());
				int checkTo = checkFrom + random.nextInt(model.size() - checkFrom + 1);
				assertArrayEquals(Arrays.copyOfRange(toByteArray(model), checkFrom, checkTo),
						root.subListForced(checkFrom, checkTo).asArray(), diagnostic);
			}
		}
	}

	@Test
	void rangeEqualityAndOrderingMatchOneReferenceAcrossStorageKinds() {
		var random = new Random(RANGE_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int iteration = 0; iteration < RANGE_CASES; iteration++) {
				byte[] leftBytes = new byte[random.nextInt(257)];
				byte[] rightBytes = new byte[random.nextInt(257)];
				random.nextBytes(leftBytes);
				random.nextBytes(rightBytes);
				Buf left = randomView(random, arena, leftBytes);
				Buf right = randomView(random, arena, rightBytes);
				int leftOffset = rangeComponent(random, left.size());
				int rightOffset = rangeComponent(random, right.size());
				int length = rangeComponent(random, Math.max(left.size(), right.size()));
				String diagnostic = diagnostic(RANGE_SEED, 0, iteration, 0,
						left.size() + right.size()) + ", leftOffset=" + leftOffset
						+ ", rightOffset=" + rightOffset + ", length=" + length;
				boolean expected = rangeEquals(leftBytes, leftOffset, rightBytes, rightOffset, length);

				boolean bufActual = assertDoesNotThrow(
						() -> left.equals(leftOffset, right, rightOffset, length), diagnostic);
				boolean arrayActual = assertDoesNotThrow(
						() -> left.equals(leftOffset, rightBytes, rightOffset, length), diagnostic);
				assertEquals(expected, bufActual, diagnostic);
				assertEquals(expected, arrayActual, diagnostic);

				int expectedOrder = leftBytes.length != rightBytes.length
						? Integer.compare(leftBytes.length, rightBytes.length)
						: Arrays.compareUnsigned(leftBytes, rightBytes);
				assertEquals(Integer.signum(expectedOrder), Integer.signum(left.compareTo(right)), diagnostic);
				assertEquals(-Integer.signum(expectedOrder), Integer.signum(right.compareTo(left)), diagnostic);
				assertEquals(Arrays.equals(leftBytes, rightBytes), left.equals(right), diagnostic);
				if (Arrays.equals(leftBytes, rightBytes)) {
					assertEquals(left.hashCode(), right.hashCode(), diagnostic);
				}
			}
		}
	}

	@Test
	void frozenRandomSlicesRemainZeroCopyAndRejectEveryMutationPath() {
		var random = new Random(0x5E40_18C7_3B2D_69AFL);
		try (var arena = Arena.ofConfined()) {
			for (int iteration = 0; iteration < 10_000; iteration++) {
				byte[] values = new byte[1 + random.nextInt(256)];
				random.nextBytes(values);
				int from = random.nextInt(values.length);
				int to = from + 1 + random.nextInt(values.length - from);
				byte replacement = (byte) (values[from] + 1);

				byte[] heapStorage = values.clone();
				Buf heapFrozen = Buf.wrap(heapStorage).subList(from, to).freeze();
				heapStorage[from] = replacement;
				assertFrozenView(heapFrozen, replacement);

				Buf nativeMutable = nativeBuf(arena, values);
				Buf nativeFrozen = nativeMutable.subList(from, to).freeze();
				nativeMutable.setByte(from, replacement);
				assertFrozenView(nativeFrozen, replacement);
			}
		}
	}

	private static void assertFrozenView(Buf frozen, byte expectedFirstByte) {
		assertEquals(expectedFirstByte, frozen.getByte(0));
		assertFalse(frozen.isMutable());
		assertThrows(UnsupportedOperationException.class,
				() -> frozen.setByte(0, (byte) 1));
		assertThrows(UnsupportedOperationException.class,
				() -> frozen.setBytesFromBuf(0, Buf.wrap((byte) 1), 0, 1));
	}

	private static void mutateNestedView(Buf root,
			ArrayList<Byte> model,
			int from,
			int to,
			Random random,
			int kind,
			String diagnostic) {
		Buf view = root.subListForced(from, to);
		List<Byte> modelView = model.subList(from, to);
		int nestedFrom = random.nextInt(view.size() + 1);
		int nestedTo = nestedFrom + random.nextInt(view.size() - nestedFrom + 1);
		Buf nested = view.subList(nestedFrom, nestedTo);
		List<Byte> nestedModel = modelView.subList(nestedFrom, nestedTo);
		if (kind == 6 && model.size() < 512) {
			int index = random.nextInt(nested.size() + 1);
			byte value = (byte) random.nextInt();
			nested.add(index, value);
			nestedModel.add(index, value);
		} else if (kind == 7 && !nestedModel.isEmpty()) {
			int index = random.nextInt(nested.size());
			assertEquals(nestedModel.remove(index).byteValue(), nested.removeByte(index), diagnostic);
		} else if (kind == 8 && !nestedModel.isEmpty()) {
			int index = random.nextInt(nested.size());
			byte value = (byte) random.nextInt();
			assertEquals(nestedModel.set(index, value).byteValue(), nested.set(index, value), diagnostic);
		}
	}

	private static void assertRandomRangeViews(byte[] model,
			Buf heap,
			Buf nativeBuf,
			Random random,
			String diagnostic) {
		int from = random.nextInt(model.length + 1);
		int to = from + random.nextInt(model.length - from + 1);
		byte[] expected = Arrays.copyOfRange(model, from, to);
		for (Buf buf : List.of(heap, nativeBuf)) {
			Buf slice = buf.subListForced(from, to);
			assertArrayEquals(expected, slice.asArray(), diagnostic);
			assertArrayEquals(expected, slice.copy().asArray(), diagnostic);
			assertArrayEquals(expected, buf.copyOfRange(from, to).asArray(), diagnostic);
			assertArrayEquals(expected, slice.asMemorySegment().toArray(ValueLayout.JAVA_BYTE), diagnostic);
		}
	}

	private static void assertRandomPrimitiveReads(byte[] model,
			Buf heap,
			Buf nativeBuf,
			Random random,
			String diagnostic) {
		int kind = random.nextInt(7);
		int width = switch (kind) {
			case 0, 1 -> Short.BYTES;
			case 2, 3, 4 -> Integer.BYTES;
			case 5, 6 -> Long.BYTES;
			default -> throw new AssertionError(kind);
		};
		int offset = offset(random, model.length, width);
		ByteBuffer expected = ByteBuffer.wrap(model);
		for (Buf buf : List.of(heap, nativeBuf)) {
			switch (kind) {
				case 0 -> assertEquals(expected.getShort(offset), buf.getShort(offset), diagnostic);
				case 1 -> assertEquals(expected.getChar(offset), buf.getChar(offset), diagnostic);
				case 2 -> assertEquals(expected.getInt(offset), buf.getInt(offset), diagnostic);
				case 3 -> assertEquals(ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN).getInt(offset),
						buf.getIntLE(offset), diagnostic);
				case 4 -> assertEquals(Float.floatToRawIntBits(expected.getFloat(offset)),
						Float.floatToRawIntBits(buf.getFloat(offset)), diagnostic);
				case 5 -> assertEquals(expected.getLong(offset), buf.getLong(offset), diagnostic);
				case 6 -> assertEquals(Double.doubleToRawLongBits(expected.getDouble(offset)),
						Double.doubleToRawLongBits(buf.getDouble(offset)), diagnostic);
				default -> throw new AssertionError(kind);
			}
		}
	}

	private static Buf randomView(Random random, Arena arena, byte[] values) {
		return switch (random.nextInt(5)) {
			case 0 -> Buf.wrap(values.clone());
			case 1 -> {
				byte[] padded = new byte[values.length + 2];
				System.arraycopy(values, 0, padded, 1, values.length);
				yield Buf.wrap(padded).subListForced(1, 1 + values.length);
			}
			case 2 -> {
				byte[] padded = new byte[values.length + 4];
				System.arraycopy(values, 0, padded, 2, values.length);
				yield Buf.wrap(padded).subListForced(1, 3 + values.length)
						.subListForced(1, 1 + values.length);
			}
			case 3 -> nativeBuf(arena, values);
			case 4 -> {
				byte[] padded = new byte[values.length + 2];
				System.arraycopy(values, 0, padded, 1, values.length);
				yield nativeBuf(arena, padded).subListForced(1, 1 + values.length);
			}
			default -> throw new AssertionError();
		};
	}

	private static Buf nativeBuf(Arena arena, byte[] values) {
		MemorySegment segment = arena.allocate(Math.max(1, values.length), 1);
		if (values.length != 0) {
			MemorySegment.copy(MemorySegment.ofArray(values), 0, segment, 0, values.length);
		}
		return new MemorySegmentBuf(segment.asSlice(0, values.length));
	}

	private static boolean rangeEquals(byte[] left, int leftOffset, byte[] right, int rightOffset, int length) {
		if (!validRange(leftOffset, length, left.length)
				|| !validRange(rightOffset, length, right.length)) {
			return false;
		}
		return Arrays.equals(left, leftOffset, leftOffset + length,
				right, rightOffset, rightOffset + length);
	}

	private static boolean validRange(int offset, int length, int size) {
		return offset >= 0 && length >= 0 && (long) offset + length <= size;
	}

	private static int rangeComponent(Random random, int size) {
		return switch (random.nextInt(12)) {
			case 0 -> Integer.MIN_VALUE;
			case 1 -> -1;
			case 2 -> 0;
			case 3 -> 1;
			case 4 -> Math.max(0, size - 1);
			case 5 -> size;
			case 6 -> size + 1;
			case 7 -> Integer.MAX_VALUE;
			default -> random.nextInt();
		};
	}

	private static int offset(Random random, int size, int width) {
		return random.nextInt(size - width + 1);
	}

	private static byte[] toByteArray(List<Byte> values) {
		byte[] result = new byte[values.size()];
		for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
		return result;
	}

	private static String diagnostic(long seed,
			int caseIndex,
			int operation,
			int kind,
			int size) {
		return "seed=" + seed + ", case=" + caseIndex + ", operation=" + operation
				+ ", kind=" + kind + ", size=" + size;
	}
}
