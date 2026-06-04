#!/usr/bin/env bash
# Run klause against every problem in the downloaded MiniZinc-benchmarks corpus.
# Classifies each instance into {solved, unsat, unknown, timeout, unsupported,
# parse-error, mzn-error, other}. Emits a CSV to stdout and a summary to stderr.
#
# Run: ./klause-mzn-lib/scripts/sweep.sh [timeout-seconds]
#
# Prereqs:
#   - MiniZinc installed (`minizinc --version`).
#   - `./gradlew :klause-bench:downloadMzn` already run.
#   - `./gradlew :klause-cli:installJvmDist` already run.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CORPUS="$REPO_ROOT/klause-bench/build/mzn/minizinc-benchmarks"
SOLVER_MSC="$REPO_ROOT/klause-mzn-lib/share/minizinc/solvers/klause.msc"
TIMEOUT_S="${1:-30}"

if [[ ! -d "$CORPUS" ]]; then
  echo "sweep: corpus not found at $CORPUS" >&2
  echo "sweep: run './gradlew :klause-bench:downloadMzn' first" >&2
  exit 2
fi

if ! command -v minizinc >/dev/null 2>&1; then
  echo "sweep: minizinc not in PATH" >&2
  exit 2
fi

count_solved=0
count_unsat=0
count_unknown=0
count_timeout=0
count_unsupported=0
count_parse_error=0
count_mzn_error=0
count_other=0
total=0

echo "problem,instance,outcome,elapsed_s,detail"

for prob_dir in "$CORPUS"/*/; do
  prob="$(basename "$prob_dir")"
  # Skip non-problem dirs.
  [[ "$prob" == "LICENSE" || "$prob" == "README"* ]] && continue
  mzn_files=( "$prob_dir"*.mzn )
  [[ -e "${mzn_files[0]}" ]] || continue
  mzn="${mzn_files[0]}"

  # Pick the smallest .dzn (if any).
  dzn_files=( "$prob_dir"*.dzn )
  if [[ -e "${dzn_files[0]}" ]]; then
    # smallest by size
    dzn=$(ls -S "$prob_dir"*.dzn 2>/dev/null | tail -1)
    instance="$(basename "$dzn")"
    invocation=( "$mzn" "$dzn" )
  else
    dzn=""
    instance="(no-data)"
    invocation=( "$mzn" )
  fi

  total=$((total + 1))
  start=$(date +%s)
  out=$(timeout "$TIMEOUT_S" minizinc --solver "$SOLVER_MSC" "${invocation[@]}" 2>&1)
  rc=$?
  end=$(date +%s)
  elapsed=$((end - start))

  outcome="other"
  detail=""
  case "$rc" in
    124)
      outcome="timeout"
      ;;
    *)
      if grep -q "=====UNSATISFIABLE=====" <<<"$out"; then
        outcome="unsat"
      elif grep -q "=====UNKNOWN=====" <<<"$out"; then
        outcome="unknown"
      elif grep -q "==========" <<<"$out"; then
        outcome="solved"
      elif grep -q "unsupported FlatZinc builtin" <<<"$out"; then
        outcome="unsupported"
        detail=$(grep -oE "builtin \`[a-z_0-9]+\`" <<<"$out" | head -1 | tr -d '`' | sed 's/builtin //')
      elif grep -qE "FlatZincParseException|FlatZinc.*error" <<<"$out"; then
        outcome="parse-error"
        detail=$(grep -E "FlatZincParseException" <<<"$out" | head -1 | sed -E 's/.*Exception: //' | head -c 80 | tr ',\n' ' ')
      elif grep -qE "MiniZinc:" <<<"$out"; then
        outcome="mzn-error"
        detail=$(grep "MiniZinc:" <<<"$out" | head -1 | head -c 80 | tr ',\n' ' ')
      fi
      ;;
  esac

  case "$outcome" in
    solved) count_solved=$((count_solved + 1)) ;;
    unsat) count_unsat=$((count_unsat + 1)) ;;
    unknown) count_unknown=$((count_unknown + 1)) ;;
    timeout) count_timeout=$((count_timeout + 1)) ;;
    unsupported) count_unsupported=$((count_unsupported + 1)) ;;
    parse-error) count_parse_error=$((count_parse_error + 1)) ;;
    mzn-error) count_mzn_error=$((count_mzn_error + 1)) ;;
    other) count_other=$((count_other + 1)) ;;
  esac

  # CSV row. Quote detail because it may contain commas.
  printf "%s,%s,%s,%d,\"%s\"\n" "$prob" "$instance" "$outcome" "$elapsed" "$detail"
done

{
  echo ""
  echo "=== sweep summary (total=$total, per-instance timeout=${TIMEOUT_S}s) ==="
  printf "  solved      : %3d\n" "$count_solved"
  printf "  unsat       : %3d\n" "$count_unsat"
  printf "  unknown     : %3d\n" "$count_unknown"
  printf "  timeout     : %3d\n" "$count_timeout"
  printf "  unsupported : %3d  (klause FZN compiler doesn't know the predicate)\n" "$count_unsupported"
  printf "  parse-error : %3d  (other klause-side failure)\n" "$count_parse_error"
  printf "  mzn-error   : %3d  (MiniZinc compilation failed before reaching klause)\n" "$count_mzn_error"
  printf "  other       : %3d\n" "$count_other"
} >&2
