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
- Multi-core LS portfolio for the MiniZinc Challenge Local Search track. N workers with different strategy and seed combinations; shared kumulant stats in Relaxed concurrency mode track per-strategy success; best-feasible-solution sharing across workers. The track's small competitor field (Yuck, OscaR/CBLS, iZplus) plus klause's existing LS engine make this the most realistic medal target.
- Multi-core CP portfolio for the Free and Parallel tracks. Workers with different heuristic configs; shared Bayesian-linear posteriors merged via kumulant's snapshot and merge primitives (each worker maintains a local copy in Relaxed concurrency mode); objective-bound sharing for optimization. Pairs with the kumulant learning items.
- Cross-sample and cross-instance posterior persistence on Session. Pairs with the incremental-solving Session API. Within a problem class, instance N starts from instance N-1's learned posterior; the heuristic gets better with use. OR-tools and Chuffed don't do this, it's where klause's sampling-first positioning translates into a competitive edge.
- CP search: dom/wdeg variable ordering. Each constraint accumulates a weight on failure; pick the variable with the highest weight/domain-size ratio.
- CP search: phase-saving value selection. Cache the last value tried per variable across restarts.
- CP search: counting-based value heuristic (Pesant). Solution-density per (var, value) drives the choice.
- CP search: solution-guided search. Bias value selection toward the last solution until a better one is found.
- CP search: large neighborhood search for optimization. Freeze a random subset to current-best, re-solve the rest.
- Local search: tabu list. Forbid recently-flipped variables for K steps.
- Local search: DDFW / SAPS strategies that actually use factorWeights.
- Local search: variable neighborhood search meta-heuristic.
- Optimizer: branch-and-bound minimize / maximize in BacktrackSolver. The current impl enumerates all feasibles, which is exponential.
- API: incremental solving via push / pop assumptions.
- API: UNSAT cores, mirroring SMT-LIB get-unsat-core.
- API: deterministic instruction budgets, wall-clock-independent.
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
- Backend: klause-choco adapter. JVM-only module wrapping Choco-solver as a reference CP oracle and competitive benchmark target. Mature global-constraint catalog and battle-tested FlatZinc parser; mapping from klause factors is near 1:1.
- Backend: klause-ortools adapter for OR-tools CP-SAT via the Java bindings. State-of-the-art CP-SAT performance, but JNI-heavy and platform-specific natives — pick up after Choco.
- Backend: klause-kissat / klause-cadical adapters. Kissat and CaDiCaL are SOTA CDCL SAT solvers (Kissat won SAT Competition 2020-2024); meaningfully faster than LogicNG's MiniSat on bit-blasted instances. Same lowering path as klause-logicng (bit-blast then dispatch). No Maven Central packages exist for either, so this means hand-rolled JNI bindings against the C source plus bundling platform-specific native binaries — substantial engineering. Probably only worth it once everything else on this TODO is done.
- Perf (post-benchmark): replace Assumptions and PropagationResult.Implied maps with parallel-array representations to avoid Int boxing.
- Perf (post-benchmark): make LocalSearchState.factorWeights lazy. Only DDFW-style strategies read it, but it's always allocated.
- Perf (post-benchmark): audit PropagationSession snapshot allocation cost. 5 array copies per push; consider pooling or a flat delta-trail.
- Perf (post-benchmark): switch Problem.factors from List to Array if profiling shows virtual dispatch / list iteration cost.
