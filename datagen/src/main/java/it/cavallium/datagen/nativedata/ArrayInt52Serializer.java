package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.buffer.RandomAccessDataInput;
import it.cavallium.stream.SafeDataInput;
import it.cavallium.stream.SafeDataOutput;
import org.jetbrains.annotations.NotNull;

public class ArrayInt52Serializer implements DataCodec<Int52[]> {

	private static final Int52[] EMPTY = new Int52[0];
	public static Int52[] emptyArray() { return EMPTY; }

	@Override
	public void serialize(SafeDataOutput dataOutput, Int52[] data) {
		dataOutput.writeInt(data.length);
		for (Int52 item : data) {
			Int52Serializer.INSTANCE.serialize(dataOutput, item);
		}
	}

	@NotNull
	@Override
	public Int52[] read(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			int size = ProjectionReadSupport.readLength(dataInput);
			if (size == 0) return EMPTY;
			int bodyBytes = ProjectionReadSupport.checkedArrayBytes(size, 7);
			if (dataInput instanceof RandomAccessDataInput randomInput) {
				int start = randomInput.reserve(bodyBytes);
				dataInput.decodeBudget().claimArrayElements(size);
				var data = new Int52[size];
				for (int i = 0; i < size; i++) {
					data[i] = Int52.fromLong(randomInput.getInt52At(start + i * 7));
				}
				return data;
			}
			ProjectionReadSupport.prepareArrayAllocation(dataInput, size, 7);
			var data = new Int52[size];
			for (int i = 0; i < data.length; i++) {
				data[i] = Int52Serializer.INSTANCE.read(dataInput);
			}
			return data;
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}

	@Override
	public void skip(SafeDataInput dataInput) {
		dataInput.decodeBudget().enterStructure();
		try {
			ProjectionReadSupport.skipFixedArray(dataInput, 7);
		} finally {
			dataInput.decodeBudget().exitStructure();
		}
	}
}
