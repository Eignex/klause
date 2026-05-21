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
integers, Booleans, floats (bucketed to integers), and nominals (one-hot
encoded as Booleans). The DSL covers arithmetic, logic, comparisons, and a
range of global constraints. MiniZinc models can use klause as a backend
through klause-mzn-lib.

Two native engines, both implementing Solver and Optimizer:

- A local-search engine (default: adaptive probSAT; also WalkSat, DDFW,
  simulated annealing, CCA variants). Used for sampling and stochastic
  solving.
- A complete CSP backtrack engine with propagation, configurable
  variable and value heuristics, branch-and-bound minimize, and
  model-blocking enumeration.

Optional adapter modules send the same problem to external solvers when
useful: klause-logicng for bit-blasted SAT, klause-z3 for SMT. Side doors,
not the core.

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

The DSL covers:

- Boolean: and, or, implies, iff, not, xor.
- Nominal: eq, ne against label literals.
- Integer arithmetic: +, -, *, /, % with Euclidean division and modulo
  (remainder is always non-negative, matching SMT-LIB QF_LIA), including
  variable-by-variable multiplication.
- Comparisons: le, lt, ge, gt, eq, ne over arbitrary integer expressions.
- Counting: atMost, atLeast, cardinality, gcc, pseudoBoolean, among,
  count, nValue.
- Permutations and ordering: allDifferent, allDifferentExcept, allEqual,
  inverse, sort, lexLeq, lexLt, valuePrecede.
- Scheduling: cumulative and disjunctive with Vilim Theta-tree
  edge-finding propagation.
- Routing: circuit, subcircuit.
- Packing: binPacking, knapsack.
- Tabular: table, notTable, regular (DFA constraint).
- Integer expressions: min, max, abs, element, member, argMin, argMax,
  ifThenElse.
- Linking: channel.

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

// Or point at one schema variable, MiniZinc-style:
val cheapest = solver.minimize(compiled.minimize(schema.budget), LocalSearchParams(maxFlips = 100_000))
```

Local search is the default. Swap in the backtrack solver when you
need completeness or true without-replacement enumeration.

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

## TODO

Grouped by workstream. CP covers the complete-search engine and propagators; LS covers the local-search engine and strategies. Within each group, items are listed in suggested execution order and sized so each bullet fits in a single focused session.

- [CP] Optional variables in the schema DSL: optIntVar(range) and optBoolVar() declarators returning (present, value) pairs; decoders surface present=false as absent in the result type.
- [CP] Optional-variable lowering for comparisons and logical operators: reify each opt expression on the conjunction of involved presence bools so existing factors stay opt-ignorant.
- [CP] Algebraic opt rewriting in Linear / Cardinality / PseudoBoolean / sum builders: accept presence-multiplied terms (present_i * value_i) so aggregating-by-sum constraints get native propagation without per-factor opt awareness.
- [CP] Opt-aware Cumulative and Disjunctive variants: presents: BoolArray gating each task's energy and compulsory-part contribution; Theta-tree leaves stay inactive for absent tasks.
- [CP] Opt-aware AllDifferent / GCC / nValue / Count: variants taking presents: BoolArray that restrict counting and matching to the present subset; FlatZinc mappings for the opt versions of these globals.
- [CP] Network flow DSL: networkFlow(arcs, balance, flow) and networkFlowCost(arcs, balance, weight, flow, cost) builders with decomposition lowering to per-node sum and weighted-sum.
- [CP] Network flow propagator: dedicated min-cost-flow factor (SSP / cost-scaling) with reduced-cost arc pruning and infeasibility detection beyond the linear decomposition.
- [CP] Network flow FlatZinc mapping: wire network_flow and network_flow_cost in klause-mzn-lib to the new builders, with redefinition fallback for backends without the propagator.
- [CP] geost global: DSL builder, N-dimensional non-overlapping placement propagator (sweep-based, generalization of Diffn), and FlatZinc mapping for fzn_geost.
- [CP] MDD global: DSL builder, propagator over multi-valued decision diagrams (incremental support counts on edges), and FlatZinc mapping for fzn_mdd.
- [CP] cost_regular global: DSL builder, weighted-DFA propagator accumulating edge costs into a cost variable, and FlatZinc mapping for fzn_cost_regular.
- [CP] cost_mdd global: DSL builder, weighted-MDD propagator, and FlatZinc mapping for fzn_cost_mdd.
- [CP] path global: DSL builder, directed-path propagator over node/edge variables with reachability filtering, and FlatZinc mapping for fzn_path.
- [CP] tree global: DSL builder, directed spanning-tree propagator with connectedness and acyclicity filtering, and FlatZinc mapping for fzn_tree.
- [CP] arg_sort global: DSL builder, dedicated propagator over (values, permutation) stronger than Sort + Inverse decomposition, and FlatZinc mapping for fzn_arg_sort_int.
- [CP] Generalized alldifferent_except: DSL builder taking an arbitrary excluded-value set, native propagator preserving free-value reasoning that remap-to-0 discards, and FlatZinc mapping for fzn_alldifferent_except.
- [CP] Migrate hot-path propagators (table, notIn, interior-SAC hole emission, MDD/cost_mdd/cost_regular support sets) to bitset domain operations.
- [CP] Schema DSL set declarators: multiple(labels) and setVar(over = range), decoders, indicator-bool lowering.
- [CP] Schema DSL set expressions: inSet, subsetOf, disjointFrom, union, intersect, card.
- [CP] Native bitset set-propagators for set algebra over large universes.
- [CP] Core-guided optimization: upgrade Unsat.conflictFactors to extract multi-factor cores from the propagation trail.
- [CP] Core-guided optimization: assumption-based satisfy API (incremental unsat under hypotheses).
- [CP] Core-guided optimization: OLL outer loop (unweighted MaxSAT, relax cores with cardinality constraints).
- [CP] Core-guided optimization: RC2 weight handling for weighted MaxSAT and stratified weights.
- [LS] Move-pool inlining: pack BoolFlip / IntSet into a Long-backed MoveSink lane.
- [LS] Incremental updateBoolBreakMakeForFlip for Cardinality, PseudoBoolean, Xor.
- [LS] Extend incremental updateBoolBreakMakeForFlip to Reified{Cardinality,PseudoBoolean,Linear} and IntCmpReified.
- [LS] updateIntBreakMakeForIntSet hook framework mirroring the bool path.
- [LS] Convert Linear, BinPacking, Knapsack to incremental updateIntBreakMakeForIntSet.
- [LS] Convert GlobalCardinality, AllDifferent family, AllEqual to incremental updateIntBreakMakeForIntSet.
- [LS] Convert Among, Count, NValue, Member, Inverse, Monotone, Sequence to incremental updateIntBreakMakeForIntSet.
- [LS] Problem-aware moves for cumulative (resource-feasibility-preserving swaps and shifts).
- [LS] Problem-aware moves for lexLeq / lexLt (lex-preserving swap neighbourhood).
- [LS] Problem-aware moves for reified factors (toggle-driven sub-region exploration).
- [LS] Richer VNS: VND (variable neighbourhood descent) over the existing neighbourhood ladder.
- [LS] Richer VNS: per-level neighbourhood operators and skewed-VNS acceptance.
- [LS] ILS: basin-hopping perturbation kick.
- [LS] ILS: linkage-aware crossover.
- [LS] ALNS: cumulative time-window destroy operators.
- [LS] ALNS: regret-based and best-improving repair operators.
- [LS] Multi-core LS portfolio: worker-config factory and per-worker strategy selection.
- [LS] Multi-core LS portfolio: best-feasible sharing across workers.
- [LS] Multi-core LS portfolio: shared kumulant stats for a restart-level bandit.
