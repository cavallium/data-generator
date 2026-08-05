package it.cavallium.datagen;

import java.util.Objects;

/** Mutable, reader-owned accounting state for one root decode at a time. */
public final class DecodeBudget {

	private final DecodeLimits limits;
	private final CodecReadState codecReadState = new CodecReadState();
	private long claimedArrayElements;
	private long claimedPayloadBytes;
	private int structuralDepth;
	private int rootEntries;

	public DecodeBudget(DecodeLimits limits) {
		this.limits = Objects.requireNonNull(limits, "limits");
	}

	public DecodeLimits limits() {
		return limits;
	}

	/** Returns the custom-codec sessions owned by this reader lane. */
	public CodecReadState codecReadState() {
		return codecReadState;
	}

	/** Begins a possibly nested session entry, resetting counters only for the outer root. */
	public void enterRoot() {
		if (rootEntries == 0) {
			claimedArrayElements = 0;
			claimedPayloadBytes = 0;
			structuralDepth = 0;
		}
		rootEntries = Math.addExact(rootEntries, 1);
	}

	/** Ends a session entry previously opened by {@link #enterRoot()}. */
	public void exitRoot() {
		if (rootEntries <= 0) {
			throw new IllegalStateException("Decode budget root is not active");
		}
		rootEntries--;
		if (rootEntries == 0) {
			structuralDepth = 0;
		}
	}

	public void enterStructure() {
		enterRoot();
		try {
			int nextDepth = Math.addExact(structuralDepth, 1);
			if (nextDepth > limits.maximumStructuralNestingDepth()) {
				throw new DecodeLimitExceededException("Structural nesting depth " + nextDepth
						+ " exceeds limit " + limits.maximumStructuralNestingDepth());
			}
			structuralDepth = nextDepth;
		} catch (RuntimeException | Error failure) {
			exitRoot();
			throw failure;
		}
	}

	public void exitStructure() {
		if (structuralDepth <= 0) {
			throw new IllegalStateException("Decode structure is not active");
		}
		try {
			structuralDepth--;
		} finally {
			exitRoot();
		}
	}

	public void claimArrayElements(int elements) {
		if (elements < 0) {
			throw new MalformedDataException("Negative array length: " + elements);
		}
		if (elements > limits.maximumElementsPerArray()) {
			throw new DecodeLimitExceededException("Array length " + elements
					+ " exceeds per-array limit " + limits.maximumElementsPerArray());
		}
		long total;
		try {
			total = Math.addExact(claimedArrayElements, elements);
		} catch (ArithmeticException exception) {
			throw new DecodeLimitExceededException("Cumulative array-element count overflow", exception);
		}
		if (total > limits.maximumCumulativeArrayElements()) {
			throw new DecodeLimitExceededException("Cumulative array elements " + total
					+ " exceed limit " + limits.maximumCumulativeArrayElements());
		}
		claimedArrayElements = total;
	}

	public void claimPayloadBytes(int bytes) {
		if (bytes < 0) {
			throw new MalformedDataException("Negative payload length: " + bytes);
		}
		if (bytes > limits.maximumBytesPerPayload()) {
			throw new DecodeLimitExceededException("Payload length " + bytes
					+ " exceeds per-payload limit " + limits.maximumBytesPerPayload());
		}
		long total;
		try {
			total = Math.addExact(claimedPayloadBytes, bytes);
		} catch (ArithmeticException exception) {
			throw new DecodeLimitExceededException("Cumulative payload-byte count overflow", exception);
		}
		if (total > limits.maximumCumulativePayloadBytes()) {
			throw new DecodeLimitExceededException("Cumulative payload bytes " + total
					+ " exceed limit " + limits.maximumCumulativePayloadBytes());
		}
		claimedPayloadBytes = total;
	}

	public long claimedArrayElements() {
		return claimedArrayElements;
	}

	public long claimedPayloadBytes() {
		return claimedPayloadBytes;
	}

	public int structuralDepth() {
		return structuralDepth;
	}
}
