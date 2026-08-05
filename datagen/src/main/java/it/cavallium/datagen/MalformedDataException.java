package it.cavallium.datagen;

/** Stable failure for malformed or truncated serialized input. */
public class MalformedDataException extends IllegalArgumentException {

	public MalformedDataException(String message) {
		super(message);
	}

	public MalformedDataException(String message, Throwable cause) {
		super(message, cause);
	}
}
