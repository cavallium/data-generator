package it.cavallium.datagen.fuzz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataCursor;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.datagen.fuzz.FuzzStorages.Kind;
import it.cavallium.datagen.fuzz.FuzzStorages.View;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link FuzzStorages}: the storage matrix every fuzz oracle reads through, and
 * the fallback disguise every {@code *DeepFuzzTest} duplicated until now.
 *
 * <p>These tests prove the machinery, not any codec: all six kinds expose the same bytes, the two
 * heap slice kinds really are views behind a non-zero offset, the native view lives in an arena this
 * instance owns and frees exactly once, and {@link Kind#FORCED_FALLBACK} really is what pushes
 * {@link BufDataCursor} down its fallback path.</p>
 */
class FuzzStoragesTest {

	/**
	 * Payload sizes this test walks. The 65 535/65 536 rungs of {@link FuzzValues#SIZE_LADDER} are
	 * left out because these checks read every view byte by byte: they belong to the codec campaigns,
	 * not to a machinery test.
	 */
	private static final int[] PAYLOAD_SIZES = {0, 1, 2, 8, 17, 32, 64, 255, 256};

	/** The absolute backing offset the nested view composes: one byte of outer view plus one of inner. */
	private static final int NESTED_OFFSET = 2;

	@Test
	void allSixKindsArePresentInADeterministicOrderAndExposeTheSameBytes() {
		for (byte[] payload : payloads()) {
			try (FuzzStorages storages = new FuzzStorages(payload)) {
				List<View> views = storages.all();
				assertEquals(Kind.values().length, views.size(), "one view per kind");
				for (int i = 0; i < views.size(); i++) {
					assertEquals(Kind.values()[i], views.get(i).kind(), "stable order at " + i);
					assertSame(views.get(i), storages.kind(views.get(i).kind()), "kind() resolves to the same view");
				}
				assertEquals(Kind.values().length,
						views.stream().map(View::kind).distinct().count(), "every kind exactly once");
				assertArrayEquals(payload, storages.payload(), "payload()");
				for (View view : views) {
					assertEquals(payload.length, view.length(), view.kind() + " length");
					assertArrayEquals(payload, FuzzStorages.bytesOf(view), view.kind() + " random access");
					assertArrayEquals(payload, readThroughCursor(storages, view), view.kind() + " cursor");
					assertArrayEquals(payload, readThroughStream(view), view.kind() + " stream");
				}
			}
		}
	}

	@Test
	void heapStoragesKeepThePayloadBehindANonZeroRegionOffset() {
		for (byte[] payload : payloads()) {
			try (FuzzStorages storages = new FuzzStorages(payload)) {
				for (Kind kind : List.of(Kind.HEAP, Kind.MEMORY_SEGMENT, Kind.FORCED_FALLBACK)) {
					View view = storages.kind(kind);
					assertEquals(FuzzStorages.PAD_BEFORE, view.offset(), kind + " region offset");
					assertTrue(view.offset() > 0, kind + " must not read at offset zero");
					assertEquals(payload.length + FuzzStorages.PAD_BEFORE + FuzzStorages.PAD_AFTER,
							view.buf().size(), kind + " storage is padded on both sides");
				}
			}
		}
	}

	@Test
	void heapSlicesAreRealViewsComposingTheirBackingOffset() {
		for (byte[] payload : payloads()) {
			try (FuzzStorages storages = new FuzzStorages(payload)) {
				View flat = storages.kind(Kind.HEAP);
				View slice = storages.kind(Kind.HEAP_SLICE);
				View nested = storages.kind(Kind.NESTED_SLICE);

				assertTrue(slice.buf() instanceof java.util.List, "a Buf slice is a List view");
				assertEquals(FuzzStorages.PAD_BEFORE, slice.buf().getBackingByteArrayOffset(),
						"the slice points into the parent array instead of owning one");
				assertTrue(slice.buf().getBackingByteArray().length > slice.buf().size(),
						"the slice does not own an exact-size array");
				assertEquals(FuzzStorages.PAD_BEFORE + payload.length + FuzzStorages.PAD_AFTER,
						slice.buf().getBackingByteArray().length, "still the parent's array");
				assertSame(flat.buf().getBackingByteArray(), slice.buf().getBackingByteArray(),
						"the slice shares the heap storage");

				// the nested source holds its payload at NESTED_PAD, so only a sub-list of a sub-list
				// composes both of its levels into exactly that absolute backing offset
				assertEquals(NESTED_OFFSET, nested.buf().getBackingByteArrayOffset(),
						"nested slice offset is the sum of both view levels");
				assertTrue(nested.buf().getBackingByteArray().length > nested.buf().size(),
						"the nested slice points into a padded array rather than owning one");
				assertEquals(payload.length + 4, nested.buf().getBackingByteArray().length,
						"the nested source keeps two padding bytes on each side");
				assertEquals(slice.buf().getClass(), nested.buf().getClass(), "both are the same view type");

				// the same composition, spelled out here so the expectation above is not magic
				byte[] raw = new byte[payload.length + 4];
				System.arraycopy(payload, 0, raw, 2, payload.length);
				Buf oneLevel = Buf.wrap(raw).subListForced(1, 3 + payload.length);
				Buf twoLevels = oneLevel.subListForced(1, 1 + payload.length);
				assertEquals(1, oneLevel.getBackingByteArrayOffset(), "outer view offset");
				assertEquals(NESTED_OFFSET, twoLevels.getBackingByteArrayOffset(), "composed view offset");
				assertArrayEquals(payload, FuzzStorages.bytesOf(new View(Kind.NESTED_SLICE, twoLevels, 0,
						payload.length)), "composed view content");
			}
		}
	}

	@Test
	void forcedFallbackHidesEveryStorageProbeAndTakesTheFallbackPath() {
		for (byte[] payload : payloads()) {
			try (FuzzStorages storages = new FuzzStorages(payload)) {
				Buf fallback = storages.kind(Kind.FORCED_FALLBACK).buf();
				assertNull(fallback.getBackingByteArrayStrict(), "heap probe must be hidden");
				assertNull(fallback.asMemorySegmentStrict(), "segment probe must be hidden");
				assertNull(fallback.asArrayStrict(), "array probe must be hidden");
				assertNull(fallback.asUnboundedArrayStrict(), "unbounded array probe must be hidden");
				assertTrue(FuzzStorages.takesFallbackPath(fallback), "the oracle-facing probe must agree");
				assertEquals(BufDataCursor.StorageKind.FALLBACK, FuzzStorages.cursorKind(fallback),
						"the cursor dispatch itself must pick FALLBACK");
				// the bulk helpers are hidden too, so a reader cannot quietly copy the whole payload
				assertThrows(AssertionError.class, fallback::getBackingByteArray, "getBackingByteArray");
				assertThrows(AssertionError.class, fallback::asArray, "asArray");
				assertThrows(AssertionError.class, fallback::asUnboundedArray, "asUnboundedArray");
				assertThrows(AssertionError.class, fallback::binaryInputStream, "binaryInputStream");
				assertArrayEquals(payload, readThroughCursor(storages, storages.kind(Kind.FORCED_FALLBACK)),
						"the fallback path still reads the payload");
			}
		}
	}

	@Test
	void everyOtherKindTakesTheStoragePathItIsNamedFor() {
		try (FuzzStorages storages = new FuzzStorages(new byte[] {1, 2, 3})) {
			assertEquals(BufDataCursor.StorageKind.HEAP, FuzzStorages.cursorKind(storages.kind(Kind.HEAP).buf()));
			assertEquals(BufDataCursor.StorageKind.HEAP,
					FuzzStorages.cursorKind(storages.kind(Kind.HEAP_SLICE).buf()));
			assertEquals(BufDataCursor.StorageKind.HEAP,
					FuzzStorages.cursorKind(storages.kind(Kind.NESTED_SLICE).buf()));
			assertEquals(BufDataCursor.StorageKind.MEMORY_SEGMENT,
					FuzzStorages.cursorKind(storages.kind(Kind.MEMORY_SEGMENT).buf()));
			assertEquals(BufDataCursor.StorageKind.FALLBACK,
					FuzzStorages.cursorKind(storages.kind(Kind.FORCED_FALLBACK).buf()));
			assertFalse(FuzzStorages.takesFallbackPath(storages.kind(Kind.HEAP).buf()));
			assertFalse(FuzzStorages.takesFallbackPath(storages.kind(Kind.MEMORY_SEGMENT).buf()));
		}
	}

	@Test
	void forwardStreamHasNoRandomAccess() {
		try (FuzzStorages storages = new FuzzStorages(new byte[] {1, 2, 3})) {
			View forward = storages.kind(Kind.FORWARD_STREAM);
			assertTrue(forward.forwardOnly(), "the forward kind must say so");
			for (Kind kind : Kind.values()) {
				assertEquals(kind == Kind.FORWARD_STREAM, storages.kind(kind).forwardOnly(), kind.name());
			}
			assertThrows(IllegalStateException.class,
					() -> FuzzStorages.bindCursor(forward, DecodeLimits.unlimited()),
					"binding a forward-only view has to be refused rather than read as heap");
		}
	}

	@Test
	void memorySegmentLivesInThisInstanceOwnsConfinedArena() {
		byte[] payload = "confined".getBytes(StandardCharsets.UTF_8);
		FuzzStorages storages = new FuzzStorages(payload);
		try {
			assertTrue(storages.isOpen(), "a fresh instance owns a live arena");
			Buf nativeBuf = storages.kind(Kind.MEMORY_SEGMENT).buf();
			assertNotNull(nativeBuf.asMemorySegmentStrict(), "the native view exposes its segment");
			assertTrue(nativeBuf.asMemorySegmentStrict().isNative(), "the segment is off-heap");
			assertNull(nativeBuf.getBackingByteArrayStrict(), "the native view is not heap backed");
			assertArrayEquals(payload, FuzzStorages.bytesOf(storages.kind(Kind.MEMORY_SEGMENT)));
		} finally {
			storages.close();
		}
	}

	@Test
	void closeReleasesTheArenaExactlyOnceAndIsIdempotent() {
		byte[] payload = "close-once".getBytes(StandardCharsets.UTF_8);
		FuzzStorages storages = new FuzzStorages(payload);
		View nativeView = storages.kind(Kind.MEMORY_SEGMENT);
		assertDoesNotThrow(() -> nativeView.buf().getByte(nativeView.offset()), "readable while open");

		storages.close();
		assertFalse(storages.isOpen(), "the arena is gone after close()");
		assertThrows(IllegalStateException.class, () -> nativeView.buf().getByte(nativeView.offset()),
				"reading the native view after close must fail, which proves the arena was freed");

		assertDoesNotThrow(storages::close, "close() is idempotent: Arena.close() would throw a second time");
		assertFalse(storages.isOpen());
		assertDoesNotThrow(storages::close, "and stays idempotent however often it runs");

		assertArrayEquals(payload, FuzzStorages.bytesOf(storages.kind(Kind.HEAP)),
				"the heap views do not depend on the arena");
		assertArrayEquals(payload, FuzzStorages.bytesOf(storages.kind(Kind.FORCED_FALLBACK)));
		assertSame(nativeView, storages.kind(Kind.MEMORY_SEGMENT), "the views stay addressable for diagnostics");
	}

	@Test
	void openingAndClosingManyInstancesNeitherLeaksNorThrows() {
		byte[] payload = FuzzValues.bytes(FuzzTiers.random("storages-recycle"), 48);
		int uses = FuzzTiers.cases("core-support", 200);
		for (int use = 0; use < uses; use++) {
			try (FuzzStorages storages = new FuzzStorages(payload)) {
				assertArrayEquals(payload, readThroughCursor(storages, storages.kind(Kind.MEMORY_SEGMENT)),
						"use " + use);
			}
		}
	}

	/** @return the view's region read by a fresh bounded cursor */
	private static byte[] readThroughCursor(FuzzStorages storages, View view) {
		if (view.forwardOnly()) {
			return readThroughStream(view);
		}
		BufDataCursor cursor = FuzzStorages.bindCursor(view, DecodeLimits.unlimited());
		try {
			assertTrue(cursor.isBound(), "bindCursor must return a bound cursor");
			return cursor.readNBytes(view.length());
		} finally {
			cursor.unbind();
		}
	}

	/** @return the view's region read by a forward-only stream */
	private static byte[] readThroughStream(View view) {
		try (var in = FuzzStorages.forwardStream(view, DecodeLimits.unlimited())) {
			return in.readNBytes(view.length());
		}
	}

	/** @return an empty payload, one string payload and a random payload per tier-scaled size rung */
	private static List<byte[]> payloads() {
		Random rnd = FuzzTiers.random("storages-payloads");
		List<byte[]> payloads = new ArrayList<>();
		payloads.add(new byte[0]);
		payloads.add("x".getBytes(StandardCharsets.UTF_8));
		payloads.add("\u0000\ud800\ud83a\udd00".getBytes(StandardCharsets.UTF_8));
		int cases = FuzzTiers.cases("core-support", PAYLOAD_SIZES.length);
		for (int i = 0; i < cases; i++) {
			payloads.add(FuzzValues.bytes(rnd, PAYLOAD_SIZES[i % PAYLOAD_SIZES.length]));
		}
		// and a hostile string, because a random byte run and a UTF-8 run are different shapes of data
		payloads.add(FuzzValues.string(rnd).getBytes(StandardCharsets.UTF_8));
		return payloads;
	}
}
