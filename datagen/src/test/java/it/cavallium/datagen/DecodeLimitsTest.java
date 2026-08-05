package it.cavallium.datagen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.nativedata.ArrayBinaryStringSerializer;
import it.cavallium.datagen.nativedata.ArrayInt52Serializer;
import it.cavallium.datagen.nativedata.ArrayStringSerializer;
import it.cavallium.datagen.nativedata.ArraybooleanSerializer;
import it.cavallium.datagen.nativedata.ArraybyteSerializer;
import it.cavallium.datagen.nativedata.ArraycharSerializer;
import it.cavallium.datagen.nativedata.ArraydoubleSerializer;
import it.cavallium.datagen.nativedata.ArrayfloatSerializer;
import it.cavallium.datagen.nativedata.ArrayintSerializer;
import it.cavallium.datagen.nativedata.ArraylongSerializer;
import it.cavallium.datagen.nativedata.ArrayshortSerializer;
import it.cavallium.datagen.nativedata.BinaryString;
import it.cavallium.datagen.nativedata.BinaryStringSerializer;
import it.cavallium.datagen.nativedata.NullableBinaryString;
import it.cavallium.datagen.nativedata.NullableBinaryStringSerializer;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.datagen.nativedata.StringSerializer;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInputStream;
import it.cavallium.stream.SafeInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class DecodeLimitsTest {

	@Test
	void unsignedShortBinaryStringsAcceptTheCompleteWireDomainBeforeWriting() {
		for (int size : new int[] {0, 32767, 32768, 65535}) {
			BinaryString value = new BinaryString(new byte[size]);
			BufDataOutput output = BufDataOutput.create(size + Short.BYTES);
			BinaryStringSerializer.writeShort(output, value);
			BufDataInput input = BufDataInput.create(output.asList(), DecodeLimits.unlimited());
			assertEquals(size, input.readUnsignedShort());
			assertEquals(size, input.remainingBytesIfKnown());
		}

		BinaryString tooLarge = new BinaryString(new byte[65536]);
		BufDataOutput direct = BufDataOutput.create();
		assertThrows(ValueTooLargeException.class,
				() -> BinaryStringSerializer.writeShort(direct, tooLarge));
		assertEquals(0, direct.position());

		BufDataOutput nullable = BufDataOutput.create();
		assertThrows(ValueTooLargeException.class, () -> NullableBinaryStringSerializer.INSTANCE.serialize(
				nullable, NullableBinaryString.of(tooLarge)));
		assertEquals(0, nullable.position(), "nullable validation must precede its presence byte");

		BufDataOutput array = BufDataOutput.create();
		assertThrows(ValueTooLargeException.class, () -> new ArrayBinaryStringSerializer().serialize(array,
				new BinaryString[] {new BinaryString(new byte[] {1}), tooLarge}));
		assertEquals(0, array.position(), "array validation must precede its element-count prefix");
	}

	@Test
	void payloadAndArrayLimitsAcceptExactValuesAndRejectOneOver() {
		BufDataOutput stringOutput = BufDataOutput.create();
		StringSerializer.INSTANCE.serialize(stringOutput, "12345678");
		Buf string = stringOutput.asList();
		DecodeLimits exactPayload = new DecodeLimits(8, 8, 8, 8, 4);
		assertEquals("12345678", StringSerializer.INSTANCE.newReader(exactPayload).read(string));
		DecodeLimits shortPayload = new DecodeLimits(8, 7, 8, 8, 4);
		assertThrows(DecodeLimitExceededException.class,
				() -> StringSerializer.INSTANCE.newReader(shortPayload).read(string));

		BufDataOutput arrayOutput = BufDataOutput.create();
		new ArrayintSerializer().serialize(arrayOutput, new int[] {1, 2, 3});
		Buf ints = arrayOutput.asList();
		DecodeLimits exactArray = new DecodeLimits(3, 32, 3, 32, 1);
		assertArrayEquals(new int[] {1, 2, 3}, new ArrayintSerializer().newReader(exactArray).read(ints));
		DecodeLimits shortArray = new DecodeLimits(2, 32, 3, 32, 1);
		assertThrows(DecodeLimitExceededException.class,
				() -> new ArrayintSerializer().newReader(shortArray).read(ints));
	}

	@Test
	void everyNativeArrayClaimsPerArrayAndCumulativeBudgetsBeforeAllocation() {
		assertArrayLimits(new ArraybooleanSerializer(), new boolean[] {true, false});
		assertArrayLimits(new ArraybyteSerializer(), new byte[] {1, -2});
		assertArrayLimits(new ArrayshortSerializer(), new short[] {3, -4});
		assertArrayLimits(new ArraycharSerializer(), new char[] {'A', '\u03a9'});
		assertArrayLimits(new ArrayintSerializer(), new int[] {5, -6});
		assertArrayLimits(new ArraylongSerializer(), new long[] {7L, -8L});
		assertArrayLimits(new ArrayfloatSerializer(), new float[] {1.25f, -3.5f});
		assertArrayLimits(new ArraydoubleSerializer(), new double[] {2.5d, -4.75d});
		assertArrayLimits(new ArrayStringSerializer(), new String[] {"a", "bc"});
		assertArrayLimits(new ArrayBinaryStringSerializer(), new BinaryString[] {
				new BinaryString(new byte[] {1}), new BinaryString(new byte[] {2, 3})});
		assertArrayLimits(new ArrayInt52Serializer(), new Int52[] {Int52.ONE, Int52.fromLong(0x010203040506L)});
	}

	@Test
	void cumulativePayloadBudgetIsPerRootAndIncludesEveryArrayElement() {
		BufDataOutput output = BufDataOutput.create();
		new ArrayStringSerializer().serialize(output, new String[] {"abc", "defg"});
		Buf payload = output.asList();

		DecodeLimits exact = new DecodeLimits(2, 4, 2, 7, 1);
		DataCodec.Reader<String[]> reader = new ArrayStringSerializer().newReader(exact);
		assertArrayEquals(new String[] {"abc", "defg"}, reader.read(payload));
		assertArrayEquals(new String[] {"abc", "defg"}, reader.read(payload));

		DecodeLimits oneShort = new DecodeLimits(2, 4, 2, 6, 1);
		assertThrows(DecodeLimitExceededException.class,
				() -> new ArrayStringSerializer().newReader(oneShort).read(payload));
	}

	@Test
	void boundedPayloadTruncationStopsImmediatelyAfterThePrefix() {
		byte[] truncated = new byte[] {0, 0, 0, 3, 'a', 'b'};
		BufDataInput bounded = BufDataInput.create(Buf.wrap(truncated), DecodeLimits.unlimited());
		assertThrows(MalformedDataException.class, () -> StringSerializer.INSTANCE.read(bounded));
		assertEquals(Integer.BYTES, bounded.position());

		SafeByteArrayInputStream source = new SafeByteArrayInputStream(truncated);
		SafeDataInputStream measured = new SafeDataInputStream(source, DecodeLimits.unlimited());
		assertThrows(MalformedDataException.class, () -> StringSerializer.INSTANCE.read(measured));
		assertEquals(Integer.BYTES, source.position());
	}

	@Test
	void forwardOnlyZeroProgressAndPartialReadsStillMakeExactProgress() {
		byte[] sourceBytes = new byte[] {1, 2, 3, 4, 5, 6};
		SafeDataInputStream input = new SafeDataInputStream(
				new AlternatingZeroProgressStream(sourceBytes), DecodeLimits.unlimited());
		assertEquals(-1, input.remainingBytesIfKnown());
		byte[] first = new byte[3];
		input.readFully(first);
		assertArrayEquals(new byte[] {1, 2, 3}, first);
		ByteBuffer second = ByteBuffer.allocate(5);
		second.position(1);
		input.readFully(second, 3);
		assertEquals(4, second.position());
		assertArrayEquals(new byte[] {0, 4, 5, 6, 0}, second.array());

		SafeDataInputStream truncated = new SafeDataInputStream(
				new AlternatingZeroProgressStream(new byte[] {7, 8}), DecodeLimits.unlimited());
		assertThrows(MalformedDataException.class, () -> truncated.readFully(new byte[3]));
	}

	private static <T> void assertArrayLimits(DataCodec<T> codec, T values) {
		int elements = java.lang.reflect.Array.getLength(values);
		BufDataOutput output = BufDataOutput.create();
		codec.serialize(output, values);
		Buf payload = output.asList();
		DecodeLimits exact = new DecodeLimits(elements, Integer.MAX_VALUE, elements, Long.MAX_VALUE, 4);
		assertEquals(true, Objects.deepEquals(values, codec.newReader(exact).read(payload)));

		DecodeLimits perArrayOneShort = new DecodeLimits(elements - 1, Integer.MAX_VALUE, elements,
				Long.MAX_VALUE, 4);
		assertThrows(DecodeLimitExceededException.class,
				() -> codec.newReader(perArrayOneShort).read(payload));
		DecodeLimits cumulativeOneShort = new DecodeLimits(elements, Integer.MAX_VALUE, elements - 1L,
				Long.MAX_VALUE, 4);
		assertThrows(DecodeLimitExceededException.class,
				() -> codec.newReader(cumulativeOneShort).read(payload));
	}

	private static final class AlternatingZeroProgressStream extends SafeInputStream {
		private final byte[] source;
		private int position;
		private boolean returnZero = true;

		private AlternatingZeroProgressStream(byte[] source) {
			this.source = Arrays.copyOf(source, source.length);
		}

		@Override
		public int read() {
			return position == source.length ? -1 : Byte.toUnsignedInt(source[position++]);
		}

		@Override
		public int read(byte[] destination, int offset, int length) {
			if (length == 0) return 0;
			if (position == source.length) return -1;
			if (returnZero) {
				returnZero = false;
				return 0;
			}
			returnZero = true;
			int copied = Math.min(Math.min(length, 2), source.length - position);
			System.arraycopy(source, position, destination, offset, copied);
			position += copied;
			return copied;
		}
	}
}
