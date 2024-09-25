package it.cavallium.buffer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ZeroAllocationEncoderTest {

    private static final ZeroAllocationEncoder INSTANCE = new ZeroAllocationEncoder(16);

    @Test
    void encodeToEmpty() {
        testEncodeString("");
    }

    @Test
    void decodeEmpty() {
        testDecodeString("");
    }

    @Test
    void encodeTo1Underflow() {
        testEncodeString("ciao");
    }

    @Test
    void decode1Underflow() {
        testDecodeString("ciao");
    }

    @Test
    void encodeToExact1() {
        testEncodeString("lorem ipsum dolo");
    }

    @Test
    void decodeExact1() {
        testDecodeString("lorem ipsum dolo");
    }

    @Test
    void encodeToOverflow1() {
        testEncodeString("lorem ipsum dolor sit amet");
    }

    @Test
    void decodeOverflow1() {
        testDecodeString("lorem ipsum dolor sit amet");
    }

    @Test
    void encodeToExact2() {
        testEncodeString("lorem ipsum dolor sit amet my na");
    }

    @Test
    void decodeExact2() {
        testDecodeString("lorem ipsum dolor sit amet my na");
    }

    @Test
    void encodeToOverflow2() {
        testEncodeString("lorem ipsum dolor sit amet my name is giovanni");
    }

    @Test
    void decodeOverflow2() {
        testDecodeString("lorem ipsum dolor sit amet my name is giovanni");
    }

    public void testEncodeString(String s) {
        var bdo = BufDataOutput.create();
        INSTANCE.encodeTo(s, bdo);
        var out = bdo.toList().toString(StandardCharsets.UTF_8);
        Assertions.assertEquals(s, out);
    }

    private void testDecodeString(String s) {
        var in = BufDataInput.create(Buf.wrap(s.getBytes(StandardCharsets.UTF_8)));
        var out = INSTANCE.decodeFrom(in, in.available());
        Assertions.assertEquals(s, out);
    }
}