# klause

Local-search MaxSAT/Max-CSP solver for mixed integer-Boolean schemas.

klause is a Kotlin Multiplatform library for declaring constraint problems over Boolean and bounded-integer variables and sampling diverse satisfying assignments. The engine is a stochastic local search (WalkSAT/probSAT family) over a native factor representation; full CNF conversion happens only on the export path for compatibility with external SAT/MaxSAT solvers.

## Three layers

```
schema (DSL) ── compile ──▶  factor registry  ◀── consume ── solver
   (AST, @Serializable)        (runtime form)              (state, moves, loop)
```

- `com.eignex.klause.schema` — `VariableSchema` with property delegates for declaring typed variables and constraints. The schema is a serializable value, not an imperative builder.
- `com.eignex.klause.ast` — a sealed `@Serializable` constraint tree. The wire format.
- `com.eignex.klause.compile` — pure `Schema → Problem` lowering with Tseitin-style aux variables hidden from the user.
- `com.eignex.klause.solver` — the stateful engine. Packed-bit assignment, factor payloads (watched literals, true counts, parities), occurrence lists, delta caches, violated-factor set.
- `com.eignex.klause.export` — DIMACS / WCNF / SMT-LIB writers for handing the same problem to an external verifier.

## Example

```kotlin
class CampaignSchema : VariableSchema() {
    val premium      by boolVar()
    val type         by nominal("a", "b", "c")
    val noPremiumForA by constraint { (type eq "a") implies !premium }
}

val schema   = CampaignSchema()
val compiled = schema.compile()
val solver   = Solver(compiled.problem, randomSeed = 42L)

solver.sample(maxFlips = 100_000).take(20).forEach { bits ->
    val type    = compiled.decodeNominal("type", bits)
    val premium = compiled.decodeBool("premium", bits)
    println("type=$type premium=$premium")
}
```

## Status

**Phase A** is in place: Boolean and nominal variables, the constraint DSL with `and` / `or` / `implies` / `iff` / `not`, top-level `atMost` / `atLeast` / `cardinality`, schema → factor compilation with Tseitin lowering, a WalkSAT search loop, and DIMACS export.

**Phase B** is still to come: bitvector factors and integer moves, probSAT / SAPS / tabu / restarts, sampling diversification (Hamming and XOR blocking), WCNF / SMT-LIB writers, and a JVM-only SAT4J cross-check adapter.
