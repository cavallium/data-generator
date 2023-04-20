/*
 * Copyright (C) 2002-2022 Sebastiano Vigna
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.cavallium.datagen.nativedata;

import it.unimi.dsi.fastutil.objects.AbstractObjectList;
import it.unimi.dsi.fastutil.objects.ObjectArrays;
import it.unimi.dsi.fastutil.objects.ObjectIterators;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.ObjectSpliterator;
import it.unimi.dsi.fastutil.objects.ObjectSpliterators;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

public class ImmutableWrappedArrayList<K> extends AbstractObjectList<K> implements RandomAccess {
	/** The backing array. */
	protected final K[] a;
	/** The current actual size of the list (never greater than the backing-array length). */
	protected final int size;

	/**
	 * Creates a new array list and fills it with the elements of a given array.
	 *
	 * @param a an array whose elements will be used to fill the array list.
	 */
	public ImmutableWrappedArrayList(final K[] a) {
		this.a = a;
		this.size = a.length;
	}

	/**
	 * Creates an array list using an array of elements.
	 *
	 * @param init a the array the will become the new backing array of the array list.
	 * @return a new array list backed by the given array.
	 */
	@SafeVarargs
	public static <K> ImmutableWrappedArrayList<K> of(final K... init) {
		return new ImmutableWrappedArrayList<>(init);
	}

	private UnsupportedOperationException ex() {
		return new UnsupportedOperationException("Immutable");
	}

	@Override
	public void add(final int index, final K k) {
		throw ex();
	}

	@Override
	public boolean add(final K k) {
		throw ex();
	}

	@Override
	public K get(final int index) {
		if (index >= size) throw new IndexOutOfBoundsException("Index (" + index + ") is greater than or equal to list size (" + size + ")");
		return a[index];
	}

	@Override
	public int indexOf(final Object k) {
		for (int i = 0; i < size; i++) if (java.util.Objects.equals(k, a[i])) return i;
		return -1;
	}

	@Override
	public int lastIndexOf(final Object k) {
		for (int i = size; i-- != 0;) if (java.util.Objects.equals(k, a[i])) return i;
		return -1;
	}

	@Override
	public K remove(final int index) {
		throw ex();
	}

	@Override
	public boolean remove(final Object k) {
		throw ex();
	}

	@Override
	public K set(final int index, final K k) {
		throw ex();
	}

	@Override
	public void clear() {
		throw ex();
	}

	@Override
	public int size() {
		return size;
	}

	@Override
	public void size(final int size) {
		throw ex();
	}

	@Override
	public boolean isEmpty() {
		return size == 0;
	}

	private class SubList extends AbstractObjectList.ObjectRandomAccessSubList<K> {

		protected SubList(int from, int to) {
			super(ImmutableWrappedArrayList.this, from, to);
		}

		// Most of the inherited methods should be fine, but we can override a few of them for performance.
		// Needed because we can't access the parent class' instance variables directly in a different
		// instance of SubList.
		private K[] getParentArray() {
			return a;
		}

		@Override
		public K get(int i) {
			ensureRestrictedIndex(i);
			return a[i + from];
		}

		private final class SubListIterator extends ObjectIterators.AbstractIndexBasedListIterator<K> {
			// We are using pos == 0 to be 0 relative to SubList.from (meaning you need to do a[from + i] when
			// accessing array).
			SubListIterator(int index) {
				super(0, index);
			}

			@Override
			protected K get(int i) {
				return a[from + i];
			}

			@Override
			protected void add(int i, K k) {
				SubList.this.add(i, k);
			}

			@Override
			protected void set(int i, K k) {
				SubList.this.set(i, k);
			}

			@Override
			protected void remove(int i) {
				SubList.this.remove(i);
			}

			@Override
			protected int getMaxPos() {
				return to - from;
			}

			@Override
			public K next() {
				if (!hasNext()) throw new NoSuchElementException();
				return a[from + (lastReturned = pos++)];
			}

			@Override
			public K previous() {
				if (!hasPrevious()) throw new NoSuchElementException();
				return a[from + (lastReturned = --pos)];
			}

			@Override
			public void forEachRemaining(final Consumer<? super K> action) {
				final int max = to - from;
				while (pos < max) {
					action.accept(a[from + (lastReturned = pos++)]);
				}
			}
		}

		@Override
		public @NotNull ObjectListIterator<K> listIterator(int index) {
			return new SubListIterator(index);
		}

		private final class SubListSpliterator extends ObjectSpliterators.LateBindingSizeIndexBasedSpliterator<K> {
			// We are using pos == 0 to be 0 relative to real array 0
			SubListSpliterator() {
				super(from);
			}

			private SubListSpliterator(int pos, int maxPos) {
				super(pos, maxPos);
			}

			@Override
			protected int getMaxPosFromBackingStore() {
				return to;
			}

			@Override
			protected K get(int i) {
				return a[i];
			}

			@Override
			protected SubListSpliterator makeForSplit(int pos, int maxPos) {
				return new SubListSpliterator(pos, maxPos);
			}

			@Override
			public boolean tryAdvance(final Consumer<? super K> action) {
				if (pos >= getMaxPos()) return false;
				action.accept(a[pos++]);
				return true;
			}

			@Override
			public void forEachRemaining(final Consumer<? super K> action) {
				final int max = getMaxPos();
				while (pos < max) {
					action.accept(a[pos++]);
				}
			}
		}

		@Override
		public ObjectSpliterator<K> spliterator() {
			return new SubListSpliterator();
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) return true;
			if (o == null) return false;
			if (!(o instanceof java.util.List)) return false;
			if (o instanceof ImmutableWrappedArrayList) {
				@SuppressWarnings("unchecked") ImmutableWrappedArrayList<K> other = (ImmutableWrappedArrayList<K>)o;
				return Arrays.equals(a, from, to, other.a, 0, other.size());
			}
			if (o instanceof ImmutableWrappedArrayList.SubList) {
				@SuppressWarnings("unchecked")
				ImmutableWrappedArrayList<K>.SubList other = (ImmutableWrappedArrayList<K>.SubList)o;
				return Arrays.equals(a, from, to, other.getParentArray(), other.from, other.to);
			}
			return super.equals(o);
		}

		@SuppressWarnings("unchecked")
		int contentsCompareTo(K[] otherA, int otherAFrom, int otherATo) {
			return Arrays.compare(a, from, to, otherA, otherAFrom, otherATo, (o1, o2) -> ((Comparable<K>)o1).compareTo(o2));
		}

		@SuppressWarnings("unchecked")
		@Override
		public int compareTo(final java.util.List<? extends K> l) {
			if (l instanceof ImmutableWrappedArrayList) {
				@SuppressWarnings("unchecked") ImmutableWrappedArrayList<K> other = (ImmutableWrappedArrayList<K>)l;
				return contentsCompareTo(other.a, 0, other.size());
			}
			if (l instanceof ImmutableWrappedArrayList.SubList) {
				@SuppressWarnings("unchecked")
				ImmutableWrappedArrayList<K>.SubList other = (ImmutableWrappedArrayList<K>.SubList)l;
				return contentsCompareTo(other.getParentArray(), other.from, other.to);
			}
			return super.compareTo(l);
		}
		// We don't override subList as we want AbstractList's "sub-sublist" nesting handling,
		// which would be tricky to do here.
		// TODO Do override it so array access isn't sent through N indirections.
		// This will likely mean making this class static.
	}

	@Override
	public ObjectList<K> subList(int from, int to) {
		if (from == 0 && to == size()) return this;
		ensureIndex(from);
		ensureIndex(to);
		if (from > to) throw new IndexOutOfBoundsException("Start index (" + from + ") is greater than end index (" + to + ")");
		return new SubList(from, to);
	}

	/**
	 * Copies element of this type-specific list into the given array using optimized system calls.
	 *
	 * @param from the start index (inclusive).
	 * @param a the destination array.
	 * @param offset the offset into the destination array where to store the first element copied.
	 * @param length the number of elements to be copied.
	 */
	@Override
	public void getElements(final int from, final Object[] a, final int offset, final int length) {
		ObjectArrays.ensureOffsetLength(a, offset, length);
		System.arraycopy(this.a, from, a, offset, length);
	}

	/**
	 * Removes elements of this type-specific list using optimized system calls.
	 *
	 * @param from the start index (inclusive).
	 * @param to the end index (exclusive).
	 */
	@Override
	public void removeElements(final int from, final int to) {
		throw ex();
	}

	/**
	 * Adds elements to this type-specific list using optimized system calls.
	 *
	 * @param index the index at which to add elements.
	 * @param a the array containing the elements.
	 * @param offset the offset of the first element to add.
	 * @param length the number of elements to add.
	 */
	@Override
	public void addElements(final int index, final K[] a, final int offset, final int length) {
		throw ex();
	}

	/**
	 * Sets elements to this type-specific list using optimized system calls.
	 *
	 * @param index the index at which to start setting elements.
	 * @param a the array containing the elements.
	 * @param offset the offset of the first element to add.
	 * @param length the number of elements to add.
	 */
	@Override
	public void setElements(final int index, final K[] a, final int offset, final int length) {
		ensureIndex(index);
		ObjectArrays.ensureOffsetLength(a, offset, length);
		if (index + length > size) throw new IndexOutOfBoundsException("End index (" + (index + length) + ") is greater than list size (" + size + ")");
		System.arraycopy(a, offset, this.a, index, length);
	}

	@Override
	public void forEach(final Consumer<? super K> action) {
		for (int i = 0; i < size; ++i) {
			action.accept(a[i]);
		}
	}

	@Override
	public boolean addAll(int index, final Collection<? extends K> c) {
		throw ex();
	}

	@Override
	public boolean addAll(final int index, final ObjectList<? extends K> l) {
		throw ex();
	}

	@Override
	public boolean removeAll(final @NotNull Collection<?> c) {
		throw ex();
	}

	@Override
	public Object[] toArray() {
		final int size = size();
		// A subtle part of the spec says the returned array must be Object[] exactly.
		if (size == 0) return it.unimi.dsi.fastutil.objects.ObjectArrays.EMPTY_ARRAY;
		return Arrays.copyOf(a, size, Object[].class);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <K2> K2[] toArray(K2[] a) {
		if (a == null) {
			a = (K2[])new Object[size()];
		} else if (a.length < size()) {
			a = (K2[])Array.newInstance(a.getClass().getComponentType(), size());
		}
		//noinspection ReassignedVariable,SuspiciousSystemArraycopy
		System.arraycopy(this.a, 0, a, 0, size());
		if (a.length > size()) {
			a[size()] = null;
		}
		return a;
	}

	@Override
	public ObjectListIterator<K> listIterator(final int index) {
		ensureIndex(index);
		return new ObjectListIterator<>() {
			int pos = index, last = -1;

			@Override
			public boolean hasNext() {
				return pos < size;
			}

			@Override
			public boolean hasPrevious() {
				return pos > 0;
			}

			@Override
			public K next() {
				if (!hasNext()) {
					throw new NoSuchElementException();
				}
				return a[last = pos++];
			}

			@Override
			public K previous() {
				if (!hasPrevious()) {
					throw new NoSuchElementException();
				}
				return a[last = --pos];
			}

			@Override
			public int nextIndex() {
				return pos;
			}

			@Override
			public int previousIndex() {
				return pos - 1;
			}

			@Override
			public void add(K k) {
				ImmutableWrappedArrayList.this.add(pos++, k);
				last = -1;
			}

			@Override
			public void set(K k) {
				if (last == -1) {
					throw new IllegalStateException();
				}
				ImmutableWrappedArrayList.this.set(last, k);
			}

			@Override
			public void remove() {
				if (last == -1) {
					throw new IllegalStateException();
				}
				ImmutableWrappedArrayList.this.remove(last);
				/* If the last operation was a next(), we are removing an element *before* us, and we must decrease pos correspondingly. */
				if (last < pos) {
					pos--;
				}
				last = -1;
			}

			@Override
			public void forEachRemaining(final Consumer<? super K> action) {
				while (pos < size) {
					action.accept(a[last = pos++]);
				}
			}

			@Override
			public int back(int n) {
				if (n < 0) {
					throw new IllegalArgumentException("Argument must be nonnegative: " + n);
				}
				final int remaining = size - pos;
				if (n < remaining) {
					pos -= n;
				} else {
					n = remaining;
					pos = 0;
				}
				last = pos;
				return n;
			}

			@Override
			public int skip(int n) {
				if (n < 0) {
					throw new IllegalArgumentException("Argument must be nonnegative: " + n);
				}
				final int remaining = size - pos;
				if (n < remaining) {
					pos += n;
				} else {
					n = remaining;
					pos = size;
				}
				last = pos - 1;
				return n;
			}
		};
	}

	// If you update this, you will probably want to update ArraySet as well
	private final class Spliterator implements ObjectSpliterator<K> {
		// Until we split, we will track the size of the list.
		// Once we split, then we stop updating on structural modifications.
		// Aka, size is late-binding.
		boolean hasSplit;
		int pos, max;

		public Spliterator() {
			this(0, ImmutableWrappedArrayList.this.size, false);
		}

		private Spliterator(int pos, int max, boolean hasSplit) {
			assert pos <= max : "pos " + pos + " must be <= max " + max;
			this.pos = pos;
			this.max = max;
			this.hasSplit = hasSplit;
		}

		private int getWorkingMax() {
			return hasSplit ? max : ImmutableWrappedArrayList.this.size;
		}

		@Override
		public int characteristics() {
			return ObjectSpliterators.LIST_SPLITERATOR_CHARACTERISTICS;
		}

		@Override
		public long estimateSize() {
			return getWorkingMax() - pos;
		}

		@Override
		public boolean tryAdvance(final Consumer<? super K> action) {
			if (pos >= getWorkingMax()) return false;
			action.accept(a[pos++]);
			return true;
		}

		@Override
		public void forEachRemaining(final Consumer<? super K> action) {
			for (final int max = getWorkingMax(); pos < max; ++pos) {
				action.accept(a[pos]);
			}
		}

		@Override
		public long skip(long n) {
			if (n < 0) throw new IllegalArgumentException("Argument must be nonnegative: " + n);
			final int max = getWorkingMax();
			if (pos >= max) return 0;
			final int remaining = max - pos;
			if (n < remaining) {
				pos = it.unimi.dsi.fastutil.SafeMath.safeLongToInt(pos + n);
				return n;
			}
			n = remaining;
			pos = max;
			return n;
		}

		@Override
		public ObjectSpliterator<K> trySplit() {
			final int max = getWorkingMax();
			int retLen = (max - pos) >> 1;
			if (retLen <= 1) return null;
			// Update instance max with the last seen list size (if needed) before continuing
			this.max = max;
			int myNewPos = pos + retLen;
			int oldPos = pos;
			this.pos = myNewPos;
			this.hasSplit = true;
			return new Spliterator(oldPos, myNewPos, true);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>
	 * The returned spliterator is late-binding; it will track structural changes after the current
	 * index, up until the first {@link java.util.Spliterator#trySplit() trySplit()}, at which point the
	 * maximum index will be fixed. <br>
	 * Structural changes before the current index or after the first
	 * {@link java.util.Spliterator#trySplit() trySplit()} will result in unspecified behavior.
	 */
	@Override
	public ObjectSpliterator<K> spliterator() {
		// If it wasn't for the possibility of the list being expanded or shrunk,
		// we could return SPLITERATORS.wrap(a, 0, size).
		return new Spliterator();
	}

	@Override
	public void sort(final Comparator<? super K> comp) {
		if (comp == null) {
			ObjectArrays.stableSort(a, 0, size);
		} else {
			ObjectArrays.stableSort(a, 0, size, comp);
		}
	}

	@Override
	public void unstableSort(final Comparator<? super K> comp) {
		if (comp == null) {
			ObjectArrays.unstableSort(a, 0, size);
		} else {
			ObjectArrays.unstableSort(a, 0, size, comp);
		}
	}

	/**
	 * Compares this type-specific array list to another one.
	 *
	 * This method exists only for sake of efficiency. The implementation inherited from the
	 *          abstract implementation would already work.
	 *
	 * @param l a type-specific array list.
	 * @return true if the argument contains the same elements of this type-specific array list.
	 */
	public boolean equals(final ImmutableWrappedArrayList<K> l) {
		return Arrays.equals(a, 0, size(), l.a, 0, l.size());
	}

	@SuppressWarnings({ "unchecked", "unlikely-arg-type" })
	@Override
	public boolean equals(final Object o) {
		if (o == this) return true;
		if (o == null) return false;
		if (!(o instanceof java.util.List)) return false;
		if (o instanceof ImmutableWrappedArrayList) {
			// Safe cast because we are only going to take elements from other list, never give them
			return equals((ImmutableWrappedArrayList<K>)o);
		}
		if (o instanceof ImmutableWrappedArrayList.SubList) {
			// Safe cast because we are only going to take elements from other list, never give them
			// Sublist has an optimized sub-array based comparison, reuse that.
			return o.equals(this);
		}
		return super.equals(o);
	}

	/**
	 * Compares this array list to another array list.
	 *
	 * This method exists only for sake of efficiency. The implementation inherited from the
	 *          abstract implementation would already work.
	 *
	 * @param l an array list.
	 * @return a negative integer, zero, or a positive integer as this list is lexicographically less
	 *         than, equal to, or greater than the argument.
	 */
	@SuppressWarnings("unchecked")
	public int compareTo(final ImmutableWrappedArrayList<? extends K> l) {
		return Arrays.compare(a, 0, size(), l.a, 0, l.size(), (o1, o2) -> ((Comparable<K>) o1).compareTo(o2));
	}

	@SuppressWarnings("unchecked")
	@Override
	public int compareTo(final java.util.List<? extends K> l) {
		if (l instanceof ImmutableWrappedArrayList) {
			return compareTo((ImmutableWrappedArrayList<? extends K>)l);
		}
		if (l instanceof ImmutableWrappedArrayList.SubList) {
			// Must negate because we are inverting the order of the comparison.
			return -((ImmutableWrappedArrayList<K>.SubList)l).compareTo(this);
		}
		return super.compareTo(l);
	}
}
