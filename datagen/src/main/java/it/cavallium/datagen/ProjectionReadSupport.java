package it.cavallium.datagen;

import it.cavallium.stream.SafeDataInput;

/** Runtime helpers used by generated projection readers. */
public final class ProjectionReadSupport {

	private ProjectionReadSupport() {}

	public static void skipBytes(SafeDataInput input, int length) {
		if (length < 0) {
			throw new IndexOutOfBoundsException("Negative skip length: " + length);
		}
		int remaining = length;
		while (remaining != 0) {
			int skipped = input.skipBytes(remaining);
			if (skipped <= 0) {
				input.readByte();
				remaining--;
				continue;
			}
			remaining -= skipped;
		}
	}

	public static int readLength(SafeDataInput input) {
		int length = input.readInt();
		if (length < 0) {
			throw new NegativeArraySizeException(Integer.toString(length));
		}
		return length;
	}
}
