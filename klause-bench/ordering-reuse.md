# Rational ordering reuse: B6c investigation

Retain the explicitly enabled default pending Wave 8's reviewed decision. The measured priority is to
reduce the cost of declining updated bases; this session makes no tuning or default change. Natural
eligible calls are scarce, ordering overhead exceeds factor savings in this cohort, and representative
full-search performance acceptance remains inconclusive.

## Reproduction and scope

Base: `856132ed0181660a372c466ee3e36fcbcfea9137` (merged cold-exact retirement, also fetched
`origin/main`). Corrected measurement head: `31b4ca1a6`; evidence is retained under
`/home/rasmus/Workspaces/lp-evidence/session-b6c/`. `manifest.json`, `compiled-inputs.json` and
`measurement-manifest.json` freeze input hashes, source/dependency hashes, commands, runtime and host.
Koblas uses the existing immutable pins in [the integration guide](../koblas-integration.md).

The explicit JVM runner is `com.eignex.klause.lp.engine.OrderingReuseInvestigation`. Compile with
`:klause:compileTestKotlinJvm`, obtain the JVM test classpath with the evidence directory's
`classpath.init.gradle` (`:klause:b6cClasspath --no-configuration-cache`), then run:

```sh
java --add-modules=jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
  -Xmx1g -Dklause.bench.cache=false -cp "$TEST_CLASSPATH" \
  com.eignex.klause.lp.engine.OrderingReuseInvestigation mps /path/to/afiro.mps
```

Kinds are `mps`, `cp` (compiled FZN), and `smt`. `--metadata-only` as the third argument runs one
untimed pair. `run-paired.py`, `check-results.py`, `dual-check.py`, `check-original-sources.py` and
`analyze.py` reproduce the complete evidence. These scripts and external inputs remain in the evidence
directory; the reusable runner and this report are repository artifacts.

The frozen 17 inputs comprise six MPS models (four smoke inputs, afiro, adlittle), three CP models
(graph coloring, knapsack, magic square), seven existing SMT sources and one separately labeled,
designed high-denominator SMT challenge. Two disjunctive SMT inputs decline direct conjunction
assembly and remain in the denominator. CP compilation is separately hashed and outside solve timing.

Each process performs one warmup and three alternating ON/OFF pairs, with one process at a time on a
busy shared host. Scoped calls use 1,024 pivots, two million float work units and seed 606. SourceLp
retains production pricing and its unchanged finite dimensions, scalar widths, 256-operation and
five-second active-time budget. The outer cancellation is ten seconds and the process cap 180 seconds.
All 17 processes completed; there were no process timeouts.

CP/MPS consumer timing includes file reading, production relaxation assembly, exact authority import,
new scoped ownership, first solve, unchanged repeat and disposal. SMT timing includes conjunction
parsing, native-bound assembly, SourceLp's first solve, repeat, row-zero activity solve and disposal.
Split and descending-direction helpers have separate fresh budgets/timers over the prepared rows.
These are complete **LP consumer invocations**, not complete CP/MILP/SMT search performance.

The untouched prerequisite CLI was rebuilt with `:klause-cli:installJvmDist` before a separate 17-input,
seed-606, single-worker, five-second coverage sample. All exited successfully and published no rational
basis calls. Its source/refinement telemetry has blind spots: this is observed zero, not proof of zero
natural activation. CLI wall times are not used for adoption.

## Natural production-policy coverage

The corrected observer captures scoped refinement basis suboperations after their existing accounting,
without forwarding them into budget observers. For each returned consumer invocation, its work,
factory/build/reuse/solve counts reconcile exactly with returned refinement LU metrics. The standalone
`LpSolve` request constructor is outside this hook. Events include declines and accounting-only visits;
an event is not a successful proof. Unexpected exceptions before recording can prevent an event.

Counts below exclude warmup and distinguish the two arms:

| Observation | OFF | ON |
|---|---:|---:|
| Consumer runs, including 12 disjunction declines | 51 | 51 |
| Returned proof packages | 108 | 108 |
| Accounted rational basis suboperations | 78 | 78 |
| Factory calls / builds / cache hits | 42 / 45 / 36 | 42 / 45 / 36 |
| Exact solves / aggregate precision restarts | 138 / 15 | 138 / 15 |
| Ordering offers / valid proposals / attempts | 0 / 0 / 0 | 30 / 6 / 6 |
| Updated-basis declines / hint fallbacks | 0 / 0 | 24 / 0 |
| Accounted basis work | 1,320,720 | 1,358,178 |

Natural rational work occurs on afiro, adlittle, tiny-interval, rational-strict-lira,
wide-bounded-sat and the designed high-denominator input. Only tiny-interval and high-denominator offer
fresh eligible orders, on their first calls. Afiro/adlittle and the activity calls offer updated bases.
The other nine supported direct inputs do not reach this rung; the CP slice is therefore inconclusive.
The 72 helper runs have no observed rational basis events. Natural calls have no terminal exact-basis
resource/candidate declines in this cohort; inactive routes and disjunction declines are still counted.

Cache hits skip ordering export entirely. Twelve ON factory calls have no live provider offer; the
other 30 produce six accepted proposals and 24 updated declines. Fresh versus updated eligibility is
established by the actual provider funnel, not inferred from owner state sampled after solving.

## Attribution and complete consumer cost

ON saves **177 factor work units** but spends **37,635 ordering units**: 37,212 identity,
315 export/copy/translation and 108 cache permutation validation. Of this overhead, **35,997 units
(95.6%)** precede an updated-basis decline. Net rational-basis work rises **2.84%**. Other exact assembly,
primal/dual solve, source verification and precision-restart counts match. The two eligible first-call
classes alone spend 19.7% more complete basis-verification work, despite lower factor work.

| Natural active input | Basis work OFF → ON per pair | Change | Complete consumer paired time ratio ON/OFF, median [min, max] |
|---|---:|---:|---:|
| afiro | 42,029 → 44,690 | +6.33% | 1.298 [1.003, 1.388] |
| adlittle | 363,170 → 370,965 | +2.15% | 1.072 [0.949, 1.074] |
| tiny-interval | 11,602 → 11,898 | +2.55% | 1.111 [0.777, 1.112] |
| rational-strict-lira | 6,827 → 7,523 | +10.20% | 0.972 [0.811, 1.014] |
| wide-bounded-sat | 4,812 → 5,254 | +9.19% | 0.682 [0.671, 0.820] |
| high-denominator, designed | 11,800 → 12,396 | +5.05% | 0.956 [0.892, 1.087] |

`summary.json` retains all timing samples, including inactive/declined inputs and helpers. Ratios use
the median of paired ratios, not a ratio of unpaired medians. Busy-host timings are descriptive;
large variation on inactive inputs is not evidence of an ordering effect. Instrumentation is present
in both arms and adds array/snapshot/callback cost. Phase charges partition modeled reservations,
not measured physical allocations or independent elapsed-time scopes.

Raw records separately retain scoped preparation/float work, SourceLp reservations, active time and
measured work, plus returned continuation/refinement work. These are overlapping/incomplete ledgers:
they must not be summed into a purported complete-work total. SourceLp allocation reservations are
not RSS. Complete consumer wall time includes preparation, certification, recovery and closure.
There is no established complete-consumer work win or no-regression timing pass under the frozen
rule-8 thresholds (at least 5% benefit, at most 5% regression, no lost certified results).

The separately labeled forced diagnostics perform ordinary numerical solving followed by direct exact
verification and a cache repeat. Across nine CP/MPS inputs they have 27 offers, nine proposals,
18 updated declines and zero hint fallbacks; full verification work rises 3.13%, while eligible cases
rise 14.99%. Cache-repeat work is identical. Export-only timings and complete verification timings are
retained. Forced results do not establish natural activation or improve the adoption denominator.

## Correctness and limitations

Independent Python Fraction checks validate equations, exact strict/nonstrict sides, objectives,
Farkas algebra and directions against emitted stored authority. Exact Z3 queries validate bounds,
helper claims and original SMT witnesses. All 141 nonwarm on/off packages match; all 376 records,
including warmup, pass their applicable checks. The first adlittle primal-bound oracle timed out;
that result is retained. A different independent complementary-dual check reduces it to one free
variable and establishes its bound, without Klause factors or a fresh-factorization oracle.

Original-source checking separately validates 104 emitted exact internal SMT points (including
warmup) and the CLI SMT/CP points. Of 15 complete feasible CLI assignments, 13 pass their applicable
original-source checks. Afiro/adlittle emitted decimal points fail exact original MPS equality checks
by 4.2e-14 and 6.1e-14 on the **untouched prerequisite**. Both failures remain in the denominator.
Baseline reproduction establishes inheritance only; rendering/decimal-authority conversion is a
suspected cause, and stored-relaxation validation does not establish original-source validity for
those two assignments. No overall source-valid MPS optimum claim follows.

The earlier observer-only campaign is retained in `initial-observation/`. A runner-only untimed
supplement exposed missing refinement activity; an independently reviewed observation correction and
reconciled smoke probe justified one corrected campaign on the unchanged workload/budgets. This was
not a repeat to improve timing. The inherited 5.7 decimal SMT rendering issue and the disputed 6.2 raw
internal rebind diagnostic remain separate; neither is repaired or used as passing evidence here.

## Wave 8 disposition

Keep ON under the user's existing policy; do not treat these timings as adoption evidence. The
specific measured tuning candidate is checking cheap updated-owner ineligibility before reserving
full identity validation, under a separate mathematical/lifecycle contract. Do not weaken exact
identity/permutation validation for eligible orders. Duplicate export copies and cache permutation
checks are measurable but small here; removing their ownership checks is unsupported. Exporting an
order from an FT-updated basis would require a new soundness contract and representative evidence.

Wave 8 should consume this attribution and its unresolved frontend/source-validation coverage,
not repeat this matrix. Any restriction/default change needs its own reviewed decision. No blanket
throughput, final basis-performance, full-search adoption or physical-allocation claim is established.
