# Shared learned-clause retention measurements

The production shared store retains by LBD plus propagation use, with the cap off by default.
The measured activity alternatives lose SAT completions at both tested caps, so they are confined
to the [experiment branch](https://github.com/Eignex/klause/tree/codex/learned-clause-retention-experiment).
The production addition is `openReductionNs`: total wall time selecting retained clauses and
rebuilding watch lists at restart boundaries. It adds across solve slices and is zero when no pass
runs. This exposes reduction cost alongside logical work and completion counts.

## Experimental mechanisms

All four arms use [84ec0f7f9](https://github.com/Eignex/klause/commit/84ec0f7f9b0b9a44411b69c70e9519c5b4360a63).
They share the same clause owner, decisions, theory, watch machinery and root-restart lifecycle.
Native CP clauses retain their existing owner and rule.

Activity bumps the learned initial conflict, every learned reason traversed during resolution,
and new/relearned clauses. Its bump quantum decays lazily by `1 / 0.999` per analyzed conflict,
with `1e20` / `1e-20` rescaling. Binary and locked clauses are protected. Explanations carry a
stable metadata reference through watch movement and compaction; reasons are not found by comparing
literals against the database. This is MiniSat-style ranking with Klause's fixed cap and restart
schedule, not MiniSat's adaptive database sizing and half-database schedule.

Glue activity adds LBD <= 2 protection. Tiers monotonically improve LBD during resolution, retain
glue as core, protect LBD <= 6 for two reduction intervals after analysis, and rank locals by activity.
The tier policy does not reproduce a complete Glucose solver. The mechanisms follow
[MiniSat](https://github.com/niklasso/minisat/blob/master/minisat/core/Solver.cc) and
[Glucose](https://github.com/audemard/glucose/blob/master/core/Solver.cc).

## Fixed-decision results

Both runs use 50,000 committed decisions, restart 1,000, fixed engine, one processor, presolve off,
and a 30-second safety timeout. Every arm has three alternating fresh-JVM repetitions on four
klause-lab AWS workers. All 576 cases completed without runner errors or decided reference
contradictions. Every case spent its decision allowance or found SAT; no wall-time truncation
explains the completion losses. The UNSAT oracle inputs remained unknown under this budget.

| Run | Inputs | Cap | LBD-use solved | Activity solved | Glue activity solved | Tiers solved |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| [811](http://192.168.50.104:8420/experiments/811) | 24 | 2,000 | 9 | 8 | 8 | 8 |
| [818](http://192.168.50.104:8420/experiments/818) | 24 | 4,000 | 6 | 5 | 5 | 5 |

At cap 2,000, density-175 seed 20 loses SAT in all three repeats. At cap 4,000, density-175 seed 4
loses SAT in all three repeats. There are no gained completions. The lab's geometric mean PAR-2
ratios on inputs either side solves are 1.36/1.38/1.34 at cap 2,000 and 1.49/1.50/1.50 at cap 4,000
for activity/glue activity/tiers. PAR-2 charges unknowns twice the timeout; ratios above one are worse.

Comparing only common completed inputs also fails to show a runtime gain: activity's mean solve
ratio is 1.041 at cap 2,000 and 1.032 at cap 4,000. At cap 4,000 it inspects 451.7 million learned
watch entries versus LBD-use's 441.9 million and spends 2.727 seconds reducing versus 1.604 seconds.
Total solve time including unknowns is shorter for activity, but it reflects different search work
and lost solutions, so it does not meet the acceptance gate.

Run 811 is exploratory: substring name selection included all 20 density-175 inputs and only four
density-210 inputs. `activity-exploratory-manifest.json` records what ran. Run 818 uses the original
`activity-manifest.json`: 17 SAT and seven UNSAT oracles, with SHA-256 for all 24 inputs. Cap 4,000
was the second cap nominated by the local five-input development pilot before inspecting lab
outcomes. That pilot is not acceptance evidence.

The frozen 28-input holdout excludes both the planned discovery and every exploratory input. It
contains seven SAT and 21 UNSAT oracles and was not launched because discovery failed. A future
policy needs reference parity, no lost completions and repeatable runtime benefit before a holdout
and broader controls justify promotion. The cap and retention default remain unchanged.

## Reproduction

The activity specs pin the experiment revision, whose CLI recognizes `open-learned-policy`.
Name filters use a leading `*` to activate anchored glob matching: `s2` must not also select `s20`.
`analyze.py` rejects corpus mismatches, missing/unfinished records, missing build provenance, wrong
revisions and reference disagreements. The CSVs retain every repetition's verdict, logical work,
runtime and binary fingerprint; the JSONs pin the raw cases payload with SHA-256. Lab stats JSONs
retain the completion-penalized intervals and paired tests.

After downloading a completed run's `/cases` and `/arms` JSON:

```sh
python3 klause-bench/learned-clauses/analyze.py cases.json \
  klause-bench/learned-clauses/activity-manifest.json arms.json \
  --baseline lbd-use --output activity-results
```

Use `activity-exploratory-manifest.json` for run 811. `reduction-cost-lab.json` compares production
telemetry with the unchanged baseline and varies only restart cadence under LBD-plus-use.

## Production telemetry and restart cadence

Lab [820](http://192.168.50.104:8420/experiments/820) completed another 288 cases on the original
24-input manifest, at cap 4,000 and 50,000 committed decisions. The baseline is `58ae735e7`; the
production telemetry revision is `32d86cd25`. No runner errors, wall-time truncations or decided
reference contradictions occurred. All 72 telemetry/baseline pairs at restart 1,000 have identical
verdicts and logical counters, including retained clauses, reductions and watch visits.

| Restart cadence | Solved inputs | Lost inputs versus 1,000 | Total reduction seconds | Common solved mean time ratio |
| ---: | ---: | ---: | ---: | ---: |
| 1,000 | 6 | 0 | 1.714 | 0.999 |
| 250 | 4 | 2 | 2.437 | 1.148 |
| 4,000 | 5 | 1 | 0.880 | 1.015 |

Cadence 250 loses density-175 seed 4 and density-210 seed 18. Cadence 4,000 loses density-175 seed 4.
Every loss repeats three times, with no gains. Their completion-penalized ratios are 2.38 and 1.46.
Reducing less often spends less time reducing, but it does not yield a better completion result.
Neither schedule qualifies for promotion or a holdout.

At the unchanged cadence, the telemetry arm's completion-penalized ratio is 0.996 with 95% interval
[0.958, 1.039], and common completed inputs have solve-time ratio 0.999. Across all fixed-budget
runs, including unknowns, the per-input mean solve-time ratio is 1.025 and aggregate solve time
rises from 454.758 to 461.802 seconds. This is not evidence of a speedup or a guarantee of zero
measurement cost. The useful addition is direct reduction-cost accounting with preserved search
behavior; the default cap-off path does not read the reduction clock.

`reduction-cost-results.csv` and `.json` retain every case and paired checks. The corresponding
lab stats and reference JSONs preserve the independent completion-penalized summaries. The measured
solver and CLI sources are byte-identical to the PR sources; later commits add only reports.
