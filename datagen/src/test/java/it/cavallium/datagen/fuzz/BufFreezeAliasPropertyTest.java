package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.cavallium.buffer.Buf;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.bytes.ByteList;
import it.unimi.dsi.fastutil.bytes.ByteListIterator;
import it.unimi.dsi.fastutil.bytes.BytePredicate;
import it.unimi.dsi.fastutil.bytes.ByteUnaryOperator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Property;

/**
 * Property fuzzing for {@link Buf#freeze()} and the parent↔view aliasing of
 * {@link Buf#subListForced(int, int)} — the freeze/aliasing counterpart of the collection state
 * machine in {@code BufCollectionSurfaceDeepFuzzTest}, which only freezes whole roots.
 *
 * <p>Three behaviours of the real implementation are pinned, in the style of that file's
 * hand-written frozen-mutation alphabet but at randomized nesting depth:</p>
 * <ol>
 *     <li>Freeze is a property of the <em>storage</em>, not of the handle: freezing the root or any
 *     view at any depth rejects every mutation route through every handle to the same buffer
 *     (verified: {@code sub.freeze()} also freezes the root, {@code root.freeze()} also freezes
 *     live views), and {@code freeze()} returns the same handle it was called on.</li>
 *     <li>Views are live aliases, not snapshots: a write through either handle is visible through
 *     the other; the view window keeps its own size, which only edits <em>through the view</em>
 *     change, while a parent append or insert merely shifts the aliased bytes underneath it.</li>
 *     <li>{@code subListForced} on a view re-wraps against the root, so any nesting depth keeps a
 *     single composed backing-array offset: {@code getBackingByteArrayOffset()} equals the absolute
 *     start, never a per-level partial offset.</li>
 * </ol>
 *
 * <p>{@code tries = 1} on purpose: the tier ladder owns the case count, and every random choice
 * comes from {@link FuzzTiers#random(String)}, so a fixed {@code -Ddatagen.fuzz.seed} reproduces
 * the whole case set.</p>
 */
class BufFreezeAliasPropertyTest {

	@FunctionalInterface
	private interface MutationAction {
		void execute(Buf buf) throws Exception;
	}

	/** A named public mutation route that a frozen storage must reject. */
	private record FrozenMutation(String name, MutationAction action) {}

	/**
	 * The frozen-mutation alphabet in the hand-written style of
	 * {@code BufCollectionSurfaceDeepFuzzTest}, restricted to the public {@link Buf} surface (this
	 * file lives outside {@code it.cavallium.buffer}, so {@code ByteListBuf}-only methods like
	 * {@code trim}/{@code push} stay in that test) and assuming a buffer of at least four bytes,
	 * which every handle here satisfies.
	 */
	private static final List<FrozenMutation> FROZEN_ALPHABET = List.of(
			new FrozenMutation("add-byte", buf -> buf.add((byte) 9)),
			new FrozenMutation("add-index", buf -> buf.add(1, (byte) 9)),
			new FrozenMutation("add-boxed", buf -> buf.add(Byte.valueOf((byte) 9))),
			new FrozenMutation("add-index-boxed", buf -> buf.add(1, Byte.valueOf((byte) 9))),
			new FrozenMutation("remove-byte", buf -> buf.removeByte(1)),
			new FrozenMutation("remove-boxed", buf -> buf.remove(Byte.valueOf((byte) 2))),
			new FrozenMutation("remove-index-boxed", buf -> buf.remove(1)),
			new FrozenMutation("set-byte", buf -> buf.set(1, (byte) 9)),
			new FrozenMutation("set-boxed", buf -> buf.set(1, Byte.valueOf((byte) 9))),
			new FrozenMutation("clear", Buf::clear),
			new FrozenMutation("resize", buf -> buf.size(7)),
			new FrozenMutation("remove-elements", buf -> buf.removeElements(1, 3)),
			new FrozenMutation("add-elements", buf -> buf.addElements(1, new byte[] {7, 8})),
			new FrozenMutation("add-elements-range", buf -> buf.addElements(1, new byte[] {7, 8, 9}, 1, 2)),
			new FrozenMutation("set-elements", buf -> buf.setElements(new byte[] {7, 8, 9, 10})),
			new FrozenMutation("set-elements-index", buf -> buf.setElements(1, new byte[] {7, 8})),
			new FrozenMutation("add-all-byte-collection",
					buf -> buf.addAll((ByteCollection) new ByteArrayList(new byte[] {7, 8}))),
			new FrozenMutation("add-all-byte-list",
					buf -> buf.addAll((ByteList) new ByteArrayList(new byte[] {7, 8}))),
			new FrozenMutation("add-all-collection",
					buf -> buf.addAll((java.util.Collection<? extends Byte>) List.of((byte) 7, (byte) 8))),
			new FrozenMutation("add-all-index-byte-list",
					buf -> buf.addAll(1, (ByteList) new ByteArrayList(new byte[] {7, 8}))),
			new FrozenMutation("remove-all-byte-collection",
					buf -> buf.removeAll((ByteCollection) new ByteArrayList(new byte[] {2, 3}))),
			new FrozenMutation("remove-all-collection", buf -> buf.removeAll(List.of((byte) 2, (byte) 3))),
			new FrozenMutation("retain-all-byte-collection",
					buf -> buf.retainAll((ByteCollection) new ByteArrayList(new byte[] {2, 3}))),
			new FrozenMutation("retain-all-collection", buf -> buf.retainAll(List.of((byte) 2, (byte) 3))),
			new FrozenMutation("replace-all-byte",
					buf -> buf.replaceAll((ByteUnaryOperator) value -> (byte) (value + 1))),
			new FrozenMutation("replace-all-int",
					buf -> buf.replaceAll((java.util.function.IntUnaryOperator) value -> (byte) (value + 1))),
			new FrozenMutation("sort", buf -> buf.sort((Comparator<? super Byte>) Comparator.naturalOrder())),
			new FrozenMutation("unstable-sort",
					buf -> buf.unstableSort((Comparator<? super Byte>) Comparator.naturalOrder())),
			new FrozenMutation("remove-if-byte", buf -> buf.removeIf((BytePredicate) value -> value == 2)),
			new FrozenMutation("bulk-copy", buf -> buf.setBytesFromBuf(1, Buf.wrap((byte) 7), 0, 1)),
			new FrozenMutation("primitive-set", buf -> buf.setInt(0, 0x0102_0304)),
			new FrozenMutation("iterator-add", buf -> buf.listIterator(1).add((byte) 7)),
			new FrozenMutation("iterator-set", buf -> {
				ByteListIterator iterator = buf.listIterator();
				iterator.nextByte();
				iterator.set((byte) 7);
			}),
			new FrozenMutation("iterator-remove", buf -> {
				ByteListIterator iterator = buf.listIterator();
				iterator.nextByte();
				iterator.remove();
			}),
			new FrozenMutation("sublist-add", buf -> buf.subListForced(1, 3).add((byte) 7)),
			new FrozenMutation("sublist-set", buf -> buf.subListForced(1, 3).set(0, (byte) 7)),
			new FrozenMutation("sublist-remove", buf -> buf.subListForced(1, 3).removeByte(0)),
			new FrozenMutation("binary-output", buf -> buf.binaryOutputStream().write(7)));

	@Property(tries = 1)
	void freezingRootOrAnyViewDepthRejectsEveryMutationRouteThroughEveryHandle() {
		Random rnd = FuzzTiers.random("buf-freeze");
		int cases = FuzzTiers.cases("buf-freeze", 2, 6, 20, 40);
		for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
			byte[] bytes = new byte[6];
			rnd.nextBytes(bytes);
			int depth = 1 + rnd.nextInt(3);
			int[][] windows = new int[depth][2];
			int parentLength = bytes.length;
			for (int level = 0; level < depth; level++) {
				// every view keeps at least four bytes so the alphabet's indices stay in range;
				// windows are relative to their parent, which itself shrinks with each level
				int size = 4 + rnd.nextInt(parentLength - 4 + 1);
				int from = rnd.nextInt(Math.min(2, parentLength - size + 1));
				windows[level][0] = from;
				windows[level][1] = from + size;
				parentLength = size;
			}
			int frozenIndex = rnd.nextInt(depth + 1);

			// The freeze identity and immutability checks run on one storage...
			List<Buf> handles = buildHandles(bytes, windows);
			Buf frozen = handles.get(frozenIndex);
			assertSame(frozen, frozen.freeze(),
					gate("freeze returns the same handle, case " + caseIndex));
			for (Buf participant : handles) {
				assertFalse(participant.isMutable(),
						gate("every handle is immutable after freezing handle " + frozenIndex
								+ " of " + (depth + 1) + ", case " + caseIndex));
			}

			// ...but each mutation route then runs on a freshly rebuilt storage: a rejected removal
			// through a frozen *view* still shrinks that view's own fastutil size counter (verified:
			// the root bytes never change), so reusing one storage across routes would surface later
			// routes as IndexOutOfBoundsException instead of the freeze rejection under test.
			for (FrozenMutation mutation : FROZEN_ALPHABET) {
				for (int h = 0; h <= depth; h++) {
					List<Buf> fresh = buildHandles(bytes, windows);
					fresh.get(frozenIndex).freeze();
					byte[] before = fresh.get(0).asArray().clone();
					Buf victim = fresh.get(h);
					assertThrows(UnsupportedOperationException.class,
							() -> mutation.action().execute(victim),
							gate(mutation.name() + " through handle " + h + " of " + (depth + 1)
									+ " (frozen " + frozenIndex + "), case " + caseIndex));
					assertArrayEquals(before, fresh.get(0).asArray(),
							gate("no rejected mutation may write through the root, " + mutation.name()
									+ ", case " + caseIndex));
				}
			}
		}
	}

	/** Builds root + the nested views of {@code windows}, with the value seeds every case needs. */
	private static List<Buf> buildHandles(byte[] bytes, int[][] windows) {
		byte[] copy = bytes.clone();
		Buf root = Buf.wrap(copy);
		List<Buf> handles = new ArrayList<>();
		handles.add(root);
		Buf handle = root;
		for (int[] window : windows) {
			handle = handle.subListForced(window[0], window[1]);
			handles.add(handle);
		}
		// value-driven routes must not be silent no-ops: every handle has size >= 4, so writing the
		// seeds through the handles puts all three values in every window; ancestors write first and
		// the deepest handle writes last, and every window contains the deepest one. The sentinel 5
		// keeps retain/remove routes non-empty even if the random bytes happen to be {2,3} only.
		for (Buf participant : handles) {
			participant.set(1, (byte) 5);
			participant.set(2, (byte) 2);
			participant.set(3, (byte) 3);
		}
		return handles;
	}

	@Property(tries = 1)
	void liveViewAliasesTheParentThroughEveryMutationRoute() {
		Random rnd = FuzzTiers.random("buf-alias");
		int cases = FuzzTiers.cases("buf-alias", 2, 6, 16, 32);
		int operations = FuzzTiers.cases("buf-alias-ops", 12, 30, 80, 160);
		for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
			byte[] initial = new byte[7 + rnd.nextInt(17)];
			rnd.nextBytes(initial);
			List<Byte> model = new ArrayList<>();
			for (byte b : initial) model.add(b);
			Buf root = Buf.wrap(initial.clone());
			int from = rnd.nextInt(initial.length - 3);
			int to = from + 4 + rnd.nextInt(initial.length - from - 3);
			Buf view = root.subListForced(from, to);
			// the window model: the view owns its size; only edits through the view change it,
			// parent edits only shift the aliased bytes (verified against the real SubList)
			int viewSize = to - from;

			for (int op = 0; op < operations; op++) {
				byte value = (byte) rnd.nextInt();
				int choice = rnd.nextInt(7);
				if (viewSize == 0 && (choice == 1 || choice == 3)) {
					choice = 2; // an empty view cannot be read or shrunk: insert instead
				}
				switch (choice) {
					case 0 -> { // plain write through the parent, visible through the view
						int at = rnd.nextInt(model.size());
						root.set(at, value);
						model.set(at, value);
					}
					case 1 -> { // plain write through the view, visible in the parent
						int at = rnd.nextInt(view.size());
						view.set(at, value);
						model.set(from + at, value);
					}
					case 2 -> { // structural insert through the view grows the window
						int at = rnd.nextInt(view.size() + 1);
						view.add(at, value);
						model.add(from + at, value);
						viewSize++;
					}
					case 3 -> { // structural remove through the view shrinks the window
						int at = rnd.nextInt(view.size());
						view.removeByte(at);
						model.remove(from + at);
						viewSize--;
					}
					case 4 -> { // root append leaves the window size untouched
						root.add(value);
						model.add(value);
					}
					case 5 -> { // root insert shifts the aliased bytes under the window
						int at = rnd.nextInt(model.size() + 1);
						root.add(at, value);
						model.add(at, value);
					}
					default -> { // root remove behind the window leaves the view untouched
						int behind = model.size() - (from + viewSize);
						if (behind <= 0) {
							root.add(value);
							model.add(value);
						} else {
							int at = from + viewSize + rnd.nextInt(behind);
							root.removeByte(at);
							model.remove(at);
						}
					}
				}
				String what = gate("case " + caseIndex + " op " + op + " window " + from + ".."
						+ (from + viewSize));
				byte[] expectedRoot = new byte[model.size()];
				for (int i = 0; i < model.size(); i++) expectedRoot[i] = model.get(i);
				assertArrayEquals(expectedRoot, root.asArray(), what + " (root)");
				assertArrayEquals(Arrays.copyOfRange(expectedRoot, from, from + viewSize),
						view.asArray(), what + " (view)");
				assertEquals(from, view.getBackingByteArrayOffset(), what + " (offset)");
				assertEquals(viewSize, view.getBackingByteArrayLength(), what + " (length)");
				assertEquals(viewSize, view.size(), what + " (size)");
			}
		}
	}

	@Property(tries = 1)
	void subListForcedComposesOneBackingOffsetAcrossEveryNestingLevel() {
		Random rnd = FuzzTiers.random("buf-nesting");
		int cases = FuzzTiers.cases("buf-nesting", 3, 8, 24, 48);
		for (int caseIndex = 0; caseIndex < cases; caseIndex++) {
			byte[] bytes = new byte[1 + rnd.nextInt(256)];
			rnd.nextBytes(bytes);
			Buf root = Buf.wrap(bytes); // wrap aliases the caller's array: one storage under everything
			Buf view = root;
			int absFrom = 0;
			int absTo = bytes.length;
			int levels = 1 + rnd.nextInt(4);
			for (int level = 0; level < levels; level++) {
				int from = rnd.nextInt(absTo - absFrom + 1);
				int to = from + rnd.nextInt(absTo - absFrom - from + 1);
				view = view.subListForced(from, to);
				// subListForced on a view re-wraps against the root, so offsets compose absolutely
				int parentFrom = absFrom;
				absFrom = parentFrom + from;
				absTo = parentFrom + to;
				String what = gate("case " + caseIndex + " level " + level + " window " + absFrom
						+ ".." + absTo);
				assertEquals(absFrom, view.getBackingByteArrayOffset(), what + " (offset)");
				assertEquals(absTo - absFrom, view.getBackingByteArrayLength(), what + " (length)");
				assertEquals(absFrom, view.getBackingByteArrayFrom(), what + " (from)");
				assertEquals(absTo, view.getBackingByteArrayTo(), what + " (to)");
				assertSame(bytes, view.getBackingByteArray(), what + " (same backing array)");
				assertArrayEquals(Arrays.copyOfRange(bytes, absFrom, absTo), view.asArray(),
						what + " (content)");
				if (absTo > absFrom) {
					int at = absFrom + rnd.nextInt(absTo - absFrom);
					assertEquals(bytes[at], view.getByte(at - absFrom), what + " (sampled byte)");
					// a write into the wrapped array is visible through the nested view: one storage
					bytes[at] ^= (byte) 0x7F;
					assertEquals(bytes[at], view.getByte(at - absFrom), what + " (aliased write)");
				}
			}
		}
	}

	private static String gate(String what) {
		return FuzzTiers.describe() + " | " + what;
	}
}
