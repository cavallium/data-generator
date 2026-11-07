package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.nativedata.BinaryString;
import it.cavallium.datagen.nativedata.Serializers;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.Property;

/**
 * Megabyte-scale payload behaviour of the three byte-hungry runtime codecs —
 * {@link Serializers#BinaryStringSerializerInstance}, {@link Serializers#ArraybyteSerializerInstance}
 * and {@link Serializers#StringSerializerInstance} — plus the two admission refusals a
 * {@link DecodeBudget} can make against them: the per-claim wall and the cumulative wall.
 *
 * <p>This is the only fuzz file that spends megabytes, and it is deliberately deep-only: a
 * {@code @net.jqwik.api.Tag("deep")} and {@link FuzzTiers#requireMinimumTier} keep the allocation
 * out of the default gate. The views covered are the heap family and the native memory segment;
 * {@link FuzzStorages.Kind#FORCED_FALLBACK} and {@link FuzzStorages.Kind#FORWARD_STREAM} are left
 * out on purpose — the fallback path copies byte-by-byte and the stream path re-copies the whole
 * payload, so at this scale they multiply runtime without testing anything the small fuzz files do
 * not already cover. That also bounds the peak memory: at most one {@link FuzzStorages} is alive per
 * case, the payload itself is capped at 2 MiB, and the views add a couple of heap copies plus a
 * native segment of the same size — roughly 64 MiB peak at the largest rung, the documented
 * allocation ceiling of this file.</p>
 *
 * <p>The accounting assertions encode the production contract exactly: a {@code BinaryString} and a
 * medium {@code String} claim <em>payload bytes</em> (so the per-claim wall they hit is
 * {@link DecodeLimits#maximumBytesPerPayload}), a {@code byte[]} array claims <em>array
 * elements</em> ({@link DecodeLimits#maximumElementsPerArray}), a rejected claim changes nothing,
 * and per {@link DecodeBudget} semantics the counters stay visible after the final {@code exitRoot()}
 * and are cleared only by the next {@code enterRoot()} — hence the empty-root drain before each
 * quiescence check.</p>
 *
 * <p>{@code tries = 1} on purpose: the tier ladder owns the case count.</p>
 */
class PayloadScalePropertyTest {

	private static final int KIB = 1024;
	private static final int MIB = 1024 * KIB;

	/** The storage families worth megabytes of reads: the heap views and the native segment. */
	private static final Set<FuzzStorages.Kind> SCALED_KINDS = EnumSet.of(
			FuzzStorages.Kind.HEAP,
			FuzzStorages.Kind.HEAP_SLICE,
			FuzzStorages.Kind.NESTED_SLICE,
			FuzzStorages.Kind.MEMORY_SEGMENT);

	@Property(tries = 1)
	@net.jqwik.api.Tag("deep")
	void megabytePayloadsRoundTripThroughHeapAndNativeStorage() {
		FuzzTiers.requireMinimumTier(FuzzTiers.Tier.DEEP);
		Random rnd = FuzzTiers.random("payload-scale-round-trip");
		int rounds = FuzzTiers.cases("payload-scale-round-trip", 1, 2, 4, 6);
		for (int size : sizes()) {
			for (Shape shape : Shape.values()) {
				for (int round = 0; round < rounds; round++) {
					byte[] payload = shape.randomPayload(rnd, size);
					byte[] encoded = shape.encode(payload);
					assertEquals(size + Integer.BYTES, encoded.length,
							gate(shape + " framing of a " + size + "-byte payload"));
					try (FuzzStorages storages = new FuzzStorages(encoded)) {
						for (FuzzStorages.View view : storages.all()) {
							if (!SCALED_KINDS.contains(view.kind())) continue;
							DecodeBudget budget = new DecodeBudget(DecodeLimits.unlimited());
							BufDataCursor cursor = FuzzStorages.bindCursor(view, budget);
							Object decoded;
							try {
								decoded = shape.read(cursor);
								assertEquals(0, cursor.available(),
										gate(shape + " read must consume exactly its bytes on " + view.kind()));
							} finally {
								cursor.unbind();
							}
							shape.assertSame(payload, decoded,
									gate(shape + " on " + view.kind() + " round " + round));
							shape.assertClaims(budget, size,
									gate(shape + " accounting on " + view.kind()));
						}
					}
				}
			}
		}
	}

	@Property(tries = 1)
	@net.jqwik.api.Tag("deep")
	void budgetRefusesPerClaimAndCumulativeClaimsWithoutConsumingAnything() {
		FuzzTiers.requireMinimumTier(FuzzTiers.Tier.DEEP);
		for (Shape shape : Shape.values()) {
			int size = sizes()[0];
			byte[] payload = shape.randomPayload(FuzzTiers.random("payload-scale-refusal"), size);
			byte[] encoded = shape.encode(payload);
			try (FuzzStorages storages = new FuzzStorages(encoded)) {
				FuzzStorages.View view = storages.kind(FuzzStorages.Kind.HEAP);
				// per-claim wall: one unit short of the payload
				DecodeBudget refused = new DecodeBudget(shape.perClaimWall(size));
				DecodeLimitExceededException perClaim = assertThrows(
						DecodeLimitExceededException.class, () -> shape.readWithinRoot(view, refused),
						gate(shape + " must refuse a per-claim wall one unit below " + size));
				assertTrue(perClaim.getMessage().contains(shape.wallMarker()),
						gate(shape + " refusal must be the per-claim wall: " + perClaim.getMessage()));
				shape.assertClaims(refused, 0, gate(shape + " refused claim must consume nothing"));

				// cumulative wall: the first read fits, the second does not
				DecodeBudget cumulative = new DecodeBudget(shape.cumulativeWall(size));
				cumulative.enterRoot();
				try {
					shape.readWithinRoot(view, cumulative);
					DecodeLimitExceededException second = assertThrows(
							DecodeLimitExceededException.class,
							() -> shape.readWithinRoot(view, cumulative),
							gate(shape + " must refuse the second " + size + "-unit claim"));
					assertTrue(second.getMessage().contains("Cumulative"),
							gate(shape + " refusal must be the cumulative wall: " + second.getMessage()));
					shape.assertClaims(cumulative, size,
							gate(shape + " cumulative refusal must keep the accepted claim"));
				} finally {
					cumulative.exitRoot();
				}
				shape.assertClaims(cumulative, size,
						gate(shape + " claims stay visible after exitRoot until the next enterRoot"));
				cumulative.enterRoot();
				cumulative.exitRoot();
				shape.assertClaims(cumulative, 0, gate(shape + " empty root must reset the counters"));

				// and with unlimited limits the exact same bytes still decode on the same view
				DecodeBudget unlimited = new DecodeBudget(DecodeLimits.unlimited());
				shape.assertSame(payload, shape.readWithinRoot(view, unlimited),
						gate(shape + " must still decode after the refusals"));
			}
		}
	}

	@Property(tries = 1)
	@net.jqwik.api.Tag("deep")
	void skipOfMegabytePayloadsConsumesEverythingAndLeavesNoClaims() {
		FuzzTiers.requireMinimumTier(FuzzTiers.Tier.DEEP);
		for (Shape shape : Shape.values()) {
			int size = sizes()[0];
			byte[] payload = shape.randomPayload(FuzzTiers.random("payload-scale-skip"), size);
			byte[] encoded = shape.encode(payload);
			try (FuzzStorages storages = new FuzzStorages(encoded)) {
				for (FuzzStorages.Kind kind : SCALED_KINDS) {
					FuzzStorages.View view = storages.kind(kind);
					DecodeBudget budget = new DecodeBudget(DecodeLimits.unlimited());
					BufDataCursor cursor = FuzzStorages.bindCursor(view, budget);
					try {
						shape.skip(cursor);
						assertEquals(0, cursor.available(),
								gate(shape + " skip must consume the whole value on " + kind));
					} finally {
						cursor.unbind();
					}
					// skip accounts while it runs; drain one empty root to observe the reset
					budget.enterRoot();
					budget.exitRoot();
					shape.assertClaims(budget, 0, gate(shape + " skip must leave no claims on " + kind));
				}
			}
		}
	}

	/** @return the size ladder of this file: one rung at DEEP, a second one at NIGHTLY. */
	private static int[] sizes() {
		if (FuzzTiers.tier().compareTo(FuzzTiers.Tier.NIGHTLY) >= 0) {
			return new int[] {MIB + 100, 2 * MIB};
		}
		return new int[] {MIB + 100};
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}

	/** The three byte-hungry wire shapes, with their claim and equality contracts. */
	private enum Shape {
		BINARY {
			@Override byte[] randomPayload(Random rnd, int size) {
				byte[] data = new byte[size];
				rnd.nextBytes(data);
				return data;
			}

			@Override byte[] encode(byte[] payload) {
				return encodeWith(Serializers.BinaryStringSerializerInstance, new BinaryString(payload));
			}

			@Override Object read(BufDataCursor cursor) {
				return Serializers.BinaryStringSerializerInstance.read(cursor);
			}

			@Override void skip(BufDataCursor cursor) {
				Serializers.BinaryStringSerializerInstance.skip(cursor);
			}

			@Override void assertSame(byte[] payload, Object decoded, String what) {
				assertEquals(new BinaryString(payload), decoded, what);
			}

			@Override void assertClaims(DecodeBudget budget, long expected, String what) {
				assertEquals(expected, budget.claimedPayloadBytes(), what + " (payload bytes)");
				assertEquals(0, budget.claimedArrayElements(), what + " (no array elements)");
			}

			@Override DecodeLimits perClaimWall(int units) {
				return new DecodeLimits(Integer.MAX_VALUE, units - 1,
						Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
			}

			@Override DecodeLimits cumulativeWall(int units) {
				return new DecodeLimits(Integer.MAX_VALUE, units,
						Long.MAX_VALUE, 2L * units - 1, Integer.MAX_VALUE);
			}

			@Override String wallMarker() {
				return "per-payload limit";
			}
		},
		BYTES {
			@Override byte[] randomPayload(Random rnd, int size) {
				return BINARY.randomPayload(rnd, size);
			}

			@Override byte[] encode(byte[] payload) {
				return encodeWith(Serializers.ArraybyteSerializerInstance, payload);
			}

			@Override Object read(BufDataCursor cursor) {
				return Serializers.ArraybyteSerializerInstance.read(cursor);
			}

			@Override void skip(BufDataCursor cursor) {
				Serializers.ArraybyteSerializerInstance.skip(cursor);
			}

			@Override void assertSame(byte[] payload, Object decoded, String what) {
				assertTrue(java.util.Arrays.equals(payload, (byte[]) decoded), what);
			}

			@Override void assertClaims(DecodeBudget budget, long expected, String what) {
				// a byte[] array claims elements, not payload bytes
				assertEquals(expected, budget.claimedArrayElements(), what + " (array elements)");
				assertEquals(0, budget.claimedPayloadBytes(), what + " (no payload bytes)");
			}

			@Override DecodeLimits perClaimWall(int units) {
				return new DecodeLimits(units - 1, Integer.MAX_VALUE,
						Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
			}

			@Override DecodeLimits cumulativeWall(int units) {
				return new DecodeLimits(Integer.MAX_VALUE, Integer.MAX_VALUE,
						2L * units - 1, Long.MAX_VALUE, Integer.MAX_VALUE);
			}

			@Override String wallMarker() {
				return "per-array limit";
			}
		},
		TEXT {
			@Override byte[] randomPayload(Random rnd, int size) {
				// valid UTF-8 of exactly `size` bytes: only whole sequences of the pool are written
				char[] pool = {'a', '\u00e9', '\u4e16', '7'};
				byte[][] encoded = new byte[pool.length][];
				for (int i = 0; i < pool.length; i++) {
					encoded[i] = new String(new char[] {pool[i]}).getBytes(StandardCharsets.UTF_8);
				}
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(size);
				int written = 0;
				while (written < size) {
					int pick = rnd.nextInt(pool.length);
					if (written + encoded[pick].length > size) continue; // a 1-byte pick always fits
					out.writeBytes(encoded[pick]);
					written += encoded[pick].length;
				}
				return out.toByteArray();
			}

			@Override byte[] encode(byte[] payload) {
				return encodeWith(Serializers.StringSerializerInstance, decodeUtf8(payload));
			}

			@Override Object read(BufDataCursor cursor) {
				return Serializers.StringSerializerInstance.read(cursor);
			}

			@Override void skip(BufDataCursor cursor) {
				Serializers.StringSerializerInstance.skip(cursor);
			}

			@Override void assertSame(byte[] payload, Object decoded, String what) {
				assertEquals(decodeUtf8(payload), decoded, what);
			}

			@Override void assertClaims(DecodeBudget budget, long expected, String what) {
				assertEquals(expected, budget.claimedPayloadBytes(), what + " (payload bytes)");
				assertEquals(0, budget.claimedArrayElements(), what + " (no array elements)");
			}

			@Override DecodeLimits perClaimWall(int units) {
				return BINARY.perClaimWall(units);
			}

			@Override DecodeLimits cumulativeWall(int units) {
				return BINARY.cumulativeWall(units);
			}

			@Override String wallMarker() {
				return "per-payload limit";
			}
		};

		abstract byte[] randomPayload(Random rnd, int size);

		abstract byte[] encode(byte[] payload);

		abstract Object read(BufDataCursor cursor);

		abstract void skip(BufDataCursor cursor);

		abstract void assertSame(byte[] payload, Object decoded, String what);

		abstract void assertClaims(DecodeBudget budget, long expected, String what);

		/** @return limits whose per-claim wall is exactly one unit below {@code units}. */
		abstract DecodeLimits perClaimWall(int units);

		/** @return limits that fit one claim of {@code units} but refuse a second. */
		abstract DecodeLimits cumulativeWall(int units);

		/** @return the fragment the per-claim refusal message must contain. */
		abstract String wallMarker();

		/** Reads through a cursor on the caller's budget, inside a root of its own. */
		final Object readWithinRoot(FuzzStorages.View view, DecodeBudget budget) {
			budget.enterRoot();
			try {
				BufDataCursor cursor = FuzzStorages.bindCursor(view, budget);
				try {
					return read(cursor);
				} finally {
					cursor.unbind();
				}
			} finally {
				budget.exitRoot();
			}
		}

		/** Decodes payload bytes produced by {@link #randomPayload}: whole sequences only, so exact. */
		static String decodeUtf8(byte[] payload) {
			return new String(payload, StandardCharsets.UTF_8);
		}

		private static <T> byte[] encodeWith(DataCodec<T> codec, T value) {
			BufDataOutput output = BufDataOutput.create();
			codec.serialize(output, value);
			Buf encoded = output.asList();
			return FuzzStorages.bytesOf(
					new FuzzStorages.View(FuzzStorages.Kind.FORWARD_STREAM, encoded, 0, encoded.size()));
		}
	}
}
