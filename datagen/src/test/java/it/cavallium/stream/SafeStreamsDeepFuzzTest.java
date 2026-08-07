package it.cavallium.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.datagen.nativedata.Int52;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Direct state-machine and differential fuzzing for the safe stream package. */
class SafeStreamsDeepFuzzTest {

	private static final long INPUT_SEED = 0x62D7_1A9C_40B5_E83FL;
	private static final long OUTPUT_SEED = 0x35AF_8C12_D760_49BEL;
	private static final long DATA_SEED = 0x7E04_B93D_2CA1_658FL;
	private static final long DELEGATE_SEED = 0x18C6_5FA0_9D32_B74EL;
	private static final int INPUT_CASES = 12_000;
	private static final int OUTPUT_CASES = 12_000;
	private static final int DATA_CASES = 20_000;
	private static final int DELEGATE_CASES = 10_000;
	private static final DecodeLimits UNLIMITED = DecodeLimits.unlimited();

	@Test
	void slicedByteArrayInputStateMachineMatchesAnIndependentPositionAndMarkModel() {
		var random = new Random(INPUT_SEED);
		for (int caseIndex = 0; caseIndex < INPUT_CASES; caseIndex++) {
			byte[] payload = new byte[random.nextInt(257)];
			random.nextBytes(payload);
			int prefix = random.nextInt(17);
			int suffix = random.nextInt(17);
			byte[] backing = new byte[prefix + payload.length + suffix];
			random.nextBytes(backing);
			System.arraycopy(payload, 0, backing, prefix, payload.length);
			SafeByteArrayInputStream input = new SafeByteArrayInputStream(backing, prefix, payload.length);
			int position = 0;
			int mark = 0;

			for (int operationIndex = 0; operationIndex < 300; operationIndex++) {
				int kind = random.nextInt(12);
				String diagnostic = diagnostic(INPUT_SEED, caseIndex, operationIndex, kind)
						+ ", position=" + position + ", length=" + payload.length;
				switch (kind) {
					case 0 -> {
						int expected = position == payload.length ? -1 : Byte.toUnsignedInt(payload[position++]);
						assertEquals(expected, input.read(), diagnostic);
					}
					case 1 -> {
						byte[] destination = new byte[2 + random.nextInt(65)];
						random.nextBytes(destination);
						byte[] expected = destination.clone();
						int offset = random.nextInt(destination.length + 1);
						int length = random.nextInt(destination.length - offset + 1);
						int count = length == 0 ? 0 : position == payload.length ? -1
								: Math.min(length, payload.length - position);
						if (count > 0) {
							System.arraycopy(payload, position, expected, offset, count);
							position += count;
						}
						assertEquals(count, input.read(destination, offset, length), diagnostic);
						assertArrayEquals(expected, destination, diagnostic);
					}
					case 2 -> {
						int requested = random.nextInt(payload.length + 33);
						int count = Math.min(requested, payload.length - position);
						assertArrayEquals(Arrays.copyOfRange(payload, position, position + count),
								input.readNBytes(requested), diagnostic);
						position += count;
					}
					case 3 -> {
						byte[] destination = new byte[2 + random.nextInt(65)];
						random.nextBytes(destination);
						byte[] expected = destination.clone();
						int offset = random.nextInt(destination.length + 1);
						int length = random.nextInt(destination.length - offset + 1);
						int count = Math.min(length, payload.length - position);
						System.arraycopy(payload, position, expected, offset, count);
						assertEquals(count, input.readNBytes(destination, offset, length), diagnostic);
						assertArrayEquals(expected, destination, diagnostic);
						position += count;
					}
					case 4 -> {
						int requested = random.nextInt(payload.length + 17);
						ByteBuffer destination = ByteBuffer.allocate(requested + 4);
						destination.position(2);
						int before = destination.position();
						if (requested > payload.length - position) {
							assertThrows(IndexOutOfBoundsException.class,
									() -> input.readNBytes(requested, destination), diagnostic);
							assertEquals(before, destination.position(), diagnostic);
						} else {
							input.readNBytes(requested, destination);
							assertEquals(before + requested, destination.position(), diagnostic);
							assertArrayEquals(Arrays.copyOfRange(payload, position, position + requested),
									Arrays.copyOfRange(destination.array(), before, before + requested), diagnostic);
							position += requested;
						}
					}
					case 5 -> {
						int requested = random.nextInt(payload.length + 17);
						if (requested > payload.length - position) {
							assertThrows(IndexOutOfBoundsException.class,
									() -> input.readString(requested, StandardCharsets.UTF_8), diagnostic);
						} else {
							assertEquals(new String(payload, position, requested, StandardCharsets.UTF_8),
									input.readString(requested, StandardCharsets.UTF_8), diagnostic);
							position += requested;
						}
					}
					case 6 -> {
						long requested = random.nextInt(payload.length + 33);
						long skipped = Math.min(requested, payload.length - position);
						assertEquals(skipped, input.skip(requested), diagnostic);
						position += (int) skipped;
					}
					case 7 -> {
						input.mark(random.nextInt(1_025));
						mark = position;
					}
					case 8 -> {
						input.reset();
						position = mark;
					}
					case 9 -> {
						int requested = random.nextInt(payload.length + 17);
						input.position(requested);
						position = Math.min(requested, payload.length);
					}
					case 10 -> {
						assertArrayEquals(Arrays.copyOfRange(payload, position, payload.length),
								input.readAllBytes(), diagnostic);
						position = payload.length;
					}
					case 11 -> input.close();
					default -> throw new AssertionError(kind);
				}
				assertEquals(position, input.position(), diagnostic);
				assertEquals(payload.length - position, input.available(), diagnostic);
				assertEquals(payload.length, input.length(), diagnostic);
				assertTrue(input.markSupported(), diagnostic);
			}
			assertThrows(IllegalArgumentException.class, () -> input.mark(-1));
			assertThrows(IllegalArgumentException.class, () -> input.readNBytes(-1));
		}
	}

	@Test
	void growableByteArrayOutputStateMachineFuzzesSparseOverwriteResetTrimAndCapacity() {
		var random = new Random(OUTPUT_SEED);
		for (int caseIndex = 0; caseIndex < OUTPUT_CASES; caseIndex++) {
			SafeByteArrayOutputStream output = new SafeByteArrayOutputStream(random.nextInt(65));
			OutputModel model = new OutputModel();
			for (int operationIndex = 0; operationIndex < 350; operationIndex++) {
				int kind = random.nextInt(7);
				String diagnostic = diagnostic(OUTPUT_SEED, caseIndex, operationIndex, kind);
				switch (kind) {
					case 0 -> {
						int value = random.nextInt();
						output.write(value);
						model.write((byte) value);
					}
					case 1 -> {
						byte[] source = new byte[random.nextInt(65)];
						random.nextBytes(source);
						int offset = random.nextInt(source.length + 1);
						int length = random.nextInt(source.length - offset + 1);
						output.write(source, offset, length);
						model.write(source, offset, length);
					}
					case 2 -> {
						int position = random.nextInt(model.length + 33);
						output.position(position);
						model.position = position;
					}
					case 3 -> {
						output.reset();
						model.reset();
					}
					case 4 -> {
						output.trim();
						model.trim();
						assertEquals(model.length, output.array.length, diagnostic);
					}
					case 5 -> {
						int requested = random.nextInt(65);
						output.ensureWritable(requested);
						assertTrue(output.array.length >= model.position + requested, diagnostic);
					}
					case 6 -> {
						byte[] before = output.toByteArray();
						assertThrows(IndexOutOfBoundsException.class,
								() -> output.write(new byte[4], -1, 1), diagnostic);
						assertArrayEquals(before, output.toByteArray(), diagnostic);
					}
					default -> throw new AssertionError(kind);
				}
				assertEquals(model.position, output.position(), diagnostic);
				assertEquals(model.length, output.length(), diagnostic);
				assertArrayEquals(model.bytes(), output.toByteArray(), diagnostic);
				assertTrue(output.toString().startsWith("SafeByteArrayOutputStream["), diagnostic);
			}
		}
	}

	@Test
	void wrappedByteArrayOutputFuzzesRegionRelativePositionsBoundsResetAndAliasing() {
		var random = new Random(OUTPUT_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < OUTPUT_CASES; caseIndex++) {
			byte[] backing = new byte[1 + random.nextInt(257)];
			random.nextBytes(backing);
			int from = random.nextInt(backing.length + 1);
			int to = from + random.nextInt(backing.length - from + 1);
			SafeByteArrayOutputStream output = new SafeByteArrayOutputStream(backing, from, to);
			byte[] region = Arrays.copyOfRange(backing, from, to);
			int position = 0;
			for (int operationIndex = 0; operationIndex < 250; operationIndex++) {
				int kind = random.nextInt(6);
				String diagnostic = diagnostic(OUTPUT_SEED, caseIndex, operationIndex, kind)
						+ ", region=" + region.length + ", position=" + position;
				if (kind == 0) {
					int value = random.nextInt();
					if (position == region.length) {
						assertThrows(IndexOutOfBoundsException.class, () -> output.write(value), diagnostic);
					} else {
						output.write(value);
						region[position++] = (byte) value;
					}
				} else if (kind == 1) {
					byte[] source = new byte[random.nextInt(65)];
					random.nextBytes(source);
					int length = random.nextInt(source.length + 1);
					if (length > region.length - position) {
						byte[] before = backing.clone();
						assertThrows(IndexOutOfBoundsException.class,
								() -> output.write(source, 0, length), diagnostic);
						assertArrayEquals(before, backing, diagnostic);
					} else {
						output.write(source, 0, length);
						System.arraycopy(source, 0, region, position, length);
						position += length;
					}
				} else if (kind == 2) {
					position = random.nextInt(region.length + 1);
					output.position(position);
				} else if (kind == 3) {
					output.reset();
					position = 0;
				} else if (kind == 4) {
					int requested = random.nextInt(region.length + 17);
					if (requested > region.length - position) {
						assertThrows(IndexOutOfBoundsException.class,
								() -> output.ensureWritable(requested), diagnostic);
					} else {
						output.ensureWritable(requested);
					}
				} else {
					output.trim();
				}
				assertEquals(position, output.position(), diagnostic);
				assertEquals(region.length, output.length(), diagnostic);
				assertArrayEquals(region, output.toByteArray(), diagnostic);
				assertArrayEquals(region, Arrays.copyOfRange(backing, from, to), diagnostic);
			}
		}
		assertThrows(IndexOutOfBoundsException.class,
				() -> new SafeByteArrayOutputStream(new byte[4], -1, 2));
		assertThrows(IndexOutOfBoundsException.class,
				() -> new SafeByteArrayOutputStream(new byte[4], 3, 2));
		assertThrows(IndexOutOfBoundsException.class,
				() -> new SafeByteArrayOutputStream(new byte[4], 0, 5));
	}

	@Test
	void dataStreamsMatchJdkWireEncodingAndRoundTripRandomPrimitiveAndTextTraces() throws Exception {
		var random = new Random(DATA_SEED);
		for (int caseIndex = 0; caseIndex < DATA_CASES; caseIndex++) {
			boolean bool = random.nextBoolean();
			byte byteValue = (byte) random.nextInt();
			short shortValue = (short) random.nextInt();
			char charValue = (char) random.nextInt();
			int intValue = random.nextInt();
			long longValue = random.nextLong();
			long int52Value = random.nextLong() & Int52.MAX_VALUE_L;
			float floatValue = Float.intBitsToFloat(random.nextInt());
			double doubleValue = Double.longBitsToDouble(random.nextLong());
			String text = randomString(random);
			byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
			String diagnostic = diagnostic(DATA_SEED, caseIndex, textBytes.length, intValue);

			SafeByteArrayOutputStream sink = new SafeByteArrayOutputStream(random.nextInt(17));
			SafeDataOutputStream output = new SafeDataOutputStream(sink);
			output.writeBoolean(bool);
			output.writeByte(byteValue);
			output.writeShort(shortValue);
			output.writeChar(charValue);
			output.writeInt(intValue);
			output.writeLong(longValue);
			output.writeInt52(int52Value);
			output.writeFloat(floatValue);
			output.writeDouble(doubleValue);
			output.writeShortText(text, StandardCharsets.UTF_8);
			output.writeMediumText(text, StandardCharsets.UTF_8);

			ByteArrayOutputStream expectedBytes = new ByteArrayOutputStream();
			try (DataOutputStream expected = new DataOutputStream(expectedBytes)) {
				expected.writeBoolean(bool);
				expected.writeByte(byteValue);
				expected.writeShort(shortValue);
				expected.writeChar(charValue);
				expected.writeInt(intValue);
				expected.writeLong(longValue);
				expected.write(int52Bytes(int52Value));
				expected.writeFloat(floatValue);
				expected.writeDouble(doubleValue);
				expected.writeShort(textBytes.length);
				expected.write(textBytes);
				expected.writeInt(textBytes.length);
				expected.write(textBytes);
			}
			byte[] wire = sink.toByteArray();
			assertArrayEquals(expectedBytes.toByteArray(), wire, diagnostic);
			assertEquals(wire.length, output.size(), diagnostic);

			SafeDataInputStream input = new SafeDataInputStream(
					new SafeByteArrayInputStream(wire), UNLIMITED);
			assertEquals(bool, input.readBoolean(), diagnostic);
			assertEquals(byteValue, input.readByte(), diagnostic);
			assertEquals(shortValue, input.readShort(), diagnostic);
			assertEquals(charValue, input.readChar(), diagnostic);
			assertEquals(intValue, input.readInt(), diagnostic);
			assertEquals(longValue, input.readLong(), diagnostic);
			assertEquals(int52Value, input.readInt52(), diagnostic);
			assertEquals(Float.floatToIntBits(floatValue), Float.floatToIntBits(input.readFloat()), diagnostic);
			assertEquals(Double.doubleToLongBits(doubleValue), Double.doubleToLongBits(input.readDouble()), diagnostic);
			assertEquals(text, input.readShortText(StandardCharsets.UTF_8), diagnostic);
			assertEquals(text, input.readMediumText(StandardCharsets.UTF_8), diagnostic);
			assertEquals(0, input.remainingBytesIfKnown(), diagnostic);
		}
	}

	@Test
	void primitiveTruncationAndZeroProgressBulkReadsFuzzStableFailuresAndExactProgress() {
		var random = new Random(DATA_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < DATA_CASES; caseIndex++) {
			int kind = random.nextInt(11);
			int width = switch (kind) {
				case 0, 1, 2 -> 1;
				case 3, 4, 5 -> 2;
				case 6, 8 -> 4;
				case 7, 9 -> 8;
				case 10 -> 7;
				default -> throw new AssertionError(kind);
			};
			byte[] truncated = new byte[random.nextInt(width)];
			random.nextBytes(truncated);
			SafeDataInputStream input = new SafeDataInputStream(
					new SafeByteArrayInputStream(truncated), UNLIMITED);
			String diagnostic = diagnostic(DATA_SEED, caseIndex, kind, truncated.length);
			assertThrows(MalformedDataException.class, () -> readPrimitive(input, kind), diagnostic);

			byte[] source = new byte[random.nextInt(257)];
			random.nextBytes(source);
			ZeroProgressInputStream hostile = new ZeroProgressInputStream(source);
			SafeDataInputStream bulk = new SafeDataInputStream(hostile, UNLIMITED);
			byte[] destination = new byte[source.length + 4];
			Arrays.fill(destination, (byte) 0x5A);
			bulk.readFully(destination, 2, source.length);
			assertArrayEquals(source, Arrays.copyOfRange(destination, 2, 2 + source.length), diagnostic);
			assertEquals(source.length, hostile.position, diagnostic);

			ZeroProgressInputStream byteBufferSource = new ZeroProgressInputStream(source);
			SafeDataInputStream byteBufferInput = new SafeDataInputStream(byteBufferSource, UNLIMITED);
			ByteBuffer buffer = ByteBuffer.allocate(source.length + 4);
			buffer.position(2);
			byteBufferInput.readFully(buffer, source.length);
			assertArrayEquals(source, Arrays.copyOfRange(buffer.array(), 2, 2 + source.length), diagnostic);
		}
	}

	@Test
	@SuppressWarnings("deprecation")
	void deprecatedTextCounterOverflowAndByteBufferBoundsCoverDataStreamEdgeSurfaces() throws Exception {
		var random = new Random(DATA_SEED ^ 0x55AA_55AA_55AA_55AAL);
		for (int caseIndex = 0; caseIndex < DELEGATE_CASES; caseIndex++) {
			char[] units = new char[random.nextInt(65)];
			for (int i = 0; i < units.length; i++) units[i] = (char) random.nextInt();
			String value = new String(units);
			SafeByteArrayOutputStream safeSink = new SafeByteArrayOutputStream();
			SafeDataOutputStream safe = new SafeDataOutputStream(safeSink);
			safe.writeBytes(value);
			safe.writeChars(value);
			ByteArrayOutputStream jdkSink = new ByteArrayOutputStream();
			try (DataOutputStream jdk = new DataOutputStream(jdkSink)) {
				jdk.writeBytes(value);
				jdk.writeChars(value);
			}
			String diagnostic = diagnostic(DATA_SEED, caseIndex, units.length, safe.size());
			assertArrayEquals(jdkSink.toByteArray(), safeSink.toByteArray(), diagnostic);
			assertEquals(safeSink.length(), safe.size(), diagnostic);

			String utf = randomString(random);
			SafeByteArrayOutputStream utfSink = new SafeByteArrayOutputStream();
			SafeDataOutputStream utfOutput = new SafeDataOutputStream(utfSink);
			utfOutput.writeUTF(utf);
			SafeDataInputStream utfInput = new SafeDataInputStream(
					new SafeByteArrayInputStream(utfSink.toByteArray()), UNLIMITED);
			assertEquals(utf, utfInput.readUTF(), diagnostic);

			SafeByteArrayOutputStream oversizedSink = new SafeByteArrayOutputStream();
			SafeDataOutputStream oversized = new SafeDataOutputStream(oversizedSink);
			assertThrows(ValueTooLargeException.class,
					() -> oversized.writeShortText("a".repeat(65_536), StandardCharsets.UTF_8), diagnostic);
			assertEquals(0, oversized.size(), diagnostic);
			assertEquals(0, oversizedSink.length(), diagnostic);

			byte[] bytes = new byte[random.nextInt(65)];
			random.nextBytes(bytes);
			SafeDataInputStream bounded = new SafeDataInputStream(
					new SafeByteArrayInputStream(bytes), UNLIMITED);
			ByteBuffer destination = ByteBuffer.allocate(8);
			int sourceBefore = (int) bounded.remainingBytesIfKnown();
			assertThrows(IndexOutOfBoundsException.class,
					() -> bounded.readFully(destination, -1), diagnostic);
			assertThrows(IndexOutOfBoundsException.class,
					() -> bounded.readFully(destination, destination.remaining() + 1), diagnostic);
			assertEquals(sourceBefore, bounded.remainingBytesIfKnown(), diagnostic);
			assertEquals(0, destination.position(), diagnostic);
		}

		SafeByteArrayOutputStream overflowSink = new SafeByteArrayOutputStream();
		ExposedDataOutputStream overflow = new ExposedDataOutputStream(overflowSink);
		overflow.setWritten(Integer.MAX_VALUE);
		assertThrows(ArithmeticException.class, () -> overflow.writeByte(1));
		assertEquals(Integer.MAX_VALUE, overflow.size());
		assertArrayEquals(new byte[] {1}, overflowSink.toByteArray());
		ExposedDataOutputStream underflow = new ExposedDataOutputStream(new SafeByteArrayOutputStream());
		underflow.decrement(1);
		assertEquals(-1, underflow.size());
		underflow.setWritten(Integer.MIN_VALUE);
		assertThrows(ArithmeticException.class, () -> underflow.decrement(1));
		assertEquals(Integer.MIN_VALUE, underflow.size());
	}

	@Test
	void baseStreamsFiltersNullSinkAndRemainingLengthDelegationFuzzLifecycleEdges() {
		var random = new Random(DELEGATE_SEED);
		SafeMeasurableOutputStream measurableOutput = new SafeByteArrayOutputStream();
		SafeMeasurableStream measurable = measurableOutput;
		SafeRepositionableStream repositionable = (SafeRepositionableStream) measurableOutput;
		assertEquals(0, measurable.length());
		assertEquals(0, repositionable.position());
		for (int caseIndex = 0; caseIndex < DELEGATE_CASES; caseIndex++) {
			byte[] bytes = new byte[random.nextInt(513)];
			random.nextBytes(bytes);
			OneByteInputStream base = new OneByteInputStream(bytes);
			SafeFilterInputStream filter = new SafeFilterInputStream(base);
			int prefix = random.nextInt(bytes.length + 1);
			assertArrayEquals(Arrays.copyOf(bytes, prefix), filter.readNBytes(prefix));
			ByteArrayOutputStream transferred = new ByteArrayOutputStream();
			assertEquals(bytes.length - prefix, filter.transferTo(transferred));
			assertArrayEquals(Arrays.copyOfRange(bytes, prefix, bytes.length), transferred.toByteArray());
			filter.close();
			assertTrue(base.closed);

			TrackingOutputStream sink = new TrackingOutputStream();
			SafeFilterOutputStream outputFilter = new SafeFilterOutputStream(sink);
			outputFilter.write(bytes);
			outputFilter.flush();
			outputFilter.close();
			assertArrayEquals(bytes, sink.bytes.toByteArray());
			assertTrue(sink.flushed);
			assertTrue(sink.closed);

			SafeOutputStream nullSink = caseIndex == 0 ? new NullOutputStream()
					: SafeOutputStream.nullOutputStream();
			nullSink.write(bytes);
			nullSink.write(bytes, 0, bytes.length);
			nullSink.flush();
			nullSink.close();
			nullSink.close();
			assertThrows(IllegalStateException.class, () -> nullSink.write(1));
			assertThrows(IllegalStateException.class, () -> nullSink.write(bytes));

			SafeDataInputStream known = new SafeDataInputStream(
					new SafeByteArrayInputStream(bytes), UNLIMITED);
			assertEquals(bytes.length, known.remainingBytesIfKnown());
			known.skipBytes(prefix);
			assertEquals(bytes.length - prefix, known.remainingBytesIfKnown());
			SafeDataInputStream unknown = new SafeDataInputStream(new OneByteInputStream(bytes), UNLIMITED);
			assertEquals(-1, unknown.remainingBytesIfKnown());
			SafeDataInputStream inconsistent = new SafeDataInputStream(
					new ReportedLengthInputStream(10, 11), UNLIMITED);
			assertThrows(MalformedDataException.class, inconsistent::remainingBytesIfKnown);
			SafeDataInputStream unsupported = new SafeDataInputStream(
					new UnsupportedLengthInputStream(), UNLIMITED);
			assertEquals(-1, unsupported.remainingBytesIfKnown());
		}
	}

	private static Object readPrimitive(SafeDataInput input, int kind) {
		return switch (kind) {
			case 0 -> input.readBoolean();
			case 1 -> input.readByte();
			case 2 -> input.readUnsignedByte();
			case 3 -> input.readShort();
			case 4 -> input.readUnsignedShort();
			case 5 -> input.readChar();
			case 6 -> input.readInt();
			case 7 -> input.readLong();
			case 8 -> input.readFloat();
			case 9 -> input.readDouble();
			case 10 -> input.readInt52();
			default -> throw new AssertionError(kind);
		};
	}

	private static byte[] int52Bytes(long value) {
		return new byte[] {
				(byte) (value >>> 48 & 0x0F),
				(byte) (value >>> 40),
				(byte) (value >>> 32),
				(byte) (value >>> 24),
				(byte) (value >>> 16),
				(byte) (value >>> 8),
				(byte) value};
	}

	private static String randomString(Random random) {
		int length = random.nextInt(65);
		StringBuilder value = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			int codePoint = switch (random.nextInt(5)) {
				case 0 -> random.nextInt(0x80);
				case 1 -> 0x80 + random.nextInt(0x780);
				case 2 -> 0x800 + random.nextInt(0xD800 - 0x800);
				case 3 -> 0xE000 + random.nextInt(0x10000 - 0xE000);
				default -> 0x10000 + random.nextInt(0x10FFFF - 0x10000 + 1);
			};
			value.appendCodePoint(codePoint);
		}
		return value.toString();
	}

	private static String diagnostic(long seed, int caseIndex, int operationIndex, int kind) {
		return "seed=" + seed + ", case=" + caseIndex + ", operation=" + operationIndex + ", kind=" + kind;
	}

	private static final class OutputModel {
		private byte[] data = new byte[0];
		private int position;
		private int length;

		private void write(byte value) {
			ensure(position + 1);
			if (position > length) Arrays.fill(data, length, position, (byte) 0);
			data[position++] = value;
			length = Math.max(length, position);
		}

		private void write(byte[] source, int offset, int count) {
			ensure(position + count);
			if (position > length) Arrays.fill(data, length, position, (byte) 0);
			System.arraycopy(source, offset, data, position, count);
			position += count;
			length = Math.max(length, position);
		}

		private void ensure(int capacity) {
			if (data.length < capacity) data = Arrays.copyOf(data, capacity);
		}

		private void reset() {
			position = 0;
			length = 0;
		}

		private void trim() {
			data = Arrays.copyOf(data, length);
		}

		private byte[] bytes() {
			return Arrays.copyOf(data, length);
		}
	}

	private static final class ZeroProgressInputStream extends SafeInputStream {
		private final byte[] bytes;
		private int position;
		private boolean zero = true;

		private ZeroProgressInputStream(byte[] bytes) { this.bytes = bytes; }

		@Override public int read() {
			return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
		}

		@Override public int read(byte[] destination, int offset, int length) {
			if (length == 0) return 0;
			if (position == bytes.length) return -1;
			if (zero) {
				zero = false;
				return 0;
			}
			zero = true;
			int count = Math.min(Math.min(length, 3), bytes.length - position);
			System.arraycopy(bytes, position, destination, offset, count);
			position += count;
			return count;
		}
	}

	private static final class OneByteInputStream extends SafeInputStream {
		private final byte[] bytes;
		private int position;
		private boolean closed;
		private OneByteInputStream(byte[] bytes) { this.bytes = bytes; }
		@Override public int read() {
			return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
		}
		@Override public void close() { closed = true; }
	}

	private static final class TrackingOutputStream extends SafeOutputStream {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private boolean flushed;
		private boolean closed;
		@Override public void write(int value) { bytes.write(value); }
		@Override public void write(byte[] source, int offset, int length) { bytes.write(source, offset, length); }
		@Override public void flush() { flushed = true; }
		@Override public void close() { closed = true; }
	}

	private static final class ExposedDataOutputStream extends SafeDataOutputStream {
		private ExposedDataOutputStream(SafeOutputStream output) { super(output); }
		private void setWritten(int value) { written = value; }
		private void decrement(int value) { decCount(value); }
	}

	private static final class ReportedLengthInputStream extends SafeMeasurableInputStream {
		private final long length;
		private final long position;
		private ReportedLengthInputStream(long length, long position) {
			this.length = length;
			this.position = position;
		}
		@Override public int read() { return -1; }
		@Override public long length() { return length; }
		@Override public long position() { return position; }
	}

	private static final class UnsupportedLengthInputStream extends SafeMeasurableInputStream {
		@Override public int read() { return -1; }
		@Override public long length() { throw new UnsupportedOperationException(); }
		@Override public long position() { throw new UnsupportedOperationException(); }
	}
}
