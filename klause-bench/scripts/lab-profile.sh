#!/usr/bin/env bash
set -euo pipefail
: "${LAB_WORKTREE:?lab source root is required}"
: "${LAB_CORPUS:?lab corpus root is required}"
: "${OUT:?lab output directory is required}"
debug=true cpus=1 heap=2560
while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug=*) debug="${1#*=}"; shift ;;
    --cpus=*) cpus="${1#*=}"; shift ;;
    --cli-heap-mib=*) heap="${1#*=}"; shift ;;
    *) break ;;
  esac
done
[[ "$debug" == true || "$debug" == false ]] || exit 2
[[ "$cpus" =~ ^[1-9][0-9]*$ && "$heap" =~ ^[1-9][0-9]*$ ]] || exit 2
export KLAUSE_DEBUG="$debug"
export KLAUSE_CLI_OPTS="-Xmx${heap}m -XX:ActiveProcessorCount=$cpus"
export KLAUSE_CLI_PREFLIGHT_OPTS="$KLAUSE_CLI_OPTS"
[[ $# -ge 1 ]] || { echo 'usage: lab-profile.sh <main> [args...]' >&2; exit 2; }
export KLAUSE_CORPUS="$LAB_CORPUS"
export KLAUSE_CLI="$LAB_WORKTREE/klause-cli/build/install/klause-cli-jvm/bin/klause-cli"
export KLAUSE_BENCH="$LAB_WORKTREE/klause-bench/build/install/klause-bench/bin/klause-bench"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx512m "-XX:ActiveProcessorCount=$cpus" --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
  "-Dklause.bench.corpusCache=$LAB_CORPUS" "-Dklause.workspace.root=$LAB_WORKTREE" \
  "-XX:StartFlightRecording=filename=$OUT/profile.jfr,settings=profile,dumponexit=true" \
  -cp "$LAB_WORKTREE/klause-bench/build/install/klause-bench/lib/*" "$@"
