# System architecture

Klause is a Kotlin Multiplatform constraint solver. Schema compilation and file
frontends produce engine-neutral source models. Search strategies operate on
projections of those models, use propagation and arithmetic services, and publish
results checked against the source.

The architecture has three main layers: model construction, solving policy and
reusable mechanisms. Policy chooses when to search or check; mechanisms supply
propagation, scoring and arithmetic. Dependencies point from policy to mechanism.
The [solver vision](vision.md) defines the intended common breadth and portfolio
contract. [Current coverage](formats.md#solving-coverage) describes implemented limits.

## From source to result

1. A schema compiler or format frontend preserves declarations, constraints,
   objective and source names in the shared representation.
2. Preparation applies sound reductions and retains the mappings needed to rebuild
   source assignments. The pipeline selects a finite, open-arithmetic or composed
   route without inventing bounds for open variables.
3. Complete search, local search and portfolio workers run with their own mutable
   state. Finite propagation owns Bool+Int domains; arithmetic services own real
   values and open integer state.
4. Source checks and applicable proof checks admit witnesses, bounds and terminal
   verdicts. Frontends render the result; they do not invoke concrete theories.

[Input semantics](formats.md) owns source lowering and route qualifications.
[Presolve](solver/presolve.md) owns reconstruction. [Model ownership](model.md)
separates source data, engine views and published assignments.

## Solving

The pipeline selects mechanisms from the model structure and requested operation.
Finite backtracking combines Boolean learning, explainable CP propagators,
combinatorial global bounds and optional LP relaxations. A Boolean-only path
handles SAT and pseudo-Boolean structure. Local search exposes composable move,
score and acceptance policies. Portfolio engines select arms based on model
structure, objectives and requested search settings.

Problem-level presolve and inprocessing are shared mechanisms. LP adapters
assemble relaxations and reconstruct source results; the engine does not implement
a second problem presolver. Source maps, fixed substitutions, scoped premises and
objective units must survive preparation and reconstruction.

[Search and propagation](solver/search.md) defines shared traversal and reasons.
[Sessions](solver/sessions.md) define caller scopes and stream ownership;
[local search](solver/local-search.md) defines assignment state and moves;
[portfolios](solver/portfolio.md) define scheduling and result admission.
[Presolve](solver/presolve.md) defines source reconstruction and phase accounting.
[Theory services](theories.md) defines peer difference, linear integer, linear real,
linear mixed, nonlinear integer and polynomial real contracts and their common composition boundary.
[LP architecture](lp/architecture.md) defines numerical ownership and consumers.

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
| `ir` | Literals, variable descriptions, remapping, structural keys, polynomial source expressions/formulas and `Problem` | Engine-neutral; no engines, compilation or input formats |
| `model`, `schema`, `compile` | Model expressions, schema DSL and compilation | Use shared IR and lowering |
| `lowering` | Generic Boolean and constraint encodings | Independent of schema compiler and format callers |
| `formats` | Parsing and lowering source syntax | Uses shared lowering; no compiler or concrete theory dependency |
| `factor` | Constraint semantics and factor operations | Supplies mechanisms used by propagation, relaxations and search |
| `propagation` | Finite domains, explanations and incremental propagators | May use immutable arithmetic representations |
| `arithmetic.polynomial` | Exact sparse polynomials, elimination determinants and rational real-root isolation | Only `arithmetic.polynomial`, `simplex.exact` and `util`; no model or solver state |
| `arithmetic.quadratic` | Exact convex quadratic bounds, attainment, approach paths and improving rays | Only `arithmetic.quadratic`, `simplex.exact` and `util`; source/result scope belongs to adapters |
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
| `theory.difference` | Integer difference checks and shared graph components | Must not materialize finite CP domains |
| `theory.qflra` | Linear integer, real and mixed checks, including real difference graphs | Reads source models and arithmetic services; no finite-domain materialization |
| `theory.qfnia` | Sound incomplete nonlinear integer candidate search and source checking | Uses neutral polynomial metadata and exact real completion; never finite CP domains |
| `theory.qfnra` | Complete polynomial-real decision with exact CAD and Boolean search | Reads neutral polynomial formulas and the exact kernel; no finite domains or linear-engine state |
| `solver.pipeline` | Source routing and one verdict surface | Selects theory once; frontends render assignments |
| `solver.search` | Component protocol, decisions, traversal, retraction, learning and complete checks | Owns shared lifecycle across CP and theory components |

## Boundary validation

Dependencies point from policy to mechanism. In particular, enlarging the engine's
closed dependency list couples reusable numerical work to model policy.
[checkArchitecture](../scripts/check_architecture.py) checks production sources in every
platform source set without compiling Kotlin. It enforces the closed numerical and
IR lists, caller-independent lowering, frontend/compiler/theory separation, the
pipeline's neutral metadata and the prohibition on finite-domain state in open
theories. Imports, aliases, wildcard imports and qualified executable references
are checked; comments and literal text are excluded. Compiler resolution and
consumer behavior remain the responsibility of the JVM/native gates.
[LpDeclineDisciplineTest](../klause/src/jvmTest/kotlin/com/eignex/klause/lp/LpDeclineDisciplineTest.kt)
checks proof-decline behavior at engine consumers.

## Numerical package ownership

Numerical kernels own arithmetic and private working storage; they do not own source
models, search strategy or verdict publication. The LP engine's closed dependency
list keeps it independently replaceable. Model-facing adapters assemble rows,
interpret certificates and preserve source scope outside that engine.

[LP architecture](lp/architecture.md) defines linear model authority, basis state and
consumers. [LP certification](lp/certification.md) defines numerical evidence.
[Theory contracts](theories.md) own fragment truth and decision checks;
[exact polynomial arithmetic](arithmetic/polynomial.md) owns polynomial and algebraic
operations. Numerical tests run in the solver's JVM and Native test suites.

[Library API](api.md) distinguishes supported contracts from experimental and
implementation surfaces.
