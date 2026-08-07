package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ValueTooLargeException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Fuzzes cursor ownership, decode accounting, direct storage, and text streaming as one system. */
class BufCursorBudgetTextDeepFuzzTest {

	private static final DecodeLimits UNLIMITED = DecodeLimits.unlimited();
	private static final long DISPATCH_SEED = 0x3A8D_71F4_C209_5E6BL;
	private static final long BUDGET_SEED = 0x6E15_BC83_40A9_D27FL;
	private static final long BUFFER_SEED = 0x1F94_6DA0_B378_2CE5L;
	private static final long TEXT_SEED = 0x52B7_0C3D_EA16_894FL;
	private static final int DISPATCH_CASES = 20_000;
	private static final int BUDGET_CASES = 50_000;
	private static final int BUFFER_CASES = 20_000;
	private static final int TEXT_CASES = 20_000;

	@Test
	void specializedDispatchDirectOffsetsAndWrongStorageRejectionsFuzzAllCursorClasses() {
		var random = new Random(DISPATCH_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < DISPATCH_CASES; caseIndex++) {
				byte[] payload = randomBytes(random, 513);
				byte[] padded = new byte[payload.length + 12];
				int padding = 1 + random.nextInt(6);
				System.arraycopy(payload, 0, padded, padding, payload.length);
				MemorySegment segment = arena.allocate(Math.max(1, padded.length), 1);
				MemorySegment.copy(MemorySegment.ofArray(padded), 0, segment, 0, padded.length);
				List<StorageCase> storages = List.of(
						new StorageCase("heap-root", Buf.wrap(padded), padding,
								BufDataCursor.StorageKind.HEAP, padding),
						new StorageCase("heap-slice", Buf.wrap(padded).subListForced(padding,
								padding + payload.length), 0, BufDataCursor.StorageKind.HEAP, padding),
						new StorageCase("heap-segment", new MemorySegmentBuf(MemorySegment.ofArray(padded)),
								padding, BufDataCursor.StorageKind.MEMORY_SEGMENT, padding),
						new StorageCase("native", new MemorySegmentBuf(segment), padding,
								BufDataCursor.StorageKind.MEMORY_SEGMENT, padding),
						new StorageCase("fallback", forcedFallback(Buf.wrap(padded)), padding,
								BufDataCursor.StorageKind.FALLBACK, -1));

				for (StorageCase storage : storages) {
					var heap = new HeapBufDataCursor(UNLIMITED);
					var memorySegment = new MemorySegmentBufDataCursor(UNLIMITED);
					var fallback = new FallbackBufDataCursor(UNLIMITED);
					BufDataCursor.StorageKind kind = BufDataCursor.bindSpecialized(storage.source(),
							storage.offset(), payload.length, heap, memorySegment, fallback);
					String diagnostic = "seed=" + DISPATCH_SEED + ", case=" + caseIndex
							+ ", storage=" + storage.name() + ", length=" + payload.length;
					assertEquals(storage.kind(), kind, diagnostic);
					BufDataCursor selected = switch (kind) {
						case HEAP -> heap;
						case MEMORY_SEGMENT -> memorySegment;
						case FALLBACK -> fallback;
					};
					assertTrue(selected.isBound(), diagnostic);
					assertEquals(payload.length, selected.length(), diagnostic);
					assertEquals(payload.length, selected.remaining(), diagnostic);
					assertEquals(kind == BufDataCursor.StorageKind.HEAP, selected.directHeapArray() != null,
							diagnostic);
					assertEquals(kind == BufDataCursor.StorageKind.MEMORY_SEGMENT,
							selected.directMemorySegment() != null, diagnostic);
					if (kind == BufDataCursor.StorageKind.FALLBACK) {
						assertThrows(IllegalStateException.class, () -> selected.directStorageOffset(0), diagnostic);
					} else {
						assertEquals(storage.expectedDirectOffset(), selected.directStorageOffset(0), diagnostic);
						assertEquals((long) storage.expectedDirectOffset() + payload.length,
								selected.directStorageOffset(payload.length), diagnostic);
						assertThrows(IndexOutOfBoundsException.class,
								() -> selected.directStorageOffset(-1), diagnostic);
						assertThrows(IndexOutOfBoundsException.class,
								() -> selected.directStorageOffset(payload.length + 1), diagnostic);
					}
					assertArrayEquals(payload, selected.readAllBytes(), diagnostic);
					selected.unbind();
					assertFalse(heap.isBound(), diagnostic);
					assertFalse(memorySegment.isBound(), diagnostic);
					assertFalse(fallback.isBound(), diagnostic);
				}

				Buf heapSource = Buf.wrap(payload);
				Buf segmentSource = new MemorySegmentBuf(segment.asSlice(padding, payload.length));
				Buf fallbackSource = forcedFallback(heapSource);
				assertThrows(IllegalArgumentException.class,
						() -> new HeapBufDataCursor(UNLIMITED).bind(segmentSource, 0, payload.length));
				assertThrows(IllegalArgumentException.class,
						() -> new HeapBufDataCursor(UNLIMITED).bind(fallbackSource, 0, payload.length));
				assertThrows(IllegalArgumentException.class,
						() -> new MemorySegmentBufDataCursor(UNLIMITED).bind(heapSource, 0, payload.length));
				assertThrows(IllegalArgumentException.class,
						() -> new MemorySegmentBufDataCursor(UNLIMITED).bind(fallbackSource, 0, payload.length));
				assertThrows(IllegalArgumentException.class,
						() -> new FallbackBufDataCursor(UNLIMITED).bind(heapSource, 0, payload.length));
				assertThrows(IllegalArgumentException.class,
						() -> new FallbackBufDataCursor(UNLIMITED).bind(segmentSource, 0, payload.length));
			}
		}
	}

	@Test
	void borrowedRegionsShareBudgetStorageAndClosedConsumptionAcrossRepeatedBindings() {
		var random = new Random(DISPATCH_SEED ^ Long.MIN_VALUE);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < DISPATCH_CASES; caseIndex++) {
				byte[] payload = randomBytes(random, 257);
				MemorySegment segment = arena.allocate(Math.max(1, payload.length), 1);
				if (payload.length != 0) segment.copyFrom(MemorySegment.ofArray(payload));
				List<BorrowedFactory> factories = List.of(
						new BorrowedFactory(Buf.wrap(payload), () -> HeapBufDataCursor.borrowed()),
						new BorrowedFactory(new MemorySegmentBuf(segment.asSlice(0, payload.length)),
								() -> MemorySegmentBufDataCursor.borrowed()),
						new BorrowedFactory(forcedFallback(Buf.wrap(payload)), () -> FallbackBufDataCursor.borrowed()));

				for (BorrowedFactory factory : factories) {
					DecodeBudget budget = new DecodeBudget(new DecodeLimits(1_024, 1_024, 8_192, 8_192, 32));
					var parent = new BufDataCursor(budget);
					BufDataCursor child = factory.child().create();
					assertThrows(IllegalStateException.class,
							() -> child.bind(factory.source(), 0, factory.source().size()));
					parent.bind(factory.source(), 0, payload.length);
					int from = random.nextInt(payload.length + 1);
					int length = random.nextInt(payload.length - from + 1);
					int parentPosition = parent.position();
					parent.bindRegion(child, from, length);
					assertSame(parent.decodeBudget(), child.decodeBudget());
					assertEquals(parentPosition, parent.position());
					assertArrayEquals(Arrays.copyOfRange(payload, from, from + length), child.readAllBytes());
					child.close();
					assertEquals(0, child.remainingIncludingClosed());
					assertFalse(child.isBound());

					int reserved = parent.reserve(length);
					parent.bindReservedRegion(child, reserved, length);
					assertArrayEquals(Arrays.copyOfRange(payload, reserved, reserved + length),
							child.readAllBytes(),
							"reserved region must use parent-relative coordinates");
					child.unbind();
					parent.unbind();
					assertFalse(parent.isBound());
				}
			}
		}
	}

	@Test
	void decodeBudgetClaimsResetNestAndFailAtomicallyAtEveryBoundary() {
		var random = new Random(BUDGET_SEED);
		for (int caseIndex = 0; caseIndex < BUDGET_CASES; caseIndex++) {
			int perArray = random.nextInt(65);
			int perPayload = random.nextInt(65);
			long cumulativeArray = random.nextInt(257);
			long cumulativePayload = random.nextInt(257);
			int maximumDepth = random.nextInt(17);
			var limits = new DecodeLimits(perArray, perPayload, cumulativeArray,
					cumulativePayload, maximumDepth);
			var budget = new DecodeBudget(limits);
			budget.enterRoot();
			long arrays = 0;
			long payloads = 0;
			int depth = 0;

			for (int operationIndex = 0; operationIndex < 64; operationIndex++) {
				int operation = random.nextInt(4);
				String diagnostic = "seed=" + BUDGET_SEED + ", case=" + caseIndex
						+ ", operation=" + operationIndex + ", kind=" + operation;
				if (operation == 0) {
					int claim = random.nextInt(81) - 8;
					long before = budget.claimedArrayElements();
					boolean allowed = claim >= 0 && claim <= perArray && arrays + claim <= cumulativeArray;
					if (allowed) {
						budget.claimArrayElements(claim);
						arrays += claim;
					} else if (claim < 0) {
						assertThrows(MalformedDataException.class, () -> budget.claimArrayElements(claim), diagnostic);
					} else {
						assertThrows(DecodeLimitExceededException.class,
								() -> budget.claimArrayElements(claim), diagnostic);
					}
					assertEquals(allowed ? arrays : before, budget.claimedArrayElements(), diagnostic);
				} else if (operation == 1) {
					int claim = random.nextInt(81) - 8;
					long before = budget.claimedPayloadBytes();
					boolean allowed = claim >= 0 && claim <= perPayload && payloads + claim <= cumulativePayload;
					if (allowed) {
						budget.claimPayloadBytes(claim);
						payloads += claim;
					} else if (claim < 0) {
						assertThrows(MalformedDataException.class, () -> budget.claimPayloadBytes(claim), diagnostic);
					} else {
						assertThrows(DecodeLimitExceededException.class,
								() -> budget.claimPayloadBytes(claim), diagnostic);
					}
					assertEquals(allowed ? payloads : before, budget.claimedPayloadBytes(), diagnostic);
				} else if (operation == 2 && depth < maximumDepth) {
					budget.enterStructure();
					depth++;
					assertEquals(depth, budget.structuralDepth(), diagnostic);
				} else if (depth > 0) {
					budget.exitStructure();
					depth--;
					assertEquals(depth, budget.structuralDepth(), diagnostic);
				} else {
					assertThrows(IllegalStateException.class, budget::exitStructure, diagnostic);
				}
			}

			while (depth > 0) {
				budget.exitStructure();
				depth--;
			}
			if (maximumDepth < Integer.MAX_VALUE) {
				for (int index = 0; index < maximumDepth; index++) budget.enterStructure();
				assertThrows(DecodeLimitExceededException.class, budget::enterStructure);
				assertEquals(maximumDepth, budget.structuralDepth());
				for (int index = 0; index < maximumDepth; index++) budget.exitStructure();
			}
			budget.exitRoot();
			assertThrows(IllegalStateException.class, budget::exitRoot);

			budget.enterRoot();
			assertEquals(0, budget.claimedArrayElements());
			assertEquals(0, budget.claimedPayloadBytes());
			assertEquals(0, budget.structuralDepth());
			budget.exitRoot();
		}
	}

	@Test
	void eachPrimitiveArrayReadChargesExactlyOnceAndLimitFailuresLeaveInputReusable() {
		for (int length = 0; length <= 64; length++) {
			for (ArrayRead read : ArrayRead.values()) {
				int byteLength = Math.multiplyExact(length, read.width);
				DecodeBudget budget = new DecodeBudget(new DecodeLimits(length, byteLength,
						length, byteLength, 1));
				var cursor = new BufDataCursor(budget);
				cursor.bind(Buf.wrap(new byte[byteLength]), 0, byteLength);
				read.read(cursor, length);
				assertEquals(length, budget.claimedArrayElements(), read + ", length=" + length);
				assertEquals(byteLength, cursor.position(), read + ", length=" + length);
				cursor.unbind();
			}
		}

		DecodeLimits limits = new DecodeLimits(3, 3, 3, 3, 1);
		for (ArrayRead read : ArrayRead.values()) {
			int length = 4;
			int byteLength = Math.multiplyExact(length, read.width);
			var cursor = new BufDataCursor(limits);
			cursor.bind(Buf.wrap(new byte[byteLength]), 0, byteLength);
			assertThrows(DecodeLimitExceededException.class, () -> read.read(cursor, length), read.name());
			assertEquals(0, cursor.position(), read.name());
			cursor.unbind();
		}

		var stringCursor = new BufDataCursor(limits);
		stringCursor.bind(Buf.wrap(new byte[4]), 0, 4);
		assertThrows(DecodeLimitExceededException.class,
				() -> stringCursor.readString(4, StandardCharsets.UTF_8));
		assertEquals(0, stringCursor.position());
		assertEquals(0, stringCursor.decodeBudget().claimedPayloadBytes());
		stringCursor.unbind();
	}

	@Test
	void randomAccessGettersByteBufferDestinationsAndOneShotInputFuzzEveryPosition() {
		var random = new Random(BUFFER_SEED);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < BUFFER_CASES; caseIndex++) {
				byte[] bytes = randomBytes(random, 513);
				byte[] padded = new byte[bytes.length + 8];
				System.arraycopy(bytes, 0, padded, 4, bytes.length);
				MemorySegment segment = arena.allocate(Math.max(1, padded.length), 1);
				segment.copyFrom(MemorySegment.ofArray(padded));
				List<Buf> sources = List.of(Buf.wrap(padded).subListForced(4, 4 + bytes.length),
						new MemorySegmentBuf(segment.asSlice(4, bytes.length)),
						forcedFallback(Buf.wrap(bytes)));
				for (Buf source : sources) {
					BufDataInput input = BufDataInput.create(source, UNLIMITED);
					input.close();
					assertEquals(0, input.position());
					assertEquals(bytes.length, input.length());
					assertEquals(bytes.length, input.remainingBytesIfKnown());
					if (bytes.length >= Long.BYTES) {
						int offset = random.nextInt(bytes.length - Long.BYTES + 1);
						ByteBuffer reference = ByteBuffer.wrap(bytes);
						assertEquals(reference.get(offset) != 0, input.getBooleanAt(offset));
						assertEquals(reference.get(offset), input.getByteAt(offset));
						assertEquals(Byte.toUnsignedInt(reference.get(offset)), input.getUnsignedByteAt(offset));
						assertEquals(reference.getShort(offset), input.getShortAt(offset));
						assertEquals(Short.toUnsignedInt(reference.getShort(offset)), input.getUnsignedShortAt(offset));
						assertEquals(reference.getChar(offset), input.getCharAt(offset));
						assertEquals(reference.getInt(offset), input.getIntAt(offset));
						assertEquals(reference.getLong(offset), input.getLongAt(offset));
						assertEquals(Float.floatToRawIntBits(reference.getFloat(offset)),
								Float.floatToRawIntBits(input.getFloatAt(offset)));
						assertEquals(Double.doubleToRawLongBits(reference.getDouble(offset)),
								Double.doubleToRawLongBits(input.getDoubleAt(offset)));
					}

					int position = random.nextInt(bytes.length + 1);
					input.position(position);
					int length = random.nextInt(bytes.length - position + 1);
					ByteBuffer destination = switch (caseIndex % 3) {
						case 0 -> ByteBuffer.allocate(length + 6).position(3).slice();
						case 1 -> arena.allocate(Math.max(1, length + 6), 1).asByteBuffer().position(3).slice();
						default -> ByteBuffer.allocate(length + 9).position(4).limit(length + 4).slice();
					};
					input.readFully(destination, length);
					destination.flip();
					byte[] actual = new byte[length];
					destination.get(actual);
					assertArrayEquals(Arrays.copyOfRange(bytes, position, position + length), actual);
					assertEquals(position + length, input.position());

					int before = input.position();
					ByteBuffer readOnly = ByteBuffer.allocate(8).asReadOnlyBuffer();
					int attempted = Math.min(1, bytes.length - before);
					if (attempted != 0) {
						assertThrows(ReadOnlyBufferException.class, () -> input.readFully(readOnly, attempted));
						assertEquals(before, input.position());
					}
				}
			}
		}
	}

	@Test
	void zeroAllocationUtf8EncodingAndDecodingMatchesJdkForArbitraryCodeUnitsAndBytes() {
		var random = new Random(TEXT_SEED);
		var minimumBufferEncoder = new ZeroAllocationEncoder(1);
		for (String boundary : List.of("\ud83d\ude00", "a\ud83d\ude00b", "\ud800", "\udc00")) {
			byte[] expected = boundary.getBytes(StandardCharsets.UTF_8);
			BufDataOutput output = BufDataOutput.createLimited(expected.length);
			minimumBufferEncoder.encodeTo(boundary, output);
			assertArrayEquals(expected, output.asList().asArray(), boundary);
		}
		assertThrows(IllegalArgumentException.class, () -> new ZeroAllocationEncoder(0));
		assertThrows(IllegalArgumentException.class, () -> new ZeroAllocationEncoder(-1));
		assertThrows(IllegalArgumentException.class, () -> new ZeroAllocationEncoder(Integer.MAX_VALUE));
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < TEXT_CASES; caseIndex++) {
				String value = randomCodeUnits(random, 1_025);
				byte[] expected = value.getBytes(StandardCharsets.UTF_8);
				var encoder = new ZeroAllocationEncoder(1 + random.nextInt(65));
				BufDataOutput output = BufDataOutput.createLimited(expected.length);
				encoder.encodeTo(value, output);
				String diagnostic = "seed=" + TEXT_SEED + ", case=" + caseIndex
						+ ", chars=" + value.length() + ", bytes=" + expected.length;
				assertArrayEquals(expected, output.asList().asArray(), diagnostic);

				byte[] arbitrary = randomBytes(random, 2_049);
				String decoded = new String(arbitrary, StandardCharsets.UTF_8);
				MemorySegment nativeBytes = arena.allocate(Math.max(1, arbitrary.length), 1);
				if (arbitrary.length != 0) nativeBytes.copyFrom(MemorySegment.ofArray(arbitrary));
				for (Buf source : List.of(Buf.wrap(arbitrary),
						new MemorySegmentBuf(nativeBytes.asSlice(0, arbitrary.length)),
						forcedFallback(Buf.wrap(arbitrary)))) {
					var cursor = new BufDataCursor(UNLIMITED);
					cursor.bind(source, 0, arbitrary.length);
					assertEquals(decoded, encoder.decodeFrom(cursor, arbitrary.length), diagnostic);
					assertEquals(0, cursor.remaining(), diagnostic);
					cursor.unbind();
				}

				BufDataOutput zeroCopy = BufDataOutput.create();
				BufDataOutput legacy = BufDataOutput.create();
				zeroCopy.writeMediumTextZeroCopy(value);
				legacy.writeMediumTextLegacy(value, StandardCharsets.UTF_8);
				assertArrayEquals(legacy.asList().asArray(), zeroCopy.asList().asArray(), diagnostic);
			}
		}
	}

	@Test
	void sharedEncoderThreadLocalsNeverBleedAcrossVirtualThreadLanes() throws Exception {
		var random = new Random(TEXT_SEED ^ Long.MIN_VALUE);
		var tasks = new ArrayList<Callable<Void>>();
		for (int lane = 0; lane < 64; lane++) {
			long laneSeed = random.nextLong();
			tasks.add(() -> {
				var laneRandom = new Random(laneSeed);
				for (int iteration = 0; iteration < 500; iteration++) {
					String value = randomCodeUnits(laneRandom, 513);
					byte[] expected = value.getBytes(StandardCharsets.UTF_8);
					BufDataOutput output = BufDataOutput.create();
					ZeroAllocationEncoder.INSTANCE.encodeTo(value, output);
					assertArrayEquals(expected, output.asList().asArray(),
							"laneSeed=" + laneSeed + ", iteration=" + iteration);
					var cursor = new BufDataCursor(UNLIMITED);
					cursor.bind(Buf.wrap(expected), 0, expected.length);
					assertEquals(new String(expected, StandardCharsets.UTF_8),
							ZeroAllocationEncoder.INSTANCE.decodeFrom(cursor, expected.length));
					cursor.unbind();
				}
				return null;
			});
		}
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var future : executor.invokeAll(tasks)) future.get();
		}
	}

	@Test
	@SuppressWarnings("removal")
	void textLengthEdgesWrappedOutputsAndInvalidPositionChangesAreAtomic() {
		String maximumShort = "a".repeat(0xffff);
		String tooLong = maximumShort + "a";
		BufDataOutput shortOutput = BufDataOutput.create();
		shortOutput.writeShortText(maximumShort, StandardCharsets.UTF_8);
		assertEquals(maximumShort, BufDataInput.create(shortOutput.asList(), UNLIMITED)
				.readShortText(StandardCharsets.UTF_8));
		assertEquals(maximumShort, shortOutput.asList().getShortText(0, StandardCharsets.UTF_8));
		assertThrows(ValueTooLargeException.class,
				() -> BufDataOutput.create().writeShortText(tooLong, StandardCharsets.UTF_8));

		byte[] storage = new byte[32];
		Arrays.fill(storage, (byte) 0x5a);
		Buf target = Buf.wrap(storage);
		BufDataOutput wrapped = BufDataOutput.wrap(target, 7, 19);
		wrapped.writeLong(0x0102_0304_0506_0708L);
		wrapped.writeInt(0x1122_3344);
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 0x11, 0x22, 0x33, 0x44},
				Arrays.copyOfRange(storage, 7, 19));
		byte[] before = storage.clone();
		assertThrows(IndexOutOfBoundsException.class, () -> wrapped.writeByte(1));
		assertArrayEquals(before, storage);

		BufDataOutput output = BufDataOutput.create();
		output.writeInt(1);
		byte[] outputBefore = output.asList().asArray().clone();
		assertThrows(IndexOutOfBoundsException.class, () -> output.rewindPosition(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> output.advancePosition(-1));
		assertArrayEquals(outputBefore, output.asList().asArray());
		assertEquals(Integer.BYTES, output.position());

		for (int offset : List.of(Integer.MIN_VALUE, -1, 5, Integer.MAX_VALUE)) {
			for (int length : List.of(Integer.MIN_VALUE, -1, 1, Integer.MAX_VALUE)) {
				BufDataOutput candidate = BufDataOutput.create();
				candidate.writeByte(9);
				byte[] snapshot = candidate.asList().asArray().clone();
				assertThrows(RuntimeException.class,
						() -> candidate.write(new byte[4], offset, length),
						"offset=" + offset + ", length=" + length);
				assertArrayEquals(snapshot, candidate.asList().asArray());
				assertEquals(1, candidate.position());
			}
		}
	}

	private static Buf forcedFallback(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(BufCursorBudgetTextDeepFuzzTest.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict", "asArrayStrict",
							"asUnboundedArrayStrict" -> null;
					case "getBackingByteArray", "asArray", "asUnboundedArray", "binaryInputStream" ->
							throw new AssertionError("Fallback cursor requested a whole-payload conversion");
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}

	private static String randomCodeUnits(Random random, int exclusiveMaximumLength) {
		char[] value = new char[random.nextInt(exclusiveMaximumLength)];
		for (int index = 0; index < value.length; index++) {
			value[index] = switch (random.nextInt(8)) {
				case 0 -> (char) random.nextInt(0x80);
				case 1 -> (char) (0x80 + random.nextInt(0x780));
				case 2 -> (char) (0x800 + random.nextInt(0x800));
				case 3 -> (char) (0xd800 + random.nextInt(0x800));
				default -> (char) random.nextInt(Character.MAX_VALUE + 1);
			};
		}
		return new String(value);
	}

	private static byte[] randomBytes(Random random, int exclusiveMaximumLength) {
		byte[] result = new byte[random.nextInt(exclusiveMaximumLength)];
		random.nextBytes(result);
		return result;
	}

	private record StorageCase(String name,
			Buf source,
			int offset,
			BufDataCursor.StorageKind kind,
			int expectedDirectOffset) {}

	private record BorrowedFactory(Buf source, CursorFactory child) {}

	@FunctionalInterface
	private interface CursorFactory {
		BufDataCursor create();
	}

	private enum ArrayRead {
		BOOLEAN(1) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readBooleanArray(length); }
		},
		BYTE(1) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readByteArray(length); }
		},
		SHORT(Short.BYTES) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readShortArray(length); }
		},
		CHAR(Character.BYTES) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readCharArray(length); }
		},
		INT(Integer.BYTES) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readIntArray(length); }
		},
		LONG(Long.BYTES) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readLongArray(length); }
		},
		FLOAT(Float.BYTES) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readFloatArray(length); }
		},
		DOUBLE(Double.BYTES) {
			@Override void read(BufDataCursor cursor, int length) { cursor.readDoubleArray(length); }
		};

		private final int width;

		ArrayRead(int width) {
			this.width = width;
		}

		abstract void read(BufDataCursor cursor, int length);
	}
}
