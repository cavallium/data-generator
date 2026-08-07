package it.cavallium.datagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.lang.reflect.Field;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Deep state-machine fuzzing for reader-owned codec sessions and decode accounting. */
class DataCodecSessionDeepFuzzTest {

	private static final long SESSION_SEED = 0x58C1_2D7A_904E_B36FL;
	private static final long RESERVED_SEED = 0x2A94_F70C_61D8_3BE5L;
	private static final long STATE_SEED = 0x739E_04B5_C218_6AFDL;
	private static final int SESSION_CASES = 40_000;
	private static final int RESERVED_CASES = 25_000;
	private static final int STATE_CASES = 20_000;
	private static final DecodeLimits GENEROUS =
			new DecodeLimits(1_024, 1_024, 1_000_000, 1_000_000, 64);

	@Test
	void sessionEntriesClearTransientStateBalanceRootsAndRecoverAfterEveryFailureKind() {
		var random = new Random(SESSION_SEED);
		var session = new LifecycleSession();
		for (int caseIndex = 0; caseIndex < SESSION_CASES; caseIndex++) {
			int value = random.nextInt();
			int mode = random.nextInt(6);
			BufDataInput input = input(mode, value, GENEROUS);
			DecodeBudget budget = input.decodeBudget();
			int clearsBefore = session.clears;
			String diagnostic = diagnostic(SESSION_SEED, caseIndex, mode, value);

			if (mode == 0 || mode == 5) {
				assertEquals(value, session.read(input), diagnostic);
				assertEquals(Integer.BYTES + 1, input.position(), diagnostic);
			} else if (mode == 1) {
				assertThrows(DeliberateRuntimeFailure.class, () -> session.read(input), diagnostic);
			} else if (mode == 2) {
				assertThrows(DeliberateError.class, () -> session.read(input), diagnostic);
			} else if (mode == 3) {
				assertThrows(MalformedDataException.class, () -> session.read(input), diagnostic);
			} else {
				session.failClear = true;
				assertThrows(ClearFailure.class, () -> session.read(input), diagnostic);
			}

			assertEquals(clearsBefore + 1, session.clears, diagnostic);
			assertNull(session.retainedInput, diagnostic);
			assertEquals(0, budget.structuralDepth(), diagnostic);
			assertRootEntries(budget, 0, diagnostic);

			BufDataInput recovery = input(0, value ^ 0x5A5A_5A5A, GENEROUS);
			assertEquals(value ^ 0x5A5A_5A5A, session.read(recovery), diagnostic);
			assertNull(session.retainedInput, diagnostic);
			assertRootEntries(recovery.decodeBudget(), 0, diagnostic);
		}
	}

	@Test
	void skipAndReservedDecodeFuzzParentPositionsTrailingBytesAndReusableChildCleanup() {
		var random = new Random(RESERVED_SEED);
		var session = new LifecycleSession();
		for (int caseIndex = 0; caseIndex < RESERVED_CASES; caseIndex++) {
			int value = random.nextInt();
			int prefix = random.nextInt(17);
			int suffix = random.nextInt(17);
			byte[] bytes = new byte[prefix + 1 + Integer.BYTES + 1 + suffix];
			random.nextBytes(bytes);
			bytes[prefix] = 0;
			putInt(bytes, prefix + 1, value);
			BufDataInput parent = BufDataInput.create(Buf.wrap(bytes), GENEROUS);
			parent.reserve(bytes.length);
			int parentPosition = parent.position();
			String diagnostic = diagnostic(RESERVED_SEED, caseIndex, prefix, suffix);

			assertEquals(value, session.readReserved(parent, prefix, 1 + Integer.BYTES), diagnostic);
			assertEquals(parentPosition, parent.position(), diagnostic);
			assertNull(session.retainedInput, diagnostic);
			assertRootEntries(parent.decodeBudget(), 0, diagnostic);

			assertThrows(MalformedDataException.class,
					() -> session.readReserved(parent, prefix, Integer.BYTES + 2), diagnostic);
			assertEquals(parentPosition, parent.position(), diagnostic);
			assertNull(session.retainedInput, diagnostic);
			assertRootEntries(parent.decodeBudget(), 0, diagnostic);

			BufDataInput skipInput = input(0, value, GENEROUS);
			int clearsBefore = session.clears;
			session.skip(skipInput);
			assertEquals(1 + Integer.BYTES, skipInput.position(), diagnostic);
			assertEquals(clearsBefore + 1, session.clears, diagnostic);
			assertNull(session.retainedInput, diagnostic);
			assertRootEntries(skipInput.decodeBudget(), 0, diagnostic);

			BufDataInput truncated = BufDataInput.create(Buf.wrap(new byte[] {0, 1, 2}), GENEROUS);
			assertThrows(MalformedDataException.class, () -> session.skip(truncated), diagnostic);
			assertNull(session.retainedInput, diagnostic);
			assertRootEntries(truncated.decodeBudget(), 0, diagnostic);
		}
	}

	@Test
	void nestedSessionsShareOneRootBudgetAccumulateWithinItAndResetAtTheNextOuterEntry() {
		var random = new Random(STATE_SEED);
		for (int caseIndex = 0; caseIndex < STATE_CASES; caseIndex++) {
			int outerArray = random.nextInt(33);
			int childArray = random.nextInt(33);
			int outerPayload = random.nextInt(33);
			int childPayload = random.nextInt(33);
			DecodeLimits limits = new DecodeLimits(64, 64, 128, 128, 8);
			BufDataInput input = BufDataInput.create(Buf.create(), limits);
			ClaimingSession child = new ClaimingSession(childArray, childPayload, null);
			ClaimingSession parent = new ClaimingSession(outerArray, outerPayload, child);
			String diagnostic = diagnostic(STATE_SEED, caseIndex, outerArray, childArray);

			assertEquals(outerArray + childArray, parent.read(input), diagnostic);
			assertEquals((long) outerArray + childArray,
					input.decodeBudget().claimedArrayElements(), diagnostic);
			assertEquals((long) outerPayload + childPayload,
					input.decodeBudget().claimedPayloadBytes(), diagnostic);
			assertRootEntries(input.decodeBudget(), 0, diagnostic);
			assertEquals(1, parent.clears, diagnostic);
			assertEquals(1, child.clears, diagnostic);

			assertEquals(outerArray + childArray, parent.read(input), diagnostic);
			assertEquals((long) outerArray + childArray,
					input.decodeBudget().claimedArrayElements(), diagnostic);
			assertEquals((long) outerPayload + childPayload,
					input.decodeBudget().claimedPayloadBytes(), diagnostic);
			assertRootEntries(input.decodeBudget(), 0, diagnostic);
		}
	}

	@Test
	void codecReadStateFuzzesLogicalKeysClassCompatibilityLazyCreationAndNullFactories() {
		var random = new Random(STATE_SEED ^ Long.MIN_VALUE);
		var state = new CodecReadState();
		var expected = new java.util.HashMap<String, ReadSession<Integer>>();
		assertEquals(0, state.initializedSessionCount());

		for (int caseIndex = 0; caseIndex < STATE_CASES; caseIndex++) {
			String logicalType = "logical-" + random.nextInt(512);
			ClassACodec codec = new ClassACodec(random.nextInt());
			ReadSession<Integer> actual = state.session(logicalType, codec);
			ReadSession<Integer> first = expected.putIfAbsent(logicalType, actual);
			String diagnostic = diagnostic(STATE_SEED, caseIndex, logicalType.hashCode(), codec.identity);
			if (first != null) assertSame(first, actual, diagnostic);
			assertEquals(expected.size(), state.initializedSessionCount(), diagnostic);
			assertSame(actual, state.session(logicalType, new ClassACodec(random.nextInt())), diagnostic);
		}

		state.session("conflict", new ClassACodec(0));
		assertThrows(IllegalStateException.class,
				() -> state.session("conflict", new ClassBCodec()));
		assertEquals(expected.size() + 1, state.initializedSessionCount());
		assertThrows(NullPointerException.class, () -> state.session(null, new ClassACodec(1)));
		assertThrows(NullPointerException.class, () -> state.session("null-codec", null));
		assertThrows(NullPointerException.class,
				() -> state.session("null-session", new NullSessionCodec()));
		assertEquals(expected.size() + 1, state.initializedSessionCount(),
				"failed factories must not install a slot");
	}

	@Test
	void decodeLimitConstructorsAndArithmeticOverflowPathsRemainAtomic() throws Exception {
		for (int field = 0; field < 5; field++) {
			long[] values = {1, 1, 1, 1, 1};
			values[field] = -1;
			assertThrows(IllegalArgumentException.class, () -> new DecodeLimits(
					(int) values[0], (int) values[1], values[2], values[3], (int) values[4]));
		}
		DecodeLimits unlimited = DecodeLimits.unlimited();
		assertSame(unlimited, DecodeLimits.unlimited());
		assertEquals(Integer.MAX_VALUE, unlimited.maximumElementsPerArray());
		assertEquals(Integer.MAX_VALUE, unlimited.maximumBytesPerPayload());
		assertEquals(Long.MAX_VALUE, unlimited.maximumCumulativeArrayElements());
		assertEquals(Long.MAX_VALUE, unlimited.maximumCumulativePayloadBytes());
		assertEquals(Integer.MAX_VALUE, unlimited.maximumStructuralNestingDepth());

		DecodeBudget arrayOverflow = new DecodeBudget(unlimited);
		arrayOverflow.enterRoot();
		setLong(arrayOverflow, "claimedArrayElements", Long.MAX_VALUE);
		assertThrows(DecodeLimitExceededException.class, () -> arrayOverflow.claimArrayElements(1));
		assertEquals(Long.MAX_VALUE, arrayOverflow.claimedArrayElements());
		arrayOverflow.exitRoot();

		DecodeBudget payloadOverflow = new DecodeBudget(unlimited);
		payloadOverflow.enterRoot();
		setLong(payloadOverflow, "claimedPayloadBytes", Long.MAX_VALUE);
		assertThrows(DecodeLimitExceededException.class, () -> payloadOverflow.claimPayloadBytes(1));
		assertEquals(Long.MAX_VALUE, payloadOverflow.claimedPayloadBytes());
		payloadOverflow.exitRoot();

		DecodeBudget rootOverflow = new DecodeBudget(unlimited);
		setInt(rootOverflow, "rootEntries", Integer.MAX_VALUE);
		assertThrows(ArithmeticException.class, rootOverflow::enterRoot);
		assertRootEntries(rootOverflow, Integer.MAX_VALUE, "root entry overflow must be atomic");

		DecodeBudget depthOverflow = new DecodeBudget(unlimited);
		depthOverflow.enterRoot();
		setInt(depthOverflow, "structuralDepth", Integer.MAX_VALUE);
		assertThrows(ArithmeticException.class, depthOverflow::enterStructure);
		assertEquals(Integer.MAX_VALUE, depthOverflow.structuralDepth());
		assertRootEntries(depthOverflow, 1, "failed nested structure must unwind its root entry");
		setInt(depthOverflow, "structuralDepth", 0);
		depthOverflow.exitRoot();

		DecodeBudget inactive = new DecodeBudget(unlimited);
		assertThrows(IllegalStateException.class, inactive::exitRoot);
		assertThrows(IllegalStateException.class, inactive::exitStructure);
		assertThrows(MalformedDataException.class, () -> inactive.claimArrayElements(-1));
		assertThrows(MalformedDataException.class, () -> inactive.claimPayloadBytes(-1));
		assertEquals(0, inactive.claimedArrayElements());
		assertEquals(0, inactive.claimedPayloadBytes());
	}

	@Test
	void simpleInitializersAndUpgradersDelegateExactlyOnceWithTheSuppliedObjects() {
		var initializer = new DataInitializerSimple<Object>() {
			int calls;
			@Override public Object initialize() {
				calls++;
				return this;
			}
		};
		DataInitializer<DataContextNone, Object> initializerContract = initializer;
		assertSame(initializer, initializerContract.initialize(DataContextNone.INSTANCE));
		assertEquals(1, initializer.calls);

		var upgrader = new DataUpgraderSimple<Object, Object>() {
			int calls;
			Object seen;
			@Override public Object upgrade(Object data) {
				calls++;
				seen = data;
				return data;
			}
		};
		Object value = new Object();
		assertSame(value, upgrader.upgrade(DataContextNone.INSTANCE, value));
		assertSame(value, upgrader.seen);
		assertEquals(1, upgrader.calls);
		assertSame(DataContextNone.INSTANCE, DataContextNone.INSTANCE);
	}

	@Test
	void markerCodecMetadataFieldsExceptionsAndReaderNullContractsCoverTheRemainingCoreSurface() {
		CommonField field = new CommonField("payload", "java.lang.String", true);
		assertEquals("payload", field.fieldName);
		assertEquals("java.lang.String", field.fieldType);
		assertEquals(true, field.hasSetter);

		RuntimeException cause = new RuntimeException("cause");
		MalformedDataException malformed = new MalformedDataException("malformed", cause);
		DecodeLimitExceededException limited = new DecodeLimitExceededException("limited", cause);
		ValueTooLargeException tooLarge = new ValueTooLargeException("large", cause);
		assertEquals("malformed", malformed.getMessage());
		assertSame(cause, malformed.getCause());
		assertEquals("limited", limited.getMessage());
		assertSame(cause, limited.getCause());
		assertEquals("large", tooLarge.getMessage());
		assertSame(cause, tooLarge.getCause());
		assertEquals(true, new NotSerializableException() instanceof UnsupportedOperationException);

		FixedDataCodec<Integer> fixed = new FixedDataCodec<>() {
			@Override public int fixedSize() { return Integer.BYTES; }
			@Override public void serialize(SafeDataOutput output, Integer data) { output.writeInt(data); }
			@Override public Integer read(SafeDataInput input) { return input.readInt(); }
			@Override public void skip(SafeDataInput input) { ProjectionReadSupport.skipBytes(input, Integer.BYTES); }
		};
		assertEquals(Integer.BYTES, fixed.fixedSize());
		assertEquals(0x1020_3040, fixed.newReader(GENEROUS).read(intBuf(0x1020_3040)));
		assertThrows(NullPointerException.class, () -> fixed.newReader(null));
		DataCodec.Reader<Integer> reader = fixed.newReader(GENEROUS);
		assertThrows(NullPointerException.class, () -> reader.read(null));
		assertThrows(NullPointerException.class, () -> new NullSessionCodec().newReader(GENEROUS));
	}

	private static BufDataInput input(int mode, int value, DecodeLimits limits) {
		BufDataOutput output = BufDataOutput.create(1 + Integer.BYTES);
		output.writeByte(mode);
		output.writeInt(value);
		return BufDataInput.create(output.asList(), limits);
	}

	private static Buf intBuf(int value) {
		BufDataOutput output = BufDataOutput.create(Integer.BYTES);
		output.writeInt(value);
		return output.asList();
	}

	private static void putInt(byte[] bytes, int offset, int value) {
		bytes[offset] = (byte) (value >>> 24);
		bytes[offset + 1] = (byte) (value >>> 16);
		bytes[offset + 2] = (byte) (value >>> 8);
		bytes[offset + 3] = (byte) value;
	}

	private static void assertRootEntries(DecodeBudget budget, int expected, String diagnostic) {
		try {
			Field field = DecodeBudget.class.getDeclaredField("rootEntries");
			field.setAccessible(true);
			assertEquals(expected, field.getInt(budget), diagnostic);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void setLong(Object target, String fieldName, long value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.setLong(target, value);
	}

	private static void setInt(Object target, String fieldName, int value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.setInt(target, value);
	}

	private static String diagnostic(long seed, int caseIndex, int first, int second) {
		return "seed=" + seed + ", case=" + caseIndex + ", first=" + first + ", second=" + second;
	}

	private static final class LifecycleSession extends ReadSession<Integer> {
		private SafeDataInput retainedInput;
		private int clears;
		private boolean failClear;

		@Override
		protected Integer decode(SafeDataInput input) {
			retainedInput = input;
			int mode = input.readUnsignedByte();
			if (mode == 1) throw new DeliberateRuntimeFailure();
			if (mode == 2) throw new DeliberateError();
			if (mode == 3) {
				input.readLong();
				throw new AssertionError("truncated read unexpectedly succeeded");
			}
			return input.readInt();
		}

		@Override
		protected void skipValue(SafeDataInput input) {
			retainedInput = input;
			ProjectionReadSupport.skipBytes(input, 1 + Integer.BYTES);
		}

		@Override
		protected void clearTransientState() {
			retainedInput = null;
			clears++;
			if (failClear) {
				failClear = false;
				throw new ClearFailure();
			}
		}
	}

	private static final class ClaimingSession extends ReadSession<Integer> {
		private final int arrayElements;
		private final int payloadBytes;
		private final ClaimingSession child;
		private int clears;

		private ClaimingSession(int arrayElements, int payloadBytes, ClaimingSession child) {
			this.arrayElements = arrayElements;
			this.payloadBytes = payloadBytes;
			this.child = child;
		}

		@Override
		protected Integer decode(SafeDataInput input) {
			input.decodeBudget().claimArrayElements(arrayElements);
			input.decodeBudget().claimPayloadBytes(payloadBytes);
			return arrayElements + (child == null ? 0 : child.read(input));
		}

		@Override
		protected void skipValue(SafeDataInput input) {
			decode(input);
		}

		@Override
		protected void clearTransientState() {
			clears++;
		}
	}

	private static class ClassACodec implements DataCodec<Integer> {
		private final int identity;
		private ClassACodec(int identity) { this.identity = identity; }
		@Override public void serialize(SafeDataOutput output, Integer data) { output.writeInt(data); }
		@Override public Integer read(SafeDataInput input) { return input.readInt(); }
		@Override public void skip(SafeDataInput input) { ProjectionReadSupport.skipBytes(input, 4); }
	}

	private static final class ClassBCodec extends ClassACodec {
		private ClassBCodec() { super(0); }
	}

	private static final class NullSessionCodec extends ClassACodec {
		private NullSessionCodec() { super(0); }
		@Override public ReadSession<Integer> newReadSession() { return null; }
	}

	private static final class DeliberateRuntimeFailure extends RuntimeException {}
	private static final class DeliberateError extends Error {}
	private static final class ClearFailure extends RuntimeException {}
}
