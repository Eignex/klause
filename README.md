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

- A local-search solver (WalkSat / probSAT-style) for sampling and
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
```

Local search is the default. Swap in the backtrack solver when you
need completeness or true without-replacement enumeration.

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

## TODO

- Maven Central publishing, CI.
- Propagation: Product reverse direction for non-singleton operands. Singleton-operand reverse landed; the general interval-division case destabilized worklist interactions with bit-blasted Product chains.
- Propagation: full Hall-set / matching arc consistency in AllDifferent. Currently pigeonhole plus boundary shaving only.
- CP search: activity-based variable ordering (VSIDS-style).
- CP search: Luby or geometric restarts. Currently pure chronological DFS.
- CP search: last-conflict and impact-based value selection.
- CP search: no-good / lazy clause learning (LCG-style). The deepest engineering item, gets us from CP-circa-2005 to competitive with Chuffed and CP-SAT.
- Online heuristic learning via Bayesian linear models on [kumulant](https://github.com/eignex/kumulant). LinUCB / Linear Thompson Sampling: per var-value feature vector (domain size, activity, depth, last-conflict distance, value offset, recency, propagator failure rate, ...); full-covariance Gaussian posterior over weights; Thompson sampling draws weights and picks the value maximising the predicted success probability. Generalises across pairs via shared weights, no per-pair cold start. Per-variable Moments (mean, variance, skewness, kurtosis of conflict depth) for cumulant-based variable ordering. EwmaMean and DecayingMean for dom/wdeg with principled decay. Extensions: distributional regression via DDSketch for risk-aware picking; hierarchical Bayesian priors shared across instances within a problem class, the structural differentiator over one-shot solvers (OR-tools and Chuffed throw heuristic state away between instances).
- Extend kumulant with a BayesianLinearStat. Gaussian posterior over weights, conjugate Bayesian updates per observation, Thompson sampling primitive, mergeable across observation streams. Klause is its first consumer; useful standalone for online experiment analysis. Roughly 200 lines of math-with-tests.
- Restart-level heuristic portfolio bandit. UCB1 or Thompson sampling over a configurable palette of variable and value heuristic configs; updates between Luby restarts. Uses BernoulliSum per strategy.
- Multi-core LS portfolio for the MiniZinc Challenge Local Search track. The klause-portfolio module ships the parallel coordination layer (Session-per-worker, race-first-feasible solve, fan-in sample flow, cooperative cancellation). Remaining: best-feasible-solution sharing between workers (each worker's first model gets broadcast as a warm-start hint), shared kumulant stats in Relaxed concurrency mode to track per-strategy success and steer a restart-level bandit, and a worker-config factory that hands each worker a different (strategy, seed) pair.
- Multi-core CP portfolio for the Free and Parallel tracks. The Portfolio class works for any Session; remaining is objective-bound sharing for optimize (shared AtomicLong best-bound that each worker tightens its search against), shared Bayesian-linear posteriors merged via kumulant's snapshot and merge primitives (each worker keeps a local copy in Relaxed concurrency mode), and an exhaustive strategy (run all workers until each budget, collect everything).
- Cross-sample and cross-instance posterior persistence. The Session interface and StatelessSession default impl landed; what's pending is backend-specific Session subclasses that hold real cross-call state (learned clauses, kumulant posteriors, warm-start last solution). LocalSearchSolver's existing WarmState is the first candidate to move onto a LocalSearchSession subclass. Within a problem class, instance N starts from instance N-1's learned state — the structural differentiator over one-shot solvers (OR-tools and Chuffed throw heuristic state away between instances).
- CP search: dom/wdeg variable ordering. Each constraint accumulates a weight on failure; pick the variable with the highest weight/domain-size ratio.
- CP search: phase-saving value selection. Cache the last value tried per variable across restarts.
- CP search: counting-based value heuristic (Pesant). Solution-density per (var, value) drives the choice.
- CP search: solution-guided search. Bias value selection toward the last solution until a better one is found.
- Meta-optimizer: ALNS (Adaptive Large Neighborhood Search). LNS shape — freeze most variables at their current-best values, free a subset, re-solve — extended with multiple destroy operators (random, activity-biased, objective-relevant, problem-aware) and multiple repair strategies. A bandit picks which destroy/repair pair to use based on past success (kumulant BernoulliSum per pair, Thompson sampling). Strictly dominates vanilla LNS in the literature; what Yuck, LocalSolver, and ROADEF winners actually run. Sits above Optimizer; composes existing Session and assumption primitives. The single biggest competitive item for the LS-track optimization workload.
- Meta-optimizer: ILS (Iterated Local Search). LS to local optimum, perturb the solution with a tuned mutation rate, restart LS from the perturbed point. Simpler than ALNS and sometimes competitive when problem-specific destroy operators don't exist (which is our default). Complements ALNS in a portfolio.
- Local search: anytime objective tracking audit. Ensure `LocalSearchSolver.minimize` always returns the best-feasible-so-far on cancellation or budget exhaustion, never null when a feasible was seen. Architecturally trivial; correctness audit only.
- Local search: extend `NoiseController` from pure stall-detection to kumulant-EwmaMean cost smoothing once kumulant lands as a dependency. The current bump-on-stall / decay-on-improvement rule (Hoos 2002) covers the +10-30% literature gain on random instances but doesn't filter noisy cost trajectories; an EWMA over `state.cost` would make adaptation less reactive to single-step jitter on factor problems with mixed objective and constraint terms. Adaptive variants for WalkSat, ProbSat, DDFW shipped as `AdaptiveWalkSat` / `AdaptiveProbSat` / `AdaptiveDdfw`.
- Local search: cost function shaping for optimization. When minimizing under hard constraints, combine constraint-violation cost with objective cost via a tunable penalty function. Linear combination is the baseline; saturating penalties (one violation shouldn't dominate) often help on tight problems. Engine-level decision in the cost computation.
- Local search: optional SAPS strategy as a multiplicative-weighting alternative to the existing additive-transfer DDFW. Low priority — DDFW already covers the weight-learning niche.
- Local search: extract DDFW's inline tabu list into a first-class decorator strategy that can wrap any picker (WalkSat, ProbSat, etc.), with aspiration criteria (allow tabu moves that strictly improve best-so-far) and dynamic tabu tenure.
- Local search: variable neighborhood search meta-heuristic. Cycle through neighborhood sizes when stuck.
- Local search: richer move generation for int variables. Today `Move.IntSet` candidates are basically ±1 / random-in-domain; competitive CP-LS picks based on score (most-improving among neighbors) with problem-aware moves for global constraints (cumulative time-window slides, allDifferent swaps, etc.). Per-factor `proposeRepairMoves` is the extension point.
- Optimizer: branch-and-bound minimize / maximize in BacktrackSolver. The current impl enumerates all feasibles, which is exponential.
- API: per-backend Session subclasses for incremental solving with learned-state persistence. The Session interface, push/pop assumption stack, and default stateless impl landed; backends with native incremental engines (LogicNG via MiniSat's assumption-set, Z3 via push/pop) should override Session to expose that natively rather than re-baking on each solve.
- API: UNSAT cores, mirroring SMT-LIB get-unsat-core. SolveResult.Unsat is currently opaque; extending it to carry a reason set (subset of constraint ids) means the data class grows and every backend must support it or document the gap.
- API: deterministic instruction budgets, wall-clock-independent. Add a maxInstructions param across backends complementing the existing maxFlips / maxDecisions / timeoutMillis budgets.
- Sampling: hash-based uniform sampling (UniGen2-style). XOR-slice the model space, sample within a slice.
- Sampling: approximate model counting (ApproxMC). Same XOR-hashing primitive as UniGen.
- Sampling: weighted projected sampling (WAPS / KUS).
- Docs: tutorial covering schema, constraints, and backend selection.
- Docs: Dokka site for the KDoc.
- SMT-LIB parity: SMT-LIB v2 parser covering the finite-domain integer subset (QF_LIA in SMT-LIB terminology). Lets klause run SMT-LIB benchmarks alongside the FlatZinc / DIMACS / OPB ones it already handles.
- SMT-LIB parity: static bound inference for int vars declared with full-range or unbounded domains. Error out cleanly when no bound is provable.
- SMT-LIB parity: distinct over arbitrary terms (booleans, mixed bool/int). AllDifferent covers ints already.
- SMT-LIB parity: to_real / to_int casts. Either bucket reals onto bounded ints or reject the benchmark.
- SMT-LIB parity: let-binding expansion in the SMT-LIB parser.
- SMT-LIB parity: unbounded integers in BacktrackSolver. Pairs with the bound-inference item above.
- FlatZinc parity: int_div / int_mod with truncated-toward-zero semantics (FlatZinc spec, distinct from klause's internal Euclidean div). Currently fail-loud with a TODO message.
- FlatZinc parity: bool_and_reif, bool_or_reif, bool_xor_reif, bool_lt_reif. The int comparison _reif variants landed; bool side still missing.
- FlatZinc parity: streaming branch-and-bound in solve minimize / maximize. The current FZN CLI does linear search over enumerate and tracks the best — works but doesn't prune. B&B once it lands in BacktrackSolver wires through.
- FlatZinc parity: LS-track conventions. Handle symmetry_breaking_constraint(...) and redundant_constraint(...) as no-ops when the engine is local-search, per MiniZinc Challenge LS rules.
- FlatZinc perf: native propagators for globals that decomposition encodes poorly. Profile against klause-mzn-lib; cumulative, regular, circuit, and bin_packing are the usual suspects.
- FlatZinc parity: set variables. MiniZinc decomposes set vars to indicator-bool arrays when the solver doesn't claim native sets; the FZN parser currently misdiagnoses the set declarations as "unbounded int". Either decline cleanly or claim set support and decompose at klause level.
- FlatZinc parity: populate Problem.floatMetadata from FlatZincCompiler so FZN-loaded problems also get native-real Z3 handling. The schema path already does this; the FZN side still buckets inline without recording the real-valued sidecar.
- XCSP3 parity: parser for XCSP3 XML, including extension tables and intension predicates.
- DIMACS / OPB parity: weighted MaxSAT (.wcnf) loader.
- Backend: klause-smt module on top of JavaSMT (org.sosy-lab:java-smt). Wraps Z3, CVC5, MathSAT5, Bitwuzla, SMTInterpol, Yices2, and Princess behind one API; pure-Java solvers (SMTInterpol, Princess) come with no JNI overhead. Replaces klause-z3 (or sits next to it) — one adapter, eight backends, easy cross-validation. JavaSMT also has SMT-LIB v2 parsing capability that could close part of the SMT-LIB parity TODO for free.
- Backend: klause-choco adapter. JVM-only module wrapping Choco-solver as a reference CP oracle and competitive benchmark target. Mature global-constraint catalog and battle-tested FlatZinc parser; mapping from klause factors is near 1:1.
- Backend: klause-ortools adapter for OR-tools CP-SAT via the Java bindings. State-of-the-art CP-SAT performance, but JNI-heavy and platform-specific natives — pick up after Choco.
- Backend: klause-kissat / klause-cadical adapters. Kissat and CaDiCaL are SOTA CDCL SAT solvers (Kissat won SAT Competition 2020-2024); meaningfully faster than LogicNG's MiniSat on bit-blasted instances. Same lowering path as klause-logicng (bit-blast then dispatch). No Maven Central packages exist for either, so this means hand-rolled JNI bindings against the C source plus bundling platform-specific native binaries — substantial engineering. Probably only worth it once everything else on this TODO is done.
- Perf (post-benchmark): replace Assumptions and PropagationResult.Implied maps with parallel-array representations to avoid Int boxing.
- Perf (post-benchmark): make LocalSearchState.factorWeights lazy. Only DDFW (and a hypothetical SAPS) read it; WalkSat / ProbSat / SimulatedAnnealing / CcaWalkSat pay the allocation cost for nothing. Either lazy-init on first access from a weight-using strategy, or move the field onto a per-strategy state object.
- Perf (post-benchmark): audit PropagationSession snapshot allocation cost. 5 array copies per push; consider pooling or a flat delta-trail.
- Perf (post-benchmark): switch Problem.factors from List to Array if profiling shows virtual dispatch / list iteration cost.
