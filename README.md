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

Klause samples satisfying assignments to constraint problems over Boolean,
nominal, integer, and bucketed-float variables, using stochastic local search
in the WalkSAT family.

## Overview

A schema is declared as a Kotlin class. Property delegates capture each
variable and constraint as a typed value, so the whole schema serializes
through kotlinx.serialization and crosses the wire intact.

The compiler lowers a schema to a native factor registry. Variables split into
two id spaces: Boolean vars are packed into bits, integer vars carry a domain
and live in a separate int array. Float vars are bucketed at the schema layer
and become integer vars internally with a decoder kept for read-back.

Disjunctions become clauses, nominal variables become indicator booleans plus
an exactly-one factor, integer comparisons become dedicated comparator factors
that propose snap-to-bound repair moves, and nested non-literal subexpressions
go through Tseitin with hidden aux variables. The solver runs WalkSAT directly
over that registry and yields hard-feasible assignments as a lazy sequence;
full CNF conversion only happens on the bit-blasting export path, where the
problem is handed off to an external propositional SAT engine.

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
    val rate    by floatVar(min = 0.0, max = 1.0, buckets = 21)

    val capWhenA by constraint { (type eq "a") implies (budget le 2000) }
    val highRate by constraint { rate ge 0.5 }
}
```

Inside a `constraint { ... }` block the DSL provides `and`, `or`, `implies`,
`iff`, and unary `!` for the Boolean side; `eq` / `ne` against label literals
on nominals; `le`, `lt`, `ge`, `gt`, `eq`, `ne` against integer or float
literals (and against another variable of the same kind); and top-level
`atMost`, `atLeast`, `cardinality` for counting constraints. The returned
value is a sealed `BoolExpr` tree, never a host-language lambda, so
`schema.definition()` round-trips through JSON or ProtoBuf.

Float comparisons resolve at construction time to integer comparisons against
the bucket index, so the AST stays integer-only beyond the schema definition
itself.

---

## Solving and sampling

```kotlin
val compiled = CampaignSchema().compile()
val solver   = Solver(compiled.problem, randomSeed = 42L)

solver.sample(maxFlips = 100_000).take(20).forEach { sample ->
    val type   = compiled.decodeNominal("type", sample)
    val budget = compiled.decodeInt("budget", sample)
    val rate   = compiled.decodeFloat("rate", sample)
    println("type=$type budget=$budget rate=$rate")
}
```

`Solver.sample` returns a lazy `Sequence<Sample>` where each sample carries a
`bools: BooleanArray` and an `ints: IntArray`. After each yielded sample the
search restarts from a randomized state, so callers take as many samples as
they want with no formal uniformity guarantee. The default strategy is
`WalkSat(noise)`: pick a violated factor, ask it for repair-move suggestions,
then either flip a random suggestion (probability `noise`) or pick the
suggestion with the smallest break count, ties broken uniformly at random.

`compiled.boolVarIdByName`, `compiled.intVarIdByName`, `compiled.nominalIndicators`,
and `compiled.floatDecoders` map schema names back to solver-side ids for any
decoding that does not go through the helpers.

---

## Bit-blasting to CNF

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
val intValue = cnf.decodeInt(originalIntVarId, model)
```

`BitBlaster` produces a propositional CNF for any problem klause supports,
suitable for handing to an external SAT engine. Integer variables use
canonical binary encoding: each variable in `[min, max]` gets
`ceil(log2(max - min + 1))` bits representing the offset from `min`, with an
explicit domain constraint when the domain size is not a power of two.
Reusable circuit builders cover Tseitin AND/OR/XOR3/MAJ3, zero-extend,
shift-left, ripple-carry add, multiply-by-constant, constant comparators, and
unsigned `≤` / `==` over arbitrary widths.

Lowered factor types: `Clause`, `Cardinality` (only AtMostOne / AtLeastOne /
ExactlyOne, since the sequential-counter encoding is out of scope),
`IntLeq`, `IntGeq`, `IntEq`, `IntNeq`, `Linear` (any signed coefficients and
bound), and `ReifiedIntCompare`. Out-of-domain `IntEq` / `IntNeq` constants
short-circuit to a true/false unit clause at compile time.

## How it works

The `Problem` is immutable: a count of Boolean variables, a count of integer
variables with their `IntDomain` bounds, and an ordered list of factors, plus
two precomputed occurrence lists (one per variable kind) mapping each variable
to the factors that mention it. The mutable `SolverState` holds an
`Assignment` (packed bits + int array), an `intPayload` array carrying
per-factor scratch, the violated-factor set behind an `IntSwapSet` for O(1)
random pick, and aggregated `hardCost` and `softCost`.

| Factor                | Payload                  | Violated when                          |
|-----------------------|--------------------------|----------------------------------------|
| `Clause`              | count of true literals   | count is 0                             |
| `Cardinality(a, b)`   | count of true literals   | count is below a or above b            |
| `IntLeq(x, b)`        | none                     | `x > b`                                |
| `IntGeq(x, b)`        | none                     | `x < b`                                |
| `IntEq(x, v)`         | none                     | `x != v`                               |
| `IntNeq(x, v)`        | none                     | `x == v`                               |
| `Linear(c, x, op, b)` | current weighted sum     | `Σ c·x` on wrong side of `b`           |
| `ReifiedIntCompare`   | none                     | `aux != (x op b)`                      |

Literals use the MiniSAT encoding: `lit = (variable shl 1) or 1` for negated,
so `lit xor 1` flips polarity and `lit ushr 1` recovers the variable id.
Boolean factors override `applyBoolFlip`, integer factors override
`applyIntSet`, and the strategy enumerates moves via `proposeRepairMoves`,
which each factor implements with structural insight (a comparator snaps to
its bound, a linear constraint solves for the integer value that puts the sum
on the right side). On each accepted move only the factors mentioning the
changed variable refresh their payload, and the violated set is updated
incrementally; the per-move cost is proportional to the degree of the changed
variable, not the total factor count.
