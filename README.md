# klause

Local-search MaxSAT/Max-CSP solver for mixed integer-Boolean schemas.

klause is a Kotlin Multiplatform library for declaring constraint problems over Boolean and nominal variables and sampling diverse satisfying assignments. The engine is a stochastic local search in the WalkSAT family, running directly over a native factor representation; full CNF conversion happens only on the export path for compatibility with external SAT/MaxSAT solvers.

## Overview

The library is laid out in three layers, with one direction of dependency:

```
schema (DSL) ── compile ──▶  factor registry  ◀── consume ── solver
   (AST, @Serializable)        (runtime form)              (state, moves, loop)
```

1. **Schema** (`com.eignex.klause.schema`) — `VariableSchema` with property delegates for declaring typed variables and constraints. The schema is a serializable value, not an imperative builder.
2. **AST** (`com.eignex.klause.ast`) — a sealed `@Serializable` constraint tree (`And`, `Or`, `Not`, `Implies`, `Iff`, `AtMost`, `AtLeast`, `CardinalityExpr`). The wire format.
3. **Compiler** (`com.eignex.klause.compile`) — pure `SchemaDef → Problem` lowering with Tseitin-style aux variables hidden from the user.
4. **Solver** (`com.eignex.klause.solver`) — the stateful engine. Packed-bit assignment, factor payloads (true counts), occurrence lists, and a violated-factor set behind an `IntSwapSet` for O(1) random pick.
5. **Export** (`com.eignex.klause.export`) — DIMACS CNF writer for handing the same problem to an external SAT solver.

---

## Schema

A `VariableSchema` declares typed variables and constraints as class properties. Each delegate captures the property's name and registers a spec; constraint bodies are typed trees of values, never host-language lambdas, so the whole schema round-trips through `kotlinx.serialization`.

```kotlin
class CampaignSchema : VariableSchema() {
    val premium       by boolVar()
    val type          by nominal("a", "b", "c")
    val noPremiumForA by constraint { (type eq "a") implies !premium }
}
```

The DSL inside `constraint { ... }` provides:

* **Boolean operators**: `and`, `or`, `implies`, `iff`, `!` (which folds into the `BoolRef.negated` flag).
* **Nominal predicates**: `nominalHandle eq "label"` and `ne "label"`.
* **Cardinality**: top-level `atMost(k, ...)`, `atLeast(k, ...)`, `cardinality(min, max, ...)` over a list of terms.

`schema.definition()` returns a `SchemaDef(vars, constraints)` that serializes to JSON or ProtoBuf out of the box.

## Compiler

`schema.compile()` lowers a `VariableSchema` to a solver-side `Problem` plus the index needed to decode an assignment back to schema values.

```kotlin
val compiled = CampaignSchema().compile()
compiled.problem        // Problem(numVars, factors)
compiled.varIdByName    // Boolean variable name → solver var id
compiled.nominalIndicators // nominal name → label → solver var id
```

A `NominalSpec` lowers to one Boolean indicator per label plus an `ExactlyOne` cardinality factor. Disjunctions of literals become `Clause` factors directly; nested non-literal sub-expressions go through Tseitin (`tseitinAnd`, `tseitinOr`, `tseitinIff`), which introduces a fresh aux variable and equivalence clauses. Top-level `AtMost` / `AtLeast` / `CardinalityExpr` lower to a single `Cardinality` factor.

## Solver

`Solver` runs WalkSAT (Selman-Kautz-Cohen) over the factor registry and returns hard-feasible assignments as a lazy `Sequence<BooleanArray>`. After each yielded assignment the search restarts from a randomized state, so callers can `take(n)` to draw `n` samples.

```kotlin
val solver = Solver(compiled.problem, randomSeed = 42L)

solver.sample(maxFlips = 100_000).take(20).forEach { bits ->
    val type    = compiled.decodeNominal("type", bits)
    val premium = compiled.decodeBool("premium", bits)
    println("type=$type premium=$premium")
}
```

* **Strategy**: `WalkSat(noise)` — with probability `noise`, flip a random variable from a violated factor; otherwise pick the variable with the smallest break count, tied uniformly at random.
* **State**: `SolverState` holds the packed-bit `Assignment`, per-factor `intPayload` (true count for clauses and cardinality), the violated-factor `IntSwapSet`, and aggregated `hardCost` / `softCost`.
* **Restarts**: `maxFlipsBeforeRestart` triggers a randomized restart when the current trajectory stalls. The `Strategy` interface is the extension point for additional engines (probSAT, SAPS, tabu, simulated annealing).

Sampling has no formal uniformity guarantee: distinct restarts and the WalkSAT noise term give approximately diverse, satisfying assignments rather than a uniformly random one.

## Export

`DimacsWriter.write(problem)` returns a DIMACS CNF string for any `Problem`. Cardinality factors are lowered to clauses on the way out: `AtMost-1` to pairwise binary clauses, `AtLeast-1` to a single big clause, `ExactlyOne` to both. Higher-`k` cardinality bounds raise — the sequential-counter encoding is intentionally not in scope yet.

```kotlin
val dimacs = DimacsWriter.write(compiled.problem)
println(dimacs)
// p cnf 4 4
// -2 -3 0
// -2 -4 0
// -3 -4 0
// 2 3 4 0
// ...
```

---

## How It Works

The solver maintains an immutable `Problem` (variables and `Factor` registry) and a mutable `SolverState` (assignment, violation state, aggregated cost). Each `Factor` owns three operations: `initialize` populates its scratch from the current assignment, `isViolated` reads it, and `applyFlip` updates it incrementally when a variable is flipped — touching only `O(degree-of-variable)` factors per move rather than the whole problem.

Variables and literals use the MiniSAT encoding: `lit = (variable shl 1) or (1 if negated else 0)`, so `lit xor 1` flips polarity and `lit ushr 1` recovers the variable id. The `Problem` precomputes an occurrence list (`bitToFactors: Array<IntArray>`) so flips propagate without any factor lookup.

The two factor types in scope today are:

| Factor | Payload | Violated when |
|---|---|---|
| `Clause` | count of true literals | count == 0 |
| `Cardinality(min, max)` | count of true literals | count < min or count > max |

WalkSAT picks a violated factor uniformly from the `IntSwapSet`, scores its variables by break count (number of currently-satisfied hard factors a flip would break), and either takes the lowest-break variable (tie-broken at random) or — with probability `noise` — flips a random variable in the factor. After each accepted flip, only the factors mentioning the flipped variable refresh their payload and the violated set is updated incrementally.
