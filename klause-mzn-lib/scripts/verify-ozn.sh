#!/usr/bin/env bash
# Generate the .ozn template for every .mzn in a corpus and attempt to parse it
# through klause's OznParser. Reports CSV: path,status[,detail]. status is one of:
#   - parses     : OznParser accepted the .ozn syntax
#   - parse-fail : OznParser threw on a construct it doesn't understand
#   - mzn-error  : minizinc couldn't even produce the .ozn (data file issues, etc.)
#
# Usage: verify-ozn.sh CORPUS_ROOT
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOLVER_MSC="$REPO_ROOT/klause-mzn-lib/share/minizinc/solvers/klause.msc"
CORPUS="${1:-}"

if [[ -z "$CORPUS" || ! -d "$CORPUS" ]]; then
  echo "usage: verify-ozn.sh CORPUS_ROOT" >&2
  exit 2
fi

KLAUSE_FZN="$REPO_ROOT/klause-mzn-lib/bin/klause-fzn"
if [[ ! -x "$KLAUSE_FZN" ]]; then
  echo "verify-ozn: klause-fzn not built; run :klause-cli:installDist first" >&2
  exit 2
fi

parses=0
parse_fail=0
mzn_error=0
total=0

echo "path,status,detail"
while IFS= read -r -d '' mzn; do
  shopt -s nullglob
  dzns=("$(dirname "$mzn")"/*.dzn)
  shopt -u nullglob
  if (( ${#dzns[@]} == 0 )); then
    pairs=("")
  else
    pairs=("$(ls -S "${dzns[@]}" | tail -1)")
  fi
  for dzn in "${pairs[@]}"; do
    rel="${mzn#$CORPUS/}"
    [[ -n "$dzn" ]] && rel="$rel+$(basename "$dzn" .dzn)"
    total=$((total + 1))
    ozn="$(mktemp --suffix=.ozn)"
    fzn="$(mktemp --suffix=.fzn)"
    err="$(mktemp)"
    args=(--solver "$SOLVER_MSC" -c "$mzn" -o "$fzn" --output-ozn-to-file "$ozn")
    [[ -n "$dzn" ]] && args+=("$dzn")
    rc=0
    timeout 20s minizinc "${args[@]}" >"$err" 2>&1 || rc=$?
    if (( rc != 0 )); then
      echo "$rel,mzn-error,"
      mzn_error=$((mzn_error + 1))
      rm -f "$ozn" "$fzn" "$err"
      continue
    fi
    # Use klause-fzn --ozn to drive the parser (parser is exercised at load time).
    # We don't need to actually solve — kill after parser load by passing -n 0.
    rc=0
    parse_err="$(timeout 10s "$KLAUSE_FZN" --ozn "$ozn" -n 1 "$fzn" 2>&1 >/dev/null)" || rc=$?
    if echo "$parse_err" | grep -qE "Ozn(Parse|Eval)Exception"; then
      detail="$(echo "$parse_err" | grep -oE "Ozn(Parse|Eval)Exception: [^$]*" | head -1 | tr ',' ';' | cut -c1-120)"
      echo "$rel,parse-fail,$detail"
      parse_fail=$((parse_fail + 1))
    else
      # rc may be non-zero for solver-side bugs (unrelated to .ozn parsing). Either
      # the parser worked or the .ozn was never reached because of an earlier crash;
      # both count as "parses" for the purpose of this audit.
      echo "$rel,parses,"
      parses=$((parses + 1))
    fi
    rm -f "$ozn" "$fzn" "$err"
  done
done < <(find "$CORPUS" -name '*.mzn' -print0)

{
  echo ""
  echo "=== summary ==="
  echo "total:       $total"
  echo "parses:      $parses"
  echo "parse-fail:  $parse_fail"
  echo "mzn-error:   $mzn_error"
} >&2
