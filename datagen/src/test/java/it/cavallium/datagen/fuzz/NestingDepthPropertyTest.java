package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.ReadSession;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataInputStream;
import it.cavallium.stream.SafeDataOutput;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Property;

/**
 * Structural nesting depth against the {@link DecodeLimits#maximumStructuralNestingDepth()} wall, on a
 * synthetic recursive codec driven through a real {@link ReadSession} and {@link DecodeBudget}.
 *
 * <p>The wire format is deliberately trivial so that the only thing under test is the accounting: one
 * frame is a nullable framing (a {@code boolean} presence tag) immediately followed by an array framing
 * (an {@code int} element count), and the value ends with an absent tag. Each present frame enters
 * exactly one structure, so {@code n} frames are {@code n} nested structures. The frames claim
 * alternately as array elements and as payload bytes, so one run exercises both budget-claim paths.</p>
 *
 * <p>The wall is inclusive: {@code maximumStructuralNestingDepth = w} accepts exactly {@code w} frames
 * and refuses frame {@code w + 1} with {@link DecodeLimitExceededException} — never with a malformed-data
 * failure, and never by letting the depth overflow into a negative number.</p>
 *
 * <p>The baseline is what a failure must not corrupt. Every frame leaves the budget at exactly the
 * {@link DecodeBudget#structuralDepth()} it found, on the way out of a successful read as well as on the
 * way out of a refused one, back to the baseline the caller started with. That is observed from
 * <em>inside</em> the read, in a {@code finally} that runs before {@link ReadSession#read} exits its
 * root, because {@link DecodeBudget#exitRoot()} zeroes the depth on its own once the last root is
 * closed — an assertion made after it would pass for the wrong reason.</p>
 *
 * <p>Claim counters follow the documented lazy rule instead: a finished root's claims stay visible until
 * the next {@link DecodeBudget#enterRoot()} clears them, so this test asserts the claims the root made
 * and only then drains one empty root to reach the quiescent state.</p>
 */
class NestingDepthPropertyTest {

	/** Nesting walls this property walks; the near-limit ladder is taken around each of them. */
	private static final int[] DEPTH_WALLS = {1, 2, 3, 4, 8, 16, 32, 64};

	@Property(tries = 1)
	void theWallAcceptsItsLastFrameAndRefusesExactlyOneMore() {
		for (int wall : tierSample(DEPTH_WALLS, "nesting-walls", 3)) {
			// parity 0 and 1 swap which framing claims first, so the wall is hit from both sides
			for (int parity : List.of(0, 1)) {
				NestingCodec codec = new NestingCodec(parity);
				Outcome accepted = run(codec, wall, wall);
				assertTrue(accepted.accepted(), gate("wall=" + wall + " parity=" + parity
						+ " must accept exactly wall frames, got " + accepted));
				assertEquals(wall, accepted.frames(), gate("wall=" + wall + " accepted frame count"));
				assertEquals(wall, accepted.maxDepth(), gate("wall=" + wall + " reached depth"));
				assertEquals(0, accepted.depthAtReturn(),
						gate("wall=" + wall + " a successful read must unwind to the baseline"));

				Outcome refused = run(codec, wall, wall + 1);
				assertTrue(refused.refusedByLimit(), gate("wall=" + wall + " parity=" + parity
						+ " must refuse wall+1 with DecodeLimitExceededException, got " + refused));
				assertEquals(wall, refused.depthAtFailure(),
						gate("wall=" + wall + " the refused frame must not have increased the depth"));
				assertEquals(0, refused.depthAtReturn(),
						gate("wall=" + wall + " a refused read must unwind to the baseline too"));
			}
		}
	}

	@Property(tries = 1)
	void structuralDepthUnwindsToTheBaselineAtEveryDepthUpToTheWallAndBeyond() {
		for (int wall : tierSample(DEPTH_WALLS, "nesting-ladder-walls", 3)) {
			// the ladder is 1..wall+2, so every accepted depth, the frame past the wall and the
			// over-long value are all covered against the same budget
			for (int frames = 1; frames <= wall + 2; frames++) {
				Outcome outcome = run(new NestingCodec(0), wall, frames);
				assertEquals(0, outcome.depthAtReturn(),
						gate("wall=" + wall + " frames=" + frames + " must unwind to the baseline"));
				assertEquals(0, outcome.depthAfterDrain(),
						gate("wall=" + wall + " frames=" + frames + " must leave a quiescent budget"));
				int walked = Math.min(frames, wall);
				assertEquals(expectedArrayClaims(walked), outcome.arrayClaims(),
						gate("wall=" + wall + " frames=" + frames + " array-element claims"));
				assertEquals(expectedPayloadClaims(walked), outcome.payloadClaims(),
						gate("wall=" + wall + " frames=" + frames + " payload-byte claims"));
				assertEquals(0L, outcome.claimedAfterDrain()[0], gate("claims must drain, array elements"));
				assertEquals(0L, outcome.claimedAfterDrain()[1], gate("claims must drain, payload bytes"));
				if (frames <= wall) {
					assertTrue(outcome.accepted(),
							gate("wall=" + wall + " frames=" + frames + " must be accepted, got " + outcome));
					assertEquals(frames, outcome.frames(), gate("wall=" + wall + " walked frames"));
					assertEquals(frames, outcome.maxDepth(),
							gate("wall=" + wall + " frames=" + frames + " must reach depth " + frames));
				} else {
					assertTrue(outcome.refusedByLimit(),
							gate("wall=" + wall + " frames=" + frames + " must be refused, got " + outcome));
					assertEquals(wall, outcome.depthAtFailure(),
							gate("wall=" + wall + " the refused frame must leave the wall depth, not a"
									+ " deeper or a negative one"));
				}
			}
		}
	}

	@Property(tries = 1)
	void skipAndEveryStoragePathFollowTheSameNestingWall() {
		for (int wall : tierSample(DEPTH_WALLS, "nesting-skip-walls", 3)) {
			for (int frames : new int[] {1, wall, wall + 1, wall + 2}) {
				byte[] encoded = wire(frames);
				int walked = Math.min(frames, wall);

				Outcome skipped = skip(new NestingCodec(0), wall, encoded);
				if (frames <= wall) {
					assertTrue(skipped.accepted(), gate("wall=" + wall + " skip accepts " + frames));
					assertEquals(frames, skipped.frames(), gate("wall=" + wall + " skipped frames"));
				} else {
					assertTrue(skipped.refusedByLimit(),
							gate("wall=" + wall + " skip must refuse " + frames + ", got " + skipped));
				}
				assertEquals(0, skipped.depthAtReturn(), gate("wall=" + wall + " skip must unwind"));
				assertEquals(expectedArrayClaims(walked), skipped.arrayClaims(),
						gate("wall=" + wall + " skip array-element claims"));

				StoragesResult storages = readThroughStorages(wall, encoded, frames);
				assertEquals(6, storages.views(), gate("every storage family must be read"));
				assertEquals(frames <= wall ? 6 : 0, storages.accepted(),
						gate("wall=" + wall + " frames=" + frames + " every storage must agree, accepted="
								+ storages.accepted() + " of " + storages.views()));
				assertFalse(storages.mismatchedDepth(),
						gate("wall=" + wall + " frames=" + frames + " a storage left the baseline behind"));
				assertFalse(storages.wrongException(),
						gate("wall=" + wall + " frames=" + frames + " a storage refused with the wrong type"));
			}
		}
	}

	/** @return the tier-scaled sample of {@code values}, always including the largest one. */
	private static List<Integer> tierSample(int[] values, String id, int base) {
		int wanted = Math.max(1, Math.min(values.length, FuzzTiers.cases(id, base)));
		List<Integer> result = new ArrayList<>(wanted);
		for (int i = 0; i < wanted; i++) {
			result.add(values[i * values.length / wanted]);
		}
		result.set(wanted - 1, values[values.length - 1]);
		return result;
	}

	/**
	 * Limits whose claim dimensions are wide enough that only the nesting wall can ever be hit.
	 * {@link DecodeLimits} is a plain record without {@code with...} copies, so each wall is spelled out.
	 */
	private static DecodeLimits wallLimits(int wall) {
		return new DecodeLimits(4_096, 4_096, 1_048_576L, 1_048_576L, wall);
	}

	/** @return one {@code frames}-deep read through a session over a bounded cursor. */
	private static Outcome run(NestingCodec codec, int wall, int frames) {
		DecodeBudget budget = new DecodeBudget(wallLimits(wall));
		BufDataCursor cursor = new BufDataCursor(budget);
		byte[] encoded = wire(frames);
		cursor.bind(Buf.wrap(encoded), 0, encoded.length);
		ReadSession<Integer> session = codec.newReadSession();
		int framesRead = -1;
		boolean refused = false;
		try {
			framesRead = session.read(cursor);
		} catch (DecodeLimitExceededException expected) {
			refused = true;
		} finally {
			cursor.unbind();
		}
		return observe(codec, budget, framesRead, refused);
	}

	/** @return one {@code skip} of the same wire through a session over a bounded cursor. */
	private static Outcome skip(NestingCodec codec, int wall, byte[] encoded) {
		DecodeBudget budget = new DecodeBudget(wallLimits(wall));
		BufDataCursor cursor = new BufDataCursor(budget);
		cursor.bind(Buf.wrap(encoded), 0, encoded.length);
		ReadSession<Integer> session = codec.newReadSession();
		int framesSkipped = -1;
		boolean refused = false;
		try {
			session.skip(cursor);
			framesSkipped = codec.framesSkipped;
		} catch (DecodeLimitExceededException expected) {
			refused = true;
		} finally {
			cursor.unbind();
		}
		return observe(codec, budget, framesSkipped, refused);
	}

	/** Reads the same wire through all six storages and reports whether they behaved identically. */
	private static StoragesResult readThroughStorages(int wall, byte[] encoded, int frames) {
		int views = 0;
		int accepted = 0;
		boolean mismatchedDepth = false;
		boolean wrongException = false;
		try (FuzzStorages storages = new FuzzStorages(encoded)) {
			for (FuzzStorages.View view : storages.all()) {
				views++;
				NestingCodec codec = new NestingCodec(0);
				DecodeBudget budget = new DecodeBudget(wallLimits(wall));
				int framesRead = -1;
				boolean refused = false;
				try {
					if (view.forwardOnly()) {
						try (SafeDataInputStream in = FuzzStorages.forwardStream(view, budget)) {
							framesRead = codec.newReadSession().read(in);
						}
					} else {
						BufDataCursor cursor = FuzzStorages.bindCursor(view, budget);
						try {
							framesRead = codec.newReadSession().read(cursor);
						} finally {
							cursor.unbind();
						}
					}
					accepted++;
				} catch (DecodeLimitExceededException expected) {
					refused = true;
				} catch (RuntimeException | Error unstable) {
					wrongException = true;
				}
				Outcome outcome = observe(codec, budget, framesRead, refused);
				if (outcome.depthAtReturn() != 0 || outcome.depthAfterDrain() != 0) {
					mismatchedDepth = true;
				}
			}
		}
		return new StoragesResult(views, accepted, mismatchedDepth, wrongException);
	}

	/** @return the claim counters, then one empty root, then the counters again. */
	private static Outcome observe(NestingCodec codec, DecodeBudget budget, int framesRead, boolean refused) {
		long[] afterRead = {budget.claimedArrayElements(), budget.claimedPayloadBytes()};
		int depthAtReturn = codec.depthAtReturn;
		budget.enterRoot();
		budget.exitRoot();
		return new Outcome(framesRead, depthAtReturn, codec.depthAtFailure, refused, codec.maxDepth,
				afterRead,
				new long[] {budget.claimedArrayElements(), budget.claimedPayloadBytes()},
				budget.structuralDepth());
	}

	/** @return the array-element claims a run of {@code walked} frames makes: the even levels. */
	private static long expectedArrayClaims(int walked) {
		return (walked + 1L) / 2L;
	}

	/** @return the payload-byte claims a run of {@code walked} frames makes: the odd levels. */
	private static long expectedPayloadClaims(int walked) {
		return walked / 2L;
	}

	/** @return {@code frames} present frames followed by the absent terminator. */
	private static byte[] wire(int frames) {
		BufDataOutput output = BufDataOutput.create();
		for (int i = 0; i < frames; i++) {
			output.writeBoolean(true);
			output.writeInt(1);
		}
		output.writeBoolean(false);
		Buf encoded = output.asList();
		return FuzzStorages.bytesOf(
				new FuzzStorages.View(FuzzStorages.Kind.FORWARD_STREAM, encoded, 0, encoded.size()));
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}

	/**
	 * What one nested decode did.
	 *
	 * @param frames            frames the decode walked through, or {@code -1} when it was refused
	 * @param depthAtReturn     {@code structuralDepth()} observed from inside the read, in a
	 *                          {@code finally} that runs before {@link ReadSession} exits its root, so it
	 *                          proves the frames unwound themselves rather than being reset
	 * @param depthAtFailure    the depth at the frame that hit the wall, i.e. the wall itself
	 * @param refusedByLimit    whether the run ended in {@link DecodeLimitExceededException}
	 * @param maxDepth          the deepest structure the run reached
	 * @param claimedAfterRead  the claim counters as the completed root left them: {@code enterRoot()}
	 *                          clears them only for the next root, so they are still visible here
	 * @param claimedAfterDrain the same counters after one empty root, i.e. the quiescent state
	 * @param depthAfterDrain   {@code structuralDepth()} of that quiescent state
	 */
	private record Outcome(int frames, int depthAtReturn, int depthAtFailure, boolean refusedByLimit,
			int maxDepth, long[] claimedAfterRead, long[] claimedAfterDrain, int depthAfterDrain) {

		boolean accepted() {
			return !refusedByLimit;
		}

		long arrayClaims() {
			return claimedAfterRead[0];
		}

		long payloadClaims() {
			return claimedAfterRead[1];
		}
	}

	/** @param views storages that were read, {@param accepted} storages that accepted the value */
	private record StoragesResult(int views, int accepted, boolean mismatchedDepth, boolean wrongException) {
	}

	/**
	 * A recursive codec whose only payload is its own nesting: a nullable presence tag plus an array
	 * element count per frame. {@code parity} swaps which framing claims first, so the two claim paths
	 * can be tested against the wall from both sides.
	 */
	private static final class NestingCodec implements DataCodec<Integer> {

		private final int parity;

		int maxDepth;
		int depthAtReturn = -1;
		int depthAtFailure = -1;
		int framesSkipped = -1;

		NestingCodec(int parity) {
			this.parity = parity;
		}

		@Override
		public void serialize(SafeDataOutput dataOutput, Integer data) {
			for (int i = 0; i < data; i++) {
				dataOutput.writeBoolean(true);
				dataOutput.writeInt(1);
			}
			dataOutput.writeBoolean(false);
		}

		@Override
		public Integer read(SafeDataInput dataInput) {
			depthAtReturn = -1;
			depthAtFailure = -1;
			maxDepth = 0;
			try {
				return descend(dataInput, 0);
			} finally {
				// observed before ReadSession#read exits the root, so exitRoot() cannot zero it first
				depthAtReturn = dataInput.decodeBudget().structuralDepth();
			}
		}

		@Override
		public void skip(SafeDataInput dataInput) {
			framesSkipped = -1;
			depthAtReturn = -1;
			depthAtFailure = -1;
			maxDepth = 0;
			try {
				framesSkipped = descend(dataInput, 0);
			} finally {
				depthAtReturn = dataInput.decodeBudget().structuralDepth();
			}
		}

		/** One frame: presence tag, one structure, alternating claim, element count, then the child. */
		private int descend(SafeDataInput dataInput, int level) {
			if (!dataInput.readBoolean()) {
				return level;
			}
			DecodeBudget budget = dataInput.decodeBudget();
			boolean entered = false;
			try {
				budget.enterStructure();
				entered = true;
				if (((level + parity) & 1) == 0) {
					budget.claimArrayElements(1);
				} else {
					budget.claimPayloadBytes(1);
				}
				dataInput.readInt();
				maxDepth = Math.max(maxDepth, budget.structuralDepth());
				return descend(dataInput, level + 1);
			} catch (RuntimeException | Error failure) {
				// only the frame that hit the wall: the outer frames see the same exception unwind
				if (depthAtFailure < 0) {
					depthAtFailure = budget.structuralDepth();
				}
				throw failure;
			} finally {
				if (entered) {
					budget.exitStructure();
				}
			}
		}

		@Override
		public ReadSession<Integer> newReadSession() {
			return new ReadSession<>() {
				@Override
				protected Integer decode(SafeDataInput input) {
					return NestingCodec.this.read(input);
				}

				@Override
				protected void skipValue(SafeDataInput input) {
					NestingCodec.this.skip(input);
				}

				@Override
				protected void clearTransientState() {
					// nothing is retained: only the counters above are observable
				}
			};
		}
	}
}
