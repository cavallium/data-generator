package it.cavallium.datagen;

/** Stable failure for an otherwise well-framed value that exceeds caller-selected decode limits. */
public class DecodeLimitExceededException extends IllegalArgumentException {

	public DecodeLimitExceededException(String message) {
		super(message);
	}

	public DecodeLimitExceededException(String message, Throwable cause) {
		super(message, cause);
	}
}
