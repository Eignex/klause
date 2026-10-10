# System architecture

## Repository modules

| Location | Responsibility |
|---|---|
| `klause` | Kotlin Multiplatform model, DSL, compilation, formats and solving |
| `klause-cli` | Shared command-line driver, format dispatch, output protocols and platform seams |
| `klause-bench` | JVM catalog, corpus management, campaign runner and offline measurement tools |
| `klause-mzn-lib` | MiniZinc solver configurations, FlatZinc wrappers, predicate declarations and smoke models |

The Gradle settings include `:klause`, `:klause-cli` and `:klause-bench`.
`klause-mzn-lib` is integration data and scripts, rather than a Gradle module.

## Package boundaries

Packages below are under `com.eignex.klause` in
[commonMain](../klause/src/commonMain/kotlin/com/eignex/klause/).

| Package | Owns | Dependency rule |
|---|---|---|
| `ir` | Literals, variable descriptions, remapping, structural keys and `Problem` | Engine-neutral; no engines, compilation or input formats |
| `model`, `schema`, `compile` | Model expressions, schema DSL and compilation | Use shared IR and lowering |
| `lowering` | Generic Boolean and constraint encodings | Independent of schema compiler and format callers |
| `formats` | Parsing and lowering source syntax | Uses shared lowering; no compiler dependency |
| `factor` | Constraint semantics and factor operations | Supplies mechanisms used by propagation, relaxations and search |
| `propagation` | Finite domains, explanations and incremental propagators | May use immutable arithmetic representations |
| `arithmetic.difference` | Difference constraints, extraction and non-incremental graph operations | No propagation or search state |
| `simplex.exact` | Rational arithmetic and exact feasibility primitives | Reusable numerical mechanism |
| `simplex.basis` | Sparse floating and rational basis operations | Numerical types, `util` and koblas; no search policy |
| `lp.lattice` | Exact integer echelon/Hermite reduction, transforms and triangular bounds | Leaf depending only on `util` |
| `lp.engine` | LP solving, basis state and exact certificates | Only `util`, `simplex.exact`, `lp.lattice` and `simplex.basis` among Klause packages |
| Remaining `lp` packages | Factor relaxations, source reasoning, cuts and bounding policy | Depend on the engine; no reverse dependency |
| `bound` | Combinatorial bounds over global constraints | Factors, IR, objectives, propagation and relaxation views; no `lp.engine` |
| `backtrack` | Finite CP search policy and DFS integration | Uses propagation and shared search |
| `localsearch`, `portfolio` | Local-search policy and engine orchestration | Select and schedule solver mechanisms |
| `meta` | Adaptive large-neighbourhood search and core-guided optimization | Composes solver mechanisms |
| `count` | Exact/approximate counting and near-uniform sampling | Uses compiled finite models and bounded solver cells |
| `theory.difference`, `theory.lia`, `theory.qflra` | Open arithmetic fragments | Must not materialize finite CP domains |
| `solver.pipeline` | Source routing and one verdict surface | Selects theory once; frontends render assignments |
| `solver.search` | Component protocol, decisions, traversal, retraction, learning and complete checks | Owns shared lifecycle across CP and theory components |

Dependencies point from policy to mechanism. In particular, enlarging the engine's
closed dependency list couples reusable numerical work to model policy.
[LpDeclineDisciplineTest](../klause/src/jvmTest/kotlin/com/eignex/klause/lp/LpDeclineDisciplineTest.kt)
checks this structural boundary.

## Model and ownership

`Problem` stores source integer declarations as bounds and, where necessary,
`SourceIntDomains` value sets. Finite mutable search domains belong to
`BakedProblem` and the propagation session. A source declaration does not assign
ownership to an engine.

`Problem.componentPlan()` selects an immutable ownership plan before search. It
builds a compact remapped CP projection and theory fragments. Variables owned by
an open theory never become finite CP domains. Continuous variables are LP columns
and are not integer branch candidates.

Shared search exchanges Boolean literals, semantic integer bounds and typed opaque
theory decisions. Components can maintain private residual state through
`SearchBrancher`, but traversal and decision levels belong to the shared session.
`PropagationSession` remains finite-domain state. Source-keyed `SearchBoolValue`,
`SearchIntValue` and `SearchRealValue` assignments let each component contribute
only the values it owns.

Frontends call the [pipeline](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/)
and render its result. They do not invoke concrete theories directly. Open routing
can prove missing bounds before selecting the finite lane; it retains the source
semantics if that proof declines. See [input semantics](formats.md).

SMT-LIB and source-exact MPS can use an open local-search route when the selected
theory cannot decide an objective or fragment. That route searches for witnesses
without turning failure into refutation. Exact FlatZinc declines an unsupported
open fragment. Route-specific guarantees belong to the shared pipeline and source
publication checks.

## Solving

Finite backtracking combines Boolean learning, explainable CP propagators,
combinatorial global bounds and optional LP relaxations. A Boolean-only path
handles SAT and pseudo-Boolean structure. Local search exposes composable move,
score and acceptance policies. Portfolio engines select arms based on model
structure, objectives and requested search settings.

Problem-level presolve and inprocessing are shared mechanisms. LP adapters
assemble relaxations and reconstruct source results; the engine does not implement
a second problem presolver. Source maps, fixed substitutions, scoped premises and
objective units must survive preparation and reconstruction.

[Search and propagation](search.md) describes learning and scheduling.
[LP architecture](lp/architecture.md) describes numerical ownership and consumers.
