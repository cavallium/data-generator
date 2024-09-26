package it.cavallium.buffer;

import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class ZeroAllocationEncoder {

    public static final ZeroAllocationEncoder INSTANCE = new ZeroAllocationEncoder(8192);

    private static final ThreadLocal<CharsetEncoder> CHARSET_ENCODER_UTF8 = ThreadLocal.withInitial(() ->
            StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE));

    private static final ThreadLocal<CharsetDecoder> CHARSET_DECODER_UTF8 = ThreadLocal.withInitial(() ->
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE));

    private final ThreadLocal<ByteBuffer> bufferThreadLocal;

    private final ThreadLocal<AtomicReference<CharBuffer>> charBufferRefThreadLocal;

    private final ThreadLocal<AtomicReference<ByteBuffer>> byteBufferRefThreadLocal;

    public ZeroAllocationEncoder(int outBufferSize) {
        var maxBytesPerChar = (int) Math.ceil(StandardCharsets.UTF_8.newEncoder().maxBytesPerChar());
        bufferThreadLocal = ThreadLocal.withInitial(() -> ByteBuffer.allocate(outBufferSize * maxBytesPerChar));
        charBufferRefThreadLocal = ThreadLocal.withInitial(() -> new AtomicReference<>(CharBuffer.allocate(outBufferSize)));
        byteBufferRefThreadLocal = ThreadLocal.withInitial(() -> new AtomicReference<>(ByteBuffer.allocate(outBufferSize * maxBytesPerChar)));
    }

    public void encodeTo(String s, SafeDataOutput bufDataOutput) {
        var encoder = CHARSET_ENCODER_UTF8.get();
        encoder.reset();
        var buf = bufferThreadLocal.get();
        var charBuffer = CharBuffer.wrap(s);
        boolean endOfInput = false;
        CoderResult result;
        do {
            buf.clear();
            result = encoder.encode(charBuffer, buf, endOfInput);
            buf.flip();
            bufDataOutput.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
            if (result.isUnderflow()) {
                if (endOfInput) {
                    buf.clear();
                    encoder.flush(buf);
                    buf.flip();
                    bufDataOutput.write(buf.array(), buf.arrayOffset() + buf.position(), buf.remaining());
                    break;
                } else {
                    endOfInput = true;
                    continue;
                }
            } else if (result.isOverflow()) {
                continue;
            } else if (result.isError()) {
                try {
                    result.throwException();
                } catch (CharacterCodingException e) {
                    // This should not happen
                    throw new Error(e);
                }
            }
        } while (true);
    }

    public String decodeFrom(SafeDataInput bufDataInput, int bytesLength) {
        var decoder = CHARSET_DECODER_UTF8.get();
        decoder.reset();
        var bufRef = byteBufferRefThreadLocal.get();
        var charBufRef = charBufferRefThreadLocal.get();
        var buf = bufRef.get();
        var charBuf = charBufRef.get();
        assert decoder.maxCharsPerByte() == 1.0f
                : "UTF8 max chars per byte is 1.0f, but the decoder got a value of " + decoder.maxCharsPerByte();
        if (charBuf.capacity() < bytesLength) {
            charBuf = CharBuffer.allocate(bytesLength);
            charBufRef.set(charBuf);
        } else {
            charBuf.clear();
        }
        if (buf.capacity() < bytesLength) {
            buf = ByteBuffer.allocate(bytesLength);
            bufRef.set(buf);
        } else {
            buf.clear();
        }
        CoderResult result;
        do {
            buf.clear();
            assert buf.capacity() >= bytesLength;
            bufDataInput.readFully(buf, bytesLength);
            buf.flip();
            result = decoder.decode(buf, charBuf, true);
            if (result.isUnderflow()) {
                result = decoder.flush(charBuf);
                if (result.isOverflow()) {
                    throw new IllegalStateException("Unexpected overflow");
                }
                charBuf.flip();
                return charBuf.toString();
            } else if (result.isOverflow()) {
                throw new UnsupportedOperationException();
            } else if (result.isError()) {
                try {
                    result.throwException();
                } catch (CharacterCodingException e) {
                    // This should not happen
                    throw new Error(e);
                }
            }
        } while (true);
    }

    private CharBuffer getNextCharBuf(ArrayList<CharBuffer> charBufs, int charBufIndex) {
        if (charBufIndex == 0) return charBufs.getFirst();
        if (charBufIndex >= charBufs.size()) {
            var b = charBufs.getFirst().duplicate();
            charBufs.add(b);
            return b;
        } else {
            return charBufs.get(charBufIndex);
        }
    }
}
