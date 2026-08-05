package it.cavallium.datagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.nativedata.ArrayintSerializer;
import it.cavallium.datagen.nativedata.StringSerializer;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataCodecReaderTest {

	private static final DataCodec<Integer> INT_CODEC = new DataCodec<>() {
		@Override
		public void serialize(SafeDataOutput output, Integer data) {
			output.writeInt(data);
		}

		@Override
		public Integer read(SafeDataInput input) {
			int value = input.readInt();
			if (value == -1) throw new IllegalStateException("custom failure");
			return value;
		}

		@Override
		public void skip(SafeDataInput input) {
			ProjectionReadSupport.skipBytes(input, Integer.BYTES);
		}
	};

	@Test
	void readsEveryStorageShapeAndRejectsInvalidBounds() throws Exception {
		DataCodec.Reader<Integer> reader = INT_CODEC.newReader(DecodeLimits.unlimited());
		Buf value = intBuf(42);
		assertEquals(42, reader.read(value));
		assertUnbound(reader);

		byte[] padded = new byte[] {9, 9, 0, 0, 0, 42, 8};
		assertEquals(42, reader.read(Buf.wrap(padded), 2, Integer.BYTES));
		assertEquals(42, reader.read(Buf.wrap(padded).subList(2, 6)));
		assertUnbound(reader);

		try (var arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocate(Integer.BYTES + 3, 1);
			MemorySegment.copy(value.asMemorySegment(), 0, segment, 2, Integer.BYTES);
			Buf nativeValue = new MemorySegmentBuf(segment) {
				@Override
				public byte[] asArray() {
					throw new AssertionError("Reader requested a whole-payload array");
				}

				@Override
				public SafeByteArrayInputStream binaryInputStream() {
					throw new AssertionError("Reader requested a whole-payload stream");
				}
			};
			assertEquals(42, reader.read(nativeValue, 2, Integer.BYTES));
			assertEquals(42, reader.read(nativeValue.subList(2, 2 + Integer.BYTES)));
		}
		assertUnbound(reader);

		assertThrows(MalformedDataException.class, () -> reader.read(value, 0, 3));
		assertUnbound(reader);
		assertThrows(IllegalArgumentException.class, () -> reader.read(Buf.wrap(padded), 2, 5));
		assertUnbound(reader);
		assertThrows(IllegalStateException.class, () -> reader.read(intBuf(-1)));
		assertUnbound(reader);
		assertEquals(42, reader.read(value));
		assertUnbound(reader);
	}

	@Test
	void acceptsAClosedFullyConsumedCursorAndRejectsAClosedPartialCursor() throws Exception {
		DataCodec<Integer> closingSerializer = new DataCodec<>() {
			@Override
			public void serialize(SafeDataOutput output, Integer data) {
				output.writeInt(data);
			}

			@Override
			public Integer read(SafeDataInput input) {
				int value = input.readInt();
				((BufDataCursor) input).close();
				return value;
			}

			@Override
			public void skip(SafeDataInput input) {
				ProjectionReadSupport.skipBytes(input, Integer.BYTES);
			}
		};
		DataCodec.Reader<Integer> closingReader = closingSerializer.newReader(DecodeLimits.unlimited());
		assertEquals(42, closingReader.read(intBuf(42)));
		assertUnbound(closingReader);

		DataCodec<Integer> partialClosingSerializer = new DataCodec<>() {
			@Override
			public void serialize(SafeDataOutput output, Integer data) {
				output.writeInt(data);
			}

			@Override
			public Integer read(SafeDataInput input) {
				int value = input.readUnsignedByte();
				((BufDataCursor) input).close();
				return value;
			}

			@Override
			public void skip(SafeDataInput input) {
				ProjectionReadSupport.skipBytes(input, Integer.BYTES);
			}
		};
		DataCodec.Reader<Integer> partialReader = partialClosingSerializer.newReader(DecodeLimits.unlimited());
		IllegalArgumentException trailing = assertThrows(IllegalArgumentException.class,
				() -> partialReader.read(intBuf(42)));
		assertEquals("Trailing bytes: 3", trailing.getMessage());
		assertUnbound(partialReader);
	}

	@Test
	void nativeCodecsSkipExactlyOneValueWithoutMaterializingIt() {
		BufDataOutput output = BufDataOutput.createLimited(64);
		StringSerializer.INSTANCE.serialize(output, "legacy payload");
		output.writeInt(0x12345678);
		BufDataInput stringInput = BufDataInput.create(output.asList(), DecodeLimits.unlimited());
		StringSerializer.INSTANCE.skip(stringInput);
		assertEquals(0x12345678, stringInput.readInt());

		output = BufDataOutput.createLimited(64);
		var arrayCodec = new ArrayintSerializer();
		arrayCodec.serialize(output, new int[] {1, 2, 3, 4});
		output.writeByte(77);
		BufDataInput arrayInput = BufDataInput.create(output.asList(), DecodeLimits.unlimited());
		arrayCodec.skip(arrayInput);
		assertEquals(77, arrayInput.readUnsignedByte());

		BufDataOutput truncated = BufDataOutput.createLimited(16);
		truncated.writeInt(8);
		truncated.writeInt(1);
		assertThrows(MalformedDataException.class,
					() -> StringSerializer.INSTANCE.skip(BufDataInput.create(truncated.asList(), DecodeLimits.unlimited())));
	}

	@Test
	void readerLanesOwnDistinctReusableSessionsAndClearFailures() {
		TrackingCodec codec = new TrackingCodec();
		DataCodec.Reader<Integer> first = codec.newReader(DecodeLimits.unlimited());
		DataCodec.Reader<Integer> second = codec.newReader(DecodeLimits.unlimited());
		assertEquals(2, codec.sessions.size());
		TrackingSession firstSession = codec.sessions.get(0);
		TrackingSession secondSession = codec.sessions.get(1);
		assertNotSame(firstSession, secondSession);

		assertEquals(1, first.read(intBuf(1)));
		assertEquals(2, first.read(intBuf(2)));
		assertEquals(2, firstSession.reads);
		assertEquals(0, secondSession.reads);
		assertNull(firstSession.retainedInput);

		assertThrows(IllegalStateException.class, () -> first.read(intBuf(-1)));
		assertNull(firstSession.retainedInput);
		assertEquals(3, firstSession.clears);
		assertEquals(3, first.read(intBuf(3)));
		assertEquals(4, firstSession.clears);
		assertEquals(4, second.read(intBuf(4)));
		assertSame(secondSession, codec.sessions.get(1));
	}

	@Test
	void structuralAndCumulativeBudgetsResetAfterEveryRoot() {
		BufDataOutput output = BufDataOutput.create();
		new ArrayintSerializer().serialize(output, new int[] {11, 12});
		Buf payload = output.asList();

		DecodeLimits depthZero = new DecodeLimits(2, 16, 2, 16, 0);
		BufDataInput rejected = BufDataInput.create(payload, depthZero);
		assertThrows(DecodeLimitExceededException.class,
				() -> new ArrayintSerializer().read(rejected));
		assertEquals(0, rejected.decodeBudget().structuralDepth());

		DecodeLimits exact = new DecodeLimits(2, 16, 2, 16, 1);
		DataCodec.Reader<int[]> reader = new ArrayintSerializer().newReader(exact);
		assertArrayEquals(new int[] {11, 12}, reader.read(payload));
		assertArrayEquals(new int[] {11, 12}, reader.read(payload));
	}

	@Test
	void immutableSingletonCodecsContainNoReaderLaneFields() {
		for (Class<?> codecClass : List.of(StringSerializer.class, ArrayintSerializer.class)) {
			for (Field field : codecClass.getDeclaredFields()) {
				assertFalse(!Modifier.isStatic(field.getModifiers()),
						() -> codecClass.getName() + " retains mutable lane field " + field.getName());
			}
		}
	}

	private static final class TrackingCodec implements DataCodec<Integer> {
		private final List<TrackingSession> sessions = new ArrayList<>();

		@Override
		public void serialize(SafeDataOutput output, Integer data) {
			output.writeInt(data);
		}

		@Override
		public Integer read(SafeDataInput input) {
			throw new AssertionError("reader lane bypassed its session");
		}

		@Override
		public void skip(SafeDataInput input) {
			throw new AssertionError("reader lane bypassed its session");
		}

		@Override
		public ReadSession<Integer> newReadSession() {
			TrackingSession session = new TrackingSession();
			sessions.add(session);
			return session;
		}
	}

	private static final class TrackingSession extends ReadSession<Integer> {
		private SafeDataInput retainedInput;
		private int reads;
		private int clears;

		@Override
		protected Integer decode(SafeDataInput input) {
			retainedInput = input;
			reads++;
			int value = input.readInt();
			if (value == -1) throw new IllegalStateException("custom failure");
			return value;
		}

		@Override
		protected void skipValue(SafeDataInput input) {
			retainedInput = input;
			ProjectionReadSupport.skipBytes(input, Integer.BYTES);
		}

		@Override
		protected void clearTransientState() {
			retainedInput = null;
			clears++;
		}
	}

	private static Buf intBuf(int value) {
		BufDataOutput output = BufDataOutput.create(Integer.BYTES);
		output.writeInt(value);
		return output.asList();
	}

	private static void assertUnbound(DataCodec.Reader<?> reader) throws Exception {
		Field cursorField = reader.getClass().getDeclaredField("cursor");
		cursorField.setAccessible(true);
		Object cursor = cursorField.get(reader);
		Class<?> core = BufDataCursor.class.getSuperclass();
		Field bound = core.getDeclaredField("bound");
		bound.setAccessible(true);
		assertEquals(false, bound.get(cursor));
		for (String fieldName : new String[] {"heap", "segment", "fallback"}) {
			Field field = core.getDeclaredField(fieldName);
			field.setAccessible(true);
			assertNull(field.get(cursor), fieldName);
		}
	}
}
