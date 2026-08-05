package it.cavallium.buffer;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;

/** Reusable cursor whose hot operations are specialized for an existing heap byte array. */
public final class HeapBufDataCursor extends BufDataCursor {

	private HeapBufDataCursor() {
		super();
	}

	/** Creates a heap-specialized child cursor that must borrow a parent region. */
	public static HeapBufDataCursor borrowed() {
		return new HeapBufDataCursor();
	}

	public HeapBufDataCursor(DecodeLimits limits) {
		super(limits);
	}

	public HeapBufDataCursor(DecodeBudget budget) {
		super(budget);
	}

	@Override
	public void bind(Buf source, int offset, int length) {
		byte[] heap = source.getBackingByteArrayStrict();
		if (heap == null) {
			throw new IllegalArgumentException("Buf is not heap-backed");
		}
		bindKnown(source, heap, source.getBackingByteArrayOffset(), offset, length);
	}

	void bindKnown(Buf source, byte[] heap, int heapOffset, int offset, int length) {
		bindHeapSource(source, heap, heapOffset, offset, length);
		markBound();
	}

	@Override
	protected StorageAccess storageAccess() {
		return heapStorageAccess();
	}
}
