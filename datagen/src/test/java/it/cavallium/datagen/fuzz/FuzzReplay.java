package it.cavallium.datagen.fuzz;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Curated regression seeds in, falsified seeds out.
 *
 * <p>Committed corpus: {@code fuzz/seeds/<id>.txt} on the <em>test classpath</em> (i.e.
 * {@code src/test/resources/fuzz/seeds/}), one decimal seed per line, {@code #} starting a comment,
 * blank lines ignored. A missing or unreadable corpus is not an error: it yields an empty array so a
 * test that also generates its own seeds keeps running.</p>
 *
 * <p>Recorded failures: appended to {@code target/fuzz-failures/<id>.txt}, which is build output and
 * deliberately not committed. Writing is best effort — this runs from a failing test's teardown path,
 * where a second exception would hide the real one.</p>
 */
public final class FuzzReplay {

	/** Directory of recorded failures, relative to the module's build directory. */
	private static final Path FAILURE_DIR = Path.of("target", "fuzz-failures");

	private FuzzReplay() {
	}

	/**
	 * Loads the committed regression seeds for {@code id}.
	 *
	 * @param id corpus name, without directory or {@code .txt} suffix
	 * @return the seeds of {@code fuzz/seeds/<id>.txt}; empty when the resource is absent, unreadable
	 * or holds no parseable line. Never throws.
	 */
	public static long[] replaySeeds(String id) {
		if (id == null || id.isEmpty()) {
			return new long[0];
		}
		List<Long> seeds = new ArrayList<>();
		try (InputStream in = FuzzReplay.class.getClassLoader().getResourceAsStream("fuzz/seeds/" + id + ".txt")) {
			if (in == null) {
				return new long[0];
			}
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
				String value = stripComment(line).trim();
				if (value.isEmpty()) {
					continue;
				}
				try {
					seeds.add(Long.parseLong(value));
				} catch (NumberFormatException ignored) {
					// a corrupt line is data, not a test failure: skip it
				}
			}
		} catch (IOException | RuntimeException ignored) {
			return new long[0];
		}
		long[] result = new long[seeds.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = seeds.get(i);
		}
		return result;
	}

	/**
	 * Appends one falsified case so the next run can replay it.
	 *
	 * @param id corpus name that also names the record file
	 * @param seed the seed that reproduced the failure
	 * @param detail free-form diagnosis, one line preferred; {@code null} is recorded as empty
	 */
	public static void recordFailure(String id, long seed, String detail) {
		String safeId = (id == null || id.isEmpty()) ? "unnamed" : sanitize(id);
		String line = seed + " # " + (detail == null ? "" : detail.replace('\r', ' ').replace('\n', ' '))
				+ System.lineSeparator();
		try {
			Files.createDirectories(FAILURE_DIR);
			Files.writeString(FAILURE_DIR.resolve(safeId + ".txt"), line, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException | RuntimeException ignored) {
			// never let diagnostics break the failing test they describe
		}
	}

	private static String stripComment(String line) {
		int hash = line.indexOf('#');
		return hash < 0 ? line : line.substring(0, hash);
	}

	/** Keeps the record file name inside its directory whatever the callsite passes as {@code id}. */
	private static String sanitize(String id) {
		StringBuilder sb = new StringBuilder(id.length());
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
		}
		return sb.toString();
	}
}
