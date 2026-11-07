package it.cavallium.datagen.fuzz;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The shared oracle layer of the fuzz campaign: every check a codec must pass, stated once.
 *
 * <p>Each oracle takes the value under test (or an already encoded byte array), reads it through the
 * whole {@link FuzzStorages} matrix and throws an {@link AssertionError} whose message always starts
 * with {@link FuzzTiers#describe()} and always renders the failing input compactly, so a red run
 * states its budget and its data without a debugger.</p>
 *
 * <p>The interesting capability is {@link #corruptAcceptOrStableFail}: a codec may not care whether
 * garbage is <em>readable</em>, but it must never crash on it. Corrupted input is accepted with some
 * value or rejected with exactly one of {@link MalformedDataException},
 * {@link DecodeLimitExceededException}, {@link ValueTooLargeException}; any other runtime exception or
 * {@link Error} — {@code NegativeArraySizeException}, {@code IndexOutOfBoundsException},
 * {@code ClassCastException}, {@code OutOfMemoryError} — is an oracle failure.</p>
 */
public final class FuzzOracles {

	/**
	 * Limits the oracles read with. They are wide enough for every {@link FuzzValues#SIZE_LADDER}
	 * value and narrow enough that a {@code Integer.MAX_VALUE} length header is refused by the bounded
	 * region or the payload limit before a reader can allocate against it.
	 */
	public static final DecodeLimits LIMITS =
			new DecodeLimits(65_536, 4_194_304, 1_048_576L, 67_108_864L, 64);

	/** Beyond the encoding, this many bytes may ever appear behind it: the appended-junk mutant slack. */
	private static final int MAX_TRAILING_JUNK = Integer.BYTES;

	/** Length headers written by the {@link #corruptAcceptOrStableFail} bomb mutation. */
	private static final int[] LENGTH_BOMBS = {0, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};

	/** Longest value {@link #render(Object)} prints before eliding, in characters. */
	private static final int MAX_RENDERED_VALUE = 96;

	/** Longest byte run {@link #renderBytes(byte[])} prints before eliding. */
	private static final int MAX_RENDERED_BYTES = 24;

	private FuzzOracles() {
	}

	/**
	 * Encodes {@code value} and requires every storage family and every read path to decode it back to
	 * an equal value, without consuming anything behind it.
	 *
	 * @param codec the codec under test
	 * @param value a value {@code codec} must accept
	 * @param rnd   drives how many junk bytes are appended behind the encoding, which is what turns
	 *              the over-read check into a real test rather than a fixed case
	 * @param what callsite description, prefixed to every failure message
	 */
	public static <T> void roundTrip(DataCodec<T> codec, T value, Random rnd, String what) {
		byte[] encoded = encode(codec, value);
		try (FuzzStorages storages = new FuzzStorages(encoded)) {
			for (FuzzStorages.View view : storages.all()) {
				valueContract(value, read(codec, view, view.length(), LIMITS), true,
						what + ", storage=" + view.kind() + " (cursor)");
				valueContract(value, readWithReader(codec, view, LIMITS), true,
						what + ", storage=" + view.kind() + " (reader)");
			}
			// a codec must consume exactly its own value, never the bytes that happen to follow it
			int junk = rnd.nextInt(MAX_TRAILING_JUNK + 1);
			byte[] withJunk = Arrays.copyOf(encoded, encoded.length + junk);
			for (int i = encoded.length; i < withJunk.length; i++) {
				withJunk[i] = (byte) rnd.nextInt();
			}
			try (FuzzStorages padded = new FuzzStorages(withJunk)) {
				for (FuzzStorages.View view : padded.all()) {
					int consumed = consumedBy(codec, view, encoded.length, LIMITS);
					if (consumed != encoded.length) {
						throw fail(what, "storage=" + view.kind(), "consumed=" + consumed,
								"expected=" + encoded.length, input(value, withJunk));
					}
				}
			}
		}
	}

	/**
	 * Requires the wire form to be a function of the value alone: encoding is repeatable, and decoding
	 * through any storage family and re-encoding reproduces the same bytes.
	 *
	 * @param codec the codec under test
	 * @param value a value {@code codec} must accept
	 * @param what callsite description, prefixed to every failure message
	 */
	public static <T> void byteDeterminism(DataCodec<T> codec, T value, String what) {
		byte[] encoded = encode(codec, value);
		byte[] again = encode(codec, value);
		if (!Arrays.equals(encoded, again)) {
			throw fail(what, "serializing twice produced different bytes", input(value, encoded),
					"second=" + renderBytes(again));
		}
		try (FuzzStorages storages = new FuzzStorages(encoded)) {
			for (FuzzStorages.View view : storages.all()) {
				T decoded = read(codec, view, view.length(), LIMITS);
				valueContract(value, decoded, true, what + ", storage=" + view.kind());
				byte[] reEncoded = encode(codec, decoded);
				if (!Arrays.equals(encoded, reEncoded)) {
					throw fail(what, "storage=" + view.kind() + " re-encoded to different bytes",
							input(decoded, reEncoded), "expected=" + renderBytes(encoded));
				}
			}
		}
	}

	/**
	 * Requires {@code skip} to consume exactly one value: the position it leaves must equal the
	 * position {@code read} leaves, on a freshly bound cursor as well as on a stream, and the bytes
	 * behind the value must survive untouched.
	 *
	 * @param codec the codec under test
	 * @param value a value {@code codec} must accept
	 * @param what callsite description, prefixed to every failure message
	 */
	public static <T> void skipConsumesOne(DataCodec<T> codec, T value, String what) {
		byte[] encoded = encode(codec, value);
		int sentinel = 0x5E_1F_00_D3;
		byte[] twice = concat(encoded, encoded);
		byte[] withSentinel = concat(encoded, intBytes(sentinel));
		try (FuzzStorages pair = new FuzzStorages(twice);
				FuzzStorages followed = new FuzzStorages(withSentinel)) {
			for (FuzzStorages.View view : pair.all()) {
				// skipping the first copy must land exactly on the start of the second
				T second = readSkippingFirst(codec, view, twice.length);
				valueContract(value, second, true,
						what + ", storage=" + view.kind() + " (value behind a skipped copy)");
			}
			for (FuzzStorages.View view : followed.all()) {
				int afterRead = consumedBy(codec, view, encoded.length, LIMITS);
				if (afterRead != encoded.length) {
					throw fail(what, "storage=" + view.kind(), "read-consumed=" + afterRead,
							"expected=" + encoded.length, input(value, encoded));
				}
				skipThenSentinel(codec, view, encoded, sentinel, value, what);
			}
		}
	}

	/**
	 * Corrupts the encoding and requires every mutant to be accepted or to fail in a stable way.
	 *
	 * @param codec     the codec under test
	 * @param value     a value {@code codec} must accept; its encoding is what gets corrupted
	 * @param rnd       drives the random mutations
	 * @param mutations number of random mutations on top of the systematic truncation ladder
	 * @param what      callsite description, prefixed to every failure message
	 */
	public static <T> void corruptAcceptOrStableFail(DataCodec<T> codec, T value, Random rnd, int mutations,
			String what) {
		corruptAcceptOrStableFail(codec, value, rnd, mutations, new DecodeBudget(LIMITS), what);
	}

	/**
	 * Corrupts the encoding and requires every mutant to be accepted or to fail in a stable way, on a
	 * caller-owned {@link DecodeBudget}.
	 *
	 * <p>Mutants are: every truncation {@code 0..len} (systematically, so no short-input corner is left
	 * to luck), then {@code mutations} random draws from one-bit flip, one hostile-byte substitution,
	 * one length-header bomb ({@code 0}, {@code -1}, {@code Integer.MAX_VALUE},
	 * {@code Integer.MIN_VALUE}, {@code len + 1}) and appended trailing junk. Every mutant is read and
	 * skipped through all six storages.</p>
	 *
	 * <p>Per mutant the oracle additionally asserts that no mutant array is bigger than the encoding
	 * plus {@value #MAX_TRAILING_JUNK} bytes (the bound that keeps a length bomb from allocating), that
	 * a cursor the read failed on is unbound and immediately reusable, that a clean read of the valid
	 * encoding still succeeds afterwards, and that the budget is back at its quiescent state.</p>
	 *
	 * @param codec     the codec under test
	 * @param value     a value {@code codec} must accept
	 * @param rnd       drives the random mutations
	 * @param mutations number of random mutations on top of the systematic truncation ladder
	 * @param budget    the budget every cursor read accounts to
	 * @param what      callsite description, prefixed to every failure message
	 */
	public static <T> void corruptAcceptOrStableFail(DataCodec<T> codec, T value, Random rnd, int mutations,
			DecodeBudget budget, String what) {
		Objects.requireNonNull(budget, "budget");
		byte[] encoded = encode(codec, value);
		int baselineDepth = drainBudget(budget);
		try (FuzzStorages clean = new FuzzStorages(encoded)) {
			for (byte[] mutant : mutants(encoded, rnd, mutations)) {
				if (mutant.length > encoded.length + MAX_TRAILING_JUNK) {
					throw fail(what, "mutant grew beyond the payload plus slack",
							"mutant=" + mutant.length, "payload=" + encoded.length, input(value, mutant));
				}
				try (FuzzStorages storages = new FuzzStorages(mutant)) {
					for (FuzzStorages.View view : storages.all()) {
						FuzzStorages.View cleanView = clean.kind(view.kind());
						corruptOne(codec, value, budget, view, cleanView, encoded.length, mutant, what);
						int quiescent = drainBudget(budget);
						if (quiescent != baselineDepth || budget.claimedArrayElements() != 0L
								|| budget.claimedPayloadBytes() != 0L) {
							throw fail(what, "storage=" + view.kind() + " left the decode budget changed",
									"structuralDepth=" + quiescent, "baselineDepth=" + baselineDepth,
									"claimedArrayElements=" + budget.claimedArrayElements(),
									"claimedPayloadBytes=" + budget.claimedPayloadBytes(),
									input(value, mutant));
								}
					}
				}
			}
		}
	}

	/**
	 * Requires every storage family to decode {@code encoded} to the same value, by cursor and by
	 * {@link DataCodec.Reader} alike.
	 *
	 * @param codec          the codec under test
	 * @param encoded        a valid encoding produced by {@code codec}
	 * @param expectedOrNull the value every storage must produce, or {@code null} to only compare the
	 *                       storages against each other
	 * @param what           callsite description, prefixed to every failure message
	 */
	public static <T> void storageParity(DataCodec<T> codec, byte[] encoded, T expectedOrNull, String what) {
		try (FuzzStorages storages = new FuzzStorages(encoded)) {
			T reference = null;
			FuzzStorages.Kind referenceKind = null;
			for (FuzzStorages.View view : storages.all()) {
				T decoded = read(codec, view, view.length(), LIMITS);
				valueContract(decoded, readWithReader(codec, view, LIMITS), true,
						what + ", storage=" + view.kind() + " (cursor-vs-reader)");
				if (expectedOrNull != null) {
					valueContract(expectedOrNull, decoded, true, what + ", storage=" + view.kind());
				}
				if (reference == null) {
					reference = decoded;
					referenceKind = view.kind();
				} else {
					valueContract(reference, decoded, true,
							what + ", storage=" + referenceKind + "-vs-" + view.kind());
				}
			}
		}
	}

	/**
	 * The value-level contract a decoded graph must satisfy: equality is reflexive, symmetric and
	 * transitive over {@code {a, b}}, agrees with deep array content, matches {@code hashCode}, is
	 * {@code false} against {@code null}, and both values render a non-empty {@code toString}.
	 *
	 * @param a           the left value; {@code null} is allowed and only equal to {@code null}
	 * @param b           the right value
	 * @param expectEqual whether the two must be equal
	 * @param what        callsite description, prefixed to every failure message
	 */
	public static void valueContract(Object a, Object b, boolean expectEqual, String what) {
		boolean contentEqual = valueEquals(a, b);
		if (contentEqual != expectEqual) {
			throw fail(what, "expectedEqual=" + expectEqual + " but deep content equality is " + contentEqual,
					"a=" + render(a), "b=" + render(b));
		}
		if (!valueEquals(a, a) || !valueEquals(b, b)) {
			throw fail(what, "equality is not reflexive", "a=" + render(a), "b=" + render(b));
		}
		if (a != null && a.equals(null)) {
			throw fail(what, "equals(null) must be false", "a=" + render(a));
		}
		if (a == null || b == null) {
			if ((a == null) != (b == null)) {
				throw fail(what, "exactly one side is null, so the expectation is wrong",
						"a=" + render(a), "b=" + render(b), "expectedEqual=" + expectEqual);
			}
			return;
		}
		// arrays are compared the way the generated value classes compare them: by deep content, so
		// their inherited identity equals() and hashCode() are not part of the contract
		boolean arrayShaped = a.getClass().isArray() || b.getClass().isArray();
		if (arrayShaped) {
			if (expectEqual && a.getClass() != b.getClass()) {
				throw fail(what, "equal array values must share a component type",
						"a=" + a.getClass(), "b=" + b.getClass());
			}
			if (expectEqual && deepHash(a) != deepHash(b)) {
				throw fail(what, "equal array values must share a deep hash code",
						"deepHash(a)=" + deepHash(a), "deepHash(b)=" + deepHash(b),
						"a=" + render(a), "b=" + render(b));
			}
		} else {
			if (a.equals(b) != expectEqual || b.equals(a) != expectEqual) {
				throw fail(what, "equals must be symmetric and agree with the expectation",
						"a.equals(b)=" + a.equals(b), "b.equals(a)=" + b.equals(a),
						"expectedEqual=" + expectEqual, "a=" + render(a), "b=" + render(b));
			}
			if (expectEqual && a.hashCode() != b.hashCode()) {
				throw fail(what, "equal values must share a hash code",
						"a.hashCode()=" + a.hashCode(), "b.hashCode()=" + b.hashCode(),
						"a=" + render(a), "b=" + render(b));
			}
			Object[] family = {a, b, a};
			for (Object left : family) {
				for (Object middle : family) {
					for (Object right : family) {
						if (left.equals(middle) && middle.equals(right) && !left.equals(right)) {
							throw fail(what, "equals is not transitive",
									"left=" + render(left), "middle=" + render(middle), "right=" + render(right));
						}
					}
				}
			}
		}
		String leftText = a.toString();
		String rightText = b.toString();
		if (leftText == null || rightText == null) {
			throw fail(what, "toString must not return null", "a=" + render(a), "b=" + render(b));
		}
		if ((leftText.isEmpty() && !mayRenderEmpty(a)) || (rightText.isEmpty() && !mayRenderEmpty(b))) {
			throw fail(what, "toString must render a non-empty description",
					"a.toString()=" + render(leftText), "b.toString()=" + render(rightText));
		}
		if (expectEqual && !arrayShaped && !leftText.equals(rightText)) {
			throw fail(what, "equal values must render the same text",
					"a.toString()=" + render(leftText), "b.toString()=" + render(rightText));
		}
	}

	/**
	 * Requires a read path to succeed under {@code within}, to be refused under {@code exceeded}, and
	 * to work again under {@code within} afterwards — limits must gate decoding without damaging the
	 * reader.
	 *
	 * @param what          callsite description, prefixed to every failure message
	 * @param readPath      performs one decode with the limits it is handed and asserts the result
	 *                      itself, since a {@link Consumer} cannot return it
	 * @param within        limits the value fits
	 * @param exceeded      limits at least one of whose dimensions the value exceeds
	 * @param expectedValue the value the read path must produce, rendered into failure messages
	 */
	public static void decodeLimits(String what, Consumer<DecodeLimits> readPath, DecodeLimits within,
			DecodeLimits exceeded, Object expectedValue) {
		Objects.requireNonNull(readPath, "readPath");
		try {
			readPath.accept(within);
		} catch (RuntimeException | Error failure) {
			throw fail(what, "the value must decode within its limits", "limits=" + within,
					"expected=" + render(expectedValue), cause(failure));
		}
		try {
			readPath.accept(exceeded);
		} catch (DecodeLimitExceededException expected) {
			try {
				readPath.accept(within);
			} catch (RuntimeException | Error failure) {
				throw fail(what, "a refused decode must leave the read path usable", "limits=" + within,
						"expected=" + render(expectedValue), cause(failure));
			}
			return;
		} catch (RuntimeException | Error failure) {
			throw fail(what, "exceeding a limit must throw DecodeLimitExceededException",
					"limits=" + exceeded, "expected=" + render(expectedValue), cause(failure));
		}
		throw fail(what, "exceeding a limit was accepted", "limits=" + exceeded,
				"expected=" + render(expectedValue));
	}

	/**
	 * Runs one mutant through one storage: read, skip, cursor reusability, clean recovery.
	 */
	private static <T> void corruptOne(DataCodec<T> codec, T value, DecodeBudget budget,
			FuzzStorages.View view, FuzzStorages.View cleanView, int cleanLength, byte[] mutant, String what) {
		Throwable unstable = unstableOf(codec, view, mutant, true, budget);
		if (unstable != null) {
			throw fail(what, "storage=" + view.kind() + " read threw an unstable failure",
					input(value, mutant), cause(unstable));
		}
		unstable = unstableOf(codec, view, mutant, false, budget);
		if (unstable != null) {
			throw fail(what, "storage=" + view.kind() + " skip threw an unstable failure",
					input(value, mutant), cause(unstable));
		}
		if (view.forwardOnly()) {
			return;
		}
		// a read that failed must not wedge the cursor: the same instance has to bind and decode again
		BufDataCursor cursor = new BufDataCursor(budget);
		try {
			if (cursor.isBound()) {
				throw fail(what, "a fresh cursor must start unbound", "storage=" + view.kind());
			}
			cursor.bind(cleanView.buf(), cleanView.offset(), cleanLength);
			T recovered = codec.read(cursor);
			valueContract(value, recovered, true,
					what + ", storage=" + view.kind() + " (clean read after a mutant)");
		} catch (MalformedDataException | DecodeLimitExceededException | ValueTooLargeException stable) {
			throw fail(what, "storage=" + view.kind() + " lost the valid encoding after a mutant",
					input(value, mutant), cause(stable));
		} finally {
			cursor.unbind();
		}
		if (cursor.isBound()) {
			throw fail(what, "unbind left the cursor bound", "storage=" + view.kind(), input(value, mutant));
		}
	}

	/** @return the failure that is <em>not</em> a stable rejection, or {@code null} when the outcome is fine */
	private static <T> Throwable unstableOf(DataCodec<T> codec, FuzzStorages.View view, byte[] mutant,
			boolean asRead, DecodeBudget budget) {
		String operation = asRead ? "read" : "skip";
		if (view.forwardOnly()) {
			try (SafeDataInputStream in = new SafeDataInputStream(
					new SafeByteArrayInputStream(mutant), budget.limits())) {
				if (asRead) {
					codec.read(in);
				} else {
					codec.skip(in);
				}
				return null;
			} catch (MalformedDataException | DecodeLimitExceededException | ValueTooLargeException stable) {
				return null;
			} catch (RuntimeException | Error unstable) {
				return describeUnstable(operation, view, unstable);
			}
		}
		BufDataCursor cursor = new BufDataCursor(budget);
		try {
			cursor.bind(view.buf(), view.offset(), mutant.length);
			if (asRead) {
				codec.read(cursor);
			} else {
				codec.skip(cursor);
			}
			return null;
		} catch (MalformedDataException | DecodeLimitExceededException | ValueTooLargeException stable) {
			return null;
		} catch (RuntimeException | Error unstable) {
			return describeUnstable(operation, view, unstable);
		} finally {
			cursor.unbind();
		}
	}

	/**
	 * Keeps the original throwable but records which operation and storage produced it, so the failure
	 * message can name both. A {@link RuntimeException} wrapper would lose nothing else.
	 */
	private static Throwable describeUnstable(String operation, FuzzStorages.View view, Throwable unstable) {
		unstable.addSuppressed(new IllegalStateException(operation + " on " + view.kind()));
		return unstable;
	}

	/**
	 * Brings the budget back to its quiescent state and reports the depth it must keep.
	 *
	 * <p>The counters are per root and {@code DecodeBudget} exposes no {@code rootEntries()} accessor,
	 * so the drain probe is what proves the codec released every root it entered:
	 * {@link DecodeBudget#enterRoot()} resets both claim counters <em>only</em> while no root is
	 * active, so a leaked root leaves the mutant's accounting visible to the caller.</p>
	 *
	 * @return {@link DecodeBudget#structuralDepth()} after draining
	 */
	private static int drainBudget(DecodeBudget budget) {
		budget.enterRoot();
		budget.exitRoot();
		return budget.structuralDepth();
	}

	/** @return the systematic truncation ladder plus {@code mutations} random mutations of {@code encoded} */
	private static List<byte[]> mutants(byte[] encoded, Random rnd, int mutations) {
		List<byte[]> result = new ArrayList<>(encoded.length + 1 + Math.max(0, mutations));
		for (int cut = 0; cut <= encoded.length; cut++) {
			result.add(Arrays.copyOf(encoded, cut));
		}
		for (int i = 0; i < Math.max(0, mutations); i++) {
			switch (rnd.nextInt(4)) {
				case 0 -> {
					byte[] mutant = encoded.clone();
					if (mutant.length > 0) {
						mutant[rnd.nextInt(mutant.length)] ^= (byte) (1 << rnd.nextInt(8));
					}
					result.add(mutant);
				}
				case 1 -> {
					byte[] mutant = encoded.clone();
					if (mutant.length > 0) {
						mutant[rnd.nextInt(mutant.length)] =
								(byte) FuzzValues.HOSTILE_BYTES[rnd.nextInt(FuzzValues.HOSTILE_BYTES.length)];
					}
					result.add(mutant);
				}
				case 2 -> {
					// overwrite the length header; a shorter encoding is grown into the slack, never truncated
					byte[] mutant = Arrays.copyOf(encoded, Math.max(encoded.length, Integer.BYTES));
					int bomb = rnd.nextInt(LENGTH_BOMBS.length + 1) == LENGTH_BOMBS.length
							? encoded.length + 1
							: LENGTH_BOMBS[rnd.nextInt(LENGTH_BOMBS.length)];
					putInt(mutant, 0, bomb);
					result.add(mutant);
				}
				default -> {
					byte[] mutant = Arrays.copyOf(encoded, encoded.length + 1 + rnd.nextInt(MAX_TRAILING_JUNK));
					for (int j = encoded.length; j < mutant.length; j++) {
						mutant[j] = (byte) FuzzValues.HOSTILE_BYTES[rnd.nextInt(FuzzValues.HOSTILE_BYTES.length)];
					}
					result.add(mutant);
				}
			}
		}
		return result;
	}

	/** @return the exact wire bytes {@code codec} produces for {@code value} */
	private static <T> byte[] encode(DataCodec<T> codec, T value) {
		Objects.requireNonNull(codec, "codec");
		BufDataOutput output = BufDataOutput.create();
		codec.serialize(output, value);
		return bytesOf(output.asList(), 0, output.asList().size());
	}

	/** Reads a region of {@code view} through a cursor or a forward stream, whichever the kind allows. */
	private static <T> T read(DataCodec<T> codec, FuzzStorages.View view, int length, DecodeLimits limits) {
		return decoding(view, length, "read", () -> {
			if (view.forwardOnly()) {
				try (SafeDataInputStream in = stream(view, length, limits)) {
					return codec.read(in);
				}
			}
			BufDataCursor cursor = new BufDataCursor(limits);
			cursor.bind(view.buf(), view.offset(), length);
			try {
				return codec.read(cursor);
			} finally {
				cursor.unbind();
			}
		});
	}

	/** Reads a region through the codec's own reusable {@link DataCodec.Reader}. */
	private static <T> T readWithReader(DataCodec<T> codec, FuzzStorages.View view, DecodeLimits limits) {
		return decoding(view, view.length(), "reader",
				() -> codec.newReader(limits).read(view.buf(), view.offset(), view.length()));
	}

	/**
	 * A codec that refuses or mangles its own valid encoding is an oracle failure, so anything it throws
	 * here — except an {@link AssertionError}, which already is one — becomes a reportable one.
	 */
	private static <T> T decoding(FuzzStorages.View view, int length, String path, Supplier<T> read) {
		try {
			return read.get();
		} catch (AssertionError alreadyAnOracleFailure) {
			throw alreadyAnOracleFailure;
		} catch (RuntimeException | Error failure) {
			throw fail("decode through " + view.kind() + " (" + path + ")",
					"length=" + length, "bytes=" + renderBytes(FuzzStorages.bytesOf(view)), cause(failure));
		}
	}

	/** @return how many of the bound bytes the codec consumed while reading {@code view} */
	private static <T> int consumedBy(DataCodec<T> codec, FuzzStorages.View view, int length,
			DecodeLimits limits) {
		if (view.forwardOnly()) {
			return decoding(view, length, "stream", () -> {
				try (SafeDataInputStream in = stream(view, length, limits)) {
					codec.read(in);
					long remaining = in.remainingBytesIfKnown();
					if (remaining < 0) {
						throw fail("forward stream", "must report its remaining bytes", "length=" + length);
					}
					return length - (int) remaining;
				}
			});
		}
		return decoding(view, length, "cursor", () -> {
			BufDataCursor cursor = new BufDataCursor(limits);
			cursor.bind(view.buf(), view.offset(), length);
			try {
				codec.read(cursor);
				return cursor.position();
			} finally {
				cursor.unbind();
			}
		});
	}

	/**
	 * Skips the encoding on {@code view} and requires the bytes behind it to still be readable, which
	 * is the skip-side half of "consumes exactly one value".
	 */
	private static <T> void skipThenSentinel(DataCodec<T> codec, FuzzStorages.View view, byte[] encoded,
			int sentinel, T value, String what) {
		decoding(view, encoded.length + Integer.BYTES, "skip-then-sentinel", () -> {
			if (view.forwardOnly()) {
				try (SafeDataInputStream in = stream(view, encoded.length + Integer.BYTES, LIMITS)) {
					codec.skip(in);
					if (in.readInt() != sentinel) {
						throw fail(what, "storage=" + view.kind() + " skip ate the bytes behind the value",
								input(value, encoded));
					}
				}
				return null;
			}
			BufDataCursor cursor = new BufDataCursor(LIMITS);
			cursor.bind(view.buf(), view.offset(), encoded.length + Integer.BYTES);
			try {
				codec.skip(cursor);
				if (cursor.readInt() != sentinel) {
					throw fail(what, "storage=" + view.kind() + " skip ate the bytes behind the value",
							input(value, encoded));
				}
			} finally {
				cursor.unbind();
			}
			return null;
		});
	}

	/**
	 * Skips the first of two back-to-back encodings and reads the second one: a codec that skips too
	 * few or too many bytes cannot return the second copy unchanged.
	 */
	private static <T> T readSkippingFirst(DataCodec<T> codec, FuzzStorages.View view, int length) {
		return decoding(view, length, "skip-then-read", () -> {
			if (view.forwardOnly()) {
				try (SafeDataInputStream in = stream(view, length, LIMITS)) {
					codec.skip(in);
					return codec.read(in);
				}
			}
			BufDataCursor cursor = new BufDataCursor(LIMITS);
			cursor.bind(view.buf(), view.offset(), length);
			try {
				codec.skip(cursor);
				return codec.read(cursor);
			} finally {
				cursor.unbind();
			}
		});
	}

	/** @return a forward-only input over the first {@code length} bytes of {@code view} */
	private static SafeDataInputStream stream(FuzzStorages.View view, int length, DecodeLimits limits) {
		Objects.checkFromIndexSize(0, length, view.length());
		byte[] bytes = FuzzStorages.bytesOf(view);
		return new SafeDataInputStream(
				new SafeByteArrayInputStream(length == bytes.length ? bytes : Arrays.copyOf(bytes, length)),
				limits);
	}

	/** @return the requested range of {@code buf} as a fresh array, read through random access */
	private static byte[] bytesOf(Buf buf, int offset, int length) {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			bytes[i] = buf.getByte(offset + i);
		}
		return bytes;
	}

	private static byte[] concat(byte[] prefix, byte[] suffix) {
		byte[] result = Arrays.copyOf(prefix, prefix.length + suffix.length);
		System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
		return result;
	}

	private static byte[] intBytes(int value) {
		return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
	}

	private static void putInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 24);
		bytes[offset + 1] = (byte) (value >>> 16);
		bytes[offset + 2] = (byte) (value >>> 8);
		bytes[offset + 3] = (byte) value;
	}

	/**
	 * Deep-content equality, matching the array-aware {@code equals} the generated value classes use:
	 * primitive arrays compare by value, object arrays element-wise, everything else by {@code equals}.
	 */
	private static boolean valueEquals(Object a, Object b) {
		if (a instanceof float[] left && b instanceof float[] right) {
			return Arrays.equals(left, right);
		}
		if (a instanceof double[] left && b instanceof double[] right) {
			return Arrays.equals(left, right);
		}
		return Objects.deepEquals(a, b);
	}

	/** Deep-content {@code hashCode}, matching {@link #valueEquals}: the generated value classes use it. */
	private static int deepHash(Object value) {
		if (value instanceof Object[] array) {
			return Arrays.deepHashCode(array);
		}
		if (value instanceof byte[] array) {
			return Arrays.hashCode(array);
		}
		if (value instanceof boolean[] array) {
			return Arrays.hashCode(array);
		}
		if (value instanceof short[] array) {
			return Arrays.hashCode(array);
		}
		if (value instanceof char[] array) {
			return Arrays.hashCode(array);
		}
		if (value instanceof int[] array) {
			return Arrays.hashCode(array);
		}
		if (value instanceof long[] array) {
			return Arrays.hashCode(array);
		}
		if (value instanceof float[] array) {
			return Arrays.hashCode(array);
		}
		if (value instanceof double[] array) {
			return Arrays.hashCode(array);
		}
		return value.hashCode();
	}

	/**
	 * A value that legitimately renders as the empty text: an empty string or an empty array. Every
	 * other value has to say something about itself.
	 */
	private static boolean mayRenderEmpty(Object value) {
		if (value instanceof String text) {
			return text.isEmpty();
		}
		return value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0;
	}

	/** @return the failure the oracles throw: the active gate first, then the input that broke it */
	private static AssertionError fail(String what, Object... facts) {
		StringBuilder message = new StringBuilder(FuzzTiers.describe()).append(" | ").append(what);
		for (Object fact : facts) {
			message.append(' ').append(fact);
		}
		return new AssertionError(message);
	}

	/** @return the failing input in the shape every oracle reports: the value and its wire bytes */
	private static String input(Object value, byte[] encoded) {
		return "value=" + render(value) + " bytes=" + renderBytes(encoded);
	}

	/** @return {@code type: message} for a failure the oracle refuses to accept */
	private static String cause(Throwable failure) {
		return "threw=" + failure.getClass().getName() + ": " + render(failure.getMessage());
	}

	/** @return a quoted, escaped, length-capped rendering of {@code value} */
	private static String render(Object value) {
		if (value == null) {
			return "null";
		}
		String text = value instanceof String string
				? "\"" + escape(string) + "\""
				: value instanceof byte[] bytes ? renderBytes(bytes) : String.valueOf(value);
		if (text.length() <= MAX_RENDERED_VALUE) {
			return text;
		}
		return text.substring(0, MAX_RENDERED_VALUE) + "…(" + text.length() + " chars)";
	}

	/** @return {@code len=N [hex…]} capped at {@value #MAX_RENDERED_BYTES} bytes */
	private static String renderBytes(byte[] bytes) {
		if (bytes == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder("len=").append(bytes.length).append(" [");
		int shown = Math.min(bytes.length, MAX_RENDERED_BYTES);
		for (int i = 0; i < shown; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(Character.forDigit((bytes[i] >>> 4) & 0xF, 16))
					.append(Character.forDigit(bytes[i] & 0xF, 16));
		}
		if (shown < bytes.length) {
			sb.append('…');
		}
		return sb.append(']').toString();
	}

	/** Keeps control characters and unpaired surrogates visible inside a rendered string. */
	private static String escape(String text) {
		StringBuilder sb = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\').append(c);
			} else if (c >= 0x20 && c < 0x7F) {
				sb.append(c);
			} else {
				sb.append(String.format("\\u%04x", (int) c));
			}
		}
		return sb.toString();
	}
}
