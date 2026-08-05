package it.cavallium.buffer;

import it.cavallium.datagen.DecodeLimits;
import java.util.Objects;

/** One-shot direct-storage data input over a complete {@link Buf}. */
public final class BufDataInput extends BufDataInputCore {

	private BufDataInput(Buf source, DecodeLimits limits) {
		super(limits);
		bindSource(source, 0, source.size());
	}

	public static BufDataInput create(Buf source, DecodeLimits limits) {
		return new BufDataInput(Objects.requireNonNull(source, "source"),
				Objects.requireNonNull(limits, "limits"));
	}

	@Deprecated
	@Override
	public void close() {
		// Preserve the one-shot input's historical no-op close contract.
	}
}
