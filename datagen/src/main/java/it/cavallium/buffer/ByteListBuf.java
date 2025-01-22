package it.cavallium.buffer;

import static java.util.Objects.checkFromIndexSize;
import static java.util.Objects.checkFromToIndex;

import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutput;
import it.unimi.dsi.fastutil.bytes.AbstractByteList;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteArrays;
import it.unimi.dsi.fastutil.bytes.ByteCollection;
import it.unimi.dsi.fastutil.bytes.ByteConsumer;
import it.unimi.dsi.fastutil.bytes.ByteIterator;
import it.unimi.dsi.fastutil.bytes.ByteIterators;
import it.unimi.dsi.fastutil.bytes.ByteList;
import it.unimi.dsi.fastutil.bytes.ByteListIterator;
import it.unimi.dsi.fastutil.bytes.BytePredicate;
import it.unimi.dsi.fastutil.bytes.ByteSpliterator;
import it.unimi.dsi.fastutil.bytes.ByteSpliterators;
import it.unimi.dsi.fastutil.bytes.ByteUnaryOperator;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

class ByteListBuf extends ByteArrayList implements Buf {

	private static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();
	private static final String IMMUTABLE_ERROR = "The buffer is immutable";
	private static final VariableLengthLexiconographicComparator VAR_LENGTH_LEX_COMP = new VariableLengthLexiconographicComparator();

	private boolean immutable;

	protected ByteListBuf(byte[] a, boolean wrapped) {
		super(a, wrapped);
	}

	public ByteListBuf(int capacity) {
		super(capacity);
	}

	public ByteListBuf() {
	}

	public ByteListBuf(Collection<? extends Byte> c) {
		super(c);
	}

	public ByteListBuf(ByteCollection c) {
		super(c);
	}

	public ByteListBuf(ByteList l) {
		super(l);
	}

	public ByteListBuf(byte[] a) {
		super(a);
	}

	public ByteListBuf(byte[] a, int offset, int length) {
		super(a, offset, length);
	}

	public ByteListBuf(Iterator<? extends Byte> i) {
		super(i);
	}

	public ByteListBuf(ByteIterator i) {
		super(i);
	}

	/**
	 * Wraps a given array into an array list of given size.
	 *
	 * <p>
	 * Note it is guaranteed that the type of the array returned by {@link #elements()} will be the same
	 * (see the comments in the class documentation).
	 *
	 * @param a an array to wrap.
	 * @param length the length of the resulting array list.
	 * @return a new array list of the given size, wrapping the given array.
	 */
	public static ByteListBuf wrap(final byte[] a, final int length) {
		ByteArrays.ensureFromTo(a, 0, length);
		final ByteListBuf l = new ByteListBuf(a, true);
		l.size = length;
		return l;
	}

	/**
	 * Wraps a given array into an array list.
	 *
	 * <p>
	 * Note it is guaranteed that the type of the array returned by {@link #elements()} will be the same
	 * (see the comments in the class documentation).
	 *
	 * @param a an array to wrap.
	 * @return a new array list wrapping the given array.
	 */
	public static ByteListBuf wrap(final byte[] a) {
		return wrap(a, a.length);
	}

	/**
	 * Creates a new empty array list.
	 *
	 * @return a new empty array list.
	 */
	public static ByteListBuf of() {
		return new ByteListBuf();
	}

	/**
	 * Creates an array list using an array of elements.
	 *
	 * @param init a the array the will become the new backing array of the array list.
	 * @return a new array list backed by the given array.
	 * @see #wrap
	 */

	public static ByteListBuf of(final byte... init) {
		return wrap(init);
	}

	@Override
	public byte @NotNull [] asArray() {
		if (this.size() == a.length) {
			return this.a;
		} else {
			return this.toByteArray();
		}
	}

	@Override
	public byte @Nullable [] asArrayStrict() {
		if (this.size() == a.length) {
			return a;
		} else {
			return null;
		}
	}

	@Override
	public byte[] asUnboundedArray() {
		return a;
	}

	@Override
	public ByteBuffer asHeapByteBuffer() {
		return ByteBuffer.wrap(a, 0, size());
	}

	@Override
	public byte @Nullable [] asUnboundedArrayStrict() {
		return a;
	}

	@Override
	public boolean isMutable() {
		return !immutable;
	}

	@Override
	public ByteListBuf freeze() {
		immutable = true;
		return this;
	}

	@Override
	public Buf subList(int from, int to) {
		if (from == 0 && to == size()) return this;
		return subListForced(from, to);
	}

	@VisibleForTesting
	public Buf subListForced(int from, int to) {
		checkFromToIndex(from, to, this.size());
		return new SubList(from, to);
	}

	@Override
	public Buf copyOfRange(int from, int to) {
		if (from == 0 && to == size()) {
			return copy();
		} else {
			return ByteListBuf.wrap(Arrays.copyOfRange(this.a, from, to), to - from);
		}
	}

	@Override
	public Buf copy() {
		return ByteListBuf.wrap(this.a.clone(), this.size);
	}

	@Override
	public SafeByteArrayInputStream binaryInputStream() {
		return new SafeByteArrayInputStream(this.a, 0, this.size);
	}

	@Override
	public void writeTo(SafeDataOutput dataOutput) {
		dataOutput.write(this.a, 0, this.size);
	}

	@Override
	public SafeByteArrayOutputStream binaryOutputStream(int from, int to) {
		checkFromToIndex(from, to, size);
		return new SafeByteArrayOutputStream(a, from, to);
	}

	@Override
	public boolean equals(int aStartIndex, Buf b, int bStartIndex, int length) {
		if (aStartIndex + length > size()) {
			return false;
		}
		return b.equals(bStartIndex, this.a, aStartIndex, length);
	}

	@Override
	public boolean equals(int aStartIndex, byte[] b, int bStartIndex, int length) {
		if (aStartIndex < 0) return false;
		if (aStartIndex + length > this.size) {
			return false;
		}
		if (bStartIndex + length > b.length) {
			return false;
		}
		return Arrays.equals(a, aStartIndex, aStartIndex + length, b, bStartIndex, bStartIndex + length);
	}

	@Override
	public String toString(Charset charset) {
		return getString(0, size, charset);
	}

	@Override
	public String toString() {
		return HEX_FORMAT.formatHex(a, 0, size());
	}

	@Override
	public String getString(int i, int length, Charset charset) {
		return new String(a, i, length, charset);
	}

	class SubList extends AbstractByteList.ByteRandomAccessSubList implements Buf {
		@Serial
		private static final long serialVersionUID = -3185226345314976296L;

		protected SubList(int from, int to) {
			super(ByteListBuf.this, from, to);
		}

		// Most of the inherited methods should be fine, but we can override a few of them for performance.
		// Needed because we can't access the parent class' instance variables directly in a different
		// instance of SubList.
		@IgnoreCoverage
		private byte[] getParentArray() {
			return a;
		}

		@Override
		public @NotNull Buf subList(int from, int to) {
			// Sadly we have to rewrap this, because if there is a sublist of a sublist, and the
			// subsublist adds, both sublists need to update their "to" value.
			return subListForced(from, to);
		}

		@Override
		public Buf subListForced(int from, int to) {
			checkFromToIndex(from, to, this.to);
			var fromAbs = this.from + from;
			var toAbs = this.from + to;
			// Sadly we have to rewrap this, because if there is a sublist of a sublist, and the
			// subsublist adds, both sublists need to update their "to" value.
			return new SubList(fromAbs, toAbs);
		}

		@Override
		public Buf copyOfRange(int from, int to) {
			if (from == 0 && to == size()) {
				return copy();
			} else {
				return Buf.wrap(Arrays.copyOfRange(a, this.from + from, this.from + to));
			}
		}

		@Override
		public Buf copy() {
			return Buf.wrap(Arrays.copyOfRange(a, from, to));
		}

		@Override
		public SafeByteArrayInputStream binaryInputStream() {
			return new SafeByteArrayInputStream(a, from, size());
		}

		@Override
		public void writeTo(SafeDataOutput dataOutput) {
			dataOutput.write(a, from, size());
		}

		@Override
		public SafeByteArrayOutputStream binaryOutputStream(int from, int to) {
			checkFromToIndex(from, to, size());
			return new SafeByteArrayOutputStream(a, from + this.from, to + this.from);
		}

		@Override
		public boolean equals(int aStartIndex, Buf b, int bStartIndex, int length) {
			if (aStartIndex + length > size()) {
				return false;
			}
			return b.equals(bStartIndex, a, aStartIndex + from, length);
		}

		@Override
		public boolean equals(int aStartIndex, byte[] b, int bStartIndex, int length) {
			var aFrom = from + aStartIndex;
			var aTo = from + aStartIndex + length;
			var bTo = bStartIndex + length;
			if (aFrom < from) return false;
			if (aTo > to) return false;
			if (bTo > b.length) return false;
			return Arrays.equals(a, aFrom, aTo, b, bStartIndex, bTo);
		}

		@Override
		public byte getByte(int i) {
			ensureRestrictedIndex(i);
			return a[i + from];
		}

		@Override
		public byte @NotNull [] asArray() {
			if (this.from == 0 && this.to == a.length) {
				return a;
			} else {
				return SubList.this.toByteArray();
			}
		}

		@Override
		public byte @Nullable [] asArrayStrict() {
			if (this.from == 0 && this.to == a.length) {
				return a;
			} else {
				return null;
			}
		}

		@Override
		public byte[] asUnboundedArray() {
			if (from == 0) {
				return a;
			} else {
				return toByteArray();
			}
		}

		@Override
		public byte @Nullable [] asUnboundedArrayStrict() {
			if (from == 0) {
				return a;
			} else {
				return null;
			}
		}

		@Override
		public ByteBuffer asHeapByteBuffer() {
			return ByteBuffer.wrap(a, this.from, this.to);
		}

		@Override
		public boolean isMutable() {
			return ByteListBuf.this.isMutable();
		}

		@Override
		public SubList freeze() {
			immutable = true;
			return this;
		}

		private final class SubListIterator extends ByteIterators.AbstractIndexBasedListIterator {
			// We are using pos == 0 to be 0 relative to SubList.from (meaning you need to do a[from + i] when
			// accessing array).
			SubListIterator(int index) {
				super(0, index);
			}

			@IgnoreCoverage
			@Override
			protected byte get(int i) {
				return ByteListBuf.SubList.this.getByte(i);
			}

			@IgnoreCoverage
			@Override
			protected void add(int i, byte k) {
				assert isMutable() : IMMUTABLE_ERROR;
				ByteListBuf.SubList.this.add(i, k);
			}

			@IgnoreCoverage
			@Override
			protected void set(int i, byte k) {
				assert isMutable() : IMMUTABLE_ERROR;
				ByteListBuf.SubList.this.set(i, k);
			}

			@IgnoreCoverage
			@Override
			protected void remove(int i) {
				assert isMutable() : IMMUTABLE_ERROR;
				ByteListBuf.SubList.this.removeByte(i);
			}

			@Override
			protected int getMaxPos() {
				return to - from;
			}

			@Override
			public byte nextByte() {
				if (!hasNext()) throw new NoSuchElementException();
				return a[from + (lastReturned = pos++)];
			}

			@Override
			public byte previousByte() {
				if (!hasPrevious()) throw new NoSuchElementException();
				return a[from + (lastReturned = --pos)];
			}

			@Override
			public void forEachRemaining(final ByteConsumer action) {
				final int max = to - from;
				while (pos < max) {
					action.accept(a[from + (lastReturned = pos++)]);
				}
			}
		}

		@Override
		public @NotNull ByteListIterator listIterator(int index) {
			return new ByteListBuf.SubList.SubListIterator(index);
		}

		private final class SubListSpliterator extends ByteSpliterators.LateBindingSizeIndexBasedSpliterator {
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

			@IgnoreCoverage
			@Override
			protected byte get(int i) {
				return a[i];
			}

			@Override
			protected ByteListBuf.SubList.SubListSpliterator makeForSplit(int pos, int maxPos) {
				return new ByteListBuf.SubList.SubListSpliterator(pos, maxPos);
			}

			@Override
			public boolean tryAdvance(final ByteConsumer action) {
				if (pos >= getMaxPos()) return false;
				action.accept(a[pos++]);
				return true;
			}

			@Override
			public void forEachRemaining(final ByteConsumer action) {
				final int max = getMaxPos();
				while (pos < max) {
					action.accept(a[pos++]);
				}
			}
		}

		@Override
		public ByteSpliterator spliterator() {
			return new ByteListBuf.SubList.SubListSpliterator();
		}

		boolean contentsEquals(byte[] otherA, int otherAFrom, int otherATo) {
			if (a == otherA && from == otherAFrom && to == otherATo) return true;
			return Arrays.equals(a, from, to, otherA, otherAFrom, otherATo);
		}

		@Override
		public boolean equals(Object o) {
			if (o == this) return true;
			if (o == null) return false;
			if (!(o instanceof java.util.List)) return false;
			if (o instanceof ByteListBuf other) {
				return contentsEquals(other.a, 0, other.size());
			}
			if (o instanceof SubList other) {
				return contentsEquals(other.getParentArray(), other.from, other.to);
			}
			return super.equals(o);
		}

		int contentsCompareTo(byte[] otherA, int otherAFrom, int otherATo) {
			if (a == otherA && from == otherAFrom && to == otherATo) return 0;
			return VAR_LENGTH_LEX_COMP.compare(a, from, to, otherA, otherAFrom, otherATo);
		}

		@Override
		public int compareTo(final java.util.@NotNull List<? extends Byte> l) {
			if (l instanceof ByteListBuf other) {
				return contentsCompareTo(other.a, 0, other.size());
			}
			if (l instanceof ByteListBuf.SubList other) {
				return contentsCompareTo(other.getParentArray(), other.from, other.to);
			}
			return super.compareTo(l);
		}

		@Override
		public String toString(Charset charset) {
			return new String(a, from, size(), charset);
		}

		@Override
		public String toString() {
			return HEX_FORMAT.formatHex(a, from, from + size());
		}

		@Override
		public String getString(int i, int length, Charset charset) {
			checkFromIndexSize(i, length, to - from);
			return new String(a, from + i, length, charset);
		}
	}

	@IgnoreCoverage
	@Override
	public void add(int index, byte k) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.add(index, k);
	}

	@IgnoreCoverage
	@Override
	public boolean add(byte k) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.add(k);
	}

	@IgnoreCoverage
	@Override
	public byte removeByte(int index) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.removeByte(index);
	}

	@IgnoreCoverage
	@Override
	public boolean rem(byte k) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.rem(k);
	}

	@IgnoreCoverage
	@Override
	public byte set(int index, byte k) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.set(index, k);
	}

	@IgnoreCoverage
	@Override
	public void clear() {
		assert isMutable() : IMMUTABLE_ERROR;
		super.clear();
	}

	@IgnoreCoverage
	@Override
	public void trim() {
		assert isMutable() : IMMUTABLE_ERROR;
		super.trim();
	}

	@IgnoreCoverage
	@Override
	public void trim(int n) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.trim(n);
	}

	@IgnoreCoverage
	@Override
	public void removeElements(int from, int to) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.removeElements(from, to);
	}

	@IgnoreCoverage
	@Override
	public void addElements(int index, byte[] a, int offset, int length) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.addElements(index, a, offset, length);
	}

	@IgnoreCoverage
	@Override
	public void setElements(int index, byte[] a, int offset, int length) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.setElements(index, a, offset, length);
	}

	@IgnoreCoverage
	@Override
	public boolean addAll(int index, ByteCollection c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.addAll(index, c);
	}

	@IgnoreCoverage
	@Override
	public boolean addAll(int index, ByteList l) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.addAll(index, l);
	}

	@IgnoreCoverage
	@Override
	public boolean removeAll(ByteCollection c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.removeAll(c);
	}

	@IgnoreCoverage
	@Override
	public boolean addAll(int index, @NotNull Collection<? extends Byte> c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.addAll(index, c);
	}

	@IgnoreCoverage
	@Override
	public boolean addAll(@NotNull Collection<? extends Byte> c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.addAll(c);
	}

	@IgnoreCoverage
	@Override
	public void addElements(int index, byte[] a) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.addElements(index, a);
	}

	@IgnoreCoverage
	@Override
	public void push(byte o) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.push(o);
	}

	@IgnoreCoverage
	@Override
	public byte popByte() {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.popByte();
	}

	@IgnoreCoverage
	@Override
	public byte topByte() {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.topByte();
	}

	@IgnoreCoverage
	@Override
	public boolean addAll(ByteCollection c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.addAll(c);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public boolean add(Byte key) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.add(key);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public boolean remove(Object key) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.remove(key);
	}

	@IgnoreCoverage
	@Override
	public boolean removeAll(@NotNull Collection<?> c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.removeAll(c);
	}

	@IgnoreCoverage
	@Override
	public boolean retainAll(ByteCollection c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.retainAll(c);
	}

	@IgnoreCoverage
	@Override
	public boolean retainAll(@NotNull Collection<?> c) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.retainAll(c);
	}

	@IgnoreCoverage
	@Override
	public void setElements(byte[] a) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.setElements(a);
	}

	@IgnoreCoverage
	@Override
	public void setElements(int index, byte[] a) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.setElements(index, a);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public void add(int index, Byte key) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.add(index, key);
	}

	@IgnoreCoverage
	@Override
	public void replaceAll(ByteUnaryOperator operator) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.replaceAll(operator);
	}

	@IgnoreCoverage
	@Override
	public void replaceAll(IntUnaryOperator operator) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.replaceAll(operator);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public void replaceAll(UnaryOperator<Byte> operator) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.replaceAll(operator);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public Byte remove(int index) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.remove(index);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public Byte set(int index, Byte k) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.set(index, k);
	}

	@IgnoreCoverage
	@Override
	public boolean addAll(ByteList l) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.addAll(l);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public void sort(Comparator<? super Byte> comparator) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.sort(comparator);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public void unstableSort(Comparator<? super Byte> comparator) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.unstableSort(comparator);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public boolean removeIf(Predicate<? super Byte> filter) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.removeIf(filter);
	}

	@IgnoreCoverage
	@Override
	public boolean removeIf(BytePredicate filter) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.removeIf(filter);
	}

	@IgnoreCoverage
	@Override
	public boolean removeIf(IntPredicate filter) {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.removeIf(filter);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public void push(Byte o) {
		assert isMutable() : IMMUTABLE_ERROR;
		super.push(o);
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public Byte pop() {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.pop();
	}

	@IgnoreCoverage
	@SuppressWarnings("deprecation")
	@Deprecated
	@Override
	public Byte top() {
		assert isMutable() : IMMUTABLE_ERROR;
		return super.top();
	}

	@IgnoreCoverage
	private void ensureMutable() {
		if (!isMutable()) {
			throw new UnsupportedOperationException(IMMUTABLE_ERROR);
		}
	}

	@Override
	public int compareTo(List<? extends Byte> l) {
		if (l instanceof ByteArrayList) {
			return compareTo((ByteArrayList)l);
		}
		if (l instanceof SubList) {
			// Must negate because we are inverting the order of the comparison.
			return -((SubList)l).compareTo(this);
		}
		return super.compareTo(l);
	}

	@Override
	public int compareTo(ByteArrayList l) {
		final int s1 = size(), s2 = l.size();
		final byte[] a1 = a, a2 = l.elements();
		if (a1 == a2 && s1 == s2) return 0;
		return VAR_LENGTH_LEX_COMP.compare(a, 0, s1, a2, 0, s2);
	}

	/**
	 * Compares this type-specific array list to another one.
	 *
	 * @apiNote This method exists only for sake of efficiency. The implementation inherited from the
	 *          abstract implementation would already work.
	 *
	 * @param l a type-specific array list.
	 * @return true if the argument contains the same elements of this type-specific array list.
	 */
	public boolean equals(final ByteArrayList l) {
		if (l == this) return true;
		int s = size();
		if (s != l.size()) return false;
		final byte[] a1 = a, a2 = l.elements();
		final int s1 = this.size(), s2 = l.size();
		return Arrays.equals(a1, 0, s1, a2, 0, s2);
	}

	@SuppressWarnings("unlikely-arg-type")
	@Override
	public boolean equals(final Object o) {
		if (o == this) return true;
		if (o == null) return false;
		if (!(o instanceof java.util.List)) return false;
		if (o instanceof ByteArrayList) {
			// Safe cast because we are only going to take elements from other list, never give them
			return equals((ByteArrayList) o);
		}
		if (o instanceof SubList subList) {
			// Safe cast because we are only going to take elements from other list, never give them
			// Sublist has an optimized sub-array based comparison, reuse that.
			return subList.equals(this);
		}
		return super.equals(o);
	}
}
