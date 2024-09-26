package it.cavallium.buffer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@Fork(value = 1, warmups = 1)
@Warmup(time = 1, iterations = 3)
@Measurement(time = 1, iterations = 6)
public class ZeroAllocationEncoderBench {

    @State(Scope.Thread)
    public static class ZeroAllocationEncoderState {
        ZeroAllocationEncoder encoder;
        BufDataOutput bufOutput;

        @Setup
        public void prepare() {
            encoder = ZeroAllocationEncoder.INSTANCE;
            bufOutput = BufDataOutput.create(1024);
        }
    }

    @State(Scope.Benchmark)
    public static class ZeroAllocationEncoderBenchState {
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
        String shortText;
        byte[] shortTextBytes;
        BufDataInput shortTextInput;
        BufDataOutput shortTextOutput;
        String mediumText;
        byte[] mediumTextBytes;
        BufDataInput mediumTextInput;
        BufDataOutput mediumTextOutput;
        String longText;
        byte[] longTextBytes;
        BufDataInput longTextInput;
        BufDataOutput longTextOutput;

        @Setup(Level.Invocation)
        public void reset() {
            longTextInput.reset();
            longTextOutput.resetUnderlyingBuffer();
            mediumTextInput.reset();
            mediumTextOutput.resetUnderlyingBuffer();
            shortTextInput.reset();
            shortTextOutput.resetUnderlyingBuffer();
        }

        @Setup
        public void prepare() {
            var l = new ArrayList<String>();
            var maxI = ThreadLocalRandom.current().nextInt(1, 20);
            for (int i = 0; i < maxI; i++) {
                l.addAll(WORDS);
            }
            Collections.shuffle(l);
            var fullText = String.join(" ", l);
            longText = fullText;
            longTextBytes = longText.getBytes(StandardCharsets.UTF_8);
            longTextInput = BufDataInput.create(Buf.wrap(longTextBytes));
            longTextOutput = BufDataOutput.create(longTextBytes.length);
            mediumText = fullText.substring(0, 128);
            mediumTextBytes = mediumText.getBytes(StandardCharsets.UTF_8);
            mediumTextInput = BufDataInput.create(Buf.wrap(mediumTextBytes));
            mediumTextOutput = BufDataOutput.create(mediumTextBytes.length);
            shortText = fullText.substring(0, 15);
            shortTextBytes = shortText.getBytes(StandardCharsets.UTF_8);
            shortTextInput = BufDataInput.create(Buf.wrap(shortTextBytes));
            shortTextOutput = BufDataOutput.create(shortTextBytes.length);
        }
    }

    @Benchmark
    public BufDataOutput encodeShortTextZeroCopy(ZeroAllocationEncoderState encoderState, ZeroAllocationEncoderBenchState benchState) {
        var out = benchState.shortTextOutput;
        encoderState.encoder.encodeTo(benchState.shortText, out);
        return out;
    }

    @Benchmark
    public BufDataOutput encodeMediumTextZeroCopy(ZeroAllocationEncoderState encoderState, ZeroAllocationEncoderBenchState benchState) {
        var out = benchState.mediumTextOutput;
        encoderState.encoder.encodeTo(benchState.mediumText, out);
        return out;
    }

    @Benchmark
    public BufDataOutput encodeLongTextZeroCopy(ZeroAllocationEncoderState encoderState, ZeroAllocationEncoderBenchState benchState) {
        var out = benchState.longTextOutput;
        encoderState.encoder.encodeTo(benchState.longText, out);
        return out;
    }

    @Benchmark
    public BufDataOutput encodeShortTextJava(ZeroAllocationEncoderBenchState benchState) {
        var out = benchState.shortTextOutput;
        out.write(benchState.shortText.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    @Benchmark
    public BufDataOutput encodeMediumTextJava(ZeroAllocationEncoderBenchState benchState) {
        var out = benchState.mediumTextOutput;
        out.write(benchState.mediumText.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    @Benchmark
    public BufDataOutput encodeLongTextJava(ZeroAllocationEncoderBenchState benchState) {
        var out = benchState.longTextOutput;
        out.write(benchState.longText.getBytes(StandardCharsets.UTF_8));
        return out;
    }


    @Benchmark
    public String decodeShortTextZeroCopy(ZeroAllocationEncoderState encoderState, ZeroAllocationEncoderBenchState benchState) {
        return encoderState.encoder.decodeFrom(benchState.shortTextInput, benchState.shortTextBytes.length);
    }

    @Benchmark
    public String decodeMediumTextZeroCopy(ZeroAllocationEncoderState encoderState, ZeroAllocationEncoderBenchState benchState) {
        return encoderState.encoder.decodeFrom(benchState.mediumTextInput, benchState.mediumTextBytes.length);
    }

    @Benchmark
    public String decodeLongTextZeroCopy(ZeroAllocationEncoderState encoderState, ZeroAllocationEncoderBenchState benchState) {
        return encoderState.encoder.decodeFrom(benchState.longTextInput, benchState.longTextBytes.length);
    }

    @Benchmark
    public String decodeShortTextJava(ZeroAllocationEncoderBenchState benchState) {
        return new String(benchState.shortTextInput.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Benchmark
    public String decodeMediumTextJava(ZeroAllocationEncoderBenchState benchState) {
        return new String(benchState.mediumTextInput.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Benchmark
    public String decodeLongTextJava(ZeroAllocationEncoderBenchState benchState) {
        return new String(benchState.longTextInput.readAllBytes(), StandardCharsets.UTF_8);
    }

}