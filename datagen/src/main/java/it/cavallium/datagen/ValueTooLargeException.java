package it.cavallium.datagen;

/** Stable serialization failure for a value that cannot fit its existing wire prefix. */
public class ValueTooLargeException extends IllegalArgumentException {

	public ValueTooLargeException(String message) {
		super(message);
	}

	public ValueTooLargeException(String message, Throwable cause) {
		super(message, cause);
	}
}
