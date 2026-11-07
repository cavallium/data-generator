package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.fuzz.FuzzStorages.View;
import it.cavallium.datagen.nativedata.BinaryString;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.datagen.nativedata.NullableBinaryString;
import it.cavallium.datagen.nativedata.NullableInt52;
import it.cavallium.datagen.nativedata.NullableString;
import it.cavallium.datagen.nativedata.Nullableboolean;
import it.cavallium.datagen.nativedata.Nullablebyte;
import it.cavallium.datagen.nativedata.Nullablechar;
import it.cavallium.datagen.nativedata.Nullabledouble;
import it.cavallium.datagen.nativedata.Nullablefloat;
import it.cavallium.datagen.nativedata.Nullableint;
import it.cavallium.datagen.nativedata.Nullablelong;
import it.cavallium.datagen.nativedata.Nullableshort;
import it.cavallium.datagen.nativedata.Serializers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Property;

/**
 * Corruption, skip-alignment and wire-determinism properties for <em>every</em> codec the runtime
 * registry exposes, read through all six {@link FuzzStorages} families.
 *
 * <p>The codecs are not listed by hand: they are discovered by reflecting over the public static
 * {@link DataCodec} fields of {@link Serializers}, exactly as {@code NativeSerializerDeepFuzzTest}
 * does, and the discovered count is pinned to {@value #EXPECTED_CODEC_COUNT}. A codec that is added
 * to the registry without a value factory here fails the discovery property instead of being
 * silently skipped, which is the whole point of enumerating rather than listing.</p>
 *
 * <p>Case budget: the outer round count comes from {@link FuzzTiers#cases(String, int, int, int, int)}
 * and every round walks the full registry, so a run always covers all 25 codecs at least once. Size
 * rungs come from {@link FuzzValues#SIZE_LADDER}; the rungs above 256 are only taken from
 * {@link FuzzTiers.Tier#DEEP} up, and the truncation ladder of
 * {@link FuzzOracles#corruptAcceptOrStableFail} is only spent on encodings up to
 * {@value #MAX_CORRUPTED_PAYLOAD} bytes (it mutates every prefix {@code 0..len}, so a megabyte
 * payload would turn one case into a megabyte of reads). Larger rungs still get
 * {@link FuzzOracles#byteDeterminism} and {@link FuzzOracles#skipConsumesOne}, which are linear.</p>
 *
 * <p>{@code tries = 1} on purpose: the tier ladder, not jqwik, owns the case count, and the values
 * come from {@link FuzzValues} through {@link FuzzTiers#random(String)}, so the whole case set is a
 * function of {@code -Ddatagen.fuzz.seed}.</p>
 */
class NativeCodecCorruptionPropertyTest {

	/** Registry size the reflection walk must find; keeps a new codec from being silently untested. */
	private static final int EXPECTED_CODEC_COUNT = 25;

	/** Encodings up to this many bytes also get the full corruption ladder. */
	private static final int MAX_CORRUPTED_PAYLOAD = 512;

	/** Element count cap for object-shaped arrays, so a deep rung stays a linear amount of work. */
	private static final int MAX_OBJECT_ARRAY_ELEMENTS = 128;

	/** Size rung for the curated replay pass: small and fixed, so 4 seeds &times; 25 codecs stay below 0.5 s. */
	private static final int REPLAY_SIZE = 8;

	/** Random mutations per codec in the replay pass (the truncation ladder runs on top, as always). */
	private static final int REPLAY_MUTATIONS = 1;

	@Property(tries = 1)
	void registryExposesExactlyTheExpectedCodecCountAndEveryCodecHasAValueFactory() {
		List<Entry> codecs = discoverCodecs();
		assertEquals(EXPECTED_CODEC_COUNT, codecs.size(), gate("registry codec count"));
		for (Entry entry : codecs) {
			assertNotNull(entry.codec(), gate("codec instance for " + entry.name()));
			assertNotNull(entry.values(), gate("missing value factory for " + entry.name()));
		}
		assertEquals(EXPECTED_CODEC_COUNT,
				codecs.stream().map(Entry::name).distinct().count(), gate("distinct codec names"));
	}

	@Property(tries = 1)
	void everyRegistryCodecAcceptsCorruptionOrFailsStablyOnAllSixStorages() {
		List<Entry> codecs = discoverCodecs();
		int[] ladder = allowedLadder();
		int rounds = FuzzTiers.cases("native-codec-corruption", 2, 4, 10, 20);
		int mutations = FuzzTiers.cases("native-codec-corruption-mutations", 4);
		Random rnd = FuzzTiers.random("native-codec-corruption");
		// one caller-owned budget across the whole property: the oracle asserts it is quiescent again
		// after every mutant, which is the half of the contract that a per-read budget cannot show
		DecodeBudget budget = new DecodeBudget(FuzzOracles.LIMITS);
		for (int round = 0; round < rounds; round++) {
			for (int index = 0; index < codecs.size(); index++) {
				Entry entry = codecs.get(index);
				int size = ladder[(round + index) % ladder.length];
				Object value = entry.values().create(rnd, size);
				byte[] encoded = encode(entry, value);
				if (encoded.length <= MAX_CORRUPTED_PAYLOAD) {
					FuzzOracles.corruptAcceptOrStableFail(entry.codec(), value, rnd, mutations, budget,
							entry.name() + " corruption round=" + round + " size=" + size);
					FuzzOracles.corruptAcceptOrStableFail(entry.codec(), value, rnd, mutations,
							entry.name() + " corruption on a fresh budget round=" + round + " size=" + size);
				}
				assertEquals(0L, budget.claimedArrayElements(),
						gate(entry.name() + " left claimed array elements behind"));
				assertEquals(0L, budget.claimedPayloadBytes(),
						gate(entry.name() + " left claimed payload bytes behind"));
				assertEquals(0, budget.structuralDepth(),
						gate(entry.name() + " left a decode root open"));
			}
		}
	}

	@Property(tries = 1)
	void everyRegistryCodecSkipsExactlyOneValueOnAllSixStorages() {
		List<Entry> codecs = discoverCodecs();
		int[] ladder = allowedLadder();
		int rounds = FuzzTiers.cases("native-codec-skip", 2, 4, 10, 20);
		Random rnd = FuzzTiers.random("native-codec-skip");
		for (int round = 0; round < rounds; round++) {
			for (int index = 0; index < codecs.size(); index++) {
				Entry entry = codecs.get(index);
				int size = ladder[(round + index) % ladder.length];
				FuzzOracles.skipConsumesOne(entry.codec(), entry.values().create(rnd, size),
						entry.name() + " skip round=" + round + " size=" + size);
			}
		}
	}

	@Property(tries = 1)
	void everyRegistryCodecEncodesAndReEncodesToTheSameBytesOnAllSixStorages() {
		List<Entry> codecs = discoverCodecs();
		int[] ladder = allowedLadder();
		int rounds = FuzzTiers.cases("native-codec-determinism", 2, 5, 12, 25);
		Random rnd = FuzzTiers.random("native-codec-determinism");
		for (int round = 0; round < rounds; round++) {
			for (int index = 0; index < codecs.size(); index++) {
				Entry entry = codecs.get(index);
				int size = ladder[(round + index) % ladder.length];
				Object value = entry.values().create(rnd, size);
				FuzzOracles.byteDeterminism(entry.codec(), value,
						entry.name() + " determinism round=" + round + " size=" + size);
				FuzzOracles.roundTrip(entry.codec(), value, rnd,
						entry.name() + " round trip round=" + round + " size=" + size);
			}
		}
	}

	/**
	 * Curated-replay path (contract §4): every seed in the checked-in corpus
	 * {@code fuzz/seeds/native-codec.txt} gets ONE corruption+skip pass over the full pinned registry, at the
	 * fixed small {@value #REPLAY_SIZE} rung so the whole property stays far below 0.5 s. Results fold into
	 * the same oracle failure reporting as the generated rounds. A silently empty corpus would make this
	 * property dead code again, so its presence is asserted rather than assumed.
	 */
	@Property(tries = 1)
	void curatedReplaySeedsCorruptAndSkipEveryRegistryCodec() {
		long[] seeds = FuzzReplay.replaySeeds("native-codec");
		assertTrue(seeds.length > 0, gate("corpus fuzz/seeds/native-codec.txt must exist and hold seeds"));
		List<Entry> codecs = discoverCodecs();
		for (long seed : seeds) {
			Random rnd = new Random(seed);
			for (Entry entry : codecs) {
				Object value = entry.values().create(rnd, REPLAY_SIZE);
				FuzzOracles.corruptAcceptOrStableFail(entry.codec(), value, rnd, REPLAY_MUTATIONS,
						entry.name() + " replay seed=" + seed + " corruption");
				FuzzOracles.skipConsumesOne(entry.codec(), value,
						entry.name() + " replay seed=" + seed + " skip");
			}
		}
	}

	/**
	 * Sanity check that the drawn Int52 pool really is the valid domain, since the value factories
	 * below rely on {@link FuzzValues#int52(Random)} being accepted by {@link Int52#fromLong(long)}.
	 */
	@Property(tries = 1)
	void drawnInt52ValuesStayInTheDocumentDomain() {
		Random rnd = FuzzTiers.random("native-codec-int52-domain");
		int draws = FuzzTiers.cases("native-codec-int52-domain", 8);
		for (int i = 0; i < draws; i++) {
			long valid = FuzzValues.int52(rnd);
			assertTrue(valid >= Int52.MIN_VALUE_L && valid <= Int52.MAX_VALUE_L,
					gate("int52 out of domain: " + valid));
			long invalid = FuzzValues.int52OutOfRange(rnd);
			assertTrue(invalid < Int52.MIN_VALUE_L || invalid > Int52.MAX_VALUE_L,
					gate("int52OutOfRange accepted an in-domain value: " + invalid));
		}
	}

	/**
	 * Same for the hostile string pool: unpaired surrogates are a legal input but not a legal UTF-8
	 * round trip, so this property states where the repair happens instead of hiding it.
	 */
	@Property(tries = 1)
	void drawnStringsAreRepairedExactlyWhereUtf8CannotCarryASurrogate() {
		Random rnd = FuzzTiers.random("native-codec-string-repair");
		int cases = FuzzTiers.cases("native-codec-string-repair", 8);
		for (int i = 0; i < cases; i++) {
			String drawn = string(rnd, i % 33);
			assertEquals(drawn, surrogateSafe(drawn), gate("string() must hand out repaired strings only"));
			byte[] utf8 = drawn.getBytes(StandardCharsets.UTF_8);
			assertEquals(drawn, new String(utf8, StandardCharsets.UTF_8),
					gate("a repaired string must survive UTF-8"));
		}
	}

	/** @return the codec entries of {@link Serializers}, in name order, each with its value factory. */
	private static List<Entry> discoverCodecs() {
		Map<String, ValueFactory> factories = valueFactories();
		List<Field> fields = Arrays.stream(Serializers.class.getFields())
				.filter(field -> Modifier.isStatic(field.getModifiers()))
				.filter(field -> DataCodec.class.isAssignableFrom(field.getType()))
				.sorted(Comparator.comparing(Field::getName))
				.toList();
		assertEquals(EXPECTED_CODEC_COUNT, fields.size(),
				gate("Serializers must expose " + EXPECTED_CODEC_COUNT + " static DataCodec fields, found "
						+ fields.size() + ": " + fields.stream().map(Field::getName).toList()));
		List<Entry> result = new ArrayList<>(fields.size());
		for (Field field : fields) {
			Object value;
			try {
				value = field.get(null);
			} catch (IllegalAccessException impossible) {
				throw new AssertionError(gate("public static field must be readable: " + field.getName()),
						impossible);
			}
			assertTrue(value instanceof DataCodec, gate(field.getName() + " is not a DataCodec"));
			DataCodec<Object> codec = asCodec(field.getName(), (DataCodec<?>) value);
			ValueFactory factory = factories.get(field.getName());
			assertNotNull(factory, gate("no value factory for registry codec " + field.getName()
					+ ": add one to valueFactories() so it cannot be silently skipped"));
			result.add(new Entry(field.getName(), codec, factory));
		}
		return List.copyOf(result);
	}

	@SuppressWarnings("unchecked")
	private static DataCodec<Object> asCodec(String name, DataCodec<?> codec) {
		return (DataCodec<Object>) codec;
	}

	/** @return the size rungs this tier allows: everything up to 256, the walls only from DEEP up. */
	private static int[] allowedLadder() {
		if (FuzzTiers.tier().compareTo(FuzzTiers.Tier.DEEP) >= 0) {
			return FuzzValues.SIZE_LADDER.clone();
		}
		return Arrays.stream(FuzzValues.SIZE_LADDER).filter(size -> size <= 256).toArray();
	}

	/** @return one value factory per registry field name; a missing entry fails {@link #discoverCodecs()}. */
	private static Map<String, ValueFactory> valueFactories() {
		Map<String, ValueFactory> factories = new HashMap<>();

		factories.put("StringSerializerInstance",
				(rnd, size) -> string(rnd, size));
		factories.put("BinaryStringSerializerInstance",
				(rnd, size) -> new BinaryString(FuzzValues.bytes(rnd, Math.max(1, size))));
		factories.put("Int52SerializerInstance", (rnd, size) -> Int52.fromLong(FuzzValues.int52(rnd)));

		factories.put("NullableStringSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? NullableString.empty()
				: NullableString.of(nonEmpty(string(rnd, Math.min(size, MAX_OBJECT_ARRAY_ELEMENTS)))));
		factories.put("NullableBinaryStringSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? NullableBinaryString.empty()
				// unsigned-short prefix: the 65536 ladder rung must be clamped to 0xFFFF
				: NullableBinaryString.of(new BinaryString(
						FuzzValues.bytes(rnd, Math.max(1, Math.min(size, 0xFFFF))))));
		factories.put("NullableInt52SerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? NullableInt52.empty()
				: NullableInt52.of(Int52.fromLong(FuzzValues.int52(rnd))));
		factories.put("NullablebooleanSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullableboolean.empty()
				: Nullableboolean.of(rnd.nextBoolean()));
		factories.put("NullablebyteSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullablebyte.empty()
				: Nullablebyte.of(randomByte(rnd)));
		factories.put("NullableshortSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullableshort.empty()
				: Nullableshort.of((short) rnd.nextInt()));
		factories.put("NullablecharSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullablechar.empty()
				: Nullablechar.of((char) rnd.nextInt()));
		factories.put("NullableintSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullableint.empty()
				: Nullableint.of(rnd.nextInt()));
		factories.put("NullablelongSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullablelong.empty()
				: Nullablelong.of(rnd.nextLong()));
		factories.put("NullablefloatSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullablefloat.empty()
				: Nullablefloat.of(Float.intBitsToFloat(rnd.nextInt())));
		factories.put("NullabledoubleSerializerInstance", (rnd, size) -> rnd.nextBoolean()
				? Nullabledouble.empty()
				: Nullabledouble.of(Double.longBitsToDouble(rnd.nextLong())));

		factories.put("ArrayBinaryStringSerializerInstance", (rnd, size) -> {
			BinaryString[] result = new BinaryString[elements(rnd, size, MAX_OBJECT_ARRAY_ELEMENTS)];
			for (int i = 0; i < result.length; i++) {
				result[i] = new BinaryString(FuzzValues.bytes(rnd, rnd.nextInt(Math.max(2, size + 1))));
			}
			return result;
		});
		factories.put("ArrayStringSerializerInstance", (rnd, size) -> {
			String[] result = new String[elements(rnd, size, MAX_OBJECT_ARRAY_ELEMENTS)];
			for (int i = 0; i < result.length; i++) {
				result[i] = string(rnd, Math.min(size, 16));
			}
			return result;
		});
		factories.put("ArrayInt52SerializerInstance", (rnd, size) -> {
			Int52[] result = new Int52[elements(rnd, size, MAX_OBJECT_ARRAY_ELEMENTS)];
			for (int i = 0; i < result.length; i++) {
				result[i] = Int52.fromLong(FuzzValues.int52(rnd));
			}
			return result;
		});
		factories.put("ArraybooleanSerializerInstance", (rnd, size) -> {
			boolean[] result = new boolean[elements(rnd, size, Integer.MAX_VALUE)];
			for (int i = 0; i < result.length; i++) {
				result[i] = rnd.nextBoolean();
			}
			return result;
		});
		factories.put("ArraybyteSerializerInstance", (rnd, size) -> FuzzValues.bytes(rnd,
				elements(rnd, size, Integer.MAX_VALUE)));
		factories.put("ArrayshortSerializerInstance", (rnd, size) -> {
			short[] result = new short[elements(rnd, size, Integer.MAX_VALUE)];
			for (int i = 0; i < result.length; i++) {
				result[i] = (short) rnd.nextInt();
			}
			return result;
		});
		factories.put("ArraycharSerializerInstance", (rnd, size) -> {
			char[] result = new char[elements(rnd, size, Integer.MAX_VALUE)];
			for (int i = 0; i < result.length; i++) {
				result[i] = (char) rnd.nextInt();
			}
			return result;
		});
		factories.put("ArrayintSerializerInstance", (rnd, size) -> {
			int[] result = new int[elements(rnd, size, Integer.MAX_VALUE)];
			for (int i = 0; i < result.length; i++) {
				result[i] = rnd.nextInt();
			}
			return result;
		});
		factories.put("ArraylongSerializerInstance", (rnd, size) -> {
			long[] result = new long[elements(rnd, size, Integer.MAX_VALUE)];
			for (int i = 0; i < result.length; i++) {
				result[i] = rnd.nextLong();
			}
			return result;
		});
		factories.put("ArrayfloatSerializerInstance", (rnd, size) -> {
			float[] result = new float[elements(rnd, size, Integer.MAX_VALUE)];
			for (int i = 0; i < result.length; i++) {
				result[i] = Float.intBitsToFloat(rnd.nextInt());
			}
			return result;
		});
		factories.put("ArraydoubleSerializerInstance", (rnd, size) -> {
			double[] result = new double[elements(rnd, size, Integer.MAX_VALUE)];
			for (int i = 0; i < result.length; i++) {
				result[i] = Double.longBitsToDouble(rnd.nextLong());
			}
			return result;
		});

		// every element type of the registry, spelled out so a new registry entry cannot hide
		assertEquals(RegistryFieldNames.ALL.length, factories.size(),
				gate("valueFactories() must stay in step with the registry"));
		for (String name : RegistryFieldNames.ALL) {
			assertTrue(factories.containsKey(name), gate("valueFactories() is missing " + name));
		}
		return factories;
	}

	/** Draws a hostile string of exactly {@code size} UTF-16 units, with unpaired surrogates repaired. */
	private static String string(Random rnd, int size) {
		if (size <= 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder(size);
		while (sb.length() < size) {
			String drawn = FuzzValues.string(rnd);
			sb.append(drawn.isEmpty() ? "x\u0000" : drawn);
		}
		return surrogateSafe(sb.substring(0, size));
	}

	/** @return {@code text} or a non-empty stand-in: the value contract requires a rendered form. */
	private static String nonEmpty(String text) {
		return text.isEmpty() ? "\u0000" : text;
	}

	/** @return a tier-independent element count for an array codec, capped by {@code cap}. */
	private static int elements(Random rnd, int size, int cap) {
		return Math.min(size, cap);
	}

	private static byte randomByte(Random rnd) {
		return rnd.nextBoolean()
				? (byte) FuzzValues.HOSTILE_BYTES[rnd.nextInt(FuzzValues.HOSTILE_BYTES.length)]
				: (byte) rnd.nextInt();
	}

	/**
	 * @return {@code text} with every unpaired surrogate replaced by {@code U+FFFD}, which is what
	 * {@code StringSerializer} legitimately produces on decode over UTF-8
	 */
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

	/** @return the exact wire form of {@code value} under {@code codec}. */
	private static byte[] encode(Entry entry, Object value) {
		BufDataOutput output = BufDataOutput.create();
		entry.codec().serialize(output, value);
		Buf encoded = output.asList();
		return FuzzStorages.bytesOf(new View(FuzzStorages.Kind.FORWARD_STREAM, encoded, 0, encoded.size()));
	}

	/** Every failure message states the gate it ran under. */
	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}

	/** Draws one value of the codec's own type at the requested size rung. */
	@FunctionalInterface
	private interface ValueFactory {
		Object create(Random rnd, int size);
	}

	/** One discovered registry entry. */
	private record Entry(String name, DataCodec<Object> codec, ValueFactory values) {}

	/**
	 * The registry field names, as a compile-time list: {@link #valueFactories()} is checked against
	 * it, and {@link #discoverCodecs()} is checked against the class itself, so both a new codec and a
	 * stale factory map are reported rather than skipped.
	 */
	private static final class RegistryFieldNames {
		private static final String[] ALL = {
				"StringSerializerInstance",
				"BinaryStringSerializerInstance",
				"Int52SerializerInstance",
				"NullableStringSerializerInstance",
				"NullableBinaryStringSerializerInstance",
				"NullableInt52SerializerInstance",
				"NullablebooleanSerializerInstance",
				"NullablebyteSerializerInstance",
				"NullableshortSerializerInstance",
				"NullablecharSerializerInstance",
				"NullableintSerializerInstance",
				"NullablelongSerializerInstance",
				"NullablefloatSerializerInstance",
				"NullabledoubleSerializerInstance",
				"ArrayBinaryStringSerializerInstance",
				"ArrayStringSerializerInstance",
				"ArrayInt52SerializerInstance",
				"ArraybooleanSerializerInstance",
				"ArraybyteSerializerInstance",
				"ArrayshortSerializerInstance",
				"ArraycharSerializerInstance",
				"ArrayintSerializerInstance",
				"ArraylongSerializerInstance",
				"ArrayfloatSerializerInstance",
				"ArraydoubleSerializerInstance",
		};

		private RegistryFieldNames() {
		}
	}
}
