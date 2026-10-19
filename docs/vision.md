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
| Source evidence | [Typed source-scoped evidence](solver/evidence.md) retains original premises, actual invocation and objective binding, exact claims and optional result/transformation capture. Supported derivations are checked separately from native admission; unsupported or incomplete coverage remains unavailable. |

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
| Factor retention and composed reasoning | Frontends can decompose relations before mechanism selection; theory assembly and routing have specialized admission paths. | [Encoder foundation #2850](https://github.com/Eignex/klause/issues/2850) and [format migrations #2841](https://github.com/Eignex/klause/issues/2841) track implementation of the [factor and ownership requirements](#high-level-factors-and-composed-reasoning). |
| Independent proof export | Exact numerical checks and supported source derivations are in memory. | [Proof-file support](#independently-checkable-proof-files) and a separate checker for entire original-source claims remain target capabilities. |
| Format semantics and automatic setup | Formats declare [typed source semantics](model.md#typed-source-semantics) that caller overrides replace before lowering. [Source setup](api.md#source-setup) selects and dispatches the route for DIMACS, WCNF, OPB, XCSP3, FlatZinc, SMT-LIB and source strategies. | MPS and legacy typed compatibility entries retain their own execution surfaces, and SMT-LIB admission still chooses its source representation, short of [one automatic setup](#format-semantics-caller-settings-and-automatic-setup) for every entry point. [Epic #3043](https://github.com/Eignex/klause/issues/3043) tracks the remainder. |

## Format semantics, caller settings and automatic setup

The path from a source to a running solver has one seam: a frontend produces a
source model with declared semantics, and the library selects how to solve it.
Three layers determine the effective setup, applied in order.

1. **Format semantics.** Each format declares the defaults its syntax implies as
   explicit, typed data carried with the source model. Examples are numeric
   authority (exact rationals for SMT-LIB; decimal rows checked under a published
   tolerance for MPS; binary64 floats with a bounded grid for unranged FlatZinc
   floats), the reading of undeclared ranges, and absent optional values. These defaults
   describe the declared solution set and result qualification. They select no
   engine, theory, route or solver-motivated lowering.
2. **Caller settings.** Library parameters and CLI flags override individual
   semantic defaults. An override names the semantic it changes, means the same
   thing for every format with that semantic, and is rejected where it has no
   meaning instead of being silently ignored. Results carry the qualification of
   the effective semantics, so an override that approximates or bounds the source
   cannot be published as an exact source claim.
3. **Automatic setup.** One library entry receives the source model, its effective
   semantics, the requested operation and the caller's strategy and resource
   settings. From the retained factors and their capabilities it selects the route,
   theory composition, engines and portfolio: the strongest available complete
   mechanism, paired with local search as [shared breadth](#shared-model-breadth)
   requires. An explicit engine or arm request narrows that selection without
   bypassing admission. The setup owns fallbacks, such as heuristic search where no
   complete procedure admits a fragment, and the authority rules of
   [results and completeness](#results-and-completeness).

Frontends parse, lower to the [strongest factors](#high-level-factors-and-composed-reasoning),
attach semantics and render results. They do not prove bounds, choose between
lowerings by solver capability, build theory-specific requests or interpret engine
names. The CLI and library callers reach the same setup with the same inputs, so a
model receives the same route and portfolio whichever entry point submits it.

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

## High-level factors and composed reasoning

These target requirements are tracked by [#2850](https://github.com/Eignex/klause/issues/2850)
and [#2841](https://github.com/Eignex/klause/issues/2841). They are not fully implemented.
[Model preparation](model.md#engine-projections-and-factor-capabilities) and
[theory composition](theories.md#shared-composition-contract) describe current execution.

Frontends retain the strongest source factor available. Preparation selects its
propagation, scoring, relaxation and theory mechanisms. An `Encoder` installs the
factor's relation into a selected theory using direct support or a source-equivalent
encoding. Generic equivalent encodings belong in `lowering`; private auxiliaries
retain source mappings. Existing frontend decompositions must migrate to these
consumer views across every applicable format.

Variable ownership is separate from factor participation. Finite propagation owns
Boolean and finite integer domains and their choices; arithmetic owns open integer
and continuous state. A factor can use a `Propagator` and an `Encoder` together,
alongside an `Invariant` and relaxation views. Joint linear reasoning can complement
finite bound propagation, and finite globals can remain native beside real completion.
Theories consuming finite variables preserve integrality, bounds and domain holes.
Participating components must agree on the authoritative source assignment.

An arithmetic relation or exact source coefficient does not by itself transfer
unrelated finite variables or factors to theory search. Exact source authority,
numerical proposal generation and execution ownership remain separate decisions.
Selective encoding applies where a selected consumer needs a factor's relation;
finite factors retain their other useful mechanisms.

Providers distinguish direct support, equivalent lowering, conditioned support,
relaxation-only support and unsupported relations. Conditions such as a fixed integer
operand retain their premises and retract with shared search. Live arithmetic systems
belong to shared `SearchComponent` instances; encoders retain prepared source data.
Shared deductions carry reasons and follow the same assertion/retraction lifecycle.

Every source factor must be enforced under its declared semantics before a solution
is published. Relaxations and heuristic scores cannot replace source checks. Proof
claims require coverage of the original source and active conditions, including
agreement between participating components; unresolved required checks withhold them.

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
