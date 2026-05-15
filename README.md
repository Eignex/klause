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

Klause is a Kotlin SMT-flavored solver for QF_LIA-style problems:
quantifier-free formulas over Booleans and bounded integers, with
arithmetic, comparisons, logic, and global constraints like
allDifferent, gcc, and table. Floats lower onto bucketed integers and
nominals lower onto Boolean indicators.

The internals borrow from the SMT playbook. A bit-blaster lowers
integer constraints to CNF so problems can be shipped to external SAT
engines, with a LogicNG adapter in the klause-logicng module.
DPLL(T)-style theory propagation lives in the backtrack solver, a DFS
over finite-domain integers with propagators per constraint,
configurable variable and value heuristics, and true model-blocking
enumeration. The klause-z3 module translates problems directly to Z3
for hard instances. A local-search solver (WalkSat / probSAT-style) is
the stochastic alternative for when complete methods blow up. All four
backends implement the same Solver and Optimizer interfaces, so
consumers swap by tradeoff per problem.

Unlike Z3 or CVC5, klause's theory is narrow: bounded integers and
Booleans, no bitvectors, arrays, floats, or strings. In exchange,
sampling is first-class. Drawing samples with replacement and
enumerating without replacement are core operations, not afterthoughts.
Klause is also not a MILP solver; objectives are linear over integers,
not reals.

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
need completeness or true without-replacement enumeration, and the
SAT or SMT adapters in klause-logicng and klause-z3 when you need raw
solver horsepower on hard instances.

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

## TODO

- Maven Central publishing, CI.
- Propagation: `Product` reverse direction for non-singleton operands. Singleton-operand reverse (`a = result / b` when b is fixed) landed; the general interval-division case is sound but historically destabilized worklist interactions with bit-blasted Product chains.
- Propagation: full Hall-set / matching arc consistency in `AllDifferent` (currently pigeonhole + boundary shaving only).
- CP search in `BacktrackSolver`: activity-based variable ordering (VSIDS-style), in addition to the existing `RandomVariable` / `SmallestDomain` / `InputOrder`. Cheap addition, big win on structured problems.
- CP search in `BacktrackSolver`: Luby or geometric restarts. Currently pure chronological DFS.
- CP search in `BacktrackSolver`: last-conflict and impact-based value selection on top of the existing stateless `IndomainRandom` / `IndomainMin` / etc.
- CP search in `BacktrackSolver`: no-good / lazy clause learning (LCG-style). The biggest jump in solver power but deepest engineering item; gets us from "CP solver circa 2005" to "competitive with Chuffed / CP-SAT".
- CP search in `BacktrackSolver`: dom/wdeg variable ordering (Boussemart et al.). Each constraint accumulates a weight every time it triggers failure; pick the variable with the highest weight/domain-size ratio. Standard finite-domain CP heuristic, robust across problem types.
- CP search in `BacktrackSolver`: phase-saving value selection. Cache the last value tried for each variable across restarts; reuse on next visit. Cheap, well-known SAT/CP technique.
- CP search in `BacktrackSolver`: counting-based value heuristic (Pesant). Solution-density estimates per (var, value) drive the value choice. Works particularly well on globals like `allDifferent` and `gcc` where counts can be computed cheaply.
- CP search in `BacktrackSolver`: solution-guided search. After the first solution, bias value selection toward the previous solution's assignment until a better one is found. Trivial to wire on top of phase-saving.
- CP search in `BacktrackSolver`: large neighborhood search (LNS) for optimization. Freeze a random subset of variables to their current-best values and re-solve the rest. Standard CP optimization meta-heuristic; pairs well with `minimize`.
- Local search in `LocalSearchSolver`: tabu list. Forbid recently-flipped variables for K steps to avoid cycling. Trivial addition to `Strategy`.
- Local search in `LocalSearchSolver`: DDFW / SAPS strategies that actually use `LocalSearchState.factorWeights` (currently the field is allocated but no shipped strategy reads it).
- Local search in `LocalSearchSolver`: variable neighborhood search (VNS) meta-heuristic. Cycles through neighborhood sizes when stuck.
- Optimizer: branch-and-bound `minimize` / `maximize` in `BacktrackSolver`. The current implementation enumerates all feasible models and tracks the best, which is exponential. A real B&B prunes once a partial assignment can't beat the current best.
- API: incremental solving via assumption push/pop. Some backends already accept per-call assumptions; an explicit `pushAssumptions` / `popAssumptions` lets callers reuse engine state across related queries.
- API: UNSAT cores. Return the minimal subset of asserted constraints that produced unsat, mirroring SMT-LIB `get-unsat-core`. Useful for debugging and for MaxSAT-style relaxation.
- API: deterministic budgets. `maxFlips` / `maxDecisions` exist per backend; add a wall-clock-independent "instruction budget" so test runs are reproducible across machines.
- Sampling: hash-based uniform sampling (UniGen2-style). Slice the model space with XOR hashing and uniformly sample a slice. Currently we only have stochastic samplers with no uniformity guarantee.
- Sampling: approximate model counting (ApproxMC). Companion to UniGen; same XOR-hashing primitive.
- Sampling: weighted projected sampling (WAPS / KUS-style). Sample over a projection of the model space, weighted by user-defined factors.
- Docs: tutorial / cookbook covering schema → constraints → solver-backend selection.
- Docs: KDoc → static site (Dokka) so the API reference is browsable.
- QF_LIA parity: SMT-LIB v2 parser for the QF_LIA subset (`set-logic`, `declare-fun`, `assert`, `check-sat`, core s-expression). Lets us run SMT-LIB benchmarks directly.
- QF_LIA parity: static bound inference for int variables declared with full-range or unbounded domains. Scan constraint structure to derive sound bounds before bit-blasting; error out cleanly when no bound can be proved.
- QF_LIA parity: `distinct` over arbitrary terms (booleans, mixed bool/int). `AllDifferent` covers the int case; the general `distinct` lowering for booleans is at-most-one over each value.
- QF_LIA parity: `to_real` / `to_int` casts. Klause has no Real sort; any benchmark that mixes them via the SMT-LIB cast operators currently has no expressible analog. Either lift reals onto bucketed integers (with a chosen bucket count) or reject the benchmark.
- QF_LIA parity: `let` binding expansion in the SMT-LIB parser. Klause's Kotlin DSL composes naturally so this is purely a parser concern (de-sugar lets to fresh names before lowering).
- QF_LIA parity: unbounded integers in `BacktrackSolver`. Activity-based search + LCG should make wide-domain bounds propagation viable, but today there's no fallback when `IntDomain(min, max)` can't be derived statically. Pair with the bound-inference item above.
- FlatZinc parity: arithmetic builtins beyond `int_times`. Need `int_plus`, `int_minus`, `int_div`, `int_mod`, `int_abs`, `int_min`, `int_max`, `int_pow`. Most map directly to existing klause expressions.
- FlatZinc parity: array element constraints. `array_int_element`, `array_var_int_element`, `array_bool_element` lower to klause's `element`; the var-indexed form needs an extra layer of channeling.
- FlatZinc parity: counting predicates. `count_neq`, `count_leq`, `count_geq` (we have `count_eq`); `global_cardinality` / `global_cardinality_low_up` lower to klause's `gcc`; `nvalue`, `among`, `alldifferent_except_0` are direct.
- FlatZinc parity: structural globals. `table_int` / `table_bool` lower to klause's `table`; `lex_lesseq` / `lex_less` lower to `lexLeq` / `lexLt`; `inverse` is a channeling primitive klause already has. `bin_packing`, `bin_packing_load`, `bin_packing_capa`, `cumulative`, `regular`, `circuit`, `subcircuit`, `diffn`, `knapsack`, `value_precede` / `value_precede_chain` need new factors.
- FlatZinc parity: full reification coverage. We have `int_lin_*_reif`; missing reified variants for comparisons (`int_eq_reif`, `int_le_reif`, etc.), Boolean ops (`bool_and_reif`, `bool_or_reif`, `bool_xor_reif`), and globals.
- FlatZinc parity: set variables and set constraints. `set_in`, `set_subset`, `set_union`, `set_intersect`, `set_card` etc. Klause has no Set sort today; a meaningful slice of the MiniZinc Challenge corpus uses them. Either lower sets to indicator-vector booleans (cheap, scales poorly) or add a first-class `SetDomain`.
- FlatZinc parity: float variables and continuous arithmetic. `float_plus`, `float_lin_eq`, `float_times`, etc. Klause buckets floats onto bounded ints at the schema level; FlatZinc benchmarks ship raw float predicates. Either decline these instances or auto-bucket on import.
- FlatZinc parity: solve goals beyond `satisfy`. Wire `minimize` / `maximize` annotations into the Optimizer interface end-to-end (today only the search annotations are propagated).
- XCSP3 parity: parser for XCSP3 XML. Constraint coverage overlaps heavily with FlatZinc but a few unique forms (`<extension>` tables, `<intension>` predicates) need their own lowering.
- DIMACS / OPB parity: `.wcnf` (weighted MaxSAT) loader. We already read plain DIMACS and `.opb`; weighted CNF and the OPB optimization variant slot in as `Optimizer` problems with a linear objective.
- Perf (post-benchmark): replace `Assumptions.bools: Map<Int, Boolean>` / `ints: Map<Int, Int>` and `PropagationResult.Implied.{bools, ints}` with parallel-array representations to avoid `Int` boxing on the propagation hot path.
- Perf (post-benchmark): make `LocalSearchState.factorWeights` lazy. Only DDFW-style strategies read it, but it's always allocated.
- Perf (post-benchmark): audit `PropagationSession` snapshot allocation cost per push (5 array copies per snapshot). Consider pooling or a flat delta-trail.
- Perf (post-benchmark): switch `Problem.factors: List<Factor>` to `Array<Factor>` if profiling shows virtual dispatch / list iteration cost on the propagation hot path.
