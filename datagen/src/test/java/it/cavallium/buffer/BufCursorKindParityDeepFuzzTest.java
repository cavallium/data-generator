package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.fuzz.FuzzTiers;
import it.cavallium.datagen.fuzz.FuzzValues;
import it.cavallium.datagen.nativedata.Int52;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Cursor-kind parity: the <em>same</em> bytes and the <em>same</em> typed-read program run over every
 * storage path a generated reader can dispatch to, and the results, the bookkeeping and the exact
 * failure claim must be indistinguishable.
 *
 * <p>What the neighbouring suites already pin, and what this class therefore does not repeat:
 * {@code BufDataIoDeepFuzzTest} compares one cursor against a {@code byte[]} model,
 * {@code BufApiSurfaceDeepFuzzTest} covers the binding lifecycle and the unsigned absolute views, and
 * {@code BufCursorBudgetTextDeepFuzzTest} already fuzzes {@code bindSpecialized} against the expected
 * {@code StorageKind}s. Item 1 below re-pins that dispatch anyway, as a local precondition: if a source
 * silently bound to another kind, the comparison in items 2 and 3 would degenerate into one path.
 * Neither of those suites compares the three cursor <em>classes</em> against each other on one program,
 * and neither pins the exact {@link it.cavallium.datagen.MalformedDataException} claim text of the
 * failing step.
 * The third storage path is the interesting one: {@link FallbackBufDataCursor} reaches the bytes
 * through {@code Buf} getters instead of a {@code VarHandle} or a {@code MemorySegment}, so a
 * widening, endianness or bounds difference there is invisible to a model that only one cursor is
 * checked against.</p>
 *
 * <p>Each case therefore checks three things at once:</p>
 * <ol>
 *     <li>the production dispatch really picks the intended {@link BufDataCursor.StorageKind} for each
 *     of the seven sources (heap at offset 0 and at a non-zero offset, a two-level heap slice, native
 *     over the whole segment, native at a non-zero bind offset, native over a non-zero
 *     {@code MemorySegment} slice, and a forced-fallback buffer) — otherwise the comparison below
 *     would silently degenerate into one path;</li>
 *     <li>every source's outcome line per step — the decoded value, or the exception type plus message,
 *     which carries the required-versus-available claim — equals the test's own model, and hence every
 *     other kind's;</li>
 *     <li>{@code remaining()}, {@code position()}, the budget's two claim counters and the still unread
 *     bytes agree, including immediately after the failing step, which is the atomicity half of
 *     parity.</li>
 * </ol>
 *
 * <p>Programs are self-describing: each step knows the bytes it consumes, so the region is cut at an
 * adversarial size (nothing, one byte, around the 7-byte Int52 and 8-byte long widths, the last byte)
 * to make late steps fail with a truncation claim. Skip counts are resolved against the model's own
 * remaining count, which is what turns "exact boundary" and "boundary + 1" into real cases instead of
 * arbitrary numbers.</p>
 */
class BufCursorKindParityDeepFuzzTest {

	private static final DecodeLimits UNLIMITED = DecodeLimits.unlimited();
	private static final long SEED = 0x51B7_3C60_D2A9_48EEL;
	private static final int PAD_BEFORE = 3;
	private static final int PAD_AFTER = 2;
	private static final long INT52_MASK = 0x000F_FFFF_FFFF_FFFFL;

	private static final int CASES = FuzzTiers.cases("cursor-parity", 3, 12, 40, 80);
	private static final int PROGRAM_STEPS = FuzzTiers.cases("cursor-parity-steps", 6, 22, 44, 88);

	@Test
	void everyCursorKindReadsIdenticalValuesPositionsClaimsAndFailureMessages() {
		var random = new Random(SEED);
		int truncatingPrograms = 0;
		int completePrograms = 0;
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
				// Case 0 pins a COMPLETE region and case 1 a 1-byte region, so both coverage gates below
				// hold deterministically even at QUICK (where a purely random cut is complete only ~1/6 of
				// the time); every remaining case keeps the random adversarial cut.
				Program program = caseIndex == 0 ? Program.random(random, PROGRAM_STEPS, Boolean.TRUE)
						: caseIndex == 1 ? Program.random(random, PROGRAM_STEPS, Boolean.FALSE)
						: Program.random(random, PROGRAM_STEPS);
				if (program.failed) {
					truncatingPrograms++;
				} else {
					completePrograms++;
				}
				for (Source source : sources(arena, program.payload)) {
					String diagnostic = gate("seed=" + SEED + " case=" + caseIndex
							+ " fingerprint=" + program.fingerprint() + " source=" + source.name
							+ " region=0.." + program.region + " of " + program.payload.length);

					// 1. the dispatch must really be the kind this source is meant to exercise
					assertEquals(source.kind, dispatchedKind(source),
							diagnostic + " (production dispatch picked the wrong cursor)");

					// 2. the generic cursor, and 3. the same program on the specialized cursor classes
					Outcome generic = run(program, source, new DecodeBudget(UNLIMITED));
					assertMatches(program, generic, diagnostic + " (generic cursor)");
					Outcome specialized = runSpecialized(program, source, new DecodeBudget(UNLIMITED));
					assertMatches(program, specialized, diagnostic + " (specialized cursor)");
					assertEquals(generic.lines, specialized.lines,
							diagnostic + " (specialized cursor must match the generic one)");
					assertEquals(generic.remaining, specialized.remaining, diagnostic + " (remaining)");
					assertEquals(generic.position, specialized.position, diagnostic + " (position)");
					assertEquals(generic.payloadClaims, specialized.payloadClaims, diagnostic + " (payload claims)");
					assertEquals(generic.arrayClaims, specialized.arrayClaims, diagnostic + " (array claims)");
				}
			}
		}
		// coverage gate: the budget of this tier must exercise both halves of the parity contract,
		// otherwise a green run would say nothing about the atomicity of a truncated read.
		// -Ddatagen.fuzz.cases=0 deliberately runs no cases, and then there is nothing to cover.
		assertTrue(CASES == 0 || truncatingPrograms > 0,
				gate("no program was cut short: " + CASES + " cases, seed " + SEED));
		assertTrue(CASES == 0 || completePrograms > 0,
				gate("every program failed early: " + CASES + " cases, seed " + SEED));
	}

	/**
	 * The exact truncation wording, pinned literally at every field boundary of one fixed layout, for
	 * every source and both cursor flavours. The fuzz test above compares each claim against the model;
	 * this table additionally compares it against the message itself, so neither a changed message nor
	 * a per-path difference in the claimed byte count can pass unnoticed. The layout is
	 * {@code shortText("abc") int int52 long mediumText("ab")}, which puts a boundary at 0, 1, 4, 6, 7,
	 * 9, 12, 16, 19, 25, 28 and 29 bytes — including the two different claims a length-prefixed text can
	 * state: the width of its header, and the length it just read from the data.
	 */
	@Test
	void everyCursorKindStatesTheSameTruncationClaimAtEveryFieldBoundary() {
		byte[] payload = claimPayload();
		try (var arena = Arena.ofConfined()) {
			for (int index = 0; index < CLAIMS.length; index += 2) {
				int region = Integer.parseInt(CLAIMS[index]);
				String expectedFailure = CLAIMS[index + 1];
				Program program = new Program(payload, claimSteps(), region);
				for (Source source : sources(arena, payload)) {
					String diagnostic = gate("region=" + region + " source=" + source.name);
					assertEquals(expectedFailure, failureLine(program.expectedLines),
							diagnostic + " (model wording)");
					Outcome generic = run(program, source, new DecodeBudget(UNLIMITED));
					assertMatches(program, generic, diagnostic + " (generic cursor)");
					assertEquals(expectedFailure, failureLine(generic.lines),
							diagnostic + " (generic cursor wording)");
					Outcome specialized = runSpecialized(program, source, new DecodeBudget(UNLIMITED));
					assertMatches(program, specialized, diagnostic + " (specialized cursor)");
					assertEquals(expectedFailure, failureLine(specialized.lines),
							diagnostic + " (specialized cursor wording)");
				}
			}
		}
	}

	/** The failure line of an outcome, or {@code ""} when the program ran to completion. */
	private static String failureLine(List<String> lines) {
		return lines.size() >= 2 && lines.get(lines.size() - 1).startsWith("remainingAfterFailure=")
				? lines.get(lines.size() - 2)
				: "";
	}

	/** {@code shortText("abc") int int52 long mediumText("ab")} — 30 bytes, header starts at 0, 5, 9, 16, 24. */
	private static byte[] claimPayload() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			out.writeShort(3);
			out.writeBytes("abc");
			out.writeInt(0x0102_0304);
			writeInt52(out, 0x0001_2345_6789_ABCEL);
			out.writeLong(0x1122_3344_5566_7788L);
			out.writeInt(2);
			out.writeBytes("ab");
			out.flush();
		} catch (IOException impossible) {
			throw new UncheckedIOException(impossible);
		}
		return bytes.toByteArray();
	}

	private static List<Step> claimSteps() {
		return List.of(
				new TextField(2, "abc".getBytes(StandardCharsets.UTF_8), false),
				new FixedStep(4, "int", () -> 0x0102_0304) {
					@Override Object value(BufDataCursor cursor) { return cursor.readInt(); }
				},
				new FixedStep(Int52.BYTES, "int52", () -> 0x0001_2345_6789_ABCEL) {
					@Override Object value(BufDataCursor cursor) { return cursor.readInt52(); }
				},
				new FixedStep(8, "long", () -> 0x1122_3344_5566_7788L) {
					@Override Object value(BufDataCursor cursor) { return cursor.readLong(); }
				},
				new TextField(4, "ab".getBytes(StandardCharsets.UTF_8), true));
	}

	/** Region size, then the exact first-failure line; the empty string means "no failure". */
	private static final String[] CLAIMS = {
			"0", "MalformedDataException: Truncated input: need 2 bytes, have 0",
			"1", "MalformedDataException: Truncated input: need 2 bytes, have 1",
			"4", "MalformedDataException: Truncated input: need 3 bytes, have 2",
			"6", "MalformedDataException: Truncated input: need 4 bytes, have 1",
			"7", "MalformedDataException: Truncated input: need 4 bytes, have 2",
			"9", "MalformedDataException: Truncated input: need 7 bytes, have 0",
			"12", "MalformedDataException: Truncated input: need 7 bytes, have 3",
			"16", "MalformedDataException: Truncated input: need 8 bytes, have 0",
			"19", "MalformedDataException: Truncated input: need 8 bytes, have 3",
			"25", "MalformedDataException: Truncated input: need 4 bytes, have 1",
			"28", "MalformedDataException: Truncated input: need 2 bytes, have 0",
			"29", "MalformedDataException: Truncated input: need 2 bytes, have 1",
			"30", "",
	};

	// ------------------------------------------------------------------ runner

	/** Runs the program on one source through the plain {@link BufDataCursor} dispatch. */
	private static Outcome run(Program program, Source source, DecodeBudget budget) {
		BufDataCursor cursor = new BufDataCursor(budget);
		cursor.bind(source.buf, source.offset, program.region);
		try {
			return execute(program, cursor, budget);
		} finally {
			cursor.unbind();
		}
	}

	/**
	 * Runs the same program through {@link BufDataCursor#bindSpecialized}, i.e. through exactly one of
	 * {@link HeapBufDataCursor}, {@link MemorySegmentBufDataCursor} or {@link FallbackBufDataCursor}.
	 */
	private static Outcome runSpecialized(Program program, Source source, DecodeBudget budget) {
		var heap = new HeapBufDataCursor(budget);
		var segment = new MemorySegmentBufDataCursor(budget);
		var fallback = new FallbackBufDataCursor(budget);
		BufDataCursor.StorageKind kind =
				BufDataCursor.bindSpecialized(source.buf, source.offset, program.region, heap, segment, fallback);
		BufDataCursor chosen = switch (kind) {
			case HEAP -> heap;
			case MEMORY_SEGMENT -> segment;
			case FALLBACK -> fallback;
		};
		try {
			return execute(program, chosen, budget);
		} finally {
			heap.unbind();
			segment.unbind();
			fallback.unbind();
		}
	}

	/**
	 * Executes the steps, recording one outcome line per step. A failing step ends the program, and the
	 * bookkeeping taken after the failure is the atomicity evidence: a single-call read leaves every byte
	 * where it was, while a composite read keeps the header it had already decoded.
	 */
	private static Outcome execute(Program program, BufDataCursor cursor, DecodeBudget budget) {
		Outcome outcome = new Outcome();
		for (Step step : program.steps) {
			try {
				outcome.lines.add(step.read(cursor, cursor.remaining()));
			} catch (RuntimeException failure) {
				outcome.lines.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
				outcome.lines.add("remainingAfterFailure=" + cursor.remaining());
				break;
			}
		}
		outcome.remaining = cursor.remaining();
		outcome.position = cursor.position();
		outcome.length = cursor.length();
		outcome.payloadClaims = budget.claimedPayloadBytes();
		outcome.arrayClaims = budget.claimedArrayElements();
		// a failed read must leave the input usable: the untouched tail has to stay fully readable
		outcome.tail = new byte[cursor.remaining()];
		cursor.readFully(outcome.tail);
		assertEquals(0, cursor.remaining(), gate("reading the tail must consume the region"));
		return outcome;
	}

	private static void assertMatches(Program program, Outcome outcome, String diagnostic) {
		assertEquals(program.expectedLines, outcome.lines, diagnostic + " (per-step outcomes)");
		assertEquals(program.expectedRemaining, outcome.remaining, diagnostic + " (remaining)");
		assertEquals(program.expectedPosition, outcome.position, diagnostic + " (position)");
		assertEquals(program.region, outcome.length, diagnostic + " (length must stay the bound region)");
		assertEquals(program.expectedPayloadClaims, outcome.payloadClaims, diagnostic + " (payload claims)");
		assertEquals(program.expectedArrayClaims, outcome.arrayClaims, diagnostic + " (array claims)");
		assertArrayEquals(program.expectedTail, outcome.tail, diagnostic + " (unread bytes)");
	}

	/** The storage kind the production dispatch selects, without reading anything. */
	private static BufDataCursor.StorageKind dispatchedKind(Source source) {
		try (var heap = new HeapBufDataCursor(UNLIMITED);
				var segment = new MemorySegmentBufDataCursor(UNLIMITED);
				var fallback = new FallbackBufDataCursor(UNLIMITED)) {
			BufDataCursor.StorageKind kind =
					BufDataCursor.bindSpecialized(source.buf, source.offset, 0, heap, segment, fallback);
			heap.unbind();
			segment.unbind();
			fallback.unbind();
			return kind;
		}
	}

	// ------------------------------------------------------------------ sources

	/** The seven sources, each exposing exactly {@code payload} to the reader. */
	private static List<Source> sources(Arena arena, byte[] payload) {
		int length = payload.length;
		byte[] paddedHeap = new byte[PAD_BEFORE + length + PAD_AFTER];
		System.arraycopy(payload, 0, paddedHeap, PAD_BEFORE, length);
		MemorySegment paddedNative = arena.allocate(Math.max(1, paddedHeap.length), 1);
		MemorySegment.copy(MemorySegment.ofArray(paddedHeap), 0, paddedNative, 0, paddedHeap.length);
		MemorySegment exactNative = arena.allocate(Math.max(1, length), 1);
		if (length != 0) {
			MemorySegment.copy(MemorySegment.ofArray(payload), 0, exactNative, 0, length);
		}
		return List.of(
				new Source("heap-at-zero", Buf.wrap(payload), 0, BufDataCursor.StorageKind.HEAP),
				new Source("heap-padded-offset", Buf.wrap(paddedHeap), PAD_BEFORE, BufDataCursor.StorageKind.HEAP),
				new Source("heap-two-level-slice",
						Buf.wrap(paddedHeap).subListForced(1, PAD_BEFORE + length + PAD_AFTER)
								.subListForced(PAD_BEFORE - 1, PAD_BEFORE - 1 + length),
						0, BufDataCursor.StorageKind.HEAP),
				new Source("native-full-at-zero", new NoCopyNativeBuf(exactNative), 0,
						BufDataCursor.StorageKind.MEMORY_SEGMENT),
				new Source("native-padded-offset", new NoCopyNativeBuf(paddedNative), PAD_BEFORE,
						BufDataCursor.StorageKind.MEMORY_SEGMENT),
				new Source("native-non-zero-slice",
						new NoCopyNativeBuf(paddedNative.asSlice(PAD_BEFORE, length)), 0,
						BufDataCursor.StorageKind.MEMORY_SEGMENT),
				new Source("forced-fallback", new NoCopyFallbackBuf(paddedHeap), PAD_BEFORE,
						BufDataCursor.StorageKind.FALLBACK));
	}

	// ------------------------------------------------------------------ program

	/**
	 * One typed read. {@link #cost}, {@link #claim} and {@link #read} must describe the same bytes: the
	 * model calls {@code read(null, remaining)} to predict the outcome line, the cursor calls
	 * {@code read(cursor, remaining)} to produce it.
	 */
	private interface Step {
		/** The bytes this step consumes, or {@code -1} when it cannot complete inside {@code remaining}. */
		int cost(int remaining);

		/** The claim body a truncating step states, given {@code remaining} at its start. */
		String claim(int remaining);

		/**
		 * The bytes a failing step still consumed. A single-call read is atomic and consumes nothing, but a
		 * composite read that already decoded its header leaves that header consumed, which is why the
		 * bookkeeping after a failure has to be part of the contract instead of assumed.
		 */
		default int consumedOnFailure(int remaining) {
			return 0;
		}

		String read(BufDataCursor cursor, int remaining);
	}

	/** The model of one program: its bytes, the adversarial cut, and the expected outcome lines. */
	private static final class Program {
		private final byte[] payload;
		private final List<Step> steps;
		private final int region;
		private final List<String> expectedLines = new ArrayList<>();
		private boolean failed;
		private int expectedRemaining;
		private int expectedPosition;
		private long expectedPayloadClaims;
		private long expectedArrayClaims;
		private byte[] expectedTail = new byte[0];

		private Program(byte[] payload, List<Step> steps, int region) {
			this.payload = payload;
			this.steps = steps;
			this.region = region;
			simulate();
		}

		static Program random(Random random, int stepCount) {
			return random(random, stepCount, null);
		}

		/** {@code fullRegion} forces a complete (TRUE) or one-byte-truncated (FALSE) region; null keeps the random cut. */
		static Program random(Random random, int stepCount, Boolean fullRegion) {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (var out = new DataOutputStream(bytes)) {
				List<Step> built = new ArrayList<>();
				int textPoolIndex = 0;
				for (int index = 0; index < stepCount; index++) {
					switch (random.nextInt(13)) {
						case 0 -> {
							boolean value = random.nextBoolean();
							out.writeBoolean(value);
							built.add(new FixedStep(1, "boolean", () -> value) {
								@Override Object value(BufDataCursor cursor) { return cursor.readBoolean(); }
							});
						}
						case 1 -> {
							int bits = edgeByte(random);
							out.writeByte(bits);
							built.add(new FixedStep(1, "byte", () -> (byte) bits) {
								@Override Object value(BufDataCursor cursor) { return cursor.readByte(); }
							});
						}
						case 2 -> {
							int bits = edgeByte(random);
							out.writeByte(bits);
							built.add(new FixedStep(1, "ubyte", () -> Byte.toUnsignedInt((byte) bits)) {
								@Override Object value(BufDataCursor cursor) { return cursor.readUnsignedByte(); }
							});
						}
						case 3 -> {
							int bits = edgeShort(random);
							out.writeShort(bits);
							built.add(new FixedStep(2, "short", () -> (short) bits) {
								@Override Object value(BufDataCursor cursor) { return cursor.readShort(); }
							});
						}
						case 4 -> {
							int bits = edgeShort(random);
							out.writeShort(bits);
							built.add(new FixedStep(2, "ushort", () -> Short.toUnsignedInt((short) bits)) {
								@Override Object value(BufDataCursor cursor) { return cursor.readUnsignedShort(); }
							});
						}
						case 5 -> {
							int bits = edgeChar(random);
							out.writeChar(bits);
							built.add(new FixedStep(2, "char", () -> (char) bits) {
								@Override Object value(BufDataCursor cursor) { return cursor.readChar(); }
							});
						}
						case 6 -> {
							int bits = edgeInt(random);
							out.writeInt(bits);
							built.add(new FixedStep(4, "int", () -> bits) {
								@Override Object value(BufDataCursor cursor) { return cursor.readInt(); }
							});
						}
						case 7 -> {
							long bits = edgeLong(random);
							out.writeLong(bits);
							built.add(new FixedStep(8, "long", () -> bits) {
								@Override Object value(BufDataCursor cursor) { return cursor.readLong(); }
							});
						}
						case 8 -> {
							long value = random.nextInt(4) == 0
									? FuzzValues.int52OutOfRange(random)
									: FuzzValues.int52(random);
							writeInt52(out, value);
							// the wire field is masked, so a value outside the domain reads back truncated
							built.add(new FixedStep(Int52.BYTES, "int52", () -> value & INT52_MASK) {
								@Override Object value(BufDataCursor cursor) { return cursor.readInt52(); }
							});
						}
						case 9 -> {
							// the writer's documented canonicalisation is modelled, not copied from a reader:
							// writeFloat/writeDouble store floatToIntBits/doubleToLongBits, so every NaN
							// becomes the canonical pattern on the wire while signed zero keeps its sign
							float value = Float.intBitsToFloat(Float.floatToIntBits(edgeFloat(random)));
							out.writeFloat(value);
							built.add(new FixedStep(4, "float", () -> value) {
								@Override Object value(BufDataCursor cursor) { return cursor.readFloat(); }
							});
						}
						case 10 -> {
							double value = Double.longBitsToDouble(Double.doubleToLongBits(edgeDouble(random)));
							out.writeDouble(value);
							built.add(new FixedStep(8, "double", () -> value) {
								@Override Object value(BufDataCursor cursor) { return cursor.readDouble(); }
							});
						}
						case 11 -> {
							// a length-prefixed text makes the reader claim payload bytes for a length it
							// read from the data, which is where a per-path bounds difference shows up
							boolean medium = random.nextBoolean();
							String text = FuzzValues.string(random);
							byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
							if (medium) {
								out.writeInt(encoded.length);
							} else {
								out.writeShort(encoded.length);
							}
							out.write(encoded);
							built.add(new TextField(medium ? 4 : 2, encoded, medium));
						}
						default -> {
							// a raw run of bytes, drawn from a small pool so widths repeat and the cut
							// lands on the same boundary from several directions
							String text = TEXT_POOL[(textPoolIndex++) % TEXT_POOL.length];
							byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
							out.write(encoded);
							built.add(new ByteArray(encoded));
						}
					}
				}
				// Skip modes need the model's remaining count, so they run last as one group: zero, one,
				// negative, the exact boundary, the boundary plus one, and an absurd count.
				built.add(new SkipMode("zero", remaining -> 0));
				built.add(new SkipMode("one", remaining -> 1));
				built.add(new SkipMode("negative", remaining -> -3));
				built.add(new SkipMode("exact", remaining -> remaining));
				built.add(new SkipMode("over", remaining -> remaining + 1));
				built.add(new SkipMode("huge", remaining -> Long.MAX_VALUE / 2));
				out.flush();
				byte[] payload = bytes.toByteArray();
				int region = fullRegion == null ? cut(random, payload.length)
						: fullRegion ? payload.length : Math.min(1, payload.length);
				return new Program(payload, built, region);
			} catch (IOException impossible) {
				throw new UncheckedIOException(impossible);
			}
		}

		/** Adversarial region sizes: all, none, one byte, and around the widest fixed fields. */
		private static int cut(Random random, int length) {
			if (length == 0) {
				return 0;
			}
			int[] candidates = {length, length, 0, 1, 2, 6, 7, 8, 9, length - 1, length - 7, length - 8};
			int cut = candidates[random.nextInt(candidates.length)];
			return Math.min(length, Math.max(0, cut));
		}

		/** The model: consume step by step until a step cannot, recording every claim it states. */
		private void simulate() {
			int position = 0;
			for (Step step : steps) {
				int remaining = region - position;
				int cost = step.cost(remaining);
				if (cost < 0) {
					expectedLines.add("MalformedDataException: Truncated input: " + step.claim(remaining));
					expectedLines.add("remainingAfterFailure=" + (remaining - step.consumedOnFailure(remaining)));
					position += step.consumedOnFailure(remaining);
					failed = true;
					break;
				}
				expectedLines.add(step.read(null, remaining));
				if (step instanceof TextField text) {
					expectedPayloadClaims += text.encoded.length;
				}
				if (step instanceof ByteArray raw) {
					expectedArrayClaims += raw.bytes.length;
				}
				position += cost;
			}
			expectedPosition = position;
			expectedRemaining = region - position;
			expectedTail = Arrays.copyOfRange(payload, position, region);
		}

		String fingerprint() {
			return Integer.toHexString(Arrays.hashCode(payload) ^ (region * 31) ^ (steps.size() * 131));
		}
	}

	/** A fixed-width value: consumes exactly {@code width} bytes and returns the JDK-decoded value. */
	private abstract static class FixedStep implements Step {
		private final int width;
		private final String kind;
		private final Supplier<Object> model;

		FixedStep(int width, String kind, Supplier<Object> model) {
			this.width = width;
			this.kind = kind;
			this.model = model;
		}

		abstract Object value(BufDataCursor cursor);

		@Override
		public final int cost(int remaining) {
			return remaining >= width ? width : -1;
		}

		@Override
		public final String claim(int remaining) {
			return "need " + width + " bytes, have " + remaining;
		}

		@Override
		public final String read(BufDataCursor cursor, int remaining) {
			// the decoded value must be exactly what the encoder wrote, so both spellings render the same
			return kind + "=" + render(cursor == null ? model.get() : value(cursor));
		}
	}

	/** A length-prefixed text: the header is read first, so a short payload claims its own length. */
	private static final class TextField implements Step {
		private final int header;
		private final byte[] encoded;
		private final boolean medium;

		TextField(int header, byte[] encoded, boolean medium) {
			this.header = header;
			this.encoded = encoded;
			this.medium = medium;
		}

		@Override
		public int cost(int remaining) {
			return remaining < header || remaining - header < encoded.length ? -1 : header + encoded.length;
		}

		@Override
		public String claim(int remaining) {
			// two distinct claims are possible here, so the model has to predict which one is stated: the
			// header is consumed before the payload is demanded, so the payload claim sees fewer bytes
			return remaining < header
					? "need " + header + " bytes, have " + remaining
					: "need " + encoded.length + " bytes, have " + (remaining - header);
		}

		@Override
		public int consumedOnFailure(int remaining) {
			// the header is a separate read, so a payload truncation still consumed it
			return remaining < header ? 0 : header;
		}

		@Override
		public String read(BufDataCursor cursor, int remaining) {
			if (cursor == null) {
				return "text=" + render(new String(encoded, StandardCharsets.UTF_8));
			}
			return "text=" + render(medium
					? cursor.readMediumText(StandardCharsets.UTF_8)
					: cursor.readShortText(StandardCharsets.UTF_8));
		}
	}

	/** A raw byte-array read, which charges the array-element budget. */
	private static final class ByteArray implements Step {
		private final byte[] bytes;

		ByteArray(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public int cost(int remaining) {
			return remaining >= bytes.length ? bytes.length : -1;
		}

		@Override
		public String claim(int remaining) {
			return "need " + bytes.length + " bytes, have " + remaining;
		}

		@Override
		public String read(BufDataCursor cursor, int remaining) {
			return "bytes=" + render(cursor == null ? bytes : cursor.readByteArray(bytes.length));
		}
	}

	/** A {@code skip(long)} step; every mode is clamped, so it can never fail. */
	private static final class SkipMode implements Step {
		private final String name;
		private final LongUnaryOperator count;

		SkipMode(String name, LongUnaryOperator count) {
			this.name = name;
			this.count = count;
		}

		private long requested(int remaining) {
			return Math.max(0L, Math.min(count.applyAsLong(remaining), (long) remaining));
		}

		@Override
		public int cost(int remaining) {
			return (int) requested(remaining);
		}

		@Override
		public String claim(int remaining) {
			return "unreachable: skip never truncates";
		}

		@Override
		public String read(BufDataCursor cursor, int remaining) {
			return "skip[" + name + "=" + (cursor == null
					? requested(remaining)
					: cursor.skip(count.applyAsLong(remaining))) + "]";
		}
	}

	/** What one cursor produced for one program. */
	private static final class Outcome {
		private final List<String> lines = new ArrayList<>();
		private int remaining;
		private int position;
		private int length;
		private long payloadClaims;
		private long arrayClaims;
		private byte[] tail = new byte[0];
	}

	/** One readable source of the payload, bound at {@code offset}. */
	private record Source(String name, Buf buf, int offset, BufDataCursor.StorageKind kind) {}

	// ------------------------------------------------------------------ values

	private static final String[] TEXT_POOL = {
			"", "a", "ab", "\u0000", "a\u0000b", "\u007f", "\u0080", "\u07ff", "\u0800", "\uD83D\uDE00", "é",
			"你好", "0123456789",
	};

	private static int edgeByte(Random random) {
		return switch (random.nextInt(5)) {
			case 0 -> 0;
			case 1 -> 1;
			case 2 -> -1;
			case 3 -> Byte.MIN_VALUE;
			default -> random.nextInt();
		};
	}

	private static int edgeShort(Random random) {
		return switch (random.nextInt(6)) {
			case 0 -> 0;
			case 1 -> 1;
			case 2 -> -1;
			case 3 -> Short.MIN_VALUE;
			case 4 -> 0xFF;
			default -> random.nextInt();
		};
	}

	private static int edgeChar(Random random) {
		return switch (random.nextInt(5)) {
			case 0 -> 0;
			case 1 -> 1;
			case 2 -> 0xFFFF;
			case 3 -> 'a';
			default -> random.nextInt();
		};
	}

	private static int edgeInt(Random random) {
		return switch (random.nextInt(6)) {
			case 0 -> 0;
			case 1 -> 1;
			case 2 -> -1;
			case 3 -> Integer.MIN_VALUE;
			case 4 -> Integer.MAX_VALUE;
			default -> random.nextInt();
		};
	}

	private static long edgeLong(Random random) {
		return switch (random.nextInt(7)) {
			case 0 -> 0L;
			case 1 -> 1L;
			case 2 -> -1L;
			case 3 -> Long.MIN_VALUE;
			case 4 -> Long.MAX_VALUE;
			case 5 -> 0xFFL;
			default -> random.nextLong();
		};
	}

	private static float edgeFloat(Random random) {
		return switch (random.nextInt(8)) {
			case 0 -> 0.0f;
			case 1 -> -0.0f;
			case 2 -> Float.NaN;
			case 3 -> Float.intBitsToFloat(0x7F80_0001);
			case 4 -> Float.POSITIVE_INFINITY;
			case 5 -> Float.NEGATIVE_INFINITY;
			case 6 -> Float.MIN_VALUE;
			default -> Float.intBitsToFloat(random.nextInt());
		};
	}

	private static double edgeDouble(Random random) {
		return switch (random.nextInt(8)) {
			case 0 -> 0.0d;
			case 1 -> -0.0d;
			case 2 -> Double.NaN;
			case 3 -> Double.longBitsToDouble(0x7FF8_0000_0000_0001L);
			case 4 -> Double.POSITIVE_INFINITY;
			case 5 -> Double.NEGATIVE_INFINITY;
			case 6 -> Double.MIN_VALUE;
			default -> Double.longBitsToDouble(random.nextLong());
		};
	}

	// ------------------------------------------------------------------ helpers

	/** Writes the seven-byte Int52 field: big-endian, with the lead byte masked to its low nibble. */
	private static void writeInt52(DataOutputStream out, long value) throws IOException {
		long wire = value & INT52_MASK;
		for (int shift = 48; shift >= 0; shift -= 8) {
			out.write((int) ((wire >>> shift) & 0xFF));
		}
	}

	/** A stable, comparable rendering of a read value, so a parity failure states the exact bits. */
	private static String render(Object value) {
		if (value instanceof byte[] bytes) {
			return "b[" + HexFormat.of().formatHex(bytes) + "]";
		}
		if (value instanceof String text) {
			return "s[" + text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
					.replace("\t", "\\t") + "]";
		}
		if (value instanceof Character character) {
			return "c[" + (int) character + "]";
		}
		if (value instanceof Float float0 && (float0.isNaN() || float0 == 0.0f)) {
			// raw bits, not floatToIntBits: a canonicalising path would hide a NaN payload difference
			return "f[" + Float.floatToRawIntBits(float0) + "]";
		}
		if (value instanceof Double double0 && (double0.isNaN() || double0 == 0.0d)) {
			return "d[" + Double.doubleToRawLongBits(double0) + "]";
		}
		return String.valueOf(value);
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}

	/** Proves the native path never falls back to a heap copy of the payload. */
	private static final class NoCopyNativeBuf extends MemorySegmentBuf {
		private NoCopyNativeBuf(MemorySegment segment) {
			super(segment);
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("native cursor copied the whole payload");
		}

		@Override
		public byte[] getBackingByteArrayStrict() {
			return null;
		}
	}

	/** Proves the fallback path neither reaches heap storage nor copies the payload. */
	private static final class NoCopyFallbackBuf extends ByteListBuf {
		private NoCopyFallbackBuf(byte[] data) {
			super(data);
		}

		@Override
		public byte[] getBackingByteArrayStrict() {
			return null;
		}

		@Override
		public MemorySegment asMemorySegmentStrict() {
			return null;
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("fallback cursor copied the whole payload");
		}

		@Override
		public byte[] getBackingByteArray() {
			throw new AssertionError("fallback cursor asked for heap storage");
		}
	}
}
