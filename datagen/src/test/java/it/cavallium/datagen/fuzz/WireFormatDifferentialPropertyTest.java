package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Property;

/**
 * Differential oracle for the <em>wire format itself</em>, independent of the repository's readers.
 *
 * <p>Every other fuzz property pins the writer against a reader of the same project, so a bug that
 * changes both sides symmetrically is invisible there. This class instead compares the bytes
 * produced by the real write entry points against an expected-bytes oracle built from JDK
 * primitives, and decodes hand-written spec bytes with {@link SafeDataInput}. The two directions
 * together pin the format without ever trusting {@code BufDataOutput} and {@code BufDataInput} to
 * agree with each other.</p>
 *
 * <h2>The format this test pins</h2>
 *
 * <p>Taken from the writer itself, which is the only thing the readers are specified against:</p>
 * <ul>
 *     <li>{@code SafeDataOutputStream#writeBoolean} (line 158) — one byte, {@code 1} or {@code 0}.</li>
 *     <li>{@code write(int)} / {@code writeByte} — the low eight bits only.</li>
 *     <li>{@code writeShort} / {@code writeChar} / {@code writeInt} / {@code writeLong}
 *     (lines 183–240) — <strong>fixed-width big-endian</strong>, high byte first, exactly like
 *     {@code java.io.DataOutputStream}. The format has <em>no</em> varint/variable-length integer
 *     anywhere on this path: {@code grep} for a varint in {@code it.cavallium.stream} /
 *     {@code it.cavallium.buffer} finds none, and every length prefix below is a fixed-width
 *     big-endian count.</li>
 *     <li>{@code writeInt52} (lines 250–260) — a <strong>seven-byte</strong> field whose lead byte
 *     is written as {@code (byte)(v >> 48 & 0xf)}, i.e. an unsigned 52-bit big-endian value; the
 *     reader masks the same nibble ({@code BufDataInputCore#readInt52}, lines 646–655), and
 *     {@code Int52.MIN_VALUE_L == 0}/{@code MAX_VALUE_L == 0x000F_FFFF_FFFF_FFFFL}
 *     ({@link Int52}) confirm the domain. Bits above 52 are therefore <em>silently dropped</em> by
 *     the writer, which is why this test expects {@code v & 0x000F_FFFF_FFFF_FFFFL} and pins
 *     {@code -1} and {@code 1L << 52} explicitly.</li>
 *     <li>{@code writeFloat} / {@code writeDouble} (lines 274–292) — the bit-strength is
 *     documented: {@code Float.floatToIntBits} / {@code Double.doubleToLongBits}, so every NaN
 *     collapses to the canonical single-quiet-NaN pattern and both signed zeros are preserved. That
 *     is what makes bit-for-byte NaN assertions legal here; the raw-bits variants are pinned in
 *     {@link #pinnedWireVectorsPinEndiannessSignedZeroCanonicalNaNAndInt52Masking()}.</li>
 *     <li>{@code writeShortText(String, Charset)} (lines 363–374) — an <strong>unsigned-short
 *     big-endian byte count</strong> of the charset-encoded payload, then the payload; a payload
 *     above {@code 0xFFFF} bytes throws {@link ValueTooLargeException}. {@code writeMediumText}
 *     (lines 377–386) is the same with a four-byte big-endian count.</li>
 *     <li>Both text forms encode with {@code String.getBytes(charset)} — <strong>real UTF-8</strong>,
 *     not {@code DataOutput}'s modified UTF-8. {@code U+0000} is one {@code 0x00} byte (modified
 *     UTF-8 would write {@code 0x00 0x80}) and an astral code point is four bytes (modified UTF-8
 *     would write a six-byte surrogate pair), so the oracle hand-rolls those two framings instead of
 *     calling {@code DataOutputStream#writeUTF}.</li>
 * </ul>
 *
 * <p>{@code tries = 1} on purpose: the tier ladder, not jqwik, owns the case count, and every value
 * comes from {@link FuzzValues} through {@link FuzzTiers#random(String)}, so a fixed
 * {@code -Ddatagen.fuzz.seed} replays the whole case set. Nothing here is ever relaxed to make a
 * mismatch pass: the oracle is the documented format above.</p>
 */
class WireFormatDifferentialPropertyTest {

	/** Wire domain of the seven-byte Int52 field: the writer drops everything above bit 51. */
	private static final long INT52_MASK = 0x000F_FFFF_FFFF_FFFFL;

	/** A second charset, to pin that the text prefix counts <em>encoded bytes</em> for any charset. */
	private static final Charset UTF_16LE = StandardCharsets.UTF_16LE;

	/** The kinds the read direction can decode back to a stated value (fixed values + length prefixes). */
	private static final Kind[] READABLE = {
			Kind.BOOLEAN, Kind.BYTE, Kind.SHORT, Kind.CHAR, Kind.INT, Kind.LONG, Kind.INT52,
			Kind.FLOAT, Kind.DOUBLE, Kind.SHORT_TEXT, Kind.MEDIUM_TEXT, Kind.SHORT_TEXT_UTF16,
	};

	/** The full primitive write surface, including the raw-byte and deprecated string routes. */
	private static final Kind[] WRITABLE = {
			Kind.BOOLEAN, Kind.BYTE, Kind.RAW_INT, Kind.SHORT, Kind.CHAR, Kind.INT, Kind.LONG,
			Kind.INT52, Kind.FLOAT, Kind.DOUBLE, Kind.RAW_BYTES, Kind.RAW_BYTES_RANGE, Kind.BYTES_OF_BUF,
			Kind.SHORT_TEXT, Kind.MEDIUM_TEXT, Kind.SHORT_TEXT_UTF16, Kind.BYTES_STRING, Kind.CHARS,
	};

	/** Signed edges, including both zeros, both signs of one, and the two {@code long} extremes. */
	private static final long[] LONG_EDGES = {
			0L, 1L, -1L, 2L, -2L, Short.MAX_VALUE, Short.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
			Long.MAX_VALUE, Long.MIN_VALUE, 0xFFL, 0x100L, 0xFFFFL, 0x10000L, 1L << 51, 1L << 52, (1L << 52) - 1,
	};

	private static final int[] INT_EDGES = {
			0, 1, -1, 2, -2, 0x7F, 0x80, 0xFF, 0x100, 0x7FFF, -0x8000, Integer.MAX_VALUE, Integer.MIN_VALUE,
			1 << 24, -(1 << 24),
	};

	/** Floats whose <em>bits</em> matter: both zeros, both infinities, canonical and raw-bit NaN. */
	private static final float[] FLOAT_EDGES = {
			0.0f, -0.0f, 1.0f, -1.0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
			Float.MIN_VALUE, -Float.MIN_VALUE, Float.MAX_VALUE, Float.MIN_NORMAL,
			Float.intBitsToFloat(0x7F80_0001), Float.intBitsToFloat(0xFFC0_0001), Float.intBitsToFloat(0x0000_0001),
	};

	private static final double[] DOUBLE_EDGES = {
			0.0d, -0.0d, 1.0d, -1.0d, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
			Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_NORMAL,
			Double.longBitsToDouble(0x7FF0_0000_0000_0001L), Double.longBitsToDouble(0xFFF8_0000_0000_0001L),
			Double.longBitsToDouble(0x0000_0000_0000_0001L),
	};

	/** Strings around the UTF-8 boundaries a length prefix can expose: empty, NUL, walls, astral. */
	private static final String[] STRING_EDGES = {
			"", "a", "ab", "\u0000", "a\u0000b", "\u007f", "\u0080", "\u07ff", "\u0800", "\uD800", "\uDC00",
			"\uD83D\uDE00", "\uD83D", "é", "你好", "\u0301", "\u0000", "ÿ",
	};

	// ---------------------------------------------------------------- write side

	@Property(tries = 1)
	void primitiveWriteScriptBytesMatchTheIndependentSpecOracle() throws IOException {
		Random rnd = FuzzTiers.random("wire-write-script");
		int cases = FuzzTiers.cases("wire-write-script", 2, 10, 40, 80);
		int scriptLength = FuzzTiers.cases("wire-write-script-length", 5, 20, 48, 96);
		for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
			List<Step> script = script(rnd, scriptLength, WRITABLE);
			BufDataOutput output = BufDataOutput.create();
			SpecOracle oracle = new SpecOracle();
			for (Step step : script) {
				apply(output, step);
				oracle.apply(step);
			}
			byte[] actual = output.asList().asArray();
			byte[] expected = oracle.toByteArray();
			assertArrayEquals(expected, actual,
					dossier("write script differs from the documented wire format", script, expected, actual,
							caseIndex));
			// the documented `written` counter and the repositionable-stream position must agree with
			// the byte count, or a later framing decision built on them would be off
			assertEquals(expected.length, output.size(),
					dossier("size() must count the emitted bytes", script, expected, actual, caseIndex));
			assertEquals(expected.length, output.position(),
					dossier("position() must track the emitted bytes", script, expected, actual, caseIndex));
		}
	}

	@Property(tries = 1)
	void shortTextPrefixStraddlesTheUnsignedShortWall() {
		FuzzTiers.requireMinimumTier(FuzzTiers.Tier.DEEP);
		Random rnd = FuzzTiers.random("wire-text-wall");
		int cases = FuzzTiers.cases("wire-text-wall", 1, 2, 6, 12);
		for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
			String text = FuzzValues.stringNearShortBoundary(rnd);
			byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
			// the pool promises an exact byte length around the wall; without this the case below is vacuous
			assertTrue(utf8.length >= 65534 && utf8.length <= 65537,
					gate("near-boundary string must straddle 65535/65536, was " + utf8.length));
			// medium text has a four-byte count, so it must encode the same payload without a wall
			byte[] medium = encode(output -> output.writeMediumText(text, StandardCharsets.UTF_8));
			byte[] expectedMedium = concat(new byte[] {
					(byte) (utf8.length >>> 24), (byte) (utf8.length >>> 16), (byte) (utf8.length >>> 8),
					(byte) utf8.length}, utf8);
			assertArrayEquals(expectedMedium, medium,
					gate("medium text prefix must be the four-byte UTF-8 length, got " + utf8.length + " bytes"));
			if (utf8.length > 0xFFFF) {
				assertThrows(ValueTooLargeException.class,
						() -> encode(output -> output.writeShortText(text, StandardCharsets.UTF_8)),
						gate("a " + utf8.length + "-byte UTF-8 payload must not fit an unsigned short"));
			} else {
				byte[] shortText = encode(output -> output.writeShortText(text, StandardCharsets.UTF_8));
				assertArrayEquals(concat(new byte[] {(byte) (utf8.length >>> 8), (byte) utf8.length}, utf8),
						shortText, gate("short text prefix must be the unsigned-short UTF-8 length"));
			}
		}
	}

	/**
	 * The unsigned-short wall itself, cheap enough for every tier: {@code 0xFFFF} encoded bytes are
	 * accepted with an unsigned prefix, {@code 0x10000} is refused rather than wrapped to zero.
	 */
	@Property(tries = 1)
	void shortTextAcceptsSixtyFiveFiveThreeFiveBytesAndRefusesOneMore() {
		byte[] atWall = encode(output -> output.writeShortText("a".repeat(0xFFFF), StandardCharsets.UTF_8));
		assertEquals(2 + 0xFFFF, atWall.length, gate("the 0xFFFF payload must be accepted"));
		assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF}, Arrays.copyOf(atWall, 2),
				gate("the 0xFFFF payload prefix must be unsigned 65535, not a signed -1"));
		byte[] oneMore = encode(output -> output.writeMediumText("a".repeat(0x10000), StandardCharsets.UTF_8));
		assertArrayEquals(new byte[] {0, 1, 0, 0}, Arrays.copyOf(oneMore, 4),
				gate("the same payload must be a plain 65536 in the four-byte framing"));
		assertThrows(ValueTooLargeException.class,
				() -> encode(output -> output.writeShortText("a".repeat(0x10000), StandardCharsets.UTF_8)),
				gate("a 0x10000-byte payload must be refused, not truncated to two bytes"));
	}

	// ------------------------------------------------------------------ pinned

	/**
	 * Hand-written hex vectors, both directions: these are what make an endianness, zero-sign or
	 * NaN-canonicalisation regression loud, at every tier including {@code quick}.
	 */
	@Property(tries = 1)
	void pinnedWireVectorsPinEndiannessSignedZeroCanonicalNaNAndInt52Masking() {
		assertWire("writeBoolean(true)", out -> out.writeBoolean(true), "01");
		assertWire("writeBoolean(false)", out -> out.writeBoolean(false), "00");
		assertWire("writeByte keeps the low byte", out -> out.writeByte(0x1FF), "ff");
		assertWire("writeByte(-1)", out -> out.writeByte(-1), "ff");
		assertWire("write(int) keeps the low byte", out -> out.write(0x2FF), "ff");
		assertWire("writeShort", out -> out.writeShort(0x0102), "0102");
		assertWire("writeShort(-1)", out -> out.writeShort(-1), "ffff");
		assertWire("writeChar", out -> out.writeChar('A'), "0041");
		assertWire("writeInt", out -> out.writeInt(0x0102_0304), "01020304");
		assertWire("writeInt(Integer.MIN_VALUE)", out -> out.writeInt(Integer.MIN_VALUE), "80000000");
		assertWire("writeLong", out -> out.writeLong(0x0102_0304_0506_0708L), "0102030405060708");
		assertWire("writeLong(Long.MIN_VALUE)", out -> out.writeLong(Long.MIN_VALUE), "8000000000000000");
		assertWire("writeFloat(+0.0)", out -> out.writeFloat(0.0f), "00000000");
		assertWire("writeFloat(-0.0)", out -> out.writeFloat(-0.0f), "80000000");
		assertWire("writeFloat(-1.0)", out -> out.writeFloat(-1.0f), "bf800000");
		assertWire("writeFloat canonicalises a raw NaN", out -> out.writeFloat(Float.intBitsToFloat(0x7F80_0001)),
				"7fc00000");
		// floatToIntBits collapses every NaN, negative ones included, to the single positive pattern
		assertWire("writeFloat canonicalises a negative raw NaN",
				out -> out.writeFloat(Float.intBitsToFloat(0xFF80_0001)), "7fc00000");
		assertWire("writeDouble(+0.0)", out -> out.writeDouble(0.0d), "0000000000000000");
		assertWire("writeDouble(-0.0)", out -> out.writeDouble(-0.0d), "8000000000000000");
		assertWire("writeDouble canonicalises a raw NaN",
				out -> out.writeDouble(Double.longBitsToDouble(0x7FF8_0000_0000_0001L)), "7ff8000000000000");
		assertWire("writeInt52(0)", out -> out.writeInt52(0L), "00000000000000");
		assertWire("writeInt52(1)", out -> out.writeInt52(1L), "00000000000001");
		assertWire("writeInt52(2^24)", out -> out.writeInt52(1L << 24), "00000001000000");
		assertWire("writeInt52(MAX_VALUE_L)", out -> out.writeInt52(Int52.MAX_VALUE_L), "0fffffffffffff");
		assertWire("writeInt52 drops bit 52", out -> out.writeInt52(1L << 52), "00000000000000");
		assertWire("writeInt52(-1) collapses to the 52-bit domain", out -> out.writeInt52(-1L), "0fffffffffffff");
		assertWire("writeShortText U+0000 is one byte (real UTF-8, not modified UTF-8)",
				out -> out.writeShortText("a\u0000b", StandardCharsets.UTF_8), "0003610062");
		assertWire("writeShortText astral is four UTF-8 bytes (not a six-byte surrogate pair)",
				out -> out.writeShortText("\uD83D\uDE00", StandardCharsets.UTF_8), "0004f09f9880");
		assertWire("writeShortText UTF-16LE prefix counts bytes and carries no BOM",
				out -> out.writeShortText("a", UTF_16LE), "00026100");
		assertWire("writeMediumText", out -> out.writeMediumText("héllo", StandardCharsets.UTF_8),
				"0000000668c3a96c6c6f");
		assertWire("writeBytes(String) writes the low byte of each char", out -> out.writeBytes("aĀ"), "6100");
		assertWire("writeChars(String) writes two bytes per char", out -> out.writeChars("ab"), "00610062");

		assertRead("readBoolean", "01", SafeDataInput::readBoolean, Boolean.TRUE);
		assertRead("readBoolean non-zero is true", "02", SafeDataInput::readBoolean, Boolean.TRUE);
		assertRead("readByte", "ff", SafeDataInput::readByte, (byte) -1);
		assertRead("readUnsignedByte", "ff", SafeDataInput::readUnsignedByte, 255);
		assertRead("readShort big-endian", "0102", SafeDataInput::readShort, (short) 0x0102);
		assertRead("readShort(-2)", "fffe", SafeDataInput::readShort, (short) -2);
		assertRead("readChar", "4142", in -> in.readChar(), (char) 0x4142);
		assertRead("readInt", "01020304", SafeDataInput::readInt, 0x0102_0304);
		assertRead("readInt(Integer.MIN_VALUE)", "80000000", SafeDataInput::readInt, Integer.MIN_VALUE);
		assertRead("readLong", "0102030405060708", SafeDataInput::readLong, 0x0102_0304_0506_0708L);
		assertRead("readLong(Long.MIN_VALUE)", "8000000000000000", SafeDataInput::readLong, Long.MIN_VALUE);
		assertRead("readLong(-1)", "ffffffffffffffff", SafeDataInput::readLong, -1L);
		assertRead("readFloat(+0.0)", "00000000", SafeDataInput::readFloat, 0.0f);
		assertRead("readFloat(-0.0)", "80000000", SafeDataInput::readFloat, -0.0f);
		assertRead("readFloat canonical NaN", "7fc00000", SafeDataInput::readFloat, Float.NaN);
		assertRead("readDouble(-0.0)", "8000000000000000", SafeDataInput::readDouble, -0.0d);
		assertRead("readDouble canonical NaN", "7ff8000000000000", SafeDataInput::readDouble, Double.NaN);
		assertRead("readInt52(MAX_VALUE_L)", "0fffffffffffff", SafeDataInput::readInt52, Int52.MAX_VALUE_L);
		assertRead("readInt52 masks the lead nibble", "ffffffffffffff", SafeDataInput::readInt52,
				Int52.MAX_VALUE_L);
		assertRead("readInt52 is unsigned", "00000000000001", SafeDataInput::readInt52, 1L);
		assertRead("readShortText decodes the one-byte NUL", "0003610062",
				in -> in.readShortText(StandardCharsets.UTF_8), "a\u0000b");
		assertRead("readShortText decodes four-byte astral", "0004f09f9880",
				in -> in.readShortText(StandardCharsets.UTF_8), "\uD83D\uDE00");
		assertRead("readMediumText", "0000000668c3a96c6c6f",
				in -> in.readMediumText(StandardCharsets.UTF_8), "héllo");
		assertRead("readShortText UTF-16LE", "00026100", in -> in.readShortText(UTF_16LE), "a");

		// truncated spec bytes must be refused, never silently short-read into a plausible value
		assertThrows(MalformedDataException.class, () -> read("010203", in -> in.readInt()),
				gate("a three-byte readInt must be refused"));
		assertThrows(MalformedDataException.class,
				() -> read("0004610203", in -> in.readShortText(StandardCharsets.UTF_8)),
				gate("a short text with a four-byte claim and three bytes must be refused"));
		assertThrows(MalformedDataException.class, () -> read("ffffffffff", in -> in.readInt52()),
				gate("a five-byte readInt52 must be refused"));
	}

	// ---------------------------------------------------------------- read side

	@Property(tries = 1)
	void handBuiltSpecBytesDecodeToTheSpecValues() throws IOException {
		Random rnd = FuzzTiers.random("wire-read-script");
		int cases = FuzzTiers.cases("wire-read-script", 2, 10, 40, 80);
		int scriptLength = FuzzTiers.cases("wire-read-script-length", 4, 16, 40, 80);
		for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
			List<Step> script = script(rnd, scriptLength, READABLE);
			// the bytes come from the spec oracle, never from BufDataOutput, so a symmetric
			// writer/reader bug cannot make this pass
			SpecOracle oracle = new SpecOracle();
			for (Step step : script) {
				oracle.apply(step);
			}
			byte[] spec = oracle.toByteArray();
			byte[] truncated = Arrays.copyOf(spec, spec.length - 1);

			BufDataInput bufIn = BufDataInput.create(Buf.wrap(spec.clone()), FuzzOracles.LIMITS);
			assertDecoded(script, bufIn, spec, caseIndex);
			assertEquals(0, bufIn.available(), dossier("BufDataInput must consume exactly the script",
					script, spec, spec, caseIndex));

			try (SafeDataInputStream stream = new SafeDataInputStream(new SafeByteArrayInputStream(spec.clone()),
					FuzzOracles.LIMITS)) {
				assertDecoded(script, stream, spec, caseIndex);
				assertEquals(0, stream.available(), dossier("SafeDataInputStream must consume exactly the script",
						script, spec, spec, caseIndex));
			}

			// cutting the last byte off the script must be refused rather than read as a shorter value
			assertThrows(MalformedDataException.class,
					() -> decodeAll(truncated, script, BufDataInput::create),
					dossier("a truncated script must be refused", script, spec, truncated, caseIndex));
		}
	}

	// --------------------------------------------------------- views / aliasing

	@Property(tries = 1)
	void writesThroughNestedViewsLandAtTheComposedOffsetAndStayInsideTheRegion() {
		Random rnd = FuzzTiers.random("wire-view");
		int cases = FuzzTiers.cases("wire-view", 2, 8, 24, 48);
		for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
			int rootLength = 24 + rnd.nextInt(40);
			byte[] backing = pattern(rootLength);
			byte[] before = backing.clone();
			Buf root = Buf.wrap(backing);

			// two view levels, so a write start carries two composed offsets
			int outerFrom = 1 + rnd.nextInt(rootLength - 8);
			int outerTo = outerFrom + 7 + rnd.nextInt(rootLength - outerFrom - 6);
			Buf outer = root.subListForced(outerFrom, outerTo);
			int outerSize = outerTo - outerFrom;
			int innerFrom = rnd.nextInt(outerSize - 5);
			int innerTo = innerFrom + 5 + rnd.nextInt(outerSize - innerFrom - 4);
			Buf inner = outer.subListForced(innerFrom, innerTo);
			int innerSize = innerTo - innerFrom;
			// the written region may start inside the view and may stop short of its end, so bytes
			// both before it and behind it (still inside the view) must stay untouched
			int regionStart = rnd.nextInt(innerSize - 3);
			int regionLength = 4 + rnd.nextInt(innerSize - regionStart - 3);
			int absoluteFrom = outerFrom + innerFrom + regionStart;
			String what = gate("case " + caseIndex + " root=" + rootLength + " outer=" + outerFrom + ".." + outerTo
					+ " inner=" + innerFrom + ".." + innerTo + " region=" + regionStart + "+" + regionLength);

			assertEquals(outerFrom + innerFrom, inner.getBackingByteArrayOffset(),
					what + " (the view must expose one composed absolute offset)");
			BufDataOutput output = new BufDataOutput(
					inner.binaryOutputStream(regionStart, regionStart + regionLength), regionLength);

			// one byte is always written so the case is never vacuous; after that, the independent oracle
			// says what each candidate op costs and only ops that fit the region are applied
			List<Step> script = new ArrayList<>();
			SpecOracle oracle = new SpecOracle();
			Step seed = new Step(Kind.BOOLEAN, true);
			apply(output, seed);
			oracle.apply(seed);
			script.add(seed);
			for (int attempt = 0, filled = oracle.size(); attempt < 24 && filled < regionLength; attempt++) {
				Step candidate = step(rnd, WRITABLE);
				SpecOracle probe = new SpecOracle();
				probe.apply(candidate);
				if (oracle.size() + probe.size() > regionLength) {
					continue;
				}
				apply(output, candidate);
				oracle.apply(candidate);
				script.add(candidate);
				filled = oracle.size();
			}
			byte[] written = oracle.toByteArray();
			byte[] expectedRoot = before.clone();
			System.arraycopy(written, 0, expectedRoot, absoluteFrom, written.length);
			assertArrayEquals(expectedRoot, backing, what + " (only the region may change, and exactly its prefix)");
			assertEquals(written.length, output.size(), what + " (size)");
			assertEquals(written.length, output.position(), what + " (position)");
			assertArrayEquals(written, Arrays.copyOfRange(backing, absoluteFrom, absoluteFrom + written.length),
					what + " (the written bytes must be the spec bytes at the composed offset)");
			assertArrayEquals(expectedRoot, root.asArray(), what + " (the root sees the same storage)");

			// a write that does not fit the region must be refused <em>and</em> leave every byte alone
			int overflow = regionLength - written.length + 1;
			byte[] beforeOverflow = backing.clone();
			assertThrows(IndexOutOfBoundsException.class, () -> output.write(new byte[overflow]),
					what + " (a " + overflow + "-byte write past a " + regionLength + "-byte region must fail)");
			assertArrayEquals(beforeOverflow, backing, what + " (a refused write must not touch any byte)");
			assertEquals(written.length, output.size(), what + " (a refused write must not count)");
			assertArrayEquals(Arrays.copyOf(before, absoluteFrom), Arrays.copyOfRange(backing, 0, absoluteFrom),
					what + " (bytes before the region stay the sentinels)");
			assertArrayEquals(Arrays.copyOfRange(before, absoluteFrom + written.length, rootLength),
					Arrays.copyOfRange(backing, absoluteFrom + written.length, rootLength),
					what + " (bytes behind the written prefix, inside the region and behind the view, stay sentinels)");
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Asserts that decoding {@code spec} with {@code in} yields exactly the values {@code script} claims. */
	private static void assertDecoded(List<Step> script, SafeDataInput in, byte[] spec, int caseIndex) {
		for (int index = 0; index < script.size(); index++) {
			Step step = script.get(index);
			Object actual = readValue(in, step);
			assertEquals(step.expectedDecoded(), actual,
					dossier("step " + index + " decoded wrong from the spec bytes", script, spec, spec, caseIndex));
		}
	}

	/** Decodes the whole script with a fresh {@link BufDataInput}, for the refusal assertions. */
	private static void decodeAll(byte[] bytes, List<Step> script, InputFactory factory) {
		SafeDataInput in = factory.create(Buf.wrap(bytes.clone()), FuzzOracles.LIMITS);
		for (Step step : script) {
			readValue(in, step);
		}
	}

	private static Object read(String hex, ReadAction action) {
		return action.read(BufDataInput.create(Buf.wrap(HexFormat.of().parseHex(hex)), FuzzOracles.LIMITS));
	}

	/** Asserts the pinned hex both ways: {@code SafeDataInput} decodes it, and no reader is required. */
	private static void assertRead(String what, String hex, ReadAction action, Object expected) {
		byte[] bytes = HexFormat.of().parseHex(hex);
		Object fromBuf = action.read(BufDataInput.create(Buf.wrap(bytes.clone()), FuzzOracles.LIMITS));
		assertTrue(expected.equals(fromBuf),
				gate(what + " via BufDataInput: bytes=" + hex + " expected=" + expected + " actual=" + fromBuf));
		Object fromStream;
		try (SafeDataInputStream stream = new SafeDataInputStream(new SafeByteArrayInputStream(bytes.clone()),
				FuzzOracles.LIMITS)) {
			fromStream = action.read(stream);
		}
		assertTrue(expected.equals(fromStream),
				gate(what + " via SafeDataInputStream: bytes=" + hex + " expected=" + expected
						+ " actual=" + fromStream));
	}

	/** Asserts that the real writer emits exactly {@code expectedHex} for {@code action}. */
	private static void assertWire(String what, WriteAction action, String expectedHex) {
		byte[] expected = HexFormat.of().parseHex(expectedHex);
		byte[] actual = encode(action);
		assertArrayEquals(expected, actual,
				gate(what + ": expected " + expectedHex + " but the writer emitted " + hex(actual)));
	}

	/** Runs {@code action} against a fresh unbounded {@link BufDataOutput}. */
	private static byte[] encode(WriteAction action) {
		BufDataOutput output = BufDataOutput.create();
		action.write(output);
		return output.asList().asArray();
	}

	/** Applies one generated step through the production write API. */
	private static void apply(BufDataOutput out, Step step) {
		switch (step.kind()) {
			case BOOLEAN -> out.writeBoolean((Boolean) step.value());
			case BYTE -> out.writeByte((Integer) step.value());
			case RAW_INT -> out.write((Integer) step.value());
			case SHORT -> out.writeShort((Integer) step.value());
			case CHAR -> out.writeChar((Integer) step.value());
			case INT -> out.writeInt((Integer) step.value());
			case LONG -> out.writeLong((Long) step.value());
			case INT52 -> out.writeInt52((Long) step.value());
			case FLOAT -> out.writeFloat((Float) step.value());
			case DOUBLE -> out.writeDouble((Double) step.value());
			case RAW_BYTES -> out.write((byte[]) step.value());
			case RAW_BYTES_RANGE -> out.write((byte[]) step.value(), step.from(), step.length());
			case BYTES_OF_BUF -> out.writeBytes(Buf.wrap(((byte[]) step.value()).clone()));
			case SHORT_TEXT -> out.writeShortText((String) step.value(), StandardCharsets.UTF_8);
			case MEDIUM_TEXT -> out.writeMediumText((String) step.value(), StandardCharsets.UTF_8);
			case SHORT_TEXT_UTF16 -> out.writeShortText((String) step.value(), UTF_16LE);
			case BYTES_STRING -> out.writeBytes((String) step.value());
			case CHARS -> out.writeChars((String) step.value());
		}
	}

	/** Decodes one step with the read API; only {@link #READABLE} kinds reach this. */
	private static Object readValue(SafeDataInput in, Step step) {
		return switch (step.kind()) {
			case BOOLEAN -> in.readBoolean();
			case BYTE -> in.readByte();
			case SHORT -> in.readShort();
			case CHAR -> in.readChar();
			case INT -> in.readInt();
			case LONG -> in.readLong();
			case INT52 -> in.readInt52();
			case FLOAT -> in.readFloat();
			case DOUBLE -> in.readDouble();
			case SHORT_TEXT -> in.readShortText(StandardCharsets.UTF_8);
			case MEDIUM_TEXT -> in.readMediumText(StandardCharsets.UTF_8);
			case SHORT_TEXT_UTF16 -> in.readShortText(UTF_16LE);
			default -> throw new IllegalStateException("not readable as a value: " + step.kind());
		};
	}

	private static List<Step> script(Random rnd, int length, Kind[] alphabet) {
		List<Step> steps = new ArrayList<>(length);
		for (int index = 0; index < length; index++) {
			steps.add(step(rnd, alphabet));
		}
		return steps;
	}

	private static Step step(Random rnd, Kind[] alphabet) {
		Kind kind = alphabet[rnd.nextInt(alphabet.length)];
		return switch (kind) {
			case BOOLEAN -> new Step(kind, rnd.nextBoolean());
			case BYTE, RAW_INT, SHORT, CHAR, INT -> new Step(kind, rnd.nextInt(4) == 0
					? INT_EDGES[rnd.nextInt(INT_EDGES.length)]
					: rnd.nextInt());
			case LONG -> new Step(kind, rnd.nextInt(4) == 0
					? LONG_EDGES[rnd.nextInt(LONG_EDGES.length)]
					: rnd.nextLong());
			case INT52 -> new Step(kind, rnd.nextInt(2) == 0
					? FuzzValues.int52(rnd)
					// out-of-domain draws pin the documented 52-bit truncation, not a rejection
					: FuzzValues.int52OutOfRange(rnd));
			case FLOAT -> new Step(kind, rnd.nextInt(2) == 0
					? FLOAT_EDGES[rnd.nextInt(FLOAT_EDGES.length)]
					: Float.intBitsToFloat(rnd.nextInt()));
			case DOUBLE -> new Step(kind, rnd.nextInt(2) == 0
					? DOUBLE_EDGES[rnd.nextInt(DOUBLE_EDGES.length)]
					: Double.longBitsToDouble(rnd.nextLong()));
			case RAW_BYTES, BYTES_OF_BUF -> {
				byte[] bytes = FuzzValues.bytes(rnd, rnd.nextInt(9));
				yield new Step(kind, bytes, 0, bytes.length);
			}
			case RAW_BYTES_RANGE -> {
				byte[] bytes = FuzzValues.bytes(rnd, 4 + rnd.nextInt(5));
				int from = rnd.nextInt(bytes.length + 1);
				yield new Step(kind, bytes, from, rnd.nextInt(bytes.length - from + 1));
			}
			case SHORT_TEXT, MEDIUM_TEXT, SHORT_TEXT_UTF16, BYTES_STRING, CHARS -> new Step(kind,
					rnd.nextInt(2) == 0 ? STRING_EDGES[rnd.nextInt(STRING_EDGES.length)] : FuzzValues.string(rnd));
		};
	}

	/**
	 * One scripted write. {@link #expectedDecoded()} states the value the <em>format</em> carries, so
	 * it never consults the production reader.
	 */
	private record Step(Kind kind, Object value, int from, int length) {

		Step(Kind kind, Object value) {
			this(kind, value, 0, 0);
		}

		Object expectedDecoded() {
			return switch (kind) {
				case BOOLEAN -> value;
				case BYTE -> (byte) (int) (Integer) value;
				case SHORT -> (short) (int) (Integer) value;
				case CHAR -> (char) (int) (Integer) value;
				case INT -> value;
				case LONG -> value;
				// the wire is an unsigned 52-bit field, so out-of-domain draws are expected to collapse
				case INT52 -> ((Long) value) & INT52_MASK;
				case FLOAT -> Float.intBitsToFloat(Float.floatToIntBits((Float) value));
				case DOUBLE -> Double.longBitsToDouble(Double.doubleToLongBits((Double) value));
				case SHORT_TEXT -> decodedThrough((String) value, StandardCharsets.UTF_8);
				case MEDIUM_TEXT -> decodedThrough((String) value, StandardCharsets.UTF_8);
				case SHORT_TEXT_UTF16 -> decodedThrough((String) value, UTF_16LE);
				default -> throw new IllegalStateException("not readable as a value: " + kind);
			};
		}

		/**
		 * The value a charset-framed text field carries: the JDK's own decode of the encoded bytes.
		 * Unpaired surrogates are unmappable in UTF-8, so they legitimately arrive as {@code '?'};
		 * the spec is the encoding, and this is where that is stated rather than hidden.
		 */
		private static String decodedThrough(String text, Charset charset) {
			return new String(text.getBytes(charset), charset);
		}

		@Override
		public String toString() {
			return kind + "(" + (value instanceof byte[] bytes ? hex(bytes) : String.valueOf(value))
					+ (kind == Kind.RAW_BYTES_RANGE ? " from=" + from + " length=" + length : "") + ")";
		}
	}

	/** The write surface this test drives. */
	private enum Kind {
		BOOLEAN, BYTE, RAW_INT, SHORT, CHAR, INT, LONG, INT52, FLOAT, DOUBLE, RAW_BYTES, RAW_BYTES_RANGE,
		BYTES_OF_BUF, SHORT_TEXT, MEDIUM_TEXT, SHORT_TEXT_UTF16, BYTES_STRING, CHARS
	}

	/**
	 * Independent expected-bytes writer: every fixed-width value goes through
	 * {@link java.io.DataOutputStream} over a {@link ByteArrayOutputStream}, i.e. through the JDK and
	 * not through any datagen code; only the two datagen-specific encodings that the JDK does not
	 * have — the seven-byte Int52 field and the charset-framed texts — are hand-rolled here from the
	 * format documented on the class comment.
	 */
	private static final class SpecOracle {

		private final ByteArrayOutputStream raw = new ByteArrayOutputStream();
		private final DataOutputStream jdk = new DataOutputStream(raw);

		int size() {
			return raw.size();
		}

		byte[] toByteArray() {
			return raw.toByteArray();
		}

		void apply(Step step) {
			try {
				switch (step.kind()) {
					case BOOLEAN -> jdk.writeBoolean((Boolean) step.value());
					case BYTE -> jdk.writeByte((Integer) step.value());
					case RAW_INT -> jdk.write((Integer) step.value());
					case SHORT -> jdk.writeShort((Integer) step.value());
					case CHAR -> jdk.writeChar((Integer) step.value());
					case INT -> jdk.writeInt((Integer) step.value());
					case LONG -> jdk.writeLong((Long) step.value());
					case INT52 -> int52((Long) step.value());
					case FLOAT -> jdk.writeFloat((Float) step.value());
					case DOUBLE -> jdk.writeDouble((Double) step.value());
					case RAW_BYTES -> jdk.write((byte[]) step.value());
					case RAW_BYTES_RANGE -> jdk.write((byte[]) step.value(), step.from(), step.length());
					case BYTES_OF_BUF -> jdk.write((byte[]) step.value());
					case SHORT_TEXT -> shortText((String) step.value(), StandardCharsets.UTF_8);
					case MEDIUM_TEXT -> mediumText((String) step.value(), StandardCharsets.UTF_8);
					case SHORT_TEXT_UTF16 -> shortText((String) step.value(), UTF_16LE);
					case BYTES_STRING -> jdk.writeBytes((String) step.value());
					case CHARS -> jdk.writeChars((String) step.value());
				}
			} catch (IOException impossible) {
				// a ByteArrayOutputStream cannot fail
				throw new UncheckedIOException(impossible);
			}
		}

		/** Seven big-endian bytes whose lead byte only carries the low nibble: the datagen Int52 field. */
		private void int52(long value) throws IOException {
			long wire = value & INT52_MASK;
			for (int shift = 48; shift >= 0; shift -= 8) {
				jdk.write((int) ((wire >>> shift) & 0xFF));
			}
		}

		/** Unsigned-short byte count, then the charset-encoded payload — real UTF-8, not modified UTF-8. */
		private void shortText(String text, Charset charset) throws IOException {
			byte[] encoded = text.getBytes(charset);
			jdk.writeShort(encoded.length);
			jdk.write(encoded);
		}

		/** Four-byte byte count, then the charset-encoded payload. */
		private void mediumText(String text, Charset charset) throws IOException {
			byte[] encoded = text.getBytes(charset);
			jdk.writeInt(encoded.length);
			jdk.write(encoded);
		}
	}

	/** A write through the production API, used by the pinned vectors. */
	@FunctionalInterface
	private interface WriteAction {
		void write(BufDataOutput out);
	}

	/** A read through the {@link SafeDataInput} twin, used by the pinned vectors. */
	@FunctionalInterface
	private interface ReadAction {
		Object read(SafeDataInput in);
	}

	/** Creates a {@link BufDataInput} over a buffer, for the truncation refusals. */
	@FunctionalInterface
	private interface InputFactory {
		SafeDataInput create(Buf buf, it.cavallium.datagen.DecodeLimits limits);
	}

	private static byte[] concat(byte[] prefix, byte[] suffix) {
		byte[] result = Arrays.copyOf(prefix, prefix.length + suffix.length);
		System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
		return result;
	}

	/** Deterministic, index-dependent sentinel bytes: an off-by-one shift cannot look like the pattern. */
	private static byte[] pattern(int length) {
		byte[] bytes = new byte[length];
		for (int index = 0; index < length; index++) {
			bytes[index] = (byte) (0x3C + 31 * index);
		}
		return bytes;
	}

	private static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	/** Every failure states its gate, the whole script, both byte strings and the first difference. */
	private static String dossier(String what, List<Step> script, byte[] expected, byte[] actual, int caseIndex) {
		int firstDifference = 0;
		while (firstDifference < Math.min(expected.length, actual.length)
				&& expected[firstDifference] == actual[firstDifference]) {
			firstDifference++;
		}
		return gate(what + " case=" + caseIndex + " fingerprint="
				+ Integer.toHexString(Arrays.hashCode(expected) ^ (script.size() * 31))
				+ " firstDifferenceAt=" + (firstDifference == Math.min(expected.length, actual.length)
						? "none(size " + expected.length + " vs " + actual.length + ")" : firstDifference)
				+ " expected=" + hex(expected) + " actual=" + hex(actual) + " script=" + script);
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}
}
