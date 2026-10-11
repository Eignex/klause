# Portfolio scheduling and publication

Portfolios admit engine results under a shared source scope and schedule retained
workers. [Sessions](sessions.md) own assumptions; [LP
certification](../lp/certification.md) owns numerical proof checks.

## Witnesses and proof scope

Open portfolios transport exact assignments in `Sample` rather than through a side
registry. `exactInts` and `exactReals` carry immutable authoritative coordinates; finite
samples retain private compact arrays. Constructor inputs and exported arrays cannot
mutate a published incumbent. Exact theory and reconstruction carriers follow the
same owned snapshot contract; live producer workspaces remain private. See
[model and ownership](../architecture.md#model-and-ownership). Copying an unchanged assignment preserves its
coordinates and theory acceptance. Changing coordinates requires a fresh witness check.
The exact objective is evaluated from the carried assignment, including integers outside
`Long`.

A wide exact sample exposes no finite integer array. Local-search warm starts explicitly
project the integers into their search windows and discard theory acceptance. This
search seed cannot replace the exact incumbent or justify a bound.

Portfolio evidence names its source model and objective. These are opaque in-memory
identities: a fresh or transformed model needs its own check, even if structurally
equivalent. Copies of unchanged exact witnesses retain their source certificate;
coordinate changes and warm-start projections discard it. Objective changes cannot reuse
objective bounds or unboundedness claims. Unconditional variable bounds use the model
scope without an objective.

Verification preserves three outcomes. Accepted witnesses may become incumbents;
rejected proposals quarantine their producer without proving the model infeasible;
indeterminate checks publish nothing and do not count as a fault. Finite fixpoint
cancellation stays indeterminate. Uncertified real coordinates require a complete
source-row check, including strictness, reification and declared bounds, or the
configured source tolerance check. Unsupported checkers decline.

Bounds, infeasibility and unboundedness require a matching completed-proof certificate
in addition to identity. Native adapters issue these certificates only for their
engines' completed proof results; open local search has witness authority only. A
missing certificate cannot justify pruning or a terminal proof, although a separately
accepted feasible point can remain the best incumbent. The certificate is an in-memory
attestation of the engine's check, not a serialized proof or an independent proof
checker. Numerical proofs remain owned by their engines; a point alone never certifies a
recession direction.

Built-in shared channels belong to the same model and objective. Workers reject foreign
pools; objective and variable bound imports require matching certificates. The shared
incumbent exchange runs the source witness checker before installing a cutoff, including
direct engine publications. The low-level public portfolio API continues to support
caller-trusted unbound workers and its explicit witness checker; source pipeline
portfolios install the scoped verification contract.

Learned-clause exchange requires at least two materialized backtrack workers, including
replicas on parallel lanes. A lone complete worker keeps its learned database locally
without exporting portable clauses. Shared preparation and incumbent channels retain
their own lifetimes even when there is no clause peer.

## Scheduling neighborhood diversity

A curated sequential mixed optimization pool over a scheduling model pairs the original
plateau local-search arm with a primary-repair plateau variant in the preceding generic
CBLS slot. Both start from the original plateau arm's seed; the original arm and every
remaining worker retain their positions and seeds. The variant defers supplemental
Element repairs to complete improving fallback selection. The original arm retains
its full repair neighborhood. Explicit local-search pools, local-search-only execution,
parallel pools and non-scheduling models retain their selected recipes.

## Slice accounting and coverage

The counted schedule starts at 5000 node equivalents, grows by 1.5 after an ordinary
scheduled segment, and caps at 150000. Growth belongs to the shared schedule, rather
than to each arm. Initial probes and private-preparation revisits use the base allowance;
revisits do not grow the schedule. Counted segments also retain their elapsed-time cap.
The finite CLI exposes `slice-work`, `slice-max-work` and `slice-growth` for controlled
comparisons, and reports all three effective settings in portfolio statistics.
The [slice policy evidence](../evidence/slice-policy.md) records the bounded
comparison and rationale for retaining the common defaults.

Resumable backtrack arms charge decisions, propagation visits and completed LP work to
their scheduler. The current conversion is one slice node per 4000 propagation visits
and per 600 LP work units. Construction is charged once; fractional charges carry
forward and overspending is repaid by later slices. The solve-wide decision/move cap retains its separate currency; see
[resource allowances](resources.md) for persistent parallel quotas and scope composition.

### Private CP preparation

General CP optimization handles open with private preparation. Propagator projection and
root propagation advance in batches of 256 factors, retaining the queue, watcher changes
and deductions between turns. A paused root is not a search session and cannot publish a
sample or refutation. Completion installs its fixpoint or derived conflict once, then
enables the ordinary trail and branch-and-bound search. Propagator objects remain
private to the handle. Preparation work, fractional charges and overspend debt carry
into search without being charged again; the stats sink retains its timing window. A
cancellation between root fires pauses the private queue without marking the fixpoint
unusable. Final assumption seeding and search installation can abort initialization, and
their partial state cannot resume or publish a result. The packed native-SAT
optimization path and satisfaction/repair construction retain their constructor
lifecycle. State allocation, occurrence indexes, individual factor calls, assumption
seeding and LP construction remain atomic work.

## Preparation revisits

Before the first incumbent, an optimization arm with pending private preparation or a
local-search arm that has not executed an instruction receives preparation revisits
after all initial siblings have been admitted. Unfinished arms rotate in arm order until
they enter search or terminate. Each revisit uses the base allowance and the regular
family time share; it does not grow later slices. Dedicated lanes resume their own
handles directly. No preparation revisit delays an unadmitted sibling.

Before the first accepted witness, a selected complete-search family runs its available
arm with the least charged work. Shared clauses, root fixings and objective floors
remain credited, but cannot exclude a silent search from its family's coverage. Before a
witness, observable violation reductions can earn a family more turns; complete-search
root fixings and glue clauses do not favor its family, because deduction throughput does
not predict the first witness. Those counters remain recorded. After the first witness,
arm selection also follows that policy. Initial admission, preparation revisits and
explicit minimum time shares retain precedence. A segment reporting no charged work uses
its slice allowance for coverage accounting.

Slices yield before consuming an alternative or after a completed node. A pending
sibling cannot be skipped when yielding, and reset discards a pending alternative.
Constructor refutations return immediately. A rejected last root leaf can finish
exhausted even if its completed work consumes the slice.

Local-search arms follow the [retained handle
contract](local-search.md#handles-and-exclusive-ownership).

The scheduling conversion is not an elapsed-time guarantee. Propagation fixpoints,
construction, expensive factor calls, an LP solve and value-probe batches can overrun a
slice. Solve-wide cancellation remains available within propagation; some factor
internals are not covered by visit metering. Local-search segments have separate atomic
work.

## Reseeding and arm failures

Optimization portfolios can reseed stale resumable arms after an incumbent;
`reseed-stale-threshold` defaults to 3 non-improving segments with fresh charged work,
with 0 disabling it. Debt-only pauses do not advance plateau counters. Backends can
defer reseeding while bounded preparation owns pending work; session ownership wrappers
forward that guard. A bounded preparation that earns an accepted incumbent improvement
can continue across ordinary slices before the adaptive policy chooses again. Explicit
minimum shares remain first, and normal family selection resumes when preparation
finishes or expires. The containing arm retains its work charges, slice limits and
whole-solve deadline. A pending clausal primal repair retains its private state until
its existing phase allowance finishes.
Complete-search arms without a restart schedule retain traversal because closing a proof
gap need not produce a better incumbent. Imported incumbent cutoffs still apply.
Observable search families and complete workers configured with a restart schedule
retain their plateau reseeding policy under the same incumbent. Each arm retains
completed-handle totals separately from its current cumulative snapshot. Repeated
snapshots replace the live entry; closing captures the final counters and merges the
handle once, including when reseeding or stopping the pool. Open-theory handles capture
live-round progress before releasing their traversal; closing a completed round does not
capture its counters again. Ordinary arm exceptions retire their producer and leave
siblings running. The schedule retains one diagnostic per arm with its identity,
operation, segment, charged work, exception type/message and cause trace. Type, message
and trace are capped at 128, 1024 and 4096 characters. Output callback exceptions still
propagate; source-refuted claims remain quarantine faults rather than ordinary exception
diagnostics. Reseeding preserves terminal verdicts. Arm policy, scheduling and available
continuous-column capability determine which arms run; unsupported local-search arms are
filtered from mixed pools while local-search-only requests retain their own behavior.

## LP arm admission

Continuous-column optimization models rank the default LP backtrack arm after the SAT
guard and conflict-driven core. A curated sequential mixed optimization pool with at
least six configured slots appends an applicable default LP arm when its configured
backtrack slots have not admitted it. The appended arm follows the configured workers
and auxiliary ALNS arm, retaining their positions and seeds. When the objective has no
continuous coefficient, the auxiliary LP arm joins after the first incumbent, retaining
the incumbent workers' initial pool. LP ceilings that disable bounding omit the
auxiliary LP arm and its reservation. Explicit arm pools control admission and
techniques. Curated single-core mixed optimization reserves half the scheduled time for
this LP arm when the objective has a continuous coefficient, until the first incumbent.
Individual segments retain work charging and time caps; the remaining time follows the
bandit. After the first incumbent, all scheduled time follows the bandit. Explicit
backtrack pools and parallel lanes retain their requested scheduling. The auxiliary LP
arm receives one base improving-phase probe if it has earned no progress credit. An
unproductive probe retires the arm and closes its handle; retirement preserves the
incumbent without claiming exhaustion. A witness, objective or bound improvement, root
deductions, or used shared contributions retain the arm under normal bandit scheduling.
Configured LP slots do not retire under the auxiliary probe policy. An unproductive
auxiliary improving-phase probe does not decay the retained workers' bandit or family
evidence, preserving their policy state when the probe is discarded.

See
[SliceBudget](../../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/SliceBudget.kt)
and [portfolio](../../klause/src/commonMain/kotlin/com/eignex/klause/portfolio/).

### Satisfaction pools

The satisfaction backtrack palette includes conservative LP after default and aggressive
LP. Applicability, model class and LP ceilings order and filter the palette. Root variable shaving and hull
pruning remain explicit plan options. Root cuts follow the resolved plan. Curated
satisfaction workers require an explicit `lp-root-cuts=true` override to harvest root
cuts; explicit pools retain their requested root-cut plans.

The clausal complete-search recipes use clause learning, EMA restarts and a true-first
saved phase, without pseudo-Boolean learning or inprocessing. Their balanced and stable
variants use VSIDS decay 0.975 and 0.99, with database caps of 4000 and 2000. Curated
admission requires a clausal model; pseudo-Boolean models retain their cutting-plane
palette.

Clausal satisfaction leads its complete-search slots with the balanced recipe. A
curated sequential mixed pool reserves 90% of active time for that worker after the
initial probes: a refutation emits no incumbent progress from which family rewards
could predict the approaching proof. Explicit pools, parameter edits and search
annotations retain unreserved scheduling. Automatic single-core mixed clausal
satisfaction uses one local-search and one complete arm, avoiding probe time and clause
exchange between redundant complete workers. Explicit arm counts, named or edited
pools, annotations, custom fallback counts and parallel execution retain their
configured pool size. CLI property and environment arm counts are explicit overrides.

Clausal satisfaction leads its curated local-search slots with fixed-break probSAT,
using tabu tenure 10 and an improving aspiration rule without a strategy restart
schedule. Optimizing clausal models retain the general local-search order. Explicit
local-search pools retain their selected recipes.

## Incumbent guidance

FlatZinc search hints select tiered variable and value heuristics without inheriting the
free-search preset's phase saving or restart schedule. The source hint's solution-guided
value wrapper remains available without saved phases taking precedence over it.
Optimization updates guidance only after admitting a complete improving incumbent.
Solution-guided selectors retain their first descent, then adopt better verified pooled
incumbents when traversal resumes, without discarding their trail or learned state.
Other pooled solution phasing retains its restart-boundary import policy.

## Statistics

The portfolio owns `SolveStats.run.wallMs` for each satisfaction or optimization
invocation. Its monotonic clock spans schedule initialization, all segments,
witness verification, lane completion and retained-handle disposal. Worker counters
merge independently; backend tags and timeout flags retain their existing merges.
The portfolio duration replaces worker run windows rather than summing them or
taking their maximum. Per-arm scheduled times remain inclusive segment totals and
can overlap across parallel lanes.

| Field | Meaning |
|---|---|
| Arm `maxMs`, `initMs` | Longest elapsed segment and handle opening cost |
| `portfolioReseedStaleThreshold`, arm `reseeds` | Effective stale threshold and reseed count |

`initMs` measures opening the handle. Preparation deferred into slices contributes to
segment time and live propagation counters; it is not included in opening time.

[Search statistics](search.md#statistics) and [benchmark
records](../../klause-bench/README.md#saved-results) define propagation, conflict and
saved-result accounting.
