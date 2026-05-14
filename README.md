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

The internals borrow from the SMT playbook. Bit-blasting in
`BitBlaster` lowers integer constraints to CNF so problems can be
shipped to external SAT engines (LogicNG in `:klause-logicng`).
DPLL(T)-style theory propagation lives in `BacktrackSolver`, a DFS
over finite-domain integers with propagators per constraint,
configurable variable and value heuristics, and true model-blocking
`enumerate`. `:klause-z3` translates problems directly to Z3 for hard
instances. `LocalSearchSolver` is a WalkSat / probSAT-style stochastic
alternative that scales when complete methods blow up. All four
backends implement the same `Solver` and `Optimizer` interfaces, so
consumers swap by tradeoff per problem.

Unlike Z3 or CVC5, klause's theory is narrow: bounded integers and
Booleans, no bitvectors, arrays, floats, or strings. In exchange,
sampling is first-class. `samples()` (with replacement) and
`enumerate()` (without replacement) are core operations, not
afterthoughts. Klause is also not a MILP solver; objectives are linear
over integers, not reals.

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
- Integer arithmetic: signed +, -, unary -, *, /, %, with Java-truncated
  division and modulo and variable-by-variable multiplication.
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

`LocalSearchSolver` is the default. Swap in `BacktrackSolver` when you
need completeness or true without-replacement `enumerate`, and the
SAT or SMT adapters in `:klause-logicng` and `:klause-z3` when you
need raw solver horsepower on hard instances.

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

## TODO

- Maven Central publishing, CI.
- Propagation: `Product` reverse direction for non-singleton operands. Singleton-operand reverse (`a = result / b` when b is fixed) landed; the general interval-division case is sound but historically destabilized worklist interactions with bit-blasted Product chains.
- Propagation: full Hall-set / matching arc consistency in `AllDifferent` (currently pigeonhole + boundary shaving only).
- Perf (post-benchmark): replace `Assumptions.bools: Map<Int, Boolean>` / `ints: Map<Int, Int>` and `PropagationResult.Implied.{bools, ints}` with parallel-array representations to avoid `Int` boxing on the propagation hot path.
- Perf (post-benchmark): make `LocalSearchState.factorWeights` lazy. Only DDFW-style strategies read it, but it's always allocated.
- Perf (post-benchmark): audit `PropagationSession` snapshot allocation cost per push (5 array copies per snapshot). Consider pooling or a flat delta-trail.
- Perf (post-benchmark): switch `Problem.factors: List<Factor>` to `Array<Factor>` if profiling shows virtual dispatch / list iteration cost on the propagation hot path.
