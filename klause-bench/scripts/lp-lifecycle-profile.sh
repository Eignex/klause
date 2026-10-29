#!/usr/bin/env bash
set -euo pipefail
: "${LAB_WORKTREE:?lab source root is required}"
: "${OUT:?lab output directory is required}"
trace="${1:?usage: lp-lifecycle-profile.sh mps-afiro|mps-adlittle [repeats] [both|jvm|native]}"
repeats="${2:-3}"
runtime="${3:-both}"
[[ "$trace" == mps-afiro || "$trace" == mps-adlittle ]] || exit 2
[[ "$repeats" =~ ^[1-5]$ ]] || exit 2
[[ "$runtime" == both || "$runtime" == jvm || "$runtime" == native ]] || exit 2
[[ "$(uname -s)/$(uname -m)" == Linux/x86_64 ]] || exit 2
manifest="$LAB_WORKTREE/klause/src/jvmTest/resources/basis-corpus/manifest.json"
fixture="$(sed -n "/\"id\": \"$trace\"/,/^    }/p" "$manifest")"
source_identity="$(printf '%s\n' "$fixture" | sed -n 's/^ *"source": "\([^"]*\)".*/\1/p')"
expected="$(printf '%s\n' "$fixture" | sed -n 's/^ *"sourceSha256": "\([^"]*\)".*/\1/p')"
source_path="${source_identity%@*}"
source_revision="${source_identity##*@}"
[[ "$source_path" == "HiGHS/check/instances/${trace#mps-}.mps" ]] || exit 2
[[ "$source_revision" =~ ^[a-f0-9]{40}$ && "$expected" =~ ^[a-f0-9]{64}$ ]] || exit 2
mkdir -p "$OUT"
cp "$manifest" "$OUT/basis-manifest.json"
curl -fsSL --max-time 30 "https://raw.githubusercontent.com/ERGO-Code/HiGHS/$source_revision/${source_path#HiGHS/}" -o "$OUT/source.mps"
printf '%s  %s\n' "$expected" "$OUT/source.mps" | sha256sum --check
source64="$(base64 < "$OUT/source.mps" | tr -d '\n')"
args=("source64=$source64" "source=$source_identity" "sha256=$expected" "repeats=$repeats")
git -C "$LAB_WORKTREE" rev-parse HEAD > "$OUT/revision.txt"
uname -a > "$OUT/platform.txt"
if command -v lscpu >/dev/null; then lscpu > "$OUT/cpu.txt"; fi
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -version > "$OUT/java-version.txt" 2>&1
if [[ "$runtime" != native ]]; then
  find "$LAB_WORKTREE/klause/build/lp-profile/jvm" -type f -print0 | sort -z | xargs -0 sha256sum > "$OUT/jvm-sha256.txt"
  /usr/bin/time -v -o "$OUT/jvm-resources.txt" \
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx1536m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  -Xlog:jfr+startup=off \
  --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
  "-XX:StartFlightRecording=filename=$OUT/lifecycle.jfr,settings=profile,dumponexit=true" \
  -cp "$LAB_WORKTREE/klause/build/lp-profile/jvm/classes:$LAB_WORKTREE/klause/build/lp-profile/jvm/lib/*" \
  com.eignex.klause.bench.profile.LpLifecycleProfileKt "${args[@]}" > "$OUT/jvm.ndjson"
  "${JAVA_HOME:+$JAVA_HOME/bin/}jfr" print --json --events jdk.ObjectAllocationSample "$OUT/lifecycle.jfr" > "$OUT/allocations.json"
fi
if [[ "$runtime" != jvm ]]; then
  native="$LAB_WORKTREE/klause/build/bin/linuxX64/lpProfileReleaseExecutable/klause-lp-profile.kexe"
  sha256sum "$native" > "$OUT/native-sha256.txt"
  /usr/bin/time -v -o "$OUT/native-resources.txt" "$native" "${args[@]}" > "$OUT/native.ndjson"
fi
