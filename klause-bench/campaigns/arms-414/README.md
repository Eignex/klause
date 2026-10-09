# Production arm campaign

Refs [#414](https://github.com/Eignex/klause/issues/414). This bounded campaign measures the shipped
finite portfolios at p1 and p4 with 300-second budgets. It changes no defaults. Baseline:
`5471419aa8c832ee1065468a6861bb203a1ff8c7`; measurement seam:
`52193ef906cfedac893b1df8390559ca0495b59e` ([plumbing PR](https://github.com/Eignex/klause/pull/2374)).
The measurement SHA is retained by remote branch `codex/arms-414-measurement-frozen`.

## Frozen design

`selection.json` freezes six discovery and six holdout problems, two distinct families per
MiniZinc/XCSP3/OPB format on each side. City-position and fast-food are discovery cases;
depot-placement and amaze are holdout cases. A separate celar sentinel covers the fifth historical
family. Holdout families do not select variants. This small sample cannot support broad default
changes without confirmation. Source identities are the suite/problem pairs; reference rows are
snapshotted before measurement and are checks of verdicts and objectives, not comparable timings.

The wiring-only pilot uses `1795496d36dddf0ac97ababeb32ee0145e3ba49c`; the later measurement
build additionally archives final witnesses and source hashes outside CLI timing.

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
was integrated by [PR #2375](https://github.com/Eignex/klause/pull/2375) during collection. These
frozen builds predate it; policy candidates need measurements on that integrated implementation.

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

## Completed wiring pilot

[Job 855](http://192.168.50.104:8420/jobs/855) returned 240/240 feasible records, with no errors,
unsupported cases, failed commands, quarantines or reference/pairwise proof contradictions. Setup
and cases took 13.85 minutes in total. Thresholds 0/2/3/4 produced 0/120/76/57 reseeds at p1 and
0/292/188/140 at p4. The default pool has 6/8 requested arms at p1/p4; applicable mixed pools add
ALNS, giving 7/9 workers. Explicit mixed p4 arms=4 runs four whole arms and records no reseeds.
These are different executions, not aliases of the default.

All variants found witnesses on all six inputs at both seeds. The p1 default mixed pool proved
2/12 cases; its 12-arm pool proved 4/12. At p4 the default mixed pool proved 4/12, while its
four-arm pool proved 2/12. The differences are CoinsGrid proofs, not objective improvements.
No threshold changed proof counts in this pilot. Direction-aware incumbent wins vary by family
and seed; neither lower nor higher thresholds show a consistent advantage. The 300-second stages
retain these axes because the short-budget proof tradeoff needs confirmation.

The main/seam controls retain identical feasibility and proof counts, but some time-budgeted
incumbents differ. This pilot establishes wiring and infrastructure, not equivalence of wall-time
search trajectories or a default recommendation. `evidence/855/` contains complete matched raw
record snapshots, build/runtime hashes, exact CLI/bench commands and retrieval references. Legacy
main and wiring records have no final-witness/source-hash fields; their assignments cannot be
independently rechecked after AWS teardown. [Witness pilot 873](http://192.168.50.104:8420/jobs/873)
checks the durable-field build before policy sweeps.

[Baseline job 875](http://192.168.50.104:8420/jobs/875) measures frozen main on all 13 inputs,
300 seconds, seeds 3/7/11, p1/p4 and cp/mixed (156 cases). The bounded tuning selection takes
city-position, Fortress and OPB linear ordering as one active family per format, plus the
knapsack proof control. Reseeding has 96 cases and oversubscription 120. They run as separate p1
and p4 jobs, preserving the AWS worker's default case allocation for each width. Combined specs
are retained for reproduction and are not submitted. The short timing-repeat stage has 24 cases.
Worst-case solve work across these stages is 31 case-hours plus six minutes for repeats, before
setup. It is not a wall-time promise: instances grow and share quota according to the lab worker.

`generate.py` reproduces stage JSON. `collect.py JOB...` only retrieves lab state and
original record files (including fields an older lab API can omit). GitHub CI runs `analyze.py JOB...` to mine final
holders and work/reward/contribution credit, produces matched per-cell analysis in gzip JSON and
problem-cluster bootstrap intervals. An interval based on this few families is descriptive, not
population evidence. Each seed/repeat has one vote within its problem. Source witnesses are checked
in GitHub CI by the existing pinned-source compiler check with MiniZinc 2.9.4; valid, invalid,
unknown and missing checks remain distinct. This compiler gate does not run a reference solver and
its times are not campaign measurements.

## Submitted stages

Witness pilot 873 is complete: 24/24 feasible records retain a final candidate and source hashes,
covering both widths and both engines on all three formats. All eight MiniZinc candidates passed
the pinned-source constraint and objective check in [CI run 37992827225](https://github.com/Eignex/klause/actions/runs/37992827225),
with matching model/data hashes and no invalid or unknown verdict. Repeated controls 883 are also
complete, with 24/24 records and no failed commands. [CI run 37993711067](https://github.com/Eignex/klause/actions/runs/37993711067)
mines the repeats and checks all 14 distinct MiniZinc candidates from both jobs, including agreement
between the archived candidate's objective and its record. Every source check is valid.
Baseline 875 and the split sweeps are
submitted: reseeding [879](http://192.168.50.104:8420/jobs/879) / [880](http://192.168.50.104:8420/jobs/880),
oversubscription [881](http://192.168.50.104:8420/jobs/881) / [882](http://192.168.50.104:8420/jobs/882),
and repeated controls [883](http://192.168.50.104:8420/jobs/883). `jobs.json` records exact submissions.
The measurement SHA stays frozen despite later formatting-only CI repairs. No result from an
unfinished stage is a default recommendation.

Oversubscription p1 job 881 and reseeding p1 job 879 are complete with 48/48 records each and no
failed commands. Matched comparisons and source checks run in GitHub CI; the remaining 300-second
stages are still collecting records.

These measurements belong to the recorded SHAs. Exact-arithmetic normalization and Boolean
undo-lifetime fixes reached main during collection; rebasing the plumbing does not retrospectively
include those changes in the campaign. A shipped policy change needs confirmation on an integrated
build after construction budgeting #2328, with the same input bytes and held-out families.

## Identical-seed repeats

Job 883 repeats seed 3 three times per configuration at 15 seconds. Knapsack reaches and proves
objective -318 in all 12 cases; process elapsed times range from 499 to 589 ms. City-position remains
unproved in all 12 cases. At p1, cp returns 149925 and mixed returns 33156 in every repeat. At p4,
cp ranges from 24058 to 53680 and mixed from 19370 to 26274. Best-incumbent times range from
5.918 to 12.573 seconds for cp p4 and 1.450 to 12.600 seconds for mixed p4. Equal seeds do not
remove parallel search variability; small timing or objective differences need confirmation.

Within each width, the repeat configurations share the installed build fingerprint. Both widths
share the runtime-file hash; their fingerprints differ because `ActiveProcessorCount` is 1 or 4.
The recorded CLI options are `-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=N`.

## Completed p1 pool-size sweep

Job 881 has 12 matched cells per configuration: four discovery inputs at three seeds. Every cell
is feasible; each configuration proves only the three knapsack cases. All 48 records retain witnesses
and source hashes. [CI analysis 37997388472](https://github.com/Eignex/klause/actions/runs/37997388472)
compares 12 requested arms against the default six:

| Portfolio | Objective wins / ties / losses | Proof gains / losses | Problem mean quality | Descriptive 95% interval |
| --- | --- | --- | --- | --- |
| cp p1 | 2 / 6 / 4 | 0 / 0 | -0.167 | [-0.75, 0.25] |
| mixed p1 | 1 / 8 / 3 | 0 / 0 | -0.167 | [-0.75, 0.25] |

Both expanded pools lose city-position at all three seeds. Fortress varies by seed for cp; mixed
gains one Fortress incumbent and ties two. Both OPB inputs tie every seed. This is no case for
raising the p1 default, and the four-problem intervals do not establish a population effect.

The cp pools record one and two caught segment exceptions respectively, all in `bt/lp-lbtree`.
There are no refuted-claim faults or quarantines, no failed commands, and no pairwise proof
contradictions. Exception details were not retained by the frozen portfolio; the counts cannot
identify their cause. No arm-removal claim follows from these data.

In the default mixed pool, `bt/satOptimized` holds 10 of 12 final incumbents and `alns/balanced`
holds two. `ls/cbls/fixed` holds none but earns 1196836 units of intermediate Improvement credit.
The larger pool spreads final credit over additional workers while still losing city-position.
Final-holder counts and raw contribution credit measure different roles; neither is a removal
counterfactual. Per-arm time, work, initialization, segments, reseeds and work-weighted rewards are
retained in the CI analysis.
