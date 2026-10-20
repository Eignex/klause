#!/usr/bin/env bash
# MiniZinc Challenge pairwise scoring of saved records, preserving exact objective quality.
# Usage: compare.sh [--complete|--incomplete] <dirA> <dirB>
# Needs jq and bc; objectives compare as exact rationals so values past binary64 still order correctly.
set -euo pipefail

complete=1
case "${1:-}" in
  --complete) shift ;;
  --incomplete) complete=0; shift ;;
esac
[ $# -eq 2 ] && [ -d "$1" ] && [ -d "$2" ] || { echo "usage: compare.sh [--complete|--incomplete] <dirA> <dirB>" >&2; exit 2; }

# One TSV row per record: problem, solved, optimal, maximize, objective, time, display, feasible, proven.
rows() {
  local files=("$1"/*.json)
  [ -e "${files[0]}" ] || return 0
  jq -r '
    def solved: .feasible != null;
    def seconds: (.stats.solveTime? // empty) | tonumber? | select((isnan | not) and (isinfinite | not) and . >= 0);
    def time_used:
      if solved then (.timeToBestMs // (first(seconds) | . * 1000 | floor) // .elapsedMs // .budgetMs // 0)
      else (.budgetMs // 0) end;
    [ .problem,
      (if solved then 1 else 0 end),
      (if .proven == true or .feasible == false or (.kind == "satisfy" and solved) then 1 else 0 end),
      (if .maximize == true then 1 else 0 end),
      ((.exactObjective // .objective) | if . == null then "" else tostring end),
      time_used,
      (if .feasible == false then "UNSAT"
       else [.exactObjective, .objective, .feasible] | map(select(. != null)) | (first // "-") | tostring | ascii_downcase end),
      (.feasible | tostring),
      (.proven == true | tostring)
    ] | @tsv' "${files[@]}"
}

BC_LINE_LENGTH=0 awk -F'\t' -v complete="$complete" -v nameA="$(basename "$1")" -v nameB="$(basename "$2")" '
  # Splits an objective literal (integer, decimal, exponent or p/q) into a bc numerator and denominator.
  function rational(text, part,   m, k) {
    if (index(text, "/")) { split(text, m, "/"); part["n"] = "(" m[1] ")"; part["d"] = "(" m[2] ")"; return }
    if (match(text, /[eE]/)) {
      m[1] = substr(text, 1, RSTART - 1); k = substr(text, RSTART + 1); sub(/^\+/, "", k)
      if (k + 0 >= 0) { part["n"] = "(" m[1] ")*10^" (k + 0); part["d"] = "1" }
      else { part["n"] = "(" m[1] ")"; part["d"] = "10^" (-k) }
      return
    }
    part["n"] = "(" text ")"; part["d"] = "1"
  }
  function order(x, y,   px, py, cmd, out) {
    if (x == "" || y == "") return 0
    rational(x, px); rational(y, py)
    cmd = "echo \"scale=400; " px["n"] "*" py["d"] "-" py["n"] "*" px["d"] "\" | bc"
    cmd | getline out; close(cmd)
    if (out ~ /^-/) return -1
    return out ~ /^[0.]*$/ ? 0 : 1
  }
  function fmt(v,   s) { s = sprintf("%.2f", v); if (s ~ /\./) sub(/\.?0+$/, "", s); return s == "-0" ? "0" : s }
  FNR == NR { b[$1] = $0; next }
  !($1 in b) { next }
  {
    split(b[$1], r, "\t")
    sa = $2; sb = r[2]; oa = $3; ob = r[3]
    quality = order($5, r[5]) * ($4 ? 1 : -1)
    if (sa != sb) cmp = sa ? 1 : -1
    else if (!sa) cmp = 0
    else if (complete && oa != ob) cmp = oa ? 1 : -1
    else cmp = quality
    at = $6; bt = r[6]
    tie = complete && at + bt ? bt / (at + bt) : 0.5
    pa = !sa || cmp < 0 ? 0 : cmp > 0 ? 1 : tie
    pb = !sb || cmp > 0 ? 0 : cmp < 0 ? 1 : 1 - tie
    if ((quality > 0 && r[9] == "true") || ($8 == "true" && r[8] == "false")) unsound = unsound (unsound ? ", " : "") $1
    line[++n] = sprintf("  A%s%s [%s]%s A=%s%s@%sms  B=%s%s@%sms", pa >= pb ? "+" : " ", fmt(pa), $1,
      $4 ? " (max)" : "", $7, oa ? "!" : "", at, r[7], ob ? "!" : "", bt)
    sumA += pa; sumB += pb
    if (cmp > 0) wins++
    if (cmp < 0) losses++
    if (cmp == 0 && sa && sb) ties++
    if (!sa) unknown++
    if (sb && !sa) { missed++; missedList = missedList (missedList ? ", " : "") $1 }
  }
  END {
    printf "=== MiniZinc Challenge %s scoring:  %s  vs  %s   (%d shared) ===\n", complete ? "complete" : "incomplete", nameA, nameB, n
    if (unsound) print "  !! UNSOUND (A beats a proven optimum / SAT-vs-UNSAT): " unsound
    for (i = 1; i <= n; i++) print line[i]
    printf "\n  BORDA SCORE:  %s = %s   %s = %s   (of %d)\n", nameA, fmt(sumA), nameB, fmt(sumB), n
    printf "  A strict wins %d, strict losses %d, ties %d, A-unsolved %d\n", wins, losses, ties, unknown
    printf "  B-solved that A did not: %d%s\n", missed, missed ? "  -> " missedList : ""
  }
' <(rows "$2") <(rows "$1")
