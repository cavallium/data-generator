package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutputStream;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The spare high nibble of an {@code Int52} is not part of its value: every read path ignores it, and a value
 * writer never emits one.
 *
 * <p>An {@code Int52} is the unsigned 52-bit domain {@code 0 .. 0x0F_FF_FF_FF_FF_FF_FF}
 * ({@link Int52#MIN_VALUE_L}, {@link Int52#MAX_VALUE_L}). It is stored in seven bytes, so the high nibble of
 * the lead byte is not part of the value and can hold anything once bytes did not come from a value writer.
 * This test pins that the read paths agree on ignoring it — {@link Buf#getInt52(int)}, the streaming
 * {@code BufDataInputCore#readInt52()}, the absolute {@code BufDataInputCore#getInt52At(int)} and the
 * standalone {@link Int52} decoder — across the heap and memory-segment backings and through a view; and that
 * {@code SafeDataOutputStream#writeInt52} writes a zero nibble, which is what keeps the rule wire-neutral for
 * value bytes.</p>
 *
 * <p>The nibble is reserved rather than merely unused: the nullable Int52 encoding keeps its presence tag in
 * the top bit of that same byte. Masking is therefore required, not cosmetic — see
 * {@link #thePresenceTagInTheLeadByteDoesNotLeakIntoTheValue()}.</p>
 *
 * <p>The expectation is assembled here from the raw bytes rather than from {@link Int52}, so the test stays an
 * independent oracle for the mask.</p>
 */
class Int52LeadNibbleCoherenceTest {

	private static final DecodeLimits LIMITS = DecodeLimits.unlimited();

	/** Two seven-byte Int52 fields, each with a spare nibble, plus one trailing byte for views. */
	private static final int SIZE = Int52.BYTES * 2 + 1;

	private record Backing(String name, Buf buf) {
	}

	private static byte[] source(int firstNibble, int secondNibble, long firstPayload, long secondPayload) {
		var bytes = new byte[SIZE];
		writeField(bytes, 0, firstNibble, firstPayload);
		writeField(bytes, Int52.BYTES, secondNibble, secondPayload);
		bytes[SIZE - 1] = 0x5A;
		return bytes;
	}

	private static void writeField(byte[] bytes, int offset, int nibble, long payload) {
		bytes[offset] = (byte) (nibble << 4);
		for (int i = 1; i < Int52.BYTES; i++) {
			bytes[offset + i] = (byte) (payload >>> (8 * (Int52.BYTES - 1 - i)));
		}
	}

	/** Every backing plus a forced view: {@code subListForced} keeps the whole content and is still a view. */
	private static List<Backing> backings(byte[] bytes) {
		var out = new ArrayList<Backing>();
		out.add(new Backing("heap-array", Buf.wrap(bytes.clone())));
		out.add(new Backing("heap-heap-buffer",
				new MemorySegmentBuf(MemorySegment.ofBuffer(ByteBuffer.wrap(bytes.clone()).order(ByteOrder.nativeOrder())))));
		out.add(new Backing("native-array-segment", new MemorySegmentBuf(MemorySegment.ofArray(bytes.clone()))));
		var direct = new MemorySegmentBuf(
				MemorySegment.ofBuffer(ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder())));
		direct.setBytesFromBuf(0, Buf.wrap(bytes.clone()), 0, bytes.length);
		out.add(new Backing("native-direct-buffer", direct));
		out.add(new Backing("heap-view", Buf.wrap(bytes.clone()).subListForced(0, bytes.length - 1)));
		out.add(new Backing("native-view",
				new MemorySegmentBuf(MemorySegment.ofArray(bytes.clone())).subListForced(0, bytes.length - 1)));
		return out;
	}

	/** A raw big-endian assembly of the seven bytes, which may sit outside the Int52 domain. */
	private static long raw(byte[] bytes, int offset) {
		long value = 0;
		for (int i = 0; i < Int52.BYTES; i++) {
			value = (value << 8) | (bytes[offset + i] & 0xFFL);
		}
		return value;
	}

	public static Stream<Object[]> dirtyNibbles() {
		long first = 0x0023_4567_89AB_CDL;  // uses all 48 low bits, so a masked nibble cannot hide behind zeroes
		long second = 0x00FE_DCBA_9876_54L;
		var cases = new ArrayList<Object[]>();
		for (int nibble = 0; nibble <= 0x0f; nibble++) {
			var bytes = source(nibble, 0x0f - nibble, first, second);
			for (Backing backing : backings(bytes)) {
				cases.add(new Object[]{nibble + "/" + (0x0f - nibble), backing, bytes});
			}
		}
		return cases.stream();
	}

	@ParameterizedTest
	@MethodSource("dirtyNibbles")
	void everyReadPathIgnoresTheSpareNibble(String nibbles, Backing backing, byte[] bytes) {
		String diagnostic = "nibbles=" + nibbles + ", backing=" + backing.name();
		long expectedFirst = raw(bytes, 0) & Int52.MAX_VALUE_L;
		long expectedSecond = raw(bytes, Int52.BYTES) & Int52.MAX_VALUE_L;
		Buf buf = backing.buf();

		assertEquals(expectedFirst, buf.getInt52(0), diagnostic + ", Buf#getInt52(first)");
		assertEquals(expectedSecond, buf.getInt52(Int52.BYTES), diagnostic + ", Buf#getInt52(second)");

		var cursor = new BufDataCursor(LIMITS);
		cursor.bind(buf, 0, buf.size());
		assertEquals(expectedFirst, cursor.readInt52(), diagnostic + ", cursor#readInt52(first)");
		assertEquals(expectedSecond, cursor.readInt52(), diagnostic + ", cursor#readInt52(second) advances 7 bytes");
		cursor.position(0);
		assertEquals(expectedFirst, cursor.getInt52At(0), diagnostic + ", cursor#getInt52At(first)");
		assertEquals(expectedSecond, cursor.getInt52At(Int52.BYTES), diagnostic + ", cursor#getInt52At(second)");
		cursor.unbind();

		assertEquals(expectedFirst, Int52.fromByteArrayL(bytes), diagnostic + ", Int52#fromByteArrayL");
		assertEquals(expectedFirst, Int52.fromBytes(
				bytes[0],
				bytes[1],
				bytes[2],
				bytes[3],
				bytes[4],
				bytes[5],
				bytes[6]).longValue(), diagnostic + ", Int52#fromBytes stays inside the domain");
	}

	@ParameterizedTest
	@MethodSource("dirtyNibbles")
	void everyReadPathStaysInsideTheDomain(String nibbles, Backing backing, byte[] bytes) {
		Buf buf = backing.buf();
		for (int offset = 0; offset + Int52.BYTES <= buf.size(); offset++) {
			long value = buf.getInt52(offset);
			if (value < Int52.MIN_VALUE_L || value > Int52.MAX_VALUE_L) {
				throw new AssertionError("out of domain at " + offset + ": " + value + " (" + nibbles + ", "
						+ backing.name() + ")");
			}
		}
	}

	/**
	 * A write through {@link Buf#setInt52} cannot carry a dirty nibble: the domain is validated, so a foreign
	 * nibble only ever arrives through bytes this library did not write.
	 */
	@ParameterizedTest
	@MethodSource("dirtyNibbles")
	void setInt52RejectsOutOfDomainValues(String nibbles, Backing backing, byte[] bytes) {
		Buf buf = backing.buf();
		assertThrows(IllegalArgumentException.class, () -> buf.setInt52(0, Int52.MAX_VALUE_L + 1),
				"nibbles=" + nibbles + ", backing=" + backing.name());
	}

	/**
	 * Why the nibble is reserved rather than merely spare: {@code NullableInt52Serializer} writes
	 * {@code 0b10000000} for an empty value, and {@code Int52Serializer} masks this nibble on both sides,
	 * because the presence tag shares the lead byte with the value's top four bits. A reader that took the
	 * nibble literally would read {@code 1 << 59} out of a tag byte.
	 */
	@Test
	void thePresenceTagInTheLeadByteDoesNotLeakIntoTheValue() {
		long payload = 0x0023_4567_89AB_CDL;
		var bytes = source(0b1000, 0b0000, payload, payload);
		assertEquals((byte) 0b1000_0000, bytes[0], "the lead byte of an empty nullable Int52");
		for (Backing backing : backings(bytes)) {
			var diagnostic = backing.name();
			assertEquals(payload, backing.buf().getInt52(0), diagnostic + ", Buf#getInt52");
			var cursor = new BufDataCursor(LIMITS);
			cursor.bind(backing.buf(), 0, backing.buf().size());
			assertEquals(payload, cursor.readInt52(), diagnostic + ", cursor#readInt52");
			assertEquals(payload, cursor.getInt52At(0), diagnostic + ", cursor#getInt52At");
			cursor.unbind();
			assertEquals(payload, Int52.fromByteArray(bytes).longValue(), diagnostic + ", Int52#fromByteArray");
			assertEquals(payload, Int52.fromByteArrayL(bytes), diagnostic + ", Int52#fromByteArrayL");
		}
	}

	/**
	 * The other half of the rule: a value writer drops the nibble too, so no Int52 value this library writes
	 * has a dirty nibble to disagree about, and an out-of-domain value round-trips to its masked value.
	 */
	@ParameterizedTest
	@MethodSource("dirtyValues")
	void writerNeverEmitsASpareNibble(long value, long expected) {
		byte[] written = writeInt52(value);
		assertEquals(0, (written[0] & 0xff) >>> 4, "lead nibble of " + Long.toHexString(value));
		assertArrayEquals(writeInt52(expected), written, "same bytes as the in-domain value " + Long.toHexString(expected));
		assertEquals(expected, Int52.fromByteArrayL(written), "round trip of " + Long.toHexString(value));
	}

	public static Stream<Object[]> dirtyValues() {
		long payload = 0x0023_4567_89AB_CDL;
		var cases = new ArrayList<Object[]>();
		for (int nibble = 0; nibble <= 0x0f; nibble++) {
			long value = ((long) nibble << 48) | payload;
			cases.add(new Object[]{value, value & Int52.MAX_VALUE_L});
		}
		return cases.stream();
	}

	private static byte[] writeInt52(long value) {
		var sink = new SafeByteArrayOutputStream();
		var out = new SafeDataOutputStream(sink);
		out.writeInt52(value);
		out.flush();
		return sink.toByteArray();
	}
}
