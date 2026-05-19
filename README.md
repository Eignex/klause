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
- `[CP]` LCG: extend conflict reasons to non-Clause factors. The CDCL core is complete on the Clause side — first-1UIP analysis, self-subsuming-resolution clause minimization, persistent learned-clause storage with watcher integration, LBD scoring per clause, restart-driven forgetting (glue-clause retention + LBD-priority pruning under `BacktrackParams.maxLearnedClauses` / `lbdGlueThreshold`), and cascading-CDB engine loop. The remaining piece for mixed-CP strength is conflict reasons + antecedent recording on `Cardinality` (natural first one — literal-based), `Linear`, `AllDifferent`, etc. Each factor needs a `conflictReason` (clause-form nogood for its failure case) plus an antecedent-recording variant of its propagation. Until those land, the analyzer correctly bails to chronological backtrack on non-Clause failures, so existing behaviour is preserved.
- `[CP]` Online heuristic learning for variable / value selection via [kumulant](https://github.com/eignex/kumulant)'s `BayesianLinearRegression` — LinUCB / Linear Thompson Sampling over a per-(var, value) feature vector (domain size, activity, depth, last-conflict distance, value offset, recency, propagator failure rate). Posterior is per-instance; klause itself doesn't persist learned state to disk (no DB, no filesystem assumptions). Cross-instance reuse, when wanted, lands via the offline-trained portfolio-selector lane below — the trained artifact is what carries knowledge between instances, not on-line solver state.
- `[CP]` Pre-trained portfolio selector. Inspects the problem's variables and constraints (counts per factor kind, domain shapes, graph topology, objective structure) and chooses a `(VariableHeuristic, ValueHeuristic)` config — or initial bandit priors over a palette of configs — at session-construction time. The selector itself is an ML model trained offline in a separate pipeline (klause exposes feature extraction + telemetry hooks; downstream tools run the training pass and produce the artifact). At runtime the solver accepts the trained artifact as a constructor parameter and consults it once per session; no disk reads, no DB, no implicit filesystem state inside klause.
- `[CP]` Factor: regular(seq, automaton). Sequence accepts a given DFA. Used for shift patterns, rostering rules, sequence-of-letters constraints. Propagator is the classic Pesant 2004 layered-DAG construction. Already referenced under `[Format/FlatZinc]`.
- `[CP]` Factor: bin_packing(bins, weights, capacity). Item `i` goes into `bins[i]`; each bin's total weight stays under capacity. Propagator combines per-bin Linear with a no-fit reasoner. Already referenced under `[Format/FlatZinc]`.
- `[CP]` Factor: diffn(xs, ys, widths, heights). 2D rectangles don't overlap. Sweep-line propagator. Packing / floorplanning.
- `[CP]` Factor: among(n, xs, S) and count(n, xs, v). Number of `xs[i] ∈ S` (or `== v`) equals `n`. Decomposable via Cardinality + reified equality literals, but a native factor avoids the indirection. Same watched-literal scheme as [Cardinality] applies — opt into `Factor.initialBoolWatchers` with `n + 1` watches on the at-least-n side and `|xs| - n + 1` on the at-most-n side (over the reified `xs[i] ∈ S` literals), and call `state.moveBoolWatcher` from `propagate` when watches drift. The infrastructure is already shipped; the native factor inherits the speedup for free.
- `[CP]` Factor: inverse(f, g). `f[i] = j ⇔ g[j] = i`. Pairs of arrays that are functional inverses; common in assignment / symmetry-breaking. Standard channeling propagator.
- `[CP]` Factor: nvalue(n, xs). `n` equals the count of distinct values in `xs`. Useful for "use at most k colors" formulations.
- `[CP]` Factor: arg_max(idx, xs) / arg_min(idx, xs). `idx` is the position of the max / min element of `xs`. Tie-breaking via lex ordering. Useful in optimization decompositions.
- `[CP]` Factor: increasing(xs) / decreasing(xs) and strict variants. Monotone chains. Decomposable to pairwise comparisons but a dedicated propagator gives tighter pruning when domains overlap.
- `[CP]` Factor: value_precede_chain(values, xs). Value `values[i]` first appears before `values[i+1]`. Symmetry breaking on permutation classes. Linear-time propagator.
- `[CP]` Factor: sequence(low, high, k, xs, S). Every length-`k` window of `xs` has between `low` and `high` elements in `S`. Sliding-window cardinality, common in rostering. Each window is structurally a [Cardinality] over the reified `xs[i] ∈ S` literals, so each window can reuse the two-watched-literal scheme directly (`low + 1` at-least watches plus `k - high + 1` at-most watches per window). Native implementation choices: one Factor with internal per-window watch arrays, or `|xs| - k + 1` decomposed Cardinality factors sharing reified literals — the latter is simpler and gets the watched-literal speedup for free.
- `[CP]` Factor: knapsack(weights, profits, w, p). Linear `Σ weights[i] · xs[i] ≤ w` plus optimization on `Σ profits[i] · xs[i]`. Decomposable to Linear + LinearObjective, but a native factor allows specialised LP-relaxation bounding.
- `[LS]` Multi-core LS portfolio finishing touches for the MiniZinc Challenge LS track. The klause-portfolio coordination layer (Session-per-worker, race-first-feasible solve, fan-in sample flow, cooperative cancellation) is in place. Remaining: best-feasible-solution sharing between workers (broadcast as warm-start hints), shared kumulant stats in Relaxed concurrency mode for a restart-level bandit, and a worker-config factory that hands each worker a different (strategy, seed) pair.
- `[LS]` ALNS: problem-specific destroy operators (cumulative time-window slides) and regret-based / best-improving construction repairs on top of the shipped random + worst-objective + adjacency-related + currently-violated + activity-biased destroys and `InnerLsRepair` / `GreedyConstructionRepair`.
- `[LS]` ILS: basin-hopping-style large-jump perturbation (the existing adaptive ramp approximates this) and multi-parent / linkage-aware crossover beyond the shipped Uniform / BetterBiased value mixing.
- `[LS]` Auto-tune `NoiseController.ewmaAlpha` from problem characteristics (size, flip budget) so callers don't need to set it manually.
- `[LS]` Extend cost shaping past `LinearObjective`. Today's O(1) coefficient lookup makes shaping practically free for Linear; arbitrary `Objective` subtypes need apply-revert with full re-evaluation per candidate and the state churn isn't justified for the typical workload.
- `[LS]` Optional SAPS strategy as a multiplicative-weighting alternative to additive-transfer DDFW. Low priority — DDFW already covers the weight-learning niche.
- `[LS]` Richer VNS schemes beyond the shipped k-tuple Compound cycle: VND (exhaust each k before promotion), problem-specific neighbourhood operators per level (swap-only at N2, 3-opt at N3), skewed-VNS that accepts mild worsening at higher k.
- `[LS]` Problem-aware move generation for the globals not yet covered: cumulative time-window slides, lex-aware moves for `lexLeq` / `lexLt`, reified-factor diversification. Per-factor `proposeRepairMoves` is the extension point (AllDifferent / Linear / Product / Circuit / Cumulative / Disjunctive already have specialised generators).
- `[LS]` Apply LinUCB / Linear Thompson Sampling inside LS itself, not just CP search. Kumulant's `BayesianLinearRegression` with custom prior + population fit is in place; concrete LS targets that would benefit from feature-driven learning instead of hand-tuned schedules:
  - `LinearThompsonStrategy` — replace WalkSat/ProbSat/DDFW's hardcoded scoring with a learned bandit over candidate moves; features per move are `breakScore`, `netDelta`, `shapedObjectiveDelta`, `tabu / confChange flags`, `lastTouched distance`, variable-degree-in-factor-graph. Posterior lives in `WarmState` for the duration of a session; cross-instance reuse, if wanted, comes from the offline-trained portfolio selector seeding the prior at construction time — not from klause writing learned state to disk.
  - Adaptive parameter tuning — replace `NoiseController`'s hand-coded bump-on-stall schedule with a bandit over `noise` / `cb` / `increment` settings.
  - Contextual ILS acceptance — learn whether to accept worsening optima from `(objective_delta, stall_count, iteration_fraction)` features.
  - Context-driven `flipsPerIteration` for ALNS — bandit over budget profiles `{quick, standard, deep}` selecting which depth to invest given recent-improvement context.
- `[LS+CP]` Telemetry pipeline for the offline portfolio-selector training pass. klause emits per-session features (variables/constraints fingerprint) and outcomes (final objective, time-to-best, conflicts, flips) through a serialisable telemetry record; downstream tooling collects records across instances and trains the selector model. klause itself doesn't persist anything — it just exposes the feature extractor and a hook to receive the trained selector back at session construction.
- `[Sampling]` Hash-based uniform sampling (UniGen2-style). XOR-slice the model space, sample within a slice.
- `[Sampling]` Approximate model counting (ApproxMC). Same XOR-hashing primitive as UniGen.
- `[Sampling]` Weighted projected sampling (WAPS / KUS).
- `[Docs]` Tutorial covering schema, constraints, and backend selection.
- `[Docs]` Dokka site for the KDoc.
- `[Format/SMT-LIB]` Parser covering the finite-domain integer subset (QF_LIA in SMT-LIB terminology). Lets klause run SMT-LIB benchmarks alongside the FlatZinc / DIMACS / OPB ones it already handles.
- `[Format/SMT-LIB]` Static bound inference for int vars declared with full-range or unbounded domains. Error out cleanly when no bound is provable.
- `[Format/SMT-LIB]` Distinct over arbitrary terms (booleans, mixed bool/int). AllDifferent covers ints already.
- `[Format/SMT-LIB]` to_real / to_int casts. Either bucket reals onto bounded ints or reject the benchmark.
- `[Format/SMT-LIB]` Let-binding expansion in the SMT-LIB parser.
- `[Format/SMT-LIB]` Unbounded integers in BacktrackSolver. Pairs with the bound-inference item above.
- `[Format/FlatZinc]` int_div / int_mod with truncated-toward-zero semantics (FlatZinc spec, distinct from klause's internal Euclidean div). Currently fail-loud with a TODO message.
- `[Format/FlatZinc]` bool_and_reif, bool_or_reif, bool_xor_reif, bool_lt_reif. The int comparison _reif variants landed; bool side still missing.
- `[Format/FlatZinc]` Streaming branch-and-bound in solve minimize / maximize. The current FZN CLI does linear search over enumerate and tracks the best — works but doesn't prune. B&B once it lands in BacktrackSolver wires through.
- `[Format/FlatZinc]` LS-track conventions. Handle symmetry_breaking_constraint(...) and redundant_constraint(...) as no-ops when the engine is local-search, per MiniZinc Challenge LS rules.
- `[Format/FlatZinc]` Native propagators for globals that decomposition encodes poorly. Profile against klause-mzn-lib; regular and bin_packing are the remaining usual suspects. (`all_different_int`, `circuit` / `subcircuit`, `cumulative`, `disjunctive` / `disjunctive_strict` already dispatch to native klause factors.)
- `[Format/FlatZinc]` Set variables. MiniZinc decomposes set vars to indicator-bool arrays when the solver doesn't claim native sets; the FZN parser currently misdiagnoses the set declarations as "unbounded int". Either decline cleanly or claim set support and decompose at klause level.
- `[Format/FlatZinc]` Populate Problem.floatMetadata from FlatZincCompiler so FZN-loaded problems also get native-real Z3 handling. The schema path already does this; the FZN side still buckets inline without recording the real-valued sidecar.
- `[Format/FlatZinc]` Map MiniZinc enumerated types onto klause's `nominal(...)` schema field. MiniZinc enums (`enum Color = {Red, Green, Blue}`) lower to FlatZinc as integers `1..n` with the original tag names erased; klause already represents discrete-tag domains as `nominal` (string-tagged int vars with the labels round-tripped through compile/decode), so the right path is preserving the enum's name table at parse time and emitting a `nominal` field instead of a raw `int` domain when the var is enum-typed. Needs the FZN parser to retain the `output_var :: var_enum_value` annotations (or read the `.ozn` mapping file) so tag names survive.
- `[Format/FlatZinc]` Full Challenge-corpus parse-pass test. Drive `mzn2fzn` over [minizinc-benchmarks](https://github.com/MiniZinc/minizinc-benchmarks) and [libminizinc/tests](https://github.com/MiniZinc/libminizinc/tree/master/tests/spec); feed every produced `.fzn` to `FlatZincCompiler`; assert parse + compile success. Surfaces every quirk of the FZN spec the parser doesn't handle (set vars, optional types, edge-case decompositions). Run as a regression gate before any Challenge submission. CI installs MiniZinc via the official release tarball.
- `[Format/XCSP3]` Parser for XCSP3 XML, including extension tables and intension predicates.
- `[Format/DIMACS]` Weighted MaxSAT (.wcnf) loader.
- `[Backend]` klause-smt `Optimizer.minimize()` via JavaSMT's `OptimizationProverEnvironment` (Z3 / MathSAT5-only — backends without native optimization need an external linear-search loop). `SmtSolver` + `SmtSession` already ship with SMTInterpol as the pure-Java default, alongside klause-z3 for users who want the lean direct-Z3 path.
- `[Backend]` klause-choco adapter. JVM-only module wrapping Choco-solver as a reference CP oracle and competitive benchmark target. Mature global-constraint catalog and battle-tested FlatZinc parser; mapping from klause factors is near 1:1.
- `[Backend]` klause-ortools adapter for OR-tools CP-SAT via the Java bindings. State-of-the-art CP-SAT performance, but JNI-heavy and platform-specific natives — pick up after Choco.
- `[Backend]` klause-yuck and klause-oscar-cbls adapters for the MiniZinc Challenge LS track. [Yuck](https://github.com/informarte/yuck) (Scala on JVM, simulated-annealing core) and [fzn-oscar-cbls](https://github.com/oscar-cbls/oscar) (Scala on JVM, constraint-based local search) are the two reference LS competitors — both consume FlatZinc directly, so the integration shape is "shell out to their FZN binary" rather than a klause-factor mapping. Useful as benchmark oracles to compare klause's LS engine against on the Challenge corpus, and as fallback backends users can pick when their problem class is a known LS sweet spot for one of them.
- `[Backend]` klause-kissat / klause-cadical adapters. Kissat and CaDiCaL are SOTA CDCL SAT solvers (Kissat won SAT Competition 2020-2024); meaningfully faster than LogicNG's MiniSat on bit-blasted instances. Same lowering path as klause-logicng (bit-blast then dispatch). No Maven Central packages exist for either, so this means hand-rolled JNI bindings against the C source plus bundling platform-specific native binaries — substantial engineering. Probably only worth it once everything else on this TODO is done.
- `[Perf]` Audit PropagationSession snapshot allocation cost. 9 array copies per push today (boolValues, intDomains, boolLevel, intLevel, decisionVars, boolReason, intMinReason, intMaxReason, boolAntecedents); consider pooling, a flat delta-trail journal, or a per-field "modified-since-snapshot" bitmask that copies only the dirty arrays.
- `[Perf]` VSIDS pick scans every unpinned variable per call (linear in `numBoolVars + numIntVars`). For large problems, swap the scan for a bucket queue or pairing heap so `pick` becomes O(log n); `bumpBool` / `bumpInt` update the queue position when activity changes. Same upgrade applies to dom/wdeg (key by `wdeg/dom` ratio).
- `[Perf]` Switch Problem.factors from List to Array if profiling shows virtual dispatch / list iteration cost.
- `[Perf]` Make/break vectors for `LocalSearchState.breakScore` (probSAT / YalSAT style). Today `breakScore(BoolFlip(v))` cold-misses through `problem.boolOccurrences[v]` and calls `deltaIfBoolFlipped` on every clause containing `v` — O(occurrence-list × clause-arity). The lazy `boolBreakCache` absorbs repeated reads, but every flip invalidates the neighbourhood so probSAT/DDFW keep cold-missing across the whole var pool per restart. Maintaining `breakCount[v]` / `makeCount[v]` incrementally inside `applyBoolFlip` collapses the query to O(1); the per-flip update cost is O(Σ arity²) over touched clauses but well-amortised under typical SAT-LS access patterns. Biggest single optimisation outstanding for SAT-class LS workloads.
