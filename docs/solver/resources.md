# Resource allowances

A solve has a containing deadline and work limits. Preparation phases, workers,
resumable slices and arithmetic services consume parts of that allowance in their
own units. Keeping the ledger with its scope prevents a restart or nested repair
from silently receiving a fresh solve budget.

Resuming a handle, reseeding an arm or rebuilding a model does not renew the
containing allowance. Completed atomic operations can overspend; their work stays
charged and the next polling boundary stops optional work. An exhausted resource
allowance never establishes that the search space was exhausted.

This page defines configuration, work currencies and quota lifetimes.
[Sessions](sessions.md) owns handles; [presolve](presolve.md) and
[portfolios](portfolio.md) own phase and slice policy.

## Configuration and lifetime

The CLI parses `auto`, `unlimited` and a nonnegative remaining amount as distinct
settings. Omission selects `auto`. For `node-limit`, automatic and unlimited both resolve
to no solve work cap; `0` resolves to an exhausted `NodeBudget`. For
`presolve-work-limit`, automatic derives work from the preparation time policy,
unlimited removes the work cap and `0` stops optional preparation. The preparation
clock and whole-solve deadline still apply. The legacy nonpositive
`klause.presolve.budget.ms` disables the derived preparation work cap and its clock;
an explicit work setting can still supply an exhausted or finite work allowance.

Built-in `withCancellation` adapters replace the invocation token while retaining any
separately bound lifetime scope. `withLifetimeCancellation` composes a persistent token
that bounds construction and every later invocation or retained slice. Ordinary
parameter cancellation bounds one-shot execution and retained-handle construction;
each retained slice supplies its own invocation token. These scopes keep their earliest deadline without retaining an old
slice deadline. Portfolio construction and the first search segment share the offered
segment deadline. ALNS bootstrap construction uses its shortened phase token; later
bootstrap slices retain the containing lifetime scope and rearm their invocation clock.

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

Local-search initial preparation retains its progress between cancelled invocations.
Buffer allocation, assignment seeding and definition preparation spend wall time without
search instructions; resuming them neither renews nor charges the containing move
allowance. Only committed walk/restart instructions advance that ledger. Invocation
expiry leaves private preparation paused; closing discards its cursor. Individual
runtime allocations and factor calls remain atomic and may overrun the deadline; see
[initialization boundaries](local-search.md#projections-and-preparation).

General CP optimization construction retains the same private cursor across invocation
expiry. Index construction, state fills, assumption propagation and model-facing LP/search
installation checkpoint in fixed batches. Construction scans and primitive allocations
spend wall time without decision/move charges. Completed propagation visits retain their
existing slice pricing; final installation transfers fractional marks and debt once.
Individual runtime allocations and primitive/factor calls can still overrun a checkpoint;
see [private CP preparation](portfolio.md#private-cp-preparation).

The solve-wide decision/move currency excludes propagation and LP work. Ordinary LS
instructions and CP repair decisions charge the same containing `NodeBudget`. The
private clausal primal search and walk also charge that ledger. Their decisions and
walk instructions are not included in the enclosing arm's `nodes` and `lsMoves`
counters, so those exported counters alone cannot reconstruct the solve charge. ALNS
charges each outer iteration's declared flip allowance once; the inner LS repair is
not billed again for its moves, while CP repair decisions remain additional native
work. CLI statistics expose `nodeBudgetLimit`, `nodeBudgetSpent` and
`nodeBudgetRemaining` from the containing ledger, including cooperative overspend. Scheduler coverage for finite ALNS segments uses the offered segment allowance;
it is distinct from actual inner work. Native bootstrap/repair counters and
`alnsOuterAllowance` retain those distinctions in statistics.

Core-guided private encoding and augmented-model baking use the propagation visit
currency, including work stopped during construction. Preparation does not spend
solve-wide decisions or moves. The containing core handle settles query overspend
once and retains fractional charges across rounds; its nested traversal does not
repay the same debt. An automatically deferred single-lane core worker has one base
work slice per turn and two seconds of cumulative active time, independent of
later scheduler growth. Work pauses retain completed fixpoints and traversal state;
propagation interruptions discard the partial traversal. A private segment stop
reported as native budget exhaustion, including cancellation after traversal starts, pauses the core
handle while its decision, node and lifetime quotas remain available. Source
verification observes the same segment and cumulative active-time controls;
interrupted checking withholds the candidate and stops further assignment pins.
Idle waits spend no active time; construction and every retained slice do.
Expiry keeps completed checked floors and returns a budget stop. Explicit core
selection and parallel slots retain their ordinary allowances. See [core search](portfolio.md#boolean-objective-core-search).

Slice pricing belongs to solver policy: LP work costs one node per 600 units,
propagation work one per 4000 visits, inprocessing probes one per 12 and subsumption
visits one per 18000. Fractional LP and propagation marks survive slices and the
preparation-to-search transition. Overspend debt is settled once and repaid by later
slices. A nested private search leaves debt ownership with its containing budget.
Private LP rounding, pump and proposal-verification propagation charge the same
slice currency, including rejected proposals. Complete LP float proposals use the
caller's source tolerance verifier before discrete completion. Mixed proposal completion uses the
containing root cancellation and real-leaf native ledger; composed verification
adds its own short deadline without renewing the root allowance.
Root primal queries retain the remaining cumulative root/native LP allowance;
adaptive node limits do not select their primal points. Each completed attempt
charges that allowance before a fallback can open another solver.
Relative counters and saturating additions prevent a large allowance from wrapping.
Retained finite optimization behind a finite external cutoff converts the slice's remaining work to an optional
node-LP operation cap, subtracting fractional LP work already charged. The adapter
supplies native units to the model-facing LP owner, which takes the smaller of that
cap and its containing effort allowance. Required leaf completion and preparation
keep their own allowances. Retained numerical owner construction uses the containing
effort allowance; the slice cap applies to its floating queries. Atomic kernel work
can still overspend; the slice carries that debt through the existing accounting.
Search before a finite external cutoff retains its containing native effort allowance.

`Cancellation` and `WorkMeter` live in the neutral `util` package within `klause`. Their units come
from the caller's adapter; they contain no portfolio pricing. Composing tokens carries
one available meter, rather than charging all possible ancestors. Presolve's adapter
explicitly owns native root-work conversion. Numerical kernels consume native controls;
solver policy owns conversions and result/proof semantics.

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

Custom contexts retain the cancellation-only default for compatibility. Alternate
coordinators inject persistent controls to retain deadline metadata, accounting
and typed diagnostics across component callbacks.

## Parallel cap

Built-in finite portfolios with more than one lane partition the remaining solve
allowance over every materialized worker, including replicas. Integer division gives
equal shares, with remainder units assigned in worker order. Sequential portfolios
retain one shared allowance. Shared finite, open, polynomial, nonlinear-integer, quadratic and transcendental invocations
partition the decision/move allowance over their materialized parallel slots. Complete theory work
retains its separate native currency and reservation.

Quotas persist on the containing budget across reopens and reseeds. They are not
redistributed after a worker finishes, fails or is cancelled. A quota can therefore
stop a worker while other quota remains unused. Synchronization retains every native
charge in the aggregate counter. Atomic overspend remains visible and can exhaust the
whole-run cap before another worker spends its quota. Low-level callers sharing a
budget directly receive synchronized cooperative accounting, rather than reservations.
Parallel scheduling and stopping order are not reproducible.

## Native arithmetic allowances

Quadratic native worker slots retain their scope by arm identity. Reopening a slot
cannot renew or change its quota. Per-worker native counters and the containing
ledger record each charge; only one charge reaches the caller meter. A worker's
representation decline stops that worker, while containing resource stops and
cancellation stop all workers. Shared accounting does not share mutable arithmetic
workspaces.

Arithmetic services retain their own work currencies inside the containing solve.
A conditioned check receives only its invocation's remaining allowance; new branches,
retractions and candidate retries do not renew it. Native work is forwarded once to
the caller meter, and resource exhaustion withholds an incomplete verdict.

Linear repair completion retains the same native check, decision and work ledgers
across candidate queries. Cached source planning neither renews an allowance nor
rearms the containing deadline. An interrupted native root installation releases
that partial mutable search; a retry keeps the immutable source plan, canonical
CP projection, selected theory fragment, completed native row factory and charged work. Active query assertions retract even when an invocation expires.

Encoder construction observes the containing invocation token during normal source
routing, component preparation and polynomial assembly. Exact linear formula expansion
charges row, term and distribution visits to that token's meter; polynomial assembly
charges factor visits and installers can charge their native construction work.
Interrupted construction discards every emitted relation and private definition and
retains its charges. Explicit installer resource declines retain the resource cause
even when the containing token has not fired. Exact linear component source
conversion and retained finite neighborhood installation use the current opening/query
token. A numerical point requiring private real coordinates receives an exact pinned
completion under the same token; rejection or interruption withholds that point and
never refutes its parent source. Immutable provider selections cache no installation outcome or
control owner. Source strategies charge polynomial installation against their native
`maximumWork` and pass only the remaining allowance into execution. Zero prevents
installer entry. Caller meters receive construction and execution charges once;
runtime polynomial counters remain distinct from source construction work. Successfully installed relations can be reused without retaining the
construction token; rewritten or conditioned relations receive their current controls.

The exact-linear source factory charges factor, row and coefficient visits to its
supplied cancellation meter. Exact rational row conversion and owned-buffer copies
are charged before their atomic operation; the next poll can withhold the entire
preparation after cooperative overspend. A stopped preparation exports no factory
or partial row catalogue and refunds no work. The completed factory retains neither
that token nor an allowance. Linear completion caches only a completed factory per
worker. Its separate preparation counter records every charged attempt without
refunding cancelled preparation; native check/decision units retain their existing
scale. A retry after interrupted mutable initialization opens from the same factory
and does not repeat those immutable preparation charges. Opening it creates fresh mutable components whose
invocation stop, certification, statistics and LP/search budgets are bound by their
caller; it does not grant new containing work or transfer an old context.

[Theory contracts](../theories.md#state-and-resources) link each service's native
units, representation caps and controls. Finite decision and move counters cannot
reconstruct arithmetic charges.

See [sessions](sessions.md) for handle ownership, [presolve](presolve.md) for phase
telemetry, [portfolio scheduling](portfolio.md) for coverage and
[LP certification](../lp/certification.md) for partial numerical evidence.

[Nonlinear integer execution](../theories/nonlinear-integer.md#state-and-resources)
shares cumulative polynomial work across search and all neighborhood callbacks.
Integer candidate decisions and shared heuristic instructions retain their distinct
strategy/node currency; arithmetic units are not charged again as decisions.

Supported linear portfolios reserve persistent native work, check and decision
quotas for their workers. A complete worker receives three quarters of a finite
allowance; heuristic workers divide the remainder. Heuristic-only pools divide it
equally. Unlimited controls stay unlimited, and unused quota is not transferred.
Proposal and instruction caps divide among heuristic workers; completion work also
charges their instruction ledger. Zero proposal, instruction or native work
allowance prevents a heuristic completion. Timeout and external cancellation retain
their separate reasons.

[Transcendental execution](../theories/transcendental.md#state-and-resources) retains
private source services in shared invocation slots. Slot native reservations and the
containing synchronized meter cover enclosure, algebraic, relaxation and original-source
checking cumulatively. Complete segment work includes native charges and decisions;
heuristic completion retains the exact-neighborhood instruction conversion. Complete
native queries can overrun slices while retaining whole-invocation cancellation. A
neighborhood slice interruption has no source-refutation authority.

Explicit arithmetic coordinate agreement uses the containing execution controls and
native complete-check allowance. Exact source-row exchange and point checking charge
coefficient/row visits to the existing caller meter and poll its current invocation
stop. No peer completion opens a fresh native allowance or replaces a slice token
with a new lifetime token. Interruption and representation decline retain their
separate incomplete diagnostics; a failed chosen point supplies no global proof.

Whole-relation encoder installation uses the receiving scope's cancellation and resource
controls. An interrupted installation exports no partial source relation. Immutable
unconditioned linear relations can be reused across component openings; live assertions,
query results and native resources cannot. Conditioned completion supplies only its
captured pins and current containing token, without renewing any arithmetic allowance.
Numerical execution and source-enforcement admission keep their separate resource gates.
