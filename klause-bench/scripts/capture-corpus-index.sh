#!/usr/bin/env bash
set -euo pipefail

: "${LAB_WORKTREE:?lab profile worktree is required}"
: "${OUT:?lab profile output directory is required}"

cd "$LAB_WORKTREE"
mkdir -p "$OUT"
capture_cache=$(mktemp -d "${OUT%/*}/corpus-index.XXXXXX")
capture_cache=$(realpath -- "$capture_cache")
trap 'rm -rf -- "$capture_cache"' EXIT
export LAB_CORPUS_KEEP_PACKED=true

java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
jvm=(
  --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector
  -Xmx512m -XX:ActiveProcessorCount=1
  "-Dklause.workspace.root=$LAB_WORKTREE"
  "-Dklause.bench.corpusCache=$capture_cache"
  -Dklause.bench.corpus.shared=false -Dklause.bench.declaredInputs=true
  -cp "$LAB_WORKTREE/klause-bench/build/install/klause-bench/lib/*"
)

"$java" "${jvm[@]}" com.eignex.klause.bench.target.BenchCli \
  select "$@" files=true features=false > "$OUT/selection.jsonl"
test -s "$OUT/selection.jsonl"
"$java" "${jvm[@]}" com.eignex.klause.bench.source.CommittedCorpusIndexes \
  "$capture_cache" "$OUT" --selection "$OUT/selection.jsonl"
"$java" "${jvm[@]}" com.eignex.klause.bench.target.BenchCli \
  provenance "out=$OUT/provenance.json"
