package it.cavallium.stream;

import it.cavallium.buffer.Buf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOutput {

    @Test
    public void testBufOutputStream() throws IOException {
        var buf = Buf.createZeroes(Integer.BYTES * 3);
        var subMiddleBuf = buf.subList(Integer.BYTES, Integer.BYTES * 2);
        buf.setInt(0, 0);
        buf.setInt(Integer.BYTES, 5);
        buf.setInt(Integer.BYTES * 2, 4);
        var subBuf = buf.subList(Integer.BYTES, Integer.BYTES * 3);
        var subBufOut = subBuf.binaryOutputStream();
        var subBufOutData = new SafeDataOutputStream(subBufOut);
        subBufOutData.writeInt(9);
        subBufOut.position(0);
        subBufOutData.writeInt(1);
        subBufOutData.writeInt(2);
        var i0 = buf.getInt(0);
        var i1 = buf.getInt(Integer.BYTES);
        var i2 = buf.getInt(Integer.BYTES * 2);
        assertEquals(List.of(0, 1, 2), List.of(i0, i1, i2));
        {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(1);
            dos.writeInt(2);
            assertArrayEquals(baos.toByteArray(), subBufOut.toByteArray());
        }
        {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(0);
            dos.writeInt(1);
            dos.writeInt(2);
            assertArrayEquals(baos.toByteArray(), buf.toByteArray());
        }
        {
            var baos = new ByteArrayOutputStream();
            var dos = new DataOutputStream(baos);
            dos.writeInt(1);
            assertArrayEquals(baos.toByteArray(), subMiddleBuf.toByteArray());
        }
    }

    @ParameterizedTest
    @MethodSource("provideByteArrayOutputStreams")
    public void testByteArrayOutputStream(SafeByteArrayOutputStream baos) {
        assertArrayEquals(new byte[0], baos.toByteArray());
        baos.write(0);
        baos.write(0);
        baos.write(0);
        assertArrayEquals(new byte[3], baos.toByteArray());
    }

    @ParameterizedTest
    @MethodSource("provideByteArrayOutputStreams")
    public void testTrim(SafeByteArrayOutputStream baos) {
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

    public static Stream<SafeByteArrayOutputStream> provideByteArrayOutputStreams() {
        return Stream.of(new SafeByteArrayOutputStream(),
                new SafeByteArrayOutputStream(10),
                new SafeByteArrayOutputStream(8),
                new SafeByteArrayOutputStream(20),
                new SafeByteArrayOutputStream()
        );
    }
}
