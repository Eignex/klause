# 2. System architecture

The introduction stated what every solving route must guarantee. This chapter
shows how the code base is arranged to keep those guarantees: it follows a problem
from source to result and maps each step onto modules and packages.

The system has three layers. Model construction turns a Kotlin schema or a source
file into an engine-neutral source model. Solving policy decides when to search,
check or combine strategies. Reusable mechanisms supply propagation, scoring and
arithmetic. Dependencies point from policy to mechanism, never back, so a mechanism
can be replaced without touching the policy that calls it.

## From source to result

1. A schema compiler or format frontend preserves declarations, constraints,
   objective and source names in the shared representation, together with its
   [typed source semantics](03-model.md#typed-source-semantics).
2. [Presolve](12-presolve.md) applies sound reductions and retains the mappings
   needed to rebuild source assignments. [Source setup](30-api.md#source-setup)
   selects a finite, open-arithmetic or composed route without inventing bounds
   for open variables.
3. Complete search, local search and portfolio workers run with their own mutable
   state. Finite propagation owns Bool+Int domains; arithmetic services own real
   values and open integer state.
4. Source checks admit witnesses, bounds and terminal verdicts. Frontends render
   the result; they never invoke concrete theories.

## Solving

Finite backtracking combines Boolean learning, explainable CP propagators,
combinatorial global bounds and optional LP relaxations; a Boolean-only path
handles SAT and pseudo-Boolean structure. Local search exposes composable move,
score and acceptance policies, and portfolios select arms from model structure,
objectives and requested settings. LP adapters assemble relaxations and
reconstruct source results; the LP engine has no presolver of its own.

Part III follows these stages in the order a solve meets them, Part IV covers the
[theory services](18-theories.md) search consults for arithmetic, and Part V the
numerical mechanisms beneath them.

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
| `ir` | Literals, variable descriptions, remapping, structural keys, polynomial source formulas and `Problem` | No engines, compilation or input formats |
| `model`, `schema`, `compile` | Model expressions, schema DSL and compilation | Use shared IR and lowering |
| `lowering` | Generic Boolean and constraint encodings | Independent of schema compiler and format callers |
| `formats` | Parsing and lowering source syntax | Uses shared lowering; no compiler or concrete theory dependency |
| `factor` | Constraint semantics and factor operations | Supplies mechanisms used by propagation, relaxations and search |
| `propagation` | Finite domains, explanations and incremental propagators | May use immutable arithmetic representations |
| `arithmetic.polynomial` | Exact sparse polynomials, elimination and real-root isolation | Only `simplex.exact` and `util` |
| `arithmetic.quadratic` | Exact convex quadratic bounds, attainment and rays | Only `simplex.exact` and `util` |
| `arithmetic.transcendental` | Certified rational function enclosures and affine relaxations | Only `simplex.exact` and `util` |
| `arithmetic.real` | Exact symbolic real values with certified comparison | Only numerical kernels; no model or solver state |
| `arithmetic.difference` | Difference constraints, extraction and non-incremental graph operations | No propagation or search state |
| `simplex.exact` | Rational arithmetic and exact feasibility primitives | Reusable numerical mechanism |
| `simplex.basis` | Sparse floating and rational basis operations | Numerical types, `util` and koblas; no search policy |
| `lp.lattice` | Exact integer echelon/Hermite reduction, transforms and triangular bounds | Leaf depending only on `util` |
| `lp.engine` | LP solving, basis state and exact certificates | Only `util`, `simplex.exact`, `lp.lattice` and `simplex.basis` among Klause packages |
| Remaining `lp` packages | Factor relaxations, source reasoning, cuts and bounding policy | Depend on the engine; no reverse dependency |
| `bound` | Combinatorial bounds over global constraints | Factors, IR, objectives, propagation and relaxation views; no `lp.engine` |
| `solver.resources` | Decision/move ledgers, instruction conversion and worker quotas | Only `util` |
| `backtrack` | Finite CP search policy and DFS integration | Uses propagation and shared search |
| `localsearch`, `portfolio` | Local-search policy and engine orchestration | Select and schedule solver mechanisms |
| `meta` | Adaptive large-neighbourhood search and core-guided optimization | Composes solver mechanisms |
| `count` | Exact/approximate counting and near-uniform sampling | Uses compiled finite models and bounded solver cells |
| `theory.difference` | Integer difference checks and shared graph components | Must not materialize finite CP domains |
| `theory.qflra` | Linear integer, real and mixed checks, including real difference graphs | Reads source models and arithmetic services; no finite-domain materialization |
| `theory.qfnia` | Sound incomplete nonlinear integer search | Neutral polynomial data and exact real completion; no finite domains |
| `theory.qfnra` | Complete polynomial-real decision | Neutral polynomial formulas and the exact kernel; no finite domains |
| `theory.transcendental` | Sound incomplete real function checks and relaxations | Neutral function formulas and numerical kernels; no finite domains |
| `solver.validation` | Source coordinate checks and composed fixpoint verification | No orchestration or concrete theories |
| `solver.pipeline` | Source routing and one verdict surface | Selects theory once; frontends render assignments |
| `solver.search` | Component protocol, decisions, traversal, retraction, learning and complete checks | No concrete theory, LP, propagation or backtracking dependencies |

These rules all point from policy to mechanism. Enlarging a numerical package's
closed dependency list couples reusable numerical work to model policy, so it is
the change to refuse.

## Numerical package ownership

Numerical kernels own arithmetic and private working storage; they do not own source
models, search strategy or verdict publication. The LP engine's closed dependency
list keeps it independently replaceable, while model-facing adapters assemble rows,
interpret certificates and preserve source scope outside it. See
[LP architecture](26-lp-architecture.md) and
[exact arithmetic kernels](28-exact-arithmetic.md#exact-polynomial-and-algebraic-arithmetic).

---

Previous: [1. Introduction](01-introduction.md) · [Contents](README.md) · Next: [3. Model representation and ownership](03-model.md)
