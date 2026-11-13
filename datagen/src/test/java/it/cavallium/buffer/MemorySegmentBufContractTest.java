package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.stream.Stream;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.bytes.BytePredicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MemorySegmentBufContractTest {

	@Test
	void heapViewsAreExactAndZeroCopy() {
		byte[] bytes = {10, 20, 30, 40, 50};
		Buf slice = Buf.wrap(bytes).subList(1, 4);

		MemorySegment segment = slice.asMemorySegmentStrict();
		assertNotNull(segment);
		assertEquals(3, segment.byteSize());
		segment.set(ValueLayout.JAVA_BYTE, 1, (byte) 99);
		assertEquals(99, bytes[2]);

		ByteBuffer byteBuffer = slice.asByteBuffer();
		assertEquals(0, byteBuffer.position());
		assertEquals(3, byteBuffer.limit());
		assertEquals(3, byteBuffer.capacity());
		byteBuffer.put(2, (byte) 77);
		assertEquals(77, bytes[3]);
	}

	@Test
	void nativeViewsAndSlicesPreserveTheOriginalStorage() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment segment = arena.allocateFrom(ValueLayout.JAVA_BYTE,
					new byte[] {1, 2, 3, 4, 5});
			MemorySegmentBuf buf = new MemorySegmentBuf(segment);

			assertSame(segment, buf.asMemorySegment());
			assertSame(segment, buf.asMemorySegmentStrict());
			assertTrue(buf.asByteBuffer().isDirect());

			Buf slice = buf.subList(1, 4);
			assertArrayEquals(new byte[] {2, 3, 4}, slice.asArray());
			slice.setByte(1, (byte) 42);
			assertEquals(42, segment.get(ValueLayout.JAVA_BYTE, 2));
		}
	}

	@Test
	void freezeMarksTheStorageAndKeepsTheHandle() {
		byte[] storage = {1, 2, 3};
		MemorySegmentBuf mutable = new MemorySegmentBuf(MemorySegment.ofArray(storage));
		Buf view = mutable.subList(0, 2);
		Buf frozen = mutable.freeze();

		assertSame(mutable, frozen, "freeze returns the handle it was called on");
		assertFalse(mutable.isMutable(), "the receiver is frozen, not just the returned handle");
		assertFalse(view.isMutable(), "a view taken before the freeze is frozen too");
		assertFalse(frozen.isMutable());
		assertThrows(UnsupportedOperationException.class, () -> frozen.setByte(0, (byte) 9));
		assertThrows(UnsupportedOperationException.class, () -> mutable.setByte(0, (byte) 9));
		assertThrows(UnsupportedOperationException.class, () -> view.setByte(0, (byte) 9));
		// a resize to the unchanged size is refused too, as on the heap backing, rather than accepted as no-op
		assertThrows(UnsupportedOperationException.class, () -> mutable.size(mutable.size()));
		assertSame(frozen, frozen.freeze());

		// the bytes stay aliased and readable; freeze closes the Buf mutation routes, not the memory
		storage[0] = 8;
		assertEquals(8, frozen.getByte(0), "raw writes are still visible through the frozen handle");
		assertEquals(8, mutable.getByte(0));

		// a handle built separately from the same memory has its own freeze state, as Buf#freeze() documents
		Buf sibling = new MemorySegmentBuf(MemorySegment.ofArray(storage));
		assertTrue(sibling.isMutable(), "freeze() cannot reach a handle built from the segment itself");
		sibling.setByte(2, (byte) 7);
		assertEquals(7, frozen.getByte(2), "and its write is visible through the frozen handle");

		// the raw views stay writable too, exactly as a frozen heap handle's backing array does: what freeze()
		// closes is the Buf mutation API, not the memory underneath it
		assertFalse(frozen.asMemorySegmentStrict().isReadOnly(), "freeze() guards the Buf API, not the memory");
		frozen.asMemorySegmentStrict().set(ValueLayout.JAVA_BYTE, 1L, (byte) 4);
		assertEquals(4, mutable.getByte(1), "and writes through the raw view are still aliased");
	}

	@Test
	void mutablePrimitiveSettersMatchHeapBuf() {
		Random random = new Random(0x4d53425546L);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegmentBuf nativeBuf = new MemorySegmentBuf(arena.allocate(256, 8));
			Buf heapBuf = Buf.createZeroes(256);

			for (int operation = 0; operation < 10_000; operation++) {
				int kind = random.nextInt(8);
				int width = switch (kind) {
					case 0 -> 1;
					case 1, 2 -> 2;
					case 3, 4, 5 -> 4;
					default -> 8;
				};
				int offset = random.nextInt(257 - width);
				switch (kind) {
					case 0 -> {
						byte value = (byte) random.nextInt();
						nativeBuf.setByte(offset, value);
						heapBuf.setByte(offset, value);
					}
					case 1 -> {
						char value = (char) random.nextInt();
						nativeBuf.setChar(offset, value);
						heapBuf.setChar(offset, value);
					}
					case 2 -> {
						short value = (short) random.nextInt();
						nativeBuf.setShort(offset, value);
						heapBuf.setShort(offset, value);
					}
					case 3 -> {
						int value = random.nextInt();
						nativeBuf.setInt(offset, value);
						heapBuf.setInt(offset, value);
					}
					case 4 -> {
						int value = random.nextInt();
						nativeBuf.setIntLE(offset, value);
						heapBuf.setIntLE(offset, value);
					}
					case 5 -> {
						float value = Float.intBitsToFloat(random.nextInt());
						nativeBuf.setFloat(offset, value);
						heapBuf.setFloat(offset, value);
					}
					case 6 -> {
						long value = random.nextLong();
						nativeBuf.setLong(offset, value);
						heapBuf.setLong(offset, value);
					}
					case 7 -> {
						double value = Double.longBitsToDouble(random.nextLong());
						nativeBuf.setDouble(offset, value);
						heapBuf.setDouble(offset, value);
					}
					default -> throw new AssertionError();
				}
				assertTrue(nativeBuf.equals(0, heapBuf, 0, heapBuf.size()));
			}
			assertArrayEquals(heapBuf.asArray(), nativeBuf.asArray());
		}
	}

	@Test
	void randomizedHeapNativeBulkInteropMatchesArrayCopy() {
		Random random = new Random(0x42554c4bL);
		try (Arena arena = Arena.ofConfined()) {
			for (int round = 0; round < 1_000; round++) {
				int sourceSize = random.nextInt(2049);
				int targetSize = random.nextInt(2049);
				byte[] source = new byte[sourceSize];
				byte[] expected = new byte[targetSize];
				random.nextBytes(source);
				random.nextBytes(expected);

				MemorySegment sourceSegment = arena.allocate(sourceSize, 1);
				MemorySegment targetSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, expected);
				if (sourceSize != 0) {
					MemorySegment.copy(MemorySegment.ofArray(source), 0,
							sourceSegment, 0, sourceSize);
				}
				Buf nativeSource = new MemorySegmentBuf(sourceSegment);
				MemorySegmentBuf nativeTarget = new MemorySegmentBuf(targetSegment);

				int length = random.nextInt(Math.min(sourceSize, targetSize) + 1);
				int sourceOffset = random.nextInt(sourceSize - length + 1);
				int targetOffset = random.nextInt(targetSize - length + 1);
				nativeTarget.setBytesFromBuf(targetOffset, nativeSource, sourceOffset, length);
				System.arraycopy(source, sourceOffset, expected, targetOffset, length);
				assertArrayEquals(expected, nativeTarget.asArray());

				Buf heapTarget = Buf.createZeroes(targetSize);
				heapTarget.setBytesFromBuf(targetOffset, nativeSource, sourceOffset, length);
				byte[] expectedHeap = new byte[targetSize];
				System.arraycopy(source, sourceOffset, expectedHeap, targetOffset, length);
				assertArrayEquals(expectedHeap, heapTarget.asArray());
				assertTrue(nativeSource.equals(sourceOffset, source, sourceOffset, length));
			}
		}
	}

	@Test
	void closedScopeInvalidatesOriginalSliceAndFrozenViews() {
		Arena arena = Arena.ofConfined();
		MemorySegmentBuf original = new MemorySegmentBuf(arena.allocate(8));
		Buf slice = original.subList(2, 6);
		Buf frozen = slice.freeze();
		ByteBuffer byteBuffer = slice.asByteBuffer();
		arena.close();

		assertThrows(IllegalStateException.class, () -> original.getByte(0));
		assertThrows(IllegalStateException.class, () -> slice.getByte(0));
		assertThrows(IllegalStateException.class, () -> frozen.getByte(0));
		assertThrows(IllegalStateException.class, () -> byteBuffer.get(0));
	}

	@Test
	void invalidBulkRangesAreRejectedBeforeCopy() {
		MemorySegmentBuf target = new MemorySegmentBuf(MemorySegment.ofArray(new byte[4]));
		Buf source = Buf.wrap(new byte[4]);
		assertThrows(IndexOutOfBoundsException.class,
				() -> target.setBytesFromBuf(0, source, 3, 2));
		assertThrows(IndexOutOfBoundsException.class,
				() -> target.setBytesFromBuf(3, source, 0, 2));
		assertArrayEquals(new byte[4], target.asArray());
	}

	/** A public mutation route, so the two contract tests below can run one alphabet over both backings. */
	@FunctionalInterface
	private interface MutationRoute {
		void apply(Buf buf) throws Exception;
	}

	private record NamedRoute(String name, MutationRoute route) {}

	/**
	 * The routes fastutil's {@code ByteSubList} implements by moving the view's own window first and only
	 * then delegating to the list behind it. On a frozen storage that order leaves a rejected removal with a
	 * shrunken view behind, so these routes need a mutability check of their own on a heap view.
	 */
	private static final List<NamedRoute> REJECTION_ROUTES = List.of(
			new NamedRoute("removeByte", buf -> buf.removeByte(1)),
			new NamedRoute("remove(int)", buf -> buf.remove(1)),
			new NamedRoute("remove(Object)", buf -> buf.remove(Byte.valueOf((byte) 2))),
			new NamedRoute("add(byte)", buf -> buf.add((byte) 9)),
			new NamedRoute("add(int,byte)", buf -> buf.add(1, (byte) 9)),
			new NamedRoute("add(Byte)", buf -> buf.add(Byte.valueOf((byte) 9))),
			new NamedRoute("add(int,Byte)", buf -> buf.add(1, Byte.valueOf((byte) 9))),
			new NamedRoute("addAll(Collection)", buf -> buf.addAll(List.of((byte) 7, (byte) 8))),
			new NamedRoute("addAll(int,Collection)", buf -> buf.addAll(1, List.of((byte) 7, (byte) 8))),
			new NamedRoute("set", buf -> buf.set(1, (byte) 9)),
			new NamedRoute("clear", Buf::clear),
			new NamedRoute("removeElements", buf -> buf.removeElements(1, 3)));

	/**
	 * A mutation rejected because the storage is frozen has to leave nothing behind — not even the rejecting
	 * view's own window. That is what the heap backing gets wrong for the routes that move the window before
	 * asking the list behind it whether it accepts the edit.
	 */
	@ParameterizedTest
	@MethodSource("rejectionRoutes")
	void aRejectedMutationLeavesAHeapViewExactlyAsItWas(String name, MutationRoute route) {
		byte[] storage = {1, 2, 3, 4, 5, 6};
		Buf root = Buf.wrap(storage);
		Buf view = root.subListForced(0, 6);
		view.freeze();
		int sizeBefore = view.size();
		byte[] contentBefore = view.asArray().clone();

		assertThrows(UnsupportedOperationException.class, () -> route.apply(view), name);

		assertEquals(sizeBefore, view.size(), name + ": a rejected removal may not shrink the view");
		assertArrayEquals(contentBefore, view.asArray(), name + ": a rejected mutation may not move the view");
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, storage, name + ": and it may not write through");
	}

	/**
	 * The other half of the same rule: when a storage refuses an edit because it is frozen, that is the
	 * reason the caller is told, on either backing and whatever else the request would have failed on.
	 */
	@ParameterizedTest
	@MethodSource("rejectionRoutes")
	void aFrozenStorageReportsTheFreezeBeforeAnyBoundsError(String name, MutationRoute route) {
		// heap, frozen through the view
		Buf heapView = Buf.wrap(new byte[] {1, 2, 3, 4, 5, 6}).subListForced(0, 6);
		heapView.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(heapView), "heap view, " + name);

		// native, frozen through the handle itself
		Buf nativeBuf = new MemorySegmentBuf(MemorySegment.ofArray(new byte[] {1, 2, 3, 4, 5, 6}));
		nativeBuf.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(nativeBuf), "native, " + name);

		// native, frozen through a slice of the handle
		MemorySegment segment = MemorySegment.ofArray(new byte[] {1, 2, 3, 4, 5, 6});
		Buf nativeRoot = new MemorySegmentBuf(segment);
		Buf nativeSlice = nativeRoot.subListForced(0, 6);
		nativeRoot.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(nativeSlice), "native slice, " + name);
	}

	public static Stream<Arguments> rejectionRoutes() {
		return REJECTION_ROUTES.stream().map(r -> Arguments.of(r.name(), r.route()));
	}

	/** A mutation request that reports whether it changed anything, so the no-op rule below can pin both halves. */
	@FunctionalInterface
	private interface BooleanRoute {
		boolean apply(Buf buf) throws Exception;
	}

	private record NamedBooleanRoute(String name, BooleanRoute route) {}

	/**
	 * Mutation requests that ask for something to change and, on this content, would find nothing to change:
	 * fastutil answers those by searching, so before this rule they slipped past a frozen storage as a
	 * {@code false} — on the native backing they still did, while the heap backing, which guards every one of
	 * them, answered "the buffer is immutable". A frozen storage refuses the request instead of quietly
	 * accepting it as a no-op, which is the same stance both backings already take for a
	 * {@code size(currentSize)} call.
	 */
	private static final List<NamedBooleanRoute> NO_OP_REQUESTS = List.of(
			new NamedBooleanRoute("remove(absent Object)", buf -> buf.remove(Byte.valueOf((byte) 99))),
			new NamedBooleanRoute("rem(absent)", buf -> buf.rem((byte) 99)),
			new NamedBooleanRoute("removeAll(empty ByteCollection)", buf -> buf.removeAll((ByteCollection) new ByteArrayList())),
			new NamedBooleanRoute("removeAll(ByteCollection)", buf -> buf.removeAll((ByteCollection) new ByteArrayList(new byte[] {98, 99}))),
			new NamedBooleanRoute("removeAll(Collection)", buf -> buf.removeAll(List.of((byte) 98, (byte) 99))),
			new NamedBooleanRoute("retainAll(ByteCollection)", buf -> buf.retainAll((ByteCollection) new ByteArrayList(new byte[] {1, 2, 3, 4, 5, 6, 7}))),
			new NamedBooleanRoute("retainAll(Collection)", buf -> buf.retainAll(List.of((byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6, (byte) 7))),
			new NamedBooleanRoute("removeIf(Predicate)", buf -> buf.removeIf((java.util.function.Predicate<Byte>) value -> value == 99)),
			new NamedBooleanRoute("removeIf(BytePredicate)", buf -> buf.removeIf((BytePredicate) value -> value == 99)),
			new NamedBooleanRoute("removeIf(IntPredicate)", buf -> buf.removeIf((IntPredicate) value -> value == 99)));

	/** On a live storage these requests are a plain "nothing changed", so the refusal below is about the freeze. */
	@ParameterizedTest
	@MethodSource("noOpRequests")
	void anUnfrozenStorageStillCarriesANoOpMutationRequestOut(String name, BooleanRoute route) throws Exception {
		for (Buf buf : List.of(
				Buf.wrap(new byte[] {1, 2, 3, 4, 5, 6}),
				new MemorySegmentBuf(MemorySegment.ofArray(new byte[] {1, 2, 3, 4, 5, 6})))) {
			assertFalse(route.apply(buf), name + ": expected a request that changes nothing");
			assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, buf.asArray(), name + " on a live storage");
		}
	}

	/** And once the storage is frozen it is refused — through the root, a view, the handle or a slice of it. */
	@ParameterizedTest
	@MethodSource("noOpRequests")
	void aFrozenStorageRefusesEvenAMutationRequestThatWouldChangeNothing(String name, BooleanRoute route) {
		Buf heapRoot = Buf.wrap(new byte[] {1, 2, 3, 4, 5, 6});
		Buf heapView = heapRoot.subListForced(0, 6);
		heapView.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(heapView), "heap view, " + name);
		assertThrows(UnsupportedOperationException.class, () -> route.apply(heapRoot), "heap root, " + name);

		Buf nativeRoot = new MemorySegmentBuf(MemorySegment.ofArray(new byte[] {1, 2, 3, 4, 5, 6}));
		nativeRoot.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(nativeRoot), "native, " + name);

		MemorySegment segment = MemorySegment.ofArray(new byte[] {1, 2, 3, 4, 5, 6});
		Buf frozenNativeRoot = new MemorySegmentBuf(segment);
		Buf nativeSlice = frozenNativeRoot.subListForced(0, 6);
		frozenNativeRoot.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(nativeSlice), "native slice, " + name);
	}

	public static Stream<Arguments> noOpRequests() {
		return NO_OP_REQUESTS.stream().map(r -> Arguments.of(r.name(), r.route()));
	}

	/**
	 * The structural siblings of the requests above: an empty range removed, an empty array added, an empty
	 * collection added. fastutil answers all three without touching the storage, so a frozen memory-segment
	 * buffer let them through while the heap backing refused them — the same split this test already closes for
	 * the searching routes.
	 */
	private static final List<NamedRoute> EMPTY_STRUCTURAL_REQUESTS = List.of(
			new NamedRoute("removeElements(1,1)", buf -> buf.removeElements(1, 1)),
			new NamedRoute("removeElements(0,0)", buf -> buf.removeElements(0, 0)),
			new NamedRoute("addElements(int,byte[])", buf -> buf.addElements(1, new byte[0])),
			new NamedRoute("addElements(int,byte[],int,int)", buf -> buf.addElements(1, new byte[0], 0, 0)),
			new NamedRoute("addAll(empty Collection)", buf -> buf.addAll(List.of())),
			new NamedRoute("addAll(int,empty Collection)", buf -> buf.addAll(1, List.of())),
			new NamedRoute("addAll(empty ByteCollection)", buf -> buf.addAll((ByteCollection) new ByteArrayList())));

	/** A live storage carries all of them out, so the refusal below is about the freeze and nothing else. */
	@ParameterizedTest
	@MethodSource("emptyStructuralRequests")
	void anUnfrozenStorageCarriesAnEmptyStructuralRequestOut(String name, MutationRoute route) {
		for (Buf buf : List.of(
				Buf.wrap(new byte[] {1, 2, 3, 4}),
				new MemorySegmentBuf(MemorySegment.ofArray(new byte[] {1, 2, 3, 4})))) {
			assertDoesNotThrow(() -> route.apply(buf), name + " on a live storage");
			assertArrayEquals(new byte[] {1, 2, 3, 4}, buf.asArray(), name + " on a live storage");
			assertEquals(4, buf.size(), name + " on a live storage");
		}
	}

	@ParameterizedTest
	@MethodSource("emptyStructuralRequests")
	void aFrozenStorageRefusesEvenAnEmptyStructuralRequest(String name, MutationRoute route) {
		Buf heapRoot = Buf.wrap(new byte[] {1, 2, 3, 4});
		Buf heapView = heapRoot.subListForced(0, 4);
		heapView.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(heapView), "heap view, " + name);
		assertThrows(UnsupportedOperationException.class, () -> route.apply(heapRoot), "heap root, " + name);

		Buf nativeRoot = new MemorySegmentBuf(MemorySegment.ofArray(new byte[] {1, 2, 3, 4}));
		nativeRoot.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(nativeRoot), "native, " + name);

		MemorySegment segment = MemorySegment.ofArray(new byte[] {1, 2, 3, 4});
		Buf frozenNativeRoot = new MemorySegmentBuf(segment);
		Buf nativeSlice = frozenNativeRoot.subListForced(0, 4);
		frozenNativeRoot.freeze();
		assertThrows(UnsupportedOperationException.class, () -> route.apply(nativeSlice), "native slice, " + name);
	}

	public static Stream<Arguments> emptyStructuralRequests() {
		return EMPTY_STRUCTURAL_REQUESTS.stream().map(r -> Arguments.of(r.name(), r.route()));
	}

	/**
	 * {@code clear()} on a buffer that is already empty is the most empty request of all. It succeeds on a live
	 * storage of either backing, which is why the memory-segment one also let it through when frozen — and it
	 * is refused when frozen on both, because a frozen storage does not accept a request to empty it, however
	 * little there is to do.
	 */
	@Test
	void clearingAnEmptyBufferIsRefusedOnceFrozenButAcceptedWhileLive() {
		for (Buf buf : List.of(
				Buf.wrap(new byte[0]),
				new MemorySegmentBuf(MemorySegment.ofArray(new byte[0])))) {
			assertDoesNotThrow(buf::clear, "live clear() of an empty buffer");
			buf.freeze();
			assertThrows(UnsupportedOperationException.class, buf::clear, "frozen clear() of an empty buffer");
		}
		for (Buf buf : List.of(
				Buf.wrap(new byte[0]).subListForced(0, 0),
				new MemorySegmentBuf(MemorySegment.ofArray(new byte[0])).subListForced(0, 0))) {
			assertDoesNotThrow(buf::clear, "live clear() of an empty view");
			buf.freeze();
			assertThrows(UnsupportedOperationException.class, buf::clear, "frozen clear() of an empty view");
		}
	}

	/**
	 * A declared backing window that cannot describe the buffer's own content is not a zero-copy view: it
	 * reports no segment at all, so bulk copies fall back to reading the buffer element by element instead of
	 * failing inside {@code MemorySegment.copy} on a range the window never covered.
	 */
	@Test
	void aBackingWindowTooSmallForTheContentIsNotAZeroCopyView() {
		// A buffer that declares more content than the backing window it advertises: the bytes are honest,
		// only the window claim is too small. This is the state a zero-copy view must refuse to make.
		Buf inconsistent = new ByteListBuf(new byte[] {7}) {
			@Override
			public int size() {
				return 2;
			}

			@Override
			public byte getByte(int index) {
				return index == 0 ? (byte) 7 : (byte) 0;
			}
		};
		assertEquals(2, inconsistent.size());
		assertEquals(1, inconsistent.getBackingByteArray().length);
		assertTrue(inconsistent.getBackingByteArrayLength() < inconsistent.size(), "the inconsistency under test");
		assertNull(inconsistent.asMemorySegmentStrict(), "a window that cannot cover the content is no view");
		assertNull(inconsistent.asMemorySegmentStrict(), "and the claim does not depend on how often it is asked");
		assertEquals(7, inconsistent.getByte(0), "the byte the window does cover reads normally");
		assertEquals(0, inconsistent.getByte(1), "and the one beyond it reads through the ordinary route");

		// and the copy routes still work, through their element fallback
		MemorySegmentBuf nativeTarget = new MemorySegmentBuf(MemorySegment.ofArray(new byte[2]));
		nativeTarget.setBytesFromBuf(0, inconsistent, 0, 2);
		assertArrayEquals(new byte[] {7, 0}, nativeTarget.asArray());

		Buf heapTarget = Buf.createZeroes(2);
		heapTarget.setBytesFromBuf(0, inconsistent, 0, 2);
		assertArrayEquals(new byte[] {7, 0}, heapTarget.asArray());
	}

	/** A window that does cover the content keeps its zero-copy segment: this rejection is not a blanket one. */
	@Test
	void aConsistentBackingWindowStillYieldsAZeroCopyView() {
		Buf buf = Buf.wrap(new byte[] {1, 2, 3, 4});
		MemorySegment segment = buf.asMemorySegmentStrict();
		assertNotNull(segment);
		assertEquals(4, segment.byteSize());
		assertEquals(2, segment.get(ValueLayout.JAVA_BYTE, 1));
	}
}
