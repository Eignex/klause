# 6. Sessions and resources

Part II described the source model and the frontends that produce it. This part
follows a solve of that model from the caller's request to a published result,
starting with scope: which assumptions apply, which operation is live, and which
state survives between calls.

A *session* groups operations under caller assumptions. A *stream* or *resumable
handle* keeps one operation alive across consumption or across time slices. The
session owns those scopes and prevents overlapping operations from mutating active
state. The caller opens a session, establishes assumptions, starts an operation,
consumes or resumes it, and closes its owner before changing scope. Published
results remain readable after the live operation ends.

This chapter defines capture, exclusion and cleanup, then the
[resource allowances](#resource-allowances) attached to those scopes. The
traversal and heuristic state an operation retains is described with
[search](08-search.md) and [local search](10-local-search.md).

## Assumptions and conditioned queries

`Solver.session()` opens a single-threaded scope owner. Every operation captures the
assumption stack when called, including lazy sequences. Later pushes override earlier
pins and per-call parameters; popping restores the previous scope. The source model
is unchanged. Parameters that cannot enforce assumptions reject non-empty pins
explicitly, as do custom sessions for scoped operations they cannot implement.

Optional native views, such as definition orientations, are requested only after
heuristic admission and presolve, under the same cancellation and work meter. An
interrupted construction publishes no partial view, and reusing a source attachment
in a fresh invocation creates a fresh execution scope.

## Streams and exclusive ownership

`openSamples`, `openEnumerate`, `openExactCount` and `openImprovements` return
`SearchStream` cursors. A cursor has one consumer and releases its state on
completion, failure or explicit close; use `use` when stopping early. Legacy
`Sequence` APIs capture the same scope but require sequential consumption.

An open stream or resumable handle owns its session: other operations, push/pop and
local-search reset throw while it is active. Closing the session closes its active
owner, clears scopes and retained local-search weights, and rejects later operations.
Terminal resumable verdicts stay readable after close; a closed pending handle
rejects further slices.

Finite solve, sample and optimization results keep the model's captured source
interpretation, including cancellation and refutation without an assignment. This
metadata adds no proof authority. Resumable handles keep cancellation bound through
`withLifetimeCancellation`, while each slice supplies its own invocation token; see
[resource allowances](#resource-allowances) and
[ALNS acquisition](11-neighborhoods.md#alns-incumbent-acquisition) for how wrapped
handles keep their instruction charges.

## Retained state and repair

Fresh calls build fresh traversals. Resumable slices keep their trail or assignment,
RNG and restart progress. Local-search sessions keep learned weights between fresh
calls, capturing them at each published sample or incumbent. Open local-search
streams also exclude searches through other sessions of the same solver, because
strategy and restart state belong to the solver.

Repair handles are a separate internal contract. Their cutoff must be monotone
non-increasing because retained objective-bound clauses survive fragment reseeding;
session push/pop always starts a fresh search.

## Counting and sampling

Counting builds an isolated conditioned model: integer domains carry the scope's
pins, holes and bounds, and Boolean pins become unit clauses so hash extensions and
rebuilds preserve them. Exact, approximate and hybrid counting and accurate sampling
all use complete finite-model machinery on that model, independently of the
session's sampling backend.

Count bounds keep the captured source interpretation, separately from the
algorithm's confidence: an exact count of a bounded interpretation does not count an
unrestricted source, and combining bounds requires matching policies.

Approximate counting and accurate sampling project only Boolean and finite integer
values. Real columns stay existential: neither a real interval nor multiple real
completions multiply the count. Cell exhaustion requires complete feasibility
checks, so an indeterminate real leaf cannot give an exact count. Polynomial
algebraic assignments belong to the [polynomial pipeline](16-nonlinear-arithmetic.md#polynomial-real-arithmetic),
outside these entry points.

## Exact count bounds

Anytime exact counting reports proven projected assignments as its lower bound and
adds all unchecked or undecided subtree mass to its upper bound. Exhausting
`maxChecks` partway through an expansion keeps the unchecked siblings in the upper
bound, and an `Unknown` check keeps its whole subtree there. Subtree arithmetic is
exact, and integer values are visited lazily, so a bounded budget never materializes
a whole domain. `Count.upper == Long.MAX_VALUE` is an unbounded sentinel, not an
exact count; exactness requires every projected branch decided and a representable
count.

## Theory invocation scopes

Arithmetic theories (Part IV) follow the same ownership rule as finite search:
immutable source snapshots are shared, while each solve owns a fresh invocation. The
native result and reconstructed source outcome stay in that call's completed
envelope. Published exact scalars outlive the invocation; its traversal, query
results and work ledger stay private. Conditioned queries capture their pins without
changing the source model.

A mutable `SearchComponentSet` has one session construction attempt. Prepared search
openings instead support independent openings with fresh components and their own
resource registry. Every closeable component and scratch resource joins that
registry, and completion, close or partial failure release registrations once in
reverse order, with cleanup failures suppressed onto a construction failure. Callers
using resource-owning extensions must close abandoned streams.

Conditioned linear queries keep one private prepared search and work ledger. Each
query captures Boolean pins, exact coordinate bounds and a strict objective cutoff,
retracted afterwards with its deadline and meter. The traversal keeps its opening
decision level through restarts and backjumps, so exhaustion refutes only the active
condition, never the source; see
[search](08-search.md#shared-component-search) and
[resources](#native-arithmetic-allowances). Private auxiliary names
never reach a published assignment. Polynomial and nonlinear integer invocations
follow these rules; see
[polynomial invocation ownership](16-nonlinear-arithmetic.md#invocation-ownership) and
[nonlinear integer arithmetic](16-nonlinear-arithmetic.md).

## Resource allowances

Every session operation runs under a containing deadline and work limits.
Preparation phases, workers, resumable slices and arithmetic services each consume
part of that allowance in their own units. This section describes how those units
are configured, how they compose when one scope runs inside another, and what an
exhausted allowance may and may not mean. Every later chapter in this part spends
from the ledgers introduced here.

The governing rule is that the ledger stays with its scope. Resuming a handle,
reseeding a worker or rebuilding a model does not renew the containing allowance,
so a restart or nested repair cannot silently receive a fresh solve budget.
Completed atomic operations can overspend; their work stays charged and the next
polling boundary stops optional work. An exhausted resource allowance never
establishes that the search space was exhausted.

### Configuration and lifetime

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
[presolve](07-presolve.md#phase-allowance-and-telemetry).

### Units and boundaries

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

### Decisions, moves and slices

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
[core search](12-portfolio.md#boolean-objective-core-search).

### Preparation progress

Long preparations retain a private cursor across invocation expiry, so a cancelled
invocation pauses rather than restarts them. Local-search initialization (buffers,
seeding, definitions) and general CP optimization construction (indexes, state
fills, assumption propagation, LP installation) checkpoint in fixed batches and
spend wall time without decision/move charges. Closing discards the cursor.
Individual allocations and factor calls stay atomic and may overrun; see
[initialization](10-local-search.md#projections-and-preparation) and
[private CP preparation](12-portfolio.md#private-cp-preparation).

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

### Component execution controls

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

### Parallel cap

Built-in finite portfolios with more than one lane split the remaining allowance
equally over every materialized worker, remainder in worker order; sequential
portfolios share one allowance. Shared arithmetic invocations split the
decision/move allowance over their parallel slots the same way. Linear portfolios
give a complete worker three quarters when heuristic siblings are present.

Quotas persist across reopens and reseeds and are not redistributed when a worker
finishes or fails, so a quota can stop a worker while other quota remains unused.
Atomic overspend stays visible in the aggregate counter and can exhaust the whole
cap. Parallel scheduling and stopping order are not reproducible.

### Native arithmetic allowances

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
[difference logic](14-theories.md#state-and-resources-1),
[linear real](15-linear-arithmetic.md#state-and-resources),
[nonlinear integer](16-nonlinear-arithmetic.md#state-and-resources),
[polynomial real](16-nonlinear-arithmetic.md#state-and-resources-1) and
[transcendental](16-nonlinear-arithmetic.md#state-and-resources-2), with shared rules in
[theory services](14-theories.md#state-and-resources).
[LP certification](17-linear-programming.md#lp-certification) defines what survives an interrupted LP solve.

### Mechanism and compatibility

`solver.resources.NodeAllowance` owns the synchronized decision/move ledger, native
instruction conversion and quota partitioning. `backtrack.NodeBudget` is the
published facade; each facade forwards to one allowance without copying counters.
`Cancellation` and `WorkMeter` live in `util` and contain no portfolio pricing; units
come from the caller's adapter, and solver policy owns conversions and result
semantics.

Polynomial native polls read the shared allowance, worker quota and representation
stops together under their ledger lock, then inspect containing predicates outside
the lock. Encoder preparation latches an observed stop for that capture while
forwarding live deadlines and work meters to its parent.

---

Previous: [5. MPS and SMT-LIB](05-arithmetic-formats.md) · [Contents](README.md) · Next: [7. Presolve and validation](07-presolve.md)
