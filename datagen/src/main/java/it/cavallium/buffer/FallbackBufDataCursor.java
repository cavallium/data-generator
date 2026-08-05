package it.cavallium.buffer;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;

/** Reusable cursor specialized for a {@link Buf} that exposes neither heap nor segment storage. */
public final class FallbackBufDataCursor extends BufDataCursor {

	private FallbackBufDataCursor() {
		super();
	}

	/** Creates a fallback-specialized child cursor that must borrow a parent region. */
	public static FallbackBufDataCursor borrowed() {
		return new FallbackBufDataCursor();
	}

	public FallbackBufDataCursor(DecodeLimits limits) {
		super(limits);
	}

	public FallbackBufDataCursor(DecodeBudget budget) {
		super(budget);
	}

	@Override
	public void bind(Buf source, int offset, int length) {
		if (source.getBackingByteArrayStrict() != null || source.asMemorySegmentStrict() != null) {
			throw new IllegalArgumentException("Buf exposes direct heap or memory-segment storage");
		}
		bindKnown(source, offset, length);
	}

	void bindKnown(Buf source, int offset, int length) {
		bindFallbackSource(source, offset, length);
		markBound();
	}

	@Override
	protected StorageAccess storageAccess() {
		return fallbackStorageAccess();
	}
}
