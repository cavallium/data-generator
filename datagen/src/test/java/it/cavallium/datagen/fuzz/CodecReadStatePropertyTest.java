package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.CodecReadState;
import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.ReadSession;
import it.cavallium.datagen.nativedata.Serializers;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.jqwik.api.Property;

/**
 * {@link CodecReadState} session reuse: what the cache really keys on, and exactly how many sessions it
 * installs.
 *
 * <p>The code keys a session by <em>logical type name</em> and stores the codec's {@code Class} next to
 * it. That produces three guarantees, and this test asserts precisely those — not a stronger promise the
 * code never made:</p>
 * <ol>
 *     <li>the same logical name plus the same codec class returns the <em>same</em> session instance,
 *     however many codec objects ask for it;</li>
 *     <li>the codec identity does not matter at all: distinct historical instances of one class — the
 *     shape a generated {@code <Type>V3Serializer}/{@code <Type>V4Serializer} pair has when both are the
 *     same generated class — share the one session created for the first of them, and the later
 *     instances' {@link DataCodec#newReadSession()} is never even called;</li>
 *     <li>an incompatible class under an already-installed name is refused with
 *     {@link IllegalStateException} naming the logical type, and the failed request installs
 *     nothing.</li>
 * </ol>
 *
 * <p>{@link CodecReadState#initializedSessionCount()} is asserted against an independent model after
 * every request, so a slot that is created twice, or a failed request that consumes a name, is caught
 * rather than noticed later. Null handling is asserted as the code implements it:
 * {@code Objects.requireNonNull} on both arguments, and on the result of {@code newReadSession()}.</p>
 */
class CodecReadStatePropertyTest {

	@Property(tries = 1)
	void oneLogicalNameAlwaysResolvesToOneSessionInstance() {
		Random rnd = FuzzTiers.random("codec-read-state-reuse");
		CodecReadState state = new CodecReadState();
		Map<String, ReadSession<String>> model = new HashMap<>();
		Map<String, Class<?>> modelClasses = new HashMap<>();
		Calls calls = new Calls();
		assertEquals(0, state.initializedSessionCount(), gate("a fresh state owns no session"));

		int requests = FuzzTiers.cases("codec-read-state-reuse", 40);
		Set<String> askedNames = new HashSet<>();
		for (int request = 0; request < requests; request++) {
			String name = "logical-" + rnd.nextInt(7);
			askedNames.add(name);
			// a fresh codec object every time: the cache must never key on that identity
			DataCodec<String> codec = nextCodec(rnd, calls);
			ReadSession<String> known = model.get(name);
			Class<?> knownClass = modelClasses.get(name);
			if (known != null && !knownClass.equals(codec.getClass())) {
				// a second class under one name is a refusal, not a replacement
				IllegalStateException refusal = assertThrows(IllegalStateException.class,
						() -> state.session(name, codec),
						gate("class change under one name must be refused, request=" + request));
				assertTrue(refusal.getMessage().contains(name),
						gate("the refusal must name the logical type: " + refusal.getMessage()));
				assertEquals(model.size(), state.initializedSessionCount(),
						gate("a refusal must not install a slot, request=" + request));
				continue;
			}
			ReadSession<String> session = state.session(name, codec);
			assertNotNull(session, gate("session must never be null"));
			if (known == null) {
				model.put(name, session);
				modelClasses.put(name, codec.getClass());
				assertEquals(calls.created, model.size(),
						gate("exactly one session may be created per logical name, request=" + request));
			} else {
				assertSame(known, session, gate("name " + name + " must keep its first session, request="
						+ request));
				assertEquals(codec.getClass(), knownClass,
						gate("a same-class request must not replace the session"));
			}
			assertEquals(model.size(), state.initializedSessionCount(),
					gate("initializedSessionCount must equal the model, request=" + request));
		}
		assertEquals(askedNames.size(), state.initializedSessionCount(),
				gate("every asked-for name must own exactly one session"));
		assertEquals(model.size(), calls.created, gate("no session may be created twice"));
	}

	@Property(tries = 1)
	void distinctHistoricalInstancesOfOneLogicalTypeShareTheFirstSessionAndStillDecode() {
		Random rnd = FuzzTiers.random("codec-read-state-history");
		CodecReadState state = new CodecReadState();
		Calls calls = new Calls();
		int generations = FuzzTiers.cases("codec-read-state-history", 3);

		// a fresh object per "schema version", exactly like distinct generated historical codecs
		List<HistoricalStringCodec> versions = new ArrayList<>(generations);
		for (int version = 0; version < generations; version++) {
			versions.add(new HistoricalStringCodec(version, calls));
		}
		ReadSession<String> first = state.session("MyType", versions.get(0));
		assertEquals(1, calls.created, gate("only the first codec instance may build a session"));
		for (HistoricalStringCodec version : versions) {
			assertSame(first, state.session("MyType", version),
					gate("a later historical instance must reuse the session of the earlier one"));
		}
		assertEquals(1, calls.created, gate("no historical instance may build another session"));
		assertEquals(1, state.initializedSessionCount(), gate("one logical type, one slot"));

		// and the shared session is not a stub: it decodes the wire form of every one of those instances
		for (HistoricalStringCodec version : versions) {
			// StringSerializer legitimately replaces an unpaired surrogate with U+FFFD, so the value is
			// repaired before it is compared: the shared session must reproduce exactly that
			String value = "v" + version.version() + " " + surrogateSafe(FuzzValues.string(rnd));
			byte[] encoded = encode(version, value);
			assertEquals(value, readWith(first, encoded),
					gate("the shared session must decode what historical instance " + version.version()
							+ " wrote"));
		}
	}

	@Property(tries = 1)
	void anIncompatibleClassUnderTheSameNameIsRefusedAndConsumesNoName() {
		Random rnd = FuzzTiers.random("codec-read-state-conflict");
		int cases = FuzzTiers.cases("codec-read-state-conflict", 8);
		Calls calls = new Calls();
		for (int testCase = 0; testCase < cases; testCase++) {
			CodecReadState state = new CodecReadState();
			int before = calls.created;
			String name = "conflict-" + testCase;
			ReadSession<String> installed = state.session(name, new HistoricalStringCodec(testCase, calls));
			assertEquals(1, state.initializedSessionCount(), gate("the first request installs one slot"));

			// a different codec class under the same logical name: refused, loudly, and nothing changes
			DataCodec<String> incompatible = testCase % 2 == 0
					? new OtherStringCodec(calls)
					: asStringCodec(Serializers.StringSerializerInstance);
			IllegalStateException failure = assertThrows(IllegalStateException.class,
					() -> state.session(name, incompatible),
					gate("an incompatible class must be refused, testCase=" + testCase));
			assertTrue(failure.getMessage().contains(name),
					gate("the refusal must name the logical type: " + failure.getMessage()));
			assertTrue(failure.getMessage().contains(installed.getClass().getName())
							|| failure.getMessage().contains(incompatible.getClass().getName()),
					gate("the refusal should name both codec classes: " + failure.getMessage()));
			assertEquals(1, state.initializedSessionCount(),
					gate("a refused request must not install a slot, testCase=" + testCase));
			assertEquals(before + 1, calls.created, gate("a refused request must not build a session"));
			assertSame(installed, state.session(name, new HistoricalStringCodec(testCase + 1, calls)),
					gate("the original session must survive a refusal"));

			// the same name may still be reused, and a different name gets its own slot
			assertEquals(before + 1, calls.created, gate("reuse must not build a session"));
			state.session(name + "-other", new OtherStringCodec(calls));
			assertEquals(2, state.initializedSessionCount(), gate("a new name owns a new slot"));
		}
	}

	@Property(tries = 1)
	void nullNameNullCodecAndNullSessionFactoryFailWithoutConsumingAName() {
		CodecReadState state = new CodecReadState();
		Calls calls = new Calls();
		assertThrows(NullPointerException.class, () -> state.session(null, new OtherStringCodec(calls)),
				gate("a null logical name must be rejected"));
		assertThrows(NullPointerException.class, () -> state.session("no-codec", null),
				gate("a null codec must be rejected"));
		assertThrows(NullPointerException.class, () -> state.session("null-session", new NoSessionCodec()),
				gate("a codec factory returning null must be rejected"));
		assertEquals(0, state.initializedSessionCount(),
				gate("no failed request may consume a name"));
		assertEquals(0, calls.created, gate("no failed request may build a session"));

		// and the state still works afterwards
		ReadSession<String> session = state.session("after-failure", new OtherStringCodec(calls));
		assertNotNull(session, gate("a valid request after a rejection must succeed"));
		assertEquals(1, state.initializedSessionCount(), gate("exactly the valid request installed a slot"));
	}

	@Property(tries = 1)
	void everyBudgetGetsItsOwnSessionState() {
		CodecReadState state = new CodecReadState();
		DecodeLimits limits = new DecodeLimits(64, 4_096, 4_096L, 65_536L, 4);
		Calls calls = new Calls();
		it.cavallium.datagen.DecodeBudget first = new it.cavallium.datagen.DecodeBudget(limits);
		it.cavallium.datagen.DecodeBudget second = new it.cavallium.datagen.DecodeBudget(limits);
		assertEquals(0, first.codecReadState().initializedSessionCount(),
				gate("a budget starts with an empty session state"));
		assertNotSame(first.codecReadState(), second.codecReadState(),
				gate("two reader lanes must not share one session state"));
		assertSame(first.codecReadState(), first.codecReadState(),
				gate("one budget always hands out its own state instance"));

		ReadSession<String> session = first.codecReadState().session("Lane", new OtherStringCodec(calls));
		assertSame(session, first.codecReadState().session("Lane", new OtherStringCodec(calls)),
				gate("one lane reuses its own session"));
		assertEquals(1, first.codecReadState().initializedSessionCount(), gate("one lane, one session"));
		assertEquals(0, second.codecReadState().initializedSessionCount(),
				gate("the other lane must still be untouched"));
		assertEquals(0, state.initializedSessionCount(),
				gate("a standalone state is independent of every budget's own"));
	}

	/** @return a random codec class, so a name conflict and a reuse both occur by construction. */
	private static DataCodec<String> nextCodec(Random rnd, Calls calls) {
		return switch (rnd.nextInt(3)) {
			case 0 -> new HistoricalStringCodec(rnd.nextInt(), calls);
			case 1 -> new OtherStringCodec(calls);
			default -> new HistoricalStringCodec(rnd.nextInt(), calls);
		};
	}

	@SuppressWarnings("unchecked")
	private static DataCodec<String> asStringCodec(DataCodec<?> codec) {
		return (DataCodec<String>) codec;
	}

	/** @return the wire form of {@code value} as {@code codec} writes it. */
	private static byte[] encode(DataCodec<String> codec, String value) {
		BufDataOutput output = BufDataOutput.create();
		codec.serialize(output, value);
		Buf encoded = output.asList();
		return FuzzStorages.bytesOf(
				new FuzzStorages.View(FuzzStorages.Kind.FORWARD_STREAM, encoded, 0, encoded.size()));
	}

	/** @return the value one session decodes out of {@code encoded}. */
	private static String readWith(ReadSession<String> session, byte[] encoded) {
		BufDataCursor cursor = new BufDataCursor(DecodeLimits.unlimited());
		cursor.bind(Buf.wrap(encoded), 0, encoded.length);
		try {
			return session.read(cursor);
		} finally {
			cursor.unbind();
		}
	}

	/** @return {@code text} with every unpaired surrogate replaced by {@code U+FFFD}, as UTF-8 decode does */
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

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}

	/** Counts how many sessions the codecs in one property actually built. */
	private static final class Calls {
		int created;
	}

	/** A string codec that delegates to the registry codec but has its own class and session identity. */
	private static class StringCodecSupport implements DataCodec<String> {

		private final Calls calls;

		StringCodecSupport(Calls calls) {
			this.calls = calls;
		}

		@Override
		public void serialize(SafeDataOutput dataOutput, String data) {
			Serializers.StringSerializerInstance.serialize(dataOutput, data);
		}

		@Override
		public String read(SafeDataInput dataInput) {
			return Serializers.StringSerializerInstance.read(dataInput);
		}

		@Override
		public void skip(SafeDataInput dataInput) {
			Serializers.StringSerializerInstance.skip(dataInput);
		}

		protected final ReadSession<String> newSession() {
			calls.created++;
			return new ReadSession<>() {
				@Override
				protected String decode(SafeDataInput input) {
					return StringCodecSupport.this.read(input);
				}

				@Override
				protected void skipValue(SafeDataInput input) {
					StringCodecSupport.this.skip(input);
				}

				@Override
				protected void clearTransientState() {
					// stateless
				}
			};
		}
	}

	/** One "generated historical version": a distinct instance, same class, same wire format. */
	private static final class HistoricalStringCodec extends StringCodecSupport {

		private final int version;

		HistoricalStringCodec(int version, Calls calls) {
			super(calls);
			this.version = version;
		}

		int version() {
			return version;
		}

		@Override
		public ReadSession<String> newReadSession() {
			return newSession();
		}
	}

	/** A second, unrelated codec class for the same logical wire type. */
	private static final class OtherStringCodec extends StringCodecSupport {

		OtherStringCodec(Calls calls) {
			super(calls);
		}

		@Override
		public ReadSession<String> newReadSession() {
			return newSession();
		}
	}

	/** A codec whose factory returns {@code null}, which the state must refuse. */
	private static final class NoSessionCodec extends StringCodecSupport {

		NoSessionCodec() {
			super(new Calls());
		}

		@Override
		public ReadSession<String> newReadSession() {
			return null;
		}
	}
}
