# Local-search state and moves

Local search maintains assignments and graded factor violations. Source witnesses pass
the [portfolio publication contract](portfolio.md#witnesses-and-proof-scope); an
unsuccessful walk supplies no completeness claim.

## Handles and exclusive ownership

Local-search optimization handles retain the live assignment, RNG, factor payloads,
restart progress and incumbent between segments. A slice pauses at an instruction
checkpoint or cancellation poll; an interrupted continuous-candidate check retains its
candidate for the next slice. Moves and restart transitions count as instructions. The
scheduler charges the observed instruction delta, while the solve-wide move allowance is
charged once across slices. Closing or reseeding discards the retained state. Root
refutations remain authoritative; an unsuccessful local-search walk is incomplete. An
active local-search handle acquires exclusive ownership of its solver when opened.
Overlapping handle creation and other search execution throw `IllegalStateException`,
including searches through another session of that solver. Completion, failure and
idempotent close release ownership; a cancelled slice remains paused and retains it. The
owning session rejects assumption changes and warm-state reset until release. Strategy
and restart policy are shared by the solver; legacy streaming draws must be consumed
sequentially, while open streams hold exclusive ownership. Portfolio workers use
separate solvers and close a handle before reseeding it. Optimization handles open
through their session so its assumptions and state apply. Sessions that decline
resumable optimization retain their one-shot improvement stream.

## Projections and preparation

The immutable local-search projection is prepared on demand and reused across draws.
Mixed-pool arms over the same model share it, including ALNS's inner local search.
Objective-bound overlays keep their own projections. Each Boolean, integer and real
variable's occurrence index contains each active factor once, in factor order. A move
updates that invariant once; the invariant accounts for all positions reading the
coordinate, including positions with different presence literals. Assignments, RNGs,
weights and invariant payloads belong to each live state. Resumable handles initialize
large factor sets in batches, retaining the next factor and completed payloads when a
slice expires. Partial costs, samples and warm state remain private until every factor
has been scored. Initial scoring preserves factor order and does not charge search
moves. Projection preparation also retains private invariant and occurrence-index
progress in batches. Matching arms can continue that shared preparation; a mutex
protects each batch and publishes only the complete projection. A suspended arm holds no
lock. Assignment seeding, individual factor calls, restart policy mutations and repair
searches remain atomic work that can overrun a segment. [Portfolio preparation
revisits](portfolio.md#preparation-revisits) schedule unfinished CP and local-search
arms.

## Initialization and implicit table moves

Greedy initialization polls cancellation between variables and retains only completed
coordinate repairs. Payloads, costs, definition propagation and score caches update
normally; neighbor configuration marking and tabu/activity tracking are suppressed
during repair. Its activity epoch resets on completion, cancellation or failure, and
ordinary tracking resumes afterwards. Resumable optimization retains the shuffled
coordinate order and next coordinate across slice cancellation, completing initial and
post-restart repair before entering the search walk. One variable's bounded value probes
remain atomic. Its coordinate eligibility uses the generic move sink's pinned, defined
and implicit-owner filters, preserving seeded globals and one-way definitions during
repair. Implicit table moves select values from each support row intersected with root
domains. Interval and wildcard cells remain searchable, including single-row supports;
repeated variables use the intersection of all their cells. Frozen coordinates retain
their values; tables reading defined integers decline implicit seeding and moves because
a definition can rewrite them outside the chosen row. An unreachable row produces no
partial move or seed. Built-in initial random restarts apply implicit seeding and the
definition sweep before initializing factor costs, avoiding an evaluation of the
intermediate random assignment. Custom restart policies retain their ordinary restart
protocol. ALNS skips its local-search fallback when the complete-engine bootstrap
returns without an incumbent after cancellation, avoiding fresh seeding after the
deadline. Its terminal statistics distinguish measured bootstrap CP nodes, bootstrap LS
moves, repair CP nodes and repair LS moves from the declared outer repair allowance.
Retained CP repair counters contribute each fragment's difference, not its cumulative
history. Separate wall times include bootstrap construction and seeding and repair
construction and reseeding. These observations do not alter scheduler work or solve-wide
budgets; an allowance alone does not establish useful inner search. Direct construction
and custom repair operators report engine work only when they supply observations.

## Definitions and repairs

Hinted min/max expression outputs, their materialized affine operands and output aliases
form a one-way definition cone. Unhinted min/max globals remain searched. Competing
definitions and cyclic cones remain searched; independent constraints on a defined
output still contribute violations. Evaluation clips values to root domains and
preserves pinned outputs during seeding and moves. Repair proposals into these cones
backsolve affine rows and extrema into admissible searched inputs, respecting domains,
pins and implicit owners. Non-witness operands receive the required bound, allowing
domain gaps and affine rounding. Shared-input repairs recompute every affected
definition before checking the complete maintained cone. Element matching-cell indexes
and range endpoints use Long arithmetic, including offsets whose valid array indexes
cross the Int boundary. Matching-cell scans skip unreachable inverse targets until an
admissible index repair is found.

Native Element violation repairs retain their first reachable matching index and may
attempt two further index repairs from a rotating scan of at most 32 array positions.
Scan progress belongs to each state's Element payload and records proposal history,
including proposals generated during reverted repair chains. Cost recomputation preserves
it; grading probes do not advance it.
Additional targets use ordinary index inverse repairs and complete move grading; nested
Element and literal-channel index definitions retain the first-candidate neighborhood.

### Element results

Hinted native Element results are maintained from their indexes and selected cells.
Competing definitions and cyclic selections remain searched. Value-directed result
repairs change an admissible selected cell or backsolve a matching index through its
source coordinates. Each repair scans at most 32 array positions and offers at most four
alternatives, following at most 16 nested selections. Output and input domains, pins and
implicit owners remain enforced; an out-of-range index or a domain-clamped result leaves
the defining constraint subject to ordinary violation scoring.

The one-way definition network contains immutable reader indexes; matching mixed-pool
arms share its lazy construction while each state applies definitions to its own
assignment.

Native Element result cones skip writes to unselected cells. Direct and transitive
index dependencies retain the result cone, including compounds that change the index
and its destination cell together. Probe saving and propagation use the same cone.

### Affine inverse repairs

Element index repairs backsolve affine definitions with one or two distinct integer
inputs and a unit output coefficient. Hinted affine indexes with one or two inputs
feeding only unit unary equality predicates use the same maintained definitions and
bounded inverse for value-directed repairs. Independent constraints on an index prevent
this inference. Requested outputs and input coordinates respect pins and implicit
owners. Hinted one-input affine chains feeding only such index definitions are
maintained from their searched leaves; other occurrences prevent their inference.
Inverse repairs follow at most 16 such aliases, checking each intermediate domain and
rejecting conflicting targets for a shared leaf. Single-coordinate repairs precede joint
moves; joint repair enumerates at most 64 present members of one input domain. Affine
aliases over retained extrema use the extremum inverse repair so their derived inputs
remain reachable. Input domains, pins and implicit owners filter the complete move
before publication. Equality indicators join coordinate moves and carry admissible
binary channels after protecting all requested coordinates, while arithmetic
counter-shifts cannot overwrite those coordinates. Unsupported definitions, overflow and
nonintegral inverses decline the candidate; an unreachable matching cell does not stop
the remaining element scan.

## Coordinated channels and tuple moves

Value-driven moves carry equality indicator flips through directly connected unit
equality channels over 0/1 integers. Channel targets must be present in the root domain
and pass the generic pinned, defined and implicit-owner filters; a claimed coordinate is
never overwritten. Equality rows coalesce consistency flips for the same indicator;
ordinary repeated Boolean flips are interpreted by their final parity. Product
definitions then maintain their outputs from the coordinated channel changes. Wider
inputs and other row shapes retain their independent repair neighborhoods. Violated
Table rows retain single-coordinate repairs and add at most two joint tuple repairs.
Joint repairs and structured tuple jumps carry equality indicators and binary channels
with every table coordinate protected from counter-shifts. A protected changed
coordinate rejects the entire tuple move, preserving the chosen row's support;
maintained columns decline tuple construction. The ordinary scorer grades the complete
assignment.

Satisfied linear equalities counter-shift the first admissible partner in coefficient
magnitude order whose coefficient divides the driving drift. Targets outside root
domains and protected partners do not prevent later partners from absorbing the drift.

## Cumulative neighborhoods

Cumulative integer moves update every task reading the coordinate as a start,
duration or demand, including aliases introduced by remapping and coordinates shared
with capacity. Shared presence flips grade the combined footprint change. Predicted
deltas and committed grades use the final combined load rather than summing independent
scores against the original timeline.

Cumulative overload repair can remove a maintained product duration through a unit
binary equality channel and a unary equality choice predicate. It checks zero in the
channel and duration domains, pins and implicit owners before proposing a complete
choice-and-channel move. Per repair call, at most 16 such moves are drawn from at most
32 present values of each choice domain, with at most four alternatives per predicate.
The starting peak task advances per worker so early candidates cannot consume every
call's budget. Retained conditional durations also admit up to four atomic choice swaps
per repair call, with at most 32 task-pair attempts. The partner can have zero duration
in the overloaded resource; matching equality predicates and literal polarity identify
its category choice. Per-worker cursors advance the task pairs. Domains, pins and owners
protect both choices, channels and durations, and the complete move is scored across
destination resources. Start-time repairs skip absent tasks and tasks with zero duration
or demand; present positive-footprint tasks retain neighboring and sampled targets
outside the maximum peak. The ordinary scorer grades every affected factor; removing a
footprint does not establish that the complete assignment is feasible.

### Move ranking and plateau feedback

During infeasibility, weighted and raw move ranking interpolates Cumulative overloads
within their compressed grade buckets. Each factor contributes a fraction below one
grade unit, scaled by its weight for weighted scoring. A complete reversible probe
grades propagated and compound moves; integer costs and feasibility checks retain their
existing meaning. Interpolation and primitive delta predictions use the same saturated
payload as committed Cumulative grades. Weight and plateau-stall schedules recognize a
lower unweighted fraction sum when integer costs tie. Integer-cost drops take
precedence, and restarts re-anchor both components. Recipes using break scoring retain
integer-only stall feedback.

During infeasibility, root repairs can also preserve satisfied variable-array Element
results by coordinating admissible destination cells. Each candidate collection probes
at most four variants over at most 32 selected factors. Conflicts, pins, ownership and
defined cells reject a variant; original candidates remain available, and complete
compound scoring checks every affected constraint.

Opt-in Cumulative residual snapshots prioritize the start,
duration and demand coordinates of tasks participating in the maximum overload,
retaining coordinate bounds and omission counts.

## Maintained literal channels

Domain-aware definition inference derives hinted unit equality channels only when their
source bounds are within 0/1. A channel's predicate is derived only from a unique unit
unary equality whose other Boolean occurrences are channels. Mixed integer and Boolean
definitions are ordered together; competing outputs and cyclic cones stay searched.
Pinned values survive evaluation, and clipped channels retain their factor violations
without replacing a maintained predicate during seeding. Implicitly owned integer
outputs remain unchanged during per-move propagation; the affected definition factors
retain any resulting violations. Diversification kicks also exclude maintained Boolean
outputs from direct flips. Repairs into retained literal channels change an admissible
Boolean input or backsolve a retained unary equality into a searched integer or an
existing extremum inverse. When a Boolean predicate remains searched, unit unary
equality sources supply bounded coordinated choice moves before a direct flip; all other
constraints and duration fanout remain graded. These repairs check output domains, pins
and implicit owners; false equality targets sample at most four alternatives from at
most 32 present values, including bounded walks through larger domains. Literal chains
stop at depth 16. The Cumulative duration neighborhood uses the same inverse route.
Clause, cardinality and weighted Boolean-row repairs backsolve maintained equality
predicates through their integer inputs after binary lowering removes the integer
channels. Substituted integer pins remain in place; source-coordinate pins and owners
still filter every inverse move. Functional objective inference declines cones crossing
literal-to-integer channels; ordinary objective scoring still grades the complete
maintained assignment. The portfolio's ALNS inner local search shares the definition
sweep and immutable network used by ordinary LS workers using invariants. Boolean
break/make vectors initialize on their first score query and are maintained
incrementally thereafter; strategies that do not query them avoid their initialization
pass.

## Scoring and probe restoration

Moves feeding definitions score the complete propagated assignment, including derived
factor degrees and objective coordinates. Compounds without definition readers retain
additive linear objective scoring for distinct coordinates and the objective's own
incremental delta; repeated-coordinate linear deltas use a probe of the final values.
Probes apply compound inputs together before one definition pass, save affected
input/output values, and restore those values directly. Each probe reuses its definition
cone for saving and propagation. An inverse definition that cannot write does not strand
a derived output. Probe activity is suppressed and its best-cost observation is
discarded. Real moves in probes and restoration do not advance the committed row-refresh
cadence. Weighted probes snapshot and scan only factors whose degrees change, in
factor-id order to retain the full-scan floating-point accumulation order. Committed
moves reconcile cost and violated membership from exact post-move degrees. Factor data
and engine projections are separate: the local-search projection supplies an
`Invariant`, while mutable payloads belong to each `LocalSearchState`.

ArgminJump constructs coordinated integer targets and maintained-Boolean inverse repairs
through the generic move sink before comparing their complete weighted deltas. Pins,
definitions and implicit owners filter candidates before ranking. Each sampled coordinate
contributes at most one strict weighted improvement over staying put; the selected move
is emitted without regenerating repair alternatives. Small integer domains are enumerated
and wider domains retain their sampled-target cap. Inverse repairs and Element destination
variants retain the move sink's existing bounds.

### Comparison clauses

Comparison-clause integer scores substitute the target in every matching literal,
including repeated variables, and retain the minimum saturated comparison shortfall.
Violated comparison clauses offer admissible literal bounds in addition to neighboring
values. Inequalities round toward the satisfying side of domain holes; unreachable
equalities are declined. Targets use coordinated channeling and inverse repairs, with
pinned and implicitly owned source coordinates protected before synthesis.

When zero satisfies a violated literal on a maintained conditional product, the clause
can add at most two coordinated product repairs through binary equality channels and
admissible source choices. Ordinary bounds and neighbor candidates remain available.
Output and input domains, pins, ownership and definitions continue to filter these
repairs; all affected constraints contribute to compound grading. False equality choices
can sample the RNG, and existing Element-preserving variants share their collection allowance.

Candidate `deltaIf*` methods return the exact degree change. Ordinary `apply*` methods update
payloads after the assignment changes; their return values are implementation-specific
and callers must read the post-move degree for scoring. Reified linear invariants offer
an internal fused payload-update and exact-degree path. Other invariants update payloads
and then read their degree, independently of the returned apply value.

## Restart anchors

Optimization restart policies receive only accepted feasible incumbents. Before an
incumbent, their random-restart fallback can diversify the feasibility walk. Residual
violation improvements contribute telemetry without becoming restart anchors. Published
samples and samples retained by custom restart policies remain independent of subsequent
updates.

Satisfaction draws, sample/enumeration streams and resumable satisfaction share the same
restart-anchor lifecycle. Fixed-cadence and Luby policies ignore the anchor and retain no
assignment copy. Anchoring and custom policies retain the exact best assignment in private
packed storage, copying only on strict cost improvement and resetting after a published
sample. Each consuming restart receives a fresh expanded sample so policy mutation cannot
alter the retained anchor or live assignment. Initialization and the restart immediately
after publication receive null. Anchor capture and expansion consume no RNG draws or
search instructions. Anchor capture and expansion remain ordinary loop work outside the
restart seeding timer.

## Phase timing

Opt-in `ls-phase-times=true` portfolio diagnostics report active local-search nanoseconds
for preparation, seeding, move selection, application and other work. Preparation covers
projections, state and definition installation; seeding includes restart reconciliation
and greedy repair. Selection includes proposal generation and reversible scoring probes.
Suspended turns do not accrue time, and nested phases are charged once. ALNS inner work
uses its separate bootstrap and repair counters. Diagnostic timing is disabled by default.

## Residual diagnostics

Optional local-search residual reporting observes committed assignments at loop and
publication boundaries. Strict cost improvements retain a fresh per-kind sum of the
maintained factor degrees, paired with their exact total. Probes are not observed.
Reified linear categories partition their total by comparison operator and input shape:
one input bounded to 0..1 (`binary`), another single input (`unary`), or multiple inputs
(`multi`). Binary input bounds do not establish channel semantics. Each observation
includes up to eight highest-degree representative factors, one per kind. Each
representative retains its degree and up to sixteen distinct Boolean and sixteen
distinct integer values from that committed assignment, with exact distinct-coordinate
omission counts. Element representatives prioritize the index, result and selected
variable-array cell, using the lowered factor's index offset. Invalid indexes retain
the index and result without a selected cell; constant arrays contribute no cell
coordinate. Factor and variable ids belong to the local-search model after lowering
and presolve. Worker
aggregation selects one whole observation at the lowest total; it never adds breakdowns
from different assignments. A zero residual does not establish domain, completion or
source feasibility, and the observation does not replace an incumbent. Disabled
reporting allocates no recorder and performs no factor scans.
