package it.cavallium.buffer;

import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;
import java.lang.foreign.MemorySegment;

/** Reusable cursor whose hot operations are specialized for an existing memory segment. */
public final class MemorySegmentBufDataCursor extends BufDataCursor {

	private MemorySegmentBufDataCursor() {
		super();
	}

	/** Creates a segment-specialized child cursor that must borrow a parent region. */
	public static MemorySegmentBufDataCursor borrowed() {
		return new MemorySegmentBufDataCursor();
	}

	public MemorySegmentBufDataCursor(DecodeLimits limits) {
		super(limits);
	}

	public MemorySegmentBufDataCursor(DecodeBudget budget) {
		super(budget);
	}

	@Override
	public void bind(Buf source, int offset, int length) {
		if (source.getBackingByteArrayStrict() != null) {
			throw new IllegalArgumentException("Buf is heap-backed");
		}
		MemorySegment segment = source.asMemorySegmentStrict();
		if (segment == null) {
			throw new IllegalArgumentException("Buf has no directly accessible memory segment");
		}
		bindKnown(source, segment, offset, length);
	}

	void bindKnown(Buf source, MemorySegment segment, int offset, int length) {
		bindSegmentSource(source, segment, offset, length);
		markBound();
	}

	@Override
	protected StorageAccess storageAccess() {
		return segmentStorageAccess();
	}
}
