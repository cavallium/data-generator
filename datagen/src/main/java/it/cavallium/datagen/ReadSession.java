package it.cavallium.datagen;

import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.stream.SafeDataInput;
import java.util.Objects;

/**
 * Reusable thread-confined decode state for one codec and one reader lane.
 *
 * <p>The public entry points always clear transient references in a {@code finally} block. A
 * session may retain warmed scratch capacity, but {@link #clearTransientState()} must release
 * inputs, cursors, views, and decoded graphs after both success and failure.</p>
 */
public abstract class ReadSession<T> {

	private BufDataCursor reservedCursor;

	public final T read(SafeDataInput input) {
		Objects.requireNonNull(input, "input");
		DecodeBudget budget = input.decodeBudget();
		budget.enterRoot();
		try {
			return decode(input);
		} finally {
			try {
				clearTransientState();
			} finally {
				budget.exitRoot();
			}
		}
	}

	public final void skip(SafeDataInput input) {
		Objects.requireNonNull(input, "input");
		DecodeBudget budget = input.decodeBudget();
		budget.enterRoot();
		try {
			skipValue(input);
		} finally {
			try {
				clearTransientState();
			} finally {
				budget.exitRoot();
			}
		}
	}

	/**
	 * Decodes a span already reserved by a parent fixed run. The parent position is not changed.
	 */
	public final T readReserved(RandomAccessDataInput input, int offset, int length) {
		Objects.requireNonNull(input, "input");
		DecodeBudget budget = input.decodeBudget();
		budget.enterRoot();
		try {
			return decodeReserved(input, offset, length);
		} finally {
			try {
				clearTransientState();
			} finally {
				budget.exitRoot();
			}
		}
	}

	protected abstract T decode(SafeDataInput input);

	protected abstract void skipValue(SafeDataInput input);

	/**
	 * Default reserved decoding through one lazily allocated reusable child cursor. Specialized
	 * sessions may override this method with direct absolute loads.
	 */
	protected T decodeReserved(RandomAccessDataInput input, int offset, int length) {
		if (reservedCursor == null) {
			reservedCursor = new BufDataCursor(input.decodeBudget());
		}
		input.bindReservedRegion(reservedCursor, offset, length);
		try {
			T result = decode(reservedCursor);
			int trailing = reservedCursor.remainingIncludingClosed();
			if (trailing != 0) {
				throw new MalformedDataException("Trailing bytes in reserved codec value: " + trailing);
			}
			return result;
		} finally {
			reservedCursor.unbind();
		}
	}

	/** Clears all transient references while retaining reusable scratch capacity. */
	protected abstract void clearTransientState();
}
