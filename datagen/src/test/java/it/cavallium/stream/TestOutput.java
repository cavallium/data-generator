package it.cavallium.stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataOutput;
import it.cavallium.datagen.nativedata.Int52;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class TestOutput {

    @Test
    public void testBufOutputStream() throws IOException {
        var buf = Buf.createZeroes(Integer.BYTES * 3 + Short.BYTES + Character.BYTES + 4);
        var subMiddleBuf = buf.subList(Integer.BYTES, Integer.BYTES * 2);
        buf.setInt(0, 0);
        buf.setInt(Integer.BYTES, 5);
        buf.setInt(Integer.BYTES * 2, 4);
        var subBuf = buf.subList(Integer.BYTES, Integer.BYTES * 3 + Short.BYTES + Character.BYTES + 4);
        var subBufOut = subBuf.binaryOutputStream();
        var subBufOutData = new SafeDataOutputStream(subBufOut);
        assertEquals(0, subBufOutData.size());
        subBufOutData.writeInt(9);
        subBufOut.position(0);
        subBufOutData.writeInt(1);
        subBufOutData.writeInt(2);
        subBufOutData.writeShort(1);
        subBufOutData.writeChar(1);
        subBufOutData.write(new byte[] {1, 2});
        subBufOutData.write(new byte[] {0, 0, 3, 4, 0, 0}, 2, 2);
        assertEquals(Integer.BYTES, Integer.BYTES * 3 + Short.BYTES + Character.BYTES + 4, subBufOutData.size());
        assertDoesNotThrow(subBufOutData::toString);
        assertDoesNotThrow(subBufOut::toString);
        assertThrows(Exception.class, () -> subBufOutData.writeByte(1));
        assertThrows(Exception.class, () -> subBufOut.ensureWritable(1));
        var i0 = buf.getInt(0);
        var i1 = buf.getInt(Integer.BYTES);
        var i2 = buf.getInt(Integer.BYTES * 2);
        var s1 = (buf.getByte(Integer.BYTES * 3) << 8) | buf.getByte(Integer.BYTES * 3 + 1);
        var c1 = (buf.getByte(Integer.BYTES * 3 + Short.BYTES) << 8) | buf.getByte(Integer.BYTES * 3 + Short.BYTES + 1);
        var b1 = buf.getByte(Integer.BYTES * 3 + Short.BYTES + Character.BYTES);
        var b2 = buf.getByte(Integer.BYTES * 3 + Short.BYTES + Character.BYTES + 1);
        var b3 = buf.getByte(Integer.BYTES * 3 + Short.BYTES + Character.BYTES + 2);
        var b4 = buf.getByte(Integer.BYTES * 3 + Short.BYTES + Character.BYTES + 3);
        assertEquals(List.of(0, 1, 2), List.of(i0, i1, i2));
        assertEquals(1, s1);
        assertEquals(1, c1);
        assertEquals(List.of(1, 2, 3, 4), List.of((int) b1, (int) b2, (int) b3, (int) b4));
        {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(1);
            dos.writeInt(2);
            dos.writeShort(1);
            dos.writeChar(1);
            dos.writeByte(1);
            dos.writeByte(2);
            dos.writeByte(3);
            dos.writeByte(4);
            assertArrayEquals(baos.toByteArray(), subBufOut.toByteArray());
        }
        {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(0);
            dos.writeInt(1);
            dos.writeInt(2);
            dos.writeShort(1);
            dos.writeChar(1);
            dos.writeByte(1);
            dos.writeByte(2);
            dos.writeByte(3);
            dos.writeByte(4);
            assertArrayEquals(baos.toByteArray(), buf.toByteArray());
        }
        {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(1);
            assertArrayEquals(baos.toByteArray(), subMiddleBuf.toByteArray());
        }
        {
            var b = Buf.createZeroes(Long.BYTES * 4);
            var os = b.binaryOutputStream();
            var ds = new SafeDataOutputStream(os);
            ds.writeBoolean(true);
            assertTrue(b.getBoolean(0));
            os.reset();

            ds.writeByte(Byte.MAX_VALUE - 1);
            assertEquals(Byte.MAX_VALUE - 1, b.getByte(0));
            os.reset();

            ds.writeShort(Short.MAX_VALUE - 1);
            assertEquals(Short.MAX_VALUE - 1, b.getShort(0));
            os.reset();

            ds.writeChar(Character.MAX_VALUE - 1);
            assertEquals(Character.MAX_VALUE - 1, b.getChar(0));
            os.reset();

            ds.writeInt(Integer.MAX_VALUE - 1);
            assertEquals(Integer.MAX_VALUE - 1, b.getInt(0));
            os.reset();

            ds.writeLong(Long.MAX_VALUE - 1);
            assertEquals(Long.MAX_VALUE - 1, b.getLong(0));
            os.reset();

            ds.writeInt52(Int52.MAX_VALUE_L - 1);
            assertEquals(Int52.MAX_VALUE_L - 1, b.getInt52(0));
            os.reset();

            ds.writeFloat(Float.MAX_VALUE - 1);
            assertEquals(Float.MAX_VALUE - 1, b.getFloat(0));
            os.reset();

            ds.writeDouble(Double.MAX_VALUE - 1);
            assertEquals(Double.MAX_VALUE - 1, b.getDouble(0));
            os.reset();

            ds.write(10);
            ds.write(10);
            ds.writeShortText("Ciao", StandardCharsets.UTF_8);
            assertEquals("Ciao", b.getShortText(2, StandardCharsets.UTF_8));
            assertEquals("Ciao", b.subList(1, b.size()).getShortText(1, StandardCharsets.UTF_8));
            assertThrows(Exception.class, () -> ds.writeShortText("1".repeat(Short.MAX_VALUE + 1), StandardCharsets.UTF_8));
            os.reset();

            ds.write(10);
            ds.write(10);
            ds.writeMediumText("Ciao", StandardCharsets.UTF_8);
            assertEquals("Ciao", b.getMediumText(2, StandardCharsets.UTF_8));
            assertEquals("Ciao", b.subList(1, b.size()).getMediumText(1, StandardCharsets.UTF_8));
            os.reset();
        }
    }

    @Test
    public void testWrappedBufDataOutput() throws IOException {
        var sz = Long.BYTES * 4;
        BufDataOutput bdo;
        Buf buf;
        {
            bdo = BufDataOutput.createLimited(sz);
            bdo.writeBoolean(true);
            buf = bdo.asList();
            assertTrue(buf.getBoolean(0));
        }
        {
            bdo = BufDataOutput.createLimited(sz);
            bdo.writeByte(Byte.MAX_VALUE - 1);
            buf = bdo.asList();
            assertEquals(Byte.MAX_VALUE - 1, buf.getByte(0));
        }
        {
            bdo = BufDataOutput.createLimited(sz);
            bdo.writeShort(Short.MAX_VALUE - 1);
            buf = bdo.asList();
            assertEquals(Short.MAX_VALUE - 1, buf.getShort(0));
        }
        {
            bdo = BufDataOutput.createLimited(sz);
            bdo.writeChar(Character.MAX_VALUE - 1);
            buf = bdo.asList();
            assertEquals(Character.MAX_VALUE - 1, buf.getChar(0));
        }
        {
            bdo = BufDataOutput.createLimited(sz);
            bdo.writeInt(Integer.MAX_VALUE - 1);
            buf = bdo.asList();
            assertEquals(Integer.MAX_VALUE - 1, buf.getInt(0));
        }
        {
            bdo = BufDataOutput.createLimited(sz);
            bdo.writeLong(Long.MAX_VALUE - 1);
            buf = bdo.asList();
            assertEquals(Long.MAX_VALUE - 1, buf.getLong(0));
        }
    }

    @ParameterizedTest
    @MethodSource("provideByteArrayOutputStreams")
    public void testByteArrayOutputStream(SafeByteArrayOutputStream baos) {
        assertArrayEquals(new byte[0], baos.toByteArray());
        baos.write(0);
        baos.write(0);
        baos.write(0);
        var x = new byte[] {1, 2, 3, 4};
        baos.write(x);
        baos.write(x, 1, 2);
        assertArrayEquals(new byte[] {0, 0, 0, 1, 2, 3, 4, 2, 3}, baos.toByteArray());
    }

    @ParameterizedTest
    @MethodSource("provideByteArrayOutputStreams")
    public void testTrimAndGrow(SafeByteArrayOutputStream baos) {
        baos.trim();
        assertEquals(0, baos.array.length);
        baos.write(10);
        baos.trim();
        assertEquals(1, baos.array.length);
        assertArrayEquals(new byte[] {10}, baos.array);
        baos.ensureWritable(2);
        assertEquals(3, baos.array.length);
        assertArrayEquals(new byte[] {10, 0, 0}, baos.array);
    }

    @ParameterizedTest
    @MethodSource("provideByteArrayOutputStreams")
    public void testReset(SafeByteArrayOutputStream baos) {
        baos.trim();
        baos.write(10);
        baos.write(10);
        assertEquals(2, baos.position());
        assertEquals(2, baos.length());
        assertEquals(2, baos.array.length);
        baos.reset();
        assertEquals(0, baos.position());
        assertEquals(0, baos.length());
        assertEquals(2, baos.array.length);
    }

    public static Stream<SafeByteArrayOutputStream> provideByteArrayOutputStreams() {
        return Stream.of(new SafeByteArrayOutputStream(),
                new SafeByteArrayOutputStream(10),
                new SafeByteArrayOutputStream(8),
                new SafeByteArrayOutputStream(20),
                new SafeByteArrayOutputStream()
        );
    }

    @Test
    public void testNOS() {
        try (var nos = SafeDataOutputStream.nullOutputStream()) {
            nos.write(0);
            nos.flush();
        }
    }
}
