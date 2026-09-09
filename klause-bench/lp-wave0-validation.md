# LP Wave 0 baseline and reference reconciliation

This audit was prepared from base `848cfef9b6661a5b3eb941de006b2050bca44c5b` and the campaign
starts from integrated prerequisite `72d81101f71773ec38b8c6b84aacd5c09b5a9867`. It preserves the
reported Wave 0 baselines, freezes reproducible slices, corrects the test-only independent validator,
and separates the production observer overhead gate from capture, codec and validation costs.

## Evidence provenance

The raw result directories named by the earlier tasks are not present in this checkout, the saved
workspace, any current Codex worktree or `/tmp`. They therefore cannot be independently re-analysed.
The commands and reported results below survive in merged PR descriptions and archived local Codex
session logs. The logs are supporting audit material, not repository artifacts and not a substitute
for retained raw results.

| Evidence | Retained report | Raw artifacts |
|---|---|---|
| CP/MILP counters, [PR #1935](https://github.com/Eignex/klause/pull/1935) | Source `45900aea6bf0800571dd261952aa93f13ae9abe9`; 35 shared instances, 3 paired repetitions, 3 s timeout. LP default solved 18 vs 17, proved 4 vs 4, Borda 9.57 vs 8.43, comparable time +0.58%. LP off solved 18 vs 17, proved 3 vs 3, Borda 8.94 vs 9.06, comparable time -0.01%. Zero crashes or unsound results. | Absent. The `pr1935-final4-r1..3` and base directories are no longer retained. |
| SMT counters, [PR #1940](https://github.com/Eignex/klause/pull/1940) | Source `9f0e6f2582fc41e028ad7f5845d51dc0da11684e`; one 30 s run over six `smtlib-core` fixtures. Four SAT, one UNSAT, and `lia-wide-span` errored while attempting to enumerate `[1, 9223372036854775806]`. | Absent. `klause-fixed-p1-t30s-9f0e6f258` is no longer retained. |
| Replay, [PR #1943](https://github.com/Eignex/klause/pull/1943) | Label `w0.4-replay-v1`; six workloads, eight result events, seven validated, one declined, zero refuted. Three repetitions reported 5.80 ms with codec persistence versus 3.68 ms in memory. | Absent. The timing was codec persistence and never satisfied the instrumentation-overhead gate. |

The archived task logs used for the audit are:

- `/home/rasmus/.codex/archived_sessions/rollout-2026-09-07T09-55-23-01a07add-5f5c-76f2-9bc6-fab229c78afb.jsonl`
- `/home/rasmus/.codex/archived_sessions/rollout-2026-09-07T21-10-09-01a07d47-25b6-7961-a130-574518a6d1de.jsonl`
- `/home/rasmus/.codex/archived_sessions/rollout-2026-09-08T08-10-50-01a07fa4-034b-78d2-8cdf-d32f394219f1.jsonl`

The CP/MILP selection contained 19 MiniZinc instances, four of which were excluded for source
incompatibility: `2008/nmseq/020`, `2009/still_life/still_life_5`,
`2010/wwtp_random/ex05100_2600_100`, and `2026/tdtsp`. The 15 shared MiniZinc instances, four
`xcsp3-core` instances, four `mps-core` instances and twelve MIPLIB instances are frozen in
`lp-wave0-manifest.json`.

## Reference coverage at the base

Table hashes, exact frozen instance IDs, source hashes, tool versions and the MiniZinc corpus commit
are in `lp-wave0-manifest.json`.

| Required suite | Adapter route | Committed oracle coverage | Reconciliation |
|---|---|---:|---|
| `smtlib-qflra` | `bench reference` → Z3 | 1,753 `smtlib-qf_lra` rows | Existing broad oracle coverage; frozen 10-instance check slice. |
| `smtlib-qflira` | `bench reference` → Z3 | 7 `smtlib-qf_lira` rows | All seven rows exist, but the deterministic per-family slice selects one family representative. |
| `smtlib-qflia` | `bench reference` → Z3 | 13,306 `smtlib-qf_lia` rows | Existing broad oracle coverage; frozen 10-instance check slice. |
| `smtlib-qfidl` | `bench reference` → Z3 | 0 | Missing. The frozen slice has 10 instances; the full corpus has 2,528. |
| `smtlib-qfrdl` | `bench reference` → Z3 | 0 | Missing. The frozen slice has six instances; the full corpus has 255. |
| `mzn-bench` | `bench reference` → MiniZinc CP-SAT | 0 current `mzn-challenge` rows | Missing for the current suite. The 16,292 `minizinc-benchmarks` rows belong to the retired corpus ID and do not cover it. |
| `xcsp3-core` | `bench reference` → CPMpy/CP-SAT container | 0 | Missing. The four static fixtures are frozen. |
| `mps-core` | `bench reference` → SCIP container | 0 | Missing. The four static fixtures are frozen. |
| frozen `miplib2017` | `bench reference` → SCIP container | All 12 selected rows | Covered at 10 s in the 1,007-row table. Several are timeout/unproved rows, which remain in the denominator. |

The format adapters already route SMT-LIB to Z3, XCSP3 and MiniZinc to CP-SAT, and MPS to SCIP.
No missing adapter logic was found. The gaps are missing oracle executions or a corpus-ID mismatch, so
the campaign changes only the producing solver's oracle table and the owned validation tooling. In
particular, SMT reference generation uses `bench reference`; `backend=z3` is not an equivalent `bench
solve` baseline.

The original printed "full" commands were not full: every dynamic suite is constructed with a default
one-instance-per-family cap, even when no `per-family=` filter appears in the command. Read-only
preflight established the true inventories as 1,753 LRA, seven LIRA, 13,306 LIA, 2,528 IDL and 255
RDL instances. Sorted membership hashes live in the manifest and are rechecked from `preview`. The
existing table covers every current LRA, LIRA and LIA ID. Full IDL/RDL coverage would require 2,783
runs and is explicitly deferred as unreasonable for Wave 0; the bounded gate does not claim it.

Oracle execution uses the frozen `per-family=1 max=10 seed=1` slices: ten IDL and six RDL instances,
plus 19 current MiniZinc Challenge, four XCSP3 core and four MPS core instances. That is 43 attempts
and 1,290 seconds (21 min 30 s) worst-case CPU time at `jobs=1 workers=1`. Existing valid LRA, LIRA,
LIA and selected MIPLIB rows are not rerun merely to produce new labels. Unknown and timeout rows are
retained and count as coverage rows, but never as proofs.

The campaign runs on a busy shared host without an exclusive reservation or idle-host gate. At most
two independent suites run concurrently, with one worker per solver. Reference commands may overlap
only when they write different CSV tables; writers targeting the same table run serially. Wall times
are retained as descriptive, noisy context and are not regression or performance gates. Oracle
verdicts, witnesses, frozen coverage and deterministic work counts are the primary evidence.

The baseline matrix contains 39 CP/MIP instances: all 19 selected MiniZinc entries, including the four
known source-incompatible Klause cases, plus four XCSP3, four MPS and twelve MIPLIB entries. The four
MiniZinc incompatibilities run once in both controls to preserve eligibility and the 39-instance
coverage denominator; repetitions two and three contain the 15 compatible MiniZinc entries. Three
uncached default/off repetitions therefore make 218 CP/MIP attempts (654 seconds worst case), with a
35-instance shared-compatible denominator.

The five SMT slices contain 37 instances. `SmtLibMode` selects bound closure and the open exact-theory
route before search without reading the CLI LP emphasis; its exact LRA/LIRA/difference components do
not construct the floating LP engine. Fixed search also rejects `--lp default` and accepts only `off`,
so a default/off A/B is neither runnable nor an LP comparison. The baseline freezes one uncached
`engine=fixed` configuration with no `--lp` flag for three repetitions: 111 attempts and 3,330 seconds
worst case. It measures exact-theory stability only. The combined baseline ceiling is 3,984 seconds
(1 h 6 min 24 s); bounded independent suites may overlap under the scheduling policy above.

## Campaign result

The retained campaign measured `3378fa6e2aa3dbeab2e34d203ffe41f90284c4db`. It issued all 43
missing-reference attempts and committed 43 rows: 16 Z3, 23 CP-SAT and four SCIP. Thirty-six rows are
decisive; the other seven are two IDL UNKNOWNs, one MiniZinc UNKNOWN and four explicit MiniZinc source
incompatibilities. The full-coverage gaps that remain are 2,518 IDL and 249 RDL instances. Reference
serialization temporarily added an empty `logic` column; the retained raw snapshots preserve that
output, while the committed tables remove the empty column so all prerequisite rows remain unchanged.
The MiniZinc `still_life_5` source puts its `maximize objective` solve item after a multiline search
annotation that the lightweight classifier misses. Its source hash and direct process failure are
retained, and the committed unknown row records the source's maximizing sense.

| Suite | Reference outcome | Measured baseline outcome per repetition |
|---|---|---|
| `smtlib-qflra` | Existing 1,753-row full oracle; no rerun. | Stable: 3 SAT, 1 UNSAT, 6 UNKNOWN. |
| `smtlib-qflira` | Existing seven-row full oracle; no rerun. | Stable: 1 UNKNOWN. |
| `smtlib-qflia` | Existing 13,306-row full oracle; no rerun. | Stable: 4 SAT, 6 UNKNOWN. |
| `smtlib-qfidl` | 10 rows added: 8 decisive, 2 UNKNOWN. | Stable: 2 SAT, 1 UNSAT, 7 UNKNOWN. |
| `smtlib-qfrdl` | 6 decisive rows added. | Stable: 1 SAT, 1 UNSAT, 4 UNKNOWN. |
| `mzn-bench` | 19 rows added: 14 decisive, 1 UNKNOWN, 4 source-incompatible. | LP default feasible `[5, 1, 4]`; LP off `[5, 3, 5]`, over the 15 compatible instances. |
| `xcsp3-core` | 4 decisive rows added. | Both arms stable at 4 feasible and 1 proven. |
| `mps-core` | 4 decisive rows added. | Both arms stable at 3 feasible and 2 proven. |
| frozen `miplib2017` | All 12 existing rows reused. | LP default feasible `[3, 0, 1]`; LP off `[1, 0, 3]`; neither arm proved an instance. |

The CP/MIP aggregate over the 35 compatible instances was feasible `[15, 8, 12]` for LP default and
`[13, 10, 15]` for LP off, with three proofs in each arm in every repetition. The 210 result rows plus
eight recorded MiniZinc source-incompatible attempts account for the planned 218 attempts. SMT
contributed 111 rows, with an identical result signature in all repetitions. Across all 321 solver
result rows there were zero oracle verdict, impossible-objective or proof conflicts. Six
`sum-opt-tiny` records, both arms in all three repetitions, say `maximize=false` although the XCSP3
source and CP-SAT oracle say maximize. Their objective 10 and witness are correct, but those records
are excluded from objective-direction claims. Busy-host elapsed times are intentionally not promoted
to a speedup or regression claim.

The original reference sweep retained driver logs and derived CSV snapshots, but not the external
solver streams. A separate post-review recovery reran the same frozen 43-instance selection at the
measured SHA: 39 successful process records retain the exact command and raw output, while four
per-instance records retain the source-incompatible MiniZinc command, stdout, stderr and nonzero exit.
All decisive results and SMT unknown classifications agreed. Two unproven MiniZinc incumbents varied
(`751` versus `757`, and `480` versus `512`); the committed table keeps the stronger original
incumbents and makes no proof claim for either. Recovery elapsed times are descriptive and non-gating.

The campaign directory also retains 321 `.out` files, 321 `.json` files, 39 baseline CSVs, every
baseline command and stdout log, the preflight ID inventories and source hashes, tool/container/host
metadata, repair provenance for the two corpus-path snapshot keys, per-run checksums and a final
campaign checksum. The campaign audit checks exact reference additions, exact baseline identities,
CSV/JSON agreement, measured SHA and deterministic settings, oracle outcomes, stable SMT signatures,
the six documented objective-sense metadata mismatches, exact reference-solver arguments and
content-addressed keys derived from the frozen model/data bytes, and every nested checksum.

## Independent reference semantics

`LpReferenceAdapter` is the single test-only independent exact LP reference. It reconstructs an exact
rational model from the normalized `LpModel` seam, using exact `Long` values and exact IEEE-754 values.
It does not call the float engine or production certifiers.

The replay validator previously enumerated integer assignments for integer-backed models. That could
compare a continuous LP result with a bare integer optimum. It now delegates every replay problem to
the exact rational adapter, including active-row masks. Directed coverage fixes `2x >= 1`, `0 <= x <=
1` at the LP optimum `1/2`, rejects the wrong integer optimum `1`, and verifies active-row behavior.

The independent claims are deliberately distinct:

- exact infeasibility validates a production infeasibility only when the production step carries its
  proof; an uncertified gated result validates only the float candidate hint;
- a certified integral lower bound remains `CERTIFIED_BOUND`, not a claim that the integer optimum is
  the continuous LP optimum;
- an exact feasible witness validates `PROVED_OPTIMUM` only when its independently reconstructed
  source objective equals the attained reference bound;
- a valid feasibility-only witness remains `FEASIBLE_WITNESS` when the objective is unbounded, even if
  the legacy production verdict is labelled `OPTIMAL`;
- a strict, unattained infimum validates only `FEASIBLE_WITNESS`;
- probe-bounded feasibility can validate a witness, but open-model infeasibility or objective
  comparison declines;
- cancellation, pivot exhaustion and non-finite input decline instead of deciding.

`LpTestSupport.exactLpOptimum` remains a read-only production-simplex test helper despite its name. It
is not used as an independent exact oracle and was outside this task's frozen paths.

## Instrumentation evidence and contract

The v1 run measured only the optional production `LpCertificationObserver` seam at source
`1f1bd19a24cd181dcae27013a2465a51cc23b1b1`. The host was 96.8% idle immediately before the
coordinated window. Both arms had the same semantic digest, 1,300 pivots and 268,500 work operations.
The median paired delta was -2.08%, but the baseline samples were only 6.45–22.26 ms, the active
samples were 6.85–30.85 ms, and the paired range was -21.16%..67.75%. That timing scale and spread are
inconclusive, so v1 is not a 5% gate pass even though its raw median is below 5%.

The complete v1 samples are preserved in the ignored local artifact directory
`klause-bench/output/lp-wave0-validation-1f1bd19a24cd181dcae27013a2465a51cc23b1b1/`:

- baseline ns: `[18392788, 19974485, 22260151, 10313531, 6899133, 8682755, 6446782, 7651630, 8105646]`;
- active ns: `[30854005, 17389746, 18315129, 11954063, 9638230, 6845356, 7892940, 7492182, 7298727]`;
- paired delta %: `[67.75, -12.94, -17.72, 15.91, 39.7, -21.16, 22.43, -2.08, -9.96]`.

The v1 auxiliary medians, excluded from the observer gate, were 2.42 ms for 100 capture batches,
5.98 ms for 50 codec round-trips, and +3,928.65% for exact independent validation over one replay
slice. These quantify different work and are not instrumentation overhead.

The v2 contract used v1 only as duration calibration and ran once at source
`a51dcab07fe77802fbd2bbfd720d884c65dcc2b9` in a coordinated window after the host measured 96.4%
idle. Both arms had the same semantic digest, 65,000 pivots and 13,425,000 work operations. The
baseline median was 275.45 ms and the active median was 272.29 ms. Its paired median was -0.77% and
paired IQR was 2.08 percentage points, but both medians missed the predeclared 300 ms validity floor.
V2 is therefore inconclusive and is not a 5% gate pass.

The complete v2 samples are preserved in the ignored local artifact directory
`klause-bench/output/lp-wave0-validation-v2-a51dcab07fe77802fbd2bbfd720d884c65dcc2b9/`:

- baseline ns: `[271931284, 274533352, 282503578, 274133499, 324225634, 284448459, 276344454, 275448337, 266359239]`;
- active ns: `[269832122, 272294240, 283713572, 273638602, 281635335, 296033031, 270095688, 265051633, 265201142]`;
- paired delta %: `[-0.77, -0.82, 0.43, -0.18, -13.14, 4.07, -2.26, -3.77, -0.43]`.

The v2 auxiliary medians, excluded from the observer gate, were 3.32 ms for 100 capture batches,
2.95 ms for 50 codec round-trips, and +3,564.65% for exact independent validation over one replay
slice. These quantify different work and are not instrumentation overhead.

The v3 contract doubled only the observer batch to 10,000, predicting roughly 0.55 s arms from the v2
medians. Before any v3 results, it fixed these rules:

- label `w0-instrumentation-v3`; one standalone solve over each of the six `w0.4-replay-v1` model
  shapes per batch (the replay-only cancellation, pivot and gated-event controls are not available at
  the production observer seam);
- `ProductionLpEngineFactory` / `RevisedSimplex`, `componentSplit=false`, identical models and solve
  settings in both arms;
- baseline arm passes no observer; active arm counts certification, exact-input and solve-metric
  events;
- both arms use an identical test-only metrics wrapper, and every pair asserts equal semantic digest,
  pivot count and work count;
- 10,000 batches per arm, three warm-ups and nine alternating paired repetitions;
- validity requires both arm medians to be at least 300 ms and paired-delta IQR to be at most 10
  percentage points; no pair is discarded;
- statistic: median of the nine paired percentage deltas; pass threshold remains at most 5%;
- no Gradle build-cache reuse, no benchmark cache and no external solver process.

V3 ran once at source `9cc50232e69936868d864897ce70b25f3dd037f4` in a fresh coordinated
window after the host measured 96.92% idle. Both arms had the same semantic digest, 130,000 pivots
and 26,850,000 work operations. The baseline median was 536.09 ms and the active median was 531.26
ms. Its paired median overhead was +0.27%, with a 3.11 percentage-point IQR. Both arm medians exceed
300 ms, the IQR is below 10 percentage points and the overhead is below 5%, so the production
observer overhead gate passes.

The complete v3 samples are preserved in the checksummed ignored local artifact directory
`klause-bench/output/lp-wave0-validation-v3-9cc50232e69936868d864897ce70b25f3dd037f4/`:

- baseline ns: `[561239552, 542558082, 547170631, 529565820, 537264076, 536093549, 523696503, 508183033, 500458939]`;
- active ns: `[547200134, 547569226, 531661340, 531256808, 530986108, 537523027, 512238244, 516932501, 510318538]`;
- paired delta %: `[-2.50, 0.92, -2.83, 0.32, -1.17, 0.27, -2.19, 1.72, 1.97]`.

The v3 auxiliary medians, excluded from the observer gate, were 3.02 ms for 100 capture batches,
2.77 ms for 50 codec round-trips, and +4,413.72% for exact independent validation over one replay
slice. These quantify different work and are not instrumentation overhead.

Capture construction, codec round-trip, and exact replay-validator incremental cost are reported as
three separate `LP_AUXILIARY_COST` lines. None is included in the 5% observer gate. The ordinary JVM
test returns immediately unless `KLAUSE_LP_INSTRUMENTATION=1`, keeping the default test below 300 ms.

The Wave 0 observer overhead gate is closed by v3. Full Wave 0 remains open on the missing reference
coverage described above. The runner records the source SHA, host, runtime, raw log, extracted result
lines and checksums under a distinct run-specific directory. It refuses to overwrite an existing
directory and retains those artifacts even when a validity or overhead assertion rejects the run. An
earlier v3 invocation at source `09c04268e4fb7eddfe9dfa4129fd0324dfd40b7e` restored an ordinary
test result from Gradle's build cache; the runner rejected the missing instrumentation line, and that
invocation supplied no timing sample.

## Reproduction

From the repository root:

```text
klause-bench/scripts/lp-wave0-validation.sh verify
klause-bench/scripts/lp-wave0-validation.sh preview
klause-bench/scripts/lp-wave0-validation.sh check
klause-bench/scripts/lp-wave0-validation.sh audit-campaign <measured-sha>
klause-bench/scripts/lp-wave0-validation.sh print-reference-commands
klause-bench/scripts/lp-wave0-validation.sh print-baseline-commands
klause-bench/scripts/lp-wave0-validation.sh init-campaign
```

The preserved instrumentation-v3 reproduction alone requires its original idle-host guard:

```text
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh instrument
```

The v3 generated-artifact path is
`klause-bench/output/lp-wave0-validation-v3-<git-sha>/`. This task does not overwrite the preserved v1
or v2 artifacts or historical labels.
The reference and baseline commands freeze `jobs=1`, `workers=1`, `processors=1`, seeds and timeouts.
CP/MIP baselines use three uncached LP default/off repetitions, subject to the explicit MiniZinc
incompatibility policy above, and each pair is analysed with `klause-bench/output/compare.sh`. SMT uses
the one frozen uncached exact-theory configuration for three repetitions. Timeouts and declines remain
in their stated denominators.

Campaign commands retain evidence under
`klause-bench/output/lp-wave0-campaign-<measured-sha>/`. Initialization refuses an existing campaign
directory. Reference and baseline subcommands refuse an existing run directory. Baseline result
directories and result CSV files are written through temporary symlinks at the bench's standard
generated paths and live only in their campaign run directory; the symlinks are removed after each
arm. Every run retains its command, raw runner stdout, exit status, result CSV, per-instance `.out` and
`.json` files where the bench emits them, selected IDs, source hashes where applicable, and checksums.
The campaign root also retains the manifest, exact preview inventories, host/runtime/tool versions,
container image metadata and MiniZinc corpus commit. Machine-specific detail stays in that artifact
tree rather than this report.

Per the user's direct instruction for this task, no Opus review is run. An independent Codex Sol
high-reasoning review found the missing process streams, count-only finalizer and objective-sense
metadata issues described above; this report and the retained evidence incorporate those findings.
This task-specific exception is not a completed integrated Wave 0 review.
