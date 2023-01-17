package it.cavallium.data.generator.nativedata;

import it.cavallium.data.generator.DataSerializer;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import org.jetbrains.annotations.NotNull;

public class StringSerializer implements DataSerializer<String> {

	public static final StringSerializer INSTANCE = new StringSerializer();
	private static final ThreadLocal<CharsetEncoder> UTF8_ENCODER = ThreadLocal.withInitial(() -> StandardCharsets.UTF_8
			.newEncoder()
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.onMalformedInput(CodingErrorAction.REPORT)
	);
	private static final ThreadLocal<CharsetDecoder> UTF8_DECODER = ThreadLocal.withInitial(() -> StandardCharsets.UTF_8
			.newDecoder()
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.onMalformedInput(CodingErrorAction.REPORT)
	);

	@Override
	public void serialize(DataOutput dataOutput, @NotNull String data) throws IOException {
		try {
			var bytes = UTF8_ENCODER.get().reset().encode(CharBuffer.wrap(data));

			dataOutput.writeInt(bytes.limit());
			if (bytes.hasArray()) {
				dataOutput.write(bytes.array(), bytes.arrayOffset(), bytes.limit());
			} else {
				while (bytes.hasRemaining()) {
					dataOutput.writeByte(bytes.get());
				}
			}
		} catch (IllegalStateException | CharacterCodingException ex) {
			throw new IOException("Can't encode this UTF-8 string", ex);
		}
	}

	@NotNull
	@Override
	public String deserialize(DataInput dataInput) throws IOException {
		byte[] bytes = new byte[dataInput.readInt()];
		dataInput.readFully(bytes);
		try {
			CharBuffer decoded = UTF8_DECODER.get().reset().decode(ByteBuffer.wrap(bytes));
			return decoded.toString();
		} catch (IllegalStateException | CharacterCodingException ex) {
			throw new IOException("Can't decode this UTF-8 string", ex);
		}
	}
}
