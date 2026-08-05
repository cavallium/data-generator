package it.cavallium.buffer;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Reusable, thread-confined input over a bounded region of a {@link Buf}.
 *
 * <p>Binding reads the source's existing heap or native storage directly and creates no slice,
 * stream, byte array, or byte-buffer view. Always unbind in a {@code finally} block.</p>
 */
public class BufDataCursor extends BufDataInputCore {
	private int closedRemaining = -1;

	protected BufDataCursor() {
		super();
	}

	/** Creates a child cursor that receives storage and budget only from a bounded parent. */
	public static BufDataCursor borrowed() {
		return new BufDataCursor();
	}

	public BufDataCursor(DecodeLimits limits) {
		super(limits);
	}

	public BufDataCursor(DecodeBudget budget) {
		super(budget);
	}

	/** The direct backing selected once when a generated reader binds a row. */
	public enum StorageKind {
		HEAP,
		MEMORY_SEGMENT,
		FALLBACK
	}

	/**
	 * Selects and binds exactly one storage-specialized cursor without creating a slice or view.
	 * The returned enum is a canonical singleton and is intended for one outer dispatch per row.
	 */
	public static StorageKind bindSpecialized(Buf source,
			int offset,
			int length,
			HeapBufDataCursor heapCursor,
			MemorySegmentBufDataCursor segmentCursor,
			FallbackBufDataCursor fallbackCursor) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(heapCursor, "heapCursor");
		Objects.requireNonNull(segmentCursor, "segmentCursor");
		Objects.requireNonNull(fallbackCursor, "fallbackCursor");
		byte[] heap = source.getBackingByteArrayStrict();
		if (heap != null) {
			heapCursor.bindKnown(source, heap, source.getBackingByteArrayOffset(), offset, length);
			return StorageKind.HEAP;
		}
		MemorySegment segment = source.asMemorySegmentStrict();
		if (segment != null) {
			segmentCursor.bindKnown(source, segment, offset, length);
			return StorageKind.MEMORY_SEGMENT;
		}
		fallbackCursor.bindKnown(source, offset, length);
		return StorageKind.FALLBACK;
	}

	public void bind(Buf source, int offset, int length) {
		bindSource(source, offset, length);
		markBound();
	}

	protected final void markBound() {
		closedRemaining = -1;
	}

	public void unbind() {
		if (isSourceBound()) {
			closedRemaining = remainingBytes();
		}
		unbindSource();
	}

	@VisibleForTesting
	public boolean isBound() {
		return isSourceBound();
	}

	public int remaining() {
		return remainingBytes();
	}

	/**
	 * Returns the remaining byte count even if the most recent binding was closed by a borrower.
	 * Generated upgrade frames use this to validate a borrowed serialized region before reuse.
	 */
	public int remainingIncludingClosed() {
		if (isSourceBound()) {
			return remainingBytes();
		}
		if (closedRemaining < 0) {
			throw new IllegalStateException("Cursor has not been bound");
		}
		return closedRemaining;
	}

	@Override
	public void close() {
		unbind();
	}
}
