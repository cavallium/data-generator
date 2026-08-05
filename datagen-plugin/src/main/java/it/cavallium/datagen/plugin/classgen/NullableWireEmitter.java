package it.cavallium.datagen.plugin.classgen;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import it.cavallium.datagen.ProjectionReadSupport;
import it.cavallium.datagen.nativedata.BinaryStringSerializer;
import it.cavallium.datagen.nativedata.Int52Serializer;
import it.cavallium.datagen.plugin.ComputedTypeNullable;
import it.cavallium.datagen.plugin.WireLayout;
import java.nio.charset.StandardCharsets;

/** Shared JavaPoet lowering for every nullable framing operation. */
final class NullableWireEmitter {

	private NullableWireEmitter() {}

	static void emitPresence(MethodSpec.Builder method,
			ComputedTypeNullable nullable,
			CodeBlock input,
			String presentName,
			String firstByteName) {
		switch (WireLayout.of(nullable)) {
			case BOOLEAN_TAGGED, BOOLEAN_TAGGED_SHORT_STRING ->
					method.addStatement("final boolean $N = $L.readBoolean()", presentName, input);
			case INT52_HIGH_BIT_SENTINEL -> method
					.addStatement("final int $N = $L.readUnsignedByte()", firstByteName, input)
					.addStatement("final boolean $N = ($N & 0x80) == 0", presentName, firstByteName);
		}
	}

	static CodeBlock valueExpression(ComputedTypeNullable nullable,
			boolean binaryStrings,
			CodeBlock input,
			String firstByteName,
			CodeBlock ordinaryValue) {
		return switch (WireLayout.of(nullable)) {
			case BOOLEAN_TAGGED -> ordinaryValue;
			case BOOLEAN_TAGGED_SHORT_STRING -> binaryStrings
					? CodeBlock.of("$T.readShort($L)", BinaryStringSerializer.class, input)
					: CodeBlock.of("$L.readShortText($T.UTF_8)", input, StandardCharsets.class);
			case INT52_HIGH_BIT_SENTINEL ->
					CodeBlock.of("$T.readValue($N, $L)", Int52Serializer.class, firstByteName, input);
		};
	}

	static void emitSkip(MethodSpec.Builder method,
			ComputedTypeNullable nullable,
			CodeBlock input,
			String presentName,
			String firstByteName,
			CodeBlock ordinarySkipStatement) {
		emitPresence(method, nullable, input, presentName, firstByteName);
		method.beginControlFlow("if ($N)", presentName);
		switch (WireLayout.of(nullable)) {
			case BOOLEAN_TAGGED -> method.addStatement("$L", ordinarySkipStatement);
			case BOOLEAN_TAGGED_SHORT_STRING -> method.addStatement("$T.skipPayload($L, $L.readUnsignedShort())",
					ProjectionReadSupport.class, input, input);
			case INT52_HIGH_BIT_SENTINEL -> method.addStatement("$T.skipBytes($L, 6)",
					ProjectionReadSupport.class, input);
		}
		method.endControlFlow();
	}

	static void emitPresenceOnly(MethodSpec.Builder method,
			ComputedTypeNullable nullable,
			CodeBlock input,
			String presentName,
			String firstByteName,
			CodeBlock ordinarySkipStatement) {
		emitSkip(method, nullable, input, presentName, firstByteName, ordinarySkipStatement);
	}

	static void emitCapturedRegion(MethodSpec.Builder method,
			ComputedTypeNullable nullable,
			String inputName,
			String startName,
			String lengthName,
			String presentName,
			String firstByteName,
			CodeBlock ordinarySkipStatement) {
		method.addStatement("final int $N = $N.position()", startName, inputName);
		emitSkip(method, nullable, CodeBlock.of("$N", inputName), presentName, firstByteName,
				ordinarySkipStatement);
		method.addStatement("final int $N = $N.position() - $N", lengthName, inputName, startName);
	}

	/** Captures only the present payload region, retaining the Int52 sentinel's first value byte. */
	static void emitValueRegion(MethodSpec.Builder method,
			ComputedTypeNullable nullable,
			String inputName,
			CodeBlock absoluteRegionStart,
			String valueStartName,
			String valueLengthName,
			String presentName,
			String firstByteName,
			CodeBlock ordinarySkipStatement) {
		switch (WireLayout.of(nullable)) {
			case BOOLEAN_TAGGED, BOOLEAN_TAGGED_SHORT_STRING ->
					method.addStatement("$N = $N.readBoolean()", presentName, inputName);
			case INT52_HIGH_BIT_SENTINEL -> method
					.addStatement("final int $N = $N.readUnsignedByte()", firstByteName, inputName)
					.addStatement("$N = ($N & 0x80) == 0", presentName, firstByteName);
		}
		method.beginControlFlow("if ($N)", presentName);
		if (WireLayout.of(nullable) == WireLayout.INT52_HIGH_BIT_SENTINEL) {
			method.addStatement("$N = $L + $N.position() - 1", valueStartName, absoluteRegionStart, inputName)
					.addStatement("$T.skipBytes($N, 6)", ProjectionReadSupport.class, inputName);
		} else {
			method.addStatement("$N = $L + $N.position()", valueStartName, absoluteRegionStart, inputName);
			switch (WireLayout.of(nullable)) {
				case BOOLEAN_TAGGED -> method.addStatement("$L", ordinarySkipStatement);
				case BOOLEAN_TAGGED_SHORT_STRING -> method.addStatement(
						"$T.skipPayload($N, $N.readUnsignedShort())", ProjectionReadSupport.class,
						inputName, inputName);
				case INT52_HIGH_BIT_SENTINEL -> throw new AssertionError();
			}
		}
		method.addStatement("$N = ($L + $N.position()) - $N", valueLengthName, absoluteRegionStart,
				inputName, valueStartName)
				.endControlFlow();
	}
}
