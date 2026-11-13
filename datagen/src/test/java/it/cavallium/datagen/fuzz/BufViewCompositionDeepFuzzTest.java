package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.MemorySegmentBuf;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Property;

/**
 * View composition: a randomly nested chain of {@link Buf#subList(int, int)} and
 * {@link Buf#subListForced(int, int)} calls, on both backings, must behave exactly like one flat copy
 * of the same bytes — for every typed read, for every bounds claim, and for {@code freeze()}.
 *
 * <p>What {@link BufFreezeAliasPropertyTest} already pins, and this class deliberately does not
 * repeat: freeze is a property of the storage, not of the handle (every mutation route through every
 * handle is rejected); live views alias the parent through every mutation route; and
 * {@code subListForced} on a heap view re-wraps against the root, so the backing-array offset composes
 * absolutely. {@code MemorySegmentBufContractTest} pins native freeze as a read-only zero-copy view,
 * and {@code BufStorageLifecycleDeepFuzzTest} pins native slice, buffer and freeze lifetimes.</p>
 *
 * <p>What is added here is the read surface of a <em>composed</em> window, where composed offset
 * arithmetic can silently go wrong:</p>
 * <ol>
 *     <li>every fixed-width read through a chain of up to six windows, on heap and on
 *     {@link MemorySegmentBuf}, equals the same read on a flat copy of the region — and equals this
 *     test's own byte-array model of it. The native path is the interesting one:
 *     {@code MemorySegmentBuf} overrides the multi-byte accessors to read through a
 *     {@code java.lang.foreign.ValueLayout}, so a composed offset that is off by one level changes the
 *     decoded value rather than throwing;</li>
 *     <li>out-of-region access keeps the exact bounds claim of the equivalent flat copy, and every one
 *     of the four wordings states the same offending offset. The claim is modelled per backing,
 *     because the two families word it differently and because {@code getChar} and {@code getInt52} are
 *     the two accessors {@code MemorySegmentBuf} does <em>not</em> override — they therefore read byte
 *     by byte and report the first byte that fell outside the window, while {@code getShort},
 *     {@code getInt}, {@code getIntLE}, {@code getLong}, {@code getFloat}, {@code getDouble} and
 *     {@code getString} report the whole aggregate access;</li>
 *     <li>the non-obvious contracts of the real API are pinned rather than smoothed over: heap
 *     {@code freeze()} returns the same handle and therefore cannot snapshot, so a write into the
 *     wrapped array is still visible through the frozen view — while {@code copy()} is a snapshot and
 *     is not; native {@code freeze()} hands out a <em>new</em> read-only handle over the <em>same</em>
 *     memory, so a write through the source is visible through it, too; {@code subList(0, size)}
 *     returns the receiver for a root and for a native view but re-wraps for a heap view; a region
 *     stream can be opened on heap memory only; and a negative index throws
 *     {@code ArrayIndexOutOfBoundsException} from a heap root where a heap view reports the list
 *     contract. The last two stay inside {@link IndexOutOfBoundsException} semantics, so neither is a
 *     contract violation, and both are visible only because composed views and flat twins are compared
 *     against each other instead of against one expectation.</li>
 * </ol>
 *
 * <p>{@code tries = 1} on purpose, as in {@link BufFreezeAliasPropertyTest}: the tier ladder owns the
 * case count and every random choice comes from {@link FuzzTiers#random(String)}, so a fixed
 * {@code -Ddatagen.fuzz.seed} reproduces the whole case set.</p>
 */
class BufViewCompositionDeepFuzzTest {

	/** A window expressed against its parent, and which factory produced it. */
	private record Window(int from, int to, boolean forced) {}

	@Property(tries = 1)
	void everyComposedViewReadsExactlyLikeAnEquivalentFlatCopyOnBothBackings() {
		Random rnd = FuzzTiers.random("buf-view-composition");
		int cases = FuzzTiers.cases("buf-view-composition", 3, 10, 30, 60);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
				byte[] bytes = FuzzValues.bytes(rnd, 1 + rnd.nextInt(70));
				Chain chain = Chain.random(rnd, bytes.length);
				Shapes shapes = Shapes.build(arena, bytes, chain, caseIndex);
				byte[] region = shapes.region();
				// 1. the window itself: size and full content, on all four shapes
				for (int s = 0; s < Shapes.NAMES.length; s++) {
					Buf buf = shapes.shape(s);
					String what = gate("case " + caseIndex + " shape " + Shapes.NAMES[s] + " " + chain);
					assertEquals(region.length, buf.size(), what + " (size)");
					assertArrayEquals(region, buf.asArray(), what + " (content)");
				}
				// 2. every read, in region and at every boundary, must agree with the model and each other
				for (Read read : READS) {
					for (int index : indices(region.length)) {
						assertRead(shapes, read, index, gate("case " + caseIndex + " " + read.name() + '['
								+ index + "] region 0.." + region.length + ' ' + chain));
					}
				}
				// 3. the region stream of a composed heap view is exactly that region window; the native
				//    backing refuses to open one at all, which is a contract, not a gap
				int from = Math.min(1, region.length);
				int to = Math.min(region.length, 4);
				byte[] window = Arrays.copyOfRange(region, from, to);
				assertArrayEquals(window, heapStreamBytes(shapes.heapView(), from, to),
						gate("case " + caseIndex + " (heap view region stream)"));
				assertArrayEquals(window, heapStreamBytes(shapes.heapFlat(), from, to),
						gate("case " + caseIndex + " (heap flat region stream)"));
				assertEquals(Arrays.toString(window),
						Arrays.toString(bytesOf(shapes.heapView(), from, to)),
						gate("case " + caseIndex + " (heap view random access window)"));
				Buf view = shapes.nativeView();
				Buf flat = shapes.nativeFlat();
				// Pinned wording, and note it is unconditional in MemorySegmentBuf.binaryOutputStream: every
				// native buffer is refused this way, so "read-only" is what a writable arena reports too.
				assertEquals("Cannot open OutputStream on read-only native memory",
						assertThrows(UnsupportedOperationException.class, () -> view.binaryOutputStream(from, to),
								gate("case " + caseIndex + " (native view region stream)")).getMessage(),
						gate("case " + caseIndex + " (native view stream wording)"));
				assertThrows(UnsupportedOperationException.class, () -> flat.binaryOutputStream(from, to),
						gate("case " + caseIndex + " (native flat region stream)"));
				// 4. a full stream over the view exposes exactly the region, on both backings
				assertArrayEquals(region, streamBytes(shapes.heapView()),
						gate("case " + caseIndex + " (heap view stream)"));
				assertArrayEquals(region, streamBytes(shapes.nativeView()),
						gate("case " + caseIndex + " (native view stream)"));
			}
		}
	}

	@Property(tries = 1)
	void outOfRegionAccessesKeepTheBoundsClaimOfTheEquivalentFlatCopy() {
		Random rnd = FuzzTiers.random("buf-view-bounds");
		int cases = FuzzTiers.cases("buf-view-bounds", 3, 10, 24, 48);
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
				byte[] bytes = FuzzValues.bytes(rnd, 1 + rnd.nextInt(40));
				Chain chain = Chain.random(rnd, bytes.length);
				Shapes shapes = Shapes.build(arena, bytes, chain, caseIndex);
				int size = shapes.region().length;
				for (Read read : READS) {
					for (int index : indices(size)) {
						if (read.succeeds(shapes.region(), index)) {
							continue; // the value property owns successful reads
						}
						String what = gate("case " + caseIndex + " " + read.name() + '[' + index
								+ "] size " + size + ' ' + chain);
						int outside = Bounds.firstByteOutside(read, index, size);
						int start = Bounds.nativeOffset(read, index, size);
						for (int s = 0; s < Shapes.NAMES.length; s++) {
							Buf buf = shapes.shape(s);
							// every shape must refuse, and refuse with exactly the claim the model predicts
							assertEquals(Bounds.claim(s, chain.collapsesToRoot(), read, index, size),
									Bounds.canonical(buf, read, index),
									what + " (" + Shapes.NAMES[s] + " claim)");
							// the two families name the crossing differently, and each must stay coherent:
							// the heap wording names the first byte outside, the native one names the
							// access it asked for, which has to be the access containing that byte
							assertEquals(String.valueOf(s <= 1 ? outside : start),
									Bounds.offendingOffset(buf, read, index),
									what + " (" + Shapes.NAMES[s] + " offending offset)");
							assertTrue(start <= outside && outside < start + read.nativeClaimLength(),
									what + " (both wordings must describe the same crossing)");
						}
					}
				}
			}
		}
	}

	@Property(tries = 1)
	void freezingAComposedViewAliasesTheStorageWhileACopySnapshotsIt() {
		Random rnd = FuzzTiers.random("buf-view-freeze");
		int cases = FuzzTiers.cases("buf-view-freeze", 3, 10, 24, 48);
		int aliased = 0;
		try (var arena = Arena.ofConfined()) {
			for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
				byte[] bytes = FuzzValues.bytes(rnd, 2 + rnd.nextInt(40));
				Chain chain = Chain.random(rnd, bytes.length);
				// An empty window has nothing to alias, so the case would prove nothing. That is a property of
				// this generator, not of freeze: an empty level is one of six shapes, and several of the others
				// collapse to empty too whenever the draw lands on the parent's end — and once a level is empty
				// every deeper one is. At the QUICK budget the whole round is three cases, so a run where all
				// three collapsed was a real possibility — the coverage gate below would then fail on a fuzz
				// round that had tested nothing. Re-draw a collapsed case a few times from the same seeded
				// generator; a case that still collapses stays skipped, and the gate keeps its teeth.
				for (int redraw = 0; redraw < 8 && chain.length == 0; redraw++) {
					chain = Chain.random(rnd, bytes.length);
				}
				if (chain.length == 0) {
					continue;
				}
				aliased++;
				byte[] region = chain.slice(bytes);
				byte[] mutated = region.clone();
				int at = rnd.nextInt(region.length);
				byte replacement = (byte) (region[at] ^ 0x5A);
				mutated[at] = replacement;

				// one heap storage: the wrapped array backs every handle, so freeze cannot snapshot
				byte[] heapStorage = bytes.clone();
				Buf heapView = chain.apply(Buf.wrap(heapStorage));
				Buf heapSnapshot = heapView.copy();
				Buf heapFrozen = heapView.freeze();
				assertSame(heapView, heapFrozen, gate("case " + caseIndex + " (heap freeze is the same handle)"));
				assertSame(heapFrozen, heapView.freeze(), gate("case " + caseIndex + " (heap freeze idempotent)"));
				assertFalse(heapFrozen.isMutable(), gate("case " + caseIndex + " (heap frozen is immutable)"));
				heapStorage[chain.absoluteFrom + at] = replacement; // a write the frozen view cannot veto
				assertEquals(replacement, heapFrozen.getByte(at),
						gate("case " + caseIndex + " (heap freeze aliases, it does not snapshot)"));
				assertEquals(region[at], heapSnapshot.getByte(at),
						gate("case " + caseIndex + " (copy() is a snapshot)"));
				assertThrows(UnsupportedOperationException.class, () -> heapFrozen.setByte(at, region[at]),
						gate("case " + caseIndex + " (frozen heap view rejects writes)"));

				// one native storage: the same contract — freeze keeps the handle and aliases the memory
				MemorySegment segment = arena.allocate(bytes.length, 1);
				MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);
				Buf nativeRoot = new MemorySegmentBuf(segment);
				Buf nativeView = chain.apply(nativeRoot);
				Buf nativeSnapshot = nativeView.copy();
				Buf nativeFrozen = nativeView.freeze();
				assertSame(nativeView, nativeFrozen,
						gate("case " + caseIndex + " (native freeze is the same handle)"));
				assertSame(nativeFrozen, nativeFrozen.freeze(),
						gate("case " + caseIndex + " (native freeze idempotent)"));
				assertFalse(nativeFrozen.isMutable(), gate("case " + caseIndex + " (native frozen is immutable)"));
				assertFalse(nativeRoot.isMutable(), gate("case " + caseIndex + " (freezing a view freezes its root)"));
				// a write the frozen view cannot veto, through a handle made from the same memory
				new MemorySegmentBuf(segment).setByte(chain.absoluteFrom + at, replacement);
				assertEquals(replacement, nativeFrozen.getByte(at),
						gate("case " + caseIndex + " (native freeze aliases, it does not snapshot)"));
				assertEquals(region[at], nativeSnapshot.getByte(at),
						gate("case " + caseIndex + " (native copy() is a snapshot)"));
				assertThrows(UnsupportedOperationException.class, () -> nativeFrozen.setByte(at, region[at]),
						gate("case " + caseIndex + " (frozen native view rejects writes)"));
				assertArrayEquals(mutated, nativeFrozen.asArray(),
						gate("case " + caseIndex + " (frozen native view follows the storage)"));
				assertArrayEquals(region, nativeSnapshot.asArray(),
						gate("case " + caseIndex + " (snapshot ignores the storage)"));
			}
		}
		// coverage gate: a random chain collapses to an empty window in a minority of cases, which at the
		// QUICK budget could leave this property having compared nothing; -Ddatagen.fuzz.cases=0 stays legal
		assertTrue(cases == 0 || aliased > 0,
				gate("every window was empty, nothing was aliased: " + cases + " cases"));
	}

	@Property(tries = 1)
	void subListOnAWholeWindowReturnsTheReceiverWhileSubListForcedNeverDoes() {
		// pinned deterministically, because a random chain collapses into the receiver only once in a while
		try (var arena = Arena.ofConfined()) {
			for (int length : new int[] {1, 2, 17}) {
				byte[] bytes = new byte[length];
				for (int index = 0; index < length; index++) {
					bytes[index] = (byte) (index * 7 + 1);
				}
				MemorySegment segment = arena.allocate(length, 1);
				MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, length);
				for (int depth = 1; depth <= 3; depth++) {
					Buf heapRoot = Buf.wrap(bytes.clone());
					Buf nativeRoot = new MemorySegmentBuf(segment);
					Buf heapWhole = heapRoot;
					Buf nativeWhole = nativeRoot;
					Buf heapForced = heapRoot;
					for (int level = 0; level < depth; level++) {
						heapWhole = heapWhole.subList(0, heapWhole.size());
						nativeWhole = nativeWhole.subList(0, nativeWhole.size());
						heapForced = heapForced.subListForced(0, heapForced.size());
					}
					String what = gate("length " + length + " depth " + depth);
					assertSame(heapRoot, heapWhole, what + " (heap subList of a whole window)");
					assertSame(nativeRoot, nativeWhole, what + " (native subList of a whole window)");
					assertNotSame(heapRoot, heapForced, what + " (heap subListForced always re-wraps)");
					assertEquals(length, heapForced.size(), what + " (re-wrapped size)");
					assertArrayEquals(bytes, heapForced.asArray(), what + " (re-wrapped content)");
					// the wording asymmetry that is only visible when a view and a root are compared
					assertEquals("ArrayIndexOutOfBoundsException: Index -1 out of bounds for length " + length,
							Bounds.canonical(heapRoot, BYTE, -1), what + " (heap root wording)");
					assertEquals("IndexOutOfBoundsException: Index (-1) is negative",
							Bounds.canonical(heapForced, BYTE, -1), what + " (heap view wording)");
					assertEquals(Bounds.canonical(nativeRoot, BYTE, -1),
							Bounds.canonical(nativeWhole, BYTE, -1), what + " (native wording is uniform)");
				}
			}
		}
	}

	// ------------------------------------------------------------------ shapes

	/** The composed window a chain of relative windows selects out of the root. */
	private record Chain(List<Window> windows, int absoluteFrom, int length, boolean collapsesToRoot) {

		static Chain random(Random rnd, int length) {
			List<Window> windows = new ArrayList<>();
			int parent = length;
			int absoluteFrom = 0;
			boolean collapses = true;
			int depth = 1 + rnd.nextInt(FuzzTiers.cases("buf-view-depth", 3, 4, 6, 6));
			for (int level = 0; level < depth; level++) {
				int from;
				int to;
				switch (rnd.nextInt(6)) {
					case 0 -> { // empty window
						from = rnd.nextInt(parent + 1);
						to = from;
					}
					case 1 -> { // single byte
						from = rnd.nextInt(parent + 1);
						to = Math.min(parent, from + 1);
					}
					case 2 -> { // anchored on the very end of the parent
						from = rnd.nextInt(parent + 1);
						to = parent;
					}
					case 3 -> { // the whole parent: where subList and subListForced diverge
						from = 0;
						to = parent;
					}
					default -> {
						from = rnd.nextInt(parent + 1);
						to = from + rnd.nextInt(parent - from + 1);
					}
				}
				boolean forced = rnd.nextBoolean();
				// subList(0, size) hands back the receiver, so such a window keeps the chain on the root
				collapses &= from == 0 && to == parent && !forced;
				windows.add(new Window(from, to, forced));
				absoluteFrom += from;
				parent = to - from;
			}
			return new Chain(windows, absoluteFrom, parent, collapses);
		}

		Buf apply(Buf root) {
			Buf handle = root;
			for (Window window : windows) {
				handle = window.forced()
						? handle.subListForced(window.from(), window.to())
						: handle.subList(window.from(), window.to());
			}
			return handle;
		}

		byte[] slice(byte[] bytes) {
			return Arrays.copyOfRange(bytes, absoluteFrom, absoluteFrom + length);
		}
	}

	/** The four shapes of one region: composed view and flat twin, on each backing. */
	private record Shapes(byte[] region, Buf heapRoot, Buf heapView, Buf heapFlat, Buf nativeView,
			Buf nativeFlat) {

		private static final String[] NAMES = {"heapView", "heapFlat", "nativeView", "nativeFlat"};

		Buf shape(int index) {
			return switch (index) {
				case 0 -> heapView;
				case 1 -> heapFlat;
				case 2 -> nativeView;
				default -> nativeFlat;
			};
		}

		static Shapes build(Arena arena, byte[] bytes, Chain chain, int caseIndex) {
			byte[] region = chain.slice(bytes);
			Buf heapRoot = Buf.wrap(bytes.clone());
			Buf heapView = chain.apply(heapRoot);
			Buf heapFlat = Buf.wrap(region.clone());
			MemorySegment segment = arena.allocate(Math.max(1, bytes.length), 1);
			MemorySegment.copy(MemorySegment.ofArray(bytes), 0, segment, 0, bytes.length);
			Buf nativeRoot = new MemorySegmentBuf(segment);
			Buf nativeView = chain.apply(nativeRoot);
			MemorySegment flatSegment = arena.allocate(Math.max(1, region.length), 1);
			MemorySegment.copy(MemorySegment.ofArray(region), 0, flatSegment, 0, region.length);
			// the exact window, so an empty region stays empty instead of inheriting the padded slot
			Buf nativeFlat = new MemorySegmentBuf(flatSegment.asSlice(0, region.length));
			// the chain must not collapse: a view that silently became the whole root would make every
			// comparison in this file true by accident
			assertEquals(region.length, heapView.size(), gate("case " + caseIndex + " (heap chain length)"));
			assertEquals(region.length, nativeView.size(), gate("case " + caseIndex + " (native chain length)"));
			// the verified shortcut: subList(0, size) returns the receiver on both backings, while
			// subListForced always creates a new view — which is also why a heap view and a heap root
			// word a rejected access differently, the root reaching for the array contract
			assertEquals(chain.collapsesToRoot(), heapView == heapRoot,
					gate("case " + caseIndex + " (heap subList shortcut)"));
			assertEquals(chain.collapsesToRoot(), nativeView == nativeRoot,
					gate("case " + caseIndex + " (native subList shortcut)"));
			return new Shapes(region, heapRoot, heapView, heapFlat, nativeView, nativeFlat);
		}
	}

	/** Every index of the region, plus one before it and the seven bytes past its end. */
	private static List<Integer> indices(int size) {
		List<Integer> result = new ArrayList<>();
		result.add(-1);
		for (int index = 0; index < size; index++) {
			result.add(index);
		}
		for (int over = 0; over <= 7; over++) {
			result.add(size + over);
		}
		return result;
	}

	/** The bytes a heap region stream yields. */
	private static byte[] heapStreamBytes(Buf buf, int from, int to) {
		return buf.binaryOutputStream(from, to).toByteArray();
	}

	/** The same window, reached by random access instead of through a stream. */
	private static byte[] bytesOf(Buf buf, int from, int to) {
		byte[] result = new byte[to - from];
		for (int index = 0; index < result.length; index++) {
			result[index] = buf.getByte(from + index);
		}
		return result;
	}

	private static byte[] streamBytes(Buf buf) {
		try (var in = buf.binaryInputStream()) {
			return in.readAllBytes();
		}
	}

	// ------------------------------------------------------------------ reads

	/** One typed read, its width, and how each backing reports an out-of-region access. */
	private abstract static class Read {
		private final String name;
		private final int width;
		/**
		 * The length the native claim states: the whole access for the accessors
		 * {@code MemorySegmentBuf} overrides, and one byte for {@code getChar} and {@code getInt52},
		 * which it does not override, so they read byte by byte and fail on the first byte outside.
		 */
		private final int nativeClaimLength;
		private final boolean rangeStyle;

		Read(String name, int width, int nativeClaimLength, boolean rangeStyle) {
			this.name = name;
			this.width = width;
			this.nativeClaimLength = nativeClaimLength;
			this.rangeStyle = rangeStyle;
		}

		abstract Object apply(Buf buf, int index);

		/** The value the region bytes must produce, or {@code null} when the access is out of region. */
		abstract Object model(byte[] region, int index);

		String name() {
			return name;
		}

		int width() {
			return width;
		}

		int nativeClaimLength() {
			return nativeClaimLength;
		}

		boolean rangeStyle() {
			return rangeStyle;
		}

		final boolean succeeds(byte[] region, int index) {
			return model(region, index) != null;
		}
	}

	private static final int STRING_LENGTH = 3;

	/** The one-byte read, also used to pin the wording of a rejected negative index. */
	private static final Read BYTE = new Read("getByte", 1, 1, false) {
		@Override Object apply(Buf buf, int i) { return buf.getByte(i); }

		@Override Object model(byte[] r, int i) { return inRegion(r, i, 1) ? r[i] : null; }
	};

	private static final List<Read> READS = List.of(
			new Read("getBoolean", 1, 1, false) {
				@Override Object apply(Buf buf, int i) { return buf.getBoolean(i); }

				@Override Object model(byte[] r, int i) { return inRegion(r, i, 1) ? r[i] != 0 : null; }
			},
			BYTE,
			new Read("getShort", 2, 2, false) {
				@Override Object apply(Buf buf, int i) { return buf.getShort(i); }

				@Override Object model(byte[] r, int i) { return inRegion(r, i, 2) ? (short) be(r, i, 2) : null; }
			},
			new Read("getChar", 2, 1, false) {
				@Override Object apply(Buf buf, int i) { return buf.getChar(i); }

				@Override Object model(byte[] r, int i) { return inRegion(r, i, 2) ? (char) be(r, i, 2) : null; }
			},
			new Read("getInt", 4, 4, false) {
				@Override Object apply(Buf buf, int i) { return buf.getInt(i); }

				@Override Object model(byte[] r, int i) { return inRegion(r, i, 4) ? (int) be(r, i, 4) : null; }
			},
			new Read("getIntLE", 4, 4, false) {
				@Override Object apply(Buf buf, int i) { return buf.getIntLE(i); }

				@Override Object model(byte[] r, int i) { return inRegion(r, i, 4) ? (int) le(r, i, 4) : null; }
			},
			new Read("getLong", 8, 8, false) {
				@Override Object apply(Buf buf, int i) { return buf.getLong(i); }

				@Override Object model(byte[] r, int i) { return inRegion(r, i, 8) ? be(r, i, 8) : null; }
			},
			// verified: every Int52 read path ignores the spare leading nibble (Buf#getInt52,
			// BufDataInputCore#readInt52 and #getInt52At, and Int52#fromBytesL), pinned across backings in
			// Int52LeadNibbleCoherenceTest. The mask is spelled as a literal, not taken from Int52.MAX_VALUE_L,
			// so this model cannot drift along with the production constant.
			new Read("getInt52", 7, 1, false) {
				@Override Object apply(Buf buf, int i) { return buf.getInt52(i); }

				@Override Object model(byte[] r, int i) { return inRegion(r, i, 7) ? be(r, i, 7) & 0x000F_FFFF_FFFF_FFFFL : null; }
			},
			new Read("getFloat", 4, 4, false) {
				@Override Object apply(Buf buf, int i) { return buf.getFloat(i); }

				@Override Object model(byte[] r, int i) {
					return inRegion(r, i, 4) ? Float.intBitsToFloat((int) be(r, i, 4)) : null;
				}
			},
			new Read("getDouble", 8, 8, false) {
				@Override Object apply(Buf buf, int i) { return buf.getDouble(i); }

				@Override Object model(byte[] r, int i) {
					return inRegion(r, i, 8) ? Double.longBitsToDouble(be(r, i, 8)) : null;
				}
			},
			// latin-1, so that arbitrary region bytes cannot fold into a replacement character
			new Read("getString", STRING_LENGTH, STRING_LENGTH, true) {
				@Override Object apply(Buf buf, int i) {
					return buf.getString(i, STRING_LENGTH, StandardCharsets.ISO_8859_1);
				}

				@Override Object model(byte[] r, int i) {
					return inRegion(r, i, STRING_LENGTH)
							? new String(r, i, STRING_LENGTH, StandardCharsets.ISO_8859_1)
							: null;
				}
			});

	/** Compares one read on all four shapes against the model and against each other. */
	private static void assertRead(Shapes shapes, Read read, int index, String what) {
		Object expected = read.model(shapes.region(), index);
		String expectedLine = expected == null ? "must fail" : render(expected);
		for (int s = 0; s < Shapes.NAMES.length; s++) {
			Buf buf = shapes.shape(s);
			String actual;
			try {
				actual = render(read.apply(buf, index));
			} catch (RuntimeException failure) {
				actual = "must fail";
			}
			assertEquals(expectedLine, actual, what + " (" + Shapes.NAMES[s] + ")");
		}
	}

	private static boolean inRegion(byte[] region, int index, int width) {
		return index >= 0 && index + width <= region.length;
	}

	private static long be(byte[] bytes, int index, int width) {
		long value = 0;
		for (int i = 0; i < width; i++) {
			value = (value << 8) | Byte.toUnsignedInt(bytes[index + i]);
		}
		return value;
	}

	private static long le(byte[] bytes, int index, int width) {
		long value = 0;
		for (int i = width - 1; i >= 0; i--) {
			value = (value << 8) | Byte.toUnsignedInt(bytes[index + i]);
		}
		return value;
	}

	/** A stable rendering, so a composition failure states exact bits instead of a lossy value. */
	private static String render(Object value) {
		if (value instanceof byte[] bytes) {
			return "b[" + HexFormat.of().formatHex(bytes) + "]";
		}
		if (value instanceof String text) {
			return "s[" + HexFormat.of().formatHex(text.getBytes(StandardCharsets.ISO_8859_1)) + "]";
		}
		if (value instanceof Float number) {
			return "f[" + Float.floatToRawIntBits(number) + "]";
		}
		if (value instanceof Double number) {
			return "d[" + Double.doubleToRawLongBits(number) + "]";
		}
		if (value instanceof Character character) {
			return "c[" + (int) character + "]";
		}
		return String.valueOf(value);
	}

	// ------------------------------------------------------------------ bounds

	/**
	 * The bounds claims the two backing families really produce, each one verified against the
	 * implementation: a heap handle reports the first byte that fell outside the window together with
	 * the window's size, a native handle reports the access it asked the segment for. The flat heap root
	 * words a negative index as an array access and a short string as a string range, and every variant
	 * stays inside the {@link IndexOutOfBoundsException} family.
	 */
	private static final class Bounds {

		/** The byte the heap wordings name: the first one that lies outside the window. */
		static int firstByteOutside(Read read, int index, int size) {
			if (index < 0 || read.rangeStyle()) {
				return index; // a negative access names itself, and so does a range claim
			}
			return Math.max(index, size);
		}

		/** The start of the access the native wordings name, which is the window position itself. */
		static int nativeOffset(Read read, int index, int size) {
			return index < 0 || read.rangeStyle() || read.nativeClaimLength() == read.width()
					? index
					: Math.max(index, size);
		}

		/** The full canonical claim of one real failure, segment description reduced to a placeholder. */
		static String canonical(Buf buf, Read read, int index) {
			try {
				return "did not fail: " + render(read.apply(buf, index));
			} catch (Throwable failure) {
				String message = String.valueOf(failure.getMessage())
						.replaceAll("MemorySegment\\{[^}]*}", "SEG");
				return failure.getClass().getSimpleName() + ": " + message;
			}
		}

		/** The first signed integer a real failure states, which is the offending offset. */
		static String offendingOffset(Buf buf, Read read, int index) {
			String claim = canonical(buf, read, index).replace("SEG", "");
			StringBuilder first = new StringBuilder();
			boolean started = false;
			for (int i = 0; i < claim.length(); i++) {
				char character = claim.charAt(i);
				if (character == '-' && !started) {
					started = true;
					first.append(character);
				} else if (Character.isDigit(character)) {
					started = true;
					first.append(character);
				} else if (started) {
					break;
				}
			}
			return first.toString();
		}

		/**
		 * The exact claim the model predicts for one shape. The phrasing is borrowed, not modelled: "Index (x)
		 * is negative" and "… greater than or equal to list size (n)" are fastutil's {@code checkIndex} text,
		 * while "Out of bound access on segment …" and "Range [x, y) out of bounds for length n" are JDK
		 * wording (the {@code MemorySegment} bounds message and the JDK's own range checks). Only which
		 * offset each family names is derived here (see {@link #firstByteOutside} and {@link #nativeOffset});
		 * a fastutil or JDK upgrade will need these pinned literals revisited.
		 */
		static String claim(int shape, boolean heapViewIsRoot, Read read, int index, int size) {
			boolean negative = index < 0;
			int offending = firstByteOutside(read, index, size);
			boolean listContract = shape == 0 && !heapViewIsRoot;
			if (shape <= 1 && listContract) { // a heap view: always the list contract
				return read.rangeStyle()
						? "IndexOutOfBoundsException: Range [" + index + ", " + index + " + " + read.width()
								+ ") out of bounds for length " + size
						: negative
								? "IndexOutOfBoundsException: Index (" + index + ") is negative"
								: "IndexOutOfBoundsException: Index (" + offending
										+ ") is greater than or equal to list size (" + size + ")";
			}
			if (shape <= 1) { // a heap root, either the flat twin or the chain that collapsed into one
				if (read.rangeStyle()) {
					return "StringIndexOutOfBoundsException: Range [" + index + ", " + index + " + "
							+ read.width() + ") out of bounds for length " + size;
				}
				return negative
						? "ArrayIndexOutOfBoundsException: Index " + index + " out of bounds for length " + size
						: "IndexOutOfBoundsException: Index (" + offending
								+ ") is greater than or equal to list size (" + size + ")";
			}
			// both native shapes report the access they asked the segment for
			return "IndexOutOfBoundsException: Out of bound access on segment SEG; new offset = "
					+ nativeOffset(read, index, size) + "; new length = " + read.nativeClaimLength();
		}
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}
}
