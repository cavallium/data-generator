package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.jetbrains.annotations.NotNull;

public class Int52Serializer implements DataSerializer<Int52> {

	public static final Int52Serializer INSTANCE = new Int52Serializer();

	@Override
	public void serialize(DataOutput dataOutput, @NotNull Int52 data) throws IOException {
		dataOutput.write(toByteArray(data.getValue()));
	}

	@NotNull
	@Override
	public Int52 deserialize(DataInput dataInput) throws IOException {
		byte[] bytes = new byte[7];
		dataInput.readFully(bytes);
		return Int52.fromLong(fromByteArray(bytes));
	}

	public static byte[] toByteArray(long value) {
		byte[] result = new byte[7];

		for(int i = 6; i >= 0; --i) {
			result[i] = (byte)((int)(value & (i == 6 ? 0b00001111L : 255L)));
			value >>= 8;
		}

		return result;
	}

	public static long fromByteArray(byte[] bytes) {
		if (bytes.length != 8) {
			throw new IllegalArgumentException("Size must be 7, got " + bytes.length + " instead");
		}
		return fromBytes((byte) (bytes[0] & 0b00001111), bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6]);
	}

	public static long fromBytes(byte b1, byte b2, byte b3, byte b4, byte b5, byte b6, byte b7) {
		return ((long)b1 & 0b00001111) << 48 | ((long)b2 & 255L) << 40 | ((long)b3 & 255L) << 32 | ((long)b4 & 255L) << 24 | ((long)b5 & 255L) << 16 | ((long)b6 & 255L) << 8 | (long)b7 & 255L;
	}
}
