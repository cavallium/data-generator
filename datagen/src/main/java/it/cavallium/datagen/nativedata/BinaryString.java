package it.cavallium.datagen.nativedata;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public record BinaryString(byte[] data) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BinaryString that = (BinaryString) o;
        return Objects.deepEquals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return new String(data, StandardCharsets.UTF_8);
    }

    public int sizeBytes() {
        return data.length;
    }
}
