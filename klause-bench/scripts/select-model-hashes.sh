#!/usr/bin/env bash
set -euo pipefail
: "${LAB_WORKTREE:?lab source root is required}"
: "${OUT:?lab output directory is required}"
cd "$LAB_WORKTREE"
mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx512m --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
  "-Dklause.workspace.root=$LAB_WORKTREE" -cp "$LAB_WORKTREE/klause-bench/build/install/klause-bench/lib/*" \
  com.eignex.klause.bench.target.BenchCli select "$@" files=true > "$OUT/selection.jsonl.new"
jq -se 'length > 0 and all(.[]; (.model | type == "string") and .data == null and
  ((.includeDirs // []) | length == 0))' "$OUT/selection.jsonl.new" > /dev/null
mv "$OUT/selection.jsonl.new" "$OUT/selection.jsonl"
while IFS= read -r selection_record; do
  model_path=$(jq -er '.model' <<< "$selection_record")
  [[ -f "$model_path" ]]
  plain_sha=$(sha256sum < "$model_path")
  plain_sha=${plain_sha%% *}
  plain_bytes=$(wc -c < "$model_path")
  jq -c --arg sha "$plain_sha" --argjson bytes "$plain_bytes" \
    '. + {plainSourceSha256: $sha, plainSourceBytes: $bytes}' <<< "$selection_record"
done < "$OUT/selection.jsonl" > "$OUT/source-hashes.jsonl.new"
jq -se 'length > 0 and all(.[]; (.plainSourceSha256 | test("^[a-f0-9]{64}$")) and
  .plainSourceBytes >= 0)' "$OUT/source-hashes.jsonl.new" > /dev/null
mv "$OUT/source-hashes.jsonl.new" "$OUT/source-hashes.jsonl"
jq -s '{selectedSources: length}' "$OUT/source-hashes.jsonl"
