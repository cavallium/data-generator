package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Property;

/**
 * The non-UTF-8 charset matrix: one {@link BufDataOutput} write against every read path
 * ({@link Buf#getString(int, int, java.nio.charset.Charset)} on all six storages, and
 * {@link SafeDataInput#readString(int, java.nio.charset.Charset)} on a forward stream).
 *
 * <p>The contract is stated once, in {@link #expected(String, Charset)}: the writer encodes with
 * {@code String.getBytes(charset)} and both readers decode with {@code new String(bytes, charset)}, so
 * the round-trip result of a charset that cannot represent a code point is that charset's documented
 * lossy outcome — {@code '?'} for a charset with no mapping, {@code U+FFFD} where the decoder is the one
 * that cannot represent the sequence. Testing identity instead would turn correct JDK behaviour into a
 * failing property, and testing nothing would hide a real regression, so this property compares against
 * the rule and separately pins a table of concrete documented outcomes.</p>
 *
 * <p>The framing is checked too: the medium-text prefix must hold exactly the encoder's byte count, the
 * short-text framing must refuse anything larger than an unsigned short with
 * {@link it.cavallium.datagen.ValueTooLargeException}, and no read path may consume a byte behind the
 * declared length.</p>
 */
class CharsetMatrixPropertyTest {

	/** Every charset this matrix covers, in one place. */
	private static final Map<String, Charset> CHARSETS = charsets();

	@Property(tries = 1)
	void everyCharsetRoundTripsThroughEveryStorageAndTheForwardStream() {
		Random rnd = FuzzTiers.random("charset-matrix");
		int cases = FuzzTiers.cases("charset-matrix", 4, 12, 40, 80);
		List<String> values = values(rnd, cases);
		for (Map.Entry<String, Charset> entry : CHARSETS.entrySet()) {
			String name = entry.getKey();
			Charset charset = entry.getValue();
			for (String value : values) {
				String expected = expected(value, charset);
				byte[] encoded = medium(value, charset);
				int length = value.getBytes(charset).length;
				assertEquals(length + Integer.BYTES, encoded.length,
						gate(name + " must prefix exactly the encoder's byte count for a " + value.length()
								+ "-char value"));
				try (FuzzStorages storages = new FuzzStorages(encoded)) {
					for (FuzzStorages.View view : storages.all()) {
						if (!view.forwardOnly()) {
							assertEquals(expected, view.buf().getString(view.offset() + Integer.BYTES, length,
									charset), gate(name + " Buf.getString on " + view.kind()));
							assertEquals(expected, readWithCursor(view, length, charset),
									gate(name + " cursor readString on " + view.kind()));
						}
						try (SafeDataInputStream in = FuzzStorages.forwardStream(view, FuzzOracles.LIMITS)) {
							in.skipBytes(Integer.BYTES);
							assertEquals(expected, in.readString(length, charset),
									gate(name + " stream readString on " + view.kind()));
							assertEquals(0, in.readNBytes(1).length,
									gate(name + " must consume exactly its own bytes on " + view.kind()));
						}
					}
				}
			}
		}
	}

	@Property(tries = 1)
	void lossyCharsetsProduceTheDocumentedReplacementNotAnError() {
		// the rule, pinned to concrete cases: a charset that cannot represent a code point replaces it
		String[][] documented = {
				{"US-ASCII", "\u00e9", "?"},
				{"US-ASCII", "\u4e16", "?"},
				{"ISO-8859-1", "\u4e16", "?"},
				{"UTF-8", "\uD800", "?"},
				{"UTF-32LE", "\uDC00", "\uFFFD"},
				{"UTF-16BE", "\uD800", "\uFFFD"},
				{"UTF-16LE", "\u4e16", "\u4e16"},
				{"UTF-8", "\uD83D\uDE00", "\uD83D\uDE00"},
		};
		for (String[] row : documented) {
			Charset charset = Charset.forName(row[0]);
			assertEquals(row[2], expected(row[1], charset),
					gate("documented outcome for " + row[0] + " value U+" +
							row[1].codePoints().mapToObj(cp -> Integer.toHexString(cp)).findFirst().orElse("")));
		}
		// and every charset in the matrix keeps its own promise for a hostile value, whatever it is
		Random rnd = FuzzTiers.random("charset-lossy-rule");
		for (String value : values(rnd, FuzzTiers.cases("charset-lossy-rule", 4))) {
			for (Map.Entry<String, Charset> entry : CHARSETS.entrySet()) {
				String produced = expected(value, entry.getValue());
				// the rule is idempotent: re-encoding what the first pass produced changes nothing more
				assertEquals(produced, expected(produced, entry.getValue()),
						gate(entry.getKey() + " lossy replacement must be stable"));
				assertEquals(value.isEmpty(), produced.isEmpty(),
						gate(entry.getKey() + " must produce a value (emptiness preserved)"));
			}
		}
	}

	@Property(tries = 1)
	void shortTextFramingRefusesWhatAnUnsignedShortCannotHold() {
		Random rnd = FuzzTiers.random("charset-framing");
		for (Map.Entry<String, Charset> entry : CHARSETS.entrySet()) {
			Charset charset = entry.getValue();
			for (int chars : new int[] {0, 1, 100}) {
				String value = "x".repeat(chars) + values(rnd, 1).get(0);
				byte[] medium = medium(value, charset);
				assertEquals(value.getBytes(charset).length + Integer.BYTES, medium.length,
						gate(entry.getKey() + " medium framing"));
				if (value.getBytes(charset).length <= 0xFFFF) {
					byte[] shortFramed = shortText(value, charset);
					assertEquals(value.getBytes(charset).length + Short.BYTES, shortFramed.length,
							gate(entry.getKey() + " short framing"));
					try (FuzzStorages storages = new FuzzStorages(shortFramed)) {
						FuzzStorages.View view = storages.kind(FuzzStorages.Kind.HEAP);
						assertEquals(expected(value, charset),
								view.buf().getShortText(view.offset(), charset),
								gate(entry.getKey() + " getShortText round trip"));
					}
				}
			}
			// one ASCII value that cannot fit an unsigned short prefix in any charset here
			String tooLong = "x".repeat(65_536);
			assertTrue(tooLong.getBytes(charset).length > 0xFFFF,
					gate(entry.getKey() + " fixture must not fit an unsigned short"));
			assertThrows(it.cavallium.datagen.ValueTooLargeException.class,
					() -> shortText(tooLong, charset),
					gate(entry.getKey() + " must refuse a 65 536-byte short text"));
			assertEquals(tooLong, expected(tooLong, charset),
					gate(entry.getKey() + " medium framing must survive it"));
		}
	}

	/**
	 * The expected-value rule, stated once: whatever {@code charset} cannot represent is replaced by the
	 * JDK's documented substitution, which is exactly what encoding with the same charset and decoding
	 * again produces.
	 */
	private static String expected(String value, Charset charset) {
		return new String(value.getBytes(charset), charset);
	}

	/** @return {@code value} with a medium (int) text prefix, in {@code charset}. */
	private static byte[] medium(String value, Charset charset) {
		BufDataOutput output = BufDataOutput.create();
		output.writeMediumText(value, charset);
		return bytes(output);
	}

	/** @return {@code value} with a short (unsigned short) text prefix, in {@code charset}. */
	private static byte[] shortText(String value, Charset charset) {
		BufDataOutput output = BufDataOutput.create();
		output.writeShortText(value, charset);
		return bytes(output);
	}

	private static byte[] bytes(BufDataOutput output) {
		Buf encoded = output.asList();
		return FuzzStorages.bytesOf(
				new FuzzStorages.View(FuzzStorages.Kind.FORWARD_STREAM, encoded, 0, encoded.size()));
	}

	/** @return the value a bounded cursor reads out of the framed region. */
	private static String readWithCursor(FuzzStorages.View view, int length, Charset charset) {
		BufDataCursor cursor = FuzzStorages.bindCursor(view, DecodeLimits.unlimited());
		try {
			cursor.readInt();
			return cursor.readString(length, charset);
		} finally {
			cursor.unbind();
		}
	}

	/** @return the charset matrix, keyed by the name the failure messages use. */
	private static Map<String, Charset> charsets() {
		Map<String, Charset> charsets = new LinkedHashMap<>();
		charsets.put("UTF-8", StandardCharsets.UTF_8);
		charsets.put("UTF-16BE", StandardCharsets.UTF_16BE);
		charsets.put("UTF-16LE", StandardCharsets.UTF_16LE);
		charsets.put("ISO-8859-1", StandardCharsets.ISO_8859_1);
		charsets.put("US-ASCII", StandardCharsets.US_ASCII);
		charsets.put("UTF-32LE", StandardCharsets.UTF_32LE);
		return charsets;
	}

	/** @return hostile values: the empty string, a straddling value and tier-scaled drawn ones. */
	private static List<String> values(Random rnd, int cases) {
		List<String> values = new ArrayList<>();
		values.add("");
		values.add("\u0000a\u00e9\u4e16\uD83D\uDE00");
		for (int i = 0; i < Math.max(0, cases); i++) {
			values.add(FuzzValues.string(rnd));
		}
		if (FuzzTiers.tier().compareTo(FuzzTiers.Tier.DEEP) >= 0 && cases > 0) {
			// the short/medium wall is charset-dependent, so it belongs in the campaign tier
			values.add(FuzzValues.stringNearShortBoundary(rnd));
		}
		return values;
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}
}
