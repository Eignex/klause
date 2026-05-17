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
- Global: gcc, allDifferent.
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
- `[CP]` Search: Luby or geometric restarts. Currently pure chronological DFS.
- `[CP]` Search: last-conflict and impact-based value selection.
- `[CP]` Search: no-good / lazy clause learning (LCG-style). The deepest engineering item, gets us from CP-circa-2005 to competitive with Chuffed and CP-SAT.
- `[CP]` Search: dom/wdeg variable ordering. Each constraint accumulates a weight on failure; pick the variable with the highest weight/domain-size ratio.
- `[CP]` Search: phase-saving value selection. Cache the last value tried per variable across restarts.
- `[CP]` Search: counting-based value heuristic (Pesant). Solution-density per (var, value) drives the choice.
- `[CP]` Search: solution-guided search. Bias value selection toward the last solution until a better one is found.
- `[CP]` Online heuristic learning for variable / value selection via [kumulant](https://github.com/eignex/kumulant)'s Bayesian linear models. LinUCB / Linear Thompson Sampling: per var-value feature vector (domain size, activity, depth, last-conflict distance, value offset, recency, propagator failure rate, ...); full-covariance Gaussian posterior over weights; Thompson sampling draws weights and picks the value maximising the predicted success probability. Generalises across pairs via shared weights, no per-pair cold start. Per-variable Moments (mean, variance, skewness, kurtosis of conflict depth) for cumulant-based variable ordering. EwmaMean and DecayingMean for dom/wdeg with principled decay. Cross-instance persistence via kumulant's `BayesianLinearRegression.fitPopulationPrior` — instance N starts from instances 1..N-1's pooled posterior, the structural differentiator over one-shot solvers (OR-tools and Chuffed throw heuristic state away between instances).
- `[CP]` Restart-level heuristic portfolio bandit. UCB1 or Thompson sampling over a configurable palette of variable and value heuristic configs; updates between Luby restarts. Uses kumulant's BernoulliSum per strategy.
- `[CP]` Multi-core CP portfolio for the Free and Parallel tracks. Remaining on top of the existing Portfolio: objective-bound sharing for optimize (shared AtomicLong best-bound each worker tightens against), Bayesian-linear posteriors merged via kumulant's snapshot/merge primitives in Relaxed concurrency mode, and an exhaustive strategy (run all workers to budget, collect everything).
- `[CP]` Branch-and-bound minimize / maximize in BacktrackSolver. The current impl enumerates all feasibles, which is exponential.
- `[LS]` Multi-core LS portfolio finishing touches for the MiniZinc Challenge LS track. The klause-portfolio coordination layer (Session-per-worker, race-first-feasible solve, fan-in sample flow, cooperative cancellation) is in place. Remaining: best-feasible-solution sharing between workers (broadcast as warm-start hints), shared kumulant stats in Relaxed concurrency mode for a restart-level bandit, and a worker-config factory that hands each worker a different (strategy, seed) pair.
- `[LS]` Meta-optimizer: extend ALNS beyond the Ropke-Pisinger 2006 baseline. Shipped: random + worst-objective + adjacency-related + currently-violated destroy plus `activityBiased(session)` driven by cumulative per-variable touch counts persisted in `WarmState`; `InnerLsRepair` parameterised by flip budget (default palette: standard / quick / deep) plus `GreedyConstructionRepair` for myopic value-by-value fill; kumulant `UnivariateBandit` for destroy and repair selection (defaults to `RouletteWheelBandit`; swap in `MultiArmedBandit(BetaBernoulliTS())` for Thompson sampling); ILS-derived acceptance criteria; opt-in `session` constructor field that routes repair through a `LocalSearchSession` so DDFW weights and activity counts accumulate across iterations. What's missing: problem-specific destroy (cumulative time-window slides), regret-based / best-improving construction repairs.
- `[LS]` Meta-optimizer: ILS variants beyond the shipped Lourenço-Martin-Stützle 2003 baseline (Improving / BetterOrEqual / RandomWalk / SimulatedAnnealing acceptance, adaptive perturbation strength, optional population of incumbents picked at random per restart, crossover restarts with Uniform / BetterBiased value mixing when population ≥ 2). What's still missing: basin-hopping-style large-jump perturbation (the existing adaptive ramp already approximates this), and multi-parent / linkage-aware crossover.
- `[LS]` Auto-tune `ewmaAlpha` from problem characteristics. The opt-in EWMA-mode in `NoiseController` is now plumbed through `AdaptiveWalkSat` / `AdaptiveProbSat` / `AdaptiveDdfw` constructors; what's missing is a heuristic that picks `ewmaAlpha` from problem size / flip budget so callers don't need to tune it.
- `[LS]` Extend cost shaping to non-Linear objectives. `LocalSearchState.shapedBreakScore` / `shapedObjectiveDelta` only handle `LinearObjective` today (O(1) coefficient lookup). General `Objective` subtypes would need an apply-revert with full re-evaluation per candidate — the state churn isn't justified for the typical use case. All five LS strategies (WalkSat, ProbSat, DDFW, CcaWalkSat, SimulatedAnnealing — plus the adaptive variants of the first three) respect shaping under Linear; ProbSat shifts its weight-base to stay positive, DDFW adds the contribution to its weighted-break, SA fuses it into the Metropolis Δ.
- `[LS]` Optional SAPS strategy as a multiplicative-weighting alternative to the existing additive-transfer DDFW. Low priority — DDFW already covers the weight-learning niche.
- `[LS]` Richer VNS schemes beyond the shipped `Vns` strategy (single-var → k-tuple Compound cycle, promote on stagnation, demote on improvement, max 3 levels by default). Missing: VND (Variable Neighborhood Descent — exhaust each k completely before promotion, vs. our threshold-based promotion), problem-specific neighborhood operators per level (e.g. swap-only at N2, 3-opt at N3), and a "skewed VNS" variant that accepts mild worsening at higher k.
- `[LS]` Problem-aware move generation for global constraints. AllDifferent proposes up to 4 unused targets and falls back to value-swap Compounds when the occupant's domain is saturated (useful in Sudoku-style coupled AllDifferents); Linear and Product snap to exact-target values; the engine supports `Move.Compound` for atomic multi-variable transitions. Still missing: cumulative time-window slides, lex-aware moves for `lexLeq` / `lexLt`, and reified-factor diversification moves. Per-factor `proposeRepairMoves` is the extension point.
- `[LS]` Apply LinUCB / Linear Thompson Sampling inside LS itself, not just CP search. Kumulant's `BayesianLinearRegression` with custom prior + population fit is in place; concrete LS targets that would benefit from feature-driven learning instead of hand-tuned schedules:
  - `LinearThompsonStrategy` — replace WalkSat/ProbSat/DDFW's hardcoded scoring with a learned bandit over candidate moves; features per move are `breakScore`, `netDelta`, `shapedObjectiveDelta`, `tabu / confChange flags`, `lastTouched distance`, variable-degree-in-factor-graph. Posterior persists in `WarmState`; cross-instance via population priors.
  - Adaptive parameter tuning — replace `NoiseController`'s hand-coded bump-on-stall schedule with a bandit over `noise` / `cb` / `increment` settings.
  - Contextual ILS acceptance — learn whether to accept worsening optima from `(objective_delta, stall_count, iteration_fraction)` features.
  - Context-driven `flipsPerIteration` for ALNS — bandit over budget profiles `{quick, standard, deep}` selecting which depth to invest given recent-improvement context.
- `[LS+CP]` Cross-sample and cross-instance posterior persistence via backend-specific Session subclasses that hold real cross-call state (learned clauses, kumulant posteriors, warm-start last solution). `LocalSearchSession` now implements `Session<LocalSearchParams>` and carries DDFW factor weights, per-variable activity touch counts, and the `bestCostSeen` watermark across calls; what's still pending is LogicNG/Z3 session subclasses that expose native incremental engines, and cross-*instance* persistence so problem-class N starts from class N-1's learned state.
- `[API]` Per-backend Session subclasses with native incremental engines. LogicNG via MiniSat's assumption-set, Z3 via push/pop — currently the default `StatelessSession` re-bakes on each solve; backend overrides would expose the native incremental path.
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
- `[Format/FlatZinc]` Native propagators for globals that decomposition encodes poorly. Profile against klause-mzn-lib; cumulative, regular, circuit, and bin_packing are the usual suspects.
- `[Format/FlatZinc]` Set variables. MiniZinc decomposes set vars to indicator-bool arrays when the solver doesn't claim native sets; the FZN parser currently misdiagnoses the set declarations as "unbounded int". Either decline cleanly or claim set support and decompose at klause level.
- `[Format/FlatZinc]` Populate Problem.floatMetadata from FlatZincCompiler so FZN-loaded problems also get native-real Z3 handling. The schema path already does this; the FZN side still buckets inline without recording the real-valued sidecar.
- `[Format/XCSP3]` Parser for XCSP3 XML, including extension tables and intension predicates.
- `[Format/DIMACS]` Weighted MaxSAT (.wcnf) loader.
- `[Backend]` klause-smt module on top of JavaSMT (org.sosy-lab:java-smt). Wraps Z3, CVC5, MathSAT5, Bitwuzla, SMTInterpol, Yices2, and Princess behind one API; pure-Java solvers (SMTInterpol, Princess) come with no JNI overhead. Replaces klause-z3 (or sits next to it) — one adapter, eight backends, easy cross-validation. JavaSMT also has SMT-LIB v2 parsing capability that could close part of the SMT-LIB parity TODO for free.
- `[Backend]` klause-choco adapter. JVM-only module wrapping Choco-solver as a reference CP oracle and competitive benchmark target. Mature global-constraint catalog and battle-tested FlatZinc parser; mapping from klause factors is near 1:1.
- `[Backend]` klause-ortools adapter for OR-tools CP-SAT via the Java bindings. State-of-the-art CP-SAT performance, but JNI-heavy and platform-specific natives — pick up after Choco.
- `[Backend]` klause-kissat / klause-cadical adapters. Kissat and CaDiCaL are SOTA CDCL SAT solvers (Kissat won SAT Competition 2020-2024); meaningfully faster than LogicNG's MiniSat on bit-blasted instances. Same lowering path as klause-logicng (bit-blast then dispatch). No Maven Central packages exist for either, so this means hand-rolled JNI bindings against the C source plus bundling platform-specific native binaries — substantial engineering. Probably only worth it once everything else on this TODO is done.
- `[Perf]` Replace Assumptions and PropagationResult.Implied maps with parallel-array representations to avoid Int boxing.
- `[Perf]` Make LocalSearchState.factorWeights lazy. Only DDFW (and a hypothetical SAPS) read it; WalkSat / ProbSat / SimulatedAnnealing / CcaWalkSat pay the allocation cost for nothing. Either lazy-init on first access from a weight-using strategy, or move the field onto a per-strategy state object.
- `[Perf]` Audit PropagationSession snapshot allocation cost. 5 array copies per push; consider pooling or a flat delta-trail.
- `[Perf]` Switch Problem.factors from List to Array if profiling shows virtual dispatch / list iteration cost.
