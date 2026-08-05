package it.cavallium.datagen.benchmark;

import it.cavallium.datagen.benchmark.fixture.BaseType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Instruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Emits deterministic generated-source and classfile metrics and verifies the monomorphic reader shape.
 * JMH owns throughput/allocation reporting; this companion keeps bytecode/code-size evidence independent
 * from a particular profiler installation.
 */
public final class GeneratedCodeShapeReport {

	private static final String FIXTURE = "it/cavallium/datagen/benchmark/fixture";
	private static final String CURRENT_VERSION = FIXTURE + "/current/CurrentVersion";
	private static final int MAX_HOT_METHOD_BYTES = 32_000;

	private GeneratedCodeShapeReport() {}

	public static void main(String[] args) throws Exception {
		Path sources = args.length == 0
				? Path.of("target/generated-sources/database-classes/java")
				: Path.of(args[0]);
		Report report = inspectAndVerify(sources);
		System.out.println("metric\tvalue");
		System.out.println("generated.source.files\t" + report.sourceFiles());
		System.out.println("generated.source.bytes\t" + report.sourceBytes());
		System.out.println("generated.source.lines\t" + report.sourceLines());
		System.out.println("generated.reader.classes\t" + report.readerClasses());
		System.out.println("generated.reader.class.bytes\t" + report.classBytes());
		System.out.println("generated.reader.methods\t" + report.methods());
		System.out.println("generated.reader.code.bytes\t" + report.codeBytes());
		System.out.println("generated.reader.max.method.bytes\t" + report.maxMethodBytes());
		System.out.println("bound.v0.classes.verified\t" + report.boundClasses());
	}

	public static Report inspectAndVerify(Path sourceRoot) throws IOException {
		long sourceFiles = 0;
		long sourceBytes = 0;
		long sourceLines = 0;
		if (Files.isDirectory(sourceRoot)) {
			try (Stream<Path> files = Files.walk(sourceRoot)) {
				for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					sourceFiles++;
					sourceBytes += Files.size(source);
					try (Stream<String> lines = Files.lines(source)) {
						sourceLines += lines.count();
					}
				}
			}
		}

		long classBytes = 0;
		long methods = 0;
		long codeBytes = 0;
		int maxMethodBytes = 0;
		int readerClasses = 0;
		int boundClasses = 0;
		for (BaseType type : BaseType.values()) {
			String simpleName = type.name();
			ClassMetrics plan = inspectClass(FIXTURE + "/current/readers/" + simpleName + "ReadPlan.class");
			classBytes += plan.classBytes();
			methods += plan.methods();
			codeBytes += plan.codeBytes();
			maxMethodBytes = Math.max(maxMethodBytes, plan.maxMethodBytes());
			readerClasses++;

			String boundResource = CURRENT_VERSION + "$" + simpleName + "V0Reader.class";
			ClassMetrics bound = inspectClass(boundResource);
			verifyBoundReader(simpleName, bound);
			classBytes += bound.classBytes();
			methods += bound.methods();
			codeBytes += bound.codeBytes();
			maxMethodBytes = Math.max(maxMethodBytes, bound.maxMethodBytes());
			readerClasses++;
			boundClasses++;
		}
		if (maxMethodBytes > MAX_HOT_METHOD_BYTES) {
			throw new IllegalStateException("Generated hot method exceeds " + MAX_HOT_METHOD_BYTES
					+ " bytes: " + maxMethodBytes);
		}
		return new Report(sourceFiles, sourceBytes, sourceLines, readerClasses, classBytes, methods,
				codeBytes, maxMethodBytes, boundClasses);
	}

	private static void verifyBoundReader(String simpleName, ClassMetrics bound) {
		if (bound.opcodes().contains(Opcode.INVOKEDYNAMIC)) {
			throw new IllegalStateException(simpleName + " bound reader contains invokedynamic");
		}
		if (bound.opcodes().contains(Opcode.TABLESWITCH) || bound.opcodes().contains(Opcode.LOOKUPSWITCH)) {
			throw new IllegalStateException(simpleName + " bound reader dispatches with a switch");
		}
		String planOwner = FIXTURE + "/current/readers/" + simpleName + "ReadPlan";
		Set<String> requiredCursors = Set.of(
				"Lit/cavallium/buffer/HeapBufDataCursor;",
				"Lit/cavallium/buffer/MemorySegmentBufDataCursor;",
				"Lit/cavallium/buffer/FallbackBufDataCursor;");
		Set<String> found = new HashSet<>();
		for (StaticCall call : bound.staticCalls()) {
			if (!call.owner().equals(planOwner) || !call.name().equals("readV0")) continue;
			for (String cursor : requiredCursors) {
				if (call.descriptor().contains(cursor)) found.add(cursor);
			}
		}
		if (!found.equals(requiredCursors)) {
			throw new IllegalStateException(simpleName + " bound reader lacks direct V0 storage kernels: "
					+ found);
		}
	}

	private static ClassMetrics inspectClass(String resource) throws IOException {
		byte[] bytes;
		try (InputStream input = GeneratedCodeShapeReport.class.getClassLoader().getResourceAsStream(resource)) {
			if (input == null) throw new IOException("Missing generated class resource: " + resource);
			bytes = input.readAllBytes();
		}
		var model = ClassFile.of().parse(bytes);
		long codeBytes = 0;
		int maxMethodBytes = 0;
		var opcodes = new HashSet<Opcode>();
		var staticCalls = new ArrayList<StaticCall>();
		for (var method : model.methods()) {
			var code = method.findAttribute(Attributes.code());
			if (code.isEmpty()) continue;
			int length = code.orElseThrow().codeLength();
			codeBytes += length;
			maxMethodBytes = Math.max(maxMethodBytes, length);
			code.orElseThrow().elementStream()
					.filter(Instruction.class::isInstance)
					.map(Instruction.class::cast)
					.forEach(instruction -> {
						opcodes.add(instruction.opcode());
						if (instruction instanceof InvokeInstruction invoke
								&& invoke.opcode() == Opcode.INVOKESTATIC) {
							staticCalls.add(new StaticCall(invoke.owner().asInternalName(),
									invoke.name().stringValue(), invoke.type().stringValue()));
						}
					});
		}
		return new ClassMetrics(bytes.length, model.methods().size(), codeBytes, maxMethodBytes,
				Set.copyOf(opcodes), List.copyOf(staticCalls));
	}

	public record Report(long sourceFiles, long sourceBytes, long sourceLines, int readerClasses,
			long classBytes, long methods, long codeBytes, int maxMethodBytes, int boundClasses) {}

	private record ClassMetrics(int classBytes, int methods, long codeBytes, int maxMethodBytes,
			Set<Opcode> opcodes, List<StaticCall> staticCalls) {}

	private record StaticCall(String owner, String name, String descriptor) {}
}
