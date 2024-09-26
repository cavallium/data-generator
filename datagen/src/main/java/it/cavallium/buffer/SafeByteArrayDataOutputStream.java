package it.cavallium.buffer;

import it.cavallium.stream.SafeByteArrayOutputStream;
import it.cavallium.stream.SafeDataOutputStream;

public class SafeByteArrayDataOutputStream extends SafeDataOutputStream {
    private final SafeByteArrayOutputStream bOut;

    public SafeByteArrayDataOutputStream(SafeByteArrayOutputStream out) {
        super(out);
        this.bOut = out;
    }

    public void resetUnderlyingBuffer() {
        bOut.reset();
        this.written = 0;
    }

    public void rewindPosition(int count) {
        var currentPosition = bOut.position();
        if (count > written) {
            throw new IndexOutOfBoundsException(count + " > " + written);
        }
        bOut.position(currentPosition - count);
        decCount(count);
    }

    public void advancePosition(int count) {
        bOut.ensureWritable(count);
        bOut.position(bOut.position() + count);
        incCount(count);
    }

    public long position() {
        return bOut.position();
    }
}
