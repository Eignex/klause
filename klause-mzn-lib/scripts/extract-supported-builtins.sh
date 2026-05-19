#!/usr/bin/env bash
# Extract the set of FlatZinc builtin names klause's compiler dispatches on.
# Reads FlatZincConstraints.kt's `processConstraint` `when` arms and prints one
# constraint name per line (sorted, deduped). Used by verify-corpus.sh.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$REPO_ROOT/klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincConstraints.kt"

awk '
  /^internal fun FlatZincCompiler\.processConstraint/ { inside = 1; next }
  inside && /^}/ { inside = 0 }
  inside { print }
' "$SRC" \
  | grep -oE '"[a-z][a-z_0-9]*"' \
  | tr -d '"' \
  | sort -u
