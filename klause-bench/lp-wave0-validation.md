# LP Wave 0 baseline and reference reconciliation

This audit was prepared from base `848cfef9b6661a5b3eb941de006b2050bca44c5b`. It preserves the
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
| `smtlib-qfidl` | `bench reference` → Z3 | 0 | Missing. The frozen 10-instance slice and full-suite command are prepared. |
| `smtlib-qfrdl` | `bench reference` → Z3 | 0 | Missing. The corpus has six families; all six are frozen. |
| `mzn-bench` | `bench reference` → MiniZinc CP-SAT | 0 current `mzn-challenge` rows | Missing for the current suite. The 16,292 `minizinc-benchmarks` rows belong to the retired corpus ID and do not cover it. |
| `xcsp3-core` | `bench reference` → CPMpy/CP-SAT container | 0 | Missing. The four static fixtures are frozen. |
| `mps-core` | `bench reference` → SCIP container | 0 | Missing. The four static fixtures are frozen. |
| frozen `miplib2017` | `bench reference` → SCIP container | All 12 selected rows | Covered at 10 s in the 1,007-row table. Several are timeout/unproved rows, which remain in the denominator. |

The format adapters already route SMT-LIB to Z3, XCSP3 and MiniZinc to CP-SAT, and MPS to SCIP.
No missing adapter logic was found. The gaps are missing oracle executions or a corpus-ID mismatch, so
this task does not change production benchmark adapters or oracle tables. In particular, SMT reference
generation must use `bench reference`; `backend=z3` is not an equivalent `bench solve` baseline.

Full SMT reference coverage remains open. The bounded slices make the missing formats executable and
reviewable, but they do not claim to replace the full commands printed by the runner. Generating those
oracles writes committed CSV files and must happen in a task that explicitly owns them.

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
- an exact feasible witness at an attained reference bound can validate `PROVED_OPTIMUM`;
- a strict, unattained infimum validates only `FEASIBLE_WITNESS`;
- probe-bounded feasibility can validate a witness, but open-model infeasibility or objective
  comparison declines;
- cancellation, pivot exhaustion and non-finite input decline instead of deciding.

`LpTestSupport.exactLpOptimum` remains a read-only production-simplex test helper despite its name. It
is not used as an independent exact oracle and was outside this task's frozen paths.

## Instrumentation contract

The required gate measures only the optional production `LpCertificationObserver` seam:

- label `w0-instrumentation-v1`; one standalone solve over each of the six `w0.4-replay-v1` model
  shapes per batch (the replay-only cancellation, pivot and gated-event controls are not available at
  the production observer seam);
- `ProductionLpEngineFactory` / `RevisedSimplex`, `componentSplit=false`, identical models and solve
  settings in both arms;
- baseline arm passes no observer; active arm counts certification, exact-input and solve-metric
  events;
- both arms use an identical test-only metrics wrapper, and every pair asserts equal semantic digest,
  pivot count and work count;
- 100 batches per arm, three warm-ups and nine alternating paired repetitions;
- statistic: median of the nine paired percentage deltas; pass threshold: at most 5%;
- no benchmark cache and no external solver process.

Capture construction, codec round-trip, and exact replay-validator incremental cost are reported as
three separate `LP_AUXILIARY_COST` lines. None is included in the 5% observer gate. The ordinary JVM
test returns immediately unless `KLAUSE_LP_INSTRUMENTATION=1`, keeping the default test below 300 ms.

Measurement result: pending the coordinated idle-host window. The runner records the final SHA, host,
runtime, raw log, extracted result lines and checksums under the run-specific directory approved for
this task. It refuses to overwrite an existing directory.

## Reproduction

From the repository root:

```text
klause-bench/scripts/lp-wave0-validation.sh verify
klause-bench/scripts/lp-wave0-validation.sh preview
klause-bench/scripts/lp-wave0-validation.sh check
klause-bench/scripts/lp-wave0-validation.sh print-reference-commands
klause-bench/scripts/lp-wave0-validation.sh print-baseline-commands
```

After reserving an idle host window:

```text
KLAUSE_LP_IDLE_WINDOW=1 klause-bench/scripts/lp-wave0-validation.sh instrument
```

The approved generated-artifact path is
`klause-bench/output/lp-wave0-validation-<git-sha>/`. This task does not overwrite historical labels.
The reference and baseline commands freeze `jobs=1`, `workers=1`, `processors=1`, seeds, timeouts,
LP default/off controls and three uncached repetitions where timing is involved. Use
`klause-bench/output/compare.sh` on each paired default/off result directory; timeouts and declines
remain in the denominator.

Per the user's direct instruction for this task, no Opus review is run. This is a task-specific review
exception, not a completed integrated Wave 0 review.
