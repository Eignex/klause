# Production arm campaign

Refs [#414](https://github.com/Eignex/klause/issues/414). This bounded campaign measures the shipped
finite portfolios at p1 and p4 with 300-second budgets. It changes no defaults. Baseline:
`5471419aa8c832ee1065468a6861bb203a1ff8c7`; measurement seam:
`52193ef906cfedac893b1df8390559ca0495b59e` ([plumbing PR](https://github.com/Eignex/klause/pull/2374)).
The measurement SHA is retained by remote branch `codex/arms-414-measurement-frozen`.

The nine AWS stages complete 714/714 cases: 238 optimal and 476 feasible but unproved,
with no failed commands, record errors, unsupported, unknown or invalid outcomes. CI finds no
pairwise proof contradiction or disagreement in 946 applicable frozen-reference comparisons.
Independent MiniZinc source checks find 77 valid distinct candidates and one unknown, with no
invalid or error result. There are 396 legacy
records without durable witnesses/source hashes; the other 318 retain both. OPB/XCSP3 witnesses
have internal solver checks and stored-reference checks, without an independent source gate.
The arm-count sweeps and integrated confirmation support retaining the shipped arm counts and
reseed threshold 3. Threshold 2 does not transfer consistently and loses one held-out proof.

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
`SequentialPortfolio`. The frozen baseline does not expose it through CLI. The measurement seam threads
`reseed-stale-threshold` through `PortfolioScenario` into the executor and reports it with per-arm
`reseeds`. Zero disables it. It affects resumable optimization after an incumbent, on both sequential
and parallel execution, and never discards a terminal verdict. Runs without such work provide no
reseed evidence. The first-solution core and deferred variants from #2359/#2360 remain intact.

Historical scripts are not measurement commands: fixed arms=6 at p4 is different from the current
default 8, and comparing default arms with arms=6 at p1 is an identical-setting comparison.
Oversubscription uses p1 6/12 and p4 4/8/12; it measures composition and execution together, not a
pure scheduler factor. No tuning of #1748 slice sizing is included. Construction budgeting #2328
was integrated by [PR #2375](https://github.com/Eignex/klause/pull/2375) during collection. The
original eight builds predate it; the confirmation uses the integrated implementation.

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

Pair summaries separate discovery, holdout and sentinel cohorts, with objective quality, feasibility
and proof deltas on distinct axes. A cell without two feasible objectives is unscored for quality.
Aggregate solve-clock timing uses the budget for a missing event; raw missing times remain absent.

Local builds, tests, benchmarks, profiles, lint and docs gates are skipped per execution policy.
No local solver, build or test/lint/docs/source-validation gate was started or interrupted.
GitHub CI validates the PR stack.

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

The first/best times in the original stages use the CLI attribution clock, which starts after
preparation; process elapsed time also includes startup and preparation. The integrated confirmation
build additionally retains observed subprocess arrival times. CI keeps both clocks separate and
exports direction-aware objective checkpoints at 1/10/30/60/120/300 seconds (and each run's final
budget). Missing observations stay null. The checkpoints describe the archived attribution stream;
the independent source gate checks final candidates.

## Recorded stages

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

Baseline job 875 is complete with 156/156 records. Oversubscription p1 job 881 and reseeding p1
job 879 are complete with 48/48 records each and no failed commands. Reseeding p4 job 880 is also
complete with 48/48 records. Pool sizing p4 job 882 completes 72/72 records without failed commands.
All eight original stages are complete, totaling 660 cases. Matched comparisons and source checks
run in GitHub CI; the integrated confirmation also completes all 54 cases, totaling 714 overall.

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

## Production baseline at 300 seconds

[CI analysis 38000373707](https://github.com/Eignex/klause/actions/runs/38000373707) covers every
record from job 875. All 156 cases are feasible, with no record errors, unsupported inputs or
pairwise proof contradictions. Each configuration has 39 cases over 13 inputs and three seeds.

| Portfolio | Optimal / feasible unproved | Median first incumbent | Median best incumbent |
| --- | --- | --- | --- |
| cp p1 | 18 / 21 | 334 ms | 37.981 s |
| mixed p1 | 13 / 26 | 335 ms | 119.919 s |
| cp p4 | 18 / 21 | 427 ms | 13.400 s |
| mixed p4 | 17 / 22 | 273 ms | 25.287 s |

The following cells show the minimum–maximum final objective over seeds, then the number of
proofs out of three in parentheses. Every objective minimizes. D/H/S denotes discovery, holdout or
the historical sentinel; identities and source paths are in `selection.json` and `cases.csv`.

| Input | Set | cp p1 | mixed p1 | cp p4 | mixed p4 |
| --- | --- | --- | --- | --- | --- |
| city-position | D | 20666–29438 (0) | 9985–23411 (0) | 14998–34399 (0) | 5029–6878 (0) |
| fast-food | D | 704 (3) | 704–740 (1) | 704 (3) | 704 (3) |
| CoinsGrid | D | 80 (3) | 80 (0) | 80 (3) | 80 (3) |
| Fortress | D | 459519–489530 (0) | 459518 (0) | 459518–469517 (0) | 459518 (0) |
| knapsack | D | -318 (3) | -318 (3) | -318 (3) | -318 (3) |
| linear ordering | D | 61 (0) | 61 (0) | 61 (0) | 61 (0) |
| depot-placement | H | 107 (3) | 107 (3) | 107 (3) | 107 (3) |
| amaze | H | 1429 (3) | 1429 (3) | 1429 (3) | 1429 (3) |
| CyclicBandwidth | H | 6–9 (0) | 6–9 (0) | 5–6 (0) | 5–7 (0) |
| BinPacking2 | H | 25 (3) | 25 (3) | 25 (3) | 25–26 (2) |
| MIPLIB lseu | H | 1120 (0) | 1128 (0) | 1120 (0) | 1128 (0) |
| logic synthesis exam.pi | H | 88 (0) | 84–88 (0) | 88 (0) | 84–87 (0) |
| celar | S | 1163–4010 (0) | 3867–6347 (0) | 165–201 (0) | 547–2812 (0) |

Against cp at the same width, mixed has 8 wins / 22 ties / 9 losses and five proof losses at p1;
at p4 it has 7 / 24 / 8 and one proof loss. The problem-mean quality is -0.026 at both widths,
with descriptive intervals [-0.256, 0.231] and [-0.333, 0.308]. Mixed p4 against mixed p1 has
12 / 26 / 1, five proof gains and one loss. These outcomes document coverage and tradeoffs, not a
default change selected on held-out data.

CP catches one p1 and two p4 segment exceptions in `bt/lp-lbtree`; mixed catches none. All recorded
fault counters are zero. Legacy baseline records have no durable witnesses or plain source hashes:
all 156 assignments lack an independent source recheck. Baseline reseed counters are also absent;
an analysis sum of zero with zero `reseedObservations` means unavailable, not disabled.

## Observed arm contribution

Baseline final holders are distributed across BT heuristics: `bt/satOptimized` holds 9/39 cp p1,
8/39 cp p4, 14/39 mixed p1 and 13/39 mixed p4 incumbents. `alns/balanced` holds 8/39 mixed p1 and
2/39 mixed p4 incumbents. These labels fold replicas only for summaries; the original records retain
worker positions. Final ownership does not measure proof ownership or the effect of removing a worker.

| Observed worker | Cases present | Work units | Worker time | Work-weighted mean reward | Final holders |
| --- | --- | --- | --- | --- | --- |
| cp p1 `bt/linucb` | 21 | 17168499 | 712.850 s | 0.02341 | 0 |
| mixed p1 `ls/fjump/fixed` | 12 | 2473480 | 504.632 s | 0 | 0 |
| cp p4 `bt/first-fail` | 9 | 24706460 | 531.329 s | 0.01161 | 0 |
| mixed p4 `ls/cbls-chain/ils-basin` | 27 | 4076396 | 1459.936 s | 0.02498 | 0 |

The table identifies observational long-tail candidates. `bt/linucb` still earns root-fixing,
clause-use and improvement credit; CBLS chain earns intermediate improvement and violation credit.
The p1 fjump rows have no recorded contribution credit, but occur on only four families. Work units
and rewards reflect each mechanism's accounting, and should not be treated as comparable search
rates across engines. Arm removal needs a matched counterfactual on discovery and held-out families;
no removal is justified by final-holder counts alone.

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

## Completed p1 reseeding sweep

[CI analysis 37999618925](https://github.com/Eignex/klause/actions/runs/37999618925) mines all 48
records from job 879. Every record is feasible with a retained witness and source hashes; there are
no caught arm exceptions, refuted-claim faults or pairwise proof contradictions. All 37 distinct
MiniZinc candidates across the completed witness jobs pass the source gate.

| Threshold against default 3 | Objective wins / ties / losses | Proof gains / losses | Problem mean quality | Descriptive 95% interval |
| --- | --- | --- | --- | --- |
| off (0) | 1 / 8 / 3 | 1 / 0 | -0.167 | [-0.333, 0] |
| 2 | 3 / 7 / 2 | 0 / 0 | 0.083 | [-0.5, 0.75] |
| 4 | 3 / 7 / 2 | 0 / 0 | 0.083 | [-0.5, 0.75] |

Thresholds 2 and 4 win all three city-position seeds and lose two Fortress seeds. Off loses two city
seeds and one Fortress seed, wins one city seed, and proves the seed-3 linear-ordering objective 61
at 237.208 seconds. Every configuration proves the three knapsack cases at -318. These tradeoffs
do not support a general threshold replacement. The retained first/best timings distinguish early
feasibility from continued improvement: median first incumbents are 141.5/168/165.5/158 ms for
thresholds 3/0/2/4; median best times are 59.091/4.421/56.388/50.149 seconds. The short best time
for off includes earlier, worse incumbents, so it is not an optimization speedup.

Thresholds 3/0/2/4 record 134/0/204/93 actual reseeds. The off configuration performs no reseeding,
and the other values materially change execution. Per-arm initialization is retained separately;
these frozen builds predate the construction-accounting repair and its integrated measurements.

## P4 reseeding and integrated confirmation

Job 880 completes 48/48 feasible records with source hashes and witnesses. Thresholds 3/2/4 prove
the three knapsack cases; off proves those plus two linear-ordering cases. Per-record reference
checks in [CI analysis 38000958406](https://github.com/Eignex/klause/actions/runs/38000958406)
cover all 60 applicable stored rows with no disagreement or missing reference.
All 49 distinct MiniZinc candidates across the completed witness stages pass the independent source
gate. Thresholds 3/0/2/4 record 493/0/714/354 reseeds at p4.

| Threshold against default 3 | Objective wins / ties / losses | Proof gains / losses | Problem mean quality | Descriptive 95% interval |
| --- | --- | --- | --- | --- |
| off (0) | 0 / 8 / 4 | 2 / 0 | -0.333 | [-0.75, 0] |
| 2 | 3 / 9 / 0 | 0 / 0 | 0.250 | [0, 0.75] |
| 4 | 1 / 9 / 2 | 0 / 0 | -0.083 | [-0.25, 0] |

Threshold 2 wins every city seed (3297–4324 versus 5081–5805), ties Fortress at 459518 and ties the
OPB objectives. Off loses all three city seeds and one Fortress seed, despite its extra proofs.
Threshold 4 varies only on city, winning one seed and losing two. These are width-dependent
tradeoffs: threshold 2 loses two Fortress seeds at p1.

Before inspecting held-out variant outcomes, threshold 2 at p4 was selected for a single confirmation
stage, [AWS job 909](http://192.168.50.104:8420/jobs/909). It compares thresholds 3/2 on integrated
main `54101a2cb001c825d0a920779004f4b140a0b956`, including #2328, at 300 seconds and seeds 3/7/11.
The six frozen held-out families provide transfer evidence; city and Fortress repeat the discovery
signal, and CoinsGrid guards proof retention. This adds 54 cases, at most 4.5 solve case-hours
before setup. Both variants share the build, input bytes, default arm count, slice policy, presolve
policy and AWS allocation settings. No p1 threshold change is proposed.

Confirmation completes 54/54 feasible cases: 23 optimal and 31 unproved, with retained witnesses
and source hashes in every record. [CI analysis 38007048410](https://github.com/Eignex/klause/actions/runs/38007048410)
finds 27 complete matched cells, no excluded cells, feasibility changes, arm exceptions,
refuted-claim faults or proof contradictions, and no disagreement in 72 frozen-reference comparisons.
Threshold 2 is the candidate in the comparisons below; objective quality and proofs remain separate.

| Cohort | Objective wins / ties / losses | Proof gains / losses | Problem mean quality | Descriptive 95% interval |
| --- | --- | --- | --- | --- |
| Six held-out families | 2 / 14 / 2 | 0 / 1 | 0 | [-0.167, 0.167] |
| Discovery repeats and CoinsGrid guard | 1 / 5 / 3 | 0 / 0 | -0.222 | [-0.333, 0] |
| All nine inputs | 3 / 19 / 5 | 0 / 1 | -0.074 | [-0.222, 0.074] |

| Input | Threshold 3 objective range | Threshold 2 objective range | Candidate wins / ties / losses | Proofs at 3 / 2 |
| --- | --- | --- | --- | --- |
| city-position | 4347–11807 | 5160–5897 | 1 / 0 / 2 | 0 / 0 |
| Fortress | 459518 | 459518–469517 | 0 / 2 / 1 | 0 / 0 |
| CoinsGrid | 80 | 80 | 0 / 3 / 0 | 3 / 3 |
| depot-placement | 107 | 107 | 0 / 3 / 0 | 3 / 2 |
| amaze | 1429 | 1429 | 0 / 3 / 0 | 3 / 3 |
| CyclicBandwidth | 5–6 | 5 | 1 / 2 / 0 | 0 / 0 |
| BinPacking2 | 25 | 25 | 0 / 3 / 0 | 3 / 3 |
| lseu | 1120–1128 | 1128 | 0 / 2 / 1 | 0 / 0 |
| exam.pi | 84–85 | 83–88 | 1 / 1 / 1 | 0 / 0 |

The original three city wins do not repeat: threshold 2 wins seed 3 but loses seeds 7 and 11.
It also loses Fortress seed 3 (469517 against 459518). The held-out wins are CyclicBandwidth seed 3
and exam.pi seed 11; losses are lseu seed 11 and exam.pi seed 3. At depot seed 7, both reach 107,
but threshold 3 proves it in a 65.443-second process while threshold 2 exhausts 300 seconds unproved.
CoinsGrid retains every proof. The held-out objective balance and lost proof reject the candidate
as a default replacement.

Thresholds 3/2 record 1122/1731 actual reseeds. Both use the same installed fingerprint
`d839dbb10df225393fc78595c524aa876cb14b57ee21618e1d7853ed58b6b692`, runtime-file hash
`4f8040ee4b69b090ab741a4c31136ae71531132ad0304cd5de676a6d39508769` and p4 JVM options.
Median solve-clock first times are 285/271 ms; process first times are 1159/1172 ms. Median best
times are 26.639/53.719 seconds on the solve clock and 27.384/54.134 seconds on the process clock.
These are descriptive medians across heterogeneous families, with different final qualities and
proofs; they do not establish a speedup. Per-input checkpoints on both clocks, per-arm initialization,
work, reward, contributions and final-holder counts remain in `evidence/909/summary.json`, with
individual cases in `cases.csv` and the full attribution stream in the raw records.

The final CI source gate checks 78 distinct MiniZinc candidates across all durable-witness stages:
77 valid, one unknown, zero invalid and zero errors. The unknown is job 909 case 24, amaze seed 3
at threshold 3. Its model/data hashes match, but the archived DZN uses compiler-introduced
`X_INTRODUCED_16_`, which the pinned source compiler cannot resolve. This candidate remains
internally checked and reference-consistent, without an independent source-valid claim. The
compiler verdict, exact reason and source hashes are archived under `evidence/ci-38007048410/`.

## Completed p4 pool-size sweep

Job 882 completes 72/72 feasible records with witnesses and matching paired source hashes, no arm
exceptions or refuted-claim faults, and no proof/reference contradictions. [CI run 38001835657](https://github.com/Eignex/klause/actions/runs/38001835657)
analyzes all 660 original cases and independently checks all 67 distinct MiniZinc final candidates.

| Portfolio / requested arms against default 8 | Objective wins / ties / losses | Proof gains / losses | Problem mean quality | Descriptive 95% interval |
| --- | --- | --- | --- | --- |
| cp / 4 | 1 / 6 / 5 | 2 / 0 | -0.333 | [-0.75, 0] |
| cp / 12 | 1 / 8 / 3 | 0 / 0 | -0.167 | [-0.333, 0] |
| mixed / 4 | 0 / 6 / 6 | 3 / 0 | -0.500 | [-1, 0] |
| mixed / 12 | 2 / 8 / 2 | 0 / 0 | 0 | [-0.25, 0.25] |

Four-arm proofs are extra linear-ordering proofs at the unchanged objective 61. Mixed four-arm
pools lose all city and Fortress seeds; cp four-arm pools lose every Fortress seed and two city
seeds. Mixed twelve-arm pools win two city seeds, lose the third and lose one Fortress seed.
There is no consistent discovery advantage to carry into a held-out pool-size confirmation.

Four-arm configurations run whole arms at p4 and record no reseeds; they also omit the appended
mixed ALNS worker. Default/expanded cp pools record 437/439 reseeds and mixed pools 509/554.
Consequently the four-arm comparison includes composition, allocation and resumability together.
Its extra proofs do not isolate a scheduler cause or outweigh the incumbent-quality losses.

## Default decision

Retain the production free and full portfolios, requested arm counts 6 at p1 and 8 at p4, and
reseed threshold 3. Expanded pools have no consistent quality or proof advantage; four-arm pools
trade incumbent quality for extra proofs. The preselected p4 threshold-2 candidate fails integrated
confirmation, and p1 already has Fortress regressions. No arm removal follows from observational
credit or caught exceptions. No slice, construction, presolve or scheduling policy change is shipped
by this campaign. The telemetry seam and archived evidence make future bounded comparisons
reproducible; the broad #414 epic remains open.
