#!/usr/bin/env bash
# Compare two configs' saved `solve` output across the problems they share.
# Each config is a directory of per-problem JSON records (see SolveMetric): one <problem>.json per
# instance, holding solver/settings/budget + the parsed result. Direction-aware (maximize vs
# minimize per problem).
#
#   compare.sh <dirA> <dirB>
#   e.g.  compare.sh output/klause-cp-p8-free-t300s output/choco-p8-free-t300s
#
# Verdict per shared problem (A relative to B):
#   win  = A's objective is better (higher for maximize, lower for minimize), OR equal-value reached
#          no later (time-to-best tiebreak); for satisfaction, A feasible where B is not.
#   loss = the reverse.  tie = same value/feasibility, no time edge.
#   UNSOUND = A strictly beats B's PROVEN optimum (impossible — a bug to investigate).
# Also reports the "solve 100% of B's" superset (problems B solved that A did not) and the
# time-to-best aggregate over problems both solvers timed (A/B totals + ratio) — the regression edge
# when A and B are two runs of the same solver.
set -eu
A="${1%/}"; B="${2%/}"

jq -rn \
  --arg as "$(basename "$A")" --arg bs "$(basename "$B")" \
  --slurpfile a <(cat "$A"/*.json) \
  --slurpfile b <(cat "$B"/*.json) \
  '
  ($b | map({key: .problem, value: .}) | from_entries) as $bi
  | [ $a[] | . as $x | ($bi[.problem]) as $y | select($y != null)
      | (.maximize) as $max
      | {
          name: .problem, kind: .kind, max: $max,
          ao: .objective, at: .timeToBestMs, ap: .proven, af: .feasible,
          bo: $y.objective, bt: $y.timeToBestMs, bp: $y.proven, bf: $y.feasible,
          afl: (.stats.failures), bfl: ($y.stats.failures),
          and: (.stats.nodes), bnd: ($y.stats.nodes),
          verdict:
            (if (.objective != null and $y.objective != null) then
               (if (.objective == $y.objective) then
                  (if ((.timeToBestMs // 9e18) <= ($y.timeToBestMs // 9e18)) then "win" else "loss" end)
                else
                  ( ((if $max then .objective > $y.objective else .objective < $y.objective end)) as $abetter
                  | if $abetter then (if $y.proven then "UNSOUND" else "win" end) else "loss" end )
                end)
             elif (.objective != null) then "win"
             elif ($y.objective != null) then "loss"
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
  | ($rows|map(select(.afl!=null and .bfl!=null))) as $stated
  | ($stated|map(.afl|tonumber)|add // 0) as $afl
  | ($stated|map(.bfl|tonumber)|add // 0) as $bfl
  | ($stated|map(.and|tonumber)|add // 0) as $anod
  | ($stated|map(.bnd|tonumber)|add // 0) as $bnod
  | "=== \($as)  vs  \($bs)   (\($n) shared problems) ===",
    ($rows[] | "  \(.verdict|ascii_upcase|.[0:4]) [\(.name)]\(if .max then " (max)" else "" end) "
       + "A=\(.ao // .af)@\(.at // "-")ms  B=\(.bo // .bf)@\(.bt // "-")ms\(if .bp then " (B proved)" else "" end)"),
    "",
    "A wins \($w)/\($n)  (loss \($l), tie \($t), unsound \($u))",
    "B-solved problems A missed: \($missed|length)\(if ($missed|length)>0 then "  -> " + ($missed|join(", ")) else "" end)",
    "time-to-best over both-timed (\($timed|length)): A=\($atot)ms B=\($btot)ms\(if $btot>0 then "  (\(($atot/$btot*100|round)/100)×)" else "" end)",
    (if ($stated|length)>0 then "search effort over both-with-stats (\($stated|length)): A=\($afl) conflicts / \($anod) nodes  B=\($bfl) / \($bnod)" else empty end)
'
