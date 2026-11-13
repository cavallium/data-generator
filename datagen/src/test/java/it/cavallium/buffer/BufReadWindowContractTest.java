package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A ranged read is answered from the buffer's content and from nothing else.
 *
 * <p>Two of the handle shapes below used to disagree with that. A heap root is often backed by an array longer
 * than its content — {@link Buf#wrap(byte[], int)}, a buffer that grew, a {@code copy()} of a wrapped buffer —
 * and the two routes that reached that array without asking anybody were {@code ByteArrayList#getElements}
 * (which checks only the destination and then raw-{@code arraycopy}s) and the heap root's {@code getString},
 * which was {@code new String(a, i, length, charset)}. Both answered a range past the end with the bytes
 * sitting behind the content, while the heap <em>view</em> of the same bytes and the memory-segment backing
 * answered {@link IndexOutOfBoundsException}. A stale byte is worse than an exception: it decodes as a
 * plausible value, and on the heap it depended on what the buffer happened to hold before.</p>
 *
 * <p>The zero-length corner is part of the same rule. Reading nothing at an index past the end is a bad index,
 * not an empty success, and the memory-segment backing used to answer {@code ""} for it because its copy is
 * bounded by the segment rather than by the content.</p>
 */
class BufReadWindowContractTest {

	private static final byte[] CONTENT = {1, 2, 3};
	/** Behind the content of the padded fixtures: inside the backing, outside the buffer. */
	private static final byte[] BEHIND = {0x7F, 0x7E};
	private static final String CONTENT_TEXT = new String(CONTENT, StandardCharsets.UTF_8);

	/** Builds one of the five handle shapes over exactly the bytes it is given. */
	private record NamedHandle(String name, Function<byte[], Buf> build) {}

	private static byte[] withPaddingBehind(byte[] content) {
		byte[] padded = new byte[content.length + BEHIND.length];
		System.arraycopy(content, 0, padded, 0, content.length);
		System.arraycopy(BEHIND, 0, padded, content.length, BEHIND.length);
		return padded;
	}

	private static final List<NamedHandle> HANDLES = List.of(
			// heap root whose backing array is longer than its content: the shape that leaked
			new NamedHandle("heap root over a longer backing",
					bytes -> Buf.wrap(withPaddingBehind(bytes), bytes.length)),
			// heap root whose backing is exactly its content
			new NamedHandle("heap root over an exact backing", bytes -> Buf.wrap(bytes.clone())),
			// a heap view of the front of a larger storage
			new NamedHandle("heap view",
					bytes -> Buf.wrap(withPaddingBehind(bytes)).subListForced(0, bytes.length)),
			// native, exact segment
			new NamedHandle("native", bytes -> new MemorySegmentBuf(MemorySegment.ofArray(bytes.clone()))),
			// native view of the front of a larger segment
			new NamedHandle("native view",
					bytes -> new MemorySegmentBuf(MemorySegment.ofArray(withPaddingBehind(bytes))).subListForced(0, bytes.length)));

	private static Stream<Arguments> handles() {
		return HANDLES.stream().map(h -> Arguments.of(h.name(), h.build()));
	}

	private static Buf handleOverContent(Function<byte[], Buf> build) {
		Buf buf = build.apply(CONTENT);
		assertEquals(3, buf.size(), "fixture: the content is three bytes");
		assertArrayEquals(CONTENT, buf.asArray(), "fixture: the content reads back");
		return buf;
	}

	@ParameterizedTest
	@MethodSource("handles")
	void aRangePastTheEndIsRejectedByGetElements(String name, Function<byte[], Buf> build) {
		Buf buf = handleOverContent(build);
		byte[] destination = new byte[8];
		assertRejectedRead(buf, 0, 4, name + ": four bytes cannot be read from three");
		assertRejectedRead(buf, 1, 3, name + ": an in-range start with an out-of-range end is still out of range");
		assertRejectedRead(buf, 3, 1, name + ": one byte at the end is out of range");
		// the destination side keeps the check it always had
		assertThrows(IndexOutOfBoundsException.class, () -> buf.getElements(0, new byte[1], 0, 3),
				name + ": a destination too small is rejected");
		// and the accepted ranges read the content, never the padding
		buf.getElements(0, destination, 0, 3);
		assertArrayEquals(new byte[] {1, 2, 3, 0, 0, 0, 0, 0}, destination, name + ": the content, no padding");
	}

	/**
	 * A rejected bulk read has to say which bound it crossed. The two heap shapes and the native one word that
	 * differently on purpose — a heap <em>view</em> quotes fastutil's list contract ("End index (4) is greater
	 * than list size (3)"), a heap root and a native handle quote the JDK's own range or segment access — and
	 * that divergence is tolerated here because it is a message, not a class or a value. What is not tolerated
	 * is a rejection that states nothing: the message must name the size or the offending index, so the
	 * wording of every shape is pinned by this assertion and cannot quietly become empty or misleading.
	 */
	private static void assertRejectedRead(Buf buf, int from, int length, String name) {
		IndexOutOfBoundsException failure = assertThrows(IndexOutOfBoundsException.class,
				() -> buf.getElements(from, new byte[16], 0, length), name);
		String message = String.valueOf(failure.getMessage());
		assertTrue(message.contains(String.valueOf(buf.size())) || message.contains(String.valueOf(from + length)),
				name + " (the rejection must state the bound it crossed, but said: " + message + ")");
	}

	@ParameterizedTest
	@MethodSource("handles")
	void aRangePastTheEndIsRejectedByGetString(String name, Function<byte[], Buf> build) {
		Buf buf = handleOverContent(build);
		assertThrows(IndexOutOfBoundsException.class, () -> buf.getString(0, 4, StandardCharsets.UTF_8),
				name + ": four bytes cannot be decoded from three");
		assertThrows(IndexOutOfBoundsException.class, () -> buf.getString(1, 3, StandardCharsets.UTF_8),
				name + ": an in-range start with an out-of-range end is still out of range");
		assertThrows(IndexOutOfBoundsException.class, () -> buf.getString(4, 0, StandardCharsets.UTF_8),
				name + ": an empty read at an index past the end is a bad index, not an empty string");
		// accepted ranges decode the content, never the padding behind it
		assertEquals(CONTENT_TEXT, buf.getString(0, 3, StandardCharsets.UTF_8), name);
		assertEquals("", buf.getString(1, 0, StandardCharsets.UTF_8), name + ": empty read in range");
		assertEquals("", buf.getString(3, 0, StandardCharsets.UTF_8), name + ": empty read at the end");
		assertEquals(CONTENT_TEXT, buf.toString(StandardCharsets.UTF_8), name + ": toString(Charset)");
	}

	/** One content, five handles: no handle may answer with the bytes that merely happen to be behind it. */
	@ParameterizedTest
	@MethodSource("handles")
	void readsInsideTheContentAgreeAcrossBackings(String name, Function<byte[], Buf> build) {
		Buf buf = handleOverContent(build);
		byte[] destination = new byte[3];
		buf.getElements(0, destination, 0, 3);
		assertArrayEquals(CONTENT, destination, name);
		assertEquals(CONTENT_TEXT, buf.getString(0, 3, StandardCharsets.UTF_8), name);
		assertEquals("", buf.getString(2, 1, StandardCharsets.UTF_8), name);
	}

	/**
	 * {@code toString()} is what lands in a log line or a test failure, so the two backings have to say the
	 * same thing about the same bytes. The heap backing prints the content as upper-case hex; the native one
	 * inherited fastutil's list dump ({@code "[1, 2, 3]"}), which made one buffer look different depending on
	 * where it came from.
	 */
	@ParameterizedTest
	@MethodSource("handles")
	void toStringPrintsTheContentAsHexOnEveryHandle(String name, Function<byte[], Buf> build) {
		Buf buf = handleOverContent(build);
		assertEquals("010203", buf.toString(), name);
		assertEquals("", build.apply(new byte[0]).toString(), name + ", empty buffer");
	}

	/**
	 * The same content, printed from either backing, has to be the same string down to the casing of the
	 * letters: {@code 0xAB} cannot print {@code "ab"} on one side and {@code "AB"} on the other. The three
	 * bytes above cannot show this, because they have no letters in their hex.
	 */
	@Test
	void everyHandlePrintsArbitraryBytesIdentically() {
		byte[] bytes = {(byte) 0xAB, 0x00, (byte) 0xFF, 0x7F, (byte) 0xC3, (byte) 0xA9};
		String expected = "AB00FF7FC3A9";
		for (NamedHandle handle : HANDLES) {
			assertEquals(expected, handle.build().apply(bytes).toString(), handle.name());
		}
	}

	/** The same rule for a buffer whose content is empty: no non-empty range is in range. */
	@Test
	void anEmptyBufferRejectsEveryNonEmptyRange() {
		for (NamedHandle handle : HANDLES) {
			Buf buf = handle.build().apply(new byte[0]);
			assertEquals(0, buf.size(), handle.name());
			assertThrows(IndexOutOfBoundsException.class, () -> buf.getElements(0, new byte[1], 0, 1),
					handle.name());
			assertThrows(IndexOutOfBoundsException.class, () -> buf.getString(0, 1, StandardCharsets.UTF_8),
					handle.name());
			assertEquals("", buf.getString(0, 0, StandardCharsets.UTF_8), handle.name());
		}
	}
}
