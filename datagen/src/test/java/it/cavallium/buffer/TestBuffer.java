package it.cavallium.buffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.primitives.Chars;
import com.google.common.primitives.Longs;
import com.google.common.primitives.Shorts;
import it.cavallium.datagen.nativedata.Int52;
import it.cavallium.stream.SafeByteArrayInputStream;
import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutputStream;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.bytes.ByteCollections;
import it.unimi.dsi.fastutil.bytes.ByteList;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Spliterators;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

    public static Stream<BufArg> provideBufsCompare() {
        return Stream.concat(provideBufs(),
            Stream.of(new BufArg("0x50", Buf.wrap((byte) 50), 1, new byte[]{50})));
    }

    public static List<BufArg> createPrimaryBufs() {
        var emptyBuf = new BufArg("create()", Buf.create(), 0, new byte[0]);
        var byteListBuf = new BufArg("createByteListBuf()", ByteListBuf.of(), 0, new byte[0]);
        var byteListBufOf = new BufArg("createByteListBuf(0, 1, 2, 3, 4)", ByteListBuf.of((byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4), 5, new byte[] {0, 1, 2, 3, 4});
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
        var copyOfBig = new BufArg("copyOfBig(big)", Buf.copyOf(big), big.length, big.clone());
        var wrapByteArrayList = new BufArg("wrap(byte array list)", Buf.wrap(ByteArrayList.of(small)), small.length, small.clone());
        var wrapBufSmall = new BufArg("wrap(wrap(small array))", Buf.wrap(Buf.wrap(small.clone())), small.length, small.clone());
        var wrapSmallArray = new BufArg("wrap(small array)", Buf.wrap(small.clone()), small.length, small.clone());
        var wrapBigArray = new BufArg("wrap(big array)", Buf.wrap(big.clone()), big.length, big.clone());
        var wrapSmallByteList = new BufArg("wrap(small byte list)", Buf.wrap(ByteList.of(small)), small.length, small.clone());
        var wrapBigByteList = new BufArg("wrap(big byte list)", Buf.wrap(ByteList.of(big)), big.length, big.clone());
        var wrapSmallCapped = new BufArg("wrap(small array, 10)", Buf.wrap(small.clone(), 10), 10, Arrays.copyOf(small, 10));
        var wrapBigCapped = new BufArg("wrap(big array, 10)", Buf.wrap(big.clone(), 10), 10, Arrays.copyOf(big, 10));
        var wrapSmallCappedSame = new BufArg("wrap(small array, same)", Buf.wrap(small.clone(), small.length), small.length, small.clone());
        var wrapBigCappedSame = new BufArg("wrap(big array, same)", Buf.wrap(big.clone(), big.length), big.length, big.clone());
        var wrapSmallCappedMinusOne = new BufArg("wrap(small array, same-1)", Buf.wrap(small.clone(), small.length - 1), small.length - 1, Arrays.copyOf(small, small.length - 1));
        var wrapBigCappedMinusOne = new BufArg("wrap(big array, same-1)", Buf.wrap(big.clone(), big.length - 1), big.length - 1, Arrays.copyOf(big, big.length - 1));
        var wrapSmallCappedRangeSame = new BufArg("wrap(small array, 0, same)", Buf.wrap(small.clone(), 0, small.length), small.length, small.clone());
        var wrapBigCappedRangeSame = new BufArg("wrap(big array, 0, same)", Buf.wrap(big.clone(), 0, big.length), big.length, big.clone());
        var wrapSmallCappedRangeOffset = new BufArg("wrap(small array, 5, same)", Buf.wrap(small.clone(), 5, small.length), small.length - 5, Arrays.copyOfRange(small, 5, small.length));
        var wrapBigCappedRangeOffset = new BufArg("wrap(big array, 500, same)", Buf.wrap(big.clone(), 500, big.length), big.length - 500, Arrays.copyOfRange(big, 500, big.length));
        var wrapSmallCappedRangeOffsetAndLen = new BufArg("wrap(small array, 5, same-3)", Buf.wrap(small.clone(), 5, small.length - 3), small.length - 5 - 3, Arrays.copyOfRange(small, 5, small.length - 3));
        var wrapBigCappedRangeOffsetAndLen = new BufArg("wrap(big array, 500, same-100)", Buf.wrap(big.clone(), 500, big.length - 100), big.length - 500 - 100, Arrays.copyOfRange(big, 500, big.length - 100));
        var wrapSmallCappedRangeLen = new BufArg("wrap(small array, 0, same-5)", Buf.wrap(small.clone(), 0, small.length - 5), small.length - 5, Arrays.copyOf(small, small.length - 5));
        var wrapBigCappedRangeLen = new BufArg("wrap(big array, 0, same-500)", Buf.wrap(big.clone(), 0, big.length - 500), big.length - 500, Arrays.copyOf(big, big.length - 500));
        var wrapSmallBufCappedRangeOffsetAndLen = new BufArg("wrap(wrap(small byte array list), 5, same-3)", Buf.wrap(Buf.wrap(small.clone()), 5, small.length - 3), small.length - 5 - 3, Arrays.copyOfRange(small, 5, small.length - 3));
        var wrapSmallByteArrayListCappedRangeOffsetAndLen = new BufArg("wrap(small byte array list, 5, same-3)", Buf.wrap(ByteArrayList.of(small.clone()), 5, small.length - 3), small.length - 5 - 3, Arrays.copyOfRange(small, 5, small.length - 3));
        var wrapSmallByteListCappedRangeOffsetAndLen = new BufArg("wrap(small byte list, 5, same-3)", Buf.wrap(ByteList.of(small.clone()), 5, small.length - 3), small.length - 5 - 3, Arrays.copyOfRange(small, 5, small.length - 3));

        return List.of(emptyBuf, byteListBuf, byteListBufOf, def0Buf, def10Buf, def10000Buf, zeroedBuf, zeroed10Buf,
            zeroed10000Buf, copyOfEmpty, copyOfSmall, copyOfBig, wrapByteArrayList, wrapBufSmall, wrapSmallArray,
            wrapBigArray, wrapSmallByteList, wrapBigByteList, wrapSmallCapped, wrapBigCapped, wrapSmallCappedSame,
            wrapBigCappedSame, wrapSmallCappedMinusOne, wrapBigCappedMinusOne, wrapSmallCappedRangeSame,
            wrapBigCappedRangeSame, wrapSmallCappedRangeOffset, wrapBigCappedRangeOffset,
            wrapSmallCappedRangeOffsetAndLen, wrapBigCappedRangeOffsetAndLen, wrapSmallCappedRangeLen,
            wrapBigCappedRangeLen, wrapSmallBufCappedRangeOffsetAndLen, wrapSmallByteArrayListCappedRangeOffsetAndLen,
            wrapSmallByteListCappedRangeOffsetAndLen);
    }

    public static List<BufArg> createSubListBufs() {
        var sameSizeArgs = createPrimaryBufs().stream().filter(b -> b.initialSize > 0).map(bufArg -> new BufArg(bufArg.name + ".subList(0, same)", bufArg.b.subList(0, bufArg.initialSize), bufArg.initialSize, bufArg.initialContent)).toList();
        var sameSizeArgsBug = createPrimaryBufs().stream().filter(b -> b.initialSize > 0).map(bufArg -> new BufArg(bufArg.name + ".subList(0, same)", bufArg.b.subListForced(0, bufArg.initialSize), bufArg.initialSize, bufArg.initialContent)).toList();
        var firstHalfArgs = createPrimaryBufs().stream().filter(b -> b.initialSize > 0).map(bufArg -> new BufArg(bufArg.name + ".subList(0, half)", bufArg.b.subList(0, bufArg.initialSize/2), bufArg.initialSize/2, Arrays.copyOfRange(bufArg.initialContent, 0, bufArg.initialSize/2))).toList();
        var lastHalfArgs = createPrimaryBufs().stream().filter(b -> b.initialSize > 0).map(bufArg -> new BufArg(bufArg.name + ".subList(half, same)", bufArg.b.subList(bufArg.initialSize/2, bufArg.initialSize), bufArg.initialSize - bufArg.initialSize/2, Arrays.copyOfRange(bufArg.initialContent, bufArg.initialSize/2, bufArg.initialSize))).toList();
        return Stream.concat(Stream.concat(Stream.concat(sameSizeArgs.stream(), sameSizeArgsBug.stream()), firstHalfArgs.stream()), lastHalfArgs.stream()).toList();
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
    @MethodSource("provideWrappedArgs")
    public void testWrapSubList(BufArg bufArg) {
        testInitialValidity(bufArg);
        testAsArray(bufArg.subList(0, 5));
        testAsArray(bufArg.subList(0, 2));
        testAsArray(bufArg.subList(3, 5));
        testAsArray(bufArg.subList(3, 4));
        testAsArray(bufArg.subList(3, 3));
    }

    @ParameterizedTest
    @MethodSource("provideWrappedArgs")
    public void testWrapCopyOfRange(BufArg bufArg) {
        testInitialValidity(bufArg);
        testAsArray(bufArg.copyOfRange(0, 5));
        testAsArray(bufArg.copyOfRange(0, 2));
        testAsArray(bufArg.copyOfRange(3, 5));
        testAsArray(bufArg.copyOfRange(3, 4));
        testAsArray(bufArg.copyOfRange(3, 3));
        testAsArray(bufArg.copyOfRange(0, bufArg.initialSize));
    }

    public static Stream<BufArg> provideWrappedArgs() {
        byte[] source = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        return Stream.of(
                new BufArg("0-len", Buf.wrap(source.clone()), source.length, source.clone()),
                new BufArg("2-len", Buf.wrap(source.clone(), 2, source.length), source.length - 2, Arrays.copyOfRange(source, 2, source.length)),
                new BufArg("2-9", Buf.wrap(source.clone(), 2, 9), 9 - 2, Arrays.copyOfRange(source, 2, 9)),
                new BufArg("0-9", Buf.wrap(source.clone(), 0, 9), 9, Arrays.copyOfRange(source, 0, 9))
        );
    }

    @ParameterizedTest
    @MethodSource
    public void testLongs(BufArg bufArg) {
        var long1 = bufArg.b.getLong(0);
        assertEquals(Longs.fromByteArray(bufArg.initialContent), long1);
        var long2 = bufArg.b.getLong(Long.BYTES);
        assertEquals(Longs.fromByteArray(Arrays.copyOfRange(bufArg.initialContent, Long.BYTES, Long.BYTES * 2)), long2);

        var expected1 = long1 + 1;
        bufArg.b.setLong(0, expected1);
        var expected2 = long2 + 1;
        bufArg.b.setLong(Long.BYTES, expected2);
        assertEquals(expected1, bufArg.b.getLong(0));
        assertEquals(expected2, bufArg.b.getLong(Long.BYTES));
    }

    public static Stream<BufArg> testLongs() {
        return provideBufs().filter(ba -> ba.initialSize >= Long.BYTES * 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testShorts(BufArg bufArg) {
        var short1 = bufArg.b.getShort(0);
        assertEquals(Shorts.fromByteArray(bufArg.initialContent), short1);
        var short2 = bufArg.b.getShort(Short.BYTES);
        assertEquals(Shorts.fromByteArray(Arrays.copyOfRange(bufArg.initialContent, Short.BYTES, Short.BYTES * 2)), short2);

        var expected1 = (short) (short1 + 1);
        bufArg.b.setShort(0, expected1);
        var expected2 = (short) (short2 + 1);
        bufArg.b.setShort(Short.BYTES, expected2);
        assertEquals(expected1, bufArg.b.getShort(0));
        assertEquals(expected2, bufArg.b.getShort(Short.BYTES));
    }

    public static Stream<BufArg> testShorts() {
        return provideBufs().filter(ba -> ba.initialSize >= Short.BYTES * 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testChars(BufArg bufArg) {
        var char1 = bufArg.b.getChar(0);
        assertEquals(Chars.fromByteArray(bufArg.initialContent), char1);
        var char2 = bufArg.b.getChar(Character.BYTES);
        assertEquals(Chars.fromByteArray(Arrays.copyOfRange(bufArg.initialContent, Character.BYTES, Character.BYTES * 2)), char2);

        var expected1 = (char) (char1 + 1);
        bufArg.b.setChar(0, expected1);
        var expected2 = (char) (char2 + 1);
        bufArg.b.setChar(Character.BYTES, expected2);
        assertEquals(expected1, bufArg.b.getChar(0));
        assertEquals(expected2, bufArg.b.getChar(Character.BYTES));
    }

    public static Stream<BufArg> testChars() {
        return provideBufs().filter(ba -> ba.initialSize >= Character.BYTES * 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testInts(BufArg bufArg) {
        var ib = ByteBuffer.wrap(bufArg.initialContent).asIntBuffer();
        var int1 = bufArg.b.getInt(0);
        assertEquals(ib.get(0), int1);
        var int2 = bufArg.b.getInt(Integer.BYTES);
        assertEquals(ib.get(1), int2);

        var expected1 = int1 + 1;
        bufArg.b.setInt(0, expected1);
        var expected2 = int2 + 1;
        bufArg.b.setInt(Integer.BYTES, expected2);
        assertEquals(expected1, bufArg.b.getInt(0));
        assertEquals(expected2, bufArg.b.getInt(Integer.BYTES));
    }

    public static Stream<BufArg> testInts() {
        return provideBufs().filter(ba -> ba.initialSize >= Integer.BYTES * 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testDoubles(BufArg bufArg) {
        var ib = ByteBuffer.wrap(bufArg.initialContent).asDoubleBuffer();
        var double1 = bufArg.b.getDouble(0);
        assertEquals(ib.get(0), double1);
        var double2 = bufArg.b.getDouble(Double.BYTES);
        assertEquals(ib.get(1), double2);

        var expected1 = double1 + 1;
        bufArg.b.setDouble(0, expected1);
        var expected2 = double2 + 1;
        bufArg.b.setDouble(Double.BYTES, expected2);
        assertEquals(expected1, bufArg.b.getDouble(0));
        assertEquals(expected2, bufArg.b.getDouble(Double.BYTES));
    }

    public static Stream<BufArg> testDoubles() {
        return provideBufs().filter(ba -> ba.initialSize >= Double.BYTES * 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testFloats(BufArg bufArg) {
        var ib = ByteBuffer.wrap(bufArg.initialContent).asFloatBuffer();
        var float1 = bufArg.b.getFloat(0);
        assertEquals(ib.get(0), float1);
        var float2 = bufArg.b.getFloat(Float.BYTES);
        assertEquals(ib.get(1), float2);

        var expected1 = float1 + 1;
        bufArg.b.setFloat(0, expected1);
        var expected2 = float2 + 1;
        bufArg.b.setFloat(Float.BYTES, expected2);
        assertEquals(expected1, bufArg.b.getFloat(0));
        assertEquals(expected2, bufArg.b.getFloat(Float.BYTES));
    }

    public static Stream<BufArg> testFloats() {
        return provideBufs().filter(ba -> ba.initialSize >= Float.BYTES * 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testBooleans(BufArg bufArg) {
        var boolean1 = bufArg.b.getBoolean(0);
        assertEquals(bufArg.initialContent[0] != 0, boolean1);
        var boolean2 = bufArg.b.getBoolean(1);
        assertEquals(bufArg.initialContent[1] != 0, boolean2);

        var expected1 = !boolean1;
        bufArg.b.setBoolean(0, expected1);
        var expected2 = !boolean2;
        bufArg.b.setBoolean(1, expected2);
        assertEquals(expected1, bufArg.b.getBoolean(0));
        assertEquals(expected2, bufArg.b.getBoolean(1));
    }

    public static Stream<BufArg> testBooleans() {
        return provideBufs().filter(ba -> ba.initialSize >= 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testBytes(BufArg bufArg) {
        var byte1 = bufArg.b.getByte(0);
        assertEquals(bufArg.initialContent[0], byte1);
        var byte2 = bufArg.b.getByte(1);
        assertEquals(bufArg.initialContent[1], byte2);
        var byte3 = bufArg.b.getByte(bufArg.initialSize - 1);
        assertEquals(bufArg.initialContent[bufArg.initialSize - 1], byte3);

        var expected1 = (byte) (byte1 + 1);
        bufArg.b.setByte(0, expected1);
        var expected2 = (byte) (byte2 + 1);
        bufArg.b.setByte(1, expected2);
        var expected3 = (byte) (byte3 + 1);
        bufArg.b.setByte(bufArg.initialSize - 1, expected3);
        assertEquals(expected1, bufArg.b.getByte(0));
        assertEquals(expected2, bufArg.b.getByte(1));
        assertEquals(expected3, bufArg.b.getByte(bufArg.initialSize - 1));
    }

    public static Stream<BufArg> testBytes() {
        return provideBufs().filter(ba -> ba.initialSize >= 2);
    }

    @ParameterizedTest
    @MethodSource
    public void testInt52s(BufArg bufArg) {
        var int521 = bufArg.b.getInt52(0);
        assertEquals(Int52.fromByteArrayL(bufArg.initialContent), int521);
        var int522 = bufArg.b.getInt52(Int52.BYTES);
        assertEquals(Int52.fromByteArrayL(Arrays.copyOf(Arrays.copyOfRange(bufArg.initialContent, Int52.BYTES, Int52.BYTES * 2), Long.BYTES)), int522);

        var expected1 = (int521 * 3) % Int52.MAX_VALUE_L;
        bufArg.b.setInt52(0, expected1);
        var expected2 = (int522 * 3) % Int52.MAX_VALUE_L;
        bufArg.b.setInt52(Int52.BYTES, expected2);
        assertEquals(expected1, bufArg.b.getInt52(0));
        assertEquals(expected2, bufArg.b.getInt52(Int52.BYTES));
    }

    public static Stream<BufArg> testInt52s() {
        return provideBufs().filter(ba -> ba.initialSize >= Int52.BYTES * 2);
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testString(BufArg bufArg) {
        var s = new String(bufArg.b.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(s, bufArg.b.toString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testBufBinaryOutputStream(BufArg bufArg) {
        testBinaryOutputStream(bufArg.b.binaryOutputStream());
        testBinaryOutputStream(bufArg.b.binaryOutputStream(0));
        testBinaryOutputStream(bufArg.b.binaryOutputStream(0, 0));
        if (bufArg.initialSize > 10) {
            testBinaryOutputStream(bufArg.b.binaryOutputStream(0, 1));
            testBinaryOutputStream(bufArg.b.binaryOutputStream(5));
            testBinaryOutputStream(bufArg.b.binaryOutputStream(10));
            testBinaryOutputStream(bufArg.b.binaryOutputStream(11));
            testBinaryOutputStream(bufArg.b.binaryOutputStream(0, bufArg.initialSize));
            testBinaryOutputStream(bufArg.b.binaryOutputStream(bufArg.initialSize, bufArg.initialSize));
        }
    }

    //todo:
    private void testBinaryOutputStream(SafeByteArrayOutputStream bos) {

    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testBinaryInputStream(BufArg bufArg) {
        testBinaryInputStream(bufArg.b.binaryInputStream());
    }

    //todo:
    private void testBinaryInputStream(SafeByteArrayInputStream bis) {

    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testWriteTo(BufArg bufArg) {
        try (var safeBaOs = new SafeByteArrayOutputStream()) {
            try (var safeDaOs = new SafeDataOutputStream(safeBaOs)) {
                bufArg.b.writeTo(safeDaOs);
            }
            assertArrayEquals(bufArg.initialContent, safeBaOs.toByteArray());
        }
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testEquals(BufArg bufArg) {
        var b2 = Buf.copyOf(bufArg.initialContent);
        testEquals(bufArg.b, b2);
        testEquals(b2, bufArg.b);
    }

    private void testEquals(Buf a, Buf b) {
        assertEquals(a, b);
        assertArrayEquals(a.toByteArray(), b.toByteArray());
        //noinspection SimplifiableAssertion
        assertTrue(a.equals(b.subListForced(0, b.size())));
        //noinspection SimplifiableAssertion
        assertTrue(a.equals(new ByteArrayList(b)));
        //noinspection SimplifiableAssertion
        assertTrue(a.equals(new ArrayList<>(b)));
        assertTrue(a.equals(0, b, 0, a.size()));
        assertTrue(a.equals(0, b.toByteArray(), 0, a.size()));
        assertTrue(a.equals(0, b, 0, a.size() / 2));
        assertTrue(a.equals(0, b.toByteArray(), 0, a.size() / 2));
        if (a.size() > 5) {
            assertTrue(a.equals(5, b, 5, a.size() - 5));
            assertTrue(a.equals(5, b.toByteArray(), 5, a.size() - 5));
            assertTrue(a.equals(5, b, 5, 0));
            assertTrue(a.equals(5, b.toByteArray(), 5, 0));
            assertFalse(a.equals(0, new byte[1], 100, 1));
        }
        if (a.size() >= 10) {
            assertTrue(a.equals(5, b, 5, a.size() - 5 - 3));
            assertTrue(a.equals(5, b.toByteArray(), 5, a.size() - 5 - 3));
            assertFalse(a.equals(5, b.toByteArray(), 5, a.size()));
        }
        assertFalse(a.equals(a.size(), b, 0, 1));
        assertFalse(a.equals(a.size(), b.toByteArray(), 0, 1));
    }

    @ParameterizedTest
    @MethodSource("provideBufsCompare")
    public void testCompareTo(BufArg bufArg) {
        if (bufArg.initialSize > 0) {
            var bigger = Arrays.copyOf(bufArg.initialContent, bufArg.initialSize + 1);
            assertTrue(bufArg.b.compareTo(Buf.wrap(bigger)) < 0);
            assertTrue(bufArg.b.compareTo(Buf.wrap(bigger).subListForced(0, bigger.length)) < 0);
            assertTrue(bufArg.b.compareTo(new ByteArrayList(bigger)) < 0);
            var smaller = Arrays.copyOf(bufArg.initialContent, bufArg.initialSize - 1);
            assertTrue(bufArg.b.compareTo(Buf.wrap(smaller)) > 0);
            var equal = Arrays.copyOf(bufArg.initialContent, bufArg.initialSize);
            assertEquals(0, bufArg.b.compareTo(Buf.wrap(equal)));
            var bigger2 = Arrays.copyOf(bufArg.initialContent, bufArg.initialSize);
            if (bigger2[bigger2.length - 1] < 127) {
                bigger2[bigger2.length - 1]++;
                assertTrue(bufArg.b.compareTo(Buf.wrap(bigger2)) < 0);
            }
            var smaller2 = Arrays.copyOf(bufArg.initialContent, bufArg.initialSize);
            if (smaller2[smaller2.length - 1] > 0) {
                smaller2[smaller2.length - 1]--;
                assertTrue(bufArg.b.compareTo(Buf.wrap(smaller2)) > 0);
            };
            assertTrue(bufArg.b.compareTo(Buf.create()) > 0);
        }
    }

    @ParameterizedTest
    @MethodSource("provideBufs")
    public void testIterator(BufArg bufArg) {
        var it1 = ByteList.of(bufArg.initialContent).iterator();
        var it2 = bufArg.b.iterator();
        var it3 = bufArg.b.listIterator();
        var it4 = bufArg.b.iterator();
        while (it1.hasNext() && it2.hasNext() && it3.hasNext() && it4.hasNext()) {
            Byte a = it1.nextByte();
            byte b = it4.nextByte();
            //noinspection deprecation
            byte b2 = it2.next();
            //noinspection deprecation
            byte b3 = it3.next();
            //noinspection deprecation
            it3.previous();
            byte b4 = it3.nextByte();
            assertEquals(a, b);
            assertEquals(a, b2);
            assertEquals(a, b3);
            assertEquals(a, b4);
        }
        assertFalse(it1.hasNext());
        assertFalse(it2.hasNext());
        assertFalse(it3.hasNext());
        assertFalse(it4.hasNext());

        // Test list iterator
        {
            var fei = bufArg.b.iterator();
            LongAdder adder = new LongAdder();
            bufArg.b.listIterator().forEachRemaining(b -> {
                assertEquals(fei.nextByte(), b);
                adder.increment();
            });
            assertEquals(bufArg.initialSize, adder.sum());
        }

        // Test list iterator with initial index
        if (bufArg.initialSize > 0) {
            assertEquals(bufArg.b.getByte(bufArg.initialSize - 1), bufArg.b.listIterator(bufArg.initialSize).previousByte());
        }

        // Test spliterator
        //noinspection SimplifyStreamApiCallChains
        assertArrayEquals(bufArg.initialContent, new ByteArrayList(StreamSupport.stream(bufArg.b.spliterator(), true).toList()).toByteArray());
        //noinspection SimplifyStreamApiCallChains
        assertArrayEquals(bufArg.initialContent, new ByteArrayList(StreamSupport.stream(bufArg.b.spliterator(), false).peek(c -> {}).toList()).toByteArray());
        assertArrayEquals(bufArg.initialContent, new ByteArrayList(Spliterators.iterator(bufArg.b.spliterator())).toByteArray());
    }

    @Test
    public void testByteListBufConstructor() {
        ByteListBuf blb1 = new ByteListBuf();
        blb1.add((byte) 0);
        blb1.add((byte) 1);
        blb1.add((byte) 2);
        blb1.add((byte) 3);

        ByteListBuf blb2 = new ByteListBuf(List.of((byte) 0, (byte) 1, (byte) 2, (byte) 3));

        ByteListBuf blb3 = new ByteListBuf(ByteCollections.unmodifiable(ByteList.of((byte) 0, (byte) 1, (byte) 2, (byte) 3)));

        ByteListBuf blb4 = new ByteListBuf(ByteList.of((byte) 0, (byte) 1, (byte) 2, (byte) 3));

        ByteListBuf blb5 = new ByteListBuf(new byte[] {(byte) 0, (byte) 1, (byte) 2, (byte) 3});

        ByteListBuf blb6 = new ByteListBuf(new byte[] {(byte) -1, (byte) 0, (byte) 1, (byte) 2, (byte) 3, (byte) 4, (byte) 5, (byte) 6}, 1, 4);

        ByteListBuf blb7 = new ByteListBuf(List.of((byte) 0, (byte) 1, (byte) 2, (byte) 3).iterator());

        ByteListBuf blb8 = new ByteListBuf(ByteList.of((byte) 0, (byte) 1, (byte) 2, (byte) 3).iterator());

        assertEquals(blb1, blb2);
        assertEquals(blb1, blb3);
        assertEquals(blb1, blb4);
        assertEquals(blb1, blb5);
        assertEquals(blb1, blb6);
        assertEquals(blb1, blb7);
        assertEquals(blb1, blb8);
    }
}
