# Search and propagation

## Shared component search

[solver.search](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/)
owns decisions, levels, branching, retraction, complete checks, restarts and
model assembly. CP and theory components contribute residual state and reasons
through the same protocol. The session exchanges Boolean literals, semantic
integer bounds and opaque theory decisions rather than native CP domains.

Fractional source integers split at exact floor/ceiling thresholds. CP decisions
use Long values; open theories use arbitrary-precision thresholds. Stable
complementary atoms must exist before first-UIP learning. Boolean atom names
belong to one immutable source root; changing root meaning requires a fresh
session. Unnameable conflicts remain chronological and unexplained.

Complete assignments use source-keyed values from each owner. CP-learned Boolean
clauses remain natively owned by CP while their shared consequences reach theory
peers. The shared store does not duplicate the CP pin.

## Invocation scopes and stream ownership

`Solver.session()` opens a single-threaded scope owner. Every operation captures the
assumption stack when called, including lazy sequences. Later pushes override earlier
pins and per-call parameters; popping restores the previous scope. The source model
is unchanged. Solve, sample, enumerate and minimize require parameters that enforce
assumptions; unsupported parameters reject non-empty pins or deductions explicitly.
The default methods of custom sessions reject scoped operations they cannot implement.

Counting builds an isolated conditioned model. Integer domains carry the scope's pins,
holes and bounds; Boolean root pins become source unit clauses so hash extensions and
rebuilds preserve them. Exact, approximate and hybrid counting use that same model.
Accurate sampling also conditions on both call parameters and session scopes. Counting
and accurate sampling use complete finite-model machinery independently of the session's
sampling backend; local-search enumeration remains sampling with replacement.

`openSamples`, `openEnumerate`, `openExactCount` and `openImprovements` return
`SearchStream` cursors. A cursor has one consumer and releases its retained state on
completion, failure or explicit close. Use `use` when stopping early. A terminal value
can remain buffered after resources release; explicit close discards buffered values.
Legacy `Sequence` APIs capture the same scope but require sequential consumption;
built-in search paths release native LP solvers before each legacy yield.

An open stream or resumable handle owns its session. Other operations, push/pop and
local-search reset throw while that owner is active. Closing the session closes its
active owner, clears scopes and retained local-search weights, and rejects later
operations. Closing is idempotent.
Terminal resumable verdicts remain readable without further work, including after
close or subsequent scope changes; a closed pending handle rejects further slices.
Counters and preparation progress remain owned by the delegated backend handle.

Fresh calls build fresh traversals. Resumable slices retain their trail or assignment,
RNG and restart progress. Local-search sessions retain learned weights between fresh
calls, capturing them at every published sample or incumbent and at completion.
Early stream close preserves the most recent capture. Open local-search streams and
handles also exclude searches through other sessions of the same solver, because
strategy and restart state belong to that solver.

Repair handles are a separate internal contract. Their cutoff must be monotone
non-increasing because retained objective-bound clauses survive fragment reseeding.
General session push/pop starts a fresh search and does not reuse repair machinery.

## Finite propagation and explanations

Fresh presolve rebuilds retain the originating cancellation token and accounting
destination. See [presolve cancellation and accounting](presolve.md) for ownership,
probe-count scope and cooperative deadline limits.

[PropagationSession](../klause/src/commonMain/kotlin/com/eignex/klause/propagation/PropagationSession.kt)
owns the finite-domain trail and fixpoint. Sessions over one immutable model can share
its propagation projection and occurrence indexes. Incremental payloads, scratch arrays,
watchers and failure contexts belong to each state. Failure clauses and premise-variable
sets use separate state storage, leaving reversible payloads intact; each fire clears its
previous failure, and undo discards pending failure context. The native SAT lane keeps
its own state and leaves general CP failure storage unallocated.

`FactorExecutionCapabilities` inventories each built-in family's execution routes.
`SOUND_FILTERING`, `BOUNDS_FILTERING` and `DOMAIN_FILTERING` describe deduction
mechanisms; they do not promise full consistency at every size or resource limit.
Complete finite checks pin the full assignment and run a completed CP fixpoint.
Local-search invariant satisfaction is a separate check from its graded score.
Continuous arithmetic and floating objective scores require the existing source
publication checks; a zero heuristic score is not an exact certificate.

Gaussian XOR and difference-system helpers deliberately omit local-search scoring:
their retained source rows enforce the assignment. Symmetry helpers also omit
scoring because source witnesses need not satisfy a chosen CP representative.
The finite difference system is posted after presolve only when its fragment has
a guarded edge and an edge between two unfixed root integer columns. Edges to zero
or root-fixed columns act as unary bounds and retain their original propagators.
An unconditional edge between unfixed columns can still join unary guarded bounds.
Objective-bound overlays deliberately omit CP propagation. Every inert built-in
role carries a reason. A sound LP family can decline emission under feature,
resource or domain gates, and omitted rows weaken the relaxation.

Custom factors retain the `Propagator` and `Invariant` extension paths. A custom
`Propagator` without an `Invariant` explicitly takes the propagation-only path;
a custom `Invariant` supplies local checking and scoring through its interface
contract. Structural-only custom factors refuse CP and local-search execution
with route-specific errors. Custom implied linear rows remain available to LP
relaxation independently of either execution interface.

Shared table preparation transfers no-op verdicts only under matching contiguous
column domains. MDD preparation sharing includes the initial and accepting states;
value relabeling prepares an index over the relabeled transition array. Reversible
live tuples, diagram reachability and all assignment-dependent payloads remain
state-owned.

Dom/wdeg initializes from the session's prepared occurrence indexes. Failure weights
and its heap belong to the solve, retain conflict bumps across restarts, and apply bumps
received before the first selection once during initialization. Fresh selectors isolate
solves that share a projection.

Cumulative edge-finding runs in both time directions over fixed durations, demands
and capacity. Reversed deductions tighten latest starts and cite the historical
integer bounds and definitely-present task premises. Arithmetic ranges that cannot
be represented safely decline the reversed pass.

Propagators supply sound source
reasons for deductions and conflicts. An assignment's undo lifetime can be deeper
than the effective level of its reason: Boolean pins use the deeper of that level
and the current decision depth so an asserting backjump retains its consequence.
Reified arithmetic skips reason construction when a settled relation agrees with
its assigned indicator. New indicator pins and opposing assignments retain the
pin and conflict explanation protocol. Single-sided linear indicator deductions
record the existing lazy linear payload during trailed search. Shared Boolean premise
export resolves this payload into historical literals before traversing its proof graph. Conflict analysis
resolves it against the bounds at the pin, rather than later domains; untrailed
and two-sided deductions retain eager explanations. Linear bound propagation
constructs a reason only when the proposed bound tightens the live domain;
unchanged bounds skip eager explanations and lazy payload allocation. Single-variable equality and
disequality indicators over root domains contained in `{0, 1}` propagate as direct
channels: an indicator fixes its integer value, and a fixed integer pins the
indicator. Wider domains retain the general linear propagator.

The native CP analyzer and shared first-UIP analyzer have distinct counters and
ownership. A shared conflict with usable reasons can assert and backjump.
Sound resolvents that cannot assert retain chronological fallback. Root
refutations, unusable explanations and native CP analysis are outside the shared
asserting/non-asserting counters.

Table explanations use build-local caches for historical domains, carve positions
and materialized literals. These caches do not cross propagation or undo. Ground
supports use membership/value-set checks, and sparse root members avoid walks
over large unconditional holes. Support gathering can stop after all live values
of a ground column are covered, while every tuple is still checked for feasibility.
The reversible live prefix is committed once per completed sweep, including
empty-prefix conflicts; sibling branches restore filtering from trailed state.

LP explanations cite selected source bound witnesses and recursively expanded
row, fixing and cutoff premises. Current CP propagation and source LP conflicts
do not include an optional exact-theory row-propagation scanner or explanation
weakening pass. See [LP consumer contracts](lp/architecture.md#consumers-explanations-and-cuts).

## Learned-clause retention

The shared learned store has its cap off by default. When capped, reduction runs
at restart boundaries after pending attachments are drained. Glue clauses, units
and clauses used for propagation since the last reduction survive. Remaining
capacity keeps lower LBD first, then older insertion for ties. Protected clauses
can leave the retained count above the nominal cap.

Selection uses a Boolean drop mask. A bounded LBD histogram applies when the
maximum score is at most 4096 and at most four times the eligible count; sparse
ranges use the packed sort with the same ordering. This mechanism preserves the
retention rule. The native CP store keeps its own reduction policy.

See [SearchSession](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
and [ClauseDb](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ClauseDb.kt).

## Portfolio slices

FlatZinc search hints select tiered variable and value heuristics without inheriting
the free-search preset's phase saving or restart schedule. The source hint's
solution-guided value wrapper remains available without saved phases taking
precedence over it. Optimization updates guidance only after admitting a complete
improving incumbent. Solution-guided selectors retain their first descent, then
adopt better verified pooled incumbents when traversal resumes, without discarding
their trail or learned state. Other
pooled solution phasing retains its restart-boundary import policy.

Open portfolios transport exact assignments in `Sample` rather than through a
side registry. `exactInts` and `exactReals` carry immutable authoritative
coordinates; finite samples retain their compact arrays. Copying an unchanged
assignment preserves its coordinates and theory acceptance. Changing coordinates
requires a fresh witness check. The exact objective is evaluated from the carried
assignment, including integers outside `Long`.

A wide exact sample exposes no finite integer array. Local-search warm starts
explicitly project the integers into their search windows and discard theory
acceptance. This search seed cannot replace the exact incumbent or justify a bound.

Portfolio evidence names its source model and objective. These are opaque in-memory
identities: a fresh or transformed model needs its own check, even if structurally
equivalent. Copies of unchanged exact witnesses retain their source certificate;
coordinate changes and warm-start projections discard it. Objective changes cannot
reuse objective bounds or unboundedness claims. Unconditional variable bounds use
the model scope without an objective.

Verification preserves three outcomes. Accepted witnesses may become incumbents;
rejected proposals quarantine their producer without proving the model infeasible;
indeterminate checks publish nothing and do not count as a fault. Finite fixpoint
cancellation stays indeterminate. Uncertified real coordinates require a complete
source-row check, including strictness, reification and declared bounds, or the
configured source tolerance check. Unsupported checkers decline.

Bounds, infeasibility and unboundedness require a matching completed-proof
certificate in addition to identity. Native adapters issue these certificates only
for their engines' completed proof results; open local search has witness authority
only. A missing certificate cannot justify pruning or a terminal proof, although a
separately accepted feasible point can remain the best incumbent. The certificate
is an in-memory attestation of the engine's check, not a serialized proof or an
independent proof checker. Numerical proofs remain owned by their engines; a point
alone never certifies a recession direction.

Built-in shared channels belong to the same model and objective. Workers reject
foreign pools; objective and variable bound imports require matching certificates.
The shared incumbent exchange runs the source witness checker before installing a
cutoff, including direct engine publications. The low-level public portfolio API
continues to support caller-trusted unbound workers and its explicit witness checker;
source pipeline portfolios install the scoped verification contract.

Resumable backtrack arms charge decisions, propagation visits and completed LP
work to their scheduler. The current conversion is one slice node per 4000
propagation visits and per 600 LP work units. Construction is charged once;
fractional charges carry forward and overspending is repaid by later slices.
Decision/move limits still count their original events.

Before the first accepted witness, a selected complete-search family runs its
available arm with the least charged work. Shared clauses, root fixings and
objective floors remain credited, but cannot exclude a silent search from its
family's coverage. Family selection retains its progress policy; after the first
witness, arm selection also follows that policy. Initial admission, preparation
revisits and explicit minimum time shares retain precedence. A segment reporting
no charged work uses its slice allowance for coverage accounting.

Slices yield before consuming an alternative or after a completed node. A
pending sibling cannot be skipped when yielding, and reset discards a pending
alternative. Constructor refutations return immediately. A rejected last root
leaf can finish exhausted even if its completed work consumes the slice.

The scheduling conversion is not an elapsed-time guarantee. Propagation fixpoints,
construction, expensive factor calls, an LP solve and value-probe batches can
overrun a slice. Solve-wide cancellation remains available within propagation;
some factor internals are not covered by visit metering. Local-search segments
have separate atomic work.

Local-search optimization handles retain the live assignment, RNG, factor payloads,
restart progress and incumbent between segments. A slice pauses at an instruction
checkpoint or cancellation poll; an interrupted continuous-candidate check retains
its candidate for the next slice. Moves and restart transitions count as instructions.
The scheduler charges the observed instruction delta, while the solve-wide move
allowance is charged once across slices. Closing or reseeding discards the retained
state. Root refutations remain authoritative; an unsuccessful local-search walk is
incomplete.
An active local-search handle acquires exclusive ownership of its solver when opened.
Overlapping handle creation and other search execution throw `IllegalStateException`,
including searches through another session of that solver. Completion, failure and
idempotent close release ownership; a cancelled slice remains paused and retains it.
The owning session rejects assumption changes and warm-state reset until release.
Strategy and restart policy are shared by the solver; legacy streaming draws must
be consumed sequentially, while open streams hold exclusive ownership. Portfolio
workers use separate solvers and close a handle before reseeding it.
Optimization handles open through their session so its assumptions and state apply.
Sessions that decline resumable optimization retain their one-shot improvement stream.

The immutable local-search projection is prepared on demand and reused across draws.
Mixed-pool arms over the same model share it, including ALNS's inner local search.
Objective-bound overlays keep their own projections. Each Boolean, integer and real
variable's occurrence index contains each active factor once, in factor order. A move
updates that invariant once; the invariant accounts for all positions reading the
coordinate, including positions with different presence literals. Assignments, RNGs,
weights and invariant payloads belong to each live state. Resumable handles initialize large
factor sets in batches, retaining the next factor and completed payloads when a
slice expires. Partial costs, samples and warm state remain private until every
factor has been scored. Initial scoring preserves factor order and does not charge
search moves. Projection preparation also retains private invariant and occurrence-index
progress in batches. Matching arms can continue that shared preparation; a mutex protects
each batch and publishes only the complete projection. A suspended arm holds no lock.
Assignment seeding, individual factor calls,
custom restart policies, subsequent restarts and repair searches remain atomic work
that can overrun a segment.
Before the first incumbent, an optimization arm that has not executed an instruction
receives preparation revisits after all initial siblings have been admitted. Unfinished
arms rotate in arm order until they enter search or terminate. Each revisit uses the
base allowance and the regular family time share; it does not grow later slices. Dedicated lanes resume their own
handles directly. No preparation revisit delays an unadmitted sibling.
Greedy initialization polls cancellation between variables and retains only completed
coordinate repairs. Payloads, costs, definition propagation and score caches update normally;
neighbor configuration marking and tabu/activity tracking are suppressed during repair.
Its activity epoch resets on completion, cancellation or failure, and ordinary tracking
resumes afterwards. One variable's bounded value probes remain atomic.
Its coordinate eligibility uses the generic move sink's pinned, defined and implicit-owner
filters, preserving seeded globals and one-way definitions during repair.
Implicit table moves select values from each support row intersected with root domains.
Interval and wildcard cells remain searchable, including single-row supports; repeated
variables use the intersection of all their cells. Frozen coordinates retain their values;
tables reading defined integers decline implicit seeding and moves because a definition
can rewrite them outside the chosen row. An unreachable row produces no partial move or seed.
Built-in initial random restarts apply implicit seeding and the definition sweep
before initializing factor costs, avoiding an evaluation of the intermediate
random assignment. Custom restart policies retain their ordinary restart protocol.
ALNS skips its local-search fallback when the complete-engine bootstrap returns
without an incumbent after cancellation, avoiding fresh seeding after the deadline.
Hinted min/max expression outputs, their materialized affine operands and output aliases
form a one-way definition cone. Unhinted min/max globals remain searched. Competing
definitions and cyclic cones remain searched; independent constraints on a defined output
still contribute violations. Evaluation clips values to root domains and preserves pinned
outputs during seeding and moves. Repair proposals into these cones backsolve affine rows
and extrema into admissible searched inputs, respecting domains, pins and implicit owners.
Non-witness operands receive the required bound, allowing domain gaps and affine rounding.
Shared-input repairs recompute every affected definition before checking the complete maintained cone.
Element matching-cell indexes and range endpoints use Long arithmetic, including offsets
whose valid array indexes cross the Int boundary. Matching-cell scans skip unreachable
inverse targets until an admissible index repair is found.

The one-way definition network contains immutable reader indexes; matching mixed-pool
arms share its lazy construction while each state applies definitions to its own assignment.
Boolean break/make vectors initialize on their first score query and are maintained
incrementally thereafter; strategies that do not query them avoid their initialization pass.
Moves feeding definitions score the complete propagated assignment, including derived
factor degrees and objective coordinates. Compounds without definition readers retain
additive linear objective scoring for distinct coordinates and the objective's own
incremental delta; repeated-coordinate linear deltas use a probe of the final values.
Probes apply compound inputs together before
one definition pass, save affected input/output values, and restore those values directly;
an inverse definition that cannot write does not strand a derived output. Probe activity
is suppressed and its best-cost observation is discarded. Real moves in probes and
restoration do not advance the committed row-refresh cadence. Weighted probes snapshot and
scan only factors whose degrees change, in factor-id order to retain the full-scan
floating-point accumulation order.
Committed moves reconcile cost and violated membership from exact post-move degrees.
Factor data and engine projections are separate: the local-search projection supplies
an `Invariant`, while mutable payloads belong to each `LocalSearchState`.
Candidate `deltaIf*` methods return the exact degree change. Ordinary `apply*` methods
update payloads after the assignment changes; their return values are implementation-specific
and callers must read the post-move degree for scoring. Reified linear invariants offer
an internal fused payload-update and exact-degree path. Other invariants update payloads
and then read their degree, independently of the returned apply value.
Optimization retains its best infeasible restart anchor as a private packed assignment.
Strict cost improvements copy into that storage; a restart materializes an independent
sample only when no feasible incumbent supersedes the anchor. Published samples and
samples retained by custom restart policies remain independent of subsequent updates.

Optimization portfolios can reseed stale resumable arms after an incumbent;
`reseed-stale-threshold` defaults to 3 non-improving segments, with 0 disabling it.
Complete-search arms without a restart schedule retain traversal because closing
a proof gap need not produce a better incumbent. Imported incumbent cutoffs still
apply. Observable search families and complete workers configured with a restart
schedule retain their plateau reseeding policy under the same incumbent.
Each arm retains completed-handle totals separately from its current cumulative
snapshot. Repeated snapshots replace the live entry; closing captures the final
counters and merges the handle once, including when reseeding or stopping the pool.
Open-theory handles capture live-round progress before releasing their traversal;
closing a completed round does not capture its counters again.
Ordinary arm exceptions retire their producer and leave siblings running. The schedule
retains one diagnostic per arm with its identity, operation, segment, charged work,
exception type/message and cause trace. Type, message and trace are capped at 128,
1024 and 4096 characters. Output callback exceptions still propagate; source-refuted
claims remain quarantine faults rather than ordinary exception diagnostics.
Reseeding preserves terminal verdicts. Arm policy, scheduling and available
continuous-column capability determine which arms run; unsupported local-search
arms are filtered from mixed pools while local-search-only requests retain their
own behavior.

Continuous-column optimization models rank the default LP backtrack arm after the
SAT guard and conflict-driven core. A curated sequential mixed optimization pool
with at least six configured slots appends an applicable default LP arm when its
configured backtrack slots have not admitted it. The appended arm follows the
configured workers and auxiliary ALNS arm, retaining their positions and seeds.
When the objective has no continuous coefficient, the auxiliary LP arm joins after
the first incumbent, retaining the incumbent workers' initial pool.
LP ceilings that disable bounding omit the auxiliary LP arm and its reservation.
Explicit arm pools control admission and techniques. Curated
single-core mixed optimization reserves half the scheduled time for this LP arm
when the objective has a continuous coefficient, until the first incumbent. Individual segments retain
work charging and time caps; the remaining time follows the bandit. After the first
incumbent, all scheduled time follows the bandit. Explicit backtrack pools and parallel
lanes retain their requested scheduling.
The auxiliary LP arm receives one base improving-phase probe if it has earned no
progress credit. An unproductive probe retires the arm and closes its handle;
retirement preserves the incumbent without claiming exhaustion. A witness,
objective or bound improvement, root deductions, or used shared contributions
retain the arm under normal bandit scheduling. Configured LP slots do not retire
under the auxiliary probe policy.
An unproductive auxiliary improving-phase probe does not decay the retained workers'
bandit or family evidence, preserving their policy state when the probe is discarded.

See [SliceBudget](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/SliceBudget.kt)
and [portfolio](../klause/src/commonMain/kotlin/com/eignex/klause/portfolio/).

## Statistics

| Field | Meaning |
|---|---|
| `propagationWork` | Monotonic dispatch, literal, watcher, level and linear-term/reason visits, including work across undo |
| `rootPropagationWork`, `rootPropagationMs` | Constructor root-fixpoint work and time |
| `propagationMs` | Fixpoint elapsed time; some counted reason scans are outside it |
| Arm `maxMs`, `initMs` | Longest elapsed segment and handle construction cost |
| `openAssertingConflicts` | Shared first-UIP analyses yielding an asserting backjump |
| `openNonAssertingConflicts` | Sound shared conflicts retaining chronological fallback |
| `openReductionNs` | Total shared reduction selection and watch-rebuild time |
| `portfolioReseedStaleThreshold`, arm `reseeds` | Effective stale threshold and reseed count |

A nonzero non-asserting count is a reason to capture the conflict and inspect
component reasons. Zero does not establish complete factor reason coverage.
Missing metrics remain missing, including root theory refutations that never
enter shared search. Ratios of visit work to fixpoint time are coarse because
their scopes differ.

## Monotone clausal primal probe

Pure Boolean clausal optimization with at most 64 positive-cost variables can
seed incumbents through greedy feasibility repairs when those variables occur
only positively in hard clauses. A private satisfaction repair keeps unaccepted
cost variables true and tries their cheap values in descending weight order.
A bounded pure-literal fixpoint fixes free residual variables to satisfying values. Only
feasible trials are retained. Clause-local polishing releases these pins and removes unnecessary
costly values; polishing resumes across slices before the composed verifier checks the full
proposal for publication.
Failed or cancelled trials contribute no infeasibility or optimality proof.

The probe retains its repair session across portfolio slices. Its cumulative
work is charged to the owning arm, including initialization, and slice cancellation
pauses the current traversal without restarting it. The phase receives at most one half of the remaining
solve deadline, capped at 5 seconds and 100,000 work units; each trial also has a
decision cap of at least 20,000 or twice the Boolean variable count. It runs once before ordinary optimization
and is skipped behind an existing shared incumbent or within repair fragments.
