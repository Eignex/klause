# Solver vision

Klause aims to let callers describe one constraint model and combine proof-producing
search with local search over that same model. Strategy choice changes how the
problem is searched, while source semantics and result guarantees stay shared.

This page records the intended support contract, including capabilities that are
not fully implemented. [System architecture](architecture.md) describes the current
system; [input semantics and coverage](formats.md) records its supported routes and
limits. Independently checkable proof export remains a target capability.

## Current execution contracts

Preparation, strategy construction and publication share source ownership while
retaining theory-specific mechanisms and limits.

| Area | Implemented contract |
|---|---|
| Source execution planning | [Prepared source plans](model.md#engine-projections-and-factor-capabilities) retain original identity, coordinate mappings, component ownership and objectives for complete and heuristic construction. Finite/linear and polynomial projections retain their own mechanisms under that plan; nonlinear integer sources retain their neutral representation. |
| Strategy composition | [Portfolio invocation](solver/portfolio.md#exact-theory-strategy-execution) constructs finite, linear, polynomial, nonlinear-integer and quadratic portfolios through shared arm factories, lanes, seeds, quotas, source admission and cleanup. Workers retain private mutable state and exact objective contracts. |
| Conditioned completion | [Linear query owners](solver/sessions.md#theory-invocation-scopes) retain prepared source decomposition, canonical CP projection, completed native row factories and initialized search across repairs. Conditions retract on every exit; interrupted mutable initialization retries from retained preparation under [cumulative allowances](solver/resources.md#native-arithmetic-allowances). Interrupted immutable preparation exports no partial factory. |
| Strategy admission | [Prepared capabilities](solver/portfolio.md#prepared-strategy-admission) expose participating arms, supported operations, procedure completeness, claim authority and explicit declines. Direct factor mechanisms alone do not establish whole-source support; resource stops belong to the invocation. |

These contracts preserve finite propagation's Bool+Int boundary, private mutable
worker state, numerical dependency boundaries and source publication checks.
[Current coverage](formats.md#solving-coverage) remains the reference for admitted
fragments and operations; shared construction does not supply a new arithmetic
decision procedure or independent proof-file support.

## Active work toward the vision

The remaining gaps have narrower scope than the shared execution contracts above.
Subsystem pages describe current guarantees; linked issues track residual work.

| Area | Current implementation | Remaining work |
|---|---|---|
| Source evidence | [Typed source-scoped evidence](solver/evidence.md) retains original premises, invocation restrictions, exact claims and optional transformation justifications. Supported derivations are checked separately from engine admission; missing coverage remains unavailable. | [#2649](https://github.com/Eignex/klause/issues/2649) tracks remaining original-source derivation coverage. |
| Transcendental orchestration | [Transcendental execution](theories/transcendental.md#solving-and-composition) shares source completion across strategies, but MIXED runs a quarter-budget heuristic phase before traversal and the request does not forward explicit parallelism. | [#2791](https://github.com/Eignex/klause/issues/2791) tracks integration with the shared portfolio invocation. |
| Independent proof export | Exact numerical checks and supported source derivations are in memory. | [Proof-file support](#independently-checkable-proof-files) and a separate checker for entire original-source claims remain target capabilities. |

## Shared model breadth

Klause's required model surface is shared across search, local search,
large-neighbourhood search and portfolios. Every supported problem must have both
a runnable complete-search arm and a runnable local-search arm, and callers must
always be able to compose the two into a portfolio over that problem. Both arms
retain the same source constraints, variable meanings and objective. This applies
to finite, open, continuous and mixed models, including pure theory problems.
Single-arm execution remains an explicit caller choice.

Strategies can use native projections or composed theory services. Each arm must
execute meaningful search over the admitted model: dropping constraints, rejecting
the model or immediately returning unsupported UNKNOWN is a coverage gap, not
engine parity. Shared breadth does not require equal filtering, scoring,
performance or proof authority. The implementation gaps in
[current coverage](formats.md#solving-coverage) are work to close; they do not define
permanent exclusions from the portfolio requirement.

## Results and completeness

The complete-search arm is the portfolio's source of certified refutations, bounds
and optimum claims. That role is distinct from mathematical completeness: an
incomplete arithmetic procedure can produce sound proofs for individual instances,
but no algorithm can guarantee a terminating exact decision for every input in an
undecidable family. Any guarantee of eventual decision must name the fragment and
procedure. Local search contributes checked witnesses and incumbents; unsuccessful
local search is never a refutation.

Support requires defined source semantics and sound result publication.
Mathematical decidability is not an admission requirement. An incomplete procedure
can publish a checked witness or a completed sound refutation; failure to find a
witness does not establish infeasibility. Completeness belongs to a procedure and
its documented fragment, not to a factor, input format or engine name. Even a
complete procedure can stop without a verdict under time, work or representation
limits. Unsupported syntax or execution remains distinct from resource exhaustion.

| Claim | Required authority |
|---|---|
| SAT or feasible incumbent | A complete assignment checked against the source under its declared semantics |
| UNSAT or infeasible | A completed sound refutation covering the source scope |
| Optimal | A checked feasible assignment and a matching bound or exhaustive proof establishing an attained optimum |
| Unbounded | A source-valid proof of unlimited objective improvement; a feasible point alone is insufficient |
| Best found | A checked feasible incumbent without an optimum proof |
| UNKNOWN | No definitive claim; retain the unsupported, cancellation or resource cause |

Exact publication cannot be justified by a zero heuristic score, a relaxation
candidate or satisfaction of an approximated model. Approximation and configured
tolerance have the separate source contracts in [input semantics](formats.md).
The [portfolio contract](solver/portfolio.md#witnesses-and-proof-scope) owns witness
and proof admission; [resource allowances](solver/resources.md) own interruption.

## Finite propagation boundary

Finite propagation owns Boolean and finite integer domains only. Real values and
open integer state belong to arithmetic components. Shared search owns traversal,
decisions, retraction and model assembly across those owners. Continuous support
does not place real domains in CP. See [shared component search](solver/search.md#shared-component-search).

## Independently checkable proof files

The vision includes optional proof files that let a separate checker verify
Klause's published claims without trusting the search implementation. The target
is proof coverage across the supported model surface: feasible assignments,
refutations, objective bounds, attained optima and unboundedness evidence under
the declared source semantics. Proof production does not require a terminating
decision procedure for every admitted family. A timed-out run can retain a checked
incumbent and a certified bound without claiming optimality or infeasibility.

Proofs must connect back to the original source model and invocation assumptions.
Lowering, presolve, reconstruction, propagation, learned clauses and imported
bounds must have checkable justifications wherever they contribute to the claim.
Checking an exported encoding alone leaves its translation trusted; that trust
boundary must be explicit. Exact and tolerance-based claims remain distinct.

Proof production composes with the required complete-search/local-search
portfolio. Local search supplies checkable solutions; the proof-producing arm
supplies justified deductions, bounds and refutations. Heuristic moves need not be
logged. Shared incumbents and deductions used in a proof must retain their
justification and source scope regardless of which worker produced them.

One user-facing verification contract need not imply one proof format. Finite
Boolean/integer models can use pseudo-Boolean encodings and deduction proofs;
linear arithmetic needs exact numerical certificates and justified search steps;
polynomial real arithmetic needs checkable algebraic evidence and exhaustive case
coverage. Proof export must report unsupported coverage or incomplete evidence
explicitly rather than claim independent verification.

This is a target capability, not implemented proof-file support.
[Portfolio certificates](solver/portfolio.md#witnesses-and-proof-scope) retain
engine admission, [LP certification](lp/certification.md) checks exact numerical
evidence, and [source derivations](solver/evidence.md) check their supported claims
against original premises in memory. Unsupported derivation coverage remains
explicitly unavailable. These contracts do not export an independently checkable
proof of the entire source solve.
