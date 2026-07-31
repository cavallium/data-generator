package it.cavallium.buffer;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Allocation floor for the cursor pattern emitted by generated projection readers.
 * Run with {@code -prof gc}; {@link #readIntoPrimitiveSink(StateData)} targets 0 B/op,
 * while {@link #readResult(StateData)} intentionally allocates one result record.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, warmups = 1)
@Warmup(time = 2, iterations = 5)
@Measurement(time = 2, iterations = 5)
public class ProjectionReaderBench {

	@State(Scope.Thread)
	public static class StateData {

		private Buf source;
		private Reader reader;
		private PrimitiveSink sink;

		@Setup
		public void setup() {
			BufDataOutput output = BufDataOutput.create(32);
			output.writeInt(0x11223344);
			output.writeLong(42L);
			output.writeLong(77L);
			output.writeLong(99L);
			source = output.asList();
			reader = new Reader();
			sink = new PrimitiveSink();
		}
	}

	@Benchmark
	public long readIntoPrimitiveSink(StateData state) {
		state.reader.readInto(state.source, state.sink);
		return state.sink.messageId ^ state.sink.senderId ^ state.sink.chatId;
	}

	@Benchmark
	public Result readResult(StateData state) {
		return state.reader.read(state.source);
	}

	private record Result(long messageId, long senderId, long chatId) {}

	private static final class PrimitiveSink {

		private long messageId;
		private long senderId;
		private long chatId;

		private void accept(long messageId, long senderId, long chatId) {
			this.messageId = messageId;
			this.senderId = senderId;
			this.chatId = chatId;
		}
	}

	private static final class Reader {

		private final BufDataCursor cursor = new BufDataCursor();
		private long messageId;
		private long senderId;
		private long chatId;

		private Result read(Buf source) {
			readValues(source);
			return new Result(messageId, senderId, chatId);
		}

		private void readInto(Buf source, PrimitiveSink sink) {
			readValues(source);
			sink.accept(messageId, senderId, chatId);
		}

		private void readValues(Buf source) {
			cursor.bind(source, 0, source.size());
			try {
				cursor.skipBytes(Integer.BYTES);
				messageId = cursor.readLong();
				senderId = cursor.readLong();
				chatId = cursor.readLong();
			} finally {
				cursor.unbind();
			}
		}
	}
}
