package it.cavallium.datagen.plugin;

/** The three historical nullable wire layouts. */
public enum WireLayout {
	BOOLEAN_TAGGED,
	BOOLEAN_TAGGED_SHORT_STRING,
	INT52_HIGH_BIT_SENTINEL;

	public static WireLayout of(ComputedTypeNullable nullable) {
		if (nullable.getBase() instanceof ComputedTypeNative nativeType) {
			return switch (nativeType.getName()) {
				case "String" -> BOOLEAN_TAGGED_SHORT_STRING;
				case "Int52" -> INT52_HIGH_BIT_SENTINEL;
				default -> BOOLEAN_TAGGED;
			};
		}
		return BOOLEAN_TAGGED;
	}
}
