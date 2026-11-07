package it.cavallium.datagen.fuzz;

import it.cavallium.datagen.nativedata.Int52;
import java.util.Random;

/**
 * Shared hostile value pool for the fuzz campaign.
 *
 * <p>Every generator takes a {@link Random} supplied by the caller — normally
 * {@link FuzzTiers#random(String)} — so a drawn value is fully determined by {@code (seed, salt)} and
 * can be replayed. Nothing here shrinks or asserts: the callers decide what a value means, this class
 * only makes sure the interesting corners of the domains show up early and often.</p>
 */
public final class FuzzValues {

	/**
	 * Canonical sizes a generator should visit: empty, the one- and two-element edges, a few small
	 * powers of two plus their off-by-ones, and the unsigned-short/medium payload walls.
	 */
	public static final int[] SIZE_LADDER = {0, 1, 2, 8, 17, 32, 64, 255, 256, 65535, 65536};

	/** Single bytes that break naive readers: NUL, SOH, DEL, the UTF-8 continuation lead and 0xFF. */
	public static final int[] HOSTILE_BYTES = {0x00, 0x01, 0x7F, 0x80, 0xFF};

	/** Inclusive upper bound of {@link #string(Random)} measured in code points. */
	private static final int MAX_CODE_POINTS = 40;

	/** Number of code-point strata {@link #string(Random)} rotates between. */
	private static final int STRATA = 10;

	/** UTF-8 byte lengths {@link #stringNearShortBoundary(Random)} aims at, around the short wall. */
	private static final int[] NEAR_SHORT_BOUNDARY_BYTE_LENGTHS = {65534, 65535, 65536, 65537};

	/** Highest C0 control code point of the control stratum. */
	private static final int CONTROL_MAX_CP = 0x1F;

	/** DEL, the one control byte above ASCII printable that joins the control stratum. */
	private static final int DEL_CP = 0x7F;

	/** Code-point strata drawn by {@link #string(Random)}; see {@link #codePointOf(Random, int)}. */
	private static final int STRATUM_CONTROLS = 0;
	private static final int STRATUM_ASCII = 1;
	private static final int STRATUM_LATIN1 = 2;
	private static final int STRATUM_BMP = 3;
	private static final int STRATUM_CJK = 4;
	private static final int STRATUM_ASTRAL = 5;
	private static final int STRATUM_LONE_HIGH_SURROGATE = 6;
	private static final int STRATUM_LONE_LOW_SURROGATE = 7;
	private static final int STRATUM_COMBINING = 8;
	private static final int STRATUM_RTL = 9;

	private FuzzValues() {
	}

	/**
	 * Draws a code-point stratified string.
	 *
	 * <p>Each code point independently picks one of ten strata: NUL and C0 controls plus DEL,
	 * printable ASCII, Latin-1, general BMP, CJK, astral (as a properly paired surrogate pair), a lone
	 * high surrogate, a lone low surrogate, combining marks and right-to-left script.
	 * Surrogate strata stay unpaired by construction — a lone low surrogate is never appended right
	 * after a lone high one — so an unpaired surrogate survives a UTF-16 round trip as a real,
	 * replacement-worthy edge case.</p>
	 *
	 * @param rnd the caller's random source
	 * @return a non-null string of at most {@value #MAX_CODE_POINTS} code points, possibly empty; size
	 * control belongs to {@link #SIZE_LADDER} at the callsite, not here
	 */
	public static String string(Random rnd) {
		StringBuilder sb = new StringBuilder();
		int codePoints = rnd.nextInt(MAX_CODE_POINTS + 1);
		boolean pendingHighSurrogate = false;
		for (int i = 0; i < codePoints; i++) {
			int stratum = rnd.nextInt(STRATA);
			if (pendingHighSurrogate && stratum == STRATUM_LONE_LOW_SURROGATE) {
				// would silently pair up; take another draw for this position instead
				stratum = rnd.nextInt(STRATA);
				if (stratum == STRATUM_LONE_LOW_SURROGATE) {
					stratum = STRATUM_LONE_HIGH_SURROGATE;
				}
			}
			int codePoint = codePointOf(rnd, stratum);
			// only a stratum that draws a bare high surrogate can pair with the next draw; an astral
			// draw already carries its own low surrogate
			pendingHighSurrogate = stratum == STRATUM_LONE_HIGH_SURROGATE;
			sb.appendCodePoint(codePoint);
		}
		return sb.toString();
	}

	/**
	 * Draws a string whose UTF-8 encoding lands exactly on one of
	 * {@code {65534, 65535, 65536, 65537}} bytes — the short/medium text boundary.
	 *
	 * <p>Built from a mix of 3-byte CJK code points and 1-byte ASCII code points, which makes the byte
	 * length exact by construction rather than by luck. No assertion happens here; callers compare the
	 * produced byte length against their own expectation.</p>
	 *
	 * @param rnd the caller's random source
	 * @return a string of roughly 21k code points whose UTF-8 length straddles 65535/65536
	 */
	public static String stringNearShortBoundary(Random rnd) {
		int target = NEAR_SHORT_BOUNDARY_BYTE_LENGTHS[rnd.nextInt(NEAR_SHORT_BOUNDARY_BYTE_LENGTHS.length)];
		int maxThreeByte = target / 3;
		// at least one 3-byte code point and at least one leftover byte, so both widths always appear
		int threeByteCount = 1 + rnd.nextInt(Math.max(1, maxThreeByte - 1));
		int oneByteCount = target - 3 * threeByteCount;
		StringBuilder sb = new StringBuilder(threeByteCount + oneByteCount);
		int remainingThree = threeByteCount;
		int remainingOne = oneByteCount;
		while (remainingThree > 0 || remainingOne > 0) {
			if (remainingOne == 0 || (remainingThree > 0 && rnd.nextBoolean())) {
				sb.appendCodePoint(codePointOf(rnd, STRATUM_CJK));
				remainingThree--;
			} else {
				sb.appendCodePoint(codePointOf(rnd, STRATUM_ASCII));
				remainingOne--;
			}
		}
		return sb.toString();
	}

	/**
	 * Draws a value inside the {@link Int52} domain, including both bounds and their neighbours.
	 *
	 * @param rnd the caller's random source
	 * @return {@code v} with {@code Int52.MIN_VALUE_L <= v <= Int52.MAX_VALUE_L}
	 */
	public static long int52(Random rnd) {
		long min = Int52.MIN_VALUE_L;
		long max = Int52.MAX_VALUE_L;
		// a quarter of the draws are pinned to the domain edges, the rest roam the whole range
		if (rnd.nextInt(4) == 0) {
			long[] edges = {min, min + 1, min + 2, max - 2, max - 1, max};
			return edges[rnd.nextInt(edges.length)];
		}
		return Math.floorMod(rnd.nextLong(), max - min + 1) + min;
	}

	/**
	 * Draws a value just outside the {@link Int52} domain, in both directions.
	 *
	 * @param rnd the caller's random source
	 * @return a value that {@link Int52#checkValidity(long)} must reject: below
	 * {@code Int52.MIN_VALUE_L} or above {@code Int52.MAX_VALUE_L}, including both {@link Long} extremes
	 */
	public static long int52OutOfRange(Random rnd) {
		long max = Int52.MAX_VALUE_L;
		return switch (rnd.nextInt(4)) {
			// just below the lower bound
			case 0 -> -(1L + rnd.nextInt(16));
			// just above the upper bound; 2^52 + 15 is far from a long overflow
			case 1 -> max + 1 + rnd.nextInt(16);
			case 2 -> Long.MIN_VALUE;
			default -> Long.MAX_VALUE;
		};
	}

	/**
	 * Draws uniformly random bytes.
	 *
	 * @param rnd the caller's random source
	 * @param len requested length; values below one produce an empty array
	 * @return a {@code len}-long array of random bytes
	 */
	public static byte[] bytes(Random rnd, int len) {
		byte[] bytes = new byte[Math.max(0, len)];
		rnd.nextBytes(bytes);
		return bytes;
	}

	/**
	 * Picks one code point out of the requested stratum. {@link #STRATUM_BMP} splits its draw between
	 * the general BMP planes and CJK, so the BMP stratum reaches both.
	 */
	private static int codePointOf(Random rnd, int stratum) {
		return switch (stratum) {
			case STRATUM_CONTROLS -> rnd.nextBoolean() ? between(rnd, 0, CONTROL_MAX_CP) : DEL_CP;
			case STRATUM_ASCII -> between(rnd, 0x20, 0x7E);
			case STRATUM_LATIN1 -> between(rnd, 0x80, 0xFF);
			case STRATUM_BMP -> rnd.nextBoolean() ? between(rnd, 0x100, 0x2FFF) : between(rnd, 0x4E00, 0x9FFF);
			case STRATUM_CJK -> between(rnd, 0x4E00, 0x9FFF);
			case STRATUM_ASTRAL -> 0x10000 + rnd.nextInt(0x100000);
			case STRATUM_LONE_HIGH_SURROGATE -> between(rnd, Character.MIN_HIGH_SURROGATE, Character.MAX_HIGH_SURROGATE);
			case STRATUM_LONE_LOW_SURROGATE -> between(rnd, Character.MIN_LOW_SURROGATE, Character.MAX_LOW_SURROGATE);
			case STRATUM_COMBINING -> between(rnd, 0x300, 0x36F);
			case STRATUM_RTL -> rnd.nextBoolean() ? between(rnd, 0x590, 0x5FF) : between(rnd, 0x600, 0x6FF);
			default -> throw new IllegalStateException("unknown stratum: " + stratum);
		};
	}

	private static int between(Random rnd, int low, int high) {
		return low + rnd.nextInt(high - low + 1);
	}
}
