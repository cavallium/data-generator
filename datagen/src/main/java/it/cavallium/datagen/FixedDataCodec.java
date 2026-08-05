package it.cavallium.datagen;

/** A codec whose wire representation always occupies exactly {@link #fixedSize()} bytes. */
public interface FixedDataCodec<T> extends DataCodec<T> {

	int fixedSize();
}
