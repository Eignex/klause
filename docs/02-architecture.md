# 2. System architecture

The introduction stated what every solving route must guarantee. This chapter
shows how the code base is arranged to keep those guarantees. It follows a
problem from source to result, names the layers that handle each step, and maps
them onto modules and packages. Later parts of the book take each layer in turn:
Part II the model and its frontends, Part III the solving stages, Parts IV and V
the arithmetic underneath.

The system has three layers. Model construction turns a Kotlin schema or a source
file into an engine-neutral source model. Solving policy decides when to search,
check or combine strategies. Reusable mechanisms supply propagation, scoring and
arithmetic. Dependencies point from policy to mechanism, never back, so a mechanism
can be replaced without touching the policy that calls it.

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

Between steps 1 and 2, frontends declare [typed source semantics](03-model.md#typed-source-semantics),
caller overrides replace them before lowering, and [source setup](30-api.md#source-setup)
selects the route, theories, engines and portfolio. MPS and legacy typed entries keep
their own execution surfaces; [format semantics and automatic setup](01-introduction.md#format-semantics-caller-settings-and-automatic-setup)
defines the target contract.

[Input semantics](04-input-semantics.md) owns source lowering and route qualifications.
[Presolve](12-presolve.md) owns reconstruction. [Model ownership](03-model.md)
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

Part III follows these stages in the order a solve meets them:
[sessions](10-sessions.md) and [resource allowances](11-resources.md) bound each
invocation, [presolve](12-presolve.md) prepares the model,
[source validation](13-validation.md) checks candidate assignments, and
[complete search](14-search.md), [local search](15-local-search.md) and
[portfolios](16-portfolio.md) do the searching. Part IV describes the
[theory services](18-theories.md) that search consults for arithmetic, and Part V
the numerical mechanisms beneath them, starting with the
[LP engine](26-lp-architecture.md).

## Repository modules

| Location | Responsibility |
|---|---|
| `klause` | Kotlin Multiplatform model, DSL, compilation, formats and solving |
| `klause-cli` | Shared command-line driver, format dispatch, output protocols and platform seams |

The CLI depends on the library and adds no solving behavior of its own: every
route it runs is reachable through the [library API](30-api.md).

## Package boundaries

Packages below are under `com.eignex.klause` in
[commonMain](../klause/src/commonMain/kotlin/com/eignex/klause).

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
| `arithmetic.transcendental` | Certified rational function enclosures and affine relaxations | Only `arithmetic.transcendental`, `simplex.exact` and `util`; source truth belongs to adapters |
| `arithmetic.real` | Exact symbolic real values with algebraic leaves and certified comparison | Only numerical kernels and neutral controls; no source model or solver state |
| `arithmetic.difference` | Difference constraints, extraction and non-incremental graph operations | No propagation or search state |
| `simplex.exact` | Rational arithmetic and exact feasibility primitives | Reusable numerical mechanism |
| `simplex.basis` | Sparse floating and rational basis operations | Numerical types, `util` and koblas; no search policy |
| `lp.lattice` | Exact integer echelon/Hermite reduction, transforms and triangular bounds | Leaf depending only on `util` |
| `lp.engine` | LP solving, basis state and exact certificates | Only `util`, `simplex.exact`, `lp.lattice` and `simplex.basis` among Klause packages |
| Remaining `lp` packages | Factor relaxations, source reasoning, cuts and bounding policy | Depend on the engine; no reverse dependency |
| `bound` | Combinatorial bounds over global constraints | Factors, IR, objectives, propagation and relaxation views; no `lp.engine` |
| `solver.resources` | Shared decision/move ledgers, instruction conversion and persistent worker quotas | Only resource mechanisms and `util`; published `backtrack.NodeBudget` forwards here |
| `backtrack` | Finite CP search policy and DFS integration | Uses propagation and shared search |
| `localsearch`, `portfolio` | Local-search policy and engine orchestration | Select and schedule solver mechanisms |
| `meta` | Adaptive large-neighbourhood search and core-guided optimization | Composes solver mechanisms |
| `count` | Exact/approximate counting and near-uniform sampling | Uses compiled finite models and bounded solver cells |
| `theory.difference` | Integer difference checks and shared graph components | Must not materialize finite CP domains |
| `theory.qflra` | Linear integer, real and mixed checks, including real difference graphs | Reads source models and arithmetic services; no finite-domain materialization |
| `theory.qfnia` | Sound incomplete nonlinear integer candidate search and source checking | Uses neutral polynomial metadata and exact real completion; never finite CP domains |
| `theory.qfnra` | Complete polynomial-real decision with exact CAD and Boolean search | Reads neutral polynomial formulas and the exact kernel; no finite domains or linear-engine state |
| `theory.transcendental` | Sound incomplete real function source checks and relaxations | Reads neutral function formulas and numerical kernels; no finite domain state |
| `solver.validation` | Source coordinate checks and private composed fixpoint verification | Source/factor data, propagation, sample/incumbent contracts and exact row views; no orchestration or concrete theories |
| `solver.pipeline` | Source routing and one verdict surface | Selects theory once; frontends render assignments |
| `solver.search` | Component protocol, exact source predicates, decisions, traversal, retraction, learning and complete checks | Owns shared lifecycle; no concrete theory, LP, propagation or backtracking dependencies |

These rules all point from policy to mechanism. Enlarging a numerical package's
closed dependency list couples reusable numerical work to model policy, so it is
the change to refuse.

## Numerical package ownership

Numerical kernels own arithmetic and private working storage; they do not own source
models, search strategy or verdict publication. The LP engine's closed dependency
list keeps it independently replaceable. Model-facing adapters assemble rows,
interpret certificates and preserve source scope outside that engine.

[LP architecture](26-lp-architecture.md) defines linear model authority, basis state and
consumers. [LP certification](27-lp-certification.md) defines numerical evidence.
[Theory contracts](18-theories.md) own fragment truth and decision checks;
[exact polynomial arithmetic](28-exact-arithmetic.md#exact-polynomial-and-algebraic-arithmetic) owns polynomial and algebraic
operations. Numerical tests run in the solver's JVM and Native test suites.

[Library API](30-api.md) distinguishes supported contracts from experimental and
implementation surfaces.

---

Previous: [1. Introduction](01-introduction.md) · [Contents](README.md) · Next: [3. Model representation and ownership](03-model.md)
