# 6. Sessions and resources

A session groups operations under caller assumptions. A stream keeps one operation
alive while it is consumed; a resumable handle keeps it alive across slices. The
session excludes overlapping mutations until that owner closes. Published results
outlive the operation.

Sessions own scope and cleanup; the [resource allowance](#resource-allowances) owns
effort across nested work. [Search](08-search.md) and [local search](10-local-search.md)
describe the state retained by each engine.

## Assumptions and conditioned queries

`Solver.session()` opens a single-threaded scope owner. Every operation captures the
assumption stack when called, including lazy sequences. Later pushes override earlier
pins and per-call parameters; popping restores the previous scope. The source model
is unchanged. Parameters that cannot enforce assumptions reject non-empty pins
explicitly, as do custom sessions for scoped operations they cannot implement.

`satisfyUnderAssumptions` includes parameter pins in the operation's scope and in any
returned assumption core. Bound and hole restrictions also qualify infeasibility;
an empty exact-pin set does not make that refutation global.
Finite traversal seeds each domain restriction at a retractable decision level.
Reseeding a scope containing restrictions rebuilds its seed from the original root.

Reusing a source attachment creates a fresh execution scope. Optional heuristic
views are requested after admission and presolve under the containing token;
interrupted construction publishes no partial view.

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

Results retain captured source semantics even without an assignment. Resumable
handles keep lifetime cancellation across slices, while each slice supplies its own
invocation token; neither wrapping nor resuming renews an allowance.

## Retained state and repair

Fresh calls build fresh traversals. Resumable slices keep their trail or assignment,
RNG and restart progress. Local-search sessions keep learned weights between fresh
calls, capturing them at each published sample or incumbent. Open local-search
streams also exclude searches through other sessions of the same solver, because
strategy and restart state belong to the solver.

Repair handles are a separate internal contract. Their cutoff must be monotone
non-increasing because retained objective-bound clauses survive fragment reseeding;
session push/pop always starts a fresh search. `RepairSearch.beginExact` accepts a
strict rational cutoff in minimized working-objective units. Cutoff monotonicity,
incumbent admission and discrete objective dominance compare exact values, including
values with identical floating displays. A looser condition requires a fresh handle.
Floating LP pruning receives an enclosing upper bound, and continuous completion
requires exact certification instead of a tolerance optimum. Source callers retain
responsibility for objective scaling and direction.

A retained repair starts an explicit fragment and advances it in slices. Its
best improving sample and stop reason are independent: a missing reason means a
pause, `SearchExhausted` completes the conditioned optimization, and cancellation,
budget exhaustion or unavailable completion prove no closure. A slice cancellation
can resume; the fragment's lifetime cancellation remains terminal until another
fragment starts. Starting a sibling retires the old trail and slice while retaining
compatible learned clauses and LP state. Closing releases both, and terminal
observations remain readable.
The traversal reset clears the preceding fragment's stopped-budget marker without
renewing the containing allowance.
A completed fragment closes its requested strict cutoff only when no improving
working point was found; a better point and completed optimization remain distinct.

A pure Boolean satisfaction repair retains the satisfaction traversal and its prepared
clause state across sibling pins. Its first unpinned query uses the constructed root
directly; a changed or subsequent condition reseeds it. Each query has no objective or finite cutoff; a
checked witness and an exhausted sample-free query remain distinct. Reseeding resets
query traversal and root publications while keeping cumulative work, learned state
and the containing decision allowance. Numeric restrictions, registered integer
rows, symmetry reductions and extra components reject this path. Exchanges require
an internal conditioned-export promise; unmarked exchanges reject it. Portfolio task
lanes can first advance a whole-source query and transfer its retained engine into
a matching task lease. The original source, caller pins, policy and containing quota
must match; transfer retains cumulative work and learned state. Retained satisfaction
checks its decision allowance before entering another assertion and charges each
committed decision through the shared execution controls; reseeding renews neither. Lanes acquire the original session owner through the same guarded wrapper as ordinary
satisfaction, and release it on terminal completion, failure or explicit retirement.

ALNS records retained repair observations as a best found point or an unknown
result with that scoped stop reason. The observation carries no unpinned
infeasibility or optimality claim; an unconsumed improving ray remains unavailable.

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

Repeated coordinates in an explicit counting or accurate-sampling projection
contribute once, preserving their first occurrence's order.

## Exact count bounds

Anytime exact counting partitions pure Boolean clause models into independent
components after conditioning. Fixed projected values contribute one, free projected
columns contribute two, and components without projected columns contribute only
existential feasibility. Every enforcing clause participates; a clause satisfied by
a fixed literal contributes no incidence. At least two constrained components and a
nonempty projection are required. Empty projections use one whole-model feasibility
check. Integer, real, unsupported-factor, custom-projection and symmetry-breaking
models retain monolithic counting, with no representative pruning.

Each component traversal reports proven projected assignments as its lower bound and
adds all unchecked or undecided subtree mass to its upper bound. Component intervals
multiply using exact nonnegative integers and share one `maxChecks` allowance,
including root checks. Unvisited existential components retain `[0, 1]`; a proved
zero component makes the product exactly zero even when another is unresolved.
Exhausting `maxChecks` partway through an expansion keeps the unchecked siblings in
the upper bound, and an `Unknown` check keeps its whole subtree there. Subtree arithmetic is
exact, and integer values are visited lazily, so a bounded budget never materializes
a whole domain. `Count.upper == Long.MAX_VALUE` is an unbounded sentinel when the
exact upper mass exceeds the range. Equal saturated bounds cannot prove exactness;
the internal interval must collapse to a representable count.

## Theory invocation scopes

Arithmetic invocations share immutable source snapshots and own their traversal,
query results and work ledger. Published exact values outlive the workspace.

A mutable `SearchComponentSet` permits one session construction attempt. Prepared
openings instead create fresh components and a resource registry per opening.
Completion, close and partial failure release registrations once in reverse order;
cleanup failures are suppressed onto a construction failure. Abandoned streams with
resource-owning extensions must be closed.

Conditioned linear queries keep one private prepared search and ledger. Each query
asserts Boolean pins, exact bounds and a strict objective cutoff, then retracts them
with its deadline and meter. Restarts and backjumps preserve the opening decision
level, so exhaustion refutes only the condition. Unpinned Boolean splits consult
caller hints before valid live-point preferences, retaining both alternatives.
Polynomial and nonlinear integer invocations follow the same scope rule; their
native units are described in [Part IV](14-theories.md).

## Resource allowances

One containing deadline and ledger bound preparation, workers, slices and nested
arithmetic. Rebuilding, reseeding or resuming never renews them. Atomic work can
overspend an allowance; its charge remains and the next polling boundary stops
optional work. Resource exhaustion does not establish search-space exhaustion.

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

The opt-in [portfolio diagnostic schedule](12-portfolio.md#reproducible-diagnostics)
marks internal cancellation adapters as work scheduled. Composition, invocation and
lifetime wrappers preserve that policy. Finite root LP preparation, primal search
and candidate verification retain work limits and emergency cancellation while
omitting their optional clock backstops. This marker grants no result authority.

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
| Inprocessing ceilings | Propagation-work units per scheduled slice and class, inside the worker allowance | Retained cursors, batches and unpaid atomic debt |
| Learned-memory preparation | Charged observation/preparation work and modeled scratch bytes per attempt | Safe defer, protected floor or completed reclamation |
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

Continuous candidate completion and joint linear repair convert LP work, plus
exact point recovery, into LS instructions using the slice prices below. Retained
numerical traversal uses the same LP price for its scheduler work; cumulative
source LP funding keeps raw numerical units.
Advisory incumbent completion uses that same companion funding for preparation,
residual recovery and original-source/objective checking. A changed seed or source
context consumes another retained attempt without replenishing the allowance;
unchanged terminal failures do not restart recovery.

Slice pricing is solver policy: LP work costs one node per 600 units and propagation
one per 4000 visits. Inprocessing scans, probes, attachment and retirement enter
that propagation ledger; probe and visit diagnostics add no separate price.
Fractional marks survive slices and the preparation-to-search transition.
Overspend debt is settled once and repaid by later slices; a nested private search
leaves debt with its containing budget. Private LP rounding, pumps and proposal
verification charge the same currency, including rejected proposals. Retained
finite optimization behind an external cutoff converts remaining slice work into an
LP operation cap. Saturating arithmetic prevents large allowances from wrapping.

Pending continuous CP completion charges visited numerical work at each pause,
including component solves, without counting a pause as another solve. Its
cursor retains the current numerical phase and cumulative limits; certification
and source publication remain required before the discrete leaf is consumed.
Leaf encoding, row assembly, factorization and certification stay atomic under
the containing token, so a checkpoint can overspend and leave debt for later slices.

Core-guided encoding and baking use the propagation currency and spend no
decisions. An automatically deferred single-lane core worker has one base slice per
turn and two seconds of cumulative active time; see
[core search](12-portfolio.md#boolean-objective-core-search).

### Preparation progress

Local-search initialization and general CP optimization construction retain private
cursors across slice expiry. Buffers, seeding, indexes, root propagation and LP
installation advance in bounded batches, spending time without decision/move charges.
Closing discards the cursor; individual allocations and factor calls stay atomic.
[Local-search preparation](10-local-search.md#projections-and-preparation) and
[CP preparation](12-portfolio.md#private-cp-preparation) own those mechanisms.

Encoder construction charges row, term, factor and installer visits. Required
post-presolve installation uses the containing token; optional source passes use
presolve limits. Exhausting an optional allowance cannot skip required installation.
Interrupted construction discards relations and retains charges. Completed immutable
relations can be reused for one consultation unit; live assertions and query state
remain private.

Optional finite real-difference preparation has a cumulative 65,536-visit limit and
10,000-column envelope. Decline discards its whole prefix without refund.

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
cap. Worker capacity queries also respect every containing allowance, including
the aggregate instruction price across sibling quotas. Parallel scheduling and
stopping order are not reproducible.

### Native arithmetic allowances

Arithmetic work uses native units inside the containing solve. A conditioned check
receives only the invocation's remaining allowance; branches and retries never
renew it. Native work is forwarded once to the caller meter, separately from decision
charges. Parallel slots retain their quota across reopening. Representation decline
stops one worker; containing stops cancel all.

Retained numerical source queries reserve one LP operation per changed authority.
Consultations of the same suspended query retain its reservation and cumulative
numerical allowance, charging active work and elapsed time without charging the
pause between slices. Caller cancellation retires the operation; a cooperative
quantum pause leaves it available for the next slice.
Single-shot numerical execution resumes these pauses until it obtains a result or
the containing cancellation stops it.

Numerical MIP traversal and joint repair use caller-funded source LP work. Each
slice grants a quantum from one cumulative work and sparse preparation allocation
allowance. Admission costs 1024 work units, including declined and resumed attempts;
source metadata visits, sparse preparation, floating work, exact continuation,
refinement and certification debit
their measured work. Atomic phases can overspend, and the next admission pauses.
An unfunded query keeps its pending alternative and owner; cumulative exhaustion
ends the worker without proving exhaustion. Coverage splits debit modeled storage
for both children and their captured condition maps. Resuming cannot renew either ceiling.
`source-lp-work-limit` defaults to one billion units and
`source-lp-allocation-limit` to 512 MiB. Opt-in checked refutation reuse charges
support indexing, source reconstruction, proof replay, storage and eviction to
these same ceilings. Reuse admits at most 64 MiB of cumulative modeled allocation
and one eighth of the allocation remaining at its opening. Reaching that local
allowance retires the cache and leaves ordinary numerical queries available.
`source-cuts` controls checked source-row
separation in numerical frontier traversal. `source-lp-operations` supplies an independent
operation ceiling for controlled comparisons. Other source LP clients retain their
operation-based admission policy.
Optional numerical decision feedback debits journal construction, latest-query
replacement, retention and support scoring to the same cumulative source funding. Exhausted feedback retains
source-order branching and every complementary alternative.
Point-based source separation debits its scoring and projection storage to that
same ledger; its extra relaxation receives no renewed work or allocation ceiling.

Each retained sparse query takes its finite numerical lifetime from the caller's
remaining cumulative work, with a proportional pivot allowance. Slice grants limit
individual advances and do not renew that lifetime. Unfunded clients keep the fixed
query policy. Preparation, certification and atomic overspend debit the same caller.

Optional guidance reuses completed checks. Its index construction spends the check's
token under a 65,536-entry boundary; a private stop discards only the hint. Native
setup prepays source capture, and new controls cannot revive stopped preparation.

Theory-specific currencies and caps belong to [difference logic](14-theories.md#state-and-resources-1),
[linear arithmetic](15-linear-arithmetic.md#state-and-resources),
[nonlinear integer](16-nonlinear-arithmetic.md#state-and-resources),
[polynomial real](16-nonlinear-arithmetic.md#state-and-resources-1) and
[transcendental arithmetic](16-nonlinear-arithmetic.md#state-and-resources-2).
[LP certification](17-linear-programming.md#resources-and-cancellation) defines its
own interruption boundary.

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
