#!/usr/bin/env bash
# MiniZinc Challenge pairwise (Borda) scoring between two configs' saved `solve` output.
# Implements the official rules (https://www.minizinc.org/challenge/2026/rules/): for each shared
# problem, solver A scores points relative to B:
#   - A not solved                   -> 0      (even when B is also unsolved)
#   - A solved, B not                -> 1
#   - both solved, A strictly better -> 1
#   - both solved, B strictly better -> 0
#   - both solved, indistinguishable -> complete:  timeUsed(B)/(timeUsed(A)+timeUsed(B)), 0.5 if both 0
#                                       incomplete: 0.5
# "strictly better" is the priority chain solved > optimal > quality:
#   - complete   (FD / free / parallel / open): proving optimality beats not proving; then objective.
#   - incomplete (local search): optimality is ignored; only objective quality counts.
# A track's score for a solver is the sum of its pairwise points; this script reports A's and B's
# totals over the problems they share.
#
#   compare.sh [--incomplete] <dirA> <dirB>      (default --complete)
#
# Each config is a directory of per-problem JSON records (see SolveMetric), direction-aware via the
# per-record `maximize` flag. Record fields used:
#   feasible  true = found a solution, false = proved unsatisfiable, null = nothing found
#   objective the model-oriented objective value (null unless feasible)
#   proven    optimality (or unsatisfiability) was proved
#   timeToBestMs / budgetMs    timing
# timeUsed is approximated as timeToBestMs when solved, else budgetMs — the bench does not separately
# stamp proof-completion time, so the tie time-fraction uses time-to-best as the proxy.
set -eu
MODE=complete
case "${1:-}" in
  --incomplete) MODE=incomplete; shift ;;
  --complete)   MODE=complete;   shift ;;
esac
A="${1%/}"; B="${2%/}"

jq -rn \
  --arg as "$(basename "$A")" --arg bs "$(basename "$B")" --arg mode "$MODE" \
  --slurpfile a <(cat "$A"/*.json) \
  --slurpfile b <(cat "$B"/*.json) '
  ($mode == "complete") as $complete |
  ($b | map({key: .problem, value: .}) | from_entries) as $bi |
  # solved: found a solution (feasible true) or proved unsatisfiable (feasible false). null = not solved.
  def solved($r): ($r.feasible != null);
  # optimal: a complete answer — proved optimality, proved unsat, or a solved satisfaction instance.
  def optimal($r): ($r.proven == true) or ($r.feasible == false) or ($r.kind == "satisfy" and solved($r));
  # timeUsed proxy (ms).
  def tu($r): (if solved($r) then (($r.timeToBestMs // $r.budgetMs) // 0) else ($r.budgetMs // 0) end);
  [ $a[] | . as $x | ($bi[.problem]) as $y | select($y != null)
    | (.maximize) as $max
    | (if (.objective != null and $y.objective != null) then
         (if .objective == $y.objective then "eq"
          elif ($max and .objective > $y.objective) or (($max | not) and .objective < $y.objective) then "A"
          else "B" end)
       else "na" end) as $q
    # cmp: 1 = A strictly better, -1 = B strictly better, 0 = indistinguishable.
    | (if (solved($x) | not) then (if (solved($y) | not) then 0 else -1 end)
       elif (solved($y) | not) then 1
       elif $complete and optimal($x) and (optimal($y) | not) then 1
       elif $complete and optimal($y) and (optimal($x) | not) then -1
       elif $q == "A" then 1
       elif $q == "B" then -1
       else 0 end) as $cmp
    | tu($x) as $at | tu($y) as $bt
    | (if (solved($x) | not) then 0
       elif $cmp == 1 then 1
       elif $cmp == -1 then 0
       elif $complete then (if ($at + $bt) == 0 then 0.5 else ($bt / ($at + $bt)) end)
       else 0.5 end) as $pa
    | (if (solved($y) | not) then 0
       elif $cmp == -1 then 1
       elif $cmp == 1 then 0
       elif $complete then (if ($at + $bt) == 0 then 0.5 else ($at / ($at + $bt)) end)
       else 0.5 end) as $pb
    # correctness clash: A reports a value beating B'"'"'s proven optimum, or SAT vs proved-UNSAT.
    | ((($q == "A") and ($y.proven == true)) or (.feasible == true and $y.feasible == false)) as $unsound
    | {name: .problem, max: $max, cmp: $cmp, pa: $pa, pb: $pb, unsound: $unsound,
       asolved: solved($x), bsolved: solved($y),
       av: (if .feasible == false then "UNSAT" else (.objective // .feasible // "-") end),
       bv: (if $y.feasible == false then "UNSAT" else ($y.objective // $y.feasible // "-") end),
       aopt: optimal($x), bopt: optimal($y), at: $at, bt: $bt } ]
  | . as $rows
  | ($rows | length) as $n
  | ($rows | map(.pa) | add // 0) as $sa
  | ($rows | map(.pb) | add // 0) as $sb
  | ($rows | map(select(.cmp == 1)) | length) as $wa
  | ($rows | map(select(.cmp == -1)) | length) as $wb
  | ($rows | map(select(.cmp == 0 and .asolved and .bsolved)) | length) as $ties
  | ($rows | map(select(.asolved | not)) | length) as $aun
  | ($rows | map(select(.unsound)) | map(.name)) as $uns
  | ($rows | map(select(.bsolved and (.asolved | not))) | map(.name)) as $missed
  | "=== MiniZinc Challenge \($mode) scoring:  \($as)  vs  \($bs)   (\($n) shared) ===",
    (if ($uns | length) > 0 then "  !! UNSOUND (A beats a proven optimum / SAT-vs-UNSAT): \($uns | join(", "))" else empty end),
    ($rows[] | "  A\(if .pa >= .pb then "+" else " " end)\((.pa * 100 | round) / 100) [\(.name)]\(if .max then " (max)" else "" end) "
       + "A=\(.av)\(if .aopt then "!" else "" end)@\(.at)ms  B=\(.bv)\(if .bopt then "!" else "" end)@\(.bt)ms"),
    "",
    "  BORDA SCORE:  \($as) = \((($sa) * 100 | round) / 100)   \($bs) = \((($sb) * 100 | round) / 100)   (of \($n))",
    "  A strict wins \($wa), strict losses \($wb), ties \($ties), A-unsolved \($aun)",
    "  B-solved that A did not: \($missed | length)\(if ($missed | length) > 0 then "  -> " + ($missed | join(", ")) else "" end)"
'
