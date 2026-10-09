# Presolve effort campaign (#554)

Status: AWS pilot queued; no defaults recommendation yet.

The solver baseline is `5471419aa8c832ee1065468a6861bb203a1ff8c7`. The neutral
controls build is `5ad3707c0c0bfb113f994828265b9f1bafef91c3` ([PR #2378](https://github.com/Eignex/klause/pull/2378)).
No local build, test, solve, benchmark, lint or documentation gate was run.
GitHub CI provides validation; klause-lab builds installed JVM distributions with
`:klause-cli:installJvmDist` and runs every campaign on AWS.

## Pilot

[Job 863](http://192.168.50.104:8420/jobs/863) takes 12 year/family groups from
`mzn-bench`, one instance per family with selection seed 554 and `reference=any`.
Seven configurations, solver seeds 3 and 11, two alternating repetitions and a
10,000 ms budget give 336 cases, at most 56 solver minutes before setup and launch
cost. Ordinary AWS parallel is unset: the worker supplies a spare physical core
per single-core case and allocates instances within its existing capacity.
Each matched problem's arms, seeds and repetitions stay on one instance.

`engine=cp`, `processors=1`, `bt-arm=satOptimized` pin the same CP policy.
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

## Reproduction and evidence

From the repository root:

```
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/campaigns/presolve-554/experiments/pilot.json
python3 klause-bench/campaigns/presolve-554/collect.py 863
```

`collect.py` downloads and gzip-archives raw lab API responses with their URLs,
uncompressed SHA-256 and size. Cases retain exact commands, git SHAs, installed CLI
and runtime hashes/options, elapsed time, validation policy and statistics. The
file listing supplies durable retrieval references for solver streams and setup
logs. Collect finished jobs again to replace partial snapshots. The live job's
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

The pilot's 12 year/family groups are only nine base families because families
recur across years. It is a bounded plumbing/cost screen with catalogue ordering,
not the representative discovery campaign. Bootstrap intervals in `analyze.py`
cluster by base family and average seeds/repeats within each cluster.
