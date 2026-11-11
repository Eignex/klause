# 1. Introduction

Klause is a Kotlin Multiplatform constraint solver. Kotlin schemas and file
frontends produce source models that complete search, local search and arithmetic
services solve under one publication contract. Preparation selects the mechanisms;
changing strategy preserves the source constraints, variable meanings and objective.
[Solving coverage](03-input-semantics.md#solving-coverage) defines the admitted
fragments and strategy limits.

## Results and completeness

Complete workers can certify refutations, bounds and optima. Procedure completeness
is a separate property: a sound incomplete procedure may prove individual instances,
while even a complete procedure can stop under resource limits. Local search supplies
checked witnesses and incumbents; its failure never refutes the source.

| Claim | Required authority |
|---|---|
| SAT or feasible incumbent | A complete assignment checked against the source under its declared semantics |
| UNSAT or infeasible | A completed sound refutation covering the source scope |
| Optimal | A checked feasible assignment and a matching bound or exhaustive proof |
| Unbounded | A source-valid proof of unlimited objective improvement; a feasible point alone is insufficient |
| Best found | A checked feasible incumbent without an optimum proof |
| UNKNOWN | No definitive claim; retain the unsupported, cancellation or resource cause |

A zero heuristic score, a relaxation candidate or satisfaction of an approximated
model never justifies an exact claim. Approximation and configured tolerance have
separate contracts in [input semantics](03-input-semantics.md).

## Model and execution

A source model retains declarations, high-level factor relations, an objective and
[typed semantics](02-model.md#typed-source-semantics). Format defaults define
numeric authority, undeclared ranges and approximation policies. Caller overrides
replace applicable defaults before lowering; results retain the effective policy.
These semantics select no engine.

[Source setup](19-api.md#source-setup) binds the operation, strategy and resources,
then prepares ownership and execution projections. A factor can participate in
finite propagation, local scoring, relaxation and arithmetic encoding together.
Only a complete enforcing mechanism owns its relation; a relaxation provides no
feasibility authority. [Model representation](02-model.md) defines these roles.

Finite propagation owns Boolean and finite integer domains. Arithmetic components
own real values and open integer state. [Shared search](08-search.md) coordinates
decisions, retraction and model assembly without inventing finite bounds for open
coordinates. Frontends parse, lower and render; they never invoke concrete theories.

## Independently checkable proof files

Klause checks supported claims in memory through
[portfolio certificates](12-portfolio.md#witnesses-and-proof-scope),
[LP certification](17-linear-programming.md#lp-certification) and
[source derivations](13-evidence.md). It does not export a proof of an entire
source solve for a separate checker. In-memory engine attestations and checked
original-source derivations have distinct authority; neither a reduced-model
label nor a checked witness supplies a missing refutation or bound derivation.

## System architecture

Dependencies point from policy to mechanism. Model construction preserves source
meaning; solving policy chooses when to search, check or combine strategies;
reusable propagation, scoring and numerical kernels perform the work.

A solve passes through four stages:

1. A schema compiler or format frontend captures declarations, constraints,
   objective, names and semantics.
2. [Presolve](07-presolve.md) reduces the model and retains reconstruction mappings;
   source setup chooses finite, arithmetic or composed execution.
3. Complete and heuristic workers run with private mutable state under the
   containing [resource allowance](06-sessions.md#resource-allowances).
4. Source checks and scoped evidence admit publications; the frontend renders them.

Part III follows those stages. Part IV describes arithmetic services and Part V
the numerical mechanisms beneath them.

### Repository modules

| Location | Responsibility |
|---|---|
| `klause` | Kotlin Multiplatform model, DSL, compilation, formats and solving |
| `klause-cli` | Shared command-line driver, format dispatch, output protocols and platform seams |
| `klause-bench` | Non-published JVM tooling; workflow and evidence live in its [README](../klause-bench/README.md) |

The CLI depends on the library and adds no solving behavior of its own: every
route it runs is reachable through the [library API](19-api.md).

### Package boundaries

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

Numerical kernels own arithmetic and private storage, while model-facing adapters
assemble relations, interpret certificates and preserve source scope. The closed
dependency lists keep those kernels independently replaceable. See
[linear programming](17-linear-programming.md) and
[exact arithmetic](18-exact-arithmetic.md).

---

[Contents](README.md) · Next: [2. Model representation and ownership](02-model.md)
