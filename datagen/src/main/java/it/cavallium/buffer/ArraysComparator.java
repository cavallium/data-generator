package it.cavallium.buffer;

import java.util.Comparator;

public interface ArraysComparator extends Comparator<byte[]> {

	@Override
	int compare(byte[] a, byte[] b);

	int compare(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo);
}
