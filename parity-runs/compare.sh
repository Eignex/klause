#!/usr/bin/env bash
# Compare two solvers' saved `solve` reports across the problems they share, at the same settings.
# Reads the per-solver JSON written by the `solve` metric (klause-bench/build/solve-<solver>.json,
# or any path). Direction-aware (maximize vs minimize per row), so it supersedes analyze.sh.
#
#   compare.sh <A.json> <B.json>
#
# Verdict per shared instance (A relative to B):
#   win  = A's objective is better (higher for maximize, lower for minimize), OR equal-value reached
#          no later (time-to-best tiebreak); for satisfaction, A feasible where B is not.
#   loss = the reverse.  tie = same value/feasibility, no time edge.
#   UNSOUND = A strictly beats B's PROVEN optimum (impossible — a bug to investigate).
# Also reports the "solve 100% of B's" superset: instances B solved that A did not, and the
# time-to-best aggregate over instances both solvers timed (A/B totals + ratio).
#
# This doubles as a regression check: freeze a baseline `solve` JSON, then compare a fresh run
# against it — the verdict counts catch quality regressions and the time aggregate catches slowdowns.
set -eu
A="$1"; B="$2"

jq -rn --slurpfile a "$A" --slurpfile b "$B" '
  ($a[0]) as $ar | ($b[0]) as $br
  | ($ar.solver) as $as | ($br.solver) as $bs
  | ($br.rows | map({key: .name, value: .}) | from_entries) as $bi
  | [ $ar.rows[] | . as $x | ($bi[.name]) as $y | select($y != null)
      | (.maximize) as $max
      | {
          name: .name, kind: .kind, max: $max,
          ao: .objective, at: .timeMs, ap: .proven, af: .feasible,
          bo: $y.objective, bt: $y.timeMs, bp: $y.proven, bf: $y.feasible,
          verdict:
            (if (.objective != null and $y.objective != null) then
               # optimization, both have an incumbent
               (if (.objective == $y.objective) then
                  (if ((.timeMs // 9e18) <= ($y.timeMs // 9e18)) then "win" else "loss" end)
                else
                  ( ((if $max then .objective > $y.objective else .objective < $y.objective end)) as $abetter
                  | if $abetter then (if $y.proven then "UNSOUND" else "win" end) else "loss" end )
                end)
             elif (.objective != null) then "win"            # A has incumbent, B none
             elif ($y.objective != null) then "loss"
             # satisfaction
             elif (.feasible == true and $y.feasible != true) then "win"
             elif (.feasible == $y.feasible) then "tie"
             else "loss" end)
        } ]
  | . as $rows
  | ($rows|length) as $n
  | ($rows|map(select(.verdict=="win"))|length) as $w
  | ($rows|map(select(.verdict=="loss"))|length) as $l
  | ($rows|map(select(.verdict=="tie"))|length) as $t
  | ($rows|map(select(.verdict=="UNSOUND"))|length) as $u
  | ($rows|map(select((.bf==true or .bo!=null) and (.af!=true and .ao==null)))|map(.name)) as $missed
  | ($rows|map(select(.at!=null and .bt!=null))) as $timed
  | ($timed|map(.at)|add // 0) as $atot
  | ($timed|map(.bt)|add // 0) as $btot
  | "=== \($as)  vs  \($bs)   (\($n) shared instances) ===",
    ($rows[] | "  \(.verdict|ascii_upcase|.[0:4]) [\(.name)]\(if .max then " (max)" else "" end) "
       + "A=\(.ao // .af)@\(.at // "-")ms  B=\(.bo // .bf)@\(.bt // "-")ms\(if .bp then " (B proved)" else "" end)"),
    "",
    "A wins \($w)/\($n)  (loss \($l), tie \($t), unsound \($u))",
    "B-solved instances A missed: \($missed|length)\(if ($missed|length)>0 then "  -> " + ($missed|join(", ")) else "" end)",
    "time-to-best over both-timed (\($timed|length)): A=\($atot)ms B=\($btot)ms\(if $btot>0 then "  (\(($atot/$btot*100|round)/100)×)" else "" end)"
'
