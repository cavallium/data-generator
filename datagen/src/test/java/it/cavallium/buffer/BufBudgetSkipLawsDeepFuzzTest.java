package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.fuzz.FuzzTiers;
import it.cavallium.datagen.fuzz.FuzzValues;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Decode-budget and skip laws, stated as laws rather than as one API call at a time.
 *
 * <p>What is already pinned nearby, and is not repeated: {@code BufCursorBudgetTextDeepFuzzTest} drives
 * the {@link DecodeBudget} API as a standalone state machine (claim limits, structural depth limits, one
 * root entry and its reset, rejection of an unmatched {@code exitRoot}) and shows that a rejected
 * primitive-array or string read leaves {@code position()} and the claims behind;
 * {@link BufCursorKindParityDeepFuzzTest} pins the per-step failure claims across cursor kinds. What was
 * missing is the interaction of the two, which is how generated readers actually use this state:
 * {@code enterRoot}/{@code exitRoot} interleaved with structure entries and with real reads.</p>
 *
 * <p>The three laws pinned here, each against an exact model of the implementation:</p>
 * <ol>
 *     <li><b>A root entry resets only when it is the outermost one.</b> A nested {@code enterRoot} resets
 *     nothing, a nested {@code exitRoot} restores nothing, and the outermost {@code exitRoot} zeroes the
 *     <em>structural depth</em> while both claim counters survive untouched — so depth is leak-safe
 *     across a session boundary, accounting is not, and it takes the next outer {@code enterRoot} to
 *     reset the claims. Because {@code enterStructure} opens a root entry itself and unwinds that same
 *     entry when the depth limit rejects it, a rejected structure entry leaves the root nesting exactly as
 *     it found it — with one sharp edge that is easy to get wrong and is therefore pinned too: when the
 *     rejected structure opened the outermost root, the reset that entry performed has already happened,
 *     so its own failure still wiped the counters.</li>
 *     <li><b>{@code skip} is total, clamped and free.</b> Any count — zero, a negative count,
 *     {@link Long#MIN_VALUE}, {@link Long#MAX_VALUE}, the exact remaining count or one past it — returns
 *     {@code min(count, remaining)}, never throws, and charges nothing to either claim counter;
 *     {@code skipBytes} is the same law narrowed to {@code int}.</li>
 *     <li><b>A truncated read is atomic and leaves the input usable.</b> A read that cannot complete
 *     throws before it claims and before it advances, so after the failure the claims and the position are
 *     what they were, the remaining count is unchanged, and one successful read of the whole tail still
 *     yields it unchanged — what a reader needs to keep decoding after a speculative read failed.</li>
 * </ol>
 */
class BufBudgetSkipLawsDeepFuzzTest {

	private static final long NESTING_SEED = 0x2C7A_91F5_64BE_0D83L;
	private static final long SKIP_SEED = 0x7713_B0A6_5C2E_9A41L;
	private static final long TRUNCATION_SEED = 0x3F60_D84B_1E07_C25AL;

	private static final int NESTING_CASES = FuzzTiers.cases("budget-nesting", 4, 24, 80, 160);
	private static final int NESTING_STEPS = FuzzTiers.cases("budget-nesting-steps", 12, 40, 90, 180);
	private static final int SKIP_CASES = FuzzTiers.cases("budget-skip", 4, 20, 60, 120);
	private static final int TRUNCATION_CASES = FuzzTiers.cases("budget-truncation", 4, 20, 60, 120);

	@Test
	void nestedRootAndStructureEntriesResetOnlyAtTheOutermostLevel() {
		var random = new Random(NESTING_SEED);
		for (int caseIndex = 0; caseIndex < NESTING_CASES; caseIndex++) {
			// zero is a real case: with that limit, every structure entry is rejected
			int maximumDepth = random.nextInt(4);
			var budget = new DecodeBudget(new DecodeLimits(64, 64, 4_096, 4_096, maximumDepth));
			// the model mirrors the implementation: enterStructure opens a root entry itself and unwinds
			// that same entry again when the depth limit rejects it
			int rootEntries = 0;
			long arrays = 0;
			long payloads = 0;
			int depth = 0;
			for (int step = 0; step < NESTING_STEPS; step++) {
				String what = gate("seed=" + NESTING_SEED + " case=" + caseIndex + " step=" + step
						+ " maxDepth=" + maximumDepth);
				switch (random.nextInt(6)) {
					case 0 -> {
						if (rootEntries == 0) {
							arrays = 0;
							payloads = 0;
							depth = 0;
						}
						rootEntries++;
						budget.enterRoot();
					}
					case 1 -> {
						if (rootEntries == 0) {
							IllegalStateException failure =
									assertThrows(IllegalStateException.class, budget::exitRoot, what + " (extra)");
							assertEquals("Decode budget root is not active", failure.getMessage(),
									what + " (exitRoot wording)");
						} else {
							rootEntries--;
							if (rootEntries == 0) {
								depth = 0; // verified: the outermost exit zeroes the depth, never the claims
							}
							budget.exitRoot();
						}
					}
					case 2 -> {
						if (depth == maximumDepth) {
							// a positive depth implies a live root entry, so only a zero depth limit can be
							// rejected from outside a root — and that rejection still carries its own reset
							boolean outermost = rootEntries == 0;
							assertThrows(DecodeLimitExceededException.class, budget::enterStructure,
									what + " (depth limit)");
							if (outermost) {
								arrays = 0;
								payloads = 0;
							}
						} else {
							if (rootEntries == 0) {
								arrays = 0;
								payloads = 0;
								depth = 0;
							}
							rootEntries++;
							depth++;
							budget.enterStructure();
						}
					}
					case 3 -> {
						if (depth == 0) {
							IllegalStateException failure =
									assertThrows(IllegalStateException.class, budget::exitStructure, what);
							assertEquals("Decode structure is not active", failure.getMessage(),
									what + " (exitStructure wording)");
						} else {
							depth--;
							rootEntries--;
							if (rootEntries == 0) {
								depth = 0;
							}
							budget.exitStructure();
						}
					}
					case 4 -> {
						int claim = random.nextInt(25);
						arrays += claim;
						budget.claimArrayElements(claim);
					}
					default -> {
						int claim = random.nextInt(25);
						payloads += claim;
						budget.claimPayloadBytes(claim);
					}
				}
				assertEquals(arrays, budget.claimedArrayElements(), what + " (array claims)");
				assertEquals(payloads, budget.claimedPayloadBytes(), what + " (payload claims)");
				assertEquals(depth, budget.structuralDepth(), what + " (structural depth)");
			}
			// rootEntries is not exposed, so it is pinned by counting the exits the budget still owes
			assertEquals(rootEntries, drainRoots(budget),
					gate("seed=" + NESTING_SEED + " case=" + caseIndex + " (pending root entries)"));
			assertEquals(0, budget.structuralDepth(), gate("draining the roots also drains the depth"));
			assertEquals(arrays, budget.claimedArrayElements(), gate("draining the roots keeps the claims"));
			assertEquals(payloads, budget.claimedPayloadBytes(), gate("draining the roots keeps the claims"));
			// and the law the whole file is about: the next outer root entry is the reset
			budget.enterRoot();
			assertEquals(0, budget.claimedArrayElements(), gate("an outer root entry resets array claims"));
			assertEquals(0, budget.claimedPayloadBytes(), gate("an outer root entry resets payload claims"));
			assertEquals(0, budget.structuralDepth(), gate("an outer root entry resets the depth"));
			budget.exitRoot();
		}
	}

	@Test
	void skipClampsToTheRegionAndChargesNothingWhileTheTailStaysReadable() {
		var random = new Random(SKIP_SEED);
		for (int caseIndex = 0; caseIndex < SKIP_CASES; caseIndex++) {
			byte[] payload = FuzzValues.bytes(random, random.nextInt(65));
			int region = random.nextInt(payload.length + 1);
			var budget = new DecodeBudget(DecodeLimits.unlimited());
			var cursor = new BufDataCursor(budget);
			cursor.bind(Buf.wrap(payload), 0, region);
			int position = 0;
			for (int step = 0; step < 24; step++) {
				String what = gate("seed=" + SKIP_SEED + " case=" + caseIndex + " step=" + step
						+ " region=" + region);
				long count = switch (random.nextInt(7)) {
					case 0 -> 0;
					case 1 -> -1;
					case 2 -> Long.MIN_VALUE;
					case 3 -> region - position; // the exact remaining count
					case 4 -> (long) region - position + 1; // one past it
					case 5 -> Long.MAX_VALUE;
					default -> random.nextInt(35) - 5;
				};
				int expected = (int) Math.max(0, Math.min(count, (long) region - position));
				// skip is total: no count is an error, whatever the region looks like
				assertEquals(expected, cursor.skip(count), what + " (skip(" + count + "))");
				position += expected;
				assertState(cursor, region, position, budget, what + " (after skip)");
				// skipBytes is the same law, narrowed to int
				int narrow = random.nextInt(9) - 3;
				int expectedBytes = Math.max(0, Math.min(narrow, region - position));
				assertEquals(expectedBytes, cursor.skipBytes(narrow), what + " (skipBytes(" + narrow + "))");
				position += expectedBytes;
				assertState(cursor, region, position, budget, what + " (after skipBytes)");
			}
			// the unread tail is still exactly the unread tail, and it is still fully readable
			byte[] tail = new byte[region - position];
			cursor.readFully(tail);
			assertArrayEquals(Arrays.copyOfRange(payload, position, region), tail,
					gate("seed=" + SKIP_SEED + " case=" + caseIndex + " (tail after skipping)"));
			assertEquals(0, cursor.remaining(), gate("tail consumed"));
			assertEquals(-1, cursor.read(), gate("read() at the end of the region"));
			assertEquals(-1, cursor.read(new byte[4]), gate("read(byte[]) at the end of the region"));
			assertEquals(0, cursor.read(new byte[4], 0, 0), gate("read of nothing at the end"));
			assertEquals(0, cursor.skip(1), gate("skip at the end of the region"));
			assertEquals(0, cursor.skipBytes(4), gate("skipBytes at the end of the region"));
			assertEquals(0, budget.claimedArrayElements(), gate("a plain tail read charges no array elements"));
			assertEquals(0, budget.claimedPayloadBytes(), gate("a plain tail read charges no payload bytes"));
			cursor.unbind();
		}
	}

	@Test
	void truncatedReadsLeavePositionClaimsAndTheWholeTailUnderNestedRoots() {
		var random = new Random(TRUNCATION_SEED);
		for (int caseIndex = 0; caseIndex < TRUNCATION_CASES; caseIndex++) {
			byte[] payload = FuzzValues.bytes(random, 1 + random.nextInt(40));
			int region = random.nextInt(payload.length); // always at least one byte short of the payload
			int structures = random.nextInt(3);
			// generous limits, so the only failure that can happen here is a real truncation
			var budget = new DecodeBudget(new DecodeLimits(1_024, 1_024, 1_048_576, 1_048_576, 8));
			var cursor = new BufDataCursor(budget);
			cursor.bind(Buf.wrap(payload), 0, region);
			budget.enterRoot();
			for (int structure = 0; structure < structures; structure++) {
				budget.enterStructure();
			}
			String what = gate("seed=" + TRUNCATION_SEED + " case=" + caseIndex + " region=" + region
					+ " structures=" + structures);
			// read everything but the last few bytes, charging both claim counters on the way
			int consumed = 0;
			long expectedArrays = 0;
			long expectedPayloads = 0;
			while (region - consumed > 4) {
				byte[] chunk = cursor.readByteArray(2);
				assertArrayEquals(Arrays.copyOfRange(payload, consumed, consumed + 2), chunk, what + " (chunk)");
				consumed += 2;
				expectedArrays += 2;
				if (region - consumed > 2) {
					assertEquals(2, cursor.readString(2, StandardCharsets.ISO_8859_1).length(), what + " (text)");
					consumed += 2;
					expectedPayloads += 2;
				}
			}
			assertEquals(consumed, cursor.position(), what + " (position after successful reads)");
			assertEquals(expectedArrays, budget.claimedArrayElements(), what + " (array claims)");
			assertEquals(expectedPayloads, budget.claimedPayloadBytes(), what + " (payload claims)");
			int remaining = region - consumed;
			for (int overrun = 1; overrun <= 5; overrun++) {
				int ask = remaining + overrun; // the byte count each attempt demands
				String attempt = what + " (overrun " + overrun + ")";
				// every one of these asks for more than the region holds
				assertThrows(MalformedDataException.class, () -> cursor.readByteArray(ask),
						attempt + " (array)");
				assertEquals(expectedArrays, budget.claimedArrayElements(), attempt + " (array claims untouched)");
				assertThrows(MalformedDataException.class,
						() -> cursor.readString(ask, StandardCharsets.ISO_8859_1), attempt + " (string)");
				assertEquals(expectedPayloads, budget.claimedPayloadBytes(),
						attempt + " (payload claims untouched)");
				assertEquals(consumed, cursor.position(), attempt + " (position untouched)");
				assertEquals(remaining, cursor.remaining(), attempt + " (remaining untouched)");
			}
			// the failed attempts left the reader usable: one read still yields the whole untouched tail
			byte[] tail = new byte[remaining];
			cursor.readFully(tail);
			assertArrayEquals(Arrays.copyOfRange(payload, consumed, region), tail, what + " (tail after failures)");
			assertEquals(0, cursor.remaining(), what + " (tail consumed)");
			for (int structure = 0; structure < structures; structure++) {
				budget.exitStructure();
			}
			budget.exitRoot();
			assertEquals(expectedArrays, budget.claimedArrayElements(), what + " (claims outlive the root exit)");
			assertEquals(expectedPayloads, budget.claimedPayloadBytes(), what + " (claims outlive the root exit)");
			assertEquals(0, budget.structuralDepth(), what + " (the outermost exit zeroes the depth)");
			cursor.unbind();
		}
	}

	/** The bookkeeping law shared by {@code skip} and {@code skipBytes}. */
	private static void assertState(BufDataCursor cursor, int region, int position, DecodeBudget budget,
			String what) {
		assertEquals(region - position, cursor.remaining(), what + " (remaining)");
		assertEquals(region - position, cursor.available(), what + " (available)");
		assertEquals(position, cursor.position(), what + " (position)");
		assertEquals(region, cursor.length(), what + " (length stays the bound region)");
		assertEquals(0, budget.claimedArrayElements(), what + " (skipping charges no array elements)");
		assertEquals(0, budget.claimedPayloadBytes(), what + " (skipping charges no payload bytes)");
	}

	/**
	 * Counts the {@code exitRoot} calls a budget still owes, which is its pending root nesting; the
	 * counter also drains the budget, which is what the caller wants next.
	 */
	private static int drainRoots(DecodeBudget budget) {
		int pending = 0;
		while (true) {
			try {
				budget.exitRoot();
			} catch (IllegalStateException exhausted) {
				return pending;
			}
			pending++;
		}
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}
}
