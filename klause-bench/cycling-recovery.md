# Cycling recovery decision

Wave 8 final decision at `17671663b`: retire the always-on repeated-state detector proposal;
keep selective recovery deferred. The experiment failed its overhead gate and found no source
activation. Current production retains ordinary stall Bland and numerical safeguards, with no
optional detector. Existing tests cover cold/objective-warm stall recovery, exact basis certification,
iteration/work limits and cancellation. See the [final retention contract](lp-retention.md).

The measurements below describe the original 7.3 experiment; no new run or performance claim is made.
Base: `d39c302c65572dd2da7952d579ef77bd9676635f` (#2017, including #2015).
Evidence: `/home/rasmus/Workspaces/lp-evidence/session-7.3/`. `contract.md`, `contract-review.md` and
`observer-review.md` retain independent Astra/high review. `observer.patch`,
`CyclingRecoveryInvestigation.kt` and `reproduce.py` preserve the removed experiment and reproduction.

## Frozen source evaluation

Ten inputs: blend-tiny, feasible-tiny, infeasible-tiny, float-tiny, afiro and adlittle MPS;
graph_coloring and zero_one_knapsack compiled MiniZinc; lra-rational and lia-unsat SMT.
`manifest.json` fixes hashes/runtime/host, seed 606, 1,024 iterations, 2,000,000 work units and ten-second
invocation deadlines. Three alternating paired repetitions follow one warmup repetition, in separate
JVMs. Busy-host timings are descriptive, not steady-state JVM performance evidence.

These are source-derived continuous LP traces, not full search. Each supported input runs cold, warm,
objective replacement and eligible bound tightening/restoration. The baseline loads the untouched
archived base jar; existing stall Bland, scaling and ordering stay enabled. The CLI was rebuilt first.
No ordering campaign is repeated.

Each arm has 117 supported calls and 21 unsupported input/operation records across the three pairs.
Lra-rational declines Boolean/disjunctive assembly; afiro, adlittle and lia-unsat have no eligible finite
box for the declared bound edit. `run-manifest.json` identifies all records.

| Measure | Baseline | Observer |
|---|---:|---:|
| Float work | 684,747 | 947,571 |
| Pivots / refactorizations | 537 / 87 | 537 / 87 |
| Internal attained optima / feasible / infeasible | 90 / 3 / 24 | 90 / 3 / 24 |
| Certified bounds / numerical cap exits | 90 / 0 | 90 / 0 |

Detector work accounts for the entire **38.38% increase**, exceeding the frozen 5% ceiling. Its 678
observations span dual (126), primal I (96) and primal II (456), with zero revisits, collisions or
resource stops and 243 FIFO evictions. No source-campaign proof package or bound is lost. Longer
cycles than retained history can be missed; absence of observed repeats is not a general guarantee.

`default-checks.json` independently validates 432 internal packages across source/designed defaults
including warmup, with 56 unsupported records explicit. `source-checks.json` separately validates 160
exact original-MPS points; eight fractional integer-MPS points and 80 CP relaxation points are not
source assignments. Eight original-MPS cold/warm optimum/infeasibility checks pass. Afiro/adlittle's
four checks find internal versus original decimal objective differences on the untouched baseline:
these are not source-certified optima (`source-optima.json`). Earlier emitted-assignment failures
remain separate and unresolved.

## Designed diagnostics and limits

Integer-row Beale takes three default-scaled or seven unscaled pivots without revisiting. Canonical
fractional rows preserve the feasible region but change slack pricing. This separately declared,
nondefault diagnostic cannot reopen the failed gate. Cold and zero-to-Beale objective warm starts
each take 73 pivots, enter existing stall recovery once, and attain `-1` at `(1,0,1,0)`. Exact Gaussian
replay confirms 66 genuine zero-step revisits per solve, checking every intervening state. The source
identity `c*x = -x0 + 30*x1 + 42*x3 + 18*s1 >= -1` proves the bound. The dual counterpart takes three
pivots without revisiting; no dual-cycle benefit is demonstrated.

Caps 6/64 stop both arms before optimum. At work 1,000, baseline/observer take 17/12 pivots and stop;
refactors do not replenish allowance. One separate integer-row cancellation-after-12-polls diagnostic
loses a baseline certified bound because detector polling cancels earlier. Canonical cancellation
returns Indeterminate in both arms. `diagnostic-summary.json` preserves this loss separately from the
source campaign. No diagnostic timing benefit is claimed.

The observer compares full ordered headings/statuses after hash candidates. Invocation/phase/policy
scope prevents stale warm-state comparisons; accepted-transition serials exclude failed pivots and
numerical retries. Final capped transitions omitted by selection-boundary observation are recorded.
FIFO history is bounded by 32 snapshots/65,536 Int cells, with charged scans/copies in chunks of at most
1,024 cells. Direct checks cover forced collisions, status/reset negatives, retry deduplication,
eviction, capacity, work and scan/copy cancellation. Tiny trace logging is separate evidence overhead.
Generic saturation and actual numerical-retry integration remain unestablished.

No revisit-triggered recovery policy was implemented. Revival requires a representative expensive
stall/cycle that existing recovery does not handle economically and a selective activation hypothesis;
a cheaper observer alone is insufficient. Freeze numerical eligibility, complete basis/status/revision
identity, bounded history and charged scan/copy/poll costs. Demonstrate benefit against existing stall
behavior or verified uncovered primal/dual recovery while preserving the original cost/proof gates,
including the separate cancellation-bound loss. Minimum-index dual entering must remain ratio-test
admissible. Floating tolerances do not inherit Bland's exact termination theorem.

The live [RevisedSimplexCyclingTest](../klause/src/commonTest/kotlin/com/eignex/klause/lp/engine/RevisedSimplexCyclingTest.kt)
retains `stall recovery reaches the source optimum from cold and objective warm starts`,
`cyclic primal pivots cannot replenish the iteration allowance`, `cyclic primal refactors share the
work allowance`, and `cancellation during a primal cycle withholds the exact state`. These establish
existing recovery contracts, not benefit for an unimplemented selective policy.
