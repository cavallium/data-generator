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

    public ZeroAllocationEncoder(int outBufferSize) {
        bufferThreadLocal = ThreadLocal.withInitial(() -> ByteBuffer.allocate(outBufferSize));
        charBufferRefThreadLocal = ThreadLocal.withInitial(() -> new AtomicReference<>(CharBuffer.allocate(outBufferSize)));
    }

    public void encodeTo(String s, SafeDataOutput bufDataOutput) {
        var encoder = CHARSET_ENCODER_UTF8.get();
        var buf = bufferThreadLocal.get();
        var charBuffer = CharBuffer.wrap(s);
        CoderResult result;
        do {
            buf.clear();
            result = encoder.encode(charBuffer, buf, true);
            buf.flip();
            var bufArray = buf.array();
            var bufArrayOffset = buf.arrayOffset();
            bufDataOutput.write(bufArray, bufArrayOffset + buf.position(), buf.remaining());
            if (result.isUnderflow()) {
                break;
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

    public String decodeFrom(SafeDataInput bufDataInput, int length) {
        var decoder = CHARSET_DECODER_UTF8.get();
        var byteBuf = bufferThreadLocal.get();
        var charBufRef = charBufferRefThreadLocal.get();
        var charBuf = charBufRef.get();
        if (charBuf.capacity() < length) {
            charBuf = CharBuffer.allocate(length);
            charBufRef.set(charBuf);
        } else {
            charBuf.clear();
        }
        var remainingLengthToRead = length;
        CoderResult result;
        do {
            byteBuf.clear();
            bufDataInput.readFully(byteBuf, Math.min(remainingLengthToRead, byteBuf.limit()));
            byteBuf.flip();
            remainingLengthToRead -= byteBuf.remaining();
            result = decoder.decode(byteBuf, charBuf, true);
            if (result.isUnderflow()) {
                if (remainingLengthToRead > 0) {
                    continue;
                } else {
                    charBuf.flip();
                    return charBuf.toString();
                }
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
