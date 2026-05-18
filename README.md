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
- `[CP]` Propagation: Product reverse direction for non-singleton operands. Singleton-operand reverse landed; the general interval-division case destabilized worklist interactions with bit-blasted Product chains.
- `[CP]` Propagation: full Hall-set / matching arc consistency in AllDifferent. Currently pigeonhole plus boundary shaving only.
- `[CP]` Search: activity-based variable ordering (VSIDS-style).
- `[CP]` Search: last-conflict and impact-based value selection.
- `[CP]` Search: no-good / lazy clause learning (LCG-style). The deepest engineering item, gets us from CP-circa-2005 to competitive with Chuffed and CP-SAT.
- `[CP]` Search: dom/wdeg variable ordering. Each constraint accumulates a weight on failure; pick the variable with the highest weight/domain-size ratio.
- `[CP]` Search: counting-based value heuristic (Pesant). Solution-density per (var, value) drives the choice.
- `[CP]` Search: solution-guided search. Bias value selection toward the last solution until a better one is found.
- `[CP]` Online heuristic learning for variable / value selection via [kumulant](https://github.com/eignex/kumulant)'s `BayesianLinearRegression` — LinUCB / Linear Thompson Sampling over a per-(var, value) feature vector (domain size, activity, depth, last-conflict distance, value offset, recency, propagator failure rate). Cross-instance persistence via `fitPopulationPrior` so instance N starts from instances 1..N-1's pooled posterior — the structural differentiator vs. one-shot solvers (OR-tools and Chuffed throw heuristic state away between instances).
- `[CP]` Restart-level heuristic portfolio bandit. UCB1 or Thompson sampling over a configurable palette of variable and value heuristic configs; updates between Luby restarts. Uses kumulant's BernoulliSum per strategy.
- `[CP]` Multi-core CP portfolio for the Free and Parallel tracks. Remaining on top of the existing Portfolio: objective-bound sharing for optimize (shared AtomicLong best-bound each worker tightens against), Bayesian-linear posteriors merged via kumulant's snapshot/merge primitives in Relaxed concurrency mode, and an exhaustive strategy (run all workers to budget, collect everything).
- `[CP]` Factor: regular(seq, automaton). Sequence accepts a given DFA. Used for shift patterns, rostering rules, sequence-of-letters constraints. Propagator is the classic Pesant 2004 layered-DAG construction. Already referenced under `[Format/FlatZinc]`.
- `[CP]` Factor: bin_packing(bins, weights, capacity). Item `i` goes into `bins[i]`; each bin's total weight stays under capacity. Propagator combines per-bin Linear with a no-fit reasoner. Already referenced under `[Format/FlatZinc]`.
- `[CP]` Factor: diffn(xs, ys, widths, heights). 2D rectangles don't overlap. Sweep-line propagator. Packing / floorplanning.
- `[CP]` Factor: among(n, xs, S) and count(n, xs, v). Number of `xs[i] ∈ S` (or `== v`) equals `n`. Decomposable via Cardinality + reified equality literals, but a native factor avoids the indirection.
- `[CP]` Factor: inverse(f, g). `f[i] = j ⇔ g[j] = i`. Pairs of arrays that are functional inverses; common in assignment / symmetry-breaking. Standard channeling propagator.
- `[CP]` Factor: nvalue(n, xs). `n` equals the count of distinct values in `xs`. Useful for "use at most k colors" formulations.
- `[CP]` Factor: arg_max(idx, xs) / arg_min(idx, xs). `idx` is the position of the max / min element of `xs`. Tie-breaking via lex ordering. Useful in optimization decompositions.
- `[CP]` Factor: increasing(xs) / decreasing(xs) and strict variants. Monotone chains. Decomposable to pairwise comparisons but a dedicated propagator gives tighter pruning when domains overlap.
- `[CP]` Factor: value_precede_chain(values, xs). Value `values[i]` first appears before `values[i+1]`. Symmetry breaking on permutation classes. Linear-time propagator.
- `[CP]` Factor: sequence(low, high, k, xs, S). Every length-`k` window of `xs` has between `low` and `high` elements in `S`. Sliding-window cardinality, common in rostering.
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
  - `LinearThompsonStrategy` — replace WalkSat/ProbSat/DDFW's hardcoded scoring with a learned bandit over candidate moves; features per move are `breakScore`, `netDelta`, `shapedObjectiveDelta`, `tabu / confChange flags`, `lastTouched distance`, variable-degree-in-factor-graph. Posterior persists in `WarmState`; cross-instance via population priors.
  - Adaptive parameter tuning — replace `NoiseController`'s hand-coded bump-on-stall schedule with a bandit over `noise` / `cb` / `increment` settings.
  - Contextual ILS acceptance — learn whether to accept worsening optima from `(objective_delta, stall_count, iteration_fraction)` features.
  - Context-driven `flipsPerIteration` for ALNS — bandit over budget profiles `{quick, standard, deep}` selecting which depth to invest given recent-improvement context.
- `[LS+CP]` Cross-*instance* posterior persistence: problem-class N starts from class N-1's learned state. Cross-call state already lives in the Session subclasses (`LocalSearchSession` carries DDFW weights / activity touches / `bestCostSeen`; `LogicNGSession` and `SmtSession` reuse their native engines); the missing piece is the on-disk pooled-prior store that kumulant's `fitPopulationPrior` would feed from.
- `[API]` UNSAT cores, mirroring SMT-LIB get-unsat-core. SolveResult.Unsat is currently opaque; extending it to carry a reason set (subset of constraint ids) means the data class grows and every backend must support it or document the gap.
- `[API]` Deterministic instruction budgets, wall-clock-independent. Add a maxInstructions param across backends complementing the existing maxFlips / maxDecisions / timeoutMillis budgets.
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
- `[Format/FlatZinc]` Full Challenge-corpus parse-pass test. Drive `mzn2fzn` over [minizinc-benchmarks](https://github.com/MiniZinc/minizinc-benchmarks) and [libminizinc/tests](https://github.com/MiniZinc/libminizinc/tree/master/tests/spec); feed every produced `.fzn` to `FlatZincCompiler`; assert parse + compile success. Surfaces every quirk of the FZN spec the parser doesn't handle (set vars, optional types, edge-case decompositions). Run as a regression gate before any Challenge submission. CI installs MiniZinc via the official release tarball.
- `[Format/XCSP3]` Parser for XCSP3 XML, including extension tables and intension predicates.
- `[Format/DIMACS]` Weighted MaxSAT (.wcnf) loader.
- `[Backend]` klause-smt `Optimizer.minimize()` via JavaSMT's `OptimizationProverEnvironment` (Z3 / MathSAT5-only — backends without native optimization need an external linear-search loop). `SmtSolver` + `SmtSession` already ship with SMTInterpol as the pure-Java default, alongside klause-z3 for users who want the lean direct-Z3 path.
- `[Backend]` klause-choco adapter. JVM-only module wrapping Choco-solver as a reference CP oracle and competitive benchmark target. Mature global-constraint catalog and battle-tested FlatZinc parser; mapping from klause factors is near 1:1.
- `[Backend]` klause-ortools adapter for OR-tools CP-SAT via the Java bindings. State-of-the-art CP-SAT performance, but JNI-heavy and platform-specific natives — pick up after Choco.
- `[Backend]` klause-yuck and klause-oscar-cbls adapters for the MiniZinc Challenge LS track. [Yuck](https://github.com/informarte/yuck) (Scala on JVM, simulated-annealing core) and [fzn-oscar-cbls](https://github.com/oscar-cbls/oscar) (Scala on JVM, constraint-based local search) are the two reference LS competitors — both consume FlatZinc directly, so the integration shape is "shell out to their FZN binary" rather than a klause-factor mapping. Useful as benchmark oracles to compare klause's LS engine against on the Challenge corpus, and as fallback backends users can pick when their problem class is a known LS sweet spot for one of them.
- `[Backend]` klause-kissat / klause-cadical adapters. Kissat and CaDiCaL are SOTA CDCL SAT solvers (Kissat won SAT Competition 2020-2024); meaningfully faster than LogicNG's MiniSat on bit-blasted instances. Same lowering path as klause-logicng (bit-blast then dispatch). No Maven Central packages exist for either, so this means hand-rolled JNI bindings against the C source plus bundling platform-specific native binaries — substantial engineering. Probably only worth it once everything else on this TODO is done.
- `[Perf]` Make LocalSearchState.factorWeights lazy. Only DDFW (and a hypothetical SAPS) read it; WalkSat / ProbSat / SimulatedAnnealing / CcaWalkSat pay the allocation cost for nothing. Either lazy-init on first access from a weight-using strategy, or move the field onto a per-strategy state object.
- `[Perf]` Audit PropagationSession snapshot allocation cost. 5 array copies per push; consider pooling or a flat delta-trail.
- `[Perf]` Switch Problem.factors from List to Array if profiling shows virtual dispatch / list iteration cost.
- `[Perf]` Make/break vectors for `LocalSearchState.breakScore` (probSAT / YalSAT style). Today `breakScore(BoolFlip(v))` cold-misses through `problem.boolOccurrences[v]` and calls `deltaIfBoolFlipped` on every clause containing `v` — O(occurrence-list × clause-arity). The lazy `boolBreakCache` absorbs repeated reads, but every flip invalidates the neighbourhood so probSAT/DDFW keep cold-missing across the whole var pool per restart. Maintaining `breakCount[v]` / `makeCount[v]` incrementally inside `applyBoolFlip` collapses the query to O(1); the per-flip update cost is O(Σ arity²) over touched clauses but well-amortised under typical SAT-LS access patterns. Biggest single optimisation outstanding for SAT-class LS workloads.
- `[Perf]` CDCL-style per-literal watcher index for `Clause.propagate`. The CP-side `Clause.propagate` currently walks every literal on every fire; switching to a `watchersByLit[lit]` index with lazy notification would cut propagation work by roughly the average clause length and is a precondition for any LCG-class complete solving (paired with the `[CP] Search: no-good / lazy clause learning` entry above). Same scheme can be extended to `Cardinality` with `k+1` watched literals.
