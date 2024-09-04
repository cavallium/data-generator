package it.cavallium.datagen.nativedata;

import it.cavallium.datagen.TypedNullable;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestTypedNullable {
    @Test
    public void testNullableOr() {
        class TypeA {

        }

        class TypeAB extends TypeA {

        }

        var a = new TypedNullable<TypeA>() {

            @Override
            public @Nullable TypeA getNullable() {
                return new TypeA();
            }
        };

        var aNull = new TypedNullable<TypeA>() {

            @Override
            public @Nullable TypeA getNullable() {
                return null;
            }
        };

        var ab = new TypedNullable<TypeAB>() {

            @Override
            public @Nullable TypeAB getNullable() {
                return new TypeAB();
            }
        };

        var abNull = new TypedNullable<TypeAB>() {

            @Override
            public @Nullable TypeAB getNullable() {
                return null;
            }
        };

        Assertions.assertDoesNotThrow(() -> {
            a.or(ab).getNullable();
        });

        Assertions.assertDoesNotThrow(() -> {
            aNull.or(ab).getNullable();
        });

        Assertions.assertDoesNotThrow(() -> {
            a.or(abNull).getNullable();
        });

        Assertions.assertDoesNotThrow(() -> {
            aNull.or(abNull).getNullable();
        });

        Assertions.assertDoesNotThrow(() -> {
            aNull.or(abNull).orElse(new TypeA());
        });

        Assertions.assertDoesNotThrow(() -> {
            aNull.or(ab).orElse(new TypeA());
        });

        Assertions.assertDoesNotThrow(() -> {
            aNull.or(ab).orElse(new TypeAB());
        });

        Assertions.assertDoesNotThrow(() -> {
            aNull.or(abNull).orElse(new TypeAB());
        });

        Assertions.assertDoesNotThrow(() -> {
            abNull.or(abNull).orElse(new TypeAB());
        });
    }
}
