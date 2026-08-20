package it.cavallium.datagen.plugin;

import it.cavallium.datagen.plugin.ComputedType.VersionedComputedType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Map;

/** Produces a deterministic fingerprint for every numbered wire version in a data model. */
final class WireSchemaLedger {

	private static final String HEADER = "datagen-wire-schema-ledger-v1";

	private WireSchemaLedger() {}

	static String render(DataModel model, boolean binaryStrings) {
		var result = new StringBuilder(HEADER)
				.append('\n')
				.append("binary-strings=").append(binaryStrings).append('\n');
		model.getVersionsSet().stream().sorted().forEach(version -> {
			String canonical = canonicalVersion(model, version);
			result.append("version=").append(version.getVersion())
					.append(" name=").append(version.getName())
					.append(" sha256=").append(sha256(canonical))
					.append('\n');
		});
		return result.toString();
	}

	private static String canonicalVersion(DataModel model, ComputedVersion version) {
		var definitions = new ArrayList<String>();
		model.getComputedTypes(version).entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> definitions.add(entry.getKey() + "=" + definition(entry.getValue())));
		return String.join("\n", definitions);
	}

	private static String definition(ComputedType type) {
		return switch (type) {
			case ComputedTypeBase base -> "record(" + nullSafe(base.getStringRepresenter()) + "){"
					+ base.getData().entrySet().stream()
					.map(entry -> entry.getKey() + ":" + typeKey(entry.getValue()))
					.reduce((left, right) -> left + "," + right).orElse("") + "}";
			case ComputedTypeSuper union -> "union[" + union.subTypes().stream()
					.map(WireSchemaLedger::typeKey)
					.reduce((left, right) -> left + "," + right).orElse("") + "]";
			case ComputedTypeArray array -> "array(" + typeKey(array.getBase()) + ")";
			case ComputedTypeNullable nullable -> "nullable(" + typeKey(nullable.getBase()) + ")";
			case ComputedTypeCustom custom -> "custom(" + custom.getJavaClass() + ","
					+ custom.getCodec() + "," + custom.getFixedSize() + ")";
			case ComputedTypeNative nativeType -> "native(" + nativeType.getName() + ")";
		};
	}

	private static String typeKey(ComputedType type) {
		int version = type instanceof VersionedComputedType versioned ? versioned.getVersion().getVersion() : -1;
		return type.getClass().getSimpleName() + ":" + type.getName() + "@" + version;
	}

	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}
}
