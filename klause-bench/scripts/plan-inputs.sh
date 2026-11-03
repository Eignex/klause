#!/usr/bin/env bash
set -euo pipefail
: "${LAB_WORKTREE:?lab source root is required}"
: "${LAB_CORPUS:?lab corpus directory is required}"
: "${OUT:?lab output directory is required}"
[[ $# -eq 1 ]]
cd "$LAB_WORKTREE"
mkdir -p "$OUT"
jq -ce 'select(type == "object" and (.parameters | type == "object"))' <<< "$1" > "$OUT/request.json.new"
mv "$OUT/request.json.new" "$OUT/request.json"
LAB_DECLARED_INPUTS=true "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx512m \
  --enable-native-access=ALL-UNNAMED --add-modules=jdk.incubator.vector \
  -cp "$LAB_WORKTREE/klause-bench/build/install/klause-bench/lib/*" \
  com.eignex.klause.bench.lab.ReferenceAdapterKt plan "$OUT/request.json" > "$OUT/plan.json.new"
jq -e '.cases | length > 0' "$OUT/plan.json.new" > /dev/null
mv "$OUT/plan.json.new" "$OUT/plan.json"
jq '{plannedCases: (.cases | length), solverExecuted: false}' "$OUT/plan.json"
