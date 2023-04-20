/*
 * Copyright (C) 2005-2022 Sebastiano Vigna
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

package it.cavallium.stream;

import static java.util.Objects.checkFromToIndex;

import it.cavallium.buffer.IgnoreCoverage;
import it.unimi.dsi.fastutil.bytes.ByteArrays;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Simple, fast byte-array output stream that exposes the backing array.
 *
 * <p>{@link java.io.ByteArrayOutputStream} is nice, but to get its content you
 * must generate each time a new object. This doesn't happen here.
 *
 * <p>This class will automatically enlarge the backing array, doubling its
 * size whenever new space is needed. The {@link #reset()} method will
 * mark the content as empty, but will not decrease the capacity: use
 * {@link #trim()} for that purpose.
 *
 * @author Sebastiano Vigna
 */

public class SafeByteArrayOutputStream extends SafeMeasurableOutputStream implements SafeRepositionableStream {

	/** The array backing the output stream. */
	public static final int DEFAULT_INITIAL_CAPACITY = 16;
	private static final HexFormat HEX = HexFormat.of();
	private static final int MAX_PREVIEW_LENGTH = 128;
	private final boolean wrapped;
	private final int initialPosition;
	private final int initialLength;
	private final int wrappedFrom;
	private final int wrappedTo;

	/** The array backing the output stream. */
	public byte[] array;

	/** The number of valid bytes in {@link #array}. */
	public int length;

	/** The current writing position. */
	private int arrayPosition;

	/** Creates a new array output stream with an initial capacity of {@link #DEFAULT_INITIAL_CAPACITY} bytes. */
	public SafeByteArrayOutputStream() {
		this(DEFAULT_INITIAL_CAPACITY);
	}

	/** Creates a new array output stream with a given initial capacity.
	 *
	 * @param initialCapacity the initial length of the backing array.
	 */
	public SafeByteArrayOutputStream(final int initialCapacity) {
		array = new byte[initialCapacity];
		wrapped = false;
		initialPosition = wrappedFrom = length = initialLength = 0;
		wrappedTo = Integer.MAX_VALUE;
	}

	/** Creates a new array output stream wrapping a given byte array.
	 *
	 * @param a the byte array to wrap.
	 */
	@IgnoreCoverage
	public SafeByteArrayOutputStream(final byte[] a) {
		this(a, 0, a.length);
	}

	/** Creates a new array output stream wrapping a given byte array.
	 *
	 * @param a the byte array to wrap.
	 */
	public SafeByteArrayOutputStream(final byte[] a, int from, int to) {
		checkFromToIndex(from, to, a.length);
		wrapped = true;
		array = a;
		initialPosition = wrappedFrom = arrayPosition = from;
		initialLength = length = to - from;
		wrappedTo = to;
	}

	private void ensureWrappedBounds(int fromArrayPosition, int toArrayPosition) {
		Objects.checkFromToIndex(fromArrayPosition - wrappedFrom, toArrayPosition - wrappedFrom, wrappedTo - wrappedFrom);
	}

	/** Marks this array output stream as empty. */
	public void reset() {
		length = initialLength;
		arrayPosition = initialPosition;
	}

	/** Ensures that the length of the backing array is equal to {@link #length}. */
	public void trim() {
		if (!wrapped) {
			array = ByteArrays.trim(array, length);
		}
	}

	public void ensureWritable(int size) {
		growBy(size);
	}

	@Override
	public void write(final int b) {
		if (wrapped) {
			ensureWrappedBounds(arrayPosition, arrayPosition + 1);
		} else if (arrayPosition >= array.length) {
			array = ByteArrays.grow(array, arrayPosition + 1, length);
		}
		array[arrayPosition++] = (byte)b;
		if (length < arrayPosition) length = arrayPosition;
	}

	@Override
	public void write(final byte[] b, final int off, final int len) {
		if (wrapped) {
			ensureWrappedBounds(arrayPosition, arrayPosition + len);
		}
		Objects.checkFromIndexSize(off, len, b.length);
		growBy(len);
		System.arraycopy(b, off, array, arrayPosition, len);
		if (arrayPosition + len > length) length = arrayPosition += len;
	}

	private void growBy(int len) {
		if (wrapped) {
			ensureWrappedBounds(arrayPosition, arrayPosition + len);
		} else if (arrayPosition + len > array.length) {
			array = ByteArrays.grow(array, arrayPosition + len, arrayPosition);
		}
	}

	@Override
	public void position(final long newPosition) {
		arrayPosition = (int) (newPosition + wrappedFrom);
	}

	@Override
	public long position() {
		return arrayPosition - wrappedFrom;
	}

	@Override
	public long length() {
		return length;
	}

	/**
	 * This method copies the array
	 */
	public byte[] toByteArray() {
		if (wrapped) {
			return Arrays.copyOfRange(array, wrappedFrom, wrappedTo);
		} else {
			return java.util.Arrays.copyOf(array, length);
		}
	}

	@Override
	public String toString() {
		return "SafeByteArrayOutputStream[" + toHexPreview() + "]";
	}

	private String toHexPreview() {
		int len;
		int from;
		String prefix;
		if (wrapped) {
			prefix = "(wrapped from " + wrappedFrom + " to " + wrappedTo + ") ";
			from = wrappedFrom;
			len = wrappedTo - wrappedFrom;
		} else {
			prefix = "";
			from = 0;
			len = length;
		}
		return prefix + HEX.formatHex(this.array, from, (Math.min(len, MAX_PREVIEW_LENGTH) + from)) + ((len > MAX_PREVIEW_LENGTH) ? "..." : "");
	}
}
