#!/usr/bin/env bash
# Flatten every .mzn (with each matching .dzn) in a corpus via MiniZinc + the
# klause solver config, then check whether any constraint in the resulting .fzn
# is missing from klause's supported-builtins set. Reports a CSV per instance:
#
#   path,status,unsupported_names
#
# status is one of:
#   - ok          : all constraints map to native klause factors
#   - decomposed  : at least one constraint name is NOT in klause's dispatcher
#                   (i.e. MiniZinc stdlib decomposed something we don't support;
#                   the unsupported names are listed in column 3)
#   - mzn-error   : minizinc failed to flatten (syntax / type / search / data)
#   - timeout     : flattening exceeded the per-instance flatten budget
#
# Reusable across corpora — pass the root directory as $1.
#
# Usage:
#   verify-corpus.sh CORPUS_ROOT [FLATTEN_TIMEOUT_S]
#
# Examples:
#   verify-corpus.sh klause-bench/build/mzn/minizinc-benchmarks
#   verify-corpus.sh /tmp/libminizinc-tests 30
#
# Output: CSV on stdout; summary counts on stderr.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOLVER_MSC="$REPO_ROOT/klause-mzn-lib/share/minizinc/solvers/klause.msc"
SUPPORTED_FILE="$(mktemp)"
trap 'rm -f "$SUPPORTED_FILE"' EXIT
"$REPO_ROOT/klause-mzn-lib/scripts/extract-supported-builtins.sh" > "$SUPPORTED_FILE"

CORPUS="${1:-}"
FLATTEN_TIMEOUT_S="${2:-20}"
if [[ -z "$CORPUS" || ! -d "$CORPUS" ]]; then
  echo "verify-corpus: corpus root not found: $CORPUS" >&2
  echo "usage: verify-corpus.sh CORPUS_ROOT [FLATTEN_TIMEOUT_S]" >&2
  exit 2
fi

if ! command -v minizinc >/dev/null 2>&1; then
  echo "verify-corpus: minizinc not in PATH" >&2
  exit 2
fi

# Counts for the summary footer.
ok_count=0
decomposed_count=0
mzn_error_count=0
timeout_count=0
total=0
# Track all distinct unsupported predicate names across the run.
unsupported_index="$(mktemp)"
trap 'rm -f "$SUPPORTED_FILE" "$unsupported_index"' EXIT

# Iterate problem dirs: each directory under CORPUS holds .mzn files + .dzn data
# files. Pair each .mzn with each .dzn that lives alongside it. If no .dzn, the
# .mzn is run alone.
echo "path,status,unsupported_names"

while IFS= read -r -d '' mzn; do
  dir="$(dirname "$mzn")"
  # Match data files in the same directory.
  shopt -s nullglob
  dzns=("$dir"/*.dzn)
  shopt -u nullglob
  if (( ${#dzns[@]} == 0 )); then
    pairs=("")
  elif [[ "${PAIR_ALL_DZN:-0}" == "1" ]]; then
    pairs=("${dzns[@]}")
  else
    # Default: pair each .mzn with the smallest .dzn — the constraint set is the
    # same across data instances (only sizes vary), so one instance is enough to
    # exercise the constraint-coverage path. Set PAIR_ALL_DZN=1 to fully sweep.
    smallest="$(ls -S "${dzns[@]}" | tail -1)"
    pairs=("$smallest")
  fi

  for dzn in "${pairs[@]}"; do
    rel="${mzn#$CORPUS/}"
    if [[ -n "$dzn" ]]; then
      rel="$rel+$(basename "$dzn" .dzn)"
    fi
    total=$((total + 1))
    fzn="$(mktemp --suffix=.fzn)"
    err="$(mktemp)"
    # MiniZinc 2.9 wants `-c` to compile to FZN (no solve). `--no-output-ozn`
    # speeds it up since we're not running the solver here.
    args=(--solver "$SOLVER_MSC" -c "$mzn" -o "$fzn" --no-output-ozn)
    [[ -n "$dzn" ]] && args+=("$dzn")
    if timeout "${FLATTEN_TIMEOUT_S}s" minizinc "${args[@]}" >"$err" 2>&1; then
      :
    else
      rc=$?
      if (( rc == 124 )); then
        echo "$rel,timeout,"
        timeout_count=$((timeout_count + 1))
        rm -f "$fzn" "$err"
        continue
      fi
      # MiniZinc itself failed.
      echo "$rel,mzn-error,"
      mzn_error_count=$((mzn_error_count + 1))
      rm -f "$fzn" "$err"
      continue
    fi
    rm -f "$err"
    # Extract the unique constraint identifiers from the .fzn:
    #   constraint <name>(...)
    used="$(grep -oE '^constraint[[:space:]]+[a-zA-Z_][a-zA-Z_0-9]*' "$fzn" \
              | awk '{ print $2 }' | sort -u)"
    # Difference: names in .fzn not in supported set.
    bad="$(comm -23 <(echo "$used") "$SUPPORTED_FILE" | paste -sd';' -)"
    if [[ -z "$bad" ]]; then
      echo "$rel,ok,"
      ok_count=$((ok_count + 1))
    else
      echo "$rel,decomposed,$bad"
      decomposed_count=$((decomposed_count + 1))
      tr ';' '\n' <<<"$bad" >> "$unsupported_index"
    fi
    rm -f "$fzn"
  done
done < <(find "$CORPUS" -name '*.mzn' -print0)

{
  echo ""
  echo "=== summary ==="
  echo "total:       $total"
  echo "ok:          $ok_count"
  echo "decomposed:  $decomposed_count"
  echo "mzn-error:   $mzn_error_count"
  echo "timeout:     $timeout_count"
  if [[ -s "$unsupported_index" ]]; then
    echo ""
    echo "=== top unsupported predicate names ==="
    sort "$unsupported_index" | uniq -c | sort -rn | head -40
  fi
} >&2
