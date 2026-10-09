# Presolve effort campaign (#554)

Status: AWS pilots and metadata integration complete; 300-second campaigns running.

The solver baseline is `5471419aa8c832ee1065468a6861bb203a1ff8c7`. The neutral
controls build is `5ad3707c0c0bfb113f994828265b9f1bafef91c3` ([PR #2378](https://github.com/Eignex/klause/pull/2378)).
No local build, test, solve, benchmark, lint or documentation gate was run.
GitHub CI provides validation; klause-lab builds installed JVM distributions with
`:klause-cli:installJvmDist` and runs every campaign on AWS.
Initial local Python runs collected and summarized remote records only. After the
execution reminder, further inspection uses lab API reads; no local analysis or
validation scripts are run.
The immutable `codex/presolve-554-frozen-pilots`,
`codex/presolve-554-frozen-integration` and `codex/presolve-554-frozen-300` branches
preserve historical builds across stack rebases. Historical SHA references remain intact.

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
Legacy `timeToFirstFeasibleMs` and `timeToBestMs` use the search attribution clock,
which starts after preparation; without attribution they use output separators.
The pilot's PAR2 ratios therefore are reported search timings, not end-to-end gains.
The integrated benchmark also records `processTimeToFirstFeasibleMs` and
`processTimeToBestMs` when it receives each incumbent line. These include JVM launch,
frontend loading, preparation and search, plus output transport delay. They select
the best objective across the stream rather than its last arrival. `elapsedMs`
records total subprocess duration, before source checks. The analyzer reports
these clocks separately and excludes unavailable legacy process witness timings.
Preparation-adjusted search timing is an additional diagnostic; it still excludes
startup, frontend loading and routing. Unknowns receive twice the nominal budget
in PAR2; duration summaries retain observed overshoot.
Time to a run's own best objective must be read alongside its final quality.
The analyzer also reports process PAR2 on pairs with equal reported outcomes and
objectives. This describes a subset selected by the observed outcomes; its coverage
is disclosed and it does not estimate the causal effect of changing presolve.
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
aggressive's is 3.111 (1.189–9.801), under this short budget. These reported search
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
The deterministic selection fix is [PR #2386](https://github.com/Eignex/klause/pull/2386).
It opened after the existing stack passed latest-head CI and mergeability checks.
Discovery and holdout use that fixed selector with identical input hashes across
configurations. [Controls PR #2378](https://github.com/Eignex/klause/pull/2378) merged
after its full latest-head CI passed and the integrated AWS metadata check completed.
The selector merged after its full latest-head CI passed, and the report is based
on main for fresh CI. These merges retain the shipped presolve settings.

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

[Job 903](http://192.168.50.104:8420/jobs/903) checks the integrated metadata on
`ba90d4104093c70563324f95415adad10ad25b5a`, including merged construction budgeting,
deterministic model selection, canonical witnesses and process-clock attribution.
Its four BACP cases are metadata validation only. The exact submitted specification
is `experiments/integration-smoke.json`. All four cases completed, passed source
checking and retained canonical witnesses, stdout hashes and process timings.
Process time to best was 3,420, 10,161, 10,162 and 3,657 ms; raw search times were
2,486, 9,455, 9,473 and 2,848 ms. These clocks are distinct as intended.
The deterministic selector changes the BACP root models: the checked hashes are
`7e84fff2674388a5a8dfb0259963345cc45707145fe42657c0a0c1a0ff7e5a9c` (2010) and
`03b92632a1b720d592955e03d5c60dadad2e06f6c3fcd1883c969f914dca4771` (2011), stable
across both seeds. Objectives cannot be compared to the earlier BACP input hashes.
Final records and job/file metadata are archived under `evidence/903`.

## Frozen 300-second specifications

`experiments/discovery-300.json` names the 20 discovery inputs and compares default,
conservative and abort 0.01. Three solver seeds (3, 11, 29) and two alternating
repeats produce 360 cases, with 30 solver core-hours at the nominal budget.
`experiments/aggressive-300.json` uses the six discovery pilot inputs and compares
default, aggressive and aggressive with integer caps of 256 per variable and 20,000
per tier. Its 108 cases represent nine nominal solver core-hours. Boolean probes,
reseeds and subprocess overshoot are reported separately from those integer caps.
The worker rotates arm order by problem and alternates arms within each seed and
repeat; all configurations of an input stay on one AWS instance. Normal parallel
and machine counts are unset and managed by the worker.

Both specifications pin `f6944877bb7a4846d56ce2031b0843da6105515c`, containing the
merged construction budget, fixed selector, controls and process timing. They are
running as [discovery job 906](http://192.168.50.104:8420/jobs/906) and
[aggressive job 907](http://192.168.50.104:8420/jobs/907). AWS setup planned exactly
360 and 108 cases. The lab disables benchmark result caching for every
production case. Process timing includes FlatZinc frontend loading; MiniZinc source
compilation precedes subprocess launch and is outside this measurement. Setup,
compilation and post-solve source checking do not consume measured search time.
The abort fraction 0.0001 is pruned because it changed no observed pilot work.
No round-cap expansion is justified by the pilot. A holdout candidate and its
settings will be frozen only after discovery; holdout outcomes remain unopened.

Discovery screening ranks work-active candidates by the complete paired process
PAR2 ratio, provided their mean reported quality is nonnegative. Ties prefer the
smaller change (abort 0.01 before conservative; neither aggressive setting becomes
a global-default candidate from its focused six-family screen). Any invalid checked
witness, contradictory verdict or input-hash mismatch requires investigation before
selection. Freeze at most one candidate for a 12-family holdout comparison against
default, with the same build, three seeds and two repeats (144 cases). A recommendation
requires consistent quality and process-timing evidence across discovery and holdout,
with uncertainty, per-family regressions and independent-check coverage disclosed.
An inconclusive result or no eligible discovery candidate leaves shipped defaults
unchanged; the frozen holdout is not used to rescue or select a failed candidate.

## Separate deadline diagnostic

Production job 907 case 55 spends 300,144 ms in aggressive preparation on
`2022/gfd-schedule/n60f7d50m30k10_10124`, returns unknown and finishes its CLI
subprocess after 300,627 ms. Capped aggressive case 56 spends 416,234 ms in
preparation and finishes after 416,717 ms, also unknown. Its aggregate probe count
is 43,915; this includes Boolean probes and multiple tiers and does not prove a
violation of one integer cap. These are production observations of preparation
cost and deadline overshoot, not a guarantee for other inputs.

[Job 911](http://192.168.50.104:8420/jobs/911) is a separate AWS JFR diagnostic of
the capped GFD case, using the same frozen source, `profileCli=true` and
`parallel=1`. The submitted specification is `experiments/gfd-probe-profile.json`.
Its timings are excluded from campaign estimates. The recording will identify
which preparation operation consumes the time; the diagnostic result is pending.
