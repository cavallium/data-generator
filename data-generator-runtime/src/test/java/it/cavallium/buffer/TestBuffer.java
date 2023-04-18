package it.cavallium.buffer;

import it.unimi.dsi.fastutil.bytes.ByteList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class TestBuffer {

    private record BufArg(String name, Buf b, int initialSize, byte[] initialContent) {
        private static final HexFormat HEX = HexFormat.of();

        @Override
        public String toString() {
            var bs = toStringArrayb(b.toByteArray());
            var ic = toStringArrayb(initialContent);
            return "BufArg{" +
                    "name='" + name + '\'' +
                    ", b=" + bs +
                    ", initialSize=" + initialSize +
                    ", initialContent=" + ic +
                    '}';
        }

        private String toStringArraya(byte[] byteArray) {
            var bs = Arrays.toString(Arrays.copyOf(byteArray, 24));
            if (byteArray.length > 24) {
                bs = bs.substring(0, bs.length() - 1) + ", ...]";
            }
            return bs;
        }

        private String toStringArrayb(byte[] byteArray) {
            var out = HEX.formatHex(byteArray, 0, Math.min(byteArray.length, 24));
            if (byteArray.length > 24) {
                out += "...";
            }
            return out;
        }

        public BufArg subList(int from, int to) {
            return new BufArg(name + ".subList(" + from + "," + to + ")", b.subList(from, to), to - from, Arrays.copyOfRange(initialContent, from, to));
        }

        public BufArg copyOfRange(int from, int to) {
            return new BufArg(name + ".copyOfRange(" + from + "," + to + ")", b.copyOfRange(from, to), to - from, Arrays.copyOfRange(initialContent, from, to));
        }
    }

    public static Stream<BufArg> provideBufs() {
        List<BufArg> primaryBufs = createPrimaryBufs();
        List<BufArg> subListBufs = createSubListBufs();
        return Stream.concat(primaryBufs.stream(), subListBufs.stream());
    }

    public static List<BufArg> createPrimaryBufs() {
        var emptyBuf = new BufArg("create()", Buf.create(), 0, new byte[0]);
        var def0Buf = new BufArg("create(0)", Buf.create(0), 0, new byte[0]);
        var def10Buf = new BufArg("create(10)", Buf.create(10), 0, new byte[0]);
        var def10000Buf = new BufArg("create(10000)", Buf.create(10000), 0, new byte[0]);
        var zeroedBuf = new BufArg("createZeroes(0)", Buf.createZeroes(0), 0, new byte[0]);
        var zeroed10Buf = new BufArg("createZeroes(10)", Buf.createZeroes(10), 10, new byte[10]);
        var zeroed10000Buf = new BufArg("createZeroes(10000)", Buf.createZeroes(10000), 10000, new byte[10000]);
        var copyOfEmpty = new BufArg("copyOf(empty)", Buf.copyOf(new byte[0]), 0, new byte[0]);
        var small = new byte[] {126, 19, 118, 33, -24, -65, 56, 17, 0, 90};
        var copyOfSmall = new BufArg("copyOfSmall(small)", Buf.copyOf(small), small.length, small);
        var big = new byte[10000];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * i);
        }
        var copyOfBig = new BufArg("copyOfBig(big)", Buf.copyOf(big), big.length, big);
        var wrapSmallArray = new BufArg("wrap(small array)", Buf.wrap(small.clone()), small.length, small);
        var wrapBigArray = new BufArg("wrap(big array)", Buf.wrap(big.clone()), big.length, big);
        var wrapSmallByteList = new BufArg("wrap(small byte list)", Buf.wrap(ByteList.of(small)), small.length, small);
        var wrapBigByteList = new BufArg("wrap(big byte list)", Buf.wrap(ByteList.of(big)), big.length, big);
        var wrapSmallCapped = new BufArg("wrap(small array, 10)", Buf.wrap(small.clone(), 10), 10, Arrays.copyOf(small, 10));
        var wrapBigCapped = new BufArg("wrap(big array, 10)", Buf.wrap(big.clone(), 10), 10, Arrays.copyOf(big, 10));
        var wrapSmallCappedSame = new BufArg("wrap(small array, same)", Buf.wrap(small.clone(), small.length), small.length, small);
        var wrapBigCappedSame = new BufArg("wrap(big array, same)", Buf.wrap(big.clone(), big.length), big.length, big);
        var wrapSmallCappedMinusOne = new BufArg("wrap(small array, same-1)", Buf.wrap(small.clone(), small.length - 1), small.length - 1, Arrays.copyOf(small, small.length - 1));
        var wrapBigCappedMinusOne = new BufArg("wrap(big array, same-1)", Buf.wrap(big.clone(), big.length - 1), big.length - 1, Arrays.copyOf(big, big.length - 1));
        var wrapSmallCappedRangeSame = new BufArg("wrap(small array, 0, same)", Buf.wrap(small.clone(), 0, small.length), small.length, small);
        var wrapBigCappedRangeSame = new BufArg("wrap(big array, 0, same)", Buf.wrap(big.clone(), 0, big.length), big.length, big);
        var wrapSmallCappedRangeOffset = new BufArg("wrap(small array, 5, same)", Buf.wrap(small.clone(), 5, small.length), small.length - 5, Arrays.copyOfRange(small, 5, small.length));
        var wrapBigCappedRangeOffset = new BufArg("wrap(big array, 500, same)", Buf.wrap(big.clone(), 500, big.length), big.length - 500, Arrays.copyOfRange(big, 500, big.length));
        var wrapSmallCappedRangeOffsetAndLen = new BufArg("wrap(small array, 5, same-3)", Buf.wrap(small.clone(), 5, small.length - 3), small.length - 5 - 3, Arrays.copyOfRange(small, 5, small.length - 3));
        var wrapBigCappedRangeOffsetAndLen = new BufArg("wrap(big array, 500, same-100)", Buf.wrap(big.clone(), 500, big.length - 100), big.length - 500 - 100, Arrays.copyOfRange(big, 500, big.length - 100));
        var wrapSmallCappedRangeLen = new BufArg("wrap(small array, 0, same-5)", Buf.wrap(small.clone(), 0, small.length - 5), small.length - 5, Arrays.copyOf(small, small.length - 5));
        var wrapBigCappedRangeLen = new BufArg("wrap(big array, 0, same-500)", Buf.wrap(big.clone(), 0, big.length - 500), big.length - 500, Arrays.copyOf(big, big.length - 500));

        return List.of(emptyBuf, def0Buf, def10Buf, def10000Buf, zeroedBuf, zeroed10Buf, zeroed10000Buf, copyOfEmpty,
                copyOfSmall, copyOfBig, wrapSmallArray, wrapBigArray, wrapSmallByteList, wrapBigByteList,
                wrapSmallCapped, wrapBigCapped, wrapSmallCappedSame, wrapBigCappedSame, wrapSmallCappedMinusOne,
                wrapBigCappedMinusOne, wrapSmallCappedRangeSame, wrapBigCappedRangeSame, wrapSmallCappedRangeOffset,
                wrapBigCappedRangeOffset, wrapSmallCappedRangeOffsetAndLen, wrapBigCappedRangeOffsetAndLen,
                wrapSmallCappedRangeLen, wrapBigCappedRangeLen);
    }

    public static List<BufArg> createSubListBufs() {
        var sameSizeArgs = createPrimaryBufs().stream().filter(b -> b.initialSize > 0).map(bufArg -> new BufArg(bufArg.name + ".subList(0, same)", bufArg.b.subList(0, bufArg.initialSize), bufArg.initialSize, bufArg.initialContent)).toList();
        var firstHalfArgs = createPrimaryBufs().stream().filter(b -> b.initialSize > 0).map(bufArg -> new BufArg(bufArg.name + ".subList(0, half)", bufArg.b.subList(0, bufArg.initialSize/2), bufArg.initialSize/2, Arrays.copyOfRange(bufArg.initialContent, 0, bufArg.initialSize/2))).toList();
        var lastHalfArgs = createPrimaryBufs().stream().filter(b -> b.initialSize > 0).map(bufArg -> new BufArg(bufArg.name + ".subList(half, same)", bufArg.b.subList(bufArg.initialSize/2, bufArg.initialSize), bufArg.initialSize - bufArg.initialSize/2, Arrays.copyOfRange(bufArg.initialContent, bufArg.initialSize/2, bufArg.initialSize))).toList();
        return Stream.concat(Stream.concat(sameSizeArgs.stream(), firstHalfArgs.stream()), lastHalfArgs.stream()).toList();
    }

    @Test
    public void testCreate() {
        var buf = Buf.create();
        assertEquals(0, buf.size());
        assertTrue(buf.isMutable());
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testInitialValidity(BufArg bufArg) {
        assertEquals(bufArg.initialSize, bufArg.b.size());
        assertEquals(bufArg.initialSize, bufArg.initialContent.length);
        assertEquals(bufArg.initialSize == 0, bufArg.b.isEmpty());
        assertTrue(bufArg.b.isMutable());
        if (bufArg.b instanceof ByteListBuf.SubList subList) {
            assertArrayEquals(bufArg.initialContent, subList.toByteArray());
        } else if (bufArg.b instanceof ByteListBuf bytes) {
            assertArrayEquals(bufArg.initialContent, bytes.toByteArray());
        } else {
            assertArrayEquals(bufArg.initialContent, bufArg.b.toByteArray());
        }
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testGet(BufArg bufArg) {
        assertThrows(Exception.class, () -> bufArg.b.getByte(-1));
        assertThrows(Exception.class, () -> bufArg.b.getByte(bufArg.initialContent.length));
        if (bufArg.initialSize > 0) {
            // Test first
            var expected = bufArg.initialContent[0];
            var bi = bufArg.b.getByte(0);
            assertEquals(expected, bi, "The first element does not match");

            // Test last
            var expectedLast = bufArg.initialContent[bufArg.initialSize - 1];
            var biLast = bufArg.b.getByte(bufArg.initialSize - 1);
            assertEquals(expectedLast, biLast, "The last element does not match");
        }

        // Test the other
        for (int i = 1; i < bufArg.initialContent.length - 1; i++) {
            var expected = bufArg.initialContent[i];
            var bi = bufArg.b.getByte(i);
            assertEquals(expected, bi, "The element index " + i + " does not match");
        }
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testPut(BufArg bufArg) {
        bufArg.b.copy().isMutable();
        bufArg.b.size(10);
        bufArg.b.size();
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testFreeze(BufArg bufArg) {
        var buf = bufArg.b;
        assertTrue(buf.isMutable());
        Buf subList1 = null;
        Buf subList2 = null;
        if (bufArg.initialSize >= 3) {
            subList1 = buf.subList(0, 2);
            subList2 = subList1.subList(0, 1);
        }
        buf.freeze();
        assertFalse(buf.isMutable());
        if (subList1 != null) {
            Buf subList3 = buf.subList(0, 2);
            Buf subList4 = subList3.subList(0, 1);
            assertFalse(subList1.isMutable());
            assertFalse(subList2.isMutable());
            assertFalse(subList3.isMutable());
            assertFalse(subList4.isMutable());
        }
        buf.freeze();
        assertFalse(buf.isMutable());
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testAsArray(BufArg bufArg) {
        assertArrayEquals(bufArg.initialContent, bufArg.b.asArray());
        assertArrayEquals(bufArg.initialContent, bufArg.b.toByteArray());
        assertArrayEquals(bufArg.initialContent, Arrays.copyOf(bufArg.b.asUnboundedArray(), bufArg.initialSize));
        var strictArray = bufArg.b.asArrayStrict();
        if (strictArray != null) {
            assertArrayEquals(bufArg.initialContent, strictArray);
        }
        var strictUnboundedArray = bufArg.b.asUnboundedArrayStrict();
        if (strictUnboundedArray != null) {
            assertArrayEquals(bufArg.initialContent, Arrays.copyOf(strictUnboundedArray, bufArg.initialSize));
        }
    }

    @ParameterizedTest
    @MethodSource("generateWrapped")
    public void testWrapSubList(BufArg bufArg) {
        testInitialValidity(bufArg);
        testAsArray(bufArg.subList(0, 5));
        testAsArray(bufArg.subList(0, 2));
        testAsArray(bufArg.subList(3, 5));
        testAsArray(bufArg.subList(3, 4));
        testAsArray(bufArg.subList(3, 3));
    }

    @ParameterizedTest
    @MethodSource("generateWrapped")
    public void testWrapCopyOfRange(BufArg bufArg) {
        testInitialValidity(bufArg);
        testAsArray(bufArg.copyOfRange(0, 5));
        testAsArray(bufArg.copyOfRange(0, 2));
        testAsArray(bufArg.copyOfRange(3, 5));
        testAsArray(bufArg.copyOfRange(3, 4));
        testAsArray(bufArg.copyOfRange(3, 3));
    }

    public static Stream<BufArg> generateWrapped() {
        byte[] source = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        return Stream.of(
                new BufArg("0-len", Buf.wrap(source), source.length, source),
                new BufArg("2-len", Buf.wrap(source, 2, source.length), source.length - 2, Arrays.copyOfRange(source, 2, source.length)),
                new BufArg("2-9", Buf.wrap(source, 2, 9), 9 - 2, Arrays.copyOfRange(source, 2, 9)),
                new BufArg("0-9", Buf.wrap(source, 0, 9), 9, Arrays.copyOfRange(source, 0, 9))
        );
    }
}
