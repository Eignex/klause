# Production arm campaign

Refs [#414](https://github.com/Eignex/klause/issues/414). This bounded campaign measures the shipped
finite portfolios at p1 and p4 with 300-second budgets. It changes no defaults. Baseline:
`5471419aa8c832ee1065468a6861bb203a1ff8c7`; measurement seam:
`1795496d36dddf0ac97ababeb32ee0145e3ba49c` ([plumbing PR](https://github.com/Eignex/klause/pull/2374)).

## Frozen design

`selection.json` freezes six discovery and six holdout problems, two distinct families per
MiniZinc/XCSP3/OPB format on each side. City-position and fast-food are discovery cases;
depot-placement and amaze are holdout cases. A separate celar sentinel covers the fifth historical
family. Holdout families do not select variants. This small sample cannot support broad default
changes without confirmation. Source identities are the suite/problem pairs; reference rows are
snapshotted before measurement and are checks of verdicts and objectives, not comparable timings.

Stage 1 is the 15-second, two-seed wiring pilot in `specs/pilot.json`,
[AWS job 855](http://192.168.50.104:8420/jobs/855). It compares production main and a seam-only build
at the default, then effective reseed thresholds 0/2/3/4 and distinct arm counts. Stage 2 establishes
300-second default free (`cp`) and full (`mixed`) coverage at p1/p4 with seeds 3/7/11. Later stages
sweep reseeding and oversubscription separately on discovery problems, pruning axes with no
executed work or persistent faults. A promising proposal needs held-out confirmation; a null result
retains the defaults. Paired arms alternate within each problem on the same AWS instance.

Every spec sets `host=aws`, omits `parallel` and keeps the default 3 GiB SerialGC CLI heap.
The lab selects c7i.2xlarge for p4 and reserves its configured capacity. The pilot's 240 cases
cost at most one solve case-hour before setup. Larger stages are bounded explicitly in their specs;
planning and measured elapsed times determine their estimates. There is no reference-solver arm,
local solve, profile, slice-budget edit, scheduling redesign or presolve change in this campaign.

## Parameter audit

`autoArms(cores)` is `max(6, 2*cores)`: p1 defaults to 6 and p4 to 8. `--param arms=N`
sets the composition count, clamped to at least the core count. Mixed optimization can append an
applicable ALNS worker, so composed and executed worker counts can exceed N. `ls`/`bt` select count
and engine mix; they do not pin an exact mixed split. Catalog composition remains model-dependent.

`reseedStaleThreshold` belongs to `Portfolio`, the current replacement for the historical
`SequentialPortfolio`. Main does not expose it through CLI. The measurement seam threads
`reseed-stale-threshold` through `PortfolioScenario` into the executor and reports it with per-arm
`reseeds`. Zero disables it. It affects resumable optimization after an incumbent, on both sequential
and parallel execution, and never discards a terminal verdict. Runs without such work provide no
reseed evidence. The first-solution core and deferred variants from #2359/#2360 remain intact.

Historical scripts are not measurement commands: fixed arms=6 at p4 is different from the current
default 8, and comparing default arms with arms=6 at p1 is an identical-setting comparison.
Oversubscription uses p1 6/12 and p4 4/8/12; it measures composition and execution together, not a
pure scheduler factor. No tuning of #1748 slice sizing is included. Construction budgeting #2328
is still open; concurrent [PR #2375](https://github.com/Eignex/klause/pull/2375) must be integrated
and measured before any policy recommendation is considered ready.

## Evidence and reproduction

Use `/home/rasmus/Workspaces/klause-lab/deploy/lab run <spec.json>`. Exact commands, frozen
specifications, selected inputs, job/arm/case metadata, runtime/distribution hashes and raw-output
retrieval references are retained under this directory. The lab rebuilds installed JVM distributions
using `:klause-cli:installJvmDist`; `installDist` is not a campaign build task. No lab update or
restart is part of the workflow.

Only complete matched problem/seed/repeat cells enter pairwise analysis. Missing, cancelled,
failed, unsupported, error, unknown, feasible and proved results are reported separately. Objective
quality and proof retention are separate axes; incumbent attribution and work-weighted arm reward
are observational contribution evidence, not arm-removal counterfactuals. Reference contradictions
must be resolved independently before a recommendation. Source witnesses use the existing solver
checks; reference agreement alone does not independently validate a witness.

Local builds, tests, benchmarks, profiles, lint and docs gates are skipped per execution policy.
No local workload was started or interrupted. GitHub CI validates the PR stack.
