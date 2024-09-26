package it.cavallium.buffer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ZeroAllocationEncoderTest {

    private static final ZeroAllocationEncoder INSTANCE = new ZeroAllocationEncoder(16);


    private static final List<String> WORDS = List.of(
            "\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC67\uD83C\uDFFF\u200D\uD83D\uDC66\uD83C\uDFFF",
            "hello",
            "test",
            "òàòà§òè+=))=732e0",
            "ل موقع يسمح لزواره الكرام بتحويل الكتابة العربي الى كتابة مفه",
            "\uD800\uDF3C\uD800\uDF30\uD800\uDF32 \uD800\uDF32\uD800\uDF3B\uD800\uDF34\uD800\uDF43 \uD800\uDF39̈\uD800\uDF44\uD800\uDF30\uD800\uDF3D, \uD800\uDF3D\uD800\uDF39 \uD800\uDF3C\uD800\uDF39\uD800\uDF43 \uD800\uDF45\uD800\uDF3F \uD800\uDF3D\uD800\uDF33\uD800\uDF30\uD800\uDF3D \uD800\uDF31\uD800\uDF42\uD800\uDF39\uD800\uDF32\uD800\uDF32\uD800\uDF39\uD800\uDF38.",
            "Z̤͔ͧ̑̓ä͖̭̈̇lͮ̒ͫǧ̗͚̚o̙̔ͮ̇͐̇",
            "من left اليمين to الى right اليسار",
            "a\u202Db\u202Ec\u202Dd\u202Ee\u202Df\u202Eg",
            "﷽﷽﷽﷽﷽﷽﷽﷽﷽﷽﷽﷽﷽﷽﷽﷽",
            "\uD83D\uDC71\uD83D\uDC71\uD83C\uDFFB\uD83D\uDC71\uD83C\uDFFC\uD83D\uDC71\uD83C\uDFFD\uD83D\uDC71\uD83C\uDFFE\uD83D\uDC71\uD83C\uDFFF",
            "\uD83E\uDDDF\u200D♀\uFE0F\uD83E\uDDDF\u200D♂\uFE0F",
            "\uD83D\uDC68\u200D❤\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68\uD83D\uDC69\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66\uD83C\uDFF3\uFE0F\u200D⚧\uFE0F\uD83C\uDDF5\uD83C\uDDF7",
            "田中さんにあげて下さい",
            "ด้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็ ด้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็ ด้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็็้้้้้้้้็็็็็้้้้้็็็็",
            "\uD801\uDC1C \uD801\uDC14\uD801\uDC07\uD801\uDC1D\uD801\uDC00\uD801\uDC21\uD801\uDC07\uD801\uDC13 \uD801\uDC19\uD801\uDC0A\uD801\uDC21\uD801\uDC1D\uD801\uDC13/\uD801\uDC1D\uD801\uDC07\uD801\uDC17\uD801\uDC0A\uD801\uDC24\uD801\uDC14 \uD801\uDC12\uD801\uDC0B\uD801\uDC17 \uD801\uDC12\uD801\uDC0C \uD801\uDC1C \uD801\uDC21\uD801\uDC00\uD801\uDC16\uD801\uDC07\uD801\uDC24\uD801\uDC13\uD801\uDC1D \uD801\uDC31\uD801\uDC42 \uD801\uDC44 \uD801\uDC14\uD801\uDC07\uD801\uDC1D\uD801\uDC00\uD801\uDC21\uD801\uDC07\uD801\uDC13 \uD801\uDC0F\uD801\uDC06\uD801\uDC05\uD801\uDC24\uD801\uDC06\uD801\uDC1A\uD801\uDC0A\uD801\uDC21\uD801\uDC1D\uD801\uDC06\uD801\uDC13\uD801\uDC06",
            "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀\uD840\uDC00",
            "᚛ᚄᚓᚐᚋᚒᚄ ᚑᚄᚂᚑᚏᚅ᚜\n" +
                    "᚛                 ᚜\n",
            "Powerلُلُصّبُلُلصّبُررً ॣ ॣh ॣ ॣ冗\n" +
                    "\uD83C\uDFF30\uD83C\uDF08\uFE0F\n" +
                    "జ్ఞ\u200Cా"
    );

    @Test
    void encodeFuzzer() {
        var l = new ArrayList<>(WORDS);
        Collections.shuffle(l);
        var collected = l.stream().collect(Collectors.joining(" "));
        testEncodeString(collected);
    }

    @Test
    void decodeFuzzer() {
        var l = new ArrayList<>(WORDS);
        Collections.shuffle(l);
        var collected = l.stream().collect(Collectors.joining(" "));
        testDecodeString(collected);
    }

    @Test
    void encodeToEmpty() {
        testEncodeString("");
    }

    @Test
    void decodeEmpty() {
        testDecodeString("");
    }

    @Test
    void encodeComplex() {
        testEncodeString("\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC67\uD83C\uDFFF\u200D\uD83D\uDC66\uD83C\uDFFF");
    }

    @Test
    void decodeComplex() {
        testDecodeString("\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC67\uD83C\uDFFF\u200D\uD83D\uDC66\uD83C\uDFFF");
    }

    @Test
    void encodeComplexLong() {
        testEncodeString("\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC67\uD83C\uDFFF\u200D\uD83D\uDC66\uD83C\uDFFF".repeat(10));
    }

    @Test
    void decodeComplexLong() {
        testDecodeString("\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC69\uD83C\uDFFF\u200D\uD83D\uDC67\uD83C\uDFFF\u200D\uD83D\uDC66\uD83C\uDFFF".repeat(10));
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

    @Test
    void encodeToLong() {
        testEncodeString("lorem ipsum dolor sit amet ".repeat(10));
    }

    @Test
    void decodeLong() {
        testDecodeString("lorem ipsum dolor sit amet".repeat(10));
    }

    public void testEncodeString(String s) {
        var bdo = BufDataOutput.create();
        INSTANCE.encodeTo(s, bdo);
        var out = bdo.toList();
        out.toString(StandardCharsets.UTF_8);
        Assertions.assertEquals(s, out.toString(StandardCharsets.UTF_8));
        Assertions.assertEquals(s.getBytes(StandardCharsets.UTF_8).length, bdo.size());
        Assertions.assertEquals(s.getBytes(StandardCharsets.UTF_8).length, out.size());

        var bdo2 = BufDataOutput.create();
        bdo2.writeMediumText("ciao", StandardCharsets.UTF_8);
        bdo2.writeShortText("ciao2", StandardCharsets.UTF_8);
        var in = BufDataInput.create(bdo2.asList());
        Assertions.assertEquals("ciao", in.readMediumText(StandardCharsets.UTF_8));
        Assertions.assertEquals("ciao2", in.readShortText(StandardCharsets.UTF_8));
    }

    private void testDecodeString(String s) {
        var in = BufDataInput.create(Buf.wrap(s.getBytes(StandardCharsets.UTF_8)));
        var out = INSTANCE.decodeFrom(in, in.available());
        Assertions.assertEquals(s, out);
    }
}