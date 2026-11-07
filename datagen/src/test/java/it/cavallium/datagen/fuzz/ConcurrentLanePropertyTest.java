package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimitExceededException;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.MalformedDataException;
import it.cavallium.datagen.ReadSession;
import it.cavallium.datagen.ValueTooLargeException;
import it.cavallium.datagen.nativedata.BinaryString;
import it.cavallium.datagen.nativedata.Serializers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Property;

/**
 * Thread-CONFINED concurrency stress for the three reusable decode objects a reader lane owns:
 * {@link DataCodec.Reader}, {@link ReadSession} and {@link BufDataCursor}.
 *
 * <p><b>These APIs are thread-confined by contract, not thread-safe.</b> Every lane in this test
 * therefore owns its own reader, its own session and its own cursor, and nothing but the immutable input
 * bytes is shared between lanes. A shared-reader variant is deliberately <em>not</em> part of this test:
 * sharing one {@link DataCodec.Reader} across lanes would race on its single cursor and its single
 * budget, which is exactly what the contract forbids, so asserting anything about it would document a
 * misuse rather than a guarantee.</p>
 *
 * <p>What each lane does is the traffic a generated reader really produces: read a valid value, skip the
 * same value, read a corrupt region that must fail in a stable way, read a value that exceeds one decode
 * limit and must be refused — repeatedly, interleaved, with the operations deliberately mixed so a lane
 * that only worked when it happened to be used in one order would show it. The result sequence of every
 * lane is then compared against the single-threaded oracle result computed for the same operation
 * sequence before the lanes start, so this is a differential test, not a smoke test.</p>
 *
 * <p>Lanes run on virtual threads ({@link Executors#newVirtualThreadPerTaskExecutor()}) and are released
 * by a barrier so they interleave. No lane reads another lane's state, and every lane asserts that its
 * cursor ended unbound and that its budget ended quiescent.</p>
 */
class ConcurrentLanePropertyTest {

	@Property(tries = 1)
	void laneLocalReadersSessionsAndCursorsReproduceTheSingleThreadedResult() throws Exception {
		int lanes = laneCount();
		int rounds = FuzzTiers.cases("concurrent-lane-rounds", 1, 2, 10, 20);
		Random rnd = FuzzTiers.random("concurrent-lane-traffic");
		DataCodec<Object> codec = asCodec(Serializers.StringSerializerInstance);

		// one operation list, built once: the lanes and the oracle all run exactly this sequence
		List<Op> plan = new ArrayList<>();
		for (int round = 0; round < rounds; round++) {
			String value = hostiles(rnd);
			byte[] valid = encode(codec, value);
			byte[] corrupt = corrupt(valid, rnd);
			plan.add(Op.read(valid, round + " valid session read"));
			plan.add(Op.reader(valid, round + " valid reader read"));
			plan.add(Op.skip(valid, round + " skip"));
			plan.add(Op.read(corrupt, round + " corrupt session read"));
			plan.add(Op.reader(corrupt, round + " corrupt reader read"));
			plan.add(Op.skip(corrupt, round + " corrupt skip"));
		}
		List<Outcome> oracle = replaySingleThreaded(codec, plan);
		assertEquals(plan.size(), oracle.size(), gate("the oracle must answer every operation"));

		// every lane runs the same plan on its own objects; results must equal the oracle element-wise
		List<List<Outcome>> laneResults = runLanes(codec, plan, lanes);
		assertEquals(lanes, laneResults.size(), gate("every lane must report"));
		for (int lane = 0; lane < laneResults.size(); lane++) {
			List<Outcome> actual = laneResults.get(lane);
			assertEquals(oracle.size(), actual.size(), gate("lane " + lane + " answered every operation"));
			for (int index = 0; index < oracle.size(); index++) {
				Outcome expected = oracle.get(index);
				Outcome got = actual.get(index);
				assertEquals(expected.kind(), got.kind(),
						gate("lane " + lane + " op " + index + " (" + plan.get(index).what() + ") kind"));
				assertEquals(expected.rendered(), got.rendered(),
						gate("lane " + lane + " op " + index + " (" + plan.get(index).what()
								+ ") result differs from the single-threaded oracle"));
			}
		}
	}

	@Property(tries = 1)
	void everyLaneEndsWithAnUnboundCursorAndAQuiescentBudget() throws Exception {
		int lanes = laneCount();
		int rounds = FuzzTiers.cases("concurrent-lane-quiescence", 1, 2, 8, 16);
		Random rnd = FuzzTiers.random("concurrent-lane-quiescence");
		DataCodec<Object> codec = asCodec(Serializers.BinaryStringSerializerInstance);
		List<Op> plan = new ArrayList<>();
		for (int round = 0; round < rounds; round++) {
			byte[] bytes = FuzzValues.bytes(rnd, 1 + rnd.nextInt(200));
			byte[] valid = encode(codec, new BinaryString(bytes));
			plan.add(Op.read(valid, round + " valid"));
			plan.add(Op.reader(valid, round + " valid reader"));
			plan.add(Op.skip(valid, round + " skip"));
			plan.add(Op.read(corrupt(valid, rnd), round + " corrupt"));
			plan.add(Op.skip(valid, round + " skip again"));
		}
		List<LaneReport> reports = runLanesReporting(codec, plan, lanes);
		for (LaneReport report : reports) {
			assertTrue(report.finished(), gate("lane " + report.lane() + " did not finish: " + report.error()));
			assertFalse(report.cursorLeftBound(), gate("lane " + report.lane() + " ended with a bound cursor"));
			assertEquals(0L, report.claimedArrayElements(),
					gate("lane " + report.lane() + " left array elements claimed"));
			assertEquals(0L, report.claimedPayloadBytes(),
					gate("lane " + report.lane() + " left payload bytes claimed"));
			assertEquals(0, report.structuralDepth(), gate("lane " + report.lane() + " left a root open"));
			assertEquals(0, report.boundAtEnd(), gate("lane " + report.lane() + " cursor bind counter"));
		}
	}

	/** @return the lane count: 8 by default, 64 from {@link FuzzTiers.Tier#DEEP} up. */
	private static int laneCount() {
		return FuzzTiers.cases("concurrent-lane-count", 4, 8, 64, 64);
	}

	/** Runs every lane on a virtual thread, released together by a barrier. */
	private static List<List<Outcome>> runLanes(DataCodec<Object> codec, List<Op> plan, int lanes)
			throws Exception {
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Callable<List<Outcome>>> tasks = new ArrayList<>(lanes);
			for (int lane = 0; lane < lanes; lane++) {
				final int laneId = lane;
				tasks.add(() -> {
					Lane lane1 = new Lane(codec, laneId);
					try {
						lane1.begin();
						List<Outcome> results = new ArrayList<>(plan.size());
						for (Op op : plan) {
							results.add(lane1.execute(op));
						}
						return results;
					} finally {
						lane1.finish();
					}
				});
			}
			List<Future<List<Outcome>>> futures = new ArrayList<>(lanes);
			for (Callable<List<Outcome>> task : tasks) {
				futures.add(executor.submit(task));
			}
			List<List<Outcome>> results = new ArrayList<>(lanes);
			for (int lane = 0; lane < futures.size(); lane++) {
				results.add(get(futures.get(lane), lane));
			}
			executor.shutdown();
			assertTrue(executor.awaitTermination(2, TimeUnit.MINUTES),
					gate("lanes did not terminate"));
			return results;
		}
	}

	/** Runs the lanes and collects their end-state reports instead of their values. */
	private static List<LaneReport> runLanesReporting(DataCodec<Object> codec, List<Op> plan, int lanes)
			throws Exception {
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<LaneReport>> futures = new ArrayList<>(lanes);
			for (int lane = 0; lane < lanes; lane++) {
				final int laneId = lane;
				futures.add(executor.submit(() -> {
					Lane state = new Lane(codec, laneId);
					String error = null;
					try {
						state.begin();
						for (Op op : plan) {
							state.execute(op);
						}
					} catch (RuntimeException | Error failure) {
						error = failure.getClass().getName() + ": " + failure.getMessage();
					} finally {
						state.finish();
					}
					return state.report(error);
				}));
			}
			List<LaneReport> reports = new ArrayList<>(lanes);
			for (int lane = 0; lane < futures.size(); lane++) {
				reports.add(get(futures.get(lane), lane));
			}
			return reports;
		}
	}

	private static <T> T get(Future<T> future, int lane) {
		try {
			return future.get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(gate("lane " + lane + " was interrupted"), interrupted);
		} catch (ExecutionException failure) {
			throw new AssertionError(gate("lane " + lane + " failed"), failure.getCause());
		}
	}

	/** The single-threaded reference: one reader, one session, one cursor, used sequentially. */
	private static List<Outcome> replaySingleThreaded(DataCodec<Object> codec, List<Op> plan) {
		Lane oracle = new Lane(codec, -1);
		oracle.begin();
		try {
			List<Outcome> results = new ArrayList<>(plan.size());
			for (Op op : plan) {
				results.add(oracle.execute(op));
			}
			return results;
		} finally {
			oracle.finish();
		}
	}

	/**
	 * One lane's worth of thread-confined objects. A lane owns a {@link DataCodec.Reader}, a
	 * {@link ReadSession} and a {@link BufDataCursor}; it rebinds its own cursor per operation and never
	 * touches another lane's state.
	 */
	private static final class Lane {

		private final DataCodec<Object> codec;
		private final int laneId;
		private final DecodeLimits limits = new DecodeLimits(64, 4_096, 4_096L, 4_194_304L, 8);

		private DataCodec.Reader<Object> reader;
		private ReadSession<Object> session;
		private BufDataCursor cursor;
		private DecodeBudget budget;
		private int bindCounter;

		Lane(DataCodec<Object> codec, int laneId) {
			this.codec = codec;
			this.laneId = laneId;
		}

		void begin() {
			reader = codec.newReader(limits);
			session = codec.newReadSession();
			budget = new DecodeBudget(limits);
			cursor = new BufDataCursor(budget);
			bindCounter = 0;
		}

		void finish() {
			if (cursor != null) {
				cursor.unbind();
			}
		}

		/** Performs one operation on this lane's own objects and reports what it observed. */
		Outcome execute(Op op) {
			// the payload is read through a per-lane copy, so no storage is shared either
			Buf source = Buf.wrap(op.bytes().clone());
			try {
				switch (op.kind()) {
					case Op.READ: {
						cursor.bind(source, 0, op.bytes().length);
						bindCounter++;
						Object value;
						try {
							value = session.read(cursor);
						} finally {
							cursor.unbind();
							bindCounter--;
						}
						return Outcome.value(value);
					}
					case Op.SKIP: {
						cursor.bind(source, 0, op.bytes().length);
						bindCounter++;
						try {
							session.skip(cursor);
						} finally {
							cursor.unbind();
							bindCounter--;
						}
						return Outcome.of("skipped");
					}
					case Op.READER: {
						// the reader is the public bounded-region entry point: trailing bytes are refused
						Object value = reader.read(source, 0, op.bytes().length);
						return Outcome.value(value);
					}
					default:
						throw new IllegalStateException("unknown lane operation " + op.kind());
				}
			} catch (MalformedDataException | DecodeLimitExceededException | ValueTooLargeException stable) {
				return Outcome.of("refused:" + stable.getClass().getSimpleName());
			} finally {
				if (cursor != null && cursor.isBound()) {
					cursor.unbind();
					bindCounter--;
				}
			}
		}

		LaneReport report(String error) {
			// a finished root keeps its claims until the next root entry clears them, so the quiescent
			// state this property asserts is the one after an empty root, exactly like the oracle's probe
			budget.enterRoot();
			budget.exitRoot();
			return new LaneReport(laneId, error == null, error, cursor.isBound(), bindCounter,
					budget.claimedArrayElements(), budget.claimedPayloadBytes(), budget.structuralDepth());
		}
	}

	/**
	 * One planned operation. Three kinds, so all three thread-confined objects are exercised by every
	 * lane: {@link #READ} through the lane's {@link ReadSession} and cursor, {@link #SKIP} through the
	 * same session, {@link #READER} through the lane's own {@link DataCodec.Reader}.
	 */
	private record Op(int kind, byte[] bytes, String what) {

		static final int READ = 0;
		static final int SKIP = 1;
		static final int READER = 2;

		static Op read(byte[] bytes, String what) {
			return new Op(READ, bytes, what);
		}

		static Op skip(byte[] bytes, String what) {
			return new Op(SKIP, bytes, what);
		}

		static Op reader(byte[] bytes, String what) {
			return new Op(READER, bytes, what);
		}
	}

	/** What one operation produced: a rendered value, or the stable refusal it hit. */
	private record Outcome(String kind, String rendered) {

		static Outcome value(Object value) {
			return new Outcome("value", value == null ? "null" : render(value));
		}

		static Outcome of(String kind) {
			return new Outcome(kind, kind);
		}

		/** Values are compared by content, never by identity: the wire round trip may allocate freely. */
		private static String render(Object value) {
			if (value instanceof byte[] bytes) {
				return "bytes:" + java.util.Arrays.hashCode(bytes) + ":" + bytes.length;
			}
			if (value instanceof BinaryString binary) {
				return "binary:" + java.util.Arrays.hashCode(binary.data()) + ":" + binary.data().length;
			}
			return value.getClass().getName() + ":" + value;
		}
	}

	/** A lane's end state, asserted after it stopped touching anything. */
	private record LaneReport(int lane, boolean finished, String error, boolean cursorLeftBound,
			int boundAtEnd, long claimedArrayElements, long claimedPayloadBytes, int structuralDepth) {
	}

	/** @return the exact wire form of {@code value}. */
	private static byte[] encode(DataCodec<Object> codec, Object value) {
		BufDataOutput output = BufDataOutput.create();
		codec.serialize(output, value);
		Buf encoded = output.asList();
		return FuzzStorages.bytesOf(
				new FuzzStorages.View(FuzzStorages.Kind.FORWARD_STREAM, encoded, 0, encoded.size()));
	}

	/** @return a one-byte-flipped plus truncated mutant, which a lane must survive in a stable way. */
	private static byte[] corrupt(byte[] encoded, Random rnd) {
		if (encoded.length == 0) {
			return new byte[] {(byte) 0xFF};
		}
		byte[] mutant = encoded.clone();
		mutant[rnd.nextInt(mutant.length)] ^= (byte) (1 << rnd.nextInt(8));
		return mutant;
	}

	/** @return a hostile string without unpaired surrogates, which UTF-8 cannot carry unchanged */
	private static String hostiles(Random rnd) {
		StringBuilder sb = new StringBuilder();
		while (sb.length() < 8) {
			String drawn = FuzzValues.string(rnd);
			sb.append(drawn.isEmpty() ? "x\u0000" : drawn);
		}
		StringBuilder result = new StringBuilder(sb.length());
		for (int i = 0; i < sb.length(); i++) {
			char c = sb.charAt(i);
			if (Character.isHighSurrogate(c)) {
				if (i + 1 < sb.length() && Character.isLowSurrogate(sb.charAt(i + 1))) {
					result.append(c).append(sb.charAt(++i));
				} else {
					result.append('\uFFFD');
				}
			} else {
				result.append(Character.isLowSurrogate(c) ? '\uFFFD' : c);
			}
		}
		return result.toString();
	}

	@SuppressWarnings("unchecked")
	private static DataCodec<Object> asCodec(DataCodec<?> codec) {
		return Objects.requireNonNull((DataCodec<Object>) codec, "codec");
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}

}
