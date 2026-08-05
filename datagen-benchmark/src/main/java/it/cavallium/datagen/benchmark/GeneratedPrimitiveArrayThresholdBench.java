package it.cavallium.datagen.benchmark;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.benchmark.fixture.BaseType;
import it.cavallium.datagen.benchmark.fixture.current.CurrentVersion;
import it.cavallium.datagen.benchmark.fixture.current.data.BooleanArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.ByteArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.CharArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.DoubleArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.FloatArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.Int52ArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.IntArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.LongArrayCase;
import it.cavallium.datagen.benchmark.fixture.current.data.ShortArrayCase;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.datagen.nativedata.Int52Serializer;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Isolates each generated primitive-array kernel so named Vector crossover constants can be
 * justified independently. Heap and native cover the two threshold families; sliced heap shares
 * the heap kernel and generic fallback never enters Vector code, and both remain covered by the
 * full generated-reader matrix.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@Warmup(time = 2, iterations = 5)
@Measurement(time = 2, iterations = 5)
public class GeneratedPrimitiveArrayThresholdBench {
	private static final DecodeLimits LIMITS = DecodeLimits.unlimited();

	@State(Scope.Thread)
	public static class ArrayState {

		@Param({"heap", "native"})
		public String storage;

		@Param({"0", "1", "2", "8", "16", "32", "64", "128", "256", "4096"})
		public int size;

		private Arena arena;
		private Buf booleanSource;
		private Buf byteSource;
		private Buf shortSource;
		private Buf charSource;
		private Buf intSource;
		private Buf longSource;
		private Buf floatSource;
		private Buf doubleSource;
		private Buf int52Source;
		private CurrentVersion.BoundReader<BooleanArrayCase> booleanReader;
		private CurrentVersion.BoundReader<ByteArrayCase> byteReader;
		private CurrentVersion.BoundReader<ShortArrayCase> shortReader;
		private CurrentVersion.BoundReader<CharArrayCase> charReader;
		private CurrentVersion.BoundReader<IntArrayCase> intReader;
		private CurrentVersion.BoundReader<LongArrayCase> longReader;
		private CurrentVersion.BoundReader<FloatArrayCase> floatReader;
		private CurrentVersion.BoundReader<DoubleArrayCase> doubleReader;
		private CurrentVersion.BoundReader<Int52ArrayCase> int52Reader;

		@Setup(Level.Trial)
		public void setup() {
			arena = Arena.ofConfined();
			booleanSource = storage(booleanPayload(size));
			byteSource = storage(bytePayload(size));
			shortSource = storage(shortPayload(size));
			charSource = storage(charPayload(size));
			intSource = storage(intPayload(size));
			longSource = storage(longPayload(size));
			floatSource = storage(floatPayload(size));
			doubleSource = storage(doublePayload(size));
			int52Source = storage(int52Payload(size));
			booleanReader = CurrentVersion.newReader(0, BaseType.BooleanArrayCase, LIMITS);
			byteReader = CurrentVersion.newReader(0, BaseType.ByteArrayCase, LIMITS);
			shortReader = CurrentVersion.newReader(0, BaseType.ShortArrayCase, LIMITS);
			charReader = CurrentVersion.newReader(0, BaseType.CharArrayCase, LIMITS);
			intReader = CurrentVersion.newReader(0, BaseType.IntArrayCase, LIMITS);
			longReader = CurrentVersion.newReader(0, BaseType.LongArrayCase, LIMITS);
			floatReader = CurrentVersion.newReader(0, BaseType.FloatArrayCase, LIMITS);
			doubleReader = CurrentVersion.newReader(0, BaseType.DoubleArrayCase, LIMITS);
			int52Reader = CurrentVersion.newReader(0, BaseType.Int52ArrayCase, LIMITS);

			// Warm every reader-owned cursor before allocation measurement.
			booleanReader.read(booleanSource);
			byteReader.read(byteSource);
			shortReader.read(shortSource);
			charReader.read(charSource);
			intReader.read(intSource);
			longReader.read(longSource);
			floatReader.read(floatSource);
			doubleReader.read(doubleSource);
			int52Reader.read(int52Source);
		}

		private Buf storage(Buf source) {
			return switch (storage) {
				case "heap" -> source;
				case "native" -> {
					MemorySegment segment = arena.allocate(source.size(), 8);
					MemorySegment.copy(source.asMemorySegment(), 0, segment, 0, source.size());
					yield new MemorySegmentBuf(segment);
				}
				default -> throw new IllegalArgumentException(storage);
			};
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			arena.close();
		}
	}

	@Benchmark
	public BooleanArrayCase generatedBooleanArray(ArrayState state) {
		return state.booleanReader.read(state.booleanSource);
	}

	@Benchmark
	public ByteArrayCase generatedByteArray(ArrayState state) {
		return state.byteReader.read(state.byteSource);
	}

	@Benchmark
	public ShortArrayCase generatedShortArray(ArrayState state) {
		return state.shortReader.read(state.shortSource);
	}

	@Benchmark
	public CharArrayCase generatedCharArray(ArrayState state) {
		return state.charReader.read(state.charSource);
	}

	@Benchmark
	public IntArrayCase generatedIntArray(ArrayState state) {
		return state.intReader.read(state.intSource);
	}

	@Benchmark
	public LongArrayCase generatedLongArray(ArrayState state) {
		return state.longReader.read(state.longSource);
	}

	@Benchmark
	public FloatArrayCase generatedFloatArray(ArrayState state) {
		return state.floatReader.read(state.floatSource);
	}

	@Benchmark
	public DoubleArrayCase generatedDoubleArray(ArrayState state) {
		return state.doubleReader.read(state.doubleSource);
	}

	@Benchmark
	public Int52ArrayCase generatedInt52Array(ArrayState state) {
		return state.int52Reader.read(state.int52Source);
	}

	private static Buf booleanPayload(int size) {
		BufDataOutput output = output(size, Byte.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeBoolean((i & 1) == 0);
		return output.asList();
	}

	private static Buf bytePayload(int size) {
		BufDataOutput output = output(size, Byte.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeByte(i);
		return output.asList();
	}

	private static Buf shortPayload(int size) {
		BufDataOutput output = output(size, Short.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeShort(i * 3);
		return output.asList();
	}

	private static Buf charPayload(int size) {
		BufDataOutput output = output(size, Character.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeChar(i);
		return output.asList();
	}

	private static Buf intPayload(int size) {
		BufDataOutput output = output(size, Integer.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeInt(i * 5);
		return output.asList();
	}

	private static Buf longPayload(int size) {
		BufDataOutput output = output(size, Long.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeLong(i * 7L);
		return output.asList();
	}

	private static Buf floatPayload(int size) {
		BufDataOutput output = output(size, Float.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeFloat(i + 0.25f);
		return output.asList();
	}

	private static Buf doublePayload(int size) {
		BufDataOutput output = output(size, Double.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeDouble(i + 0.5d);
		return output.asList();
	}

	private static Buf int52Payload(int size) {
		BufDataOutput output = output(size, Int52.BYTES);
		output.writeInt(size);
		for (int i = 0; i < size; i++) Int52Serializer.serializeValue(output, Int52.fromLong(i));
		return output.asList();
	}

	private static BufDataOutput output(int size, int elementBytes) {
		return BufDataOutput.create(Math.max(64, Math.addExact(Integer.BYTES,
				Math.multiplyExact(size, elementBytes))));
	}
}
