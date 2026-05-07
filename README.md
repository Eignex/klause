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

The DSL covers:

* **Boolean**: `and`, `or`, `implies`, `iff`, `!`, `xor`.
* **Nominal**: `eq` and `ne` against label literals.
* **Integer arithmetic**: signed `+`, `-`, unary `-`, `*`, `/`, `%`, including variable-by-variable multiplication and Java-truncated division and modulo.
* **Comparisons**: `le`, `lt`, `ge`, `gt`, `eq`, `ne` over arbitrary integer expressions on either side. Float comparisons against literals resolve to bucket-index comparisons at construction time.
* **Counting**: `atMost`, `atLeast`, `cardinality`, plus `pbAtMost` / `pbAtLeast` / `pbExactly` / `pseudoBoolean` for weighted sums of Booleans.
* **Global**: `gcc` for cardinality across several values, `allDifferent` for pairwise inequality (specialised to a single global factor when operands are bare handles).
* **Tabular**: `table` and `notTable` for extensional allowed and forbidden tuples.
* **Integer expressions**: `min`, `max`, `abs`, `element` for array indexing by an int variable, `ifThenElse` for conditional integer expressions.
* **Linking**: `channel` to bridge an integer and a one-hot Boolean array; `lexLeq` / `lexLt` for lexicographic ordering.

Multi-variable comparisons, cardinality expressions, pseudo-Boolean expressions, and tables nested inside Boolean connectives are reified through a fresh auxiliary literal so the rest of the lowering can treat them as Booleans. Non-affine integer expressions (`*`, `/`, `%`, `min`, `max`, `abs`, `element`, `ifThenElse`) are hoisted into auxiliary integer variables with auxiliary constraints, so the affine pipeline never sees a nonlinear node directly.

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

- Typed handle-based decode API to replace name-string lookups (`compiled.decodeFloat(handle, sample)` instead of `compiled.decodeFloat("rate", sample)`).
- Wire-format problem loading; hook into `:klause-bench` once the format ships.
- Multi-float linear arithmetic across distinct `FloatHandle`s. Single-handle linear ops work; cross-handle currently throws at expression-build time. Needs scale unification in the compiler.
- `IntLeq` / `IntGeq` / `IntEq` repair-move clamping — proposed targets aren't bounded to the int's domain. Latent footgun when the bound itself is out-of-domain (the "always-false" path).
- Global GCC factor with a HashMap-of-counts payload, sharper than the current per-value cardinality decomposition.
- Benchmark suite, Maven Central publishing, CI.
