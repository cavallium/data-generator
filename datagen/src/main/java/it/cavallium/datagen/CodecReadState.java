package it.cavallium.datagen;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Lazily populated custom-codec sessions owned by one decode lane.
 *
 * <p>The logical type name, rather than a codec singleton identity, is the key because generated
 * historical versions may expose distinct immutable factory objects for the same schema custom
 * type. The state itself is thread-confined together with its owning {@link DecodeBudget}.</p>
 */
public final class CodecReadState {

	private Map<String, SessionSlot> sessions;

	@SuppressWarnings("unchecked")
	public <T> ReadSession<T> session(String logicalType, DataCodec<T> codec) {
		Objects.requireNonNull(logicalType, "logicalType");
		Objects.requireNonNull(codec, "codec");
		if (sessions == null) {
			sessions = new HashMap<>();
		}
		SessionSlot existing = sessions.get(logicalType);
		if (existing != null) {
			if (!existing.codecClass.equals(codec.getClass())) {
				throw new IllegalStateException("Logical custom type " + logicalType
						+ " was requested with both " + existing.codecClass.getName()
						+ " and " + codec.getClass().getName());
			}
			return (ReadSession<T>) existing.session;
		}
		ReadSession<T> created = Objects.requireNonNull(codec.newReadSession(),
				"codec.newReadSession() for " + logicalType);
		sessions.put(logicalType, new SessionSlot(codec.getClass(), created));
		return created;
	}

	public int initializedSessionCount() {
		return sessions == null ? 0 : sessions.size();
	}

	private record SessionSlot(Class<?> codecClass, ReadSession<?> session) {
		private SessionSlot {
			Objects.requireNonNull(codecClass, "codecClass");
			Objects.requireNonNull(session, "session");
		}
	}
}
