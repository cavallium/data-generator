package it.cavallium.datagen.plugin.classgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import it.cavallium.datagen.plugin.ClassConfiguration;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.DataModel;
import it.cavallium.datagen.plugin.SourcesGeneratorConfiguration;
import it.cavallium.datagen.plugin.VersionConfiguration;
import it.cavallium.datagen.plugin.WireLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Direct fuzzing for nullable JavaPoet lowering shared by serializers, projections, and read plans. */
class NullableWireEmitterDeepFuzzTest {

	private static final long EMITTER_SEED = 0x79B3_0E51_C8A6_24DFL;
	private static final int EMITTER_CASES = 50_000;

	@Test
	void everyNullableLayoutEmitsStablePresenceValueSkipAndRegionCodeForHostileNames() {
		for (boolean binaryStrings : List.of(false, true)) {
			DataModel model = model(binaryStrings);
			Map<String, it.cavallium.datagen.plugin.ComputedType> types =
					model.getComputedTypes(model.getCurrentVersion());
			var random = new Random(EMITTER_SEED ^ (binaryStrings ? Long.MIN_VALUE : 0));
			for (int caseIndex = 0; caseIndex < EMITTER_CASES; caseIndex++) {
				String suffix = Integer.toUnsignedString(random.nextInt(), 36);
				String present = "present" + suffix;
				String firstByte = "firstByte" + suffix;
				String start = "start" + suffix;
				String length = "length" + suffix;
				String valueStart = "valueStart" + suffix;
				String valueLength = "valueLength" + suffix;
				for (String typeName : List.of("-int", "-String", "-Int52")) {
					ComputedTypeNullable nullable = (ComputedTypeNullable) types.get(typeName);
					WireLayout layout = WireLayout.of(nullable);
					String diagnostic = "seed=" + EMITTER_SEED + ", case=" + caseIndex
							+ ", type=" + typeName + ", binary=" + binaryStrings;

					MethodSpec.Builder presenceMethod = MethodSpec.methodBuilder("presence" + suffix);
					NullableWireEmitter.emitPresence(presenceMethod, nullable, CodeBlock.of("input"),
							present, firstByte);
					String presenceSource = presenceMethod.build().toString();
					assertPresence(layout, presenceSource, present, firstByte, diagnostic);

					CodeBlock ordinaryValue = CodeBlock.of("ordinaryValue$L", caseIndex);
					String valueSource = NullableWireEmitter.valueExpression(nullable, binaryStrings,
							CodeBlock.of("input"), firstByte, ordinaryValue).toString();
					switch (layout) {
						case BOOLEAN_TAGGED -> assertEquals(ordinaryValue.toString(), valueSource, diagnostic);
						case BOOLEAN_TAGGED_SHORT_STRING -> assertTrue(binaryStrings
								? valueSource.contains("BinaryStringSerializer.readShort(input)")
								: valueSource.contains("input.readShortText(")
										&& valueSource.contains("StandardCharsets.UTF_8)"),
								diagnostic + ": " + valueSource);
						case INT52_HIGH_BIT_SENTINEL -> {
							assertTrue(valueSource.contains("Int52Serializer.readValue"), diagnostic);
							assertTrue(valueSource.contains(firstByte), diagnostic);
						}
					}

					CodeBlock ordinarySkip = CodeBlock.of("input.skipExact($L)", 4 + caseIndex % 8);
					MethodSpec.Builder skipMethod = MethodSpec.methodBuilder("skip" + suffix);
					NullableWireEmitter.emitSkip(skipMethod, nullable, CodeBlock.of("input"), present,
							firstByte, ordinarySkip);
					String skipSource = skipMethod.build().toString();
					assertPresence(layout, skipSource, present, firstByte, diagnostic);
					assertTrue(skipSource.contains("if (" + present + ")"), diagnostic);
					assertSkip(layout, skipSource, ordinarySkip.toString(), diagnostic);

					MethodSpec.Builder presenceOnlyMethod = MethodSpec.methodBuilder("presenceOnly" + suffix);
					NullableWireEmitter.emitPresenceOnly(presenceOnlyMethod, nullable, CodeBlock.of("input"),
							present, firstByte, ordinarySkip);
					assertEquals(skipSource.replace("skip" + suffix, "presenceOnly" + suffix),
							presenceOnlyMethod.build().toString(), diagnostic);

					MethodSpec.Builder captured = MethodSpec.methodBuilder("captured" + suffix);
					NullableWireEmitter.emitCapturedRegion(captured, nullable, "input", start, length,
							present, firstByte, ordinarySkip);
					String capturedSource = captured.build().toString();
					assertTrue(capturedSource.contains("final int " + start + " = input.position()"), diagnostic);
					assertTrue(capturedSource.contains("final int " + length + " = input.position() - " + start),
							diagnostic);
					assertSkip(layout, capturedSource, ordinarySkip.toString(), diagnostic);

					MethodSpec.Builder valueRegion = MethodSpec.methodBuilder("valueRegion" + suffix);
					NullableWireEmitter.emitValueRegion(valueRegion, nullable, "input",
							CodeBlock.of("absoluteStart"), valueStart, valueLength, present, firstByte,
							ordinarySkip);
					String regionSource = valueRegion.build().toString();
					assertTrue(regionSource.contains(present), diagnostic);
					assertTrue(regionSource.contains(valueStart), diagnostic);
					assertTrue(regionSource.contains(valueLength), diagnostic);
					if (layout == WireLayout.INT52_HIGH_BIT_SENTINEL) {
						assertTrue(regionSource.contains("input.position() - 1"), diagnostic);
						assertTrue(regionSource.contains("skipBytes(input, 6)"), diagnostic);
					} else {
						assertTrue(regionSource.contains("absoluteStart + input.position()"), diagnostic);
						assertSkip(layout, regionSource, ordinarySkip.toString(), diagnostic);
					}
				}
			}
		}
	}

	@Test
	void readPlanClassNamesAndIrRecordsAreExactAndDefensivelyImmutable() {
		assertEquals("org.example.current.readers.RootReadPlan",
				GenReadPlan.className("ignored", "org.example.current", "Root").reflectionName());

		var shape = new ReadPlanCompiler.NativeShape("int", "int", 4, 4);
		var fields = new java.util.ArrayList<ReadPlanCompiler.ValueShape>();
		fields.add(shape);
		var record = new ReadPlanCompiler.RecordShape("R", fields, 4);
		fields.clear();
		assertEquals(1, record.fields().size());

		var expressions = new java.util.ArrayList<ReadPlanCompiler.Expression>();
		expressions.add(new ReadPlanCompiler.Source(0, shape));
		var construct = new ReadPlanCompiler.Construct("R", expressions, record);
		expressions.clear();
		assertEquals(1, construct.fields().size());

		var fixedFields = new java.util.ArrayList<ReadPlanCompiler.FixedField>();
		fixedFields.add(new ReadPlanCompiler.FixedField(0, 0, 4, shape, ReadPlanCompiler.FieldUse.READ));
		var block = new ReadPlanCompiler.FixedBlock(4, fixedFields);
		fixedFields.clear();
		assertEquals(1, block.fields().size());

		var scan = new java.util.ArrayList<ReadPlanCompiler.ScanOperation>();
		scan.add(block);
		var plan = new ReadPlanCompiler.Plan(construct, scan);
		scan.clear();
		assertEquals(1, plan.scan().size());
		assertSame(construct, plan.construction());
	}

	private static void assertPresence(WireLayout layout,
			String source,
			String present,
			String firstByte,
			String diagnostic) {
		switch (layout) {
			case BOOLEAN_TAGGED, BOOLEAN_TAGGED_SHORT_STRING -> {
				assertTrue(source.contains("final boolean " + present + " = input.readBoolean()"), diagnostic);
				assertTrue(!source.contains(firstByte), diagnostic);
			}
			case INT52_HIGH_BIT_SENTINEL -> {
				assertTrue(source.contains("final int " + firstByte + " = input.readUnsignedByte()"), diagnostic);
				assertTrue(source.contains("final boolean " + present + " = (" + firstByte + " & 0x80) == 0"),
						diagnostic);
			}
		}
	}

	private static void assertSkip(WireLayout layout,
			String source,
			String ordinary,
			String diagnostic) {
		switch (layout) {
			case BOOLEAN_TAGGED -> assertTrue(source.contains(ordinary), diagnostic + ": " + source);
			case BOOLEAN_TAGGED_SHORT_STRING -> {
				assertTrue(source.contains("skipPayload(input, input.readUnsignedShort())"), diagnostic);
				assertTrue(!source.contains(ordinary), diagnostic);
			}
			case INT52_HIGH_BIT_SENTINEL -> {
				assertTrue(source.contains("skipBytes(input, 6)"), diagnostic);
				assertTrue(!source.contains(ordinary), diagnostic);
			}
		}
	}

	private static DataModel model(boolean binaryStrings) {
		var configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v1";
		var root = new ClassConfiguration();
		root.data = new LinkedHashMap<>();
		root.data.put("maybeInt", "-int");
		root.data.put("maybeText", "-String");
		root.data.put("maybePacked", "-Int52");
		configuration.baseTypesData = Map.of("Root", root);
		configuration.interfacesData = Map.of();
		configuration.superTypesData = Map.of();
		configuration.customTypesData = Map.of();
		configuration.projectionsData = Map.of();
		configuration.versions = Map.of("v1", new VersionConfiguration());
		return configuration.buildDataModel(binaryStrings);
	}
}
