# Presolve effort campaign (#554)

Status: AWS pilots complete; stack integration and 300-second campaigns pending.

The solver baseline is `5471419aa8c832ee1065468a6861bb203a1ff8c7`. The neutral
controls build is `5ad3707c0c0bfb113f994828265b9f1bafef91c3` ([PR #2378](https://github.com/Eignex/klause/pull/2378)).
No local build, test, solve, benchmark, lint or documentation gate was run.
GitHub CI provides validation; klause-lab builds installed JVM distributions with
`:klause-cli:installJvmDist` and runs every campaign on AWS.
Initial local Python runs collected and summarized remote records only. After the
execution reminder, further inspection uses lab API reads; no local analysis or
validation scripts are run.
The immutable `codex/presolve-554-frozen-pilots` branch preserves every pilot build
and its evidence across stack rebases. Historical SHA references remain intact.

## Pilot

[Job 863](http://192.168.50.104:8420/jobs/863) takes 12 year/family groups from
`mzn-bench`, one instance per family with selection seed 554 and `reference=any`.
Seven configurations, solver seeds 3 and 11, two alternating repetitions and a
10,000 ms budget give 336 cases, at most 56 solver minutes before setup and launch
cost. Ordinary AWS parallel is unset: the worker supplies a spare physical core
per single-core case and allocates instances within its existing capacity.
Each matched problem's arms, seeds and repetitions stay on one instance.

`engine=cp`, `processors=1`, `bt-arm=satOptimized` pin the same CP family. The first pilot left the default
pool cardinality in place (six cloned satOptimized arms); it is a family-pinned
diagnostic. A second pilot explicitly sets `param.arms=1` before the broad sweep.
The implicit and explicit default controls intentionally duplicate settings to
measure noise and plumbing neutrality. Conservative and aggressive change tiers;
abort fractions 0.0001 and 0.01 bracket the current 0.001. The pilot must establish
which knobs actually affect executed work, not just printed settings, before a
larger sweep. Round and SAC limits remain at their defaults initially.

[Job 858](http://192.168.50.104:8420/jobs/858) was cancelled during setup with zero
planned/executed cases after source review found a common-target telemetry sorting
mistake. Its exact submitted specification and cancelled job snapshot are retained.
It contributes no timing result.

## Measurement policy

The broad target is a 300,000 ms MiniZinc campaign. Freeze disjoint discovery and
holdout input lists before examining discovery outcomes. Select families evenly;
exclude pilot inputs from holdout. Use several solver seeds and alternating repeats.
Prune an inactive or clearly unhelpful variant after the pilot instead of crossing
all knobs. Expand round/SAC experiments only if observed work justifies them.

Analyze complete matched cases and disclose all missing, cancelled and failed
records. Separate reported witnesses, quality, time to first/best, proved optima,
refutations, unknowns, unsupported and errors. A reported-result validation policy
is not an independent source check. Reuse lab reference records and report their
disagreements; do not run reference-solver arms on AWS. Any source/witness validation
runs through the remote lab. JFR, if needed, gets a separate `host=aws`,
`profileCli=true`, `parallel=1` job and contributes no production timing.

`presolvePreparationMs` includes source-safe/finite preparation and base bake,
excluding frontend routing, source compilation and search-arm construction.
`presolveWork`, pass/round/probe counters share the metered source/finite allowance.
Round entries include the final empty schedule scan. Root probe counts include
Boolean failed-literal probes and integer SAC propagation/repair calls across
reseeds. Integer SAC caps apply per tier per bake; Boolean probes use cancellation.
An aggregate probe count above one cap does not alone indicate a cap violation.
Work can exceed its allowance by the final charge before a cancellation poll.

Check concurrent construction budgeting (#2328) before recommending settings. A
recommendation affected by that work requires confirmation on the integrated build.
No settings are merged automatically; a null result is acceptable.

`analyze.py` compares complete matched blocks, excludes errors, unsupported cases,
source-hash mismatches and contradictory reported outcomes from paired estimates,
and discloses every exclusion. Legacy records without hashes remain explicitly
unverified. Witness checks and retained candidates are counted separately from
reported results. Bootstrap intervals cluster by base family; a single cluster
has no estimated sampling interval. GitHub's `presolve report` workflow executes
the script against archived AWS records and publishes the JSON reports. It runs
no solver or benchmark.

## Reproduction and evidence

From the repository root:

```
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/campaigns/presolve-554/experiments/pilot.json
python3 klause-bench/campaigns/presolve-554/collect.py 863
```

`collect.py` downloads and gzip-archives raw lab API responses with their URLs,
uncompressed SHA-256 and size. Cases retain exact commands, git SHAs, installed CLI
and runtime hashes/options, elapsed time, validation policy and statistics. The
file listing supplies durable retrieval references for retained streams and setup
logs. Successful production cases retain records without their stdout streams;
the pilots therefore preserve source-check verdicts, not replayable witnesses.
Collect finished jobs again to replace partial snapshots. The live job's
`files` endpoint and `/experiments/<id>/cases.csv` remain independently accessible.

## Frozen split

`split.json` freezes 20 discovery and 12 holdout instances, one per base family
(with the year removed for grouping). Its universe is the 62 MiniZinc identities
already selected by lab status sweep 845; only identities were used for selection.
This is an existing regression corpus with reference coverage, not an unbiased
sample of the complete challenge archive. Selection uses SHA-256 order with the
`presolve-554/` prefix, and the holdout excludes every pilot base family.
Remaining families are reserved. Holdout is therefore independent by base family
as well as by input; it cannot estimate within-family instance generalization.
These campaign sets are separate from the curated thematic `sweep` set.

The pilot's 12 year/family groups are only eight base families because families
recur across years. It is a bounded plumbing/cost screen with catalogue ordering,
not the representative discovery campaign. Bootstrap intervals in `analyze.py`
cluster by base family and average seeds/repeats within each cluster.

## Pilot correction

Initial records exposed two MiniZinc compile errors (`debruijn_binary` string search
annotations and `black-hole`'s obsolete `is_output`). They stay in the record as
load errors; they do not contribute presolve cost observations. The first pilot
also confirms that `bt-arm=satOptimized` selects a family but leaves six cloned
arms under default cardinality. These are measured settings, not a single solver.

Job 863 finished all 336 cases. Each configuration has 48 records, including eight
load errors. Explicit default and frozen main each report 13 witnesses (four proved),
four refutations and 23 unknowns; aggressive reports no witnesses, four refutations
and 36 unknowns. These are reported outcomes under a short budget. The final
`evidence/863/final-{cases,job,reference,files}.json.gz` responses supplement the
earlier partial snapshots. Lab transport retries can inflate command wall times;
timing comparisons use the returned solver record, not command completion time.

[Job 874](http://192.168.50.104:8420/jobs/874) explicitly sets `param.arms=1` and
uses six frozen discovery inputs from `presolve-554-pilot`. Its controls build is
`09f01a9ba` (full SHA in `experiments/single-arm-pilot.json`), with source checks
and model/data hashes enabled only on the updated bench arms. The immutable main
arm uses its existing reported-result policy; checking happens outside solver
runtime and that policy difference is retained. This pilot has 168 cases under
10,000 ms, two seeds and two repeats. It must confirm the single-arm commands,
work telemetry and source-check data before the larger 300-second sweep.

All 168 cases finished. Explicit default, implicit default and frozen main each
report 16 witnesses and eight unknowns. Conservative reports 14 witnesses and ten
unknowns; aggressive reports four witnesses and 20 unknowns. No arm reports a
proof or refutation. Four witnesses in each updated non-aggressive configuration
passed the source check; all remaining source-check outcomes are unknown, with no
invalid candidates. Unknown checks retain integer witnesses as reported outcomes.
The final API responses are archived under `evidence/874/final-*.json.gz`.

The CI-generated comparison has 24 complete matched blocks with no source-hash
mismatches among updated arms. Abort 0.0001 and implicit default match default
work in every block. Abort 0.01 changes four blocks on `gbac`, reducing work from
263,657,217 to 263,412,856 and round entries from seven to five, with the same 603
removed constraints. It remains a discovery candidate; the lower fraction is
pruned. Conservative's PAR2 ratio is 1.157 (family bootstrap 0.842–1.832), and
aggressive's is 3.111 (1.189–9.801), under this short budget. These reported-outcome
intervals are screening evidence, not independently validated default choices.

## Reference identity diagnostic

[Job 876](http://192.168.50.104:8420/jobs/876) checks the first pilot's BACP
reference disagreement on AWS. It uses the same source-check build as job 874,
two inputs, default/conservative/off single-arm settings and a default six-arm
control, two seeds and two repeats under 10,000 ms. The exact specification is
`experiments/bacp-reference-check.json`.

All 32 cases completed, and every witness passed the pinned-source check. The
`evidence/876/final-{cases,job,reference,files}.json.gz` archives are the final
responses from the corresponding lab API routes; the earlier archives are partial
snapshots. Completed records include source-valid witnesses of objective 29 and 30 for
`2011/bacp`, below the historical reference optimum 38. Source validation checks
the witness, not the solver's optimality claim. The checked root model hash is
`d3b62b85f27603fb031e95c0a16f30c663d2771a727fe8584e5cf8f80e93dd61`.
The family contains several self-contained `bacp-*.mzn` files, while primary-model
selection uses filesystem enumeration order within its name priority. Historical
references have no root-model hash, so their problem identity cannot be matched
to this input. The disagreement is not evidence of a presolve soundness failure.
A deterministic selection fix is prepared; its PR waits for the existing stack's
latest-head CI and mergeability checks. Discovery and holdout must use that fixed
selector with identical input hashes across configurations.

[Job 892](http://192.168.50.104:8420/jobs/892) verifies durable opt-in evidence on
`4020e4488856bb5ba79d8f80ff13cd4d02214306`. All four BACP cases completed and
passed source checking; each lab record retains its checked DZN candidate and
stdout SHA-256. The specification is `experiments/witness-record-smoke.json`,
and final API records are under `evidence/892`. These metadata checks contribute
no tuning comparison.

The integrated build uses main's canonical `finalWitness` and source hashing,
with opt-in source checks and stdout hashes. Historical job 892 used
`sourceWitness`; the analyzer accepts both fields. Main's witness-size limit
still applies. Integration is validated through fresh GitHub CI and AWS records.
