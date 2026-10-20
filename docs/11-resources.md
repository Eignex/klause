# 11. Resource allowances

Every operation from [chapter 10](10-sessions.md) runs under a containing deadline
and work limits. Preparation phases, workers, resumable slices and arithmetic
services each consume part of that allowance in their own units. This chapter
describes how those units are configured, how they compose when one scope runs
inside another, and what an exhausted allowance may and may not mean. Every later
chapter in this part spends from the ledgers introduced here.

The governing rule is that the ledger stays with its scope. Resuming a handle,
reseeding a worker or rebuilding a model does not renew the containing allowance,
so a restart or nested repair cannot silently receive a fresh solve budget.
Completed atomic operations can overspend; their work stays charged and the next
polling boundary stops optional work. An exhausted resource allowance never
establishes that the search space was exhausted.

## Configuration and lifetime

`ExecutionOptions` distinguishes `auto`, `unlimited` and a nonnegative remaining
amount; omission selects `auto`. For `node-limit`, automatic and unlimited both mean
no solve work cap and `0` is an exhausted `NodeBudget`. For `presolve-work-limit`,
automatic derives work from the preparation time policy, unlimited removes the cap
and `0` stops optional preparation; the preparation clock and solve deadline still
apply. The legacy nonpositive `klause.presolve.budget.ms` disables the derived cap
and its clock.

The common `SourceExecutionRequest` binding resolves the ledger once, reusing a
caller-supplied ledger, and rejects conflicting declarations. `open-work-limit`
bounds open arithmetic work without changing the shared decision/move allowance.
Native arithmetic controls keep their own units and defaults. One resolved seed,
default zero, reaches every selected strategy.

`withCancellation` replaces the invocation token while retaining a separately bound
lifetime scope; `withLifetimeCancellation` adds a persistent token that bounds
construction and every later invocation or slice. Each retained slice supplies its
own invocation token, and scopes keep their earliest deadline without retaining an
old slice deadline.

Source setup charges objective inspection, bound closure, integral real-column
recognition and linear preparation to the containing invocation. An arithmetic
preparation cap applies only once the selected completion requires that arithmetic.
An interrupted capture exports no partial projection, and heuristic fallback reuses
the captured plan and ledger. CLI routing holds a scoped view of the shared presolve
meter and releases it before native execution; see
[presolve](12-presolve.md#phase-allowance-and-telemetry).

## Units and boundaries

| Allowance | Unit and owner | Stop surface |
|---|---|---|
| CLI `-t` | Milliseconds, one deadline anchored before loading | Cooperative cancellation; mandatory work can overrun |
| `NodeBudget` | CP decisions plus floor(5 × LS instructions / 4), per solve or worker quota | Exhaustion; completed evidence remains valid |
| `BacktrackParams.maxDecisions` / `maxInstructions` | Decision attempts and native traversal operations | Incomplete result, never exhaustion |
| `SliceBudget` | Search nodes plus policy-priced LP, propagation and inprocessing | Pause |
| Portfolio base, maximum, growth | Shared schedule in node equivalents | Work or time pause |
| LS `maxFlips` / `maxInstructions` | Native instructions per operation or handle | Exhaustion without proof |
| ALNS instruction allowance | Outer repair allowance per handle | Anytime result |
| `PresolveBudget` | Phase work across routing, schedules, probes and rebuilds | Completed reductions retained |
| LP work and iteration limits | LP work proxy and pivots per solve | Certified bounds may survive; no optimum proof |
| Open-theory limits | Policy work, leaves and decisions per theory scope | Unknown; completed proofs only |
| Native arithmetic controls | Per-invocation polynomial, integer or function work | Unknown or a completed checked result |

Missing or `Long.MAX_VALUE` caps mean unlimited; zero prevents entry or optional work.

## Decisions, moves and slices

The solve-wide decision/move currency excludes propagation and LP work. LS
instructions, CP repair decisions, open traversal and retained linear queries charge
the same containing `NodeBudget`, and rebuilding a traversal does not renew it. A
root witness or refutation that needs no decision survives an exhausted allowance.
Private clausal primal search charges the ledger too, but is not counted in the
arm's `nodes` and `lsMoves`, so those counters alone cannot reconstruct the charge.
ALNS charges each outer iteration's declared flip allowance once. CLI statistics
expose `nodeBudgetLimit`, `nodeBudgetSpent` and `nodeBudgetRemaining`, including
overspend.

Slice pricing is solver policy: LP work costs one node per 600 units, propagation
one per 4000 visits, inprocessing probes one per 12 and subsumption visits one per
18000. Fractional marks survive slices and the preparation-to-search transition.
Overspend debt is settled once and repaid by later slices; a nested private search
leaves debt with its containing budget. Private LP rounding, pumps and proposal
verification charge the same currency, including rejected proposals. Retained
finite optimization behind an external cutoff converts remaining slice work into an
LP operation cap. Saturating arithmetic prevents large allowances from wrapping.

Core-guided encoding and baking use the propagation currency and spend no
decisions. An automatically deferred single-lane core worker has one base slice per
turn and two seconds of cumulative active time; see
[core search](16.3-portfolio-recipes.md#boolean-objective-core-search).

## Preparation progress

Long preparations retain a private cursor across invocation expiry, so a cancelled
invocation pauses rather than restarts them. Local-search initialization (buffers,
seeding, definitions) and general CP optimization construction (indexes, state
fills, assumption propagation, LP installation) checkpoint in fixed batches and
spend wall time without decision/move charges. Closing discards the cursor.
Individual allocations and factor calls stay atomic and may overrun; see
[initialization](15.2-preparation.md#projections-and-preparation) and
[private CP preparation](16.2-portfolio-scheduling.md#private-cp-preparation).

Optional finite real-difference preparation charges its visits to the solve token
under a cumulative 65,536-visit limit and a 10,000-column envelope; cancellation or
decline discards the whole prefix without refund.

Encoder construction charges row, term, factor and installer visits to the
invocation meter. Required post-presolve installation uses the containing token
while optional source passes keep presolve limits, so exhausting the optional
allowance cannot retire a complete worker before installation. Interrupted
construction discards emitted relations and keeps its charges; a preparation
decline is not a refutation. Completed immutable relations can be reused across
openings for one consultation unit. Live assertions, query results and native
resources are never shared.

## Component execution controls

`SearchContext.executionControls` supplies the invocation token with its deadline
and meter, native check/decision accounting and policy-owned operation allowances,
bound by `SearchExecutionControlAdapter` before component initialization. The
open-theory pipeline supplies its `OpenTheoryWorkSink`; shared search does not price
native work. The containing sink survives branches, retractions, restarts and
retained queries.

`SearchStopCause` separates timeout, cancellation, native work, checks, decisions,
operation allowance and representation decline. A local operation stop clears when
the next operation begins, without renewing containing allowances. Timeout takes
precedence over allowances, which take precedence over external cancellation and
local decline. Open routing maps representation decline to `Unsupported` and
operation, check, decision and work stops to `BudgetExhausted`. Slice pauses are
lifecycle events, never refutations.

Tight loops without an attached meter poll cancellation at most every 64 primitive
visits; an attached meter polls after every debit. Entry, bulk allocation, result
delivery and nonpublication exits force a poll. Batching grants no work, and a
stopped query publishes no partial fact, point or conflict. Custom contexts default
to cancellation only.

## Parallel cap

Built-in finite portfolios with more than one lane split the remaining allowance
equally over every materialized worker, remainder in worker order; sequential
portfolios share one allowance. Shared arithmetic invocations split the
decision/move allowance over their parallel slots the same way. Linear portfolios
give a complete worker three quarters when heuristic siblings are present.

Quotas persist across reopens and reseeds and are not redistributed when a worker
finishes or fails, so a quota can stop a worker while other quota remains unused.
Atomic overspend stays visible in the aggregate counter and can exhaust the whole
cap. Parallel scheduling and stopping order are not reproducible.

## Native arithmetic allowances

Arithmetic services keep their own work currencies inside the containing solve. A
conditioned check receives only its invocation's remaining allowance; branches,
retractions and retries do not renew it. Native work is forwarded once to the caller
meter and is not charged again as decisions; exhaustion withholds an incomplete
verdict. Parallel arithmetic workers keep their quota by slot identity, so reopening
a slot cannot renew it. A representation decline stops one worker; containing stops
and cancellation stop all.

Optional guidance, such as exact-linear polarity hints and gate recognition, reuses a
completed check and charges its index construction to that check's token under a
65,536-entry boundary. A private limit discards the hint; required work keeps its own
limits. Native source setup prepays coordinate capture before building an immutable
plan, and a fresh execution request cannot renew stopped preparation.

Each theory chapter defines its own units and caps:
[difference logic](19-difference.md#state-and-resources),
[linear real](20-linear-real.md#state-and-resources),
[nonlinear integer](23-nonlinear-integer.md#state-and-resources),
[polynomial real](24-polynomial-real.md#state-and-resources) and
[transcendental](25-transcendental.md#state-and-resources), with shared rules in
[theory services](18-theories.md#state-and-resources).
[LP certification](27-lp-certification.md) defines what survives an interrupted LP solve.

## Mechanism and compatibility

`solver.resources.NodeAllowance` owns the synchronized decision/move ledger, native
instruction conversion and quota partitioning. `backtrack.NodeBudget` is the
published facade; each facade forwards to one allowance without copying counters.
`Cancellation` and `WorkMeter` live in `util` and contain no portfolio pricing; units
come from the caller's adapter, and solver policy owns conversions and result
semantics.

---

Previous: [10. Sessions and streams](10-sessions.md) · [Contents](README.md) · Next: [12. Presolve and reconstruction](12-presolve.md)
