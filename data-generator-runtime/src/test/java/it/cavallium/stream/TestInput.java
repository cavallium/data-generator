package it.cavallium.stream;

import it.cavallium.buffer.Buf;
import it.cavallium.buffer.BufDataInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource")
public class TestInput {

    public static final byte[] DATA = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    public static Stream<SafeInputStream> provideStreams() {
        var dataLarge = new byte[] {-2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        return Stream.of(
                new SafeDataInputStream(new SafeByteArrayInputStream(DATA)),
                new SafeByteArrayInputStream(DATA),
                BufDataInput.create(Buf.wrap(DATA)),
                BufDataInput.create(Buf.wrap(dataLarge).subList(2, 12)));
    }

    @Test
    public void testBufDataInputValidity() {
        var bdi = BufDataInput.create(Buf.wrap((byte) 1, (byte) 2, (byte) 3, (byte) 4));
        assertThrows(UnsupportedOperationException.class, () -> bdi.mark(1));
        assertThrows(UnsupportedOperationException.class, bdi::reset);
        //noinspection deprecation
        assertDoesNotThrow(bdi::close);
        assertFalse(bdi.markSupported());
    }

    @Test
    public void testString() throws IOException {
        String data = "Ciaoç\uD83D\uDC6A";
        var shortBaos = new ByteArrayOutputStream();
        var medBaos = new ByteArrayOutputStream();
        var shortDaos = new DataOutputStream(shortBaos);
        var medDaos = new DataOutputStream(medBaos);
        var sbytes = data.getBytes(StandardCharsets.UTF_8);
        shortDaos.writeShort(sbytes.length);
        shortDaos.write(sbytes);
        medDaos.writeInt(sbytes.length);
        medDaos.write(sbytes);
        var shortBytes = shortBaos.toByteArray();
        var medBytes = medBaos.toByteArray();
        var bdi = BufDataInput.create(Buf.wrap(shortBytes));
        //noinspection deprecation
        assertEquals(data, bdi.readUTF());
        var bdi2 = BufDataInput.create(Buf.wrap(shortBytes).subList(Short.BYTES, shortBytes.length));
        assertEquals(data, bdi2.readString(sbytes.length, StandardCharsets.UTF_8));
        var bdi3 = BufDataInput.create(Buf.wrap(shortBytes));
        assertEquals(data, bdi3.readShortText(StandardCharsets.UTF_8));
        var bdi4 = BufDataInput.create(Buf.wrap(medBytes));
        assertEquals(data, bdi4.readMediumText(StandardCharsets.UTF_8));
    }

    @Test
    public void testReadTypes() throws IOException {
        var baos = new ByteArrayOutputStream();
        var daos = new DataOutputStream(baos);
        daos.write(10);
        daos.writeByte(10);
        daos.writeShort(10);
        daos.writeByte(255);
        daos.writeShort(50000);
        daos.writeChar(10);
        daos.writeUTF("123");
        daos.writeInt(10);
        daos.writeLong(10);
        daos.writeFloat(10);
        daos.writeDouble(10);
        daos.writeBoolean(true);
        var bytes1 = new byte[] {1, 2, 3, 4};
        daos.write(bytes1);
        daos.write(0);
        daos.write(0);
        daos.write(0);
        daos.write("Ciao".getBytes(StandardCharsets.UTF_8));
        {
            var writeBuffer = new byte[7];
            var v = (1L << 52) - 1;
            writeBuffer[0] = (byte)(v >> 48 & 0xf);
            writeBuffer[1] = (byte)(v >> 40 & 0xff);
            writeBuffer[2] = (byte)(v >> 32 & 0xff);
            writeBuffer[3] = (byte)(v >> 24 & 0xff);
            writeBuffer[4] = (byte)(v >> 16 & 0xff);
            writeBuffer[5] = (byte)(v >> 8 & 0xff);
            writeBuffer[6] = (byte)(v & 0xff);
            daos.write(writeBuffer);
        }
        daos.writeShort(4);
        daos.write("Ciao".getBytes(StandardCharsets.UTF_8));
        daos.writeInt(4);
        daos.write("Ciao".getBytes(StandardCharsets.UTF_8));
        daos.write(1);
        daos.write(2);
        var initialArray = baos.toByteArray();

        var bdi = BufDataInput.create(Buf.wrap(initialArray));
        assertEquals(10, bdi.read());
        assertEquals(10, bdi.readByte());
        assertEquals(10, bdi.readShort());
        assertEquals(255, bdi.readUnsignedByte());
        assertEquals(50000, bdi.readUnsignedShort());
        assertEquals(10, bdi.readChar());
        //noinspection deprecation
        assertEquals("123", bdi.readUTF());
        assertEquals(10, bdi.readInt());
        assertEquals(10, bdi.readLong());
        assertEquals(10, bdi.readFloat());
        assertEquals(10, bdi.readDouble());
        assertTrue(bdi.readBoolean());
        {
            var in = new byte[4];
            bdi.readFully(in);
            assertArrayEquals(bytes1, in);
        }
        bdi.skipNBytes(1);
        assertEquals(1, bdi.skipBytes(1));
        assertEquals(1, bdi.skip(1));
        assertEquals("Ciao", bdi.readString(4, StandardCharsets.UTF_8));
        assertEquals((1L << 52) - 1, bdi.readInt52());
        assertEquals("Ciao", bdi.readShortText(StandardCharsets.UTF_8));
        assertEquals("Ciao", bdi.readMediumText(StandardCharsets.UTF_8));
        {
            var buf = new byte[1];
            assertEquals(1, bdi.read(buf));
            assertArrayEquals(new byte[] {1}, buf);
            assertEquals(1, bdi.read(buf, 0, 1));
            assertArrayEquals(new byte[] {2}, buf);
        }
        {
            var bdi1 = BufDataInput.create(Buf.create());
            assertEquals(0, bdi1.skip(1));
            assertEquals(0, bdi1.skipBytes(1));
            assertThrows(IndexOutOfBoundsException.class, () -> bdi1.readString(10, StandardCharsets.UTF_8));
            var in = new byte[4];
            assertThrows(IndexOutOfBoundsException.class, () -> bdi1.readFully(in));
            assertThrows(IndexOutOfBoundsException.class, () -> bdi1.readFully(in, 0, -1));
            assertThrows(IndexOutOfBoundsException.class, bdi1::readBoolean);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readByte);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readShort);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readInt);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readInt52);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readLong);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readFloat);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readChar);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readDouble);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readUnsignedShort);
            assertThrows(IndexOutOfBoundsException.class, bdi1::readUnsignedByte);
        }
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testSkip(SafeInputStream is) {
        assertEquals(10, is.skip(15));
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testSkipNBytes(SafeInputStream is) {
        assertDoesNotThrow(() -> is.skipNBytes(10));
        assertThrows(Exception.class, () -> is.skipNBytes(1));
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testRead(SafeInputStream is) {
        for (int i = 0; i < 10; i++) {
            assertEquals(i, is.read());
        }
        assertEquals(-1, is.read());
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testReadSmaller(SafeInputStream is) {
        byte[] data = new byte[9];
        assertEquals(9, is.read(data));
        assertArrayEquals(Arrays.copyOf(DATA, 9), data);
        assertEquals(9, is.read());
        assertEquals(-1, is.read());
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testReadBigger(SafeInputStream is) {
        byte[] data = new byte[11];
        assertEquals(10, is.read(data));
        assertArrayEquals(Arrays.copyOf(DATA, 11), data);
        assertEquals(-1, is.read());
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testReadExact(SafeInputStream is) {
        byte[] data = new byte[10];
        assertEquals(10, is.read(data));
        assertArrayEquals(DATA, data);
        assertEquals(-1, is.read());
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testPosition(SafeInputStream is) {
        if (is instanceof SafeByteArrayInputStream bis) {
            assertEquals(10, bis.available());
            assertEquals(10, bis.length());
            assertEquals(0, bis.position());
            assertEquals(0, bis.read());
            assertEquals(1, bis.position());
            assertEquals(1, bis.read());
            assertEquals(2, bis.position());
            assertEquals(2, bis.read());
            assertEquals(3, bis.position());
            assertEquals(7, bis.skip(1000));
            assertEquals(-1, bis.read());
            assertEquals(10, bis.position());
            bis.position(0);
            assertEquals(0, bis.position());
            assertEquals(0, bis.read());
            assertEquals(1, bis.position());
            assertEquals(9, bis.available());
            assertEquals(10, bis.length());
        }
    }

    @ParameterizedTest
    @MethodSource("provideStreams")
    public void testMark(SafeInputStream is) {
        if (is.markSupported()) {
            is.mark(1);
            assertEquals(0, is.read());
            assertEquals(1, is.read());
            is.reset();
            assertEquals(0, is.read());
            assertEquals(1, is.read());
            is.mark(1);
            is.mark(1);
            assertEquals(2, is.read());
            assertEquals(3, is.read());
            is.reset();
            is.reset();
            assertEquals(2, is.read());
            assertEquals(3, is.read());
            assertThrows(Exception.class, () -> is.mark(-1));
        } else {
            assertThrows(Exception.class, () -> is.mark(0));
            assertThrows(Exception.class, () -> is.mark(10));
            assertThrows(Exception.class, is::reset);
        }
    }
}
