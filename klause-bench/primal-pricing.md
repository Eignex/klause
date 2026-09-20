# Primal pricing investigation

Defer the primal pricing structure. No stratum passed the frozen requirement that replaceable
entering selection consume at least 10% of measured primal time. Production code, defaults and
public interfaces are unchanged; no heap, observer, flag or temporary runner remains.

Base: `549d129655a62991912a8914448514eb59b35037` (merged #2018).
Evidence: `/home/rasmus/Workspaces/lp-evidence/session-7.4/`, including the frozen manifest/contracts,
independent reviews, raw JFR/NDJSON, exact checkers, reproduction script and baseline discrepancy.

## Attribution and coverage

The fixed manifest has thirteen sources and two direct LP modes: explicit cold primal, and cold
`solve()` followed by objective replacement/adoption/`resolveBounds()`. Each mode has three alternating
unprofiled/profiled pairs. These source-derived continuous relaxations are distinct from the thirteen
complete CLI coverage controls. A designed objective edit does not establish frontend frequency.

JFR samples unchanged production code at a requested 1 ms period after five warmup lifecycles.
The denominator includes the primal subtree's setup, phase I, phase II, refactors and recovery.
Only resolved selection operations enter the lower numerator. Mixed dot/cost lines, ambiguous
selection descendants and unresolved primal lines contribute to the conservative upper numerator;
missing/truncated stacks cannot silently reduce the denominator. Reduced-cost computation, BTRAN,
basis work and the `allZeroCost` scan are excluded from replaceable selection. No operation counter
or instrumentation time substitutes for the time gate.

Of 156 planned process records, 108 contain measured batches, twelve decline Boolean/disjunctive
SMT assembly and 36 are correctness-blocked larger-source records. All remain in coverage accounting.
There are 48,874 primal samples, including 5,259 phase-I samples. The 26 source/mode strata comprise
five with enough samples and a descriptive upper Wilson bound below 10%, thirteen undersampled,
two unsupported and six correctness-blocked. There is no passing stratum.

| Source and mode | Primal samples | Resolved selection | Conservative selection upper | Upper Wilson bound | Paired median call / complete-lifecycle recording overhead |
|---|---:|---:|---:|---:|---:|
| afiro, explicit primal | 2,103 | 2.19% | 6.56% | 7.70% | +6.26% / +7.85% |
| afiro, cold plus objective | 2,189 | 2.70% | 8.31% | 9.55% | +6.64% / +7.87% |
| adlittle, explicit primal | 20,458 | 2.39% | 7.56% | 7.93% | +3.21% / +3.20% |
| adlittle, cold plus objective | 19,799 | 2.53% | 7.71% | 8.09% | +3.07% / +2.99% |
| zero_one_knapsack, explicit primal | 1,615 | 1.42% | 5.94% | 7.21% | +8.66% / +7.61% |

Only the two adlittle strata also satisfy the 5% recording-overhead guard. The other three numerical
below-gate results do not establish an uninstrumented baseline percentage. Samples can be correlated;
Wilson bounds are descriptive, and busy-host elapsed times are not uncontended performance evidence.
Adlittle explicit-primal complete-lifecycle medians are 4.36 ms control and 4.51 ms profiled, with
control/profile ranges 4.21–4.89 / 4.34–4.52 ms. Fixed duration limits produce unequal batch counts;
normalized per-role outcome/work/pivot distributions match across all measured pairs.

Actual routing matters: 19,572 of the 19,799 adlittle cold-plus-objective primal samples belong to
cold dual-infeasible repair; only 227 belong to objective-only continuation. The latter returns no
float optimum and only an exact feasible witness. Afiro has 427 objective-only primal samples.
Neither establishes a separately well-sampled warm-objective gate. Phase-I samples are a subset of
primal samples, and overlapping work ledgers are not added as disjoint total work.

## Correctness and larger-source blocker

Independent Fraction equations/bounds and exact complementary-dual/Z3 checks validate 162 internal
packages: 120 attained optima, 36 infeasibility packages and six feasible-only packages. All proof
solve signatures occur in the corresponding measured outcomes. Ninety original-MPS points satisfy
source constraints, and 36 small original-MPS objective/bound checks pass. The 36 CP packages remain
relaxation-only; eighteen MPS packages have no point. Forty-eight unsupported/blocked records remain.
Afiro/adlittle internal versus original decimal objective units and emitted equality failures remain
inherited limitations; these results do not establish their original-source optima.

The rebuilt baseline CLI claims `UNSATISFIABLE` on perold, 25fv47 and 80bau3b. An independent exact
80bau3b witness satisfies all 2,262 original rows and 9,799 column bounds, contradicting that verdict.
HiGHS 1.15.1 reports optima on all three; bounded exact perold/25fv47 checks, including basis-hinted
checks, time out and remain unresolved. This discrepancy was reported before timing conclusions.
The reviewed amendment retains all three sources but prohibits their pricing acceptance or timing.

One untimed diagnostic finds all three parse as continuous minimization models, route to `Finite`,
and pass root propagation, source presolve and finite presolve without infeasibility. Thus the
observed false verdict is not localized by `lpSolves=0`; the failure remains downstream of those
non-refuting stages. No unrelated production repair or larger-input timing retry was performed.
The other CLI controls include seven source-validation passes and one exact source refutation,
plus the two inherited decimal equality failures. The disputed 6.2 raw-rebind diagnostic is excluded.

## Causal finding and reconsideration

Both primal phases recompute eligible column scores on each iteration. There is no persistent
reduced-cost vector for a maximum cache to reuse. Phase I also changes its violation gradient after
bound flips. Phase II preserves ascending-index ties and switches to first-improving Bland selection
under existing stall recovery. Any future cache must preserve these policies and invalidate on
objective/status/bound changes, phase transitions, pivots, numerical rebuilds and recovery without
replenishing shared limits or allowing stale maxima to publish optimality.

GLOP at `98c165af62df62b3056c2ee0fca66b24e79097cb` maintains reduced costs/edge norms separately;
its `PrimalPrices` updates `DynamicMaximum` from sparse update-row positions. Its heap timing cannot
be transferred to Klause's dense recomputation loop. A heap alone would still pay that computation
and its own maintenance. This is a deferral for the measured scope, not rejection of primal pricing.

A concrete future hypothesis is that validated wider source routes with sparse reduced-cost changes
could justify incremental score maintenance and then a maximum cache. Establish those routes and
profile computation versus selection under a new reviewed contract before any implementation.
The larger correctness blocker, warm-objective coverage gap and retained adverse overhead results
must travel with that hypothesis; repeating this campaign cannot establish the missing benefit.
