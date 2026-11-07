package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.nativedata.Serializers;
import it.cavallium.datagen.nativedata.StringSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Proves the oracle layer itself: every {@link FuzzOracles} check passes on a real registry codec and
 * each one <em>fails</em> when its own contract is broken by a deliberately wrong test double.
 *
 * <p>A green oracle that never reports a violation is worthless, so each oracle gets both halves
 * here: {@code Serializers.StringSerializerInstance} as the known-good codec, and a mis-skipping, a
 * storage-dependent, an exploding-on-truncation or a root-leaking double as the counterexample. The
 * expected shape of every report is checked too: an {@link AssertionError} whose message carries
 * {@link FuzzTiers#describe()} and the failing input.</p>
 */
class FuzzOraclesTest {

	/** The real registry codec the oracles must be satisfied by. */
	private static final DataCodec<String> GOOD = Serializers.StringSerializerInstance;

	private static final String NON_EMPTY = "oracle \u00e9 \ud83d\ude00";

	@Test
	void roundTripPassesForTheRealStringCodec() {
		Random rnd = FuzzTiers.random("oracle-round-trip");
		for (String value : values(rnd, "oracle-round-trip")) {
			FuzzOracles.roundTrip(GOOD, value, rnd, "string round trip " + value.length());
		}
	}

	@Test
	void byteDeterminismPassesForTheRealStringCodec() {
		Random rnd = FuzzTiers.random("oracle-bytes");
		for (String value : values(rnd, "oracle-bytes")) {
			FuzzOracles.byteDeterminism(GOOD, value, "string bytes " + value.length());
		}
	}

	@Test
	void skipConsumesOnePassesForTheRealStringCodec() {
		Random rnd = FuzzTiers.random("oracle-skip");
		for (String value : values(rnd, "oracle-skip")) {
			FuzzOracles.skipConsumesOne(GOOD, value, "string skip " + value.length());
		}
	}

	@Test
	void corruptAcceptOrStableFailPassesForTheRealStringCodec() {
		Random rnd = FuzzTiers.random("oracle-corrupt");
		int mutations = FuzzTiers.cases("core-support", 4);
		for (String value : values(rnd, "oracle-corrupt")) {
			FuzzOracles.corruptAcceptOrStableFail(GOOD, value, rnd, mutations, "string corruption");
			FuzzOracles.corruptAcceptOrStableFail(GOOD, value, rnd, mutations,
					new DecodeBudget(FuzzOracles.LIMITS), "string corruption on a shared budget");
		}
	}

	@Test
	void storageParityPassesForTheRealStringCodec() {
		Random rnd = FuzzTiers.random("oracle-parity");
		for (String value : values(rnd, "oracle-parity")) {
			FuzzOracles.storageParity(GOOD, encode(value), value, "string parity");
			// with no reference value the storages still have to agree with each other
			FuzzOracles.storageParity(GOOD, encode(value), null, "string parity without expectation");
		}
	}

	@Test
	void valueContractAcceptsConsistentValues() {
		FuzzOracles.valueContract(NON_EMPTY, NON_EMPTY, true, "identical strings");
		FuzzOracles.valueContract("a", "b", false, "different strings");
		FuzzOracles.valueContract(null, null, true, "both null");
		// deep-array semantics: two distinct arrays with the same content are equal values
		FuzzOracles.valueContract(new byte[] {1, 2, 3}, new byte[] {1, 2, 3}, true, "equal array content");
		FuzzOracles.valueContract(new byte[] {1, 2, 3}, new byte[] {1, 2, 4}, false, "different array content");
		FuzzOracles.valueContract(new int[][] {{7}}, new int[][] {{7}}, true, "equal nested arrays");
	}

	@Test
	void decodeLimitsGatesARealDecode() {
		byte[] encoded = encode(NON_EMPTY);
		DecodeLimits within = FuzzOracles.LIMITS;
		DecodeLimits noPayloadRoom = new DecodeLimits(within.maximumElementsPerArray(), 0,
				within.maximumCumulativeArrayElements(), within.maximumCumulativePayloadBytes(),
				within.maximumStructuralNestingDepth());
		Consumer<DecodeLimits> readPath = limits -> {
			BufDataCursor cursor = new BufDataCursor(limits);
			cursor.bind(Buf.wrap(encoded), 0, encoded.length);
			try {
				FuzzOracles.valueContract(NON_EMPTY, StringSerializer.INSTANCE.read(cursor), true,
						"read with " + limits);
			} finally {
				cursor.unbind();
			}
		};
		assertDoesNotThrow(() -> FuzzOracles.decodeLimits("string limits", readPath, within, noPayloadRoom,
				NON_EMPTY));
	}

	@Test
	void roundTripFailsWhenTheReadDependsOnTheStorage() {
		DataCodec<String> storageDependent = new StorageDependent(GOOD);
		for (String value : values(FuzzTiers.random("oracle-bad-round-trip"), "oracle-bad-round-trip")) {
			AssertionError failure = assertThrows(AssertionError.class,
					() -> FuzzOracles.roundTrip(storageDependent, value,
							FuzzTiers.random("oracle-bad-round-trip"), "storage dependent read"),
					value);
			assertReported(failure);
		}
	}

	@Test
	void byteDeterminismFailsWhenTheReadDependsOnTheStorage() {
		AssertionError failure = assertThrows(AssertionError.class,
				() -> FuzzOracles.byteDeterminism(new StorageDependent(GOOD), NON_EMPTY,
						"storage dependent re-encode"));
		assertReported(failure);
	}

	@Test
	void storageParityFailsWhenTheReadDependsOnTheStorage() {
		AssertionError failure = assertThrows(AssertionError.class,
				() -> FuzzOracles.storageParity(new StorageDependent(GOOD), encode(NON_EMPTY), NON_EMPTY,
						"storage dependent parity"));
		assertReported(failure);
	}

	@Test
	void skipConsumesOneFailsWhenSkipSkipsTooFar() {
		AssertionError failure = assertThrows(AssertionError.class,
				() -> FuzzOracles.skipConsumesOne(new MisSkipping(GOOD), NON_EMPTY, "skip overruns"));
		assertReported(failure);
	}

	@Test
	void corruptAcceptOrStableFailFailsWhenTruncationThrowsAnUnstableFailure() {
		Random rnd = FuzzTiers.random("oracle-exploding");
		AssertionError failure = assertThrows(AssertionError.class,
				() -> FuzzOracles.corruptAcceptOrStableFail(new ExplodingTruncation(GOOD), NON_EMPTY, rnd,
						FuzzTiers.cases("core-support", 4), "truncation throws IllegalArgumentException"));
		assertReported(failure);
		assertTrue(failure.getMessage().contains("IllegalArgumentException"), failure.getMessage());
	}

	@Test
	void corruptAcceptOrStableFailFailsWhenTheCodecLeaksABudgetRoot() {
		DecodeBudget budget = new DecodeBudget(FuzzOracles.LIMITS);
		AssertionError failure = assertThrows(AssertionError.class,
				() -> FuzzOracles.corruptAcceptOrStableFail(new LeakingRoot(GOOD), NON_EMPTY,
						FuzzTiers.random("oracle-leaking"), 2, budget, "budget root leak"));
		assertReported(failure);
		assertTrue(failure.getMessage().contains("decode budget"), failure.getMessage());
	}

	@Test
	void valueContractFailsOnBrokenEqualityHashCodeAndToString() {
		AssertionError wrong = assertThrows(AssertionError.class,
				() -> FuzzOracles.valueContract("a", "b", true, "unequal strings claimed equal"));
		assertReported(wrong);
		AssertionError arrayBlindness = assertThrows(AssertionError.class,
				() -> FuzzOracles.valueContract(new byte[] {1, 2}, new byte[] {1, 2}, false,
						"arrays with equal content claimed unequal"));
		assertReported(arrayBlindness);
		AssertionError hashMismatch = assertThrows(AssertionError.class,
				() -> FuzzOracles.valueContract(new HashBlind(1), new HashBlind(2), true, "hash code"));
		assertReported(hashMismatch);
		assertTrue(hashMismatch.getMessage().contains("hash code"), hashMismatch.getMessage());
		AssertionError blank = assertThrows(AssertionError.class,
				() -> FuzzOracles.valueContract(BLANK, BLANK, true, "empty toString"));
		assertReported(blank);
		assertTrue(blank.getMessage().contains("toString"), blank.getMessage());
	}

	@Test
	void decodeLimitsFailsWhenTheExceededLimitIsAccepted() {
		byte[] encoded = encode(NON_EMPTY);
		Consumer<DecodeLimits> readPath = limits -> {
			BufDataCursor cursor = new BufDataCursor(limits);
			cursor.bind(Buf.wrap(encoded), 0, encoded.length);
			try {
				cursor.readString(encoded.length - Integer.BYTES, StandardCharsets.UTF_8);
			} finally {
				cursor.unbind();
			}
		};
		// "exceeded" that is really the within-limits: nothing is refused, so the oracle must say so
		AssertionError failure = assertThrows(AssertionError.class,
				() -> FuzzOracles.decodeLimits("limits without a wall", readPath, FuzzOracles.LIMITS,
						FuzzOracles.LIMITS, NON_EMPTY));
		assertReported(failure);
		assertTrue(failure.getMessage().contains("exceeding a limit was accepted"), failure.getMessage());
	}

	/** An {@code Object} that is equal to every other one but hashes differently. */
	private static final class HashBlind {
		private final int hash;

		HashBlind(int hash) {
			this.hash = hash;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof HashBlind;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public String toString() {
			return "HashBlind";
		}
	}

	/** A value whose {@code toString} renders nothing. */
	private static final Object BLANK = new Object() {
		@Override
		public String toString() {
			return "";
		}
	};

	/** Reads a different value depending on which input class the codec was handed. */
	private record StorageDependent(DataCodec<String> delegate) implements DataCodec<String> {

		@Override
		public void serialize(SafeDataOutput dataOutput, String data) {
			delegate.serialize(dataOutput, data);
		}

		@Override
		public String read(SafeDataInput dataInput) {
			return delegate.read(dataInput)
					+ (dataInput instanceof RandomAccessDataInput ? "/random-access" : "/forward");
		}

		@Override
		public void skip(SafeDataInput dataInput) {
			delegate.skip(dataInput);
		}
	}

	/** Reads correctly but skips one byte too many, so the next value is no longer aligned. */
	private record MisSkipping(DataCodec<String> delegate) implements DataCodec<String> {

		@Override
		public void serialize(SafeDataOutput dataOutput, String data) {
			delegate.serialize(dataOutput, data);
		}

		@Override
		public String read(SafeDataInput dataInput) {
			return delegate.read(dataInput);
		}

		@Override
		public void skip(SafeDataInput dataInput) {
			int length = dataInput.readInt();
			dataInput.skipBytes(Math.addExact(length, 1));
		}
	}

	/** Turns a stable truncation rejection into a bare, unstable {@link IllegalArgumentException}. */
	private record ExplodingTruncation(DataCodec<String> delegate) implements DataCodec<String> {

		@Override
		public void serialize(SafeDataOutput dataOutput, String data) {
			delegate.serialize(dataOutput, data);
		}

		@Override
		public String read(SafeDataInput dataInput) {
			try {
				return delegate.read(dataInput);
			} catch (MalformedDataException truncated) {
				throw new IllegalArgumentException("boom: " + truncated.getMessage(), truncated);
			}
		}

		@Override
		public void skip(SafeDataInput dataInput) {
			delegate.skip(dataInput);
		}
	}

	/** Enters a decode root and forgets to leave it, so the budget never returns to rest. */
	private record LeakingRoot(DataCodec<String> delegate) implements DataCodec<String> {

		@Override
		public void serialize(SafeDataOutput dataOutput, String data) {
			delegate.serialize(dataOutput, data);
		}

		@Override
		public String read(SafeDataInput dataInput) {
			dataInput.decodeBudget().enterRoot();
			return delegate.read(dataInput);
		}

		@Override
		public void skip(SafeDataInput dataInput) {
			delegate.skip(dataInput);
		}
	}

	/** @return the wire form of {@code value} under the oracle limits */
	private static byte[] encode(String value) {
		BufDataOutput output = BufDataOutput.create();
		GOOD.serialize(output, value);
		Buf list = output.asList();
		byte[] bytes = new byte[list.size()];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = list.getByte(i);
		}
		return bytes;
	}

	/** @return the tier-scaled value set: the empty string, an edge string and drawn hostile strings */
	private static List<String> values(Random rnd, String salt) {
		List<String> values = new ArrayList<>();
		values.add("");
		values.add(NON_EMPTY);
		values.add("\u0000");
		// StringSerializer encodes UTF-8, so an unpaired surrogate legally becomes U+FFFD on the way
		// back: the drawn strings keep every other hostile shape but are repaired before being compared
		values.add("\u0000\u001f\b\u007f");
		int cases = FuzzTiers.cases("core-support", 8);
		for (int i = 0; i < cases; i++) {
			values.add(surrogateSafe(FuzzValues.string(rnd)));
		}
		// exact count: 4 literals + max(0, cases) drawn ones, so a literal cases override of 0 or a
		// negative value (honored as written by FuzzTiers) is reflected here instead of tripping the gate.
		assertEquals(4 + Math.max(0, cases), values.size(), () -> salt + " -> " + values.size() + " values");
		return values;
	}

	/** @return {@code text} with each unpaired surrogate replaced by {@code U+FFFD} */
	private static String surrogateSafe(String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
					sb.append(c).append(text.charAt(++i));
				} else {
					sb.append('\uFFFD');
				}
			} else {
				sb.append(Character.isLowSurrogate(c) ? '\uFFFD' : c);
			}
		}
		return sb.toString();
	}

	/** Every oracle report has to state the gate it ran under and the input that broke it. */
	private static void assertReported(AssertionError failure) {
		String message = failure.getMessage();
		assertTrue(message != null && message.contains(FuzzTiers.describe().split(" ")[0]),
				"message must carry the gate: " + message);
		assertTrue(message != null && message.contains("tier="), "message must carry the tier: " + message);
		assertTrue(message != null && !message.isBlank(), "message must not be blank");
	}
}
