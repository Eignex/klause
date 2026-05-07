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

Klause is a stochastic local-search constraint solver for mixed Boolean and
bounded-integer schemas. It samples diverse satisfying assignments and finds
the constrained argmin under a linear objective. Nominal variables are
encoded as Boolean indicators; floats are bucketed onto bounded integers.

A schema is a Kotlin class. Property delegates capture each variable and
constraint as a typed value, so the whole schema is a serializable value
that crosses the wire intact through kotlinx.serialization. Compiling the
schema yields a problem the local-search engine runs over directly; nothing
in the hot path goes through CNF. A separate bit-blasting compiler lowers
the same problem to propositional CNF when an external SAT engine is
needed.

## Installation

```kotlin
dependencies {
    implementation("com.eignex:klause:0.1.0")
}
```

The constraint AST uses `@Serializable`, so the kotlinx.serialization
plugin and core library are also required.

## Schema and constraints

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
- Integer arithmetic: signed +, -, unary -, *, /, %, including
  variable-by-variable multiplication and Java-truncated division and modulo.
- Comparisons: le, lt, ge, gt, eq, ne over arbitrary integer expressions on
  either side. Float arithmetic against literals or other expressions over
  the same float resolves to bucket-int comparisons at construction time.
- Counting: atMost, atLeast, cardinality, plus pbAtMost, pbAtLeast,
  pbExactly, pseudoBoolean for weighted sums of Booleans.
- Global: gcc for cardinality across several values, allDifferent for
  pairwise inequality (specialised to a single global factor when operands
  are bare handles).
- Tabular: table and notTable for extensional allowed and forbidden tuples.
- Integer expressions: min, max, abs, element for array indexing by an int
  variable, ifThenElse for conditional integer expressions.
- Linking: channel to bridge an integer and a one-hot Boolean array; lexLeq
  and lexLt for lexicographic ordering.

Multi-variable comparisons, cardinality expressions, pseudo-Boolean
expressions, and tables nested inside Boolean connectives are reified
through a fresh auxiliary literal so the rest of the lowering can treat
them as Booleans. Non-affine integer expressions (*, /, %, min, max, abs,
element, ifThenElse) are hoisted into auxiliary integer variables with
auxiliary constraints, so the affine pipeline never sees a nonlinear node
directly.

## Solving, sampling, optimising

Compile the schema and hand the resulting problem to LocalSearchSolver. It
implements both the Sampler and Optimizer interfaces. Sampler offers
`sample` for a single satisfying assignment, `samples` for an
independent-draw sequence (with replacement), and `enumerate` for distinct
draws under a rolling-window Hamming-distance dedup. Optimizer adds
`minimize`, which returns the constrained-feasible argmin under a
LinearObjective.

```kotlin
val compiled = CampaignSchema().compile()
val solver = LocalSearchSolver(compiled.problem)

solver.enumerate(LocalSearchParams(maxFlips = 100_000)).take(20).forEach { s ->
    val type   = compiled.decodeNominal("type", s)
    val budget = compiled.decodeInt("budget", s)
    val rate   = compiled.decodeFloat("rate", s)
    println("type=$type budget=$budget rate=$rate")
}
```

Per-call configuration (random seed, per-yield flip budget, dedup window,
minimum Hamming distance) lives on LocalSearchParams rather than on the
solver, so independent draws share no state and can run on parallel
threads.

The Optimizer path takes a LinearObjective with per-variable weights and
returns the lowest-cost satisfying assignment found within the flip
budget:

```kotlin
val weights = LinearObjective(
    boolWeights = doubleArrayOf(/* one per bool var */),
    intCoefficients = doubleArrayOf(/* one per int var */),
)
val best = solver.minimize(weights, LocalSearchParams(maxFlips = 100_000))
```

## Backends

Two published adapters route the same Problem through external engines.
klause-logicng bit-blasts the problem to CNF and hands it to LogicNG's
MiniSat. klause-z3 translates each factor directly to Z3 SMT expressions
without bit-blasting and uses Z3's native Optimize solver for argmin
queries. Both adapters implement Sampler and Optimizer, so the same caller
can swap backends.

A third backend, BruteForceSampler, ships in core. It walks the entire
assignment space in a kpermute-shuffled order using mixed-radix index
decoding, supports spaces beyond 2^63 by chunking variables across nested
permutations, and is exact when the step budget is generous enough. It's
the natural ground-truth oracle for verifying the stochastic and SMT
backends on small problems.

An internal klause-bench module runs all four backends on a portfolio of
problems, asserts they agree on satisfiability and that any sample they
produce satisfies the original Problem, and reports per-backend timings.

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

Integer variables use canonical binary encoding, with reusable circuit
builders for the standard Tseitin gates and bit-vector primitives. The CNF
is suitable for any external propositional SAT engine.

## TODO

- Typed handle-based decode API to replace name-string lookups (`compiled.decodeFloat(handle, sample)` instead of `compiled.decodeFloat("rate", sample)`).
- Wire-format problem loading; hook into `:klause-bench` once the format ships.
- Multi-float linear arithmetic across distinct `FloatHandle`s. Single-handle linear ops work; cross-handle currently throws at expression-build time. Needs scale unification in the compiler.
- `IntLeq` / `IntGeq` / `IntEq` repair-move clamping — proposed targets aren't bounded to the int's domain. Latent footgun when the bound itself is out-of-domain (the "always-false" path).
- Global GCC factor with a HashMap-of-counts payload, sharper than the current per-value cardinality decomposition.
- Benchmark suite, Maven Central publishing, CI.
