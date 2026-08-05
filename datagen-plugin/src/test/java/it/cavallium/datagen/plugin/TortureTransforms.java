package it.cavallium.datagen.plugin;

/** Direct-call targets shared by the adversarial object and fused read paths. */
public final class TortureTransforms {

	private TortureTransforms() {}

	public static long widen(int value) {
		return value + 1_000L;
	}

	public static long widenWithAnchor(int value, long anchor) {
		return value + anchor + 2_000L;
	}

	public static long initialize(long anchor) {
		return anchor + 3_000L;
	}
}
