package it.cavallium.datagen;

/**
 * Immutable limits applied while decoding one root value.
 *
 * <p>Callers must choose limits explicitly. {@link #unlimited()} is intended only for trusted
 * input and is deliberately named so that opting out of defensive bounds is visible at every
 * reader construction site.</p>
 */
public record DecodeLimits(
		int maximumElementsPerArray,
		int maximumBytesPerPayload,
		long maximumCumulativeArrayElements,
		long maximumCumulativePayloadBytes,
		int maximumStructuralNestingDepth) {

	public DecodeLimits {
		if (maximumElementsPerArray < 0) {
			throw new IllegalArgumentException("maximumElementsPerArray must be non-negative");
		}
		if (maximumBytesPerPayload < 0) {
			throw new IllegalArgumentException("maximumBytesPerPayload must be non-negative");
		}
		if (maximumCumulativeArrayElements < 0) {
			throw new IllegalArgumentException("maximumCumulativeArrayElements must be non-negative");
		}
		if (maximumCumulativePayloadBytes < 0) {
			throw new IllegalArgumentException("maximumCumulativePayloadBytes must be non-negative");
		}
		if (maximumStructuralNestingDepth < 0) {
			throw new IllegalArgumentException("maximumStructuralNestingDepth must be non-negative");
		}
	}

	/** Explicit trusted-input opt-out from all decode limits. */
	public static DecodeLimits unlimited() {
		return UnlimitedHolder.INSTANCE;
	}

	private static final class UnlimitedHolder {
		private static final DecodeLimits INSTANCE = new DecodeLimits(
				Integer.MAX_VALUE,
				Integer.MAX_VALUE,
				Long.MAX_VALUE,
				Long.MAX_VALUE,
				Integer.MAX_VALUE);
	}
}
