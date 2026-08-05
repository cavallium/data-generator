#!/usr/bin/env bash
set -euo pipefail

benchmark_dir="$(cd "$(dirname "$0")" && pwd)"
repository_dir="$(cd "$benchmark_dir/.." && pwd)"
requested_args=("$@")
variant="scalar"
profiles="benchmark"
java_module_args=()
if [[ "${DATAGEN_VECTOR:-0}" == "1" ]]; then
	variant="vector"
	profiles="benchmark,vector"
	java_module_args=(--add-modules jdk.incubator.vector)
fi
report_name="${DATAGEN_REPORT_NAME:-$variant}"
report_root="${DATAGEN_REPORT_ROOT:-$benchmark_dir/reports}"
report_dir="$report_root/generated-reader-reports-$report_name"
benchmark_jar="$benchmark_dir/target/generated-normal-reader-benchmarks.jar"
timing_file="$(mktemp "${TMPDIR:-/tmp}/datagen-benchmark-time.XXXXXX")"

write_checksums() {
	[[ -d "$report_dir" ]] || return
	(
		cd "$report_dir"
		for artifact in *; do
			[[ -f "$artifact" && "$artifact" != "SHA256SUMS" ]] || continue
			sha256sum "$artifact"
		done
	) > "$report_dir/SHA256SUMS"
}

finalize_report() {
	exit_status=$?
	trap - EXIT
	if [[ -f "$timing_file" ]]; then
		mv "$timing_file" "$report_dir/build-time.txt"
	fi
	write_checksums
	exit "$exit_status"
}
trap finalize_report EXIT

if [[ -e "$report_dir" ]]; then
	printf 'Refusing to overwrite benchmark evidence: %s\n' "$report_dir" >&2
	exit 2
fi
mkdir -p "$report_dir"

benchmark_filter="${1:-it.cavallium.datagen.benchmark.Generated.*Bench}"
if (( $# > 0 )); then
	shift
fi

build_command=(mvn -q -P"$profiles" -pl datagen-benchmark -am clean verify)
jmh_vm_args=""
if [[ "${DATAGEN_LOG_COMPILATION:-0}" == "1" ]]; then
	jmh_vm_args="-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$report_dir/hotspot-%p.xml -XX:+PrintCodeCache"
fi
if [[ "$variant" == "vector" ]]; then
	jmh_vm_args="${jmh_vm_args:+$jmh_vm_args }--add-modules jdk.incubator.vector"
fi
jmh_command=(java "${java_module_args[@]}" -jar "$benchmark_jar" "$benchmark_filter" "$@"
	-prof gc
	-rf json
	-rff "$report_dir/jmh-results.json")
if [[ -n "$jmh_vm_args" ]]; then
	jmh_command+=(-jvmArgsAppend "$jmh_vm_args")
fi

print_command() {
	printf '%q ' "$@"
	printf '\n'
}

cd "$repository_dir"
tracked_diff_sha256="$(git diff --binary --no-ext-diff HEAD | sha256sum | awk '{print $1}')"
status_sha256="$(git status --porcelain=v1 -uall | sha256sum | awk '{print $1}')"
{
	printf 'variant=%s\n' "$variant"
	printf 'profiles=%s\n' "$profiles"
	printf 'git_head=%s\n' "$(git rev-parse HEAD)"
	printf 'tracked_diff_sha256=%s\n' "$tracked_diff_sha256"
	printf 'status_sha256=%s\n' "$status_sha256"
	printf 'requested_command='
	print_command "$0" "${requested_args[@]}"
	printf 'build_command='
	print_command "${build_command[@]}"
	printf 'jmh_command='
	print_command "${jmh_command[@]}"
	printf '\n[java-version]\n'
	java -version 2>&1
	printf '\n[cpu]\n'
	lscpu
	printf '\n[git-status]\n'
	git status --porcelain=v1 -uall
} > "$report_dir/metadata.txt"

if [[ "${DATAGEN_SKIP_BUILD:-0}" == "1" ]]; then
	[[ -f "$benchmark_jar" ]] || {
		printf 'Benchmark jar is missing while DATAGEN_SKIP_BUILD=1: %s\n' "$benchmark_jar" >&2
		exit 2
	}
	printf 'build_skipped=1\n' > "$timing_file"
elif [[ -x /usr/bin/time ]]; then
	/usr/bin/time -f 'elapsed_seconds=%e\nmax_rss_kib=%M' -o "$timing_file" \
		"${build_command[@]}"
else
	build_started_at=$SECONDS
	"${build_command[@]}"
	printf 'elapsed_seconds=%s\nmax_rss_kib=unavailable\n' \
		"$((SECONDS - build_started_at))" > "$timing_file"
fi

cd "$benchmark_dir"
mv "$timing_file" "$report_dir/build-time.txt"
java "${java_module_args[@]}" -cp "$benchmark_jar" \
	it.cavallium.datagen.benchmark.GeneratedCodeShapeReport \
	> "$report_dir/generated-code-shape.tsv"

"${jmh_command[@]}" \
	2>&1 | tee "$report_dir/jmh-console.log"
