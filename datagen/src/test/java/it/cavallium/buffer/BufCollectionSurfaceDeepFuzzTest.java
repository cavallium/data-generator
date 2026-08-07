package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.bytes.ByteConsumer;
import it.unimi.dsi.fastutil.bytes.ByteIterator;
import it.unimi.dsi.fastutil.bytes.ByteList;
import it.unimi.dsi.fastutil.bytes.ByteListIterator;
import it.unimi.dsi.fastutil.bytes.BytePredicate;
import it.unimi.dsi.fastutil.bytes.ByteSpliterator;
import it.unimi.dsi.fastutil.bytes.ByteUnaryOperator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Model-based fuzzing for the full fastutil collection surface inherited by {@link Buf}.
 *
 * <p>The codec tests mostly use fixed-size buffers. These state machines deliberately exercise
 * growth, shrinking, nested live views, primitive and boxed overloads, iterators, spliterators,
 * stack methods, and every mutator that must respect {@link Buf#freeze()}.</p>
 */
class BufCollectionSurfaceDeepFuzzTest {

	private static final long COLLECTION_SEED = 0x13D9_A6C7_4B20_EF85L;
	private static final long CONSTRUCTOR_SEED = 0x65A1_0F3C_D947_2B8EL;
	private static final long ITERATION_SEED = 0x7C42_E905_18AD_63BFL;
	private static final int STATE_CASES = 96;
	private static final int OPERATIONS_PER_CASE = 2_000;
	private static final int CONSTRUCTOR_CASES = 20_000;
	private static final int ITERATION_CASES = 20_000;

	@Test
	void everyMutableCollectionPathMatchesAnArrayListStateMachine() {
		var seedSource = new Random(COLLECTION_SEED);
		for (int caseIndex = 0; caseIndex < STATE_CASES; caseIndex++) {
			long caseSeed = seedSource.nextLong();
			var random = new Random(caseSeed);
			byte[] initial = new byte[random.nextInt(129)];
			random.nextBytes(initial);
			Buf actual = switch (caseIndex % 4) {
				case 0 -> Buf.create();
				case 1 -> Buf.create(random.nextInt(257));
				case 2 -> new ByteListBuf(initial.clone());
				default -> new ByteListBuf(new ByteArrayList(initial));
			};
			if (actual.isEmpty()) actual.addElements(0, initial);
			var expected = boxed(initial);

			for (int operationIndex = 0; operationIndex < OPERATIONS_PER_CASE; operationIndex++) {
				int operation = random.nextInt(30);
				String diagnostic = diagnostic(caseSeed, caseIndex, operationIndex, operation,
						actual.size());
				switch (operation) {
					case 0 -> {
						if (actual.size() < 512) {
							byte value = randomByte(random);
							assertEquals(expected.add(value), actual.add(value), diagnostic);
						}
					}
					case 1 -> {
						if (actual.size() < 512) {
							int index = random.nextInt(actual.size() + 1);
							byte value = randomByte(random);
							actual.add(index, value);
							expected.add(index, value);
						}
					}
					case 2 -> {
						if (!actual.isEmpty()) {
							int index = random.nextInt(actual.size());
							assertEquals(expected.remove(index).byteValue(), actual.removeByte(index), diagnostic);
						}
					}
					case 3 -> {
						byte value = randomByte(random);
						assertEquals(expected.remove(Byte.valueOf(value)), actual.rem(value), diagnostic);
					}
					case 4 -> {
						if (!actual.isEmpty()) {
							int index = random.nextInt(actual.size());
							byte value = randomByte(random);
							assertEquals(expected.set(index, value).byteValue(), actual.set(index, value), diagnostic);
						}
					}
					case 5 -> {
						if (actual.size() < 480) {
							byte[] values = randomBytes(random, 33);
							int offset = random.nextInt(values.length + 1);
							int length = random.nextInt(values.length - offset + 1);
							int index = random.nextInt(actual.size() + 1);
							actual.addElements(index, values, offset, length);
							for (int i = 0; i < length; i++) expected.add(index + i, values[offset + i]);
						}
					}
					case 6 -> {
						int from = random.nextInt(actual.size() + 1);
						int to = from + random.nextInt(actual.size() - from + 1);
						actual.removeElements(from, to);
						expected.subList(from, to).clear();
					}
					case 7 -> {
						if (!actual.isEmpty()) {
							int index = random.nextInt(actual.size());
							byte[] values = randomBytes(random, actual.size() - index + 1);
							int length = random.nextInt(Math.min(values.length, actual.size() - index) + 1);
							actual.setElements(index, values, 0, length);
							for (int i = 0; i < length; i++) expected.set(index + i, values[i]);
						}
					}
					case 8 -> {
						if (actual.size() < 480) {
							ByteArrayList values = new ByteArrayList(randomBytes(random, 33));
							int index = random.nextInt(actual.size() + 1);
							assertEquals(expected.addAll(index, boxed(values.toByteArray())),
									actual.addAll(index, (ByteCollection) values), diagnostic);
						}
					}
					case 9 -> {
						if (actual.size() < 480) {
							ByteList values = new ByteArrayList(randomBytes(random, 33));
							int index = random.nextInt(actual.size() + 1);
							assertEquals(expected.addAll(index, boxed(values.toByteArray())),
									actual.addAll(index, values), diagnostic);
						}
					}
					case 10 -> {
						ByteCollection values = new ByteArrayList(randomBytes(random, 17));
						boolean changed = expected.removeIf(value -> values.contains(value.byteValue()));
						assertEquals(changed, actual.removeAll(values), diagnostic);
					}
					case 11 -> {
						ByteCollection values = new ByteArrayList(randomBytes(random, 17));
						boolean changed = expected.removeIf(value -> !values.contains(value.byteValue()));
						assertEquals(changed, actual.retainAll(values), diagnostic);
					}
					case 12 -> {
						int mask = random.nextInt();
						IntPredicate predicate = value -> ((value ^ mask) & 7) == 0;
						assertEquals(expected.removeIf(value -> predicate.test(value)),
								actual.removeIf(predicate), diagnostic);
					}
					case 13 -> {
						int delta = random.nextInt();
						IntUnaryOperator operator = value -> (byte) (value + delta);
						actual.replaceAll(operator);
						expected.replaceAll(value -> (byte) (value + delta));
					}
					case 14 -> {
						if (actual.size() < 512) {
							byte value = randomByte(random);
							((ByteListBuf) actual).push(value);
							expected.add(value);
						}
						if (!actual.isEmpty() && random.nextBoolean()) {
							assertEquals(expected.getLast().byteValue(),
									((ByteListBuf) actual).topByte(), diagnostic);
							assertEquals(expected.removeLast().byteValue(),
									((ByteListBuf) actual).popByte(), diagnostic);
						}
					}
					case 15 -> mutateWithListIterator(actual, expected, random, diagnostic);
					case 16 -> mutateLiveSubList(actual, expected, random, diagnostic);
					case 17 -> {
						int newSize = random.nextInt(513);
						actual.size(newSize);
						while (expected.size() > newSize) expected.removeLast();
						while (expected.size() < newSize) expected.add((byte) 0);
					}
					case 18 -> {
						actual.clear();
						expected.clear();
					}
					case 19 -> {
						actual.sort((Comparator<? super Byte>) Comparator.naturalOrder());
						expected.sort(Comparator.naturalOrder());
					}
					case 20 -> {
						actual.unstableSort((Comparator<? super Byte>) Comparator.reverseOrder());
						expected.sort(Comparator.reverseOrder());
					}
					case 21 -> {
						int from = random.nextInt(actual.size() + 1);
						int length = random.nextInt(actual.size() - from + 1);
						byte[] destination = new byte[length + 4];
						Arrays.fill(destination, (byte) 0x55);
						actual.getElements(from, destination, 2, length);
						assertArrayEquals(modelRange(expected, from, length),
								Arrays.copyOfRange(destination, 2, 2 + length), diagnostic);
					}
					case 22 -> {
						if (actual.size() < 480) {
							Collection<Byte> values = boxed(randomBytes(random, 33));
							assertEquals(expected.addAll(values), actual.addAll(values), diagnostic);
						}
					}
					case 23 -> {
						Collection<Byte> values = boxed(randomBytes(random, 17));
						assertEquals(expected.removeAll(values), actual.removeAll(values), diagnostic);
					}
					case 24 -> {
						Collection<Byte> values = boxed(randomBytes(random, 17));
						assertEquals(expected.retainAll(values), actual.retainAll(values), diagnostic);
					}
					case 25 -> {
						int mask = random.nextInt();
						Predicate<Byte> predicate = value -> ((value ^ mask) & 15) == 1;
						assertEquals(expected.removeIf(predicate), actual.removeIf(predicate), diagnostic);
					}
					case 26 -> {
						int xor = random.nextInt();
						UnaryOperator<Byte> operator = value -> (byte) (value ^ xor);
						actual.replaceAll(operator);
						expected.replaceAll(operator);
					}
					case 27 -> {
						((ByteListBuf) actual).trim(random.nextInt(513));
						assertEquals(expected.size(), actual.size(), diagnostic);
					}
					case 28 -> assertSearchAndArraySurface(actual, expected, random, diagnostic);
					case 29 -> assertIterationSurface(actual, expected, random, diagnostic);
					default -> throw new AssertionError(operation);
				}
				assertArrayEquals(toByteArray(expected), actual.toByteArray(), diagnostic);
				assertEquals(expected.size(), actual.size(), diagnostic);
			}
		}
	}

	@Test
	void constructorsFactoriesWrappingCopyingAndAliasingFuzzEveryShape() {
		var random = new Random(CONSTRUCTOR_SEED);
		for (int caseIndex = 0; caseIndex < CONSTRUCTOR_CASES; caseIndex++) {
			byte[] bytes = randomBytes(random, 257);
			String diagnostic = "seed=" + CONSTRUCTOR_SEED + ", case=" + caseIndex
					+ ", length=" + bytes.length;
			var boxed = boxed(bytes);
			var byteList = new ByteArrayList(bytes);

			assertArrayEquals(bytes, new ByteListBuf(bytes.clone()).asArray(), diagnostic);
			assertArrayEquals(bytes, new ByteListBuf((Collection<? extends Byte>) boxed).asArray(), diagnostic);
			assertArrayEquals(bytes, new ByteListBuf((ByteCollection) byteList).asArray(), diagnostic);
			assertArrayEquals(bytes, new ByteListBuf((ByteList) byteList).asArray(), diagnostic);
			assertArrayEquals(bytes, new ByteListBuf((Iterator<? extends Byte>) boxed.iterator()).asArray(), diagnostic);
			assertArrayEquals(bytes, new ByteListBuf((ByteIterator) byteList.iterator()).asArray(), diagnostic);

			int from = random.nextInt(bytes.length + 1);
			int to = from + random.nextInt(bytes.length - from + 1);
			assertArrayEquals(Arrays.copyOfRange(bytes, from, to),
					new ByteListBuf(bytes, from, to - from).asArray(), diagnostic);

			byte[] wrappedBytes = bytes.clone();
			Buf wrapped = Buf.wrap(wrappedBytes);
			assertSame(wrappedBytes, wrapped.asArrayStrict(), diagnostic);
			assertSame(wrappedBytes, wrapped.asUnboundedArrayStrict(), diagnostic);
			assertSame(wrapped, Buf.wrap((ByteList) wrapped), diagnostic);
			if (wrappedBytes.length != 0) {
				wrappedBytes[random.nextInt(wrappedBytes.length)] ^= (byte) 0x80;
				assertArrayEquals(wrappedBytes, wrapped.asArray(), diagnostic);
			}

			Buf copy = Buf.copyOf(wrappedBytes);
			assertNotSame(wrappedBytes, copy.asArrayStrict(), diagnostic);
			assertArrayEquals(wrappedBytes, copy.asArray(), diagnostic);
			if (wrappedBytes.length != 0) {
				byte before = copy.getByte(0);
				wrappedBytes[0]++;
				assertEquals(before, copy.getByte(0), diagnostic);
			}

			Buf range = Buf.wrap(wrappedBytes, from, to);
			assertArrayEquals(Arrays.copyOfRange(wrappedBytes, from, to), range.asArray(), diagnostic);
			if (from != 0 || to != wrappedBytes.length) assertNull(range.asArrayStrict(), diagnostic);
			assertEquals(from, range.getBackingByteArrayOffset(), diagnostic);
			assertEquals(to - from, range.getBackingByteArrayLength(), diagnostic);
			assertSame(wrappedBytes, range.getBackingByteArray(), diagnostic);

			Buf optimizedByteList = Buf.wrap(new ByteArrayList(wrappedBytes));
			assertArrayEquals(wrappedBytes, optimizedByteList.asArray(), diagnostic);
			assertArrayEquals(Arrays.copyOfRange(wrappedBytes, from, to),
					Buf.wrap(new ByteArrayList(wrappedBytes), from, to).asArray(), diagnostic);
			assertEquals(0, Buf.create().size(), diagnostic);
			assertEquals(0, Buf.create(random.nextInt(257)).size(), diagnostic);
			assertArrayEquals(new byte[to - from], Buf.createZeroes(to - from).asArray(), diagnostic);
		}
	}

	@Test
	void iteratorsSpliteratorsNestedViewsAndComparisonsPreserveEncounterOrder() {
		var random = new Random(ITERATION_SEED);
		for (int caseIndex = 0; caseIndex < ITERATION_CASES; caseIndex++) {
			byte[] bytes = randomBytes(random, 513);
			Buf root = Buf.wrap(bytes.clone());
			int first = random.nextInt(bytes.length + 1);
			int second = first + random.nextInt(bytes.length - first + 1);
			int third = random.nextInt(second - first + 1);
			int fourth = third + random.nextInt(second - first - third + 1);
			Buf view = root.subListForced(first, second).subListForced(third, fourth);
			byte[] expected = Arrays.copyOfRange(bytes, first + third, first + fourth);
			String diagnostic = "seed=" + ITERATION_SEED + ", case=" + caseIndex
					+ ", rootLength=" + bytes.length + ", range=" + (first + third)
					+ ".." + (first + fourth);

			var forward = new ArrayList<Byte>();
			ByteListIterator iterator = view.listIterator();
			while (iterator.hasNext()) forward.add(iterator.nextByte());
			assertArrayEquals(expected, toByteArray(forward), diagnostic);

			var backward = new ArrayList<Byte>();
			while (iterator.hasPrevious()) backward.add(iterator.previousByte());
			var expectedBackward = boxed(expected);
			java.util.Collections.reverse(expectedBackward);
			assertArrayEquals(toByteArray(expectedBackward), toByteArray(backward), diagnostic);

			var splitValues = new ArrayList<Byte>();
			ByteSpliterator remainder = view.spliterator();
			ByteSpliterator prefix = remainder.trySplit();
			ByteConsumer collector = splitValues::add;
			if (prefix != null) prefix.forEachRemaining(collector);
			remainder.forEachRemaining(collector);
			assertArrayEquals(expected, toByteArray(splitValues), diagnostic);

			assertEquals(Arrays.hashCode(expected), view.hashCode(), diagnostic);
			assertEquals(boxed(expected), view, diagnostic);
			assertEquals(0, view.compareTo(boxed(expected)), diagnostic);
			byte[] other = expected.clone();
			if (other.length != 0) other[random.nextInt(other.length)] ^= 1;
			int expectedOrder = expected.length != other.length
					? Integer.compare(expected.length, other.length)
					: Arrays.compareUnsigned(expected, other);
			assertEquals(Integer.signum(expectedOrder),
					Integer.signum(view.compareTo(Buf.wrap(other))), diagnostic);
		}
	}

	@Test
	@SuppressWarnings({"deprecation", "removal"})
	void freezeRejectsEveryPublicMutationRouteIncludingLiveViewsAndIterators() {
		List<FrozenMutation> mutations = List.of(
				new FrozenMutation("add-byte", buf -> buf.add((byte) 9)),
				new FrozenMutation("add-index", buf -> buf.add(1, (byte) 9)),
				new FrozenMutation("add-boxed", buf -> buf.add(Byte.valueOf((byte) 9))),
				new FrozenMutation("add-index-boxed", buf -> buf.add(1, Byte.valueOf((byte) 9))),
				new FrozenMutation("remove-byte", buf -> buf.removeByte(1)),
				new FrozenMutation("remove-boxed", buf -> buf.remove(Byte.valueOf((byte) 2))),
				new FrozenMutation("remove-index-boxed", buf -> buf.remove(1)),
				new FrozenMutation("rem", buf -> buf.rem((byte) 2)),
				new FrozenMutation("set-byte", buf -> buf.set(1, (byte) 9)),
				new FrozenMutation("set-boxed", buf -> buf.set(1, Byte.valueOf((byte) 9))),
				new FrozenMutation("clear", Buf::clear),
				new FrozenMutation("resize", buf -> buf.size(7)),
				new FrozenMutation("trim", buf -> ((ByteListBuf) buf).trim()),
				new FrozenMutation("trim-size", buf -> ((ByteListBuf) buf).trim(1)),
				new FrozenMutation("remove-elements", buf -> buf.removeElements(1, 3)),
				new FrozenMutation("add-elements", buf -> buf.addElements(1, new byte[] {7, 8})),
				new FrozenMutation("add-elements-range",
						buf -> buf.addElements(1, new byte[] {7, 8, 9}, 1, 2)),
				new FrozenMutation("set-elements", buf -> buf.setElements(new byte[] {7, 8, 9, 10})),
				new FrozenMutation("set-elements-index", buf -> buf.setElements(1, new byte[] {7, 8})),
				new FrozenMutation("set-elements-range",
						buf -> buf.setElements(1, new byte[] {7, 8, 9}, 1, 2)),
				new FrozenMutation("add-all-byte-collection",
						buf -> buf.addAll((ByteCollection) new ByteArrayList(new byte[] {7, 8}))),
				new FrozenMutation("add-all-byte-list",
						buf -> buf.addAll((ByteList) new ByteArrayList(new byte[] {7, 8}))),
				new FrozenMutation("add-all-collection",
						buf -> buf.addAll((Collection<? extends Byte>) List.of((byte) 7, (byte) 8))),
				new FrozenMutation("add-all-index-byte-collection",
						buf -> buf.addAll(1, (ByteCollection) new ByteArrayList(new byte[] {7, 8}))),
				new FrozenMutation("add-all-index-byte-list",
						buf -> buf.addAll(1, (ByteList) new ByteArrayList(new byte[] {7, 8}))),
				new FrozenMutation("add-all-index-collection",
						buf -> buf.addAll(1, (Collection<? extends Byte>) List.of((byte) 7, (byte) 8))),
				new FrozenMutation("remove-all-byte-collection",
						buf -> buf.removeAll((ByteCollection) new ByteArrayList(new byte[] {2, 3}))),
				new FrozenMutation("remove-all-collection", buf -> buf.removeAll(List.of((byte) 2, (byte) 3))),
				new FrozenMutation("retain-all-byte-collection",
						buf -> buf.retainAll((ByteCollection) new ByteArrayList(new byte[] {2, 3}))),
				new FrozenMutation("retain-all-collection", buf -> buf.retainAll(List.of((byte) 2, (byte) 3))),
				new FrozenMutation("replace-all-byte",
						buf -> buf.replaceAll((ByteUnaryOperator) value -> (byte) (value + 1))),
				new FrozenMutation("replace-all-int",
						buf -> buf.replaceAll((IntUnaryOperator) value -> (byte) (value + 1))),
				new FrozenMutation("replace-all-boxed",
						buf -> buf.replaceAll((UnaryOperator<Byte>) value -> (byte) (value + 1))),
				new FrozenMutation("sort", buf -> buf.sort((Comparator<? super Byte>) Comparator.naturalOrder())),
				new FrozenMutation("unstable-sort",
						buf -> buf.unstableSort((Comparator<? super Byte>) Comparator.naturalOrder())),
				new FrozenMutation("remove-if-byte",
						buf -> buf.removeIf((BytePredicate) value -> value == 2)),
				new FrozenMutation("remove-if-int",
						buf -> buf.removeIf((IntPredicate) value -> value == 2)),
				new FrozenMutation("remove-if-boxed",
						buf -> buf.removeIf((Predicate<? super Byte>) value -> value == 2)),
				new FrozenMutation("push-byte", buf -> ((ByteListBuf) buf).push((byte) 7)),
				new FrozenMutation("push-boxed", buf -> ((ByteListBuf) buf).push(Byte.valueOf((byte) 7))),
				new FrozenMutation("pop-byte", buf -> ((ByteListBuf) buf).popByte()),
				new FrozenMutation("pop-boxed", buf -> ((ByteListBuf) buf).pop()),
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

		for (FrozenMutation mutation : mutations) {
			Buf root = Buf.wrap(new byte[] {1, 2, 3, 4});
			Buf frozen = root.freeze();
			assertSame(root, frozen, mutation.name());
			assertFalse(frozen.isMutable(), mutation.name());
			byte[] before = frozen.asArray().clone();
			assertThrows(UnsupportedOperationException.class, () -> mutation.action().execute(frozen),
					mutation.name());
			assertArrayEquals(before, frozen.asArray(), mutation.name());
		}
	}

	private static void mutateWithListIterator(Buf actual,
			List<Byte> expected,
			Random random,
			String diagnostic) {
		int index = random.nextInt(actual.size() + 1);
		ByteListIterator actualIterator = actual.listIterator(index);
		ListIterator<Byte> expectedIterator = expected.listIterator(index);
		int action = random.nextInt(4);
		if (action == 0 && actualIterator.hasNext()) {
			assertEquals(expectedIterator.next().byteValue(), actualIterator.nextByte(), diagnostic);
			byte value = randomByte(random);
			actualIterator.set(value);
			expectedIterator.set(value);
		} else if (action == 1 && actualIterator.hasNext()) {
			assertEquals(expectedIterator.next().byteValue(), actualIterator.nextByte(), diagnostic);
			actualIterator.remove();
			expectedIterator.remove();
		} else if (action == 2 && actual.size() < 512) {
			byte value = randomByte(random);
			actualIterator.add(value);
			expectedIterator.add(value);
		} else if (actualIterator.hasPrevious()) {
			assertEquals(expectedIterator.previous().byteValue(), actualIterator.previousByte(), diagnostic);
		}
	}

	private static void mutateLiveSubList(Buf actual,
			List<Byte> expected,
			Random random,
			String diagnostic) {
		int from = random.nextInt(actual.size() + 1);
		int to = from + random.nextInt(actual.size() - from + 1);
		Buf actualView = actual.subListForced(from, to);
		List<Byte> expectedView = expected.subList(from, to);
		int action = random.nextInt(5);
		if (action == 0 && actual.size() < 512) {
			int index = random.nextInt(actualView.size() + 1);
			byte value = randomByte(random);
			actualView.add(index, value);
			expectedView.add(index, value);
		} else if (action == 1 && !actualView.isEmpty()) {
			int index = random.nextInt(actualView.size());
			assertEquals(expectedView.remove(index).byteValue(), actualView.removeByte(index), diagnostic);
		} else if (action == 2 && !actualView.isEmpty()) {
			int index = random.nextInt(actualView.size());
			byte value = randomByte(random);
			assertEquals(expectedView.set(index, value).byteValue(), actualView.set(index, value), diagnostic);
		} else if (action == 3) {
			actualView.clear();
			expectedView.clear();
		} else {
			assertArrayEquals(toByteArray(expectedView), actualView.asArray(), diagnostic);
		}
	}

	private static void assertSearchAndArraySurface(Buf actual,
			List<Byte> expected,
			Random random,
			String diagnostic) {
		byte value = randomByte(random);
		assertEquals(expected.contains(value), actual.contains(value), diagnostic);
		assertEquals(expected.indexOf(value), actual.indexOf(value), diagnostic);
		assertEquals(expected.lastIndexOf(value), actual.lastIndexOf(value), diagnostic);
		assertArrayEquals(toByteArray(expected), actual.toByteArray(), diagnostic);
		byte[] target = new byte[expected.size() + 7];
		byte[] returned = actual.toArray(target);
		for (int index = 0; index < expected.size(); index++) {
			assertEquals(expected.get(index).byteValue(), returned[index], diagnostic);
		}
	}

	private static void assertIterationSurface(Buf actual,
			List<Byte> expected,
			Random random,
			String diagnostic) {
		var values = new ArrayList<Byte>();
		ByteIterator iterator = actual.iterator();
		int prefix = random.nextInt(actual.size() + 1);
		for (int index = 0; index < prefix; index++) values.add(iterator.nextByte());
		iterator.forEachRemaining((ByteConsumer) values::add);
		assertArrayEquals(toByteArray(expected), toByteArray(values), diagnostic);
	}

	private static byte[] modelRange(List<Byte> values, int from, int length) {
		byte[] result = new byte[length];
		for (int index = 0; index < length; index++) result[index] = values.get(from + index);
		return result;
	}

	private static byte[] randomBytes(Random random, int exclusiveMaximumLength) {
		byte[] result = new byte[random.nextInt(exclusiveMaximumLength)];
		random.nextBytes(result);
		return result;
	}

	private static byte randomByte(Random random) {
		return (byte) random.nextInt();
	}

	private static ArrayList<Byte> boxed(byte[] values) {
		var result = new ArrayList<Byte>(values.length);
		for (byte value : values) result.add(value);
		return result;
	}

	private static byte[] toByteArray(List<Byte> values) {
		byte[] result = new byte[values.size()];
		for (int index = 0; index < result.length; index++) result[index] = values.get(index);
		return result;
	}

	private static String diagnostic(long seed,
			int caseIndex,
			int operationIndex,
			int operation,
			int size) {
		return "seed=" + seed + ", case=" + caseIndex + ", operationIndex=" + operationIndex
				+ ", operation=" + operation + ", size=" + size;
	}

	private record FrozenMutation(String name, FrozenAction action) {}

	@FunctionalInterface
	private interface FrozenAction {
		void execute(Buf buffer) throws Exception;
	}
}
