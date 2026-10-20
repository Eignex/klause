# 1. Introduction

Klause is a Kotlin Multiplatform constraint solver. A caller describes one
constraint model, from a Kotlin schema or a source file such as FlatZinc, MPS or
SMT-LIB, and Klause solves it with whichever combination of complete search,
local search and arithmetic reasoning fits the model. Choosing a strategy changes
how the problem is searched; the meaning of the model and the guarantees attached
to every published result stay the same.

This chapter sets out what Klause is meant to be: one model surface for every
search style, results whose strength matches their evidence, and a clear boundary
between finite propagation and arithmetic. It then records where the
implementation stands against those targets and closes with the system
architecture that keeps the guarantees. Later chapters describe the implemented
system; remaining gaps are listed in
[current coverage](03-input-semantics.md#solving-coverage).

## Shared model breadth

Every supported problem must have both a runnable complete-search arm and a
runnable local-search arm, and callers must always be able to compose the two
into a portfolio. Both arms retain the same source constraints, variable meanings
and objective. This applies to finite, open, continuous and mixed models,
including pure theory problems. Single-arm execution remains an explicit caller
choice.

Each arm must execute meaningful search over the admitted model: dropping
constraints, rejecting the model or immediately returning UNKNOWN is a coverage
gap, not engine parity. Shared breadth does not require equal filtering, scoring,
performance or proof authority. The gaps in
[current coverage](03-input-semantics.md#solving-coverage) are work to close, not
permanent exclusions.

## Results and completeness

The complete-search arm is the portfolio's source of certified refutations, bounds
and optimum claims. That role is distinct from mathematical completeness: an
incomplete procedure can still produce sound proofs for individual instances, and
any guarantee of eventual decision must name its fragment and procedure. Even a
complete procedure can stop without a verdict under time, work or representation
limits. Local search contributes checked witnesses and incumbents; unsuccessful
local search is never a refutation. Support requires defined source semantics and
sound publication, not decidability.

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

## Finite propagation boundary

Finite propagation owns Boolean and finite integer domains only. Real values and
open integer state belong to arithmetic components. Shared search owns traversal,
decisions, retraction and model assembly across those owners, so continuous
support never places real domains in CP. See
[shared component search](08-search.md#shared-component-search).

## High-level factors and composed reasoning

These target requirements are tracked by [#2850](https://github.com/Eignex/klause/issues/2850)
and [#2841](https://github.com/Eignex/klause/issues/2841) and are not fully implemented.
[Engine projections](02-model.md#engine-projections-and-factor-capabilities)
and [theory composition](14-theories.md#shared-composition-contract) describe
current execution.

Frontends retain the strongest source factor available, and preparation selects
its propagation, scoring, relaxation and theory mechanisms. An `Encoder` installs
the factor's relation into a selected theory by direct support or a
source-equivalent encoding; generic encodings belong in `lowering`, and private
auxiliaries retain source mappings.

Variable ownership is separate from factor participation. A factor can use a
`Propagator`, an `Encoder`, an `Invariant` and relaxation views together: joint
linear reasoning can complement finite bound propagation, and finite globals can
stay native beside real completion. Theories consuming finite variables preserve
integrality, bounds and holes. An arithmetic relation does not by itself transfer
unrelated finite variables or factors to theory search.

Providers distinguish direct, equivalent, conditioned, relaxation-only and
unsupported support. Conditions retain their premises and retract with shared
search, and live arithmetic systems belong to shared `SearchComponent` instances.
Every source factor must be enforced under its declared semantics before a
solution is published, and proof claims require agreement between all
participating components.

## Format semantics, caller settings and automatic setup

A frontend produces a source model with declared semantics, and the library
selects how to solve it. Three layers determine the effective setup, in order:

1. **Format semantics.** Each format declares the defaults its syntax implies as
   typed data carried with the model: numeric authority (exact rationals for
   SMT-LIB, decimal rows under a published tolerance for MPS, binary64 floats with
   a bounded grid for unranged FlatZinc floats), undeclared ranges and absent
   optional values. These describe the solution set and result qualification;
   they select no engine or route.
2. **Caller settings.** Library parameters and CLI flags override individual
   semantic defaults. An override means the same thing for every format with that
   semantic and is rejected where it has no meaning. Results carry the effective
   qualification, so an approximating override cannot publish an exact claim.
3. **Automatic setup.** One library entry receives the model, its semantics, the
   operation and the caller's strategy and resource settings, and selects the
   route, theory composition, engines and portfolio: the strongest complete
   mechanism paired with local search. An explicit engine request narrows that
   selection without bypassing admission.

Frontends parse, lower to the [strongest factors](#high-level-factors-and-composed-reasoning),
attach semantics and render results. They do not prove bounds, choose lowerings
by solver capability or interpret engine names, so the CLI and library reach the
same route for the same model.

## Independently checkable proof files

The target includes optional proof files that let a separate checker verify
published claims without trusting the search: feasible assignments, refutations,
objective bounds, attained optima and unboundedness under the declared source
semantics. A timed-out run can still retain a checked incumbent and a certified
bound.

Proofs must connect back to the original source and invocation assumptions.
Lowering, presolve, propagation, learned clauses and imported bounds need
checkable justifications wherever they contribute to a claim; checking an exported
encoding alone leaves its translation trusted, and that boundary must be explicit.
Local search supplies checkable solutions without logging its moves, while the
proof-producing arm supplies justified deductions. Different fragments may need
different formats: pseudo-Boolean deduction proofs for finite models, exact
numerical certificates for linear arithmetic, algebraic evidence for polynomial
arithmetic.

This is not implemented. [Portfolio certificates](12-portfolio.md#witnesses-and-proof-scope),
[LP certification](17-linear-programming.md#lp-certification) and
[source derivations](13-evidence.md) check supported claims in memory, but no
proof of an entire source solve is exported.

## Current execution contracts

The requirements above are partly implemented. Each row links to the chapter that
owns the contract.

| Area | Implemented contract |
|---|---|
| Source execution planning | [Prepared source plans](02-model.md#engine-projections-and-factor-capabilities) retain original identity, coordinate mappings, component ownership and objectives for complete and heuristic construction. |
| Strategy composition | [Portfolio invocation](12-portfolio.md#exact-theory-strategy-execution) builds finite, linear, polynomial, nonlinear-integer and quadratic portfolios through shared arm factories, seeds, quotas and admission. |
| Conditioned completion | [Linear query owners](06-sessions.md#theory-invocation-scopes) retain prepared decomposition and search across repairs under [cumulative allowances](06-sessions.md#native-arithmetic-allowances). |
| Strategy admission | [Prepared capabilities](12-portfolio.md#prepared-strategy-admission) expose arms, operations, completeness, claim authority and explicit declines. |
| Source evidence | [Typed source-scoped evidence](13-evidence.md) checks supported derivations against original premises, separately from native admission. |

## Active work toward the vision

| Area | Current implementation | Remaining work |
|---|---|---|
| Factor retention and composed reasoning | Frontends can decompose relations before mechanism selection; theory assembly has specialized admission paths. | [#2850](https://github.com/Eignex/klause/issues/2850) and [#2841](https://github.com/Eignex/klause/issues/2841) implement the [factor requirements](#high-level-factors-and-composed-reasoning). |
| Independent proof export | Exact numerical checks and supported source derivations run in memory. | [Proof files](#independently-checkable-proof-files) and a separate checker remain targets. |
| Format semantics and automatic setup | Formats declare [typed source semantics](02-model.md#typed-source-semantics); [source setup](19-api.md#source-setup) dispatches DIMACS, WCNF, OPB, XCSP3, FlatZinc, SMT-LIB and source strategies. | MPS and legacy entries keep their own execution surfaces, and SMT-LIB admission still chooses its representation. [#3043](https://github.com/Eignex/klause/issues/3043) tracks [one automatic setup](#format-semantics-caller-settings-and-automatic-setup). |

## System architecture

The code base is arranged to keep those guarantees. This section follows a
problem from source to result and maps each step onto modules and packages.

The system has three layers. Model construction turns a Kotlin schema or a source
file into an engine-neutral source model. Solving policy decides when to search,
check or combine strategies. Reusable mechanisms supply propagation, scoring and
arithmetic. Dependencies point from policy to mechanism, never back, so a mechanism
can be replaced without touching the policy that calls it.

### From source to result

1. A schema compiler or format frontend preserves declarations, constraints,
   objective and source names in the shared representation, together with its
   [typed source semantics](02-model.md#typed-source-semantics).
2. [Presolve](07-presolve.md) applies sound reductions and retains the mappings
   needed to rebuild source assignments. [Source setup](19-api.md#source-setup)
   selects a finite, open-arithmetic or composed route without inventing bounds
   for open variables.
3. Complete search, local search and portfolio workers run with their own mutable
   state. Finite propagation owns Bool+Int domains; arithmetic services own real
   values and open integer state.
4. Source checks admit witnesses, bounds and terminal verdicts. Frontends render
   the result; they never invoke concrete theories.

### Solving

Finite backtracking combines Boolean learning, explainable CP propagators,
combinatorial global bounds and optional LP relaxations; a Boolean-only path
handles SAT and pseudo-Boolean structure. Local search exposes composable move,
score and acceptance policies, and portfolios select arms from model structure,
objectives and requested settings. LP adapters assemble relaxations and
reconstruct source results; the LP engine has no presolver of its own.

Part III follows these stages in the order a solve meets them, Part IV covers the
[theory services](14-theories.md) search consults for arithmetic, and Part V the
numerical mechanisms beneath them.

### Repository modules

| Location | Responsibility |
|---|---|
| `klause` | Kotlin Multiplatform model, DSL, compilation, formats and solving |
| `klause-cli` | Shared command-line driver, format dispatch, output protocols and platform seams |

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

These rules all point from policy to mechanism. Enlarging a numerical package's
closed dependency list couples reusable numerical work to model policy, so it is
the change to refuse.

### Numerical package ownership

Numerical kernels own arithmetic and private working storage; they do not own source
models, search strategy or verdict publication. The LP engine's closed dependency
list keeps it independently replaceable, while model-facing adapters assemble rows,
interpret certificates and preserve source scope outside it. See
[LP architecture](17-linear-programming.md) and
[exact arithmetic kernels](18-exact-arithmetic.md#exact-polynomial-and-algebraic-arithmetic).

---

[Contents](README.md) · Next: [2. Model representation and ownership](02-model.md)
