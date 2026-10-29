#!/usr/bin/env bash
set -euo pipefail
: "${LAB_WORKTREE:?lab source root is required}"
: "${OUT:?lab output directory is required}"
source_name="${1:?source fixture is required}"
case "$source_name" in
  cover-2x3|cover-3x3|dense-2x12|dense-8x24) ;;
  *) exit 2 ;;
esac
source_path="$LAB_WORKTREE/klause-bench/smoke-corpus/lp-component-split/$source_name.mps"
source_hash="$(sha256sum "$source_path" | cut -d ' ' -f 1)"
mkdir -p "$OUT"
git -C "$LAB_WORKTREE" rev-parse HEAD > "$OUT/revision.txt"
uname -a > "$OUT/platform.txt"
if command -v lscpu >/dev/null; then lscpu > "$OUT/cpu.txt"; fi
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -version > "$OUT/java-version.txt" 2>&1
find "$LAB_WORKTREE/klause/build/lp-profile/jvm" -type f -print0 | sort -z | xargs -0 sha256sum > "$OUT/jvm-sha256.txt"
exec /usr/bin/time -v -o "$OUT/resources.txt" \
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
  "-XX:StartFlightRecording=filename=$OUT/profile.jfr,settings=profile,dumponexit=true" \
  -cp "$LAB_WORKTREE/klause/build/lp-profile/jvm/classes:$LAB_WORKTREE/klause/build/lp-profile/jvm/lib/*" \
  com.eignex.klause.bench.profile.LpTemplatePreparationProfile "$source_path" "$source_hash" "${2:-3}" "${3:-32}"
