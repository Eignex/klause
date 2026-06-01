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

Klause is a Kotlin constraint programming library. Variables are bounded
integers, Booleans, floats (bucketed to integers), nominals (one-hot
encoded as Booleans), and sets over either an int range or a nominal
universe. Any variable can be declared optional, with constraints lowered
to reified or aggregation-aware forms automatically. The DSL covers
arithmetic, logic, comparisons, and a range of global constraints.
MiniZinc models can use klause as a backend through klause-mzn-lib.

Two native engines, both implementing Solver and Optimizer:

- A complete CSP backtrack engine (the default) with propagation,
  configurable variable and value heuristics, branch-and-bound minimize,
  and model-blocking enumeration. Used for proofs of unsat, optimal
  bounds, and without-replacement enumeration.
- A local-search engine (default strategy: adaptive probSAT; also
  WalkSat, DDFW, simulated annealing, CCA variants). Used for stochastic
  sampling and large-domain problems where complete search doesn't
  finish in budget.

Optional adapter modules send the same problem to external solvers when
useful: klause-logicng for bit-blasted SAT, klause-smt for SMT-LIB-based
backends. Side doors, not the core.

Sampling is first-class. Drawing samples with replacement and enumerating
without replacement are core operations. Most CP libraries solve once and
stop; klause is built for repeated, diverse, and incremental queries
against the same model.

Klause is not a MILP solver (objectives are linear over integers, not
reals), not a full SMT solver (no bitvectors, arrays, strings, or
quantifiers), and not a verification framework. For those, reach for a
MILP solver, Z3 or CVC5, or a proof assistant.

## Use cases

Klause targets problems where the constraint system is the model and the
question is "give me some valid configurations" rather than "prove this
assertion holds".

- Constraint-aware test or fuzz input generation. Produce inputs that
  satisfy structural invariants so the test exercises behaviour instead
  of rejecting on input validation.
- Diverse input sets for differential testing. Draw many valid samples
  spread across the feasible region, not clustered in one corner.
- Configuration synthesis. Find a system configuration (feature flags,
  resource caps, routing weights) that satisfies the rules, optionally
  ranked by a weighted objective.
- Scheduling and assignment. Tasks to machines, students to rooms,
  campaigns to budgets. Add a linear objective for cost-minimal solutions.
- Plan verification. Check that a proposed assignment satisfies all
  declared constraints, and report why it fails when it doesn't.

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

### Variables

- **intVar**: bounded integer with a contiguous starting domain.
  Propagation can excise interior values during solving; storage switches
  to a bitset for narrow spans and a sorted hole list for wide ones.
- **floatVar**: continuous-looking variable bucketed into a
  precision-controlled grid of integer buckets at compile time.
- **boolVar**: declares a free Boolean variable. Comparisons and Boolean
  operators on other variable kinds also produce Boolean-typed expressions
  implicitly, so most code never names a BoolVar directly.
- **nominal**: picks one label from a fixed list, lowered internally to
  one-hot indicator bools with the standard exactly-one constraint.
- **multiple and setVar**: set variables over a nominal universe or an int
  range respectively. Both lower to per-element indicator bools, with set
  operations dispatching through bulk bitset propagators.
- **Optional**: optIntVar, optBoolVar, and the opt forms of nominal and
  set vars return a (present, value) pair. The DSL routes constraints
  involving opt vars through reified or opt-aware lowerings so user code
  does not need to write the presence guards explicitly.

### Constraints

- **Boolean**: connectives (and, or, implies, iff, not, xor) over Boolean
  expressions, freely composable and reified into clauses behind the scenes.
- **Nominal**: equality and inequality (eq, ne) against label literals or
  other nominals.
- **Set**: membership (inSet), structural relations (subsetOf,
  disjointFrom), and algebra (union, intersect, card). Mixed concrete-set
  and set-var operands work uniformly.
- **Optional**: constraints on optional variables follow relational
  semantics: an undefined value in a Boolean context is false. Aggregating
  constraints filter to the present subset, so most user code does not
  need to mention presence explicitly.
- **Integer arithmetic**: +, -, *, /, % over integer expressions and
  constants. Division and modulo are Euclidean (matching SMT-LIB QF_LIA)
  so the remainder is always non-negative. Multiplication works
  variable-by-variable, not only by constants.
- **Comparisons**: le, lt, ge, gt, eq, ne between arbitrary integer
  expressions, returning a Boolean expression usable in any reified context.
- **Counting**: atMost and atLeast bound the number of true bools;
  cardinality and pseudoBoolean are the weighted versions. gcc enforces a
  global cardinality vector. count counts occurrences of a single value.
  among counts membership in a value set. nValue counts the number of
  distinct values taken.
- **Permutations and ordering**: allDifferent enforces pairwise
  distinctness via Regin's GAC matching, allDifferentExcept excepts a
  designated value set, and allEqual, inverse, sort, argSort cover the
  standard array invariants. lexLeq and lexLt enforce lexicographic
  ordering between two arrays; valuePrecede is a structural
  symmetry-breaking primitive.
- **Scheduling**: cumulative keeps every time point's resource usage under
  the capacity; the propagator combines time-tabling with a Vilim
  Theta-tree edge-finder. disjunctive is the unary special case. Opt-aware
  variants accept a presents array so that absent tasks contribute no
  resource use.
- **Routing**: circuit constrains a successor array to form a single
  Hamiltonian cycle, subcircuit allows nodes to sit outside the cycle as
  fixed points, and path and tree enforce directed-path and
  rooted-spanning-tree shapes.
- **Packing**: binPacking assigns items to bins under a per-bin capacity,
  knapsack is the classical 0-1 knapsack, and geost enforces
  N-dimensional non-overlap via a sweep-line propagator.
- **Network flow**: networkFlow enforces nodal flow conservation, and
  networkFlowCost adds per-unit-flow costs that aggregate into a cost
  variable suitable as an objective.
- **Tabular**: table allows only tuples listed in the table and notTable
  forbids them. regular accepts only the words a deterministic finite
  automaton recognises; costRegular adds per-edge costs that sum into a
  cost variable. mdd and costMdd are the multi-valued decision diagram
  analogues, supporting much richer structure than a flat table at lower
  memory cost.
- **Integer expressions**: min, max, and abs give pointwise extrema and
  absolute value. element indexes an array by a variable. member exposes
  set membership as a Boolean. argMin and argMax return the index of the
  smallest or largest entry. ifThenElse is an integer-valued conditional.
- **Linking**: channel enforces that exactly one bool is true and the
  corresponding int is its index, the standard bridge between one-hot and
  indexed representations.

## Solving

```kotlin
val schema = CampaignSchema()
val compiled = schema.compile()
val solver = BacktrackSolver(compiled.problem)

solver.enumerate(BacktrackParams()).take(20).forEach { s ->
    println("type=${compiled.decode(schema.type, s)} budget=${compiled.decode(schema.budget, s)}")
}

val weights = LinearObjective(boolWeights = doubleArrayOf(/* ... */))
val best = solver.minimize(weights, BacktrackParams())

// Or point at one schema variable, MiniZinc-style:
val cheapest = solver.minimize(compiled.minimize(schema.budget), BacktrackParams())
```

The backtrack engine is the default — it provides completeness, branch-and-bound
optimality proofs, and without-replacement enumeration. Swap in `LocalSearchSolver`
with `LocalSearchParams(maxFlips = ...)` when complete search is too slow on a
large-domain problem or you want stochastic sampling with replacement.

## Bit-blasting

```kotlin
val cnf = BitBlaster.compile(compiled.problem)
val text = cnf.toDimacs()
```

The CNF is for feeding into SAT-ecosystem tools, not a primary solving path.
Use cases:

- Approximate model counting through ApproxMC or GANAK. SAT-based XOR-hashing
  gives counts with provable error bounds. Klause's own enumeration is exact
  but slower on large problems.
- Uniform sampling through UniGen. Adds random XOR constraints over the CNF
  to draw samples close to uniformly, which local search cannot do.
- Weighted sampling over a subset of variables through WAPS or KUS. Compiles
  the relevant slice to a d-DNNF circuit and draws weighted samples from it.
- External CDCL SAT solvers (Kissat, CaDiCaL, CryptoMiniSAT) for hard problems
  where clause learning beats the native backtrack. The adapter modules call
  the bundled binaries.
- Optimization through MaxSAT or PB solvers (RC2, OLL) on WCNF input, when the
  native minimize gives weak bounds.
- Independent infeasibility checks. External solvers emit DRAT proofs of UNSAT
  that can be verified separately.

## Benchmarking

`klause-bench` separates four orthogonal axes so you can control exactly what runs:

- **format** — how an instance is encoded: DIMACS, OPB, JSON-Schema, FlatZinc,
  MiniZinc, SMT-LIB QF_LIA, XCSP3, or built in-code.
- **source** — where it comes from: vendored under `klause-bench/corpus/`, built
  in Kotlin, or an external collection fetched on demand into a cache.
- **solver** — klause's engines (local search, backtracking) plus in-process
  reference adapters: `klause-choco` (Choco, complete) and `klause-ortools`
  (OR-Tools CP-SAT, anytime). No external solver binaries.
- **metric** — time, sampling uniformness, enumeration completeness, cross-backend
  verify, differential parity, and anytime optimization.

Problems live in a declarative catalog (`catalog/Suites.kt`); a **target** binds a
set of suites to a metric. One CLI runs everything:

```
./gradlew :klause-bench:bench --args="list"            # list targets + suites
./gradlew :klause-bench:bench --args="parity-core"     # klause vs Choco on the in-process core
./gradlew :klause-bench:bench --args="mzn-parity-smoke" # compile .mzn, solve, diff vs Choco
./gradlew :klause-bench:listCorpus                     # vendored suites + external collections
./gradlew :klause-bench:warmCorpus --args="warm all"   # pre-fetch external collections
```

To add a problem, edit the catalog; to add a comparison, add a target. Non-vendored
collections (MiniZinc Challenge, SATLIB, …) are fetched transparently on first use and
recorded with their license in `catalog/Suites.kt` and `corpus/PROVENANCE.md`.

