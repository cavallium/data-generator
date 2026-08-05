package it.cavallium.datagen.benchmark;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.benchmark.fixture.BaseType;
import it.cavallium.datagen.benchmark.fixture.current.CurrentVersion;
import it.cavallium.datagen.benchmark.fixture.current.data.ContextOpaqueRoot;
import it.cavallium.datagen.benchmark.fixture.current.data.ContextOptimizedRoot;
import it.cavallium.datagen.benchmark.fixture.current.data.BuiltInRoot;
import it.cavallium.datagen.benchmark.fixture.current.data.FixedCustomArray;
import it.cavallium.datagen.benchmark.fixture.current.data.FixedCustomRun;
import it.cavallium.datagen.benchmark.fixture.current.data.NullableHeavy;
import it.cavallium.datagen.benchmark.fixture.current.data.OpaqueRoot;
import it.cavallium.datagen.benchmark.fixture.current.data.OptimizedRoot;
import it.cavallium.datagen.benchmark.fixture.current.data.PrimitiveArrays;
import it.cavallium.datagen.benchmark.fixture.current.data.PrimitiveDense;
import it.cavallium.datagen.benchmark.fixture.current.data.Root;
import it.cavallium.datagen.benchmark.fixture.current.data.StringHeavy;
import it.cavallium.datagen.benchmark.fixture.current.data.ViewRoot;
import it.cavallium.datagen.benchmark.fixture.current.data.WideRoot;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.datagen.nativedata.Int52Serializer;
import it.cavallium.stream.SafeDataInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
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
 * Runs the actual generated {@link CurrentVersion} code for the oldest and current fixture layouts.
 *
 * <p>Run with {@code -prof gc}. The bound historical method has a monomorphic reader class and calls
 * a version-specific generated read plan, while the mixed method retains per-row version dispatch.
 * The baseline materializes the exact historical graph and then invokes the generated object upgrade
 * chain. All methods use reusable readers, so allocation differences are returned-graph differences.</p>
 *
 * <p>The optimized/opaque value and context pairs use the same nested-record-array payload. The
 * optimized generated input constructs the current graph directly; each opaque control materializes
 * historical records, two historical element containers, and their structural tail.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@Warmup(time = 2, iterations = 5)
@Measurement(time = 2, iterations = 5)
public class GeneratedNormalReaderBench {
	private static final DecodeLimits LIMITS = DecodeLimits.unlimited();

	@State(Scope.Thread)
	public static class StateData {

		@Param({"heap", "sliced-heap", "native", "fallback"})
		public String storage;

		private Arena arena;
		private Buf historicalSource;
		private Buf historicalStreamSource;
		private Buf currentSource;
		private Buf upgradeSource;
		private Buf contextUpgradeSource;
		private Buf currentUpgradeSource;
		private int historicalVersion;
		private CurrentVersion.BoundReader<Root> boundHistoricalReader;
		private CurrentVersion.Reader<Root> mixedHistoricalReader;
		private CurrentVersion.BoundReader<Root> boundCurrentReader;
		private DataCodec.Reader<it.cavallium.datagen.benchmark.fixture.v0.data.Root> exactHistoricalReader;
		private CurrentVersion.BoundReader<OptimizedRoot> optimizedUpgradeReader;
		private CurrentVersion.BoundReader<OpaqueRoot> opaqueUpgradeReader;
		private CurrentVersion.BoundReader<OptimizedRoot> currentUpgradeReader;
		private CurrentVersion.BoundReader<ContextOptimizedRoot> optimizedContextReader;
		private CurrentVersion.BoundReader<ContextOpaqueRoot> opaqueContextReader;

		@Setup(Level.Trial)
		public void setup() {
			arena = Arena.ofConfined();
			historicalVersion = 0;
			Buf historicalPayload = historicalPayload();
			historicalSource = storage(historicalPayload);
			// The forced-fallback source deliberately rejects stream conversion; use the same wire bytes
			// through a normal heap source for the explicitly stream-backed control in that parameter row.
			historicalStreamSource = storage.equals("fallback") ? historicalPayload : historicalSource;
			currentSource = storage(currentPayload());
			upgradeSource = storage(upgradePayload());
			contextUpgradeSource = storage(contextUpgradePayload());
			currentUpgradeSource = storage(currentUpgradePayload());
			boundHistoricalReader = CurrentVersion.newReader(0, BaseType.Root, LIMITS);
			mixedHistoricalReader = CurrentVersion.newReader(BaseType.Root, LIMITS);
			boundCurrentReader = CurrentVersion.newReader(2, BaseType.Root, LIMITS);
			exactHistoricalReader = it.cavallium.datagen.benchmark.fixture.v0.Version
					.RootSerializerInstance.newReader(LIMITS);
			optimizedUpgradeReader = CurrentVersion.newReader(0, BaseType.OptimizedRoot, LIMITS);
			opaqueUpgradeReader = CurrentVersion.newReader(0, BaseType.OpaqueRoot, LIMITS);
			currentUpgradeReader = CurrentVersion.newReader(2, BaseType.OptimizedRoot, LIMITS);
			optimizedContextReader = CurrentVersion.newReader(0, BaseType.ContextOptimizedRoot, LIMITS);
			opaqueContextReader = CurrentVersion.newReader(0, BaseType.ContextOpaqueRoot, LIMITS);

			// Warm every cursor, including native string scratch, before allocation measurement.
			boundHistoricalReader.read(historicalSource);
			mixedHistoricalReader.read(historicalVersion, historicalSource);
			boundCurrentReader.read(currentSource);
			exactHistoricalReader.read(historicalSource);
			optimizedUpgradeReader.read(upgradeSource);
			opaqueUpgradeReader.read(upgradeSource);
			currentUpgradeReader.read(currentUpgradeSource);
			optimizedContextReader.read(contextUpgradeSource);
			opaqueContextReader.read(contextUpgradeSource);
		}

		private Buf storage(Buf source) {
			return GeneratedNormalReaderBench.storage(source, storage, arena);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			arena.close();
		}
	}

	@State(Scope.Thread)
	public static class PrimitiveArrayState {

		@Param({"heap", "sliced-heap", "native", "fallback"})
		public String storage;

		@Param({"0", "1", "2", "8", "32", "256", "4096"})
		public int size;

		private Arena arena;
		private Buf source;
		private CurrentVersion.BoundReader<PrimitiveArrays> reader;

		@Setup(Level.Trial)
		public void setup() {
			arena = Arena.ofConfined();
			source = GeneratedNormalReaderBench.storage(primitiveArrayPayload(size), storage, arena);
			reader = CurrentVersion.newReader(0, BaseType.PrimitiveArrays, LIMITS);
			reader.read(source);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			arena.close();
		}
	}

	@State(Scope.Thread)
	public static class FeatureState {

		@Param({"heap", "sliced-heap", "native", "fallback"})
		public String storage;

		private Arena arena;
		private Buf primitiveDenseSource;
		private Buf stringSource;
		private Buf nullableSource;
		private Buf viewSource;
		private Buf builtInSource;
		private CurrentVersion.BoundReader<PrimitiveDense> primitiveDenseReader;
		private CurrentVersion.BoundReader<StringHeavy> stringReader;
		private CurrentVersion.BoundReader<NullableHeavy> nullableReader;
		private CurrentVersion.BoundReader<ViewRoot> viewReader;
		private CurrentVersion.BoundReader<BuiltInRoot> builtInReader;

		@Setup(Level.Trial)
		public void setup() {
			arena = Arena.ofConfined();
			primitiveDenseSource = storage(primitiveDensePayload(), storage, arena);
			stringSource = storage(stringHeavyPayload(), storage, arena);
			nullableSource = storage(nullableHeavyPayload(), storage, arena);
			viewSource = storage(viewPayload(), storage, arena);
			builtInSource = storage(builtInPayload(), storage, arena);
			primitiveDenseReader = CurrentVersion.newReader(0, BaseType.PrimitiveDense, LIMITS);
			stringReader = CurrentVersion.newReader(0, BaseType.StringHeavy, LIMITS);
			nullableReader = CurrentVersion.newReader(0, BaseType.NullableHeavy, LIMITS);
			viewReader = CurrentVersion.newReader(0, BaseType.ViewRoot, LIMITS);
			builtInReader = CurrentVersion.newReader(0, BaseType.BuiltInRoot, LIMITS);
			primitiveDenseReader.read(primitiveDenseSource);
			stringReader.read(stringSource);
			nullableReader.read(nullableSource);
			viewReader.read(viewSource);
			builtInReader.read(builtInSource);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			arena.close();
		}
	}

	@State(Scope.Thread)
	public static class WideUnionState {

		@Param({"heap", "sliced-heap", "native", "fallback"})
		public String storage;

		@Param({"0", "7", "15"})
		public int variant;

		private Arena arena;
		private Buf source;
		private CurrentVersion.BoundReader<WideRoot> reader;

		@Setup(Level.Trial)
		public void setup() {
			arena = Arena.ofConfined();
			source = storage(wideUnionPayload(variant), storage, arena);
			reader = CurrentVersion.newReader(0, BaseType.WideRoot, LIMITS);
			reader.read(source);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			arena.close();
		}
	}

	@State(Scope.Thread)
	public static class FixedCustomState {

		@Param({"heap", "sliced-heap", "native", "fallback"})
		public String storage;

		@Param({"0", "1", "16", "256", "4096"})
		public int size;

		private Arena arena;
		private Buf runSource;
		private Buf arraySource;
		private CurrentVersion.BoundReader<FixedCustomRun> runReader;
		private CurrentVersion.BoundReader<FixedCustomArray> arrayReader;

		@Setup(Level.Trial)
		public void setup() {
			arena = Arena.ofConfined();
			runSource = storage(fixedCustomRunPayload(), storage, arena);
			arraySource = storage(fixedCustomArrayPayload(size), storage, arena);
			runReader = CurrentVersion.newReader(0, BaseType.FixedCustomRun, LIMITS);
			arrayReader = CurrentVersion.newReader(0, BaseType.FixedCustomArray, LIMITS);
			// Warm the codec sessions and every selected storage cursor before measurement.
			runReader.read(runSource);
			arrayReader.read(arraySource);
		}

		@TearDown(Level.Trial)
		public void tearDown() {
			arena.close();
		}
	}

	private static Buf storage(Buf source, String storage, Arena arena) {
			return switch (storage) {
				case "heap" -> source;
				case "sliced-heap" -> {
					byte[] padded = new byte[source.size() + 16];
					MemorySegment.copy(source.asMemorySegment(), 0, MemorySegment.ofArray(padded), 8, source.size());
					yield Buf.wrap(padded).subList(8, 8 + source.size());
				}
				case "native" -> {
					MemorySegment segment = arena.allocate(source.size(), 8);
					MemorySegment.copy(source.asMemorySegment(), 0, segment, 0, source.size());
					yield new MemorySegmentBuf(segment);
				}
			case "fallback" -> new ForcedFallbackBuf(source.asMemorySegment());
			default -> throw new IllegalArgumentException(storage);
		};
	}

	/**
	 * Forces the generic {@link Buf} kernel without the per-invocation allocations of a dynamic
	 * proxy. The inherited primitive accessors remain direct, while every whole-payload or direct
	 * storage escape fails the benchmark immediately.
	 */
	private static final class ForcedFallbackBuf extends MemorySegmentBuf {

		private ForcedFallbackBuf(MemorySegment segment) {
			super(segment);
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
		public byte[] getBackingByteArray() {
			throw new AssertionError("fallback benchmark requested heap storage");
		}

		@Override
		public byte[] asArray() {
			throw new AssertionError("fallback benchmark copied complete payload");
		}

		@Override
		public MemorySegment asMemorySegment() {
			throw new AssertionError("fallback benchmark requested segment storage");
		}

		@Override
		public it.cavallium.stream.SafeByteArrayInputStream binaryInputStream() {
			throw new AssertionError("fallback benchmark requested a payload stream");
		}
	}

	@Benchmark
	public Root generatedBoundHistorical(StateData state) {
		return state.boundHistoricalReader.read(state.historicalSource);
	}

	@Benchmark
	public Root generatedMixedHistorical(StateData state) {
		return state.mixedHistoricalReader.read(state.historicalVersion, state.historicalSource);
	}

	@Benchmark
	public Root generatedBoundCurrent(StateData state) {
		return state.boundCurrentReader.read(state.currentSource);
	}

	/** Prior one-shot input shape: allocates an input object and retains mixed type/version dispatch. */
	@Benchmark
	public Root generatedOneShotBufDataInputHistorical(StateData state) {
		return CurrentVersion.read(state.historicalVersion, BaseType.Root,
				BufDataInput.create(state.historicalSource, LIMITS));
	}

	/** Prior stream-backed shape, including native whole-payload conversion where required. */
	@Benchmark
	public Root generatedStreamBackedHistorical(StateData state) {
		return CurrentVersion.read(state.historicalVersion, BaseType.Root,
				new SafeDataInputStream(state.historicalStreamSource.binaryInputStream(), LIMITS));
	}

	@Benchmark
	public Root materializeHistoricalThenUpgrade(StateData state) {
		it.cavallium.datagen.benchmark.fixture.v0.data.Root historical =
				state.exactHistoricalReader.read(state.historicalSource);
		return CurrentVersion.upgradeDataToLatestVersion(state.historicalVersion, historical);
	}

	/** No historical payload record/list or structural-tail object is created. */
	@Benchmark
	public OptimizedRoot generatedFinalTypeReadUpgrade(StateData state) {
		return state.optimizedUpgradeReader.read(state.upgradeSource);
	}

	/** Same wire layout and current result shape through the opaque object upgrader boundary. */
	@Benchmark
	public OpaqueRoot generatedOpaqueObjectUpgrade(StateData state) {
		return state.opaqueUpgradeReader.read(state.upgradeSource);
	}

	/** Allocation-shape control: reads the equivalent graph from its current wire layout. */
	@Benchmark
	public OptimizedRoot generatedCurrentUpgradeShape(StateData state) {
		return state.currentUpgradeReader.read(state.currentUpgradeSource);
	}

	/** A used context is fused straight into the returned current graph. */
	@Benchmark
	public ContextOptimizedRoot generatedCurrentContextUpgrade(StateData state) {
		return state.optimizedContextReader.read(state.contextUpgradeSource);
	}

	/** Same context and result through historical context/payload materialization. */
	@Benchmark
	public ContextOpaqueRoot generatedOpaqueContextUpgrade(StateData state) {
		return state.opaqueContextReader.read(state.contextUpgradeSource);
	}

	/** All primitive-array codecs through the actual generated historical-to-current kernel. */
	@Benchmark
	public PrimitiveArrays generatedPrimitiveArrayMatrix(PrimitiveArrayState state) {
		return state.reader.read(state.source);
	}

	/** Fixed-run scheduling for a primitive-dense historical record. */
	@Benchmark
	public PrimitiveDense generatedPrimitiveDenseRecord(FeatureState state) {
		return state.primitiveDenseReader.read(state.primitiveDenseSource);
	}

	/** One coalesced primitive/custom fixed run with direct absolute custom-session loads. */
	@Benchmark
	public FixedCustomRun generatedAdjacentFixedCustoms(FixedCustomState state) {
		return state.runReader.read(state.runSource);
	}

	/** One reservation for the complete fixed-custom array and constant-stride reserved reads. */
	@Benchmark
	public FixedCustomArray generatedFixedCustomArray(FixedCustomState state) {
		return state.arrayReader.read(state.arraySource);
	}

	/** String fields, nullable strings, and owned string arrays through each storage kernel. */
	@Benchmark
	public StringHeavy generatedStringHeavyGraph(FeatureState state) {
		return state.stringReader.read(state.stringSource);
	}

	/** Flattened nullable primitives and references with no nullable carrier in the result graph. */
	@Benchmark
	public NullableHeavy generatedNullableHeavyGraph(FeatureState state) {
		return state.nullableReader.read(state.nullableSource);
	}

	/** Custom exact-kind union view branch without materializing the historical union. */
	@Benchmark
	public ViewRoot generatedTypedViewBranch(FeatureState state) {
		return state.viewReader.read(state.viewSource);
	}

	/** Direct invoke/construct/identity/mapArray/mapNullable/constant transform lowering. */
	@Benchmark
	public BuiltInRoot generatedDeclarativeBuiltins(FeatureState state) {
		return state.builtInReader.read(state.builtInSource);
	}

	/** Sixteen-way generated union dispatch at early, middle, and late discriminator IDs. */
	@Benchmark
	public WideRoot generatedLargeUnion(WideUnionState state) {
		return state.reader.read(state.source);
	}

	private static Buf historicalPayload() {
		BufDataOutput output = BufDataOutput.create(256);
		output.writeInt(100);
		output.writeLong(200L);
		output.writeInt(4);
		output.writeInt(1);
		output.writeInt(2);
		output.writeInt(3);
		output.writeInt(4);
		output.writeLong(42L);
		writeHistoricalLeaf(output, 7, "nested leaf");
		output.writeInt(2);
		writeHistoricalLeaf(output, 8, "first array leaf");
		writeHistoricalLeaf(output, 9, "second array leaf");
		output.writeMediumText("generated historical reader benchmark", StandardCharsets.UTF_8);
		return output.asList();
	}

	private static void writeHistoricalLeaf(BufDataOutput output, int value, String label) {
		output.writeInt(value);
		output.writeMediumText(label, StandardCharsets.UTF_8);
	}

	private static Buf currentPayload() {
		BufDataOutput output = BufDataOutput.create(256);
		output.writeLong(42L);
		writeCurrentLeaf(output, 7, "nested leaf");
		output.writeInt(2);
		writeCurrentLeaf(output, 8, "first array leaf");
		writeCurrentLeaf(output, 9, "second array leaf");
		output.writeMediumText("generated historical reader benchmark", StandardCharsets.UTF_8);
		output.writeLong(17L);
		return output.asList();
	}

	private static void writeCurrentLeaf(BufDataOutput output, int score, String label) {
		output.writeInt(score);
		output.writeMediumText(label, StandardCharsets.UTF_8);
		output.writeLong(17L);
	}

	private static Buf upgradePayload() {
		BufDataOutput output = BufDataOutput.create(128);
		writeUpgradePayload(output);
		return output.asList();
	}

	private static Buf contextUpgradePayload() {
		BufDataOutput output = BufDataOutput.create(128);
		output.writeInt(0);
		writeUpgradePayload(output);
		return output.asList();
	}

	private static void writeUpgradePayload(BufDataOutput output) {
		output.writeInt(8);
		for (int i = 0; i < 8; i++) output.writeInt(i + 1000);
		output.writeMediumText("opaque upgrade allocation payload", StandardCharsets.UTF_8);
	}

	private static Buf currentUpgradePayload() {
		BufDataOutput output = BufDataOutput.create(256);
		output.writeInt(8);
		for (int i = 0; i < 8; i++) {
			output.writeInt(i + 1000);
			output.writeLong(17L);
			output.writeLong(17L);
		}
		output.writeMediumText("opaque upgrade allocation payload", StandardCharsets.UTF_8);
		return output.asList();
	}

	private static Buf primitiveArrayPayload(int size) {
		BufDataOutput output = BufDataOutput.create(Math.max(64, size * 45));
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeBoolean((i & 1) == 0);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeByte(i);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeShort(i * 3);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeChar(i);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeInt(i * 5);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeLong(i * 7L);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeFloat(i + 0.25f);
		output.writeInt(size);
		for (int i = 0; i < size; i++) output.writeDouble(i + 0.5d);
		output.writeInt(size);
		for (int i = 0; i < size; i++) Int52Serializer.serializeValue(output, Int52.fromLong(i));
		return output.asList();
	}

	private static Buf primitiveDensePayload() {
		BufDataOutput output = BufDataOutput.create(64);
		output.writeBoolean(true);
		output.writeByte(0x7f);
		output.writeShort(0x1234);
		output.writeChar('\u03a9');
		output.writeInt(0x10203040);
		output.writeLong(0x0102030405060708L);
		output.writeFloat(1.5f);
		output.writeDouble(-2.25d);
		Int52Serializer.serializeValue(output, Int52.fromLong(0x010203040506L));
		return output.asList();
	}

	private static Buf fixedCustomRunPayload() {
		BufDataOutput output = BufDataOutput.create(24);
		output.writeInt(100);
		output.writeInt(1);
		output.writeLong(200L);
		output.writeInt(2);
		output.writeInt(300);
		return output.asList();
	}

	private static Buf fixedCustomArrayPayload(int size) {
		BufDataOutput output = BufDataOutput.create(Math.max(Integer.BYTES, Integer.BYTES + size * Integer.BYTES));
		output.writeInt(size);
		for (int index = 0; index < size; index++) output.writeInt(index & 0x7f);
		return output.asList();
	}

	private static Buf stringHeavyPayload() {
		BufDataOutput output = BufDataOutput.create(256);
		output.writeMediumText("first generated string payload", StandardCharsets.UTF_8);
		output.writeMediumText("second generated string payload", StandardCharsets.UTF_8);
		output.writeInt(4);
		for (int i = 0; i < 4; i++) {
			output.writeShortText("array string " + i, StandardCharsets.UTF_8);
		}
		output.writeBoolean(true);
		output.writeShortText("present nullable string", StandardCharsets.UTF_8);
		return output.asList();
	}

	private static Buf nullableHeavyPayload() {
		BufDataOutput output = BufDataOutput.create(192);
		output.writeBoolean(true);
		output.writeBoolean(false);
		output.writeBoolean(false);
		output.writeBoolean(true);
		output.writeShort(1234);
		output.writeBoolean(true);
		output.writeChar('\u03a9');
		output.writeBoolean(true);
		output.writeInt(123456);
		output.writeBoolean(true);
		output.writeLong(9876543210L);
		output.writeBoolean(true);
		output.writeFloat(1.25f);
		output.writeBoolean(true);
		output.writeDouble(-3.5d);
		Int52Serializer.serializeValue(output, Int52.fromLong(1234567L));
		output.writeBoolean(true);
		output.writeShortText("nullable string", StandardCharsets.UTF_8);
		output.writeBoolean(true);
		writeHistoricalLeaf(output, 77, "nullable leaf");
		output.writeInt(8);
		for (int i = 0; i < 8; i++) output.writeInt(i * 11);
		return output.asList();
	}

	private static Buf viewPayload() {
		BufDataOutput output = BufDataOutput.create(8);
		output.writeByte(3);
		output.writeInt(41);
		return output.asList();
	}

	private static Buf builtInPayload() {
		BufDataOutput output = BufDataOutput.create(64);
		output.writeInt(5);
		output.writeInt(7);
		output.writeInt(9);
		output.writeInt(11);
		output.writeInt(8);
		for (int i = 0; i < 8; i++) output.writeInt(i + 1);
		output.writeBoolean(true);
		output.writeInt(4);
		return output.asList();
	}

	private static Buf wideUnionPayload(int variant) {
		BufDataOutput output = BufDataOutput.create(8);
		output.writeByte(variant);
		output.writeInt(1000 + variant);
		return output.asList();
	}
}
