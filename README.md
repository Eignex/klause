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

Klause samples satisfying assignments to constraint problems over Boolean and
nominal variables, using stochastic local search in the WalkSAT family.

## Overview

A schema is declared as a Kotlin class. Property delegates capture each
variable and constraint as a typed value, so the whole schema serializes
through kotlinx.serialization and crosses the wire intact.

The compiler lowers a schema to a native factor registry. Disjunctions become
clauses, nominal variables become indicator booleans plus an exactly-one
factor, and nested non-literal subexpressions go through Tseitin with hidden
aux variables. The solver runs WalkSAT directly over that registry and yields
hard-feasible assignments as a lazy sequence; full CNF conversion only happens
on the DIMACS export path.

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
    val premium       by boolVar()
    val type          by nominal("a", "b", "c")
    val noPremiumForA by constraint { (type eq "a") implies !premium }
}
```

Inside a `constraint { ... }` block the DSL provides `and`, `or`, `implies`,
`iff`, and unary `!`, plus `eq` / `ne` against label literals on nominals and
top-level `atMost`, `atLeast`, `cardinality` for counting constraints. The
returned value is a sealed `BoolExpr` tree, never a host-language lambda, so
`schema.definition()` round-trips through JSON or ProtoBuf.

---

## Solving and sampling

```kotlin
val compiled = CampaignSchema().compile()
val solver   = Solver(compiled.problem, randomSeed = 42L)

solver.sample(maxFlips = 100_000).take(20).forEach { bits ->
    val type    = compiled.decodeNominal("type", bits)
    val premium = compiled.decodeBool("premium", bits)
    println("type=$type premium=$premium")
}
```

`Solver.sample` returns a lazy `Sequence<BooleanArray>`. After each yielded
assignment the search restarts from a randomized state, so callers take as
many samples as they want with no formal uniformity guarantee. The default
strategy is `WalkSat(noise)`: with probability `noise` flip a random variable
in a violated factor, otherwise pick the variable with the smallest break
count, ties broken uniformly at random.

`compiled.varIdByName` and `compiled.nominalIndicators` map schema names back
to solver variable ids for any decoding that does not go through the helpers.

---

## DIMACS export

```kotlin
val dimacs = DimacsWriter.write(compiled.problem)
```

Cardinality factors are lowered to clauses on the way out: at-most-1 to
pairwise binary clauses, at-least-1 to a single big clause, exactly-one to
both. Higher-k cardinality bounds are rejected, since the sequential-counter
encoding is not in scope yet.

## How it works

The `Problem` is immutable: a count of Boolean variables and an ordered list
of factors, plus a precomputed occurrence list mapping each variable to the
factors that mention it. The mutable `SolverState` holds a packed-bit
`Assignment`, an `intPayload` array carrying per-factor scratch (the count of
true literals, for both factor types in scope today), the violated-factor set
behind an `IntSwapSet` for O(1) random pick, and aggregated `hardCost` and
`softCost`.

| Factor               | Payload                  | Violated when             |
|----------------------|--------------------------|---------------------------|
| `Clause`             | count of true literals   | count is 0                |
| `Cardinality(a, b)`  | count of true literals   | count is below a or above b |

Literals use the MiniSAT encoding: `lit = (variable shl 1) or 1` for negated,
so `lit xor 1` flips polarity and `lit ushr 1` recovers the variable id. On
each accepted flip only the factors mentioning the flipped variable refresh
their payload, and the violated set is updated incrementally; the per-move
cost is proportional to the degree of the flipped variable, not the total
factor count.
