package it.cavallium.buffer;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.nativedata.StringSerializer;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataInputStream;
import it.cavallium.stream.SafeDataOutput;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Allocation and throughput comparison for normal owned-value readers.
 *
 * <p>Run with {@code -prof gc}. Reused-reader methods should allocate only the returned value graph
 * after native string scratch warmup. The one-shot baseline adds an input object, the stream baseline
 * adds cursor/stream machinery (and copies native payloads), and the structural model adds a
 * historical record before creating the equivalent current record. Actual generated read plans are
 * benchmarked by {@code it.cavallium.datagen.benchmark.GeneratedNormalReaderBench}.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@Warmup(time = 2, iterations = 5)
@Measurement(time = 2, iterations = 5)
public class NormalReaderBench {

	private static final DataCodec<Payload> CURRENT = new DataCodec<>() {
		@Override
		public void serialize(SafeDataOutput output, Payload data) {
			output.writeLong(data.id());
			output.writeInt(data.kind());
			StringSerializer.INSTANCE.serialize(output, data.text());
		}

		@Override
		public Payload read(SafeDataInput input) {
			return new Payload(input.readLong(), input.readInt(), StringSerializer.INSTANCE.read(input));
		}

		@Override
		public void skip(SafeDataInput input) {
			ProjectionReadSupport.skipBytes(input, Long.BYTES + Integer.BYTES);
			StringSerializer.INSTANCE.skip(input);
		}
	};

	private static final DataCodec<HistoricalPayload> HISTORICAL = new DataCodec<>() {
		@Override
		public void serialize(SafeDataOutput output, HistoricalPayload data) {
			output.writeLong(data.id());
			output.writeInt(data.kind());
			StringSerializer.INSTANCE.serialize(output, data.text());
		}

		@Override
		public HistoricalPayload read(SafeDataInput input) {
			return new HistoricalPayload(input.readLong(), input.readInt(), StringSerializer.INSTANCE.read(input));
		}

		@Override
		public void skip(SafeDataInput input) {
			ProjectionReadSupport.skipBytes(input, Long.BYTES + Integer.BYTES);
			StringSerializer.INSTANCE.skip(input);
		}
	};

	@State(Scope.Thread)
	public static class StateData {

		private Arena arena;
		private Buf heap;
		private Buf slicedHeap;
		private Buf nativeBuffer;
		private DataCodec.Reader<Payload> reader;
		private DataCodec.Reader<HistoricalPayload> historicalReader;

		@Setup(Level.Trial)
		public void setup() {
			Payload value = new Payload(42L, 7, "normal reader benchmark");
			BufDataOutput output = BufDataOutput.create(64);
			CURRENT.serialize(output, value);
			heap = output.asList();

			byte[] padded = new byte[heap.size() + 8];
			MemorySegment.copy(heap.asMemorySegment(), 0, MemorySegment.ofArray(padded), 4, heap.size());
			slicedHeap = Buf.wrap(padded).subList(4, 4 + heap.size());

			arena = Arena.ofConfined();
			MemorySegment nativeSegment = arena.allocate(heap.size(), 8);
			MemorySegment.copy(heap.asMemorySegment(), 0, nativeSegment, 0, heap.size());
			nativeBuffer = new MemorySegmentBuf(nativeSegment);
			reader = CURRENT.newReader(DecodeLimits.unlimited());
			historicalReader = HISTORICAL.newReader(DecodeLimits.unlimited());

			// Warm the cursor-owned native string scratch before allocation measurements.
			reader.read(nativeBuffer);
			historicalReader.read(nativeBuffer);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			arena.close();
		}
	}

	@Benchmark
	public Payload reusedHeap(StateData state) {
		return state.reader.read(state.heap);
	}

	@Benchmark
	public Payload reusedSlicedHeap(StateData state) {
		return state.reader.read(state.slicedHeap);
	}

	@Benchmark
	public Payload reusedNative(StateData state) {
		return state.reader.read(state.nativeBuffer);
	}

	@Benchmark
	public Payload oneShotInputHeap(StateData state) {
		return CURRENT.read(BufDataInput.create(state.heap, DecodeLimits.unlimited()));
	}

	@Benchmark
	public Payload oneShotInputSlicedHeap(StateData state) {
		return CURRENT.read(BufDataInput.create(state.slicedHeap, DecodeLimits.unlimited()));
	}

	@Benchmark
	public Payload oneShotInputNative(StateData state) {
		return CURRENT.read(BufDataInput.create(state.nativeBuffer, DecodeLimits.unlimited()));
	}

	@Benchmark
	public Payload streamBaselineHeap(StateData state) {
		return CURRENT.read(new SafeDataInputStream(state.heap.binaryInputStream(), DecodeLimits.unlimited()));
	}

	@Benchmark
	public Payload streamBaselineSlicedHeap(StateData state) {
		return CURRENT.read(new SafeDataInputStream(state.slicedHeap.binaryInputStream(), DecodeLimits.unlimited()));
	}

	@Benchmark
	public Payload streamBaselineNative(StateData state) {
		return CURRENT.read(new SafeDataInputStream(state.nativeBuffer.binaryInputStream(), DecodeLimits.unlimited()));
	}

	@Benchmark
	public Payload materializeAndUpgradeHeap(StateData state, Blackhole blackhole) {
		HistoricalPayload old = state.historicalReader.read(state.heap);
		blackhole.consume(old);
		return new Payload(old.id(), old.kind(), old.text());
	}

	@Benchmark
	public Payload materializeAndUpgradeSlicedHeap(StateData state, Blackhole blackhole) {
		HistoricalPayload old = state.historicalReader.read(state.slicedHeap);
		blackhole.consume(old);
		return new Payload(old.id(), old.kind(), old.text());
	}

	@Benchmark
	public Payload materializeAndUpgradeNative(StateData state, Blackhole blackhole) {
		HistoricalPayload old = state.historicalReader.read(state.nativeBuffer);
		blackhole.consume(old);
		return new Payload(old.id(), old.kind(), old.text());
	}

	private record Payload(long id, int kind, String text) {}

	private record HistoricalPayload(long id, int kind, String text) {}
}
