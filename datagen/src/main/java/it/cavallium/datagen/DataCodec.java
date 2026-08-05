package it.cavallium.datagen;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * The complete wire contract for one value type.
 *
 * <p>A codec must consume exactly one value from {@link #read(SafeDataInput)} and
 * {@link #skip(SafeDataInput)}. Implementations must not retain the input or any backing buffer.
 * The reusable {@link Reader} enforces exact bounded-region consumption.</p>
 */
public interface DataCodec<T> {

	void serialize(SafeDataOutput dataOutput, @NotNull T data);

	@NotNull T read(SafeDataInput dataInput);

	void skip(SafeDataInput dataInput);

	/**
	 * Creates the reusable, thread-confined state for one reader lane.
	 *
	 * <p>Stateless codecs inherit a session that delegates to their immutable codec methods.
	 * Stateful custom codecs override this factory and keep all mutable decode state in the
	 * returned session.</p>
	 */
	default ReadSession<T> newReadSession() {
		return new ReadSession<>() {
			@Override
			protected T decode(SafeDataInput input) {
				return DataCodec.this.read(input);
			}

			@Override
			protected void skipValue(SafeDataInput input) {
				DataCodec.this.skip(input);
			}

			@Override
			protected void clearTransientState() {
				// The codec is required to be immutable; this default session retains no input state.
			}
		};
	}

	/** Creates a reusable thread-confined reader for bounded {@link Buf} regions. */
	default Reader<T> newReader(DecodeLimits limits) {
		return new Reader<>(this, limits);
	}

	/** A reusable reader that rejects trailing data and never retains its source after returning. */
	final class Reader<T> {

		private final ReadSession<T> session;
		private final BufDataCursor cursor;

		private Reader(DataCodec<T> codec, DecodeLimits limits) {
			Objects.requireNonNull(codec, "codec");
			this.session = Objects.requireNonNull(codec.newReadSession(), "codec.newReadSession()");
			this.cursor = new BufDataCursor(Objects.requireNonNull(limits, "limits"));
		}

		public T read(Buf source) {
			Objects.requireNonNull(source, "source");
			return read(source, 0, source.size());
		}

		public T read(Buf source, int offset, int length) {
			cursor.bind(source, offset, length);
			try {
				T result = session.read(cursor);
				int trailing = cursor.remainingIncludingClosed();
				if (trailing != 0) {
					throw new MalformedDataException("Trailing bytes: " + trailing);
				}
				return result;
			} finally {
				cursor.unbind();
			}
		}
	}
}
