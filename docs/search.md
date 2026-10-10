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

Dom/wdeg initializes from the session's prepared occurrence indexes. Failure weights
and its heap belong to the solve, retain conflict bumps across restarts, and apply bumps
received before the first selection once during initialization. Fresh selectors isolate
solves that share a projection.

Propagators supply sound source
reasons for deductions and conflicts. An assignment's undo lifetime can be deeper
than the effective level of its reason: Boolean pins use the deeper of that level
and the current decision depth so an asserting backjump retains its consequence.

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

General CP optimization handles open with private preparation. Propagator projection
and root propagation advance in batches of 256 factors, retaining the queue, watcher
changes and deductions between turns. A paused root is not a search session and cannot
publish a sample or refutation. Completion installs its fixpoint or derived conflict
once, then enables the ordinary trail and branch-and-bound search. Propagator objects
remain private to the handle. Preparation work, fractional charges and overspend debt
carry into search without being charged again; the stats sink retains its timing window.
A cancellation between root fires pauses the private queue without marking the
fixpoint unusable. Final assumption seeding and search installation can abort
initialization, and their partial state cannot resume or publish a result.
The packed native-SAT optimization path and satisfaction/repair construction retain
their constructor lifecycle. State allocation, occurrence indexes, individual factor
calls, assumption seeding and LP construction remain atomic work.

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
Keep the owning session's assumptions and warm state unchanged until release.
Strategy and restart policy are shared by the solver, so streaming draws must also
be consumed sequentially. Portfolio workers use separate solvers and close a handle
before reseeding it.
Optimization handles open through their session so its assumptions and state apply.
Sessions that decline resumable optimization retain their one-shot improvement stream.

The immutable local-search projection is prepared on demand and reused across draws.
Mixed-pool arms over the same model share it, including ALNS's inner local search.
Objective-bound overlays keep their own projections. Assignments, RNGs, weights and
invariant payloads belong to each live state. Resumable handles initialize large
factor sets in batches, retaining the next factor and completed payloads when a
slice expires. Partial costs, samples and warm state remain private until every
factor has been scored. Initial scoring preserves factor order and does not charge
search moves. Projection preparation also retains private invariant and occurrence-index
progress in batches. Matching arms can continue that shared preparation; a mutex protects
each batch and publishes only the complete projection. A suspended arm holds no lock.
Assignment seeding, individual factor calls,
custom restart policies, subsequent restarts and repair searches remain atomic work
that can overrun a segment.
Before the first incumbent, an optimization arm with pending private preparation or
a local-search arm that has not executed an instruction
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
Element index repairs backsolve affine definitions with one or two distinct integer
inputs and a unit output coefficient. Hinted one-input affine chains feeding only
such index definitions are maintained from their searched leaves; other occurrences
prevent their inference. Inverse repairs follow at most 16 such aliases, checking each
intermediate domain and rejecting conflicting targets for a shared leaf.
Single-coordinate repairs precede joint moves; joint repair enumerates at most 64
present members of one input domain. Affine aliases over retained extrema use the
extremum inverse repair so their derived inputs remain reachable. Input domains,
pins and implicit owners filter the complete move before publication. Equality indicators
join coordinate moves and carry admissible binary channels after protecting all
requested coordinates, while arithmetic counter-shifts cannot overwrite those
coordinates. Unsupported definitions, overflow and nonintegral inverses decline the
candidate; an unreachable matching cell does not stop the remaining element scan.
Value-driven moves carry equality indicator flips through directly connected unit
equality channels over 0/1 integers. Channel targets must be present in the root domain
and pass the generic pinned, defined and implicit-owner filters; a claimed coordinate
is never overwritten. Equality rows coalesce consistency flips for the same indicator;
ordinary repeated Boolean flips are interpreted by their final parity.
Product definitions then maintain their outputs from the coordinated channel changes.
Wider inputs and other row shapes retain their independent repair neighborhoods.
Cumulative overload repair can remove a maintained product duration through a unit
binary equality channel and a unary equality choice predicate. It checks zero in the
channel and duration domains, pins and implicit owners before proposing a complete
choice-and-channel move. Per repair call, at most 16 such moves are drawn from choice
domains with at most 32 present values, with at most four alternatives per predicate.
The ordinary scorer grades every affected factor; removing a footprint does not
establish that the complete assignment is feasible.
Domain-aware definition inference derives hinted unit equality channels only when
their source bounds are within 0/1. A channel's predicate is derived only from a unique
unit unary equality whose other Boolean occurrences are channels. Mixed integer and
Boolean definitions are ordered together; competing outputs and cyclic cones stay
searched. Pinned values survive evaluation, and clipped channels retain their factor
violations without replacing a maintained predicate during seeding.
Implicitly owned integer outputs remain unchanged during per-move propagation;
the affected definition factors retain any resulting violations.
Diversification kicks also exclude maintained Boolean outputs from direct flips.
Repairs into retained literal channels change an admissible Boolean input or backsolve
a retained unary equality into a searched integer or an existing extremum inverse.
When a Boolean predicate remains searched, unit unary equality sources supply bounded
coordinated choice moves before a direct flip; all other constraints and duration
fanout remain graded.
These repairs check output domains, pins and implicit owners; false equality targets
sample at most four alternatives from at most 32 present values, and literal chains
stop at depth 16. The Cumulative duration neighborhood uses the same inverse route.
Functional objective inference declines cones crossing literal-to-integer channels;
ordinary objective scoring still grades the complete maintained assignment.
The portfolio's ALNS inner local search shares the definition sweep and immutable
network used by ordinary LS workers using invariants.
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
Optional local-search residual reporting observes committed assignments at loop and
publication boundaries. Strict cost improvements retain a fresh per-kind sum of the
maintained factor degrees, paired with their exact total. Probes are not observed.
Reified linear categories partition their total by comparison operator and input
shape: one input bounded to 0..1 (`binary`), another single input (`unary`), or
multiple inputs (`multi`). Binary input bounds do not establish channel semantics.
Worker aggregation selects one whole observation at the lowest total; it never adds
breakdowns from different assignments. A zero residual does not establish domain,
completion or source feasibility, and the observation does not replace an incumbent.
Disabled reporting allocates no recorder and performs no factor scans.

Optimization portfolios can reseed stale resumable arms after an incumbent;
`reseed-stale-threshold` defaults to 3 non-improving segments, with 0 disabling it.
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

See [SliceBudget](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/SliceBudget.kt)
and [portfolio](../klause/src/commonMain/kotlin/com/eignex/klause/portfolio/).

## Statistics

| Field | Meaning |
|---|---|
| `propagationWork` | Monotonic dispatch, literal, watcher, level and linear-term/reason visits, including work across undo |
| `rootPropagationWork`, `rootPropagationMs` | Initialization root-fixpoint work and time, including private preparation |
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

`initMs` measures opening the handle. Preparation deferred into slices contributes to
segment time and live propagation counters; it is not included in opening time.
