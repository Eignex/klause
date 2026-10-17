#!/bin/bash
set -euo pipefail

work=$1
driver=$2
out=$3
observer_cli=$4
observer_bench=$5
shift 5
mkdir -p "$out" "$work/tmp"
export DEBIAN_FRONTEND=noninteractive GRADLE_USER_HOME="$work/gradle"
export KLAUSE_CORPUS="$work/corpus" KLAUSE_BENCH_CORPUS_MAX_GB=off
unset JAVA_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS

uname -a > "$out/guest-uname.txt"
cat /proc/self/status > "$out/guest-process-status.txt"
java -XshowSettings:properties -version > "$out/guest-java.stdout" 2> "$out/guest-java.stderr"
test "$(uname -m)" = aarch64
grep -Eq '^[[:space:]]*java.runtime.version = 25[.]0[.]4[.]1[+]1([-+.]|$)' "$out/guest-java.stderr"
apt-get update > "$out/packages-update.stdout" 2> "$out/packages-update.stderr"
apt-get install -y --no-install-recommends \
    ca-certificates curl git libopenblas-dev minizinc python3 python3-venv sudo time util-linux zstd unzip xz-utils bzip2 \
    > "$out/packages-install.stdout" 2> "$out/packages-install.stderr"
rm -rf /var/lib/apt/lists/*
dpkg-query -W > "$out/guest-packages.tsv"
command -v taskset minizinc python3 jfr > "$out/guest-tools.txt"
/usr/bin/time --version > "$out/guest-time.txt"
taskset -c 0 /bin/true

# The complete original SDK remains available after the owned container is removed.
tar -C "$JAVA_HOME" -czf "$out/private-runtime-sdk.tar.gz" .
sha256sum "$out/private-runtime-sdk.tar.gz" > "$out/private-runtime-sdk.sha256"
git init "$work/driver"
git -C "$work/driver" remote add origin https://github.com/Eignex/klause.git
git -C "$work/driver" fetch --depth=1 origin "$driver"
git -C "$work/driver" checkout --detach FETCH_HEAD
test "$(git -C "$work/driver" rev-parse HEAD)" = "$driver"
mkdir -p "$work/driver/klause-cli/build/install/klause-cli-jvm"
mkdir -p "$work/driver/klause-bench/build/install/klause-bench"
cp -a "$observer_cli/." "$work/driver/klause-cli/build/install/klause-cli-jvm/"
cp -a "$observer_bench/." "$work/driver/klause-bench/build/install/klause-bench/"
python3 -m venv "$work/checker"
"$work/checker/bin/python" -m pip download --dest "$work/wheels" \
    -r "$work/driver/klause-bench/tools/requirements-source-checks.txt" \
    > "$out/wheels.stdout" 2> "$out/wheels.stderr"
export PIP_NO_INDEX=1 PIP_FIND_LINKS="$work/wheels"
export OUT="$out/study"
mkdir -p "$OUT"
java -Xmx512m --enable-preview --enable-native-access=ALL-UNNAMED \
    --add-modules=jdk.incubator.vector \
    "-Djava.io.tmpdir=$work/tmp" \
    "-Dklause.bench.corpusCache=$work/corpus" \
    "-Dklause.workspace.root=$work/driver" \
    -cp "$work/driver/klause-bench/build/install/klause-bench/lib/*" \
    com.eignex.klause.bench.profile.CliCompilationStudy "$@"
