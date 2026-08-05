package it.cavallium.datagen.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.cavallium.datagen.benchmark.fixture.BaseType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedBenchmarkShapeTest {

	@Test
	void generatedBoundReadersHaveDirectStorageKernelsAndBoundedMethods() throws Exception {
		var report = GeneratedCodeShapeReport.inspectAndVerify(
				Path.of("target/generated-sources/database-classes/java"));
		assertEquals(BaseType.values().length, report.boundClasses());
		assertEquals(BaseType.values().length * 2, report.readerClasses());
		assertTrue(report.sourceFiles() > 200);
		assertTrue(report.sourceBytes() > 500_000);
		assertTrue(report.maxMethodBytes() < 4_000);
		Path generated = Path.of("target/generated-sources/database-classes/java");
		String fixedRun = Files.readString(generated.resolve(
				"it/cavallium/datagen/benchmark/fixture/current/readers/FixedCustomRunReadPlan.java"));
		assertEquals(4, occurrences(fixedRun, ".reserve(24)"));
		assertEquals(8, occurrences(fixedRun, ".readReserved("));
		assertTrue(fixedRun.contains("getIntAt(fixedRun0 + 0)"));
		assertTrue(fixedRun.contains("getLongAt(fixedRun0 + 8)"));
		assertFalse(fixedRun.contains(".slice("));
		String fixedArray = Files.readString(generated.resolve(
				"it/cavallium/datagen/benchmark/fixture/current/serializers/ArrayFixedIntSerializer.java"));
		assertEquals(1, occurrences(fixedArray, "randomInput.reserve(bodyBytes)"));
		assertTrue(fixedArray.contains("bodyStart + i * 4, 4"));
	}

	@Test
	void jmhMetadataContainsTheFullGeneratedMatrix() throws Exception {
		String benchmarkList;
		try (var input = getClass().getClassLoader().getResourceAsStream("META-INF/BenchmarkList")) {
			assertTrue(input != null, "JMH annotation processor did not produce BenchmarkList");
			benchmarkList = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
		for (String method : List.of(
				"generatedBoundHistorical",
				"generatedMixedHistorical",
				"generatedBoundCurrent",
				"generatedOneShotBufDataInputHistorical",
				"generatedStreamBackedHistorical",
				"materializeHistoricalThenUpgrade",
				"generatedFinalTypeReadUpgrade",
				"generatedOpaqueObjectUpgrade",
				"generatedCurrentUpgradeShape",
				"generatedCurrentContextUpgrade",
				"generatedOpaqueContextUpgrade",
				"generatedPrimitiveArrayMatrix",
				"generatedPrimitiveDenseRecord",
				"generatedAdjacentFixedCustoms",
				"generatedFixedCustomArray",
				"generatedStringHeavyGraph",
				"generatedNullableHeavyGraph",
				"generatedTypedViewBranch",
				"generatedDeclarativeBuiltins",
				"generatedLargeUnion")) {
			assertTrue(benchmarkList.contains(" " + method + " "), method);
		}
		for (String method : List.of(
				"generatedBooleanArray",
				"generatedByteArray",
				"generatedShortArray",
				"generatedCharArray",
				"generatedIntArray",
				"generatedLongArray",
				"generatedFloatArray",
				"generatedDoubleArray",
				"generatedInt52Array")) {
			assertTrue(benchmarkList.contains(" " + method + " "), method);
		}
	}

	@Test
	void vectorLoweringIsPresentOnlyOnTheExplicitVectorClasspath() throws Exception {
		boolean vectorArtifactPresent = getClass().getClassLoader()
				.getResource("it/cavallium/datagen/vector/VectorArraySupport.class") != null;
		Path generated = Path.of("target/generated-sources/database-classes/java");
		List<Path> vectorSources;
		try (var files = Files.walk(generated)) {
			vectorSources = files.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> {
						try {
							return Files.readString(path).contains("it.cavallium.datagen.vector.VectorArraySupport");
						} catch (java.io.IOException failure) {
							throw new java.io.UncheckedIOException(failure);
						}
					})
					.toList();
		}
		assertEquals(vectorArtifactPresent, !vectorSources.isEmpty(), vectorSources::toString);
		if (vectorArtifactPresent) {
			String primitiveSerializer = Files.readString(generated.resolve(
					"it/cavallium/datagen/benchmark/fixture/current/serializers/PrimitiveArraysSerializer.java"));
			for (String method : List.of("readBooleanArray", "readByteArray", "readShortArray",
					"readCharArray", "readIntArray", "readLongArray", "readFloatArray",
					"readDoubleArray", "readInt52Array")) {
				assertTrue(primitiveSerializer.contains("VectorArraySupport." + method + "(in)"), method);
			}
			for (List<String> isolated : List.of(
					List.of("BooleanArrayCase", "readBooleanArray"),
					List.of("ByteArrayCase", "readByteArray"),
					List.of("ShortArrayCase", "readShortArray"),
					List.of("CharArrayCase", "readCharArray"),
					List.of("IntArrayCase", "readIntArray"),
					List.of("LongArrayCase", "readLongArray"),
					List.of("FloatArrayCase", "readFloatArray"),
					List.of("DoubleArrayCase", "readDoubleArray"),
					List.of("Int52ArrayCase", "readInt52Array"))) {
				String serializer = Files.readString(generated.resolve(
						"it/cavallium/datagen/benchmark/fixture/current/serializers/"
								+ isolated.get(0) + "Serializer.java"));
				assertTrue(serializer.contains("VectorArraySupport." + isolated.get(1) + "(in)"),
						isolated.get(0));
			}
		}
	}

	@Test
	void actualGeneratedReadersExecuteTheStorageAndFeatureSmokeMatrix() {
		var benchmark = new GeneratedNormalReaderBench();
		for (String storage : List.of("heap", "sliced-heap", "native", "fallback")) {
			var normal = new GeneratedNormalReaderBench.StateData();
			normal.storage = storage;
			normal.setup();
			try {
				assertNotNull(benchmark.generatedBoundHistorical(normal));
				assertNotNull(benchmark.generatedMixedHistorical(normal));
				assertNotNull(benchmark.generatedBoundCurrent(normal));
				assertNotNull(benchmark.generatedOneShotBufDataInputHistorical(normal));
				assertNotNull(benchmark.generatedStreamBackedHistorical(normal));
				assertNotNull(benchmark.materializeHistoricalThenUpgrade(normal));
				assertNotNull(benchmark.generatedFinalTypeReadUpgrade(normal));
				assertNotNull(benchmark.generatedOpaqueObjectUpgrade(normal));
				assertNotNull(benchmark.generatedCurrentUpgradeShape(normal));
				assertNotNull(benchmark.generatedCurrentContextUpgrade(normal));
				assertNotNull(benchmark.generatedOpaqueContextUpgrade(normal));
			} finally {
				normal.tearDown();
			}

			var features = new GeneratedNormalReaderBench.FeatureState();
			features.storage = storage;
			features.setup();
			try {
				assertNotNull(benchmark.generatedPrimitiveDenseRecord(features));
				assertNotNull(benchmark.generatedStringHeavyGraph(features));
				assertNotNull(benchmark.generatedNullableHeavyGraph(features));
				assertNotNull(benchmark.generatedTypedViewBranch(features));
				assertNotNull(benchmark.generatedDeclarativeBuiltins(features));
			} finally {
				features.tearDown();
			}

			for (int size : List.of(0, 1, 2, 8, 32, 256, 4096)) {
				var arrays = new GeneratedNormalReaderBench.PrimitiveArrayState();
				arrays.storage = storage;
				arrays.size = size;
				arrays.setup();
				try {
					assertNotNull(benchmark.generatedPrimitiveArrayMatrix(arrays));
				} finally {
					arrays.tearDown();
				}
			}

			for (int size : List.of(0, 1, 16, 256, 4096)) {
				var fixed = new GeneratedNormalReaderBench.FixedCustomState();
				fixed.storage = storage;
				fixed.size = size;
				fixed.setup();
				try {
					assertNotNull(benchmark.generatedAdjacentFixedCustoms(fixed));
					assertEquals(size, benchmark.generatedFixedCustomArray(fixed).valuesSize());
				} finally {
					fixed.tearDown();
				}
			}

			for (int variant : List.of(0, 7, 15)) {
				var union = new GeneratedNormalReaderBench.WideUnionState();
				union.storage = storage;
				union.variant = variant;
				union.setup();
				try {
					assertNotNull(benchmark.generatedLargeUnion(union));
				} finally {
					union.tearDown();
				}
			}
		}
	}

	@Test
	void isolatedPrimitiveArrayReadersExecuteThresholdBoundaries() {
		var benchmark = new GeneratedPrimitiveArrayThresholdBench();
		for (String storage : List.of("heap", "native")) {
			for (int size : List.of(0, 16, 32, 64, 128, 256, 4096)) {
				var arrays = new GeneratedPrimitiveArrayThresholdBench.ArrayState();
				arrays.storage = storage;
				arrays.size = size;
				arrays.setup();
				try {
					assertNotNull(benchmark.generatedBooleanArray(arrays));
					assertNotNull(benchmark.generatedByteArray(arrays));
					assertNotNull(benchmark.generatedShortArray(arrays));
					assertNotNull(benchmark.generatedCharArray(arrays));
					assertNotNull(benchmark.generatedIntArray(arrays));
					assertNotNull(benchmark.generatedLongArray(arrays));
					assertNotNull(benchmark.generatedFloatArray(arrays));
					assertNotNull(benchmark.generatedDoubleArray(arrays));
					assertNotNull(benchmark.generatedInt52Array(arrays));
				} finally {
					arrays.tearDown();
				}
			}
		}
	}

	private static int occurrences(String source, String fragment) {
		int count = 0;
		for (int offset = 0; (offset = source.indexOf(fragment, offset)) >= 0; offset += fragment.length()) {
			count++;
		}
		return count;
	}
}
