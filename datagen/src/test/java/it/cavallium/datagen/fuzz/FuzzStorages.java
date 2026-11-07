package it.cavallium.datagen.fuzz;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.buffer.FallbackBufDataCursor;
import it.cavallium.buffer.HeapBufDataCursor;
import it.cavallium.buffer.MemorySegmentBuf;
import it.cavallium.buffer.MemorySegmentBufDataCursor;
import it.cavallium.datagen.DecodeBudget;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeDataInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * The six input storages a fuzz oracle must read the same bytes through, built from one payload.
 *
 * <p>This is the shared replacement for the {@code storages(Arena, byte[])} lists that the deep fuzz
 * tests each duplicated. Every random-access view exposes exactly {@code payload} at its own
 * {@link View#offset()}, so an oracle binds a cursor to {@code (buf, offset, length)} and the storage
 * difference stays invisible to the codec under test:</p>
 *
 * <ul>
 *     <li>{@link Kind#HEAP} — {@link Buf#wrap(byte[])} over a padded array, so the payload starts at a
 *     non-zero offset inside the backing array;</li>
 *     <li>{@link Kind#HEAP_SLICE} — a real {@code subListForced} view of that padded array, which keeps
 *     a non-zero backing-array offset and does not own an exact-size array;</li>
 *     <li>{@link Kind#NESTED_SLICE} — a sub-list <em>of</em> a sub-list, whose offset is composed from
 *     both levels;</li>
 *     <li>{@link Kind#MEMORY_SEGMENT} — {@link MemorySegmentBuf} over native memory in the confined
 *     {@link Arena} this instance owns;</li>
 *     <li>{@link Kind#FORCED_FALLBACK} — a {@link Proxy} that hides every strict storage probe, which
 *     is what pushes {@link BufDataCursor#bind} down the {@code FALLBACK} path;</li>
 *     <li>{@link Kind#FORWARD_STREAM} — the same bytes with <em>no</em> random access: read them with
 *     {@link #forwardStream(View, DecodeLimits)}.</li>
 * </ul>
 *
 * <p>Instance life cycle: the native view is allocated in the constructor and released by
 * {@link #close()}, which is idempotent, so a {@code try (var storages = new FuzzStorages(bytes))}
 * per case neither leaks nor throws after the ten-thousandth use. The views are only readable from
 * the creating thread while the instance is open, because the arena is confined.</p>
 */
public final class FuzzStorages implements AutoCloseable {

	/** Every input family a codec must behave identically on, in {@link #all()} order. */
	public enum Kind {
		/** {@code Buf.wrap} of a padded heap array: direct heap storage at a non-zero region offset. */
		HEAP,
		/** A heap sub-list view: direct heap storage with a non-zero backing-array offset. */
		HEAP_SLICE,
		/** A sub-list of a sub-list: heap storage whose offset is composed from two view levels. */
		NESTED_SLICE,
		/** Native {@link MemorySegmentBuf} storage inside this instance's confined arena. */
		MEMORY_SEGMENT,
		/** A {@link Buf} that exposes neither heap nor segment storage, forcing the fallback cursor. */
		FORCED_FALLBACK,
		/** Forward-only stream storage: no random access at all. */
		FORWARD_STREAM
	}

	/**
	 * One readable view of the payload.
	 *
	 * @param kind   which storage family this view exercises
	 * @param buf    the storage; for {@link Kind#FORWARD_STREAM} it is an exact copy of the payload and
	 *               exists for diagnostics only, since that kind is read through a stream
	 * @param offset where the payload starts inside {@code buf}
	 * @param length the payload length, i.e. {@code buf} region size to bind
	 */
	public record View(Kind kind, Buf buf, int offset, int length) {

		public View {
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(buf, "buf");
			Objects.checkFromIndexSize(offset, length, buf.size());
		}

		/** @return {@code true} when this kind must be read through a forward-only stream */
		public boolean forwardOnly() {
			return kind == Kind.FORWARD_STREAM;
		}
	}

	/** Bytes the {@link Kind#HEAP}, {@link Kind#MEMORY_SEGMENT} and {@link Kind#FORCED_FALLBACK} views pad in front of the payload. */
	public static final int PAD_BEFORE = 3;

	/** Bytes the padded storages pad behind the payload. */
	public static final int PAD_AFTER = 2;

	/** Bytes the {@link Kind#NESTED_SLICE} source reserves so both view levels carry an offset. */
	private static final int NESTED_PAD = 2;

	/** Limits only needed to construct throwaway cursors for {@link #cursorKind(Buf)}. */
	private static final DecodeLimits PROBE_LIMITS = DecodeLimits.unlimited();

	private final byte[] payload;
	private final List<View> views;
	private final EnumMap<Kind, View> byKind;

	/** The confined arena behind the native view; {@code null} once {@link #close()} has run. */
	private Arena arena;

	/**
	 * Copies {@code payload} and builds all six views over it, allocating the native one from a new
	 * confined arena owned by this instance.
	 *
	 * @param payload the bytes every view must expose; copied, so the caller may reuse its array
	 */
	public FuzzStorages(byte[] payload) {
		this.payload = payload.clone();
		byte[] padded = padded(this.payload);
		byte[] nested = nested(this.payload);
		this.arena = Arena.ofConfined();
		MemorySegment segment = arena.allocate(Math.max(1, padded.length), 1);
		MemorySegment.copy(MemorySegment.ofArray(padded), 0, segment, 0, padded.length);
		Buf heap = Buf.wrap(padded);
		Buf heapSlice = heap.subListForced(PAD_BEFORE, PAD_BEFORE + this.payload.length);
		Buf nestedSlice = Buf.wrap(nested)
				.subListForced(NESTED_PAD - 1, NESTED_PAD + 1 + this.payload.length)
				.subListForced(1, 1 + this.payload.length);
		this.views = List.of(
				new View(Kind.HEAP, heap, PAD_BEFORE, this.payload.length),
				new View(Kind.HEAP_SLICE, heapSlice, 0, this.payload.length),
				new View(Kind.NESTED_SLICE, nestedSlice, 0, this.payload.length),
				new View(Kind.MEMORY_SEGMENT, new MemorySegmentBuf(segment.asSlice(0, padded.length)),
						PAD_BEFORE, this.payload.length),
				new View(Kind.FORCED_FALLBACK, forcedFallback(heap), PAD_BEFORE, this.payload.length),
				new View(Kind.FORWARD_STREAM, Buf.wrap(this.payload.clone()), 0, this.payload.length));
		this.byKind = new EnumMap<>(Kind.class);
		for (View view : views) {
			byKind.put(view.kind(), view);
		}
	}

	/** @return the six views in {@link Kind} declaration order; the same list on every call */
	public List<View> all() {
		return views;
	}

	/**
	 * @param kind the storage family to read
	 * @return this instance's view of that kind, never {@code null}
	 */
	public View kind(Kind kind) {
		return Objects.requireNonNull(byKind.get(kind), "missing view for " + kind);
	}

	/** @return a copy of the payload every view exposes */
	public byte[] payload() {
		return payload.clone();
	}

	/**
	 * Releases the confined arena behind {@link Kind#MEMORY_SEGMENT}. Calling it more than once is a
	 * no-op, which is what keeps a per-case {@code try}-with-resources correct across a whole campaign;
	 * the heap views stay readable, the native one starts throwing {@link IllegalStateException}.
	 */
	@Override
	public void close() {
		Arena owned = arena;
		if (owned != null) {
			arena = null;
			owned.close();
		}
	}

	/** @return {@code true} while the native view is still backed by a live arena */
	public boolean isOpen() {
		return arena != null;
	}

	/**
	 * The exact payload bytes a view exposes, read through random access.
	 *
	 * @param view any view of any kind
	 * @return {@code view.length()} bytes
	 */
	public static byte[] bytesOf(View view) {
		byte[] bytes = new byte[view.length()];
		Buf buf = view.buf();
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = buf.getByte(view.offset() + i);
		}
		return bytes;
	}

	/**
	 * Binds a fresh reusable cursor to the view's region.
	 *
	 * @param view   a random-access view; {@link Kind#FORWARD_STREAM} has no random access and is
	 *               rejected rather than silently read as heap
	 * @param limits limits for the cursor's own budget
	 * @return a bound cursor; the caller must {@link BufDataCursor#unbind()} it
	 */
	public static BufDataCursor bindCursor(View view, DecodeLimits limits) {
		Objects.requireNonNull(limits, "limits");
		return bindCursor(view, new DecodeBudget(limits));
	}

	/**
	 * Binds a fresh reusable cursor to the view's region on a caller-owned budget, so an oracle can
	 * inspect the accounting the codec left behind.
	 *
	 * @param view   a random-access view
	 * @param budget the budget the read must account to
	 * @return a bound cursor; the caller must {@link BufDataCursor#unbind()} it
	 */
	public static BufDataCursor bindCursor(View view, DecodeBudget budget) {
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(budget, "budget");
		if (view.forwardOnly()) {
			throw new IllegalStateException(view.kind() + " has no random access; use forwardStream(...)");
		}
		BufDataCursor cursor = new BufDataCursor(budget);
		cursor.bind(view.buf(), view.offset(), view.length());
		return cursor;
	}

	/**
	 * A forward-only input over the view's bytes: no {@link BufDataCursor}, no random access.
	 *
	 * @param view   any view, including {@link Kind#FORWARD_STREAM}
	 * @param limits limits for the stream's own budget
	 * @return a stream positioned at the first payload byte; the caller closes it
	 */
	public static SafeDataInputStream forwardStream(View view, DecodeLimits limits) {
		Objects.requireNonNull(view, "view");
		Objects.requireNonNull(limits, "limits");
		return new SafeDataInputStream(new SafeByteArrayInputStream(bytesOf(view)), limits);
	}

	/**
	 * A forward-only input over the view's bytes on a caller-owned budget's limits.
	 *
	 * <p>{@link SafeDataInputStream#decodeBudget()} is final, so a stream always owns its budget: use
	 * this overload when only the limits matter, and {@link #bindCursor(View, DecodeBudget)} when the
	 * oracle has to inspect one shared budget afterwards.</p>
	 *
	 * @param view   any view
	 * @param budget the budget whose {@link DecodeBudget#limits()} the stream must apply
	 * @return a stream positioned at the first payload byte; the caller closes it
	 */
	public static SafeDataInputStream forwardStream(View view, DecodeBudget budget) {
		Objects.requireNonNull(budget, "budget");
		return forwardStream(view, budget.limits());
	}

	/**
	 * Whether {@link BufDataCursor#bind} would select the {@code FALLBACK} storage for this buffer.
	 *
	 * <p>Mirrors the two probes the production dispatch performs — {@link Buf#getBackingByteArrayStrict()}
	 * and {@link Buf#asMemorySegmentStrict()} — so it is a cheap, allocation-free prediction of the
	 * cursor's choice. {@link #cursorKind(Buf)} is the authoritative check.</p>
	 *
	 * @param buf the buffer to probe
	 * @return {@code true} when neither direct storage is exposed and the cursor must use the fallback
	 */
	public static boolean takesFallbackPath(Buf buf) {
		Objects.requireNonNull(buf, "buf");
		return buf.getBackingByteArrayStrict() == null && buf.asMemorySegmentStrict() == null;
	}

	/**
	 * The storage kind the production dispatch actually picks for {@code buf}.
	 *
	 * @param buf the buffer to bind at offset zero with zero length
	 * @return the {@link BufDataCursor.StorageKind} {@link BufDataCursor#bindSpecialized} selected
	 */
	public static BufDataCursor.StorageKind cursorKind(Buf buf) {
		Objects.requireNonNull(buf, "buf");
		try (var heap = new HeapBufDataCursor(PROBE_LIMITS);
				var segment = new MemorySegmentBufDataCursor(PROBE_LIMITS);
				var fallback = new FallbackBufDataCursor(PROBE_LIMITS)) {
			BufDataCursor.StorageKind kind = BufDataCursor.bindSpecialized(buf, 0, 0, heap, segment, fallback);
			heap.unbind();
			segment.unbind();
			fallback.unbind();
			return kind;
		}
	}

	/** @return {@code payload} wrapped in hostile filler on both sides */
	private static byte[] padded(byte[] payload) {
		byte[] padded = new byte[PAD_BEFORE + payload.length + PAD_AFTER];
		for (int i = 0; i < PAD_BEFORE; i++) {
			padded[i] = (byte) FuzzValues.HOSTILE_BYTES[i % FuzzValues.HOSTILE_BYTES.length];
		}
		System.arraycopy(payload, 0, padded, PAD_BEFORE, payload.length);
		for (int i = 0; i < PAD_AFTER; i++) {
			padded[PAD_BEFORE + payload.length + i] =
					(byte) FuzzValues.HOSTILE_BYTES[i % FuzzValues.HOSTILE_BYTES.length];
		}
		return padded;
	}

	/** @return {@code payload} at offset {@value #NESTED_PAD}, so a two-level view keeps a real offset */
	private static byte[] nested(byte[] payload) {
		byte[] nested = new byte[NESTED_PAD + payload.length + 2];
		System.arraycopy(payload, 0, nested, NESTED_PAD, payload.length);
		return nested;
	}

	/**
	 * The proven fallback disguise: the union of what the deep fuzz tests' {@code forcedFallback}
	 * proxies hid. All four strict storage probes return {@code null}, so a cursor can only pick the
	 * fallback path, and the bulk-copy helpers throw instead of quietly letting a reader take the
	 * whole payload at once.
	 */
	private static Buf forcedFallback(Buf delegate) {
		return (Buf) Proxy.newProxyInstance(FuzzStorages.class.getClassLoader(),
				new Class<?>[] {Buf.class}, (proxy, method, arguments) -> switch (method.getName()) {
					case "getBackingByteArrayStrict", "asMemorySegmentStrict", "asArrayStrict",
							"asUnboundedArrayStrict" -> null;
					case "getBackingByteArray", "asArray", "asUnboundedArray", "binaryInputStream" ->
							throw new AssertionError("fallback bulk path copied the complete payload");
					default -> {
						try {
							yield method.invoke(delegate, arguments);
						} catch (InvocationTargetException failure) {
							throw failure.getCause();
						}
					}
				});
	}
}
