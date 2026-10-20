# 1. Introduction

Klause is a Kotlin Multiplatform constraint solver. A caller describes one
constraint model, from a Kotlin schema or a source file such as FlatZinc, MPS or
SMT-LIB, and Klause solves it with whichever combination of complete search,
local search and arithmetic reasoning fits the model. Choosing a strategy changes
how the problem is searched; the meaning of the model and the guarantees attached
to every published result stay the same.

This chapter sets out what Klause is meant to be: one model surface for every
search style, results whose strength matches their evidence, and a clear boundary
between finite propagation and arithmetic. It then describes the targets the
system is still growing towards, and ends with where the implementation stands.
Later chapters describe the implemented system; gaps are named in the last two
sections and in [current coverage](04-input-semantics.md#solving-coverage).

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
[current coverage](04-input-semantics.md#solving-coverage) are work to close, not
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
separate contracts in [input semantics](04-input-semantics.md).

## Finite propagation boundary

Finite propagation owns Boolean and finite integer domains only. Real values and
open integer state belong to arithmetic components. Shared search owns traversal,
decisions, retraction and model assembly across those owners, so continuous
support never places real domains in CP. See
[shared component search](14-search.md#shared-component-search).

## High-level factors and composed reasoning

These target requirements are tracked by [#2850](https://github.com/Eignex/klause/issues/2850)
and [#2841](https://github.com/Eignex/klause/issues/2841) and are not fully implemented.
[Engine projections](03.2-engine-projections.md#engine-projections-and-factor-capabilities)
and [theory composition](18-theories.md#shared-composition-contract) describe
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

This is not implemented. [Portfolio certificates](16.1-portfolio-witnesses.md),
[LP certification](27-lp-certification.md) and
[source derivations](17-evidence.md) check supported claims in memory, but no
proof of an entire source solve is exported.

## Current execution contracts

The requirements above are partly implemented. Each row links to the chapter that
owns the contract.

| Area | Implemented contract |
|---|---|
| Source execution planning | [Prepared source plans](03.2-engine-projections.md#engine-projections-and-factor-capabilities) retain original identity, coordinate mappings, component ownership and objectives for complete and heuristic construction. |
| Strategy composition | [Portfolio invocation](16.3-portfolio-recipes.md#exact-theory-strategy-execution) builds finite, linear, polynomial, nonlinear-integer and quadratic portfolios through shared arm factories, seeds, quotas and admission. |
| Conditioned completion | [Linear query owners](10-sessions.md#theory-invocation-scopes) retain prepared decomposition and search across repairs under [cumulative allowances](11-resources.md#native-arithmetic-allowances). |
| Strategy admission | [Prepared capabilities](16-portfolio.md#prepared-strategy-admission) expose arms, operations, completeness, claim authority and explicit declines. |
| Source evidence | [Typed source-scoped evidence](17-evidence.md) checks supported derivations against original premises, separately from native admission. |

## Active work toward the vision

| Area | Current implementation | Remaining work |
|---|---|---|
| Factor retention and composed reasoning | Frontends can decompose relations before mechanism selection; theory assembly has specialized admission paths. | [#2850](https://github.com/Eignex/klause/issues/2850) and [#2841](https://github.com/Eignex/klause/issues/2841) implement the [factor requirements](#high-level-factors-and-composed-reasoning). |
| Independent proof export | Exact numerical checks and supported source derivations run in memory. | [Proof files](#independently-checkable-proof-files) and a separate checker remain targets. |
| Format semantics and automatic setup | Formats declare [typed source semantics](03-model.md#typed-source-semantics); [source setup](30-api.md#source-setup) dispatches DIMACS, WCNF, OPB, XCSP3, FlatZinc, SMT-LIB and source strategies. | MPS and legacy entries keep their own execution surfaces, and SMT-LIB admission still chooses its representation. [#3043](https://github.com/Eignex/klause/issues/3043) tracks [one automatic setup](#format-semantics-caller-settings-and-automatic-setup). |

---

[Contents](README.md) · Next: [2. System architecture](02-architecture.md)
