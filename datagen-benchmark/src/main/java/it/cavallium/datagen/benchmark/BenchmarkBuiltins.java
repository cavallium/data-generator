package it.cavallium.datagen.benchmark;

/** Direct-call targets used by the declarative transform benchmark. */
public final class BenchmarkBuiltins {

	private BenchmarkBuiltins() {}

	public static long combine(int value, int context) {
		return value + (long) context;
	}

	public static long widen(int value) {
		return value * 2L;
	}
}
