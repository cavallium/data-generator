package it.cavallium.datagen.plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Produces a deterministic fingerprint for every numbered wire version in a data model. */
final class WireSchemaLedger {

	private static final String HEADER_V1 = "datagen-wire-schema-ledger-v1";
	private static final String HEADER_V2 = "datagen-wire-schema-ledger-v2";

	private WireSchemaLedger() {}

	static String render(DataModel model, boolean binaryStrings) {
		return render(model, binaryStrings, model.getCurrentVersionNumber(), Map.of());
	}

	static String render(DataModel model, boolean binaryStrings, int baselineVersion) {
		return render(model, binaryStrings, baselineVersion, Map.of());
	}

	static String render(DataModel model, boolean binaryStrings, String previousLedger) {
		return render(model, binaryStrings, baselineVersion(previousLedger), frozenEntries(previousLedger));
	}

	private static String render(DataModel model,
			boolean binaryStrings,
			int baselineVersion,
			Map<Integer, FrozenEntry> frozenEntries) {
		if (baselineVersion < 0 || baselineVersion > model.getCurrentVersionNumber()) {
			throw new IllegalArgumentException("Invalid wire-schema baseline version: " + baselineVersion);
		}
		var result = new StringBuilder(HEADER_V2)
				.append('\n')
				.append("binary-strings=").append(binaryStrings).append('\n')
				.append("baseline-version=").append(baselineVersion).append('\n');
		model.getVersionsSet().stream().sorted().forEach(version -> {
			String schemaHash = sha256(canonicalVersion(model, version));
			FrozenEntry frozen = frozenEntries.get(version.getVersion());
			String identityHash = frozen != null && frozen.name().equals(version.getName())
					? frozen.sha256()
					: schemaHash;
			result.append("version=").append(version.getVersion())
					.append(" name=").append(version.getName())
					.append(" sha256=").append(identityHash)
					.append(" schema-sha256=").append(schemaHash)
					.append('\n');
		});
		return result.toString();
	}

	static int baselineVersion(String ledger) {
		validateHeader(ledger);
		for (String line : ledger.lines().toList()) {
			if (line.startsWith("baseline-version=")) {
				return parseNonNegativeInt(line.substring("baseline-version=".length()), "baseline version");
			}
		}
		return ledger.lines()
				.filter(line -> line.startsWith("version="))
				.map(line -> line.substring("version=".length(), line.indexOf(' ', "version=".length())))
				.mapToInt(value -> parseNonNegativeInt(value, "version"))
				.max()
				.orElseThrow(() -> new IllegalArgumentException("Wire-schema ledger has no numbered versions"));
	}

	private static void validateHeader(String ledger) {
		String header = ledger.lines().findFirst().orElse("");
		if (!header.equals(HEADER_V1) && !header.equals(HEADER_V2)) {
			throw new IllegalArgumentException("Unsupported wire-schema ledger header: " + header);
		}
	}

	private static Map<Integer, FrozenEntry> frozenEntries(String ledger) {
		var result = new HashMap<Integer, FrozenEntry>();
		for (String line : ledger.lines().toList()) {
			if (!line.startsWith("version=")) continue;
			String[] fields = line.split(" ");
			if (fields.length < 3) {
				throw new IllegalArgumentException("Malformed wire-schema ledger line: " + line);
			}
			int version = parseFieldInt(fields[0], "version", line);
			String name = parseField(fields[1], "name", line);
			String sha256 = parseField(fields[2], "sha256", line);
			if (!sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Invalid wire-schema sha256 in line: " + line);
			}
			FrozenEntry previous = result.putIfAbsent(version, new FrozenEntry(name, sha256));
			if (previous != null) {
				throw new IllegalArgumentException("Duplicate wire-schema version " + version);
			}
		}
		if (result.isEmpty()) {
			throw new IllegalArgumentException("Wire-schema ledger has no numbered versions");
		}
		return result;
	}

	private static int parseFieldInt(String field, String name, String line) {
		return parseNonNegativeInt(parseField(field, name, line), name);
	}

	private static String parseField(String field, String name, String line) {
		String prefix = name + "=";
		if (!field.startsWith(prefix) || field.length() == prefix.length()) {
			throw new IllegalArgumentException("Malformed wire-schema " + name + " in line: " + line);
		}
		return field.substring(prefix.length());
	}

	private static int parseNonNegativeInt(String value, String label) {
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < 0) throw new NumberFormatException("negative");
			return parsed;
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Invalid wire-schema " + label + ": " + value, ex);
		}
	}

	static String canonicalVersion(DataModel model, ComputedVersion version) {
		var reachable = new HashMap<String, ComputedType>();
		var pending = new ArrayDeque<ComputedType>();
		model.getComputedTypes(version).values().stream()
				.filter(type -> type instanceof ComputedTypeBase || type instanceof ComputedTypeSuper)
				.forEach(pending::addLast);
		while (!pending.isEmpty()) {
			ComputedType type = pending.removeFirst();
			if (reachable.putIfAbsent(type.getName(), type) != null) continue;
			type.getDependencies().forEach(pending::addLast);
		}
		var definitions = new ArrayList<String>();
		reachable.entrySet().stream()
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
		return type.getClass().getSimpleName() + ":" + type.getName();
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

	private record FrozenEntry(String name, String sha256) {}
}
