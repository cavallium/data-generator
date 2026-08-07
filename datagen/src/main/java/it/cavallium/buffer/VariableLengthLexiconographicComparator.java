package it.cavallium.buffer;

import java.util.Arrays;
import java.util.Objects;

public class VariableLengthLexiconographicComparator implements ArraysComparator {

	@Override
	public int compare(byte[] a, byte[] b) {
		return a.length != b.length ? Integer.compare(a.length, b.length) : Arrays.compareUnsigned(a, b);
	}

	@Override
	public int compare(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
		Objects.checkFromToIndex(aFrom, aTo, a.length);
		Objects.checkFromToIndex(bFrom, bTo, b.length);
		return (aTo - aFrom) != (bTo - bFrom) ? Integer.compare(aTo - aFrom, bTo - bFrom)
				: Arrays.compareUnsigned(a, aFrom, aTo, b, bFrom, bTo);
	}
}
