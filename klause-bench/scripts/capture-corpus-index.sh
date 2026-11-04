#!/usr/bin/env bash
set -euo pipefail

: "${LAB_WORKTREE:?lab profile worktree is required}"
: "${OUT:?lab profile output directory is required}"

cd "$LAB_WORKTREE"
mkdir -p "$OUT/inputs"
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
jq -s -e 'length > 0 and all(.[]; (.collection|type=="string" and length>0) and
  (.model|type=="string" and length>0))' "$OUT/selection.jsonl" > /dev/null
mapfile -t collections < <(jq -r '.collection' "$OUT/selection.jsonl" | sort -u)
"$java" "${jvm[@]}" com.eignex.klause.bench.source.CommittedCorpusIndexes \
  "$capture_cache" "$OUT" "${collections[@]}"

: > "$OUT/selected-source-hashes.tsv"
while IFS=$'\t' read -r collection problem model; do
  model=$(realpath -- "$model")
  case "$model" in "$capture_cache/"*) ;; *) echo "source outside capture cache: $model" >&2; exit 1 ;; esac
  relative=${model#"$capture_cache/"}
  mkdir -p "$OUT/inputs/$(dirname -- "$relative")"
  cp -- "$model" "$OUT/inputs/$relative"
  read -r sha _ < <(sha256sum -- "$OUT/inputs/$relative")
  bytes=$(wc -c < "$OUT/inputs/$relative")
  printf '%s\t%s\t%s\t%s\t%s\n' "$collection" "$problem" "$sha" "$bytes" "inputs/$relative" \
    >> "$OUT/selected-source-hashes.tsv"
done < <(jq -r '[.collection,.problem,.model]|@tsv' "$OUT/selection.jsonl")

for collection in "${collections[@]}"; do
  test -s "$OUT/klause-bench/corpus-index/$collection.tsv.gz"
done
"$java" "${jvm[@]}" com.eignex.klause.bench.target.BenchCli \
  provenance "out=$OUT/provenance.json"
