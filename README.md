<p align="center">
  <a href="https://eignex.com/">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner-white.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg">
      <img alt="Eignex" src="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg" style="max-width: 100%; width: 22em;">
    </picture>
  </a>
</p>

# Klause

Klause is a Kotlin constraint programming library: finite-domain
variables (bounded integers and Booleans) with arithmetic, comparisons,
logic, and global constraints (allDifferent, gcc, table, cardinality,
element, lex). Floats lower onto bucketed integers and nominals lower
onto Boolean indicators. Usable from MiniZinc as a backend solver via
the klause-mzn-lib package.

Two native engines, both implementing the same Solver and Optimizer
interfaces:

- A local-search solver (adaptive probSAT default; WalkSat, DDFW,
  simulated-annealing, CCA variants also available) for sampling and
  stochastic solve. The default.
- A complete CSP backtrack solver with propagation, configurable
  variable and value heuristics, and true model-blocking enumeration.

Optional adapter modules (klause-logicng for bit-blasted SAT,
klause-z3 for SMT) let the same problem be shipped to an external
solver when it helps; they're side doors, not the core.

Unlike most CP libraries, sampling is first-class. Drawing samples
with replacement and enumerating without replacement are core
operations, not afterthoughts. The combination of a constraint
language, two native engines, and a sampling API is the niche: most
CP libraries solve once and stop; klause is built for repeated,
diverse, and incremental queries against the same constraint system.

Klause is not a MILP solver (objectives are linear over integers, not
reals), not a full SMT solver (theory is finite-domain integers and
Booleans, no bitvectors, arrays, strings, or quantifiers), and not
intended for proving program properties. For those, reach for a MILP
solver, Z3 or CVC5, or a verification framework respectively.

## Use cases

Klause is aimed at problems where a constraint system is the model and
the question is "give me some valid configurations" rather than "prove
this assertion holds". Concretely:

- Constraint-aware test or fuzz input generation. Generate inputs that
  satisfy structural invariants (allDifferent, table-encoded relations,
  reified comparisons) so the downstream test exercises behaviour
  rather than rejecting on input validation.
- Diverse input sets for differential testing. Draw many valid samples
  that are spread across the feasible region rather than clustered
  around one corner.
- Configuration synthesis. Find a system configuration (feature flags,
  resource caps, routing weights) that satisfies a set of business
  rules, with optional weighted-objective ranking.
- Scheduling and assignment. Tasks to machines, students to rooms,
  campaigns to budgets, given side constraints. Add a linear objective
  to get cost-minimal feasibility.
- Plan verification. Check that a proposed assignment satisfies all
  declared constraints, and surface why it fails when it doesn't.

## Schema

```kotlin
class CampaignSchema : VariableSchema() {
    val type    by nominal("a", "b", "c")
    val budget  by intVar(min = 1000, max = 4000)
    val bonus   by intVar(min = 0, max = 500)
    val rate    by floatVar(min = 0.0, max = 1.0)

    init {
        constraint((type eq "a") implies (budget + bonus le 2000))
        constraint(2 * bonus le budget)
        constraint((rate ge 0.5) implies (budget ge 2000))
    }
}
```

The DSL covers:

- Boolean: and, or, implies, iff, not, xor.
- Nominal: eq and ne against label literals.
- Integer arithmetic: signed +, -, unary -, *, /, %, with Euclidean
  division and modulo (remainder is always non-negative, matching
  SMT-LIB QF_LIA) and variable-by-variable multiplication.
- Comparisons: le, lt, ge, gt, eq, ne over arbitrary integer expressions.
- Counting: atMost, atLeast, cardinality, pseudoBoolean and friends.
- Global: gcc, allDifferent, circuit, subcircuit, cumulative, disjunctive.
- Tabular: table, notTable.
- Integer expressions: min, max, abs, element, ifThenElse.
- Linking: channel, lexLeq, lexLt.

## Solving

```kotlin
val schema = CampaignSchema()
val compiled = schema.compile()
val solver = LocalSearchSolver(compiled.problem)

solver.enumerate(LocalSearchParams(maxFlips = 100_000)).take(20).forEach { s ->
    println("type=${compiled.decode(schema.type, s)} budget=${compiled.decode(schema.budget, s)}")
}

val weights = LinearObjective(boolWeights = doubleArrayOf(/* ... */))
val best = solver.minimize(weights, LocalSearchParams(maxFlips = 100_000))

// Or point at one schema variable, MiniZinc-style:
val cheapest = solver.minimize(compiled.minimize(schema.budget), LocalSearchParams(maxFlips = 100_000))
```

Local search is the default. Swap in the backtrack solver when you
need completeness or true without-replacement enumeration.

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

## TODO

Each item is tagged with its workstream: `[LS]` local-search, `[CP]` complete CP backtrack + propagation, `[LS+CP]` cross-cutting, `[API]` cross-backend solver API, `[Sampling]` model counting / uniform sampling, `[Format]` input format parsers, `[Backend]` external solver adapters, `[Perf]` post-benchmark optimization, `[Docs]`, `[Infra]`.

- `[Infra]` Maven Central publishing, CI.
- `[CP]` LCG: virtual int-bound literals for sharper int-domain reasons. Today every factor produces a clause-form `conflictReason` — bool-pinning factors emit factor-specific sharp clauses; int-domain factors fall back to a coarse "negate the current bool partial assignment" nogood (sound only when all decisions are bool, suppressed otherwise). The coarse clause is shrunk by analyzer minimization but still misses the int-bound facts that actually drove the conflict. Full LCG needs lazily-allocated bool literals like `[x ≥ k]` / `[x ≤ k]` so int-domain factors can return clauses that reference int facts directly, and the analyzer learns to resolve over them.
- `[CP]` Online heuristic learning for variable / value selection via [kumulant](https://github.com/eignex/kumulant)'s `BayesianLinearRegression` — LinUCB / Linear Thompson Sampling over per-(var, value) features (domain size, activity, depth, last-conflict distance, value offset, recency, propagator failure rate). Per-instance posterior only; cross-instance reuse goes through the portfolio-selector lane below.
- `[CP]` Pre-trained portfolio selector. Inspects the problem (factor-kind counts, domain shapes, graph topology, objective structure) and picks a `(VariableHeuristic, ValueHeuristic)` config — or bandit priors over a config palette — at session construction. Model is trained offline in a separate pipeline; klause exposes feature extraction + telemetry hooks and accepts the trained artifact as a constructor parameter. No disk reads, no DB, no implicit filesystem state inside klause.
- `[CP]` Stronger native propagators for globals still on weak (singleton-violation-check) propagation. Upgrade in priority order: Pesant layered-DAG for `Regular`; sweep-line for `Diffn`; STR2/STR3 / GAC-Schema for `Table`; flow-based for `Knapsack`; bound-consistency for `Inverse`; lex-conflict propagator for `LexLess` / `LexLesseq`.
- `[LS]` Multi-core LS portfolio finishing touches for the MZN Challenge LS track: best-feasible sharing as warm-start hints, shared kumulant stats in Relaxed mode for a restart-level bandit, worker-config factory handing each worker a distinct `(strategy, seed)`.
- `[LS]` ALNS: problem-specific destroy operators (cumulative time-window slides) and regret-based / best-improving construction repairs.
- `[LS]` ILS: basin-hopping-style large-jump perturbation and multi-parent / linkage-aware crossover.
- `[LS]` Auto-tune `NoiseController.ewmaAlpha` from problem size + flip budget so callers don't set it manually.
- `[LS]` Extend cost shaping past `LinearObjective`. Linear is O(1) coefficient lookup; arbitrary `Objective` subtypes need apply-revert with full re-eval per candidate — not justified for typical workloads.
- `[LS]` Optional SAPS strategy as a multiplicative-weighting alternative to additive DDFW. Low priority — DDFW covers the weight-learning niche.
- `[LS]` Richer VNS: VND (exhaust each k before promotion), per-level neighbourhood operators (swap-only at N2, 3-opt at N3), skewed-VNS accepting mild worsening at higher k.
- `[LS]` Problem-aware move generation for globals not yet covered: cumulative time-window slides, lex-aware moves for `lexLeq` / `lexLt`, reified-factor diversification. Extension point is per-factor `proposeRepairMoves`.
- `[LS]` Apply LinUCB / Linear Thompson Sampling inside LS itself (not just CP). Concrete targets:
  - `LinearThompsonStrategy` — replace WalkSat/ProbSat/DDFW hardcoded scoring with an *online* bandit over candidate moves; features: `breakScore`, `netDelta`, `shapedObjectiveDelta`, `tabu / confChange flags`, `lastTouched distance`, factor-graph degree. Posterior lives in `WarmState`, per-session only — cross-instance is the portfolio selector's job.
  - Adaptive parameter tuning — bandit over `noise` / `cb` / `increment` instead of `NoiseController`'s bump-on-stall.
  - Contextual ILS acceptance from `(objective_delta, stall_count, iteration_fraction)`.
  - ALNS `flipsPerIteration` — bandit over `{quick, standard, deep}` profiles by recent-improvement context.
- `[LS+CP]` Telemetry pipeline feeding the offline portfolio-selector trainer. klause emits per-session features (var/constraint fingerprint) and outcomes (final objective, time-to-best, conflicts, flips) as a serialisable record; downstream tooling collects + trains. klause persists nothing.
- `[Sampling]` Hash-based uniform sampling (UniGen2-style). XOR-slice the model space, sample within a slice.
- `[Sampling]` Approximate model counting (ApproxMC). Same XOR-hashing primitive as UniGen.
- `[Sampling]` Weighted projected sampling (WAPS / KUS).
- `[Docs]` Tutorial covering schema, constraints, and backend selection.
- `[Docs]` Dokka site for the KDoc.
- `[Format/SMT-LIB]` Parser covering the finite-domain integer subset (QF_LIA in SMT-LIB terminology). Lets klause run SMT-LIB benchmarks.
- `[Format/SMT-LIB]` Static bound inference for int vars declared with full-range or unbounded domains. Error out cleanly when no bound is provable.
- `[Format/SMT-LIB]` Distinct over arbitrary terms (booleans, mixed bool/int).
- `[Format/SMT-LIB]` to_real / to_int casts. Either bucket reals onto bounded ints or reject the benchmark.
- `[Format/SMT-LIB]` Let-binding expansion in the SMT-LIB parser.
- `[Format/SMT-LIB]` Unbounded integers in BacktrackSolver. Pairs with the bound-inference item above.
- `[Format/FlatZinc]` Set-var follow-ups: (a) reified union/intersect/diff if MZN models actually use them (rare — the unreified forms are the common path); (b) set_le/set_lt lexicographic ordering on the indicator bool arrays; (c) `var set of E: S = { ... }` initializer (constant set var) — today only `var set of E: S` with no value is accepted.
- `[Perf]` Native bitset set-propagators where benchmarking shows bool-decomposition's per-bool propagator dispatch is the bottleneck. For sets with large universes (>256 elements) and many set algebra operations per propagation cycle, bitset throughput on `(LB, UB)` representations beats N independent bool propagator calls by a constant factor. Cost is engine plumbing (set domain arrays in `PropagationState`, dedicated move type, snapshot extension). Revisit only with profiling data.
- `[Format/FlatZinc]` Read the paired `.ozn` mapping file so MZN enum tag names round-trip into klause's `nominal(...)` schema field without callers having to inject `klause_enum_labels(...)` annotations manually.
- `[Format/FlatZinc]` Full Challenge-corpus parse-pass test. Drive `mzn2fzn` over [minizinc-benchmarks](https://github.com/MiniZinc/minizinc-benchmarks) and [libminizinc/tests](https://github.com/MiniZinc/libminizinc/tree/master/tests/spec); assert every produced `.fzn` parses + compiles. Surfaces FZN-spec quirks (set vars, optional types, edge-case decompositions). Regression gate before Challenge submission; CI installs MiniZinc from the official tarball.
- `[Format/XCSP3]` Parser for XCSP3 XML, including extension tables and intension predicates.
- `[Backend]` klause-smt `Optimizer.minimize()` via JavaSMT's `OptimizationProverEnvironment` (Z3 / MathSAT5-only — others need an external linear-search loop).
- `[Backend]` klause-choco adapter. JVM-only wrapper around Choco-solver as a reference CP oracle and benchmark target. Mature global catalog, battle-tested FZN parser; klause-factor mapping is near 1:1.
- `[Backend]` klause-ortools adapter for OR-tools CP-SAT via Java bindings. SOTA CP-SAT performance but JNI-heavy + platform-specific natives — after Choco.
- `[Backend]` klause-yuck and klause-oscar-cbls adapters for the MZN Challenge LS track. [Yuck](https://github.com/informarte/yuck) (JVM Scala, simulated annealing) and [fzn-oscar-cbls](https://github.com/oscar-cbls/oscar) (JVM Scala, CBLS) consume FlatZinc directly, so integration is "shell out to their FZN binary" rather than factor mapping. Useful as benchmark oracles and fallbacks for problem classes that are their sweet spots.
- `[Backend]` klause-kissat / klause-cadical adapters. SOTA CDCL SAT (Kissat won SAT Competition 2020–2024), meaningfully faster than LogicNG's MiniSat on bit-blasted instances. Same lowering path as klause-logicng. No Maven Central artifacts exist, so this means hand-rolled JNI + bundled platform binaries — pick up once the rest of this TODO is done.
- `[Perf]` Audit `PropagationSession` snapshot cost. 9 array copies per push today (`boolValues`, `intDomains`, `boolLevel`, `intLevel`, `decisionVars`, `boolReason`, `intMinReason`, `intMaxReason`, `boolAntecedents`); consider pooling, a flat delta-trail journal, or a dirty-field bitmask copying only modified arrays.
- `[Perf]` VSIDS `pick` scans every unpinned variable (linear in `numBoolVars + numIntVars`). Swap for a bucket queue or pairing heap → O(log n); `bumpBool` / `bumpInt` reposition on activity update. Same upgrade for dom/wdeg (key by `wdeg/dom`).
- `[Perf]` Switch `Problem.factors` from List to Array if profiling shows virtual-dispatch / list-iteration cost.
- `[Perf]` Incremental make/break vectors for `LocalSearchState.breakScore` (probSAT / YalSAT style). Today `breakScore(BoolFlip(v))` calls `deltaIfBoolFlipped` over every clause containing `v` — O(occurrences × arity). `boolBreakCache` absorbs repeated reads but every flip invalidates the neighbourhood, so probSAT/DDFW keep cold-missing per restart. Maintaining `breakCount[v]` / `makeCount[v]` incrementally inside `applyBoolFlip` collapses queries to O(1) at O(Σ arity²) per-flip update cost — well amortised. Biggest single LS-SAT optimisation outstanding.
