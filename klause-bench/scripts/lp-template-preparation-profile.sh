#!/usr/bin/env bash
set -euo pipefail
: "${LAB_WORKTREE:?lab source root is required}"
: "${OUT:?lab output directory is required}"
: "${JAVA_HOME:?lab JDK root is required}"
source_name="${1:?source fixture is required}"
mkdir -p "$OUT"
case "$source_name" in
  cover-2x3|cover-3x3)
    source_argument="$LAB_WORKTREE/klause-bench/smoke-corpus/lp-component-split/$source_name.mps"
    source_hash="$(sha256sum "$source_argument" | cut -d ' ' -f 1)"
    ;;
  miplib3-p0033|miplib3-p0201)
    source_problem="${source_name#miplib3-}"
    bench="$LAB_WORKTREE/klause-bench/build/install/klause-bench/bin/klause-bench"
    [[ -x "$bench" ]] || { echo ':klause-bench:installDist is required' >&2; exit 2; }
    JAVA_OPTS='-Xmx512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC' \
      /usr/bin/time -v -o "$OUT/selection-resources.txt" "$bench" select \
      suite=miplib3 "name=$source_problem" max=1 files=true > "$OUT/selection.jsonl"
    source_argument="selection=$OUT/selection.jsonl"
    case "$source_problem" in
      p0033) source_hash=8ccff819023237c79ef32e238a5da9348725ce9a4425d48888baf3a0b3b42628 ;;
      p0201) source_hash=8352d7f121289185f443fdc67080fa9de01e5b9bf11b0bf41087fba4277c07a4 ;;
    esac
    ;;
  *) exit 2 ;;
esac
git -C "$LAB_WORKTREE" rev-parse HEAD > "$OUT/revision.txt"
uname -a > "$OUT/platform.txt"
if command -v lscpu >/dev/null; then lscpu > "$OUT/cpu.txt"; fi
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -version > "$OUT/java-version.txt" 2>&1
/usr/bin/time -v -o "$OUT/runtime-identity-resources.txt" bash -c \
  'set -euo pipefail; find -H "$1" -type f -print0 | sort -z | xargs -0 sha256sum' _ "$JAVA_HOME" \
  > "$OUT/runtime-sha256.txt"
find "$LAB_WORKTREE/klause/build/lp-profile/jvm" -type f -print0 | sort -z | xargs -0 sha256sum > "$OUT/jvm-sha256.txt"
profile_status=0
/usr/bin/time -v -o "$OUT/resources.txt" \
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC \
  --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
  "-XX:StartFlightRecording=filename=$OUT/profile.jfr,settings=profile,dumponexit=true" \
  -cp "$LAB_WORKTREE/klause/build/lp-profile/jvm/classes:$LAB_WORKTREE/klause/build/lp-profile/jvm/lib/*" \
  com.eignex.klause.bench.profile.LpTemplatePreparationProfile "$source_argument" "$source_hash" \
  "${2:-3}" "${3:-32}" "${4:-standalone}" || profile_status=$?
printf '%s\n' "$profile_status" > "$OUT/numerical.exit"
analysis_status=0
JAVA_TOOL_OPTIONS='-Xmx512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC' \
  /usr/bin/time -v -o "$OUT/allocation-analysis-resources.txt" \
  "$JAVA_HOME/bin/jfr" print --json --events jdk.ObjectAllocationSample "$OUT/profile.jfr" \
  > "$OUT/allocations.json" || analysis_status=$?
printf '%s\n' "$analysis_status" > "$OUT/allocation-analysis.exit"
activity_status=0
JAVA_TOOL_OPTIONS='-Xmx512m -XX:ActiveProcessorCount=1 -XX:+UseSerialGC' \
  /usr/bin/time -v -o "$OUT/activity-analysis-resources.txt" \
  "$JAVA_HOME/bin/jfr" print --json \
  --events jdk.ExecutionSample,jdk.GarbageCollection,jdk.Compilation,jdk.ThreadPark "$OUT/profile.jfr" \
  > "$OUT/activity.json" || activity_status=$?
printf '%s\n' "$activity_status" > "$OUT/activity-analysis.exit"
if [[ "$profile_status" -ne 0 ]]; then exit "$profile_status"; fi
if [[ "$analysis_status" -ne 0 ]]; then exit "$analysis_status"; fi
exit "$activity_status"
