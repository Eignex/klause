# LP component split measurement

At solver revision `570949daf` (`arms.json` records the full commit), the first five-repeat
sample showed a 10.6% lower median subprocess duration on generated eight-block MiniZinc and
5.0% higher duration on eight-block MPS. These are preliminary whole-process differences,
not established gains or regressions: one host, one seed and five repeats do not characterize
noise. The linked control was effectively tied. Every repeated case returned a witness;
proof counts and final objectives agreed. The counters establish actual splitting, but
runtime conclusions require the duplicate-arm, balanced-order follow-up described below.

## Selection and method

The repeated measurements are lab jobs [793](http://192.168.50.104:8420/jobs/793),
[794](http://192.168.50.104:8420/jobs/794), and [795](http://192.168.50.104:8420/jobs/795).
They ran serially in the existing Mac lab queue with `parallel=1`, one solver processor,
seed 3, a 5000 ms solver budget, disabled result caching, engine `cp`, LP emphasis
`aggressive`, and presolve `off`. The installed CLI was built with `:klause-cli:installJvmDist`.
Both arms use the same pinned commit and differ only in `lp-component-split=true|false`.
Dense models use `exact=true` and five alternating repeats; corpus models use the default
precision and three alternating repeats. The node control also pins `max-decisions=500`.
The lab schedules each problem's arms back to back and rotates their order by problem.
No shared lab configuration or infrastructure was changed.

`host.json` records Mac aarch64, Temurin 25.0.4.1, Vector API SIMD (2 lanes) and Accelerate.
Each case retains installed jar/runtime hashes, buildFingerprint, command, seed, budget,
validation policy and runtime options. CLI options were `-Xmx3g -XX:+UseSerialGC
-XX:ActiveProcessorCount=1`. The records, raw streams, setup/command logs, resolved arm
hashes, selection and generated-source hashes are archived under the job-number directories.
The initial discovery revisions are recorded separately and are not pooled into the repetitions.

The self-authored generated models have independent width-n blocks with matrix
`nI + 11^T`, RHS `2n`, and bounds `[0,2]`. Each block has the unique solution `x=1`:
summing its rows gives `sum(x)=n`, and each row then gives `x[r]=1`. MPS uses a zero
objective; MiniZinc adds a finite `k in 0..1`, the row `k+x[1,1]>=1`, and minimizes
`x[1,1]`, whose source optimum is 1. The finite decision and real objective select CP
residual LP certification instead of exact open-theory solving. MiniZinc runs compile to
FlatZinc before the timed CLI invocation. The linked control adds one redundant row
`sum_b x[b,1]=blocks`, joining every block without changing the feasible set or optimum.

A small discovery run first confirmed nonzero counters: two/eight blocks on MPS in job
788, and two/eight blocks on the CP residual MiniZinc path in job 792. MIPLIB
`30_70_45_05_100` was chosen from an existing historical record with a nonzero split;
job 791 confirmed that some residual LPs after integer assignments actually split into
at most two blocks. The original MIP need not be globally separable. GeneralizedMKP is
the representative nonseparable, node-heavy control, rather than another zero-split sweep.

## Repeated results

Times are milliseconds, with the control (`split-off`) first. `elapsedMs` covers CLI
launch, loading, solving, output consumption and process exit. It excludes bench setup,
MiniZinc compilation and post-solve source validation. Time to best is the separate
incumbent timestamp, not proof completion. Budget-bound cases cannot establish a speedup
from subprocess duration alone. All individual samples and counters are in `summary.json`.

| Model | Repeats/arm | Decided off/on | Proven off/on | Median elapsed off/on | Median best off/on | Split calls on | Max blocks on |
| --- | ---: | --- | --- | --- | --- | --- | ---: |
| MPS 2x12 | 5 | 5/5 | 0/0 | 285/287 | 279/280 | 1 each | 2 |
| MPS 8x24 | 5 | 5/5 | 0/0 | 476/500 | 470/492 | 1 each | 8 |
| MiniZinc 2x12 | 5 | 5/5 | 5/5 | 319/319 | 125/126 | 2 each | 2 |
| MiniZinc 8x24 | 5 | 5/5 | 0/0 | 1023/915 | 311/281 | 6 each | 8 |
| MiniZinc linked 8x24 | 5 | 5/5 | 0/0 | 1041/1042 | 332/333 | 0 | 0 |
| MIPLIB 30_70_45_05_100 | 3 | 3/3 | 0/0 | 5106/5108 | 2016/2057 | 10, 5, 6 | 2 |
| GeneralizedMKP control | 3 | 3/3 | 0/0 | 2271/2334 | 495/502 | 0 | 0 |

On MiniZinc 8x24, subprocess ranges were 1003–1029 ms off and 913–925 ms on,
with reported solve-time medians 831/723 ms and best-incumbent medians 311/281 ms.
These five launches favor splitting, pending an independent noise calibration. MPS 8x24 ranges were
473–480 ms off and 481–507 ms on. The same continuous matrix can therefore repay the
split on one consumer path and cost more on another; setup, certification and consumer
behavior are included. There are five repeated launches of each problem, not five
independent corpus samples, and no significance or population-wide claim is made.

MIPLIB reached objective 400 in all six runs and proved none. Its median time to best
was 2.0% worse with splitting, while reported nodes ranged 59927–62656 on and
50836–56229 off. This is more search within the same budget without improved answer
quality on the selection. Component work varies with the number of residual solves;
node LP passes were zero. This case supplies a real corpus split, not evidence for
per-node bounding overhead.

## Node path and labeling overhead

Every GeneralizedMKP repeat, on both arms, reported objective 22688, 3006 nodes,
1779 node LP passes, 25472493 LP work operations and 62219997 LP overhead operations.
No component split occurred. Median elapsed differed by 2.8% (2334/2271 ms), but
those identical deterministic work counts do not attribute it to component labeling.
The linked MiniZinc control likewise found no split, with elapsed medians 1042/1041 ms.
Neither small timing difference establishes a labeling cost separate from total solve cost.

The issue's original assumption of union-find labeling on every node LP construction
is stale at this revision. [solveNode](https://github.com/Eignex/klause/blob/570949daf/klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpBounding.kt#L144)
uses the retained propagation owner or `newPersistentLpSolver`; both are monolithic.
[newPersistentLpSolver](https://github.com/Eignex/klause/blob/570949daf/klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolver.kt#L372)
does not consult the split switch. Residual leaf sessions instead use
[newRetainedLpSolver](https://github.com/Eignex/klause/blob/570949daf/klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolver.kt#L404),
which does component labeling when it installs a model. Thus this measurement checks
node-heavy switch behavior, not an obsolete per-node general-solver construction loop.

Existing instrumentation has no dedicated component-labeling duration or count of
unsuccessful split attempts. Isolating the O(nnz) labeling time from factorization and
certification would require engine/statistics instrumentation outside this issue's
benchmark ownership. The end-to-end A/B and linked control are the available evidence;
no engine or statistics implementation was changed.

## Source checking and exclusions

`analyze.py` independently checks every emitted generated-model witness against all
original matrix rows, bounds, the finite decision and the linked row, rather than using
split counters as correctness evidence. All 50 repeated dense-model witnesses passed;
maximum original-row residual was 0 for these exact-mode outputs. A manual checker probe
accepted the known witness and rejected a changed coordinate. MiniZinc source validation
in the bench remains `unknown` because output uses introduced array names; these explicit
source-equation checks supply independent evidence. No solver proof credit was upgraded:
the larger MiniZinc models returned objective 1 without a proof in both arms.

Job 788's default bounded-float MiniZinc runs used grid approximation and reported a
grid refutation on satisfiable sources; source validation withheld that proof and
recorded unknown, with no LP activity. They were excluded. Job 790's exact satisfaction
models selected open-theory solving and had no LP split counters, so they were also
excluded. Their archived records expose those route mismatches. Job 792's real-objective
models reached the intended residual LP path. The report does not count a theoretical
block structure as a measured split.

## Reproduction

From the repository root:

```sh
python3 klause-bench/scripts/lp-component-split/generate.py
./gradlew :klause-cli:installJvmDist
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/reports/lp-component-split-1455/dense-ab-spec.json
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/reports/lp-component-split-1455/miplib-ab-spec.json
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/reports/lp-component-split-1455/node-ab-spec.json
python3 klause-bench/scripts/lp-component-split/analyze.py klause-bench/reports/lp-component-split-1455/793/cases.json klause-bench/reports/lp-component-split-1455/793/raw
```

The specs pin the measured revision, so later report-only commits do not change their
solver or source. The lab creates its own isolated worktree, rebuilds the installed CLI,
and preserves its provenance. Re-run the analyzer for directories 794 and 795 to recover
the corpus summaries. The generated-source hashes can also be checked at the recorded
commit; external instances are identified by the fixed suite/problem and fetched through
the existing corpus workflow.

Targeted catalog tests passed. Full local `check lintDocs` was skipped; GitHub CI is the
full gate. The measurement uses existing switches and counters without changing solver policy.
