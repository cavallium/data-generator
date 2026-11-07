package it.cavallium.datagen.plugin.fuzz.support;

import it.cavallium.datagen.DataCodec;
import it.cavallium.datagen.DecodeLimits;
import it.cavallium.buffer.Buf;
import it.cavallium.stream.SafeDataInput;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective facade over ONE compiled schema: it turns the generated {@code BaseType} / {@code Versions} /
 * {@code CurrentVersion} classes into plain Java calls, so that an oracle can read, upgrade and codec-encode
 * values without knowing the generated package.
 *
 * <h2>Real generated surface (verified, not assumed)</h2>
 * <p>Generated from a tiny inline schema and read back from the emitted files:</p>
 * <ul>
 *   <li>{@code <base>/Versions.java} declares {@code public static final int V0} (one per version, the
 *   normalized version number) and {@code public static final IVersion[] VERSIONS = { <pkg>.Version.INSTANCE,
 *   ... }} in version order. There is <em>no</em> {@code Versions.v0N()} accessor and no {@code serializers()}
 *   member: {@code GenVersions} emits only the {@code V<n>} constants and the {@code VERSIONS} array.</li>
 *   <li>{@code <base>/<version>/Version.java} (current version: {@code <base>/current/Version.java}) is the
 *   {@code IVersion} implementation. It exposes {@code public static final Version INSTANCE}, one
 *   {@code public static final DataCodec<X> <X>SerializerInstance} field per codec of that version, and
 *   {@code DataCodec<T> getCodec(BaseType)} — a switch over the {@code BaseType} constant that throws
 *   {@link IllegalArgumentException} for a type not present in that version. That getter is the reachable
 *   codec path, because {@code <X>Serializer} (in {@code <version>/serializers/}) has a private constructor
 *   and no public one.</li>
 *   <li>{@code <base>/current/CurrentVersion.java} provides {@code read(int, BaseType, SafeDataInput)},
 *   {@code newReader(BaseType, DecodeLimits)} -> {@code CurrentVersion.Reader},
 *   {@code newReader(int, BaseType, DecodeLimits)} -> {@code CurrentVersion.BoundReader},
 *   {@code upgradeDataToLatestVersion(int, T)}, {@code getClass(BaseType)} and {@code VERSION}.</li>
 *   <li>{@code <base>/BaseType.java} is the enum of every base type; unions such as
 *   {@code <base>/current/data/Choice.java} are {@code sealed interface}s, so
 *   {@link Class#getPermittedSubclasses()} lists their alternatives.</li>
 * </ul>
 *
 * <p>Every lookup failure is an {@link IllegalStateException} naming the schema fingerprint and the lookup
 * that was attempted; nothing here returns {@code null} on a missing member. Resolved
 * {@link MethodHandle}s are cached per instance.
 */
public final class GeneratedModel {

	private static final String VERSIONS_FIELD = "VERSIONS";
	private static final String GET_CODEC = "getCodec";
	private static final String CURRENT = "current";

	private final CompiledSchema schema;
	private final Map<String, MethodHandle> handles = new ConcurrentHashMap<>();
	private final Object[] versionInstances;
	private final int[] versionIds;
	private volatile Object[] baseTypeConstants;

	public GeneratedModel(CompiledSchema schema) {
		this.schema = Objects.requireNonNull(schema, "schema");
		this.versionInstances = schema.versionsArray();
		this.versionIds = schema.allVersionIds();
	}

	/** The compiled schema behind this facade. */
	public CompiledSchema schema() {
		return schema;
	}

	/** Reads {@code version}'s encoding of {@code baseType} from {@code input}, upgrading to the latest shape. */
	public Object read(int version, Object baseType, SafeDataInput input) {
		Objects.requireNonNull(baseType, "baseType");
		Objects.requireNonNull(input, "input");
		MethodHandle handle = findStatic(currentVersionClass(), "read",
				MethodType.methodType(Object.class, int.class, baseTypeClass(), SafeDataInput.class),
				"CurrentVersion.read(int, BaseType, SafeDataInput)");
		return invoke(handle, "CurrentVersion.read(int, BaseType, SafeDataInput)", version, baseType, input);
	}

	/** Reads {@code length} bytes of {@code source} at {@code offset} using the generated bound reader. */
	public Object read(int version, Object baseType, Buf source, int offset, int length) {
		Objects.requireNonNull(baseType, "baseType");
		Objects.requireNonNull(source, "source");
		Object boundReader = newBoundReader(version, baseType, DecodeLimits.unlimited());
		MethodHandle handle = findVirtual(boundReader.getClass(), "read",
				MethodType.methodType(Object.class, Buf.class, int.class, int.class),
				"CurrentVersion.BoundReader.read(Buf, int, int)");
		return invoke(handle, "CurrentVersion.BoundReader.read(Buf, int, int)", boundReader, source, offset, length);
	}

	/**
	 * The generated version-agnostic reader for {@code baseType}
	 * ({@code CurrentVersion.Reader#read(int, Buf, int, int)} keeps working across versions).
	 */
	public Object newReader(Object baseType, DecodeLimits limits) {
		Objects.requireNonNull(baseType, "baseType");
		Objects.requireNonNull(limits, "limits");
		MethodHandle handle = findStatic(currentVersionClass(), "newReader",
				MethodType.methodType(Object.class, baseTypeClass(), DecodeLimits.class),
				"CurrentVersion.newReader(BaseType, DecodeLimits)");
		return invoke(handle, "CurrentVersion.newReader(BaseType, DecodeLimits)", baseType, limits);
	}

	/** The generated reader bound to one version and one type. */
	public Object newBoundReader(int version, Object baseType, DecodeLimits limits) {
		Objects.requireNonNull(baseType, "baseType");
		Objects.requireNonNull(limits, "limits");
		requireVersion(version);
		MethodHandle handle = findStatic(currentVersionClass(), "newReader",
				MethodType.methodType(Object.class, int.class, baseTypeClass(), DecodeLimits.class),
				"CurrentVersion.newReader(int, BaseType, DecodeLimits)");
		return invoke(handle, "CurrentVersion.newReader(int, BaseType, DecodeLimits)", version, baseType, limits);
	}

	/** Runs the generated upgrader chain from {@code oldVersion} to the latest version. */
	@SuppressWarnings("unchecked")
	public <T> T upgrade(int oldVersion, T oldData) {
		MethodHandle handle = findStatic(currentVersionClass(), "upgradeDataToLatestVersion",
				MethodType.methodType(Object.class, int.class, Object.class),
				"CurrentVersion.upgradeDataToLatestVersion(int, T)");
		return (T) invoke(handle, "CurrentVersion.upgradeDataToLatestVersion(int, T)", oldVersion, oldData);
	}

	/**
	 * The codec of {@code baseType} at {@code version}, reached through the generated {@code Versions} array:
	 * {@code Versions.VERSIONS[i].getCodec(baseType)}. {@code <Type>Serializer} itself has no public
	 * constructor, so its {@code INSTANCE}/static-field route is the only supported one.
	 */
	public DataCodec<?> codec(int version, Object baseType) {
		Objects.requireNonNull(baseType, "baseType");
		Object versionInstance = versionInstance(version);
		MethodHandle handle = findVirtual(versionInstance.getClass(), GET_CODEC,
				MethodType.methodType(DataCodec.class, baseTypeClass()),
				"IVersion.getCodec(BaseType) on " + versionInstance.getClass().getName());
		Object codec = invoke(handle, "IVersion.getCodec(BaseType) on " + versionInstance.getClass().getName(),
				versionInstance, baseType);
		if (!(codec instanceof DataCodec<?> dataCodec)) {
			throw failed("IVersion.getCodec(BaseType) returned " + codec);
		}
		return dataCodec;
	}

	/** {@link #codec(int, Object)} addressed by the simple name of the {@code BaseType} constant. */
	public DataCodec<?> codec(int version, String simpleTypeName) {
		return codec(version, baseTypeConstant(simpleTypeName));
	}

	/** Every {@code BaseType} constant, in generated declaration order. */
	public Object[] baseTypeConstants() {
		Object[] constants = baseTypeConstants;
		if (constants == null) {
			constants = schema.baseTypeConstants().clone();
			baseTypeConstants = constants;
		}
		return constants.clone();
	}

	/** The {@code BaseType} constant named {@code simpleTypeName}. */
	public Object baseTypeConstant(String simpleTypeName) {
		for (Object constant : baseTypeConstants()) {
			if (((Enum<?>) constant).name().equals(simpleTypeName)) {
				return constant;
			}
		}
		throw failed("BaseType constant " + simpleTypeName + " (known: " + typeNames() + ")");
	}

	/** The generated {@code current.CurrentVersion} class. */
	public Class<?> currentVersionClass() {
		return schema.load(schema.rootPackage() + "." + CURRENT + ".CurrentVersion");
	}

	/** The generated {@code BaseType} enum class. */
	public Class<?> baseTypeClass() {
		return schema.baseTypeClass();
	}

	/** Version ids of this schema, ascending. */
	public int[] versionIds() {
		return versionIds.clone();
	}

	/** Base type names of this schema. */
	public List<String> typeNames() {
		return schema.typeNames();
	}

	/** The data class of {@code simpleTypeName} at {@code version}, falling back to the current version. */
	public Class<?> dataClass(String simpleTypeName, int version) {
		requireVersion(version);
		if (version != schema.currentVersionId()) {
			String historical = schema.rootPackage() + ".v" + version + ".data." + simpleTypeName;
			try {
				return schema.loader().loadClass(historical);
			} catch (ClassNotFoundException ignored) {
				// Structurally fused version: the generator emitted only the current class for this shape.
			}
		}
		return schema.recordClass(simpleTypeName);
	}

	/**
	 * The alternatives of a generated union, sorted by simple name so that a caller can pick one from a
	 * deterministic random source.
	 */
	public List<Class<?>> unionAlternatives(String simpleTypeName) {
		Class<?> union = schema.unionInterface(simpleTypeName);
		Class<?>[] permitted = union.getPermittedSubclasses();
		if (permitted == null) {
			throw failed("permitted subclasses of " + union.getName() + " (not a sealed interface?)");
		}
		List<Class<?>> alternatives = new ArrayList<>(Arrays.asList(permitted));
		alternatives.sort(Comparator.comparing(Class::getName));
		return List.copyOf(alternatives);
	}

	private Object versionInstance(int version) {
		requireVersion(version);
		for (int index = 0; index < versionIds.length; index++) {
			if (versionIds[index] == version) {
				return versionInstances[index];
			}
		}
		throw failed("Versions.VERSIONS entry for version " + version + " (known: " + Arrays.toString(versionIds) + ")");
	}

	private void requireVersion(int version) {
		for (int known : versionIds) {
			if (known == version) {
				return;
			}
		}
		throw failed("version " + version + " (known: " + Arrays.toString(versionIds) + ")");
	}

	private MethodHandle findStatic(Class<?> owner, String name, MethodType type, String lookup) {
		String key = "static " + owner.getName() + '#' + name + type;
		return handles.computeIfAbsent(key, ignored -> resolve(owner, name, type, lookup, true));
	}

	private MethodHandle findVirtual(Class<?> owner, String name, MethodType type, String lookup) {
		String key = "virtual " + owner.getName() + '#' + name + type;
		return handles.computeIfAbsent(key, ignored -> resolve(owner, name, type, lookup, false));
	}

	/**
	 * Resolves a handle, preferring the exact {@link MethodHandles.Lookup} form and falling back to
	 * {@link Class#getMethod} plus {@code asType}. The fallback matters twice: a generated generic method is
	 * erased to {@code IBaseType} rather than {@code Object}, and a generated reader is implemented by a
	 * package-private nested class that this facade's lookup cannot see directly.
	 */
	private MethodHandle resolve(Class<?> owner, String name, MethodType type, String lookup, boolean statics) {
		MethodHandles.Lookup caller = MethodHandles.lookup();
		try {
			return statics ? caller.findStatic(owner, name, type) : caller.findVirtual(owner, name, type);
		} catch (NoSuchMethodException | IllegalAccessException first) {
			try {
				Method method = owner.getMethod(name, type.parameterArray());
				method.setAccessible(true);
				// unreflect handles static and instance methods alike, and an unreflected virtual method keeps
				// its receiver as parameter 0; only the erased return type (IBaseType -> Object) needs adapting.
				MethodHandle handle = caller.unreflect(method);
				return handle.asType(handle.type().changeReturnType(type.returnType()));
			} catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException second) {
				throw failed(lookup, second);
			}
		}
	}

	private Object invoke(MethodHandle handle, String lookup, Object... arguments) {
		try {
			return handle.invokeWithArguments(arguments);
		} catch (RuntimeException exception) {
			throw failed(lookup, exception);
		} catch (Error error) {
			// JVM-level (OOM, StackOverflow): rethrow unwrapped, never wrap in a lookup defect.
			throw error;
		} catch (Throwable throwable) {
			if (throwable instanceof InvocationTargetException target) {
				throwable = target.getCause() == null ? target : target.getCause();
			}
			if (throwable instanceof RuntimeException runtimeException) {
				throw failed(lookup, runtimeException);
			}
			if (throwable instanceof Error error) {
				throw error;
			}
			throw failed(lookup, throwable);
		}
	}

	private IllegalStateException failed(String lookup) {
		return failed(lookup, null);
	}

	private IllegalStateException failed(String lookup, Throwable cause) {
		return new IllegalStateException("Generated-model lookup failed for fingerprint="
				+ schema.fingerprint() + ", lookup=" + lookup
				+ ", versions=" + Arrays.toString(versionIds)
				+ ", baseTypes=" + typeNames(), cause);
	}

	/** The class names of the {@code Versions.VERSIONS} entries this facade resolved at construction. */
	public List<String> versionClassNames() {
		List<String> names = new ArrayList<>();
		for (Object versionInstance : versionInstances) {
			names.add(versionInstance.getClass().getName());
		}
		return List.copyOf(names);
	}
}
