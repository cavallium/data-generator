package it.cavallium.buffer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BufDataOutputTest {
    @Test
    public void writeMediumText() {
        var bdo = BufDataOutput.create();
        bdo.writeInt(5);
        bdo.writeMediumText("ciao", StandardCharsets.UTF_8);
        var buf2 = bdo.toList();
        var bdi = BufDataInput.create(buf2);
        bdi.skipNBytes(Integer.BYTES);
        Assertions.assertEquals("ciao", bdi.readMediumText(StandardCharsets.UTF_8));
    }
}