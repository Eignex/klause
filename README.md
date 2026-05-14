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

Kotlin solver for Boolean and integer constraint problems. Finds and
samples satisfying solutions, picks the best under a weighted objective,
and exports to CNF for external SAT engines. Nominal variables are
encoded as Boolean indicators; floats are bucketed onto bounded integers.

## Schema

```kotlin
class CampaignSchema : VariableSchema() {
    val type    by nominal("a", "b", "c")
    val budget  by intVar(min = 1000, max = 4000)
    val bonus   by intVar(min = 0, max = 500)
    val rate    by floatVar(min = 0.0, max = 1.0)

    val capWhenA   by constraint { (type eq "a") implies (budget + bonus le 2000) }
    val proportion by constraint { 2 * bonus le budget }
    val highRate   by constraint { rate ge 0.5 }
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

`LocalSearchSolver` is the default engine. Adapters in `:klause-logicng`
(via bit-blasting to CNF) and `:klause-z3` (direct SMT translation)
implement the same `Sampler` and `Optimizer` interfaces. A
`BruteForceSolver` in core walks the assignment space exhaustively as a
ground-truth oracle for small problems. `:klause-bench` cross-checks all
four against a hard-coded portfolio plus pre-made problem instances
loaded from DIMACS, OPB, and JSON-SchemaDef files. Run
`./gradlew :klause-bench:downloadSatlib` once to fetch the SATLIB
uf20-91 (sat) and uuf50-218 (unsat) sets — `:klause-bench:run` will
include them automatically (sample size capped via
`-Dklause.bench.satlib.max=N`, default 10 per set).

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

## Bench loop

`:klause-bench:run` writes machine-readable timings to
`klause-bench/build/bench-results.json` alongside the existing stdout. If
`klause-bench/bench-baseline.json` exists, each cell prints a `Δ%` vs
baseline and the run exits non-zero when any cell regresses by more than
`-Dklause.bench.regressionThresholdPct=N` (default 25%). Accept the current
results as the new baseline with `:klause-bench:saveBaseline`.

## TODO

- Maven Central publishing, CI.
- Propagation: `Product` reverse direction for non-singleton operands. Singleton-operand reverse (`a = result / b` when b is fixed) landed; the general interval-division case is sound but historically destabilized worklist interactions with bit-blasted Product chains.
- Propagation: full Hall-set / matching arc consistency in `AllDifferent` (currently pigeonhole + boundary shaving only).
