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

`ExecutionOptions` captures `auto`, `unlimited` and a nonnegative remaining amount as distinct
settings. Omission selects `auto`. For `node-limit`, automatic and unlimited both resolve
to no solve work cap; `0` resolves to an exhausted `NodeBudget`. For
`presolve-work-limit`, automatic derives work from the preparation time policy,
unlimited removes the work cap and `0` stops optional preparation. The preparation
clock and whole-solve deadline still apply. The legacy nonpositive
`klause.presolve.budget.ms` disables the derived preparation work cap and its clock;
an explicit work setting can still supply an exhausted or finite work allowance.

The common `SourceExecutionRequest` option binding resolves the ledger once, using
the caller's existing ledger when supplied. Conflicting resource declarations reject.
`open-work-limit` bounds open arithmetic policy work without changing the shared
decision/move allowance. Native arithmetic controls (`nra-work-limit`, coefficient
and matrix caps, integer candidate and move limits) keep their own units and
parameter defaults. One resolved seed, default zero, reaches every selected
strategy. Captured CLI source execution delegates this binding to the library.

Built-in `withCancellation` adapters replace the invocation token while retaining any
separately bound lifetime scope. `withLifetimeCancellation` composes a persistent token
that bounds construction and every later invocation or retained slice. Ordinary
parameter cancellation bounds one-shot execution and retained-handle construction;
each retained slice supplies its own invocation token. These scopes keep their
earliest deadline without retaining an old slice deadline. Portfolio construction
and the first search segment share the offered segment deadline. ALNS bootstrap
construction uses its shortened phase token; later bootstrap slices retain the
containing lifetime scope and rearm their invocation clock.

Source setup charges objective interval inspection, bound closure, integral
real-column recognition and linear preparation to the containing invocation. An
arithmetic preparation cap applies only after the selected completion requires that
arithmetic, so a finite completion proved available by closure does not consume an
unused polynomial phase allowance. An interrupted capture exports no partial
projection, and heuristic fallback reuses the captured source plan and ledger
without another routing allowance.

CLI routing preparation owns a scoped view of the shared presolve meter. Required
admission keeps charging it after optional probing exhausts its slice. Source
conversion and setup release that meter on every exit before retained native execution;
the containing deadline and cancellation stay live. Native operations use their
invocation meters and decision/move ledgers. A released preparation meter cannot
force native arithmetic into work-metered polling. See [presolve](12-presolve.md#phase-allowance-and-telemetry)
for phase ownership.

## Units and boundaries

| Allowance | Unit and owner | Unlimited / zero | Stop surface |
|---|---|---|---|
| CLI `-t` | Milliseconds, one deadline anchored before loading | Omitted deadline / immediate deadline | Cooperative solve cancellation; mandatory work can overrun |
| `NodeBudget` | Solve or worker quota: CP decisions plus floor(5 × counted LS instructions / 4) | Missing budget / exhausted budget | Budget exhaustion; completed evidence remains valid |
| `BacktrackParams.maxDecisions` and `maxInstructions` | Decision attempts, native traversal operation | `Long.MAX_VALUE` or missing instruction cap / no decisions | Incomplete result, never a proof of exhaustion |
| `SliceBudget` | Retained handle: search nodes plus policy-priced LP, propagation and inprocessing | Negative slice work / no new scheduled work | Pause; completed constructor or terminal results can return |
| Portfolio base, maximum and growth | Shared schedule, node equivalents | Dedicated lanes offer unbounded work / zero base or maximum rejected | Work or time pause |
| LS `maxFlips` and `maxInstructions` | Native instructions, operation or retained handle; sample streams renew their per-sample flip cap | `Long.MAX_VALUE` or missing instruction cap / no moves | Budget exhaustion without proof |
| ALNS instruction allowance | Declared outer repair allowance, retained ALNS handle | Missing cap / no outer iterations | Anytime result without proof |
| `PresolveBudget` | Phase work across routing, source/finite schedules, probes, rebuilds and repairs | Missing meter / no optional work | Completed sound reductions retained |
| Presolve rounds and probe caps | Native schedule rounds or probe calls, local schedule/tier/bake | Emphasis defaults; no unlimited sentinel / skip relevant work | Forgo reductions; aggregate telemetry can exceed one local cap |
| LP work and iteration limits | Native LP work proxy and pivots, individual solve or split component | Legacy zero delegates to the engine / an exhausted caller token prevents entry | Incomplete solve may retain certified bounds; no optimum proof |
| Open-theory limits | Open policy work, leaves and decisions, retained complete theory scope | `Long.MAX_VALUE` / exhausted scope | Unknown budget exhaustion; completed proofs only |
| `QfNraParams.maximumWork` | Native normalization, CAD and Boolean work, one invocation | Null / zero prevents entry | Unknown before verdict publication |
| Nonlinear integer candidate/move controls | Exact candidate attempts and heuristic moves, one pipeline invocation | Long maximum / zero prevents that stage | UNKNOWN; candidate exhaustion supplies no proof |
| `PolynomialTheoryParams.maximumWork` for quadratic execution | One invocation: counted polynomial work plus numerical proposals and exact certification | Null / zero prevents entry | Incomplete result can retain a checked incumbent or independent global bound |
| Transcendental native arithmetic | One invocation: enclosure, algebraic, polynomial relaxation and original-source checks | Null / zero prevents entry | UNKNOWN or a completed checked source result; no renewed allowance on branches or repairs |
| Neutral kernel controls | Caller-owned native work, optional maximum and cancellation | Missing maximum / zero maximum is exhausted | Kernel interruption without solver result semantics |

## Decisions, moves and slices

The solve-wide decision/move currency excludes propagation and LP work. Ordinary LS
instructions and CP repair decisions charge the same containing `NodeBudget`. The
complete open traversal, retained linear queries and numerical source traversal charge
committed decisions to that same ledger. Remaining decision capacity is read again for
each feasibility round; rebuilding a traversal does not renew it. A root witness or
refutation that needs no decision can survive an exhausted node allowance. Source
strategies bind one supplied ledger across their finite and arithmetic projections.

The private clausal primal search and walk also charge that ledger. Their decisions
and walk instructions are not included in the enclosing arm's `nodes` and `lsMoves`
counters, so those exported counters alone cannot reconstruct the solve charge. ALNS
charges each outer iteration's declared flip allowance once; the inner LS repair is
not billed again for its moves, while CP repair decisions remain additional native
work. CLI statistics expose `nodeBudgetLimit`, `nodeBudgetSpent` and
`nodeBudgetRemaining` from the containing ledger, including cooperative overspend.
Scheduler coverage for finite ALNS segments uses the offered segment allowance,
distinct from actual inner work; `alnsOuterAllowance` and the native bootstrap and
repair counters retain that distinction.

Slice pricing belongs to solver policy: LP work costs one node per 600 units,
propagation work one per 4000 visits, inprocessing probes one per 12 and subsumption
visits one per 18000. Fractional LP and propagation marks survive slices and the
preparation-to-search transition. Overspend debt is settled once and repaid by later
slices. A nested private search leaves debt ownership with its containing budget.
Private LP rounding, pump and proposal-verification propagation charge the same
slice currency, including rejected proposals. Root primal queries retain the
remaining cumulative root LP allowance, and each completed attempt charges it before
a fallback can open another solver. Retained finite optimization behind an external
cutoff converts the slice's remaining work into an optional node-LP operation cap;
the model-facing LP owner takes the smaller of that cap and its containing effort
allowance. Relative counters and saturating additions prevent a large allowance
from wrapping.

Core-guided private encoding and augmented-model baking use the propagation visit
currency and spend no solve-wide decisions or moves. The containing core handle
settles query overspend once and retains fractional charges across rounds. An
automatically deferred single-lane core worker has one base work slice per turn and
two seconds of cumulative active time; work pauses retain completed fixpoints, while
propagation interruptions discard the partial traversal. Expiry keeps completed
checked floors and returns a budget stop. See [core search](16.3-portfolio-recipes.md#boolean-objective-core-search).

## Preparation progress

Local-search initial preparation retains its progress between cancelled invocations.
Buffer allocation, assignment seeding and definition preparation spend wall time without
search instructions; resuming them neither renews nor charges the containing move
allowance. Only committed walk/restart instructions advance that ledger. Invocation
expiry leaves private preparation paused; closing discards its cursor. Individual
runtime allocations and factor calls remain atomic and may overrun the deadline; see
[initialization boundaries](15.2-preparation.md#projections-and-preparation).

General CP optimization construction retains the same private cursor across invocation
expiry. Index construction, state fills, assumption propagation and model-facing LP/search
installation checkpoint in fixed batches. Construction scans and primitive allocations
spend wall time without decision/move charges. Completed propagation visits retain their
existing slice pricing; final installation transfers fractional marks and debt once.
See [private CP preparation](16.2-portfolio-scheduling.md#private-cp-preparation).

Optional finite real-difference preparation charges factor, term, table-entry,
denominator and edge visits to the containing solve token, under one cumulative
65,536-visit limit and the 10,000 real-column envelope. A cancellation or
representation decline discards the whole prefix without refund. The completed
immutable factor retains no preparation token; each receiving finite state uses the
existing incremental difference propagator and finite propagation currency.

Encoder construction observes the containing invocation token during source
routing, component preparation and arithmetic assembly, charging row, term, factor
and installer visits to its meter. Required post-presolve installation uses the
containing invocation token, while optional source passes keep the presolve phase's
limits, so spending the optional allowance cannot retire a complete worker before its
required installation. Interrupted construction discards every emitted relation and
private definition and retains its charges; a preparation decline is not a
refutation. Completed immutable relations, such as the exact-linear source factory,
can be reused across openings without retaining the construction token; their reuse
charges one consultation unit and never repeats or refunds construction charges.
Live assertions, query results and native resources are never shared.

## Component execution controls

`SearchContext.executionControls` supplies the invocation token with its deadline
and caller meter, native check/decision accounting, and policy-owned operation
allowances. `SearchExecutionControlAdapter` binds those services without exposing
a concrete coordinator to components. The open-theory pipeline supplies its
`OpenTheoryWorkSink` through an accounting adapter; shared search does not price
or convert native work. Binding happens before component initialization. The
containing sink survives branches, retractions, restarts and retained queries.

`SearchStopCause` separates timeout, cancellation, native work, checks, decisions,
operation allowance and representation decline. A component reports a local
operation stop through its context; beginning another operation clears that local
cause without renewing containing allowances. The traversal retains the cause of
unresolved branches if subsequent operations clear their local diagnostics.
Timeout and cancellation remain classified from the active token, so a resumed
slice does not retain a previous invocation stop as lifetime cancellation.
Containing native allowances and cancellation take precedence over a local
decline. Timeout takes precedence over allowances, which take precedence over
external cancellation. A fresh traversal clears its per-run decision diagnostic;
its native accounting retains the containing allowances. An incomplete residual remains
unknown and cannot prove exhaustion. Open routing maps representation decline to
`Unsupported`, operation/check/decision/work stops to `BudgetExhausted`, and keeps
timeout and cancellation distinct. Slice pauses remain traversal lifecycle events;
they do not renew lifetime controls or become refutations.

Components that poll in tight loops batch unmetered cancellation polls: difference
guard propagation, the exact-linear component and original-linear point evaluation
poll at most every 64 primitive visits when the token has no attached meter, while
an attached meter retains an eager poll after every debit. Entry, bulk allocation,
result delivery and nonpublication exits force a containing poll. Batching grants no
work; every visit keeps its charge. An observed stop is latched only for that
operation, and a stopped query publishes no partial fact, point or conflict.

Custom contexts retain the cancellation-only default for compatibility. Alternate
coordinators inject persistent controls to retain deadline metadata, accounting
and typed diagnostics across component callbacks.

## Parallel cap

Built-in finite portfolios with more than one lane partition the remaining solve
allowance over every materialized worker, including replicas. Integer division gives
equal shares, with remainder units assigned in worker order. Sequential portfolios
retain one shared allowance. Shared arithmetic invocations partition the
decision/move allowance over their materialized parallel slots the same way; complete
theory work retains its separate native currency and reservation. Supported linear
portfolios give a complete worker three quarters of a finite allowance when
heuristic siblings are present, with heuristic workers dividing the remainder;
complete-only and heuristic-only pools divide the full allowance equally.

Quotas persist on the containing budget across reopens and reseeds. They are not
redistributed after a worker finishes, fails or is cancelled. A quota can therefore
stop a worker while other quota remains unused. Synchronization retains every native
charge in the aggregate counter. Atomic overspend remains visible and can exhaust the
whole-run cap before another worker spends its quota. Low-level callers sharing a
budget directly receive synchronized cooperative accounting, rather than reservations.
Parallel scheduling and stopping order are not reproducible.

## Native arithmetic allowances

Arithmetic services retain their own work currencies inside the containing solve.
A conditioned check receives only its invocation's remaining allowance; new branches,
retractions and candidate retries do not renew it. Native work is forwarded once to
the caller meter, and resource exhaustion withholds an incomplete verdict. Finite
decision and move counters cannot reconstruct arithmetic charges, and arithmetic
units are not charged again as decisions.

Parallel arithmetic workers retain their scope by slot identity. Reopening a slot
cannot renew or change its quota. Per-worker native counters and the containing
ledger record each charge; only one charge reaches the caller meter. A worker's
representation decline stops that worker, while containing resource stops and
cancellation stop all workers. Shared accounting does not share mutable arithmetic
workspaces.

Optional guidance, such as exact-linear polarity hints and gate recognition, reuses
a completed arithmetic check and charges its comparisons and index construction to
that check's operation token under a 65,536 entry and visit boundary. It creates no
numerical invocation or allowance; a private limit discards the partial index and
retains ordinary traversal, while cancellation withholds the whole hint. Hint usage
counters observe existing results and add no work. Required source, graph, LP and
witness work keeps its own token and limits independently of hint settings.

Native source setup prepays declaration-coordinate capture to its containing token
before building an immutable plan, and execution composes the native polynomial,
integer or function controls with that same stop and deadline. A mismatched native
control family rejects before worker construction. Neither a fresh execution request
nor an unused finite factory can renew stopped source preparation.

Each theory chapter defines its own units and representation caps:
[difference logic](19-difference.md#state-and-resources),
[linear real](20-linear-real.md#state-and-resources),
[nonlinear integer](23-nonlinear-integer.md#state-and-resources),
[polynomial real](24-polynomial-real.md#state-and-resources) and
[transcendental](25-transcendental.md#state-and-resources), with the shared rules in
[theory services](18-theories.md#state-and-resources).
[LP certification](27-lp-certification.md) defines what partial numerical evidence
survives an interrupted LP solve.

## Mechanism and compatibility

`solver.resources.NodeAllowance` owns the synchronized decision/move ledger, native
instruction conversion and persistent quota partitioning. It depends only on resource
mechanisms and neutral utilities; strategy policy consumes that mechanism.
`backtrack.NodeBudget` is the published compatibility facade. Each facade forwards
to one resource allowance; cached quota facades reference the same resource children
on every reopen. Unwrapping a facade does not copy counters, renew cancellation or
allocate a new ledger. The facade retains the historical conversion constant for
compiled callers.

`Cancellation` and `WorkMeter` live in the neutral `util` package within `klause`. Their units come
from the caller's adapter; they contain no portfolio pricing. Composing tokens carries
one available meter, rather than charging all possible ancestors. Presolve's adapter
explicitly owns native root-work conversion. Numerical kernels consume native controls;
solver policy owns conversions and result/proof semantics.

---

Previous: [10. Sessions and streams](10-sessions.md) · [Contents](README.md) · Next: [12. Presolve and reconstruction](12-presolve.md)
