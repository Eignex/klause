#!/usr/bin/env bash
# Per-arm credit over one config's saved `solve` output — the offline aggregator for a klause
# portfolio run, the sibling of compare.sh. It reads the per-problem JSON records (see SolveMetric)
# of ONE config dir and attributes every strict global improvement to the arm that produced it,
# from the `attribution` array each record carries (klause emits `%%%klause-arm:` lines under `-s`
# on a portfolio optimize; references and single-engine klause carry none).
#
#   credit.sh <dir>
#   e.g.  credit.sh output/klause-mixed-p8-t300s
#
# To produce the data:  bench solve suite=… backend=klause engine=mixed -p8   (the -s/attribution
# is always on for klause), then point this at output/klause-mixed-p8-t300s.
#
# Per arm it reports:
#   firsts       = instances where the arm produced the FIRST global incumbent
#   bests        = instances where the arm held the FINAL best
#   soles        = instances where it was the ONLY contributing arm
#   improvements = total strict global improvements it produced
# plus a greedy MARGINAL-CONTRIBUTION ranking (each slot is awarded to the arm covering the most
# instances no higher-ranked arm touched, ties broken by final-bests it would newly hold) — the
# palette-ordering signal: raw credit overrates arms whose wins are duplicated by others.
set -eu
D="${1%/}"

jq -rn \
  --arg ds "$(basename "$D")" \
  --slurpfile recs <(cat "$D"/*.json) \
  '
  # Optimization instances that recorded at least one attributed improvement.
  ([$recs[] | select(.kind == "optimize") | select((.attribution // []) | length > 0)]
    | map({
        name: .problem,
        arms: (.attribution | map(.label) | unique),
        first: (.attribution[0].label),
        firstMs: (.attribution[0].elapsedMs),
        last: (.attribution[-1].label),
        counts: (.attribution | group_by(.label) | map({label: .[0].label, n: length})),
      })) as $p
  | ([$p[].arms[]] | unique) as $labels
  | if ($p | length) == 0 then
      "=== \($ds): no per-arm attribution ===",
      "(only a klause portfolio optimize under -s emits `%%%klause-arm:` lines; references and",
      " single-engine klause carry none. Re-run `bench solve … backend=klause engine=mixed -p8`.)"
    else
      # --- per-arm aggregates ---
      ($labels | map(. as $l | {
          label: $l,
          firsts: ([$p[] | select(.first == $l)] | length),
          bests: ([$p[] | select(.last == $l)] | length),
          soles: ([$p[] | select(.arms == [$l])] | length),
          improvements: ([$p[].counts[] | select(.label == $l) | .n] | add // 0),
        }) | sort_by([-.firsts, -.bests])) as $agg
      # --- greedy marginal-contribution ranking (set cover over instances) ---
      | def coveredBy($arms; $chosen): ($arms | any(. as $a | ($chosen | index($a)) != null));
        def greedy($chosen):
          ($labels | map(select(. as $c | ($chosen | index($c)) == null))) as $rem
          | if ($rem | length) == 0 then []
            else
              ($rem | map(. as $c | {
                 label: $c,
                 unc: ([$p[] | select((.arms | index($c)) != null) | select(coveredBy(.arms; $chosen) | not)] | length),
                 bst: ([$p[] | select(.last == $c) | select(.last as $ll | ($chosen | index($ll)) == null)] | length),
               }) | sort_by([-.unc, -.bst]) | .[0]) as $best
              | if ($best.unc == 0 and $best.bst == 0) then []
                else [{label: $best.label, unc: $best.unc, bst: $best.bst}] + greedy($chosen + [$best.label])
                end
            end;
        (greedy([]) | to_entries | map(.value + {rank: (.key + 1)})) as $marg
      | "=== \($ds) credit  (\($p | length) instances with incumbents, \($labels | length) arms) ===",
        ($p[] | "  [\(.name)] first=\(.first)@\(.firstMs)ms best=\(.last)"
           + "  contrib=\(.counts | map("\(.label):\(.n)") | join(","))"),
        "",
        "--- aggregate credit (firsts / bests / soles / improvements) ---",
        ($agg[] | "  \(.label | .[0:36] | (. + (" " * (36 - length)))) \(.firsts) / \(.bests) / \(.soles) / \(.improvements)"),
        "",
        "--- marginal ranking (greedy set cover; omitted arms are redundant) ---",
        ($marg[] | "  \(.rank | tostring | (("  " + .)[-2:]))  \(.label | .[0:36] | (. + (" " * (36 - length)))) +uncovered=\(.unc) +best=\(.bst)")
    end
'
