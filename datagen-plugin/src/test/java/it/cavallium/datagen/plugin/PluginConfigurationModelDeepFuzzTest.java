package it.cavallium.datagen.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.palantir.javapoet.ClassName;
import it.cavallium.datagen.nativedata.BinaryString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Direct fuzzing for every configuration, parsed-model, and computed-model family. */
class PluginConfigurationModelDeepFuzzTest {

	private static final long CONFIGURATION_SEED = 0x4D17_A8C2_6F30_BE95L;
	private static final long READ_TRANSFORM_SEED = 0x72C0_5E19_A4D8_3B6FL;
	private static final long PARSED_CLASS_SEED = 0x185B_D3E7_90A4_6C2FL;
	private static final long NAME_SEED = 0x60F2_9C14_7BE5_38ADL;
	private static final int CONFIGURATION_CASES = 50_000;
	private static final int READ_TRANSFORM_CASES = 50_000;
	private static final int PARSED_CLASS_CASES = 20_000;
	private static final int NAME_CASES = 100_000;

	@Test
	void configurationEqualityHashingCopyingAndOrderSemanticsFuzzEveryPojo() {
		var random = new Random(CONFIGURATION_SEED);
		for (int caseIndex = 0; caseIndex < CONFIGURATION_CASES; caseIndex++) {
			String diagnostic = "seed=" + CONFIGURATION_SEED + ", case=" + caseIndex;
			ClassConfiguration classConfiguration = randomClassConfiguration(random);
			ClassConfiguration classCopy = classConfiguration.copy();
			assertEquals(classConfiguration, classCopy, diagnostic);
			assertEquals(classConfiguration.hashCode(), classCopy.hashCode(), diagnostic);
			assertNotSame(classConfiguration, classCopy, diagnostic);
			assertNotSame(classConfiguration.data, classCopy.data, diagnostic);

			if (classCopy.data.size() > 1) {
				ClassConfiguration reordered = classCopy.copy();
				var entries = new ArrayList<>(reordered.data.entrySet());
				Collections.reverse(entries);
				reordered.data.clear();
				entries.forEach(entry -> reordered.data.put(entry.getKey(), entry.getValue()));
				assertNotEquals(classConfiguration, reordered, diagnostic);
			}

			CustomTypesConfiguration custom = randomCustomType(random);
			CustomTypesConfiguration customCopy = custom.copy();
			assertEquals(custom, customCopy, diagnostic);
			assertEquals(custom.hashCode(), customCopy.hashCode(), diagnostic);
			assertNotSame(custom, customCopy, diagnostic);

			ProjectionConfiguration projection = randomProjection(random);
			ProjectionConfiguration projectionCopy = copyProjection(projection);
			assertEquals(projection, projectionCopy, diagnostic);
			assertEquals(projection.hashCode(), projectionCopy.hashCode(), diagnostic);
			if (projection.fields.size() > 1) {
				var reversed = new ArrayList<>(projection.fields.entrySet());
				Collections.reverse(reversed);
				projectionCopy.fields.clear();
				reversed.forEach(entry -> projectionCopy.fields.put(entry.getKey(), entry.getValue()));
				assertNotEquals(projection, projectionCopy, diagnostic);
			}

			InterfaceDataConfiguration interfaceData = randomInterface(random);
			InterfaceDataConfiguration interfaceCopy = copyInterface(interfaceData);
			assertEquals(interfaceData, interfaceCopy, diagnostic);
			assertEquals(interfaceData.hashCode(), interfaceCopy.hashCode(), diagnostic);
			ParsedInterface parsedInterface = new ParsedInterface(copyInterface(interfaceData));
			ParsedInterface parsedInterfaceCopy = new ParsedInterface(copyInterface(interfaceData));
			assertEquals(parsedInterface, parsedInterfaceCopy, diagnostic);
			assertEquals(parsedInterface.hashCode(), parsedInterfaceCopy.hashCode(), diagnostic);

			DetailsConfiguration details = new DetailsConfiguration();
			details.changelog = randomText(random);
			DetailsConfiguration detailsCopy = new DetailsConfiguration();
			detailsCopy.changelog = details.changelog;
			assertEquals(details, detailsCopy, diagnostic);
			assertEquals(details.hashCode(), detailsCopy.hashCode(), diagnostic);

			for (VersionTransformation transformation : randomVersionTransformations(random)) {
				VersionTransformation copy = transformation.copy();
				assertEquals(transformation, copy, diagnostic);
				assertEquals(transformation.hashCode(), copy.hashCode(), diagnostic);
				assertNotSame(transformation, copy, diagnostic);
				TransformationConfiguration selected = transformation.getTransformation();
				TransformationConfiguration selectedCopy = copy.getTransformation();
				assertNotSame(selected, selectedCopy, diagnostic);
				assertEquals(selected.getTransformClass(), selectedCopy.getTransformClass(), diagnostic);
				assertEquals(selected.getTransformName(), selectedCopy.getTransformName(), diagnostic);
				assertTrue(transformation.isForClass(selected.getTransformClass()), diagnostic);
				assertFalse(transformation.isForClass(selected.getTransformClass() + "Other"), diagnostic);
				if (selected instanceof NewDataConfiguration add) {
					String expectedLocation = add.initializer != null ? "C:" + add.initializer
							: "F:" + add.initializerInstance;
					assertEquals(expectedLocation, add.getInitializerLocation().getIdentifier(), diagnostic);
					assertEquals(add.contextParameters == null ? List.of() : add.contextParameters,
							add.getContextParameters(), diagnostic);
					assertEquals(add.readTransform != null, add.hasReadTransform(), diagnostic);
					if (add.hasReadTransform()) {
						assertSame(add.readTransform, add.getReadTransform(), diagnostic);
						assertEquals(add.readTransform.getResultType(add.type), add.getReadTransformType(), diagnostic);
						assertEquals(add.readTransform.hasResultTypeOverride(),
								add.hasReadTransformTypeOverride(), diagnostic);
					} else {
						assertThrows(IllegalStateException.class, add::getReadTransform, diagnostic);
					}
				} else if (selected instanceof UpgradeDataConfiguration upgrade) {
					String expectedLocation = upgrade.upgrader != null ? "C:" + upgrade.upgrader
							: "F:" + upgrade.upgraderInstance;
					assertEquals(expectedLocation, upgrade.getUpgraderLocation().getIdentifier(), diagnostic);
					assertEquals(upgrade.contextParameters == null ? List.of() : upgrade.contextParameters,
							upgrade.getContextParameters(), diagnostic);
					assertEquals(upgrade.readTransform != null, upgrade.hasReadTransform(), diagnostic);
					if (upgrade.hasReadTransform()) {
						assertSame(upgrade.readTransform, upgrade.getReadTransform(), diagnostic);
						assertEquals(upgrade.readTransform.getResultType(upgrade.type),
								upgrade.getReadTransformType(), diagnostic);
						assertEquals(upgrade.readTransform.hasResultTypeOverride(),
								upgrade.hasReadTransformTypeOverride(), diagnostic);
					} else {
						assertThrows(IllegalStateException.class, upgrade::getReadTransform, diagnostic);
					}
				}
			}

			VersionTransformation empty = new VersionTransformation();
			assertThrows(IllegalArgumentException.class, empty::getTransformation, diagnostic);
			VersionTransformation ambiguous = new VersionTransformation();
			ambiguous.moveData = randomMove(random);
			ambiguous.removeData = randomRemove(random);
			assertThrows(IllegalArgumentException.class, ambiguous::getTransformation, diagnostic);

			VersionConfiguration version = randomVersion(random);
			VersionConfiguration versionCopy = copyVersion(version);
			assertEquals(version, versionCopy, diagnostic);
			assertEquals(version.hashCode(), versionCopy.hashCode(), diagnostic);
			ParsedVersion parsed = new ParsedVersion(version);
			ParsedVersion parsedAgain = new ParsedVersion(versionCopy);
			assertEquals(parsed, parsedAgain, diagnostic);
			assertEquals(parsed.hashCode(), parsedAgain.hashCode(), diagnostic);
		}
	}

	@Test
	void equalTopLevelConfigurationsHaveEqualHashesAcrossMapImplementationsAndInsertionOrders() {
		var random = new Random(CONFIGURATION_SEED ^ -1L);
		for (int caseIndex = 0; caseIndex < CONFIGURATION_CASES; caseIndex++) {
			SourcesGeneratorConfiguration first = randomTopLevelConfiguration(random, false);
			SourcesGeneratorConfiguration second = reorderedTopLevelCopy(first, random);
			String diagnostic = "seed=" + (CONFIGURATION_SEED ^ -1L) + ", case=" + caseIndex;
			assertEquals(first, second, diagnostic);
			assertEquals(first.hashCode(), second.hashCode(), diagnostic);
		}

		SourcesGeneratorConfiguration defaults = new SourcesGeneratorConfiguration();
		defaults.currentVersion = "v1";
		VersionConfiguration root = new VersionConfiguration();
		defaults.versions = Map.of("v1", root);
		DataModel defaulted = defaults.buildDataModel(false);
		assertTrue(defaulted.getInterfaces().isEmpty());
		assertTrue(defaulted.getBaseTypesComputed().findAny().isEmpty());
		assertTrue(defaulted.getSuperTypesComputed().findAny().isEmpty());
		assertTrue(defaulted.getProjections().isEmpty());
	}

	@Test
	void recursiveReadTransformGrammarValidatesCopiesAndEnumeratesEveryDeclaredType() {
		var random = new Random(READ_TRANSFORM_SEED);
		for (int caseIndex = 0; caseIndex < READ_TRANSFORM_CASES; caseIndex++) {
			ReadTransformConfiguration transform = randomReadTransform(random, 0, 7);
			String coordinate = "fuzz[" + caseIndex + "]";
			transform.validate(coordinate);
			ReadTransformConfiguration copy = transform.copy();
			assertEquals(transform, copy, coordinate);
			assertEquals(transform.hashCode(), copy.hashCode(), coordinate);
			assertNotSame(transform, copy, coordinate);
			assertEquals(transform.kind(), copy.kind(), coordinate);
			assertEquals(transform.hasResultTypeOverride(), copy.hasResultTypeOverride(), coordinate);
			String defaultType = "Default" + caseIndex;
			assertEquals(transform.getResultType(defaultType), copy.getResultType(defaultType), coordinate);
			assertEquals(transform.declaredSchemaTypes().toList(), copy.declaredSchemaTypes().toList(), coordinate);
			if (transform.isCustom()) {
				assertTrue(transform.custom.location(coordinate).getIdentifier().startsWith("C:")
						|| transform.custom.location(coordinate).getIdentifier().startsWith("F:"));
			}
		}
	}

	@Test
	void malformedReadTransformTreesFailWithStableCoordinatesForEveryInvalidShape() {
		var random = new Random(READ_TRANSFORM_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < READ_TRANSFORM_CASES; caseIndex++) {
			ReadTransformConfiguration invalid = invalidReadTransform(random, caseIndex);
			String coordinate = "versions.v" + caseIndex + ".transform.readTransform";
			IllegalArgumentException first = assertThrows(IllegalArgumentException.class,
					() -> invalid.validate(coordinate));
			IllegalArgumentException second = assertThrows(IllegalArgumentException.class,
					() -> invalid.validate(coordinate));
			assertEquals(first.getMessage(), second.getMessage());
			assertTrue(first.getMessage().contains(coordinate), first::getMessage);
		}
	}

	@Test
	void interfaceLocationsParseClassAndSingletonFormsAndRejectAmbiguity() {
		var random = new Random(READ_TRANSFORM_SEED ^ 0x55AA_55AA_55AA_55AAL);
		for (int caseIndex = 0; caseIndex < READ_TRANSFORM_CASES; caseIndex++) {
			String owner = "org.example.fuzz.C" + Long.toUnsignedString(random.nextLong(), 36);
			String field = "INSTANCE" + caseIndex;
			JInterfaceLocation byClass = JInterfaceLocation.parse(owner, null);
			JInterfaceLocation byField = JInterfaceLocation.parse(null, owner + "." + field);
			assertEquals("C:" + owner, byClass.getIdentifier());
			assertEquals("F:" + owner + "." + field, byField.getIdentifier());
			assertThrows(IllegalArgumentException.class, () -> JInterfaceLocation.parse(owner, owner + "." + field));
			assertThrows(IllegalArgumentException.class, () -> JInterfaceLocation.parse(null, null));
		}
	}

	@Test
	void parsedClassOrderedEditStateMachineMatchesALinkedHashMapAndContextTypes() {
		var seedSource = new Random(PARSED_CLASS_SEED);
		for (int caseIndex = 0; caseIndex < PARSED_CLASS_CASES; caseIndex++) {
			long seed = seedSource.nextLong();
			var random = new Random(seed);
			ClassConfiguration initial = new ClassConfiguration();
			initial.stringRepresenter = "f0";
			initial.data = new LinkedHashMap<>();
			initial.data.put("f0", "int");
			initial.data.put("f1", "String");
			initial.data.put("f2", "long");
			ParsedClass actual = new ParsedClass(initial);
			var expected = new LinkedHashMap<String, ParsedClass.FieldInfo>(actual.data);
			String representer = "f0";
			int nextField = 3;

			for (int operationIndex = 0; operationIndex < 256; operationIndex++) {
				int operation = random.nextInt(6);
				String diagnostic = "seed=" + seed + ", case=" + caseIndex + ", operation=" + operationIndex;
				if (operation == 0) {
					String name = "f" + nextField++;
					int index = random.nextInt(expected.size() + 1);
					String type = random.nextBoolean() ? "int" : "§String";
					List<String> context = expected.isEmpty() || random.nextBoolean() ? List.of()
							: List.of(new ArrayList<>(expected.keySet()).get(random.nextInt(expected.size())));
					ParsedClass.FieldInfo previous = actual.insert(index, name,
							new ParsedClass.InputFieldInfo(type, context));
					assertNull(previous, diagnostic);
					var contextTypes = new LinkedHashMap<String, String>();
					for (String contextField : context) {
						contextTypes.put(contextField, expected.get(contextField).typeName());
					}
					insertExpected(expected, index, name, new ParsedClass.FieldInfo(type, contextTypes));
				} else if (operation == 1 && !expected.isEmpty()) {
					String name = pick(random, expected.keySet());
					int index = new ArrayList<>(expected.keySet()).indexOf(name);
					ParsedClass.FieldInfo removed = expected.remove(name);
					assertEquals(Optional.of(Map.entry(index, removed)), actual.remove(name), diagnostic);
				} else if (operation == 2 && !expected.isEmpty()) {
					String name = pick(random, expected.keySet());
					String type = random.nextBoolean() ? "long" : "-String";
					ParsedClass.FieldInfo value = new ParsedClass.FieldInfo(type, new LinkedHashMap<>());
					assertEquals(expected.put(name, value), actual.replace(name,
							new ParsedClass.InputFieldInfo(type, List.of())), diagnostic);
				} else if (operation == 3 && expected.containsKey(representer)) {
					String renamed = "f" + nextField++;
					int index = new ArrayList<>(expected.keySet()).indexOf(representer);
					ParsedClass.FieldInfo value = expected.remove(representer);
					actual.remove(representer);
					actual.insert(index, renamed, new ParsedClass.InputFieldInfo(value.typeName(), List.of()));
					insertExpected(expected, index, renamed,
							new ParsedClass.FieldInfo(value.typeName(), new LinkedHashMap<>()));
					actual.renameStringRepresenterField(representer, renamed);
					representer = renamed;
				} else if (operation == 4) {
					actual.addDifferentThanPrev(randomMove(random));
					assertFalse(actual.differentThanPrev.isEmpty(), diagnostic);
				} else {
					ParsedClass copy = actual.copy();
					assertEquals(actual, copy, diagnostic);
					assertEquals(actual.hashCode(), copy.hashCode(), diagnostic);
					assertNotSame(actual, copy, diagnostic);
				}
				assertEquals(expected, actual.data, diagnostic);
				assertEquals(representer, actual.stringRepresenter, diagnostic);
			}

			String missing = "missing" + caseIndex;
			assertTrue(actual.remove(missing).isEmpty());
			assertThrows(ParsedClass.NoContextParameterException.class,
					() -> actual.insert(null, "broken",
							new ParsedClass.InputFieldInfo("int", List.of(missing))));
			assertThrows(RuntimeException.class,
					() -> actual.insert(-1, "negative", new ParsedClass.InputFieldInfo("int", List.of())));
			assertThrows(RuntimeException.class,
					() -> actual.insert(actual.data.size() + 1, "past", new ParsedClass.InputFieldInfo("int", List.of())));
		}
	}

	@Test
	void maximalDataModelExercisesEveryComputedTypeFamilyVersionEdgeAndPublicQuery() {
		for (boolean binaryStrings : List.of(false, true)) {
			SourcesGeneratorConfiguration configuration = maximalConfiguration();
			DataModel model = configuration.buildDataModel(binaryStrings);
			assertEquals(configuration.hashCode(), model.computeHash());
			assertEquals(2, model.getCurrentVersionNumber());
			assertEquals(3, model.getVersionsSet().size());
			assertEquals("org.generated", model.getRootPackage(""));
			assertEquals("org.example.current", model.getVersionPackage(model.getCurrentVersion(), "org.example"));
			assertEquals("org.example.current.data", model.getVersionDataPackage(model.getCurrentVersion(), "org.example"));
			assertEquals("org.example.child", DataModel.joinPackage("org.example", "child"));
			assertEquals("org.generated.child", DataModel.joinPackage("", "child"));

			assertEquals("§String", DataModel.fixType("String[]"));
			assertEquals("String", DataModel.extractTypeName("String[]"));
			assertEquals("String", DataModel.extractTypeName("-String"));
			assertThrows(UnsupportedOperationException.class, () -> DataModel.fixType("-String[]"));
			assertThrows(UnsupportedOperationException.class, () -> DataModel.extractTypeName("-String[]"));

			Map<String, ComputedType> current = model.getComputedTypes(model.getCurrentVersion());
			assertTrue(current.values().stream().anyMatch(ComputedTypeNative.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeCustom.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeBase.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeSuper.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeArrayNative.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeArrayFixed.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeArrayVersioned.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeNullableNative.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeNullableFixed.class::isInstance));
			assertTrue(current.values().stream().anyMatch(ComputedTypeNullableVersioned.class::isInstance));

			ComputedTypeNative stringType = (ComputedTypeNative) current.get("String");
			assertEquals(binaryStrings ? BinaryString.class.getName() : String.class.getName(),
					stringType.getJTypeName("org.example").toString());
			assertFalse(stringType.isPrimitive());
			assertTrue(((ComputedTypeNative) current.get("int")).isPrimitive());
			assertTrue(((ComputedTypeArrayNative) current.get("§String")).hasContainerSpecificElementWireFormat());
			assertFalse(((ComputedTypeArrayNative) current.get("§int")).hasContainerSpecificElementWireFormat());

			assertEquals(WireLayout.BOOLEAN_TAGGED_SHORT_STRING,
					WireLayout.of((ComputedTypeNullable) current.get("-String")));
			assertEquals(WireLayout.INT52_HIGH_BIT_SENTINEL,
					WireLayout.of((ComputedTypeNullable) current.get("-Int52")));
			assertEquals(WireLayout.BOOLEAN_TAGGED,
					WireLayout.of((ComputedTypeNullable) current.get("-int")));
			assertEquals(WireLayout.BOOLEAN_TAGGED,
					WireLayout.of((ComputedTypeNullable) current.get("-Fixed")));

			ComputedTypeBase currentA = (ComputedTypeBase) current.get("A");
			ComputedTypeBase previousA = model.getPrevVersion(currentA);
			assertTrue(previousA != null);
			assertSame(currentA, model.getNextVersion(previousA));
			assertNull(model.getNextVersion(currentA));
			assertEquals(model.getCurrentVersion(), model.getVersion(currentA));
			assertTrue(model.getTypeSameVersions(currentA).findAny().isPresent());
			assertTrue(model.getVersionRange(model.getVersion(0), model.getCurrentVersion()).count() == 3);
			assertThrows(IllegalArgumentException.class,
					() -> model.getVersionRange(model.getCurrentVersion(), model.getVersion(0)));
			assertTrue(model.isTypeForVersion(model.getCurrentVersion(), "A"));
			assertFalse(model.isTypeForVersion(model.getVersion(0), "String"));

			assertTrue(currentA.getDependencies().map(ComputedType::getName).collect(Collectors.toSet())
					.containsAll(Set.of("long", "B", "Choice", "Fixed", "§String", "-String")));
			assertTrue(current.get("B").getDependents().map(ComputedType::getName).anyMatch("A"::equals));
			assertTrue(model.getSuperTypesOf(currentA, true).map(ComputedType::getName).anyMatch("Choice"::equals));
			assertFalse(model.getChanges(1, "A").isEmpty());
			assertFalse(model.getChanges(2, "A").isEmpty());
			assertTrue(model.getChanges(0, "A").isEmpty());
			assertTrue(model.getChanges(99, "A").isEmpty());
			assertEquals(1, model.getProjections().size());

			assertTrue(DataModel.canStructurallyFuse(previousA, currentA));
			assertFalse(DataModel.canStructurallyFuse(current.get("String"), current.get("int")));
			assertEquals(currentA, model.getCurrentStructuralRepresentation(previousA));
			assertNull(model.getCurrentStructuralRepresentation(current.get("Fixed")));

			ComputedVersion first = model.getVersion(0);
			ComputedVersion middle = model.getVersion(1);
			assertEquals(Optional.of(middle), model.getNextVersion(first));
			assertEquals(middle, model.getNextVersionOrThrow(first));
			assertTrue(first.compareTo(middle) < 0);
			assertEquals("V0", first.getVersionVarName());
			assertEquals("0", first.getVersionShortInt());
			assertTrue(model.getInterfacesSet().isEmpty());

			VersionedType versionedA = new VersionedType("A", first);
			assertSame(versionedA, versionedA.withVersion(first));
			assertEquals(new VersionedType("A", middle), versionedA.withVersion(middle));
			VersionChangeChecker changed = new VersionChangeChecker(Set.of("A"), 1, 2);
			assertEquals(new VersionedType("A", middle), versionedA.withVersionIfChanged(middle, changed));
			VersionChangeChecker unchanged = new VersionChangeChecker(Set.of(), 1, 2);
			assertSame(versionedA, versionedA.withVersionIfChanged(middle, unchanged));
		}
	}

	@Test
	void generatedNameAllocatorNeverCollidesAndIsDeterministicUnderHostileSchemaNames() {
		var random = new Random(NAME_SEED);
		for (int caseIndex = 0; caseIndex < NAME_CASES; caseIndex++) {
			var schema = new LinkedHashSet<String>();
			var fixed = new LinkedHashSet<String>();
			int names = random.nextInt(33);
			for (int index = 0; index < names; index++) {
				String hint = randomIdentifierFragment(random);
				schema.add(random.nextBoolean() ? hint : "$datagen$" + hint);
				fixed.add(random.nextBoolean() ? hint + "$" + random.nextInt(5) : "$datagen$" + hint);
			}
			var first = new GeneratedNameAllocator(schema, fixed);
			var second = new GeneratedNameAllocator(schema, fixed);
			var seen = new HashSet<String>();
			seen.addAll(schema);
			seen.addAll(fixed);
			for (int allocation = 0; allocation < 128; allocation++) {
				String hint = randomIdentifierFragment(random);
				String firstName = first.allocate(hint);
				String secondName = second.allocate(hint);
				assertEquals(firstName, secondName);
				assertTrue(firstName.startsWith("$datagen$" + hint));
				assertTrue(seen.add(firstName), "collision at case=" + caseIndex + ", name=" + firstName);
			}
		}
		assertThrows(NullPointerException.class,
				() -> new GeneratedNameAllocator(List.of(), List.of()).allocate(null));
	}

	@Test
	void configHashHelpersVersionChangeChecksLocationsAndCapitalizationCoverTheirBoundarySurface() {
		var random = new Random(NAME_SEED ^ Long.MIN_VALUE);
		for (int caseIndex = 0; caseIndex < NAME_CASES; caseIndex++) {
			var entries = new ArrayList<Map.Entry<String, Integer>>();
			int size = random.nextInt(33);
			for (int index = 0; index < size; index++) entries.add(Map.entry("k" + index, random.nextInt()));
			var first = new HashMap<String, Integer>();
			var second = new HashMap<String, Integer>();
			entries.forEach(entry -> first.put(entry.getKey(), entry.getValue()));
			Collections.reverse(entries);
			entries.forEach(entry -> second.put(entry.getKey(), entry.getValue()));
			assertEquals(first, second);
			assertEquals(ConfigUtils.hashCode(first), ConfigUtils.hashCode(second));
			assertEquals(0, ConfigUtils.hashCode((Map<?, ?>) null));
			assertEquals(0, ConfigUtils.hashCode((List<?>) null));
			assertEquals(0, ConfigUtils.hashCode((Object) null));

			Set<String> changed = entries.stream().map(Map.Entry::getKey).collect(Collectors.toSet());
			VersionChangeChecker checker = new VersionChangeChecker(changed, caseIndex, caseIndex + 9);
			assertEquals(caseIndex, checker.getVersion());
			assertEquals(caseIndex + 9, checker.getLatestVersion());
			for (String key : changed) assertTrue(checker.checkChanged(key));
			assertFalse(checker.checkChanged("missing"));

			FieldLocation location = new FieldLocation(ClassName.get("org.example", "Owner"), "field" + caseIndex);
			assertEquals("org.example.Owner", location.className().toString());
			assertEquals("field" + caseIndex, location.fieldName());
			String text = "f" + caseIndex;
			assertEquals("F" + caseIndex, SourcesGenerator.capitalize(text));
		}
	}

	private static ClassConfiguration randomClassConfiguration(Random random) {
		var result = new ClassConfiguration();
		result.stringRepresenter = random.nextBoolean() ? null : "f0";
		result.data = new LinkedHashMap<>();
		int fields = random.nextInt(9);
		for (int index = 0; index < fields; index++) result.data.put("f" + index, randomType(random));
		return result;
	}

	private static CustomTypesConfiguration randomCustomType(Random random) {
		var result = new CustomTypesConfiguration();
		result.setJavaClass(random.nextBoolean() ? "java.lang.String" : "java.util.Map<java.lang.String, java.lang.Long>");
		result.codec = "org.example.Codec" + random.nextInt(32);
		result.fixedSize = random.nextBoolean() ? null : random.nextInt(65);
		return result;
	}

	private static ProjectionConfiguration randomProjection(Random random) {
		var result = new ProjectionConfiguration();
		result.sourceType = "T" + random.nextInt(16);
		result.fields = new LinkedHashMap<>();
		int fields = random.nextInt(9);
		for (int index = 0; index < fields; index++) result.fields.put("p" + index,
				random.nextBoolean() ? "f" + index : "nested.f" + index);
		return result;
	}

	private static ProjectionConfiguration copyProjection(ProjectionConfiguration source) {
		var result = new ProjectionConfiguration();
		result.sourceType = source.sourceType;
		result.fields = new LinkedHashMap<>(source.fields);
		return result;
	}

	private static InterfaceDataConfiguration randomInterface(Random random) {
		var result = new InterfaceDataConfiguration();
		int count = random.nextInt(9);
		for (int index = 0; index < count; index++) {
			result.extendInterfaces.add("I" + random.nextInt(16));
			result.commonData.put("d" + index, randomType(random));
			result.commonGetters.put("g" + index, randomType(random));
		}
		return result;
	}

	private static InterfaceDataConfiguration copyInterface(InterfaceDataConfiguration source) {
		var result = new InterfaceDataConfiguration();
		result.extendInterfaces.addAll(source.extendInterfaces);
		result.commonData.putAll(source.commonData);
		result.commonGetters.putAll(source.commonGetters);
		return result;
	}

	private static List<VersionTransformation> randomVersionTransformations(Random random) {
		VersionTransformation move = new VersionTransformation();
		move.moveData = randomMove(random);
		VersionTransformation remove = new VersionTransformation();
		remove.removeData = randomRemove(random);
		VersionTransformation upgrade = new VersionTransformation();
		upgrade.upgradeData = randomUpgrade(random);
		VersionTransformation add = new VersionTransformation();
		add.newData = randomNew(random);
		return List.of(move, remove, upgrade, add);
	}

	private static MoveDataConfiguration randomMove(Random random) {
		var result = new MoveDataConfiguration();
		result.transformClass = "T" + random.nextInt(16);
		result.from = "f" + random.nextInt(16);
		result.to = "f" + random.nextInt(16);
		result.index = random.nextBoolean() ? null : random.nextInt(17);
		return result;
	}

	private static RemoveDataConfiguration randomRemove(Random random) {
		var result = new RemoveDataConfiguration();
		result.transformClass = "T" + random.nextInt(16);
		result.from = "f" + random.nextInt(16);
		return result;
	}

	private static NewDataConfiguration randomNew(Random random) {
		var result = new NewDataConfiguration();
		result.transformClass = "T" + random.nextInt(16);
		result.to = "f" + random.nextInt(16);
		result.type = randomType(random);
		if (random.nextBoolean()) result.initializer = "org.example.Initializer" + random.nextInt(16);
		else result.initializerInstance = "org.example.Initializers.I" + random.nextInt(16);
		result.readTransform = random.nextBoolean() ? null : randomReadTransform(random, 0, 3);
		result.index = random.nextBoolean() ? null : random.nextInt(17);
		result.contextParameters = random.nextBoolean() ? null : List.of("context" + random.nextInt(8));
		return result;
	}

	private static UpgradeDataConfiguration randomUpgrade(Random random) {
		var result = new UpgradeDataConfiguration();
		result.transformClass = "T" + random.nextInt(16);
		result.from = "f" + random.nextInt(16);
		result.type = randomType(random);
		if (random.nextBoolean()) result.upgrader = "org.example.Upgrader" + random.nextInt(16);
		else result.upgraderInstance = "org.example.Upgraders.I" + random.nextInt(16);
		result.readTransform = random.nextBoolean() ? null : randomReadTransform(random, 0, 3);
		result.contextParameters = random.nextBoolean() ? null : List.of("context" + random.nextInt(8));
		return result;
	}

	private static VersionConfiguration randomVersion(Random random) {
		var result = new VersionConfiguration();
		result.previousVersion = random.nextBoolean() ? null : "v" + random.nextInt(16);
		result.details = new DetailsConfiguration();
		result.details.changelog = randomText(random);
		result.transformations = randomVersionTransformations(random);
		result.typeVersions = random.nextBoolean() ? null : Map.of("T", random.nextInt(16));
		result.dependentTypes = random.nextBoolean() ? null : Map.of("T", List.of("D"));
		return result;
	}

	private static VersionConfiguration copyVersion(VersionConfiguration source) {
		var result = new VersionConfiguration();
		result.previousVersion = source.previousVersion;
		result.details = new DetailsConfiguration();
		result.details.changelog = source.details.changelog;
		result.transformations = source.transformations.stream().map(VersionTransformation::copy).toList();
		result.typeVersions = source.typeVersions == null ? null : new HashMap<>(source.typeVersions);
		result.dependentTypes = source.dependentTypes == null ? null : new HashMap<>(source.dependentTypes);
		return result;
	}

	private static SourcesGeneratorConfiguration randomTopLevelConfiguration(Random random, boolean reordered) {
		var result = new SourcesGeneratorConfiguration();
		result.currentVersion = "v1";
		result.interfacesData = new LinkedHashMap<>();
		result.baseTypesData = new LinkedHashMap<>();
		result.superTypesData = new LinkedHashMap<>();
		result.customTypesData = new LinkedHashMap<>();
		result.projectionsData = new LinkedHashMap<>();
		result.versions = new LinkedHashMap<>();
		int count = 1 + random.nextInt(8);
		for (int index = 0; index < count; index++) {
			result.interfacesData.put("I" + index, randomInterface(random));
			result.baseTypesData.put("T" + index, randomClassConfiguration(random));
			result.superTypesData.put("S" + index, List.of("T" + index));
			result.customTypesData.put("C" + index, randomCustomType(random));
			result.projectionsData.put("P" + index, randomProjection(random));
		}
		result.versions.put("v1", new VersionConfiguration());
		return result;
	}

	private static SourcesGeneratorConfiguration reorderedTopLevelCopy(SourcesGeneratorConfiguration source,
			Random random) {
		var result = new SourcesGeneratorConfiguration();
		result.currentVersion = source.currentVersion;
		result.interfacesData = shuffledMap(source.interfacesData, random);
		result.baseTypesData = shuffledMap(source.baseTypesData, random);
		result.superTypesData = shuffledMap(source.superTypesData, random);
		result.customTypesData = shuffledMap(source.customTypesData, random);
		result.projectionsData = shuffledMap(source.projectionsData, random);
		result.versions = shuffledMap(source.versions, random);
		return result;
	}

	private static <K, V> LinkedHashMap<K, V> shuffledMap(Map<K, V> source, Random random) {
		var entries = new ArrayList<>(source.entrySet());
		Collections.shuffle(entries, random);
		var result = new LinkedHashMap<K, V>();
		entries.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
		return result;
	}

	private static ReadTransformConfiguration randomReadTransform(Random random, int depth, int maximumDepth) {
		var result = new ReadTransformConfiguration();
		if (random.nextInt(4) == 0) result.type = random.nextBoolean() ? "A" : "-B";
		int kind = depth >= maximumDepth ? random.nextInt(3) : random.nextInt(7);
		switch (kind) {
			case 0 -> {
				result.custom = new ReadTransformConfiguration.Custom();
				if (random.nextBoolean()) result.custom.className = "org.example.Transform" + random.nextInt(32);
				else result.custom.instance = "org.example.Transforms.I" + random.nextInt(32);
			}
			case 1 -> {
				result.constant = new ReadTransformConfiguration.Constant();
				result.constant.value = switch (random.nextInt(5)) {
					case 0 -> null;
					case 1 -> random.nextBoolean();
					case 2 -> random.nextLong();
					case 3 -> randomText(random);
					default -> List.of(random.nextInt(), random.nextInt());
				};
			}
			case 2 -> {
				result.identity = new ReadTransformConfiguration.Identity();
				result.identity.source = random.nextBoolean() ? "value" : "currentContext.f" + random.nextInt(16);
			}
			case 3 -> {
				result.invokeStatic = new ReadTransformConfiguration.InvokeStatic();
				result.invokeStatic.method = "org.example.Transforms.m" + random.nextInt(16);
				result.invokeStatic.arguments = randomChildren(random, depth, maximumDepth);
			}
			case 4 -> {
				result.construct = new ReadTransformConfiguration.Construct();
				if (random.nextBoolean()) result.construct.type = "A";
				else result.construct.className = "org.example.Value" + random.nextInt(16);
				result.construct.factory = random.nextBoolean() ? null : "of";
				result.construct.arguments = randomChildren(random, depth, maximumDepth);
			}
			case 5 -> {
				result.mapNullable = new ReadTransformConfiguration.MapNullable();
				result.mapNullable.source = randomReadTransform(random, depth + 1, maximumDepth);
				result.mapNullable.transform = randomReadTransform(random, depth + 1, maximumDepth);
			}
			case 6 -> {
				result.mapArray = new ReadTransformConfiguration.MapArray();
				result.mapArray.source = randomReadTransform(random, depth + 1, maximumDepth);
				result.mapArray.transform = randomReadTransform(random, depth + 1, maximumDepth);
			}
			default -> throw new AssertionError(kind);
		}
		return result;
	}

	private static List<ReadTransformConfiguration> randomChildren(Random random,
			int depth,
			int maximumDepth) {
		int count = random.nextInt(4);
		var result = new ArrayList<ReadTransformConfiguration>(count);
		for (int index = 0; index < count; index++) {
			result.add(randomReadTransform(random, depth + 1, maximumDepth));
		}
		return result;
	}

	private static ReadTransformConfiguration invalidReadTransform(Random random, int caseIndex) {
		var result = new ReadTransformConfiguration();
		switch (caseIndex % 10) {
			case 0 -> { }
			case 1 -> {
				result.identity = new ReadTransformConfiguration.Identity();
				result.identity.source = "value";
				result.constant = new ReadTransformConfiguration.Constant();
			}
			case 2 -> result.identity = new ReadTransformConfiguration.Identity();
			case 3 -> {
				result.custom = new ReadTransformConfiguration.Custom();
				result.custom.className = "org.example.Transform";
				result.custom.instance = "org.example.Transforms.I";
			}
			case 4 -> result.construct = new ReadTransformConfiguration.Construct();
			case 5 -> {
				result.construct = new ReadTransformConfiguration.Construct();
				result.construct.type = "A";
				result.construct.className = "org.example.A";
			}
			case 6 -> {
				result.mapNullable = new ReadTransformConfiguration.MapNullable();
				result.mapNullable.source = randomReadTransform(random, 0, 2);
			}
			case 7 -> {
				result.mapArray = new ReadTransformConfiguration.MapArray();
				result.mapArray.transform = randomReadTransform(random, 0, 2);
			}
			case 8 -> result.invokeStatic = new ReadTransformConfiguration.InvokeStatic();
			case 9 -> {
				result.invokeStatic = new ReadTransformConfiguration.InvokeStatic();
				result.invokeStatic.method = "org.example.Transforms.call";
				result.invokeStatic.arguments = new ArrayList<>();
				result.invokeStatic.arguments.add(null);
			}
			default -> throw new AssertionError();
		}
		return result;
	}

	private static SourcesGeneratorConfiguration maximalConfiguration() {
		var configuration = new SourcesGeneratorConfiguration();
		configuration.currentVersion = "v3";
		configuration.baseTypesData = new LinkedHashMap<>();

		var a = new ClassConfiguration();
		a.stringRepresenter = "id";
		a.data = new LinkedHashMap<>();
		a.data.put("id", "int");
		a.data.put("text", "String");
		a.data.put("boolValue", "boolean");
		a.data.put("shortValue", "short");
		a.data.put("charValue", "char");
		a.data.put("intValue", "int");
		a.data.put("longValue", "long");
		a.data.put("floatValue", "float");
		a.data.put("doubleValue", "double");
		a.data.put("byteValue", "byte");
		a.data.put("int52Value", "Int52");
		for (String nativeType : List.of("String", "boolean", "short", "char", "int", "long",
				"float", "double", "byte", "Int52")) {
			a.data.put("array" + SourcesGenerator.capitalize(nativeType), nativeType + "[]");
			a.data.put("maybe" + SourcesGenerator.capitalize(nativeType), "-" + nativeType);
		}
		a.data.put("fixed", "Fixed");
		a.data.put("fixedArray", "Fixed[]");
		a.data.put("maybeFixed", "-Fixed");
		a.data.put("opaque", "Opaque");
		a.data.put("opaqueArray", "Opaque[]");
		a.data.put("maybeOpaque", "-Opaque");
		a.data.put("child", "B");
		a.data.put("children", "B[]");
		a.data.put("maybeChild", "-B");
		a.data.put("choice", "Choice");
		a.data.put("choices", "Choice[]");
		a.data.put("maybeChoice", "-Choice");
		configuration.baseTypesData.put("A", a);

		var b = new ClassConfiguration();
		b.stringRepresenter = "code";
		b.data = new LinkedHashMap<>();
		b.data.put("code", "long");
		configuration.baseTypesData.put("B", b);

		configuration.superTypesData = new LinkedHashMap<>();
		configuration.superTypesData.put("Choice", List.of("A", "B"));
		configuration.interfacesData = Map.of();

		var fixed = new CustomTypesConfiguration();
		fixed.setJavaClass("java.lang.Integer");
		fixed.codec = "it.cavallium.datagen.plugin.TestFixedIntCodec";
		fixed.fixedSize = 4;
		var opaque = new CustomTypesConfiguration();
		opaque.setJavaClass("java.lang.String");
		opaque.codec = "it.cavallium.datagen.nativedata.StringSerializer";
		configuration.customTypesData = new LinkedHashMap<>();
		configuration.customTypesData.put("Fixed", fixed);
		configuration.customTypesData.put("Opaque", opaque);

		var projection = new ProjectionConfiguration();
		projection.sourceType = "A";
		projection.fields = new LinkedHashMap<>();
		projection.fields.put("identifier", "identifier");
		projection.fields.put("childCode", "child.code");
		projection.fields.put("maybeChildCode", "maybeChild.code");
		configuration.projectionsData = Map.of("ASummary", projection);

		var v1 = new VersionConfiguration();
		var v2 = new VersionConfiguration();
		v2.previousVersion = "v1";
		VersionTransformation move = new VersionTransformation();
		move.moveData = new MoveDataConfiguration();
		move.moveData.transformClass = "A";
		move.moveData.from = "id";
		move.moveData.to = "identifier";
		VersionTransformation remove = new VersionTransformation();
		remove.removeData = new RemoveDataConfiguration();
		remove.removeData.transformClass = "A";
		remove.removeData.from = "text";
		VersionTransformation add = new VersionTransformation();
		add.newData = new NewDataConfiguration();
		add.newData.transformClass = "B";
		add.newData.to = "active";
		add.newData.type = "long";
		add.newData.initializer = "it.cavallium.datagen.plugin.TestSimpleLongInitializer";
		v2.transformations = List.of(move, remove, add);

		var v3 = new VersionConfiguration();
		v3.previousVersion = "v2";
		VersionTransformation upgrade = new VersionTransformation();
		upgrade.upgradeData = new UpgradeDataConfiguration();
		upgrade.upgradeData.transformClass = "A";
		upgrade.upgradeData.from = "identifier";
		upgrade.upgradeData.type = "long";
		upgrade.upgradeData.upgrader = "it.cavallium.datagen.plugin.TestSimpleIntToLongUpgrader";
		v3.transformations = List.of(upgrade);
		configuration.versions = new LinkedHashMap<>();
		configuration.versions.put("v1", v1);
		configuration.versions.put("v2", v2);
		configuration.versions.put("v3", v3);
		return configuration;
	}

	private static <K, V> void insertExpected(LinkedHashMap<K, V> map, int index, K key, V value) {
		var entries = new ArrayList<>(map.entrySet());
		map.clear();
		for (int position = 0; position <= entries.size(); position++) {
			if (position == index) map.put(key, value);
			if (position < entries.size()) map.put(entries.get(position).getKey(), entries.get(position).getValue());
		}
	}

	private static <T> T pick(Random random, Set<T> values) {
		return new ArrayList<>(values).get(random.nextInt(values.size()));
	}

	private static String randomType(Random random) {
		String base = List.of("String", "boolean", "short", "char", "int", "long", "float",
				"double", "byte", "Int52", "T0", "Custom").get(random.nextInt(12));
		return switch (random.nextInt(3)) {
			case 0 -> base;
			case 1 -> "-" + base;
			default -> base + "[]";
		};
	}

	private static String randomText(Random random) {
		return "text-" + Long.toUnsignedString(random.nextLong(), 36);
	}

	private static String randomIdentifierFragment(Random random) {
		return switch (random.nextInt(8)) {
			case 0 -> "value";
			case 1 -> "cursor";
			case 2 -> "value$" + random.nextInt(4);
			case 3 -> "$datagen$value";
			default -> "n" + Long.toUnsignedString(random.nextLong(), 36);
		};
	}
}
