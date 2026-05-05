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

Klause is a stochastic local-search SMT solver for mixed Boolean and
bounded-integer schemas, focused on sampling diverse satisfying assignments.
Nominal variables are encoded as Boolean indicators; floats are bucketed onto
bounded integers.

## Overview

A schema is a Kotlin class. Property delegates capture each variable and
constraint as a typed value, so the whole schema is a serializable value that
crosses the wire intact through kotlinx.serialization. Compiling the schema
yields a problem the solver runs local search over directly; nothing in the
hot path goes through CNF.

For handing the same problem to an external SAT engine, a separate
bit-blasting compiler lowers it to propositional CNF.

### Installation

```kotlin
dependencies {
    implementation("com.eignex:klause:0.1.0")
}
```

The constraint AST uses `@Serializable`, so the kotlinx.serialization plugin
and core library are also required.

## Schema and constraints

```kotlin
class CampaignSchema : VariableSchema() {
    val type    by nominal("a", "b", "c")
    val budget  by intVar(min = 1000, max = 4000)
    val bonus   by intVar(min = 0, max = 500)
    val rate    by floatVar(min = 0.0, max = 1.0, buckets = 21)

    val capWhenA   by constraint { (type eq "a") implies (budget + bonus le 2000) }
    val proportion by constraint { 2 * bonus le budget }
    val highRate   by constraint { rate ge 0.5 }
}
```

The constraint DSL covers `and`, `or`, `implies`, `iff`, `!` for the Boolean
side; `eq` / `ne` against label literals on nominals; full integer arithmetic
through `+`, `-`, unary `-`, and `*` by constant; the comparators `le`, `lt`,
`ge`, `gt`, `eq`, `ne` over arbitrary integer expressions on either side; the
same comparators against float literals on `floatVar` (resolved to
bucket-index comparisons at construction time); and `atMost`, `atLeast`,
`cardinality` for counting constraints. Every form composes inside Boolean
connectives: a multi-variable comparison, a linear arithmetic constraint, or
a cardinality expression nested inside `implies` is reified through a fresh
auxiliary literal so the rest of the lowering can treat it as a Boolean.

---

## Solving and sampling

```kotlin
val compiled = CampaignSchema().compile()
val solver   = Solver(compiled.problem)

solver.sample(maxFlips = 100_000).take(20).forEach { sample ->
    val type   = compiled.decodeNominal("type", sample)
    val budget = compiled.decodeInt("budget", sample)
    val rate   = compiled.decodeFloat("rate", sample)
    println("type=$type budget=$budget rate=$rate")
}
```

`sample` returns a lazy sequence that restarts the search after each yield.
Per-draw configuration (random seed, dedup window, minimum distance between
samples) lives on `sample`, not on `Solver`, so independent draws share no
state and can run on parallel threads.

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

Integer variables use canonical binary encoding, with reusable circuit
builders for the standard Tseitin gates and bit-vector primitives. The CNF is
suitable for any external propositional SAT engine.

## TODO

- Per-variable make/break cache for incremental scoring instead of recomputing each step.
- Additional search strategies: probSAT, SAPS, tabu, simulated annealing, restart schedules.
- Diversification beyond the rolling window: Hamming and XOR blocking factors.
- Pseudo-Boolean factor with weighted-sum semantics; XOR factor for parity-based diversification.
- Soft constraints surfaced in the DSL with weights.
- Typed handle-based decode API to replace name-string lookups.
- LogicNG adapter (jvmMain) for SAT-engine cross-checks and UNSAT proofs.
- Bit-blaster cross-check oracle wired into the test suite.
- Benchmark suite, Maven Central publishing, CI.
