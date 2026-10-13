# Local-search state and moves

Transcendental sources use the shared exact-neighborhood move owner with
original-source function completion. Rational windows and approximate centers
guide proposals; checked symbolic real expressions carry coordinate authority.
Neighborhood failures cannot refute an unrestricted source. See
[transcendental real arithmetic](../theories/transcendental.md).

Local search searches for feasible or improving assignments by changing values and
measuring constraint violations. It keeps incremental scores so a candidate move
can be evaluated without rebuilding the whole model. Defined outputs and coordinated
moves keep related coordinates consistent as the walk progresses.

A run prepares its model projection, initializes an assignment, generates and scores
moves, accepts selected changes, and restarts when needed. Candidate publication
checks every required source constraint, including arithmetic completion. A low
score guides the walk; it does not prove feasibility, optimality or infeasibility.
Propagation-only custom factors receive a private full-coordinate check before
caller completion or publication; see [custom factor checks](search.md#custom-factor-publication-checks).

The sections follow preparation, move mechanics and run lifetime, then diagnostics.
[Search](search.md) owns complete traversal; [sessions](sessions.md) owns caller
scopes; [portfolios](portfolio.md#witnesses-and-proof-scope) owns result admission.

## Projections and preparation

The immutable local-search projection is prepared on demand and reused across draws.
Mixed-pool arms over the same model share it, including ALNS's inner local search.
Objective-bound overlays keep their own projections. Each Boolean, integer and real
variable's occurrence index contains each active factor once, in factor order. A move
updates that invariant once; the invariant accounts for all positions reading the
coordinate, including positions with different presence literals. Assignments, RNGs,
weights and invariant payloads belong to each live state. Immutable per-factor integer
lookups store their arrays on one owner, shared with the projection. Compact key ranges
use offset arrays; sparse ranges use primitive probing with storage bounded by entry
count. Duplicate keys retain their last payload, while occurrence indexes separately
account for aliased positions. These lookups allocate no mutable per-worker cache.

Resumable handles retain
private initialization cursors for state buffers,
assignment loading or randomization, implicit seed election and ownership, definition
readers and evaluation, warm weights and activity, greedy coordinate order, and factor
payloads. Cancellation is polled before each retained step. Explicit loops advance in
batches of at most 256 coordinates, words, factors or reader edges; implicit seeding
polls between individual globals. Random Boolean words precede integer draws, followed
by anchored integer draws, pins and real starts. Suspension draws no additional random
values and does not repeat completed work. A supplied assignment consumes no random
initialization draws. Satisfaction preserves its supplied assignment; optimization
reconciles supplied definitions before scoring. Partial assignments, costs, samples,
residuals and warm captures remain private until all initial factors are scored and the
initial greedy pass completes. Preparation consumes time but no search instructions or
solve-wide move allowance.

Projection preparation retains each buffer allocation, occurrence-array installation
and count/stamp reset as well as factor visits in private batches. Matching arms also share retained definition-reader preparation. A mutex
protects each shared step and publishes only complete indexes; a suspended arm holds no
lock. Closing a handle releases its private cursor and exclusive solver ownership.
Completed shared preparation remains reusable. Invocation cancellation pauses a cursor;
bound lifetime cancellation applies to every later slice. [Portfolio preparation
revisits](portfolio.md#preparation-revisits) admit every initial sibling before extra
visits.

These boundaries are cooperative, not hard elapsed-time bounds. Every primitive array
allocation still zeroes one complete buffer atomically: packed Boolean words, integer
and real assignments, violation membership and positions, each factor payload/degree
buffer, tabu/activity, Boolean scores, configuration/probe flags, definition masks/counts
and reader arrays, implicit owners, seed sort scratch, warm weights and greedy order.
Array copies and growable-list/map resizing also remain atomic. They can trigger runtime
allocation or collection delays; polling occurs between these primitives, before the
whole state exists. One factor projection/occurrence visit, definition evaluation, reification row, factor initialization,
implicit global seed or bounded coordinate repair can also overrun a segment. Root
support/pin checks, definition inference performed by the caller, custom restart policy
mutations, later restart reconciliation, lazy first-use scoring indexes and activity
reset remain atomic outside the retained initial buffer/assignment/definition cursors.

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

ALNS adjacency destruction follows first-discovery breadth-first order through active
factor occurrences. Each finite variable enters the frontier once and each encountered
factor expands once. Frontier storage is bounded by the finite variable count, with
variable and factor visit masks owned by the destruction call. Overlapping factors retain
the same selected neighborhood and disconnected components retain random reseeding.

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
The primary-repair plateau recipe collects supplemental repairs separately. Productive
primary candidates retain priority; when none improves the complete score, strictly
better supplemental candidates enter its score-only pool. Other recipes and direct
repair consumers retain the complete bounded neighborhood. Recipe copies preserve the
primary-repair policy.

The source-driven strategy generates candidates into its retained pools before its
selection helper applies configuration and tabu filters, supplemental scoring and
acceptance. Generation and selection keep their existing candidate order and RNG
cadence; this boundary adds no pool copies or scratch owners.

CBLS and optimizing SA reserve the first satisfied-factor draw for an elected
structural global, then sample the remaining factors uniformly from the model.
The total draw cap is unchanged; zero draws perform no election, and an empty
structural set retains uniform sampling. Election uses the existing immutable
per-state implicit factor set without seeding or claiming coordinates. Its first
access scans the projection once within the atomic source call. Each drawn factor
must be satisfied and proposes with its own sink owner attribution; complete move
scoring retains feasibility and objective authority. Explicit uniform and elected
source scopes keep their separate selection policies.

Tabu aspiration with the improving criterion admits a move that lowers violations.
At feasibility it also admits a strictly improving objective move whose complete
model delta is zero, independently of the objective shaping coefficient. Equal or
worse objectives, unsupported objective deltas and moves introducing violations
retain their tabu restriction. This permits monotone envelope descent after a
recent coordinate write; tabu cannot trap a feasible improving compound behind
unrelated zero-objective candidates.

Discrete linear objective deltas accumulate in a retained per-state integer sum,
using checked 64-bit arithmetic and arbitrary precision on overflow. Compound and
definition probes round that complete discrete sum once, preserving cancellation
and the direction of wide coordinate changes. Functional objective views retain
exact weighted cone totals and subtract them before conversion to floating point.

### Element results

Hinted native Element results are maintained from their indexes and selected cells.
Competing definitions and cyclic selections remain searched. Value-directed result
repairs change an admissible selected cell or backsolve a matching index through its
source coordinates. Each repair scans at most 32 array positions and offers at most four
alternatives, following at most 16 nested selections. Output and input domains, pins and
implicit owners remain enforced; an out-of-range index or a domain-clamped result leaves
the defining constraint subject to ordinary violation scoring.

The one-way definition network contains immutable reader indexes; matching mixed-pool
arms share its retained construction while each state applies definitions to its own
assignment.

Each state owns reusable move-seed lists and a lazily allocated traversal workspace
bound to its current network. The workspace marks each affected node once, visits its
readers, then sorts its live node prefix into exact topological order. It retains no
assignment-dependent cone between moves. Replacing a state's network discards its
workspace. Public cone queries retain their sparse traversal and return independent arrays;
internal live prefixes are
borrowed only until the synchronous move or probe returns and never enter snapshots.
Coordinate saving retains each touched Boolean, integer or real coordinate once,
including defined outputs, and restores them through the incremental primitives.
Reentrant move application, reversible probing, objective scoring or network replacement
on the same state throws before mutating live scratch, including callbacks during the
initial objective evaluation. Objective failures and cancellation
unwind the ordinary probe restoration; subsequent calls reuse cleared traversal marks.
These operations remain atomic within the existing cancellation boundaries.

Repair-chain preparation captures activity, configuration and assignment snapshots;
its execution phase borrows those snapshots for the walk and ordinary restoration.
This phase boundary retains effect, callback and random-draw order.

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

## Maintained literal channels

Domain-aware definition inference derives hinted unit equality channels only when their
source bounds are within 0/1. A channel's predicate is derived only from a unique unit
unary equality, disequality or ordered comparison whose other Boolean occurrences are
channels. Negative unit coefficients reverse ordered comparisons; unrepresentable
negated bounds and inputs admitting `Long.MIN_VALUE` decline that normalization. Mixed integer and Boolean
definitions are ordered together; competing outputs and cyclic cones stay searched.
Pinned values survive evaluation, and clipped channels retain their factor violations
without replacing a maintained predicate during seeding. Implicitly owned integer
outputs remain unchanged during per-move propagation; the affected definition factors
retain any resulting violations. Diversification kicks also exclude maintained Boolean
outputs from direct flips. Repairs into retained literal channels change an admissible
Boolean input or backsolve a retained unary predicate into a searched integer or an
existing extremum inverse. When a Boolean predicate remains searched, unit unary
equality sources supply bounded coordinated choice moves before a direct flip; all other
constraints and duration fanout remain graded. These repairs check output domains, pins
and implicit owners; false equality targets sample at most four alternatives from at
most 32 present values, including bounded walks through larger domains. Literal chains
stop at depth 16. The Cumulative duration neighborhood uses the same inverse route.
Clause, cardinality and weighted Boolean-row repairs backsolve maintained equality
predicates through their integer inputs after binary lowering removes the integer
channels. Substituted integer pins remain in place; source-coordinate pins and owners
still filter every inverse move. Ordered predicate inverses round toward the requested
truth through root-domain holes and reject unreachable thresholds, including strict
complements at signed endpoints. Equality and disequality inverses retain the bounded
alternative walk.

Functional objective inference follows admitted unary predicates, literal channels,
Boolean folds and arithmetic definitions across both coordinate spaces in their shared
topological order. Integer and Boolean leaves remain separate; mixed objective terms
and polarity retain their source meaning. Unsupported reachable definitions decline
functional inference. The objective computes the defining expressions for shaping;
domain-clipped, pinned or owned outputs retain ordinary constraint violations and do
not establish source feasibility. Complete propagated probes reconcile predicted and
committed objective deltas. The portfolio's ALNS inner local search shares the definition
sweep and immutable network used by ordinary LS workers using invariants. Boolean
break/make vectors initialize on their first score query and are maintained
incrementally thereafter; strategies that do not query them avoid their initialization
pass.

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

A maintained unary affine definition with zero offset, unit output coefficient and
positive scale can invert exact zero or scale targets through a maintained binary
literal channel. Domain, pin and owner checks protect both outputs and source choices;
the existing literal inverse retains its depth and alternative bounds. Other affine
forms keep their ordinary inverse path. This repair route does not widen definition
inference admission.

Satisfied linear equalities counter-shift the first admissible partner in coefficient
magnitude order whose coefficient divides the driving drift. Targets outside root
domains and protected partners do not prevent later partners from absorbing the drift.

## NValue neighborhoods

Satisfied NValue factors supplement count-preserving coordinate moves with bounded
value-class elimination. A proposal removes one distinct value, moving every present coordinate of one class to
an existing value admitted by every participating root domain and reconciles the
count for its equality or inequality mode. Repeated coordinates are changed once;
count aliases are checked against the final distinct values. Frozen, maintained or
foreign-owned changed coordinates decline the whole class. Absent positions do not
restrict a class unless they share a participating coordinate.
Elimination declines factors reading maintained integers or presence literals, since
changing a searched input can rewrite another class or its presence.

Each call samples at most 24 class pairs and offers at most four elimination moves,
each changing at most 32 class coordinates plus the count and its channels. Channel
contributions protect all NValue coordinates from counter-shifts and carry equality
indicators and binary channels. Proposals changing presence literals decline. Complete
model scoring and ordinary source publication checks remain required; NValue
satisfaction alone establishes neither feasibility nor objective improvement.

At a feasible assignment, the sampled elimination with the least positive whole-model
cost delta may also use the existing directed repair-chain mechanism to reconcile
regressed surrounding constraints. At most one completion follows at
most eight repair steps, protects the changed class and count coordinates, and offers
only a complete zero-violation proposal with at most 128 primitive parts. Class
coordinates carry indicators together; arithmetic rows reconcile their combined drift
through repairs instead of independent per-coordinate counter-shifts. The count retains
its ordinary channel contributions. Chain generation restores the original assignment
and tracking state before complete objective scoring.

## Cumulative neighborhoods

Cumulative integer moves update every task reading the coordinate as a start,
duration or demand, including aliases introduced by remapping and coordinates shared
with capacity. Shared presence flips grade the combined footprint change. Predicted
deltas and committed grades use the final combined load rather than summing independent
scores against the original timeline.

Overload start swaps filter on the joint raw profile change, including every aliased
role of either coordinate. The filter applies and restores only affected footprints,
without copying the timeline; capacity aliases also rescan the retained profile.
At most four swaps are offered, preserving the peak-task/partner neighborhood. Both
starts must be in each other's root domains and pass pins, definitions and implicit
ownership checks. The ordinary scorer grades the complete move and maintained outputs
across every affected factor.

Cumulative overload repair can remove a maintained product duration or its zero-offset
positive scaled binary affine form through a unit binary equality channel and a unary
equality choice predicate. It checks zero in the channel and duration domains, pins and
implicit owners before proposing a complete choice-and-channel move. Per repair call,
at most 16 such moves are drawn from at most
32 present values of each choice domain, with at most four alternatives per predicate.
The starting peak task advances per worker so early candidates cannot consume every
call's budget. Retained conditional durations also admit up to four atomic choice swaps
per repair call, with at most 32 task-pair attempts. The partner can have zero duration
in the overloaded resource; matching equality predicates and literal polarity identify
its category choice. Per-worker cursors advance the task pairs. Domains, pins and owners
protect both choices, channels and durations, and the complete move is scored across
destination resources. Start-time repairs skip absent tasks and tasks with zero duration
or demand; present positive-footprint tasks retain neighboring and sampled targets
outside the maximum peak. Per-task target emission borrows the current root domain,
peak coordinates and move sink; neighboring, enumerated and random targets retain
their order. The ordinary scorer grades every affected factor; removing a
footprint does not establish that the complete assignment is feasible.

## Diffn neighborhoods

Satisfied Diffn layouts offer up to four adjacency, boundary compaction and paired
reordering/translation moves, alongside equal-footprint swaps. Relocations sample
at most eight rectangles and check at most 24 nonempty admissible targets. Joint
moves apply both positions before checking geometry; their independent writes need
not preserve packing. Root-domain holes, pins, definitions and implicit owners
filter every changed coordinate before emission. Repeated coordinates coalesce
only when their targets agree. Geometry reads every aliased position and variable
size at its final target, and declines moves affecting more than eight rectangles.
Overflowing adjacency targets decline; geometry compares mathematical endpoints
beyond the Long range without saturating them onto a searched coordinate. Strict
and nonstrict zero-size semantics match the ordinary invariant. Equal-footprint
swaps require independent position
coordinates that do not alias dimensions.

An admitted relocation can reconcile up to two decreasing scalar envelopes outside
its Diffn scope. Discovery inspects at most 128 neighboring factor occurrences;
completion shares per-call limits of 16,384 factor visits and row-term scans.
Integral linear inequalities with a unit negative output coefficient supply exact
lower targets after the geometry writes. Root-domain ceilings retain holes and
all coordinate eligibility filters remain enforced. Overflowing long row arithmetic
uses exact integer evaluation; unsupported negative output coefficients or exhausted
work decline that envelope. These are private candidate values, not deductions or
published bounds. Every resulting compound receives complete model scoring.

Geometry checks only establish the proposing Diffn's satisfaction. Ordinary complete
move scoring, definition propagation and source publication checks remain responsible
for all other model constraints and the objective. Candidate generation consumes
bounded work within an atomic factor call; it adds no search instruction allowance.

### Move ranking and plateau feedback

During infeasibility, weighted and raw move ranking interpolates Cumulative overloads
within their compressed grade buckets. Each factor contributes a fraction below one
grade unit, scaled by its weight for weighted scoring. A complete reversible probe
grades propagated and compound moves; integer costs and feasibility checks retain their
existing meaning. Primitive moves without definition readers or Cumulative occurrences
use direct degree scoring. Their weighted repair score equals the raw delta until
weights are allocated. Interpolation and primitive delta predictions use the same saturated
payload as committed Cumulative grades. Weight and plateau-stall schedules recognize a
lower unweighted fraction sum when integer costs tie. Integer-cost drops take
precedence, and restarts re-anchor both components. Recipes using break scoring retain
integer-only stall feedback.

During infeasibility, root repairs can also preserve satisfied variable-array Element
results by coordinating admissible destination cells. Each candidate collection probes
at most four variants over at most 32 selected factors. Conflicts, pins, ownership and
defined cells reject a variant; original candidates remain available, and complete
compound scoring checks every affected constraint.

Cumulative repair generation separates peak preparation from proposal emission.
The emission helper receives the borrowed payload and prepared peak coordinates
and tasks. Conditional-duration proposals precede start proposals and swaps,
keeping task, target and random-draw order.

Opt-in Cumulative residual snapshots prioritize the start,
duration and demand coordinates of tasks participating in the maximum overload,
retaining coordinate bounds and omission counts.

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

### Linear repairs

Linear repairs offer nearest-clamped targets in term order, retaining partial
interior and endpoint repairs. Inequality repairs then append distinct moving targets
rounded through domain holes toward the requested predicate truth, using coefficient
sign to select the direction. The added targets must attain that truth. Both target
sets use the same bounded term sample on wide ordinary Linear rows.
Reified repairs offer valid nearest-clamped candidates in indicator and term order,
then offer distinct rounded alternatives. The move sink groups primitives before
compounds when exposing candidates. Body targets must match the retained or flipped
indicator. Equality and disequality retain their nearest-value behavior.

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

## Continuous candidate completion

A finite projection can use `CandidateCompletion` when the source also requires
arithmetic coordinates, even if its assignment workspace has no continuous buffer.
The completion service checks a pinned finite candidate
against those constraints before publication; a rejected or indeterminate candidate
cannot refute the source model. Completed samples retain authoritative coordinates
and source scope rather than substituting the local-search score for a witness check.

Linear completion retains a worker-private conditioned query owner. Boolean pins
and arbitrary-width integer equalities use the prepared source plan's arithmetic
route; continuous coordinates remain theory-owned. Native work charges each
candidate's delta against the same containing ledger. Workers share immutable
planning data, and each worker owns its mutable completion. Operation and handle
closure dispose the private arithmetic state.

[Theory contracts](../theories.md) define the supported graph, linear and polynomial
checks. Their source semantics and resource ledgers remain authoritative across
candidate retries. Completion coordinates are distinct from the finite factors
that guide the walk.

Standalone LS checks covered constraints through their invariants. A propagation-only
custom factor mapped to `NoInvariant` instead receives a private
[full-coordinate finite check](search.md#custom-factor-publication-checks) before
caller completion. Zero score alone cannot publish its candidate. An uncovered
continuous constraint explicitly declines finite local-search execution.

## Exact coordinate neighborhoods

The retained exact-coordinate driver proposes rational coordinate windows and
Boolean pins to a source-bound completion adapter. A coordinate repair relaxes one
pin; compound repairs relax a block. Adaptive neighborhood search weights the
block sizes by accepted improvements. Centers, window radii, random state, weights
and incumbents survive slices. A rejected repair widens search windows without
changing the source domain or establishing infeasibility.

The linear adapter prepares source decomposition once and asserts registered exact
rational bounds and strict objective cutoffs into a private retained theory search.
It retains arbitrary-width integers and checks source witnesses before publication.
Query assertions retract after every verdict or interruption; sibling and nested
conditions cannot inherit a witness, bound or refutation from another scope.
Native work, decisions
and legacy checks share one allowance across repairs; completion work also charges
the heuristic instruction ledger. Exact objective cutoffs require strict source
improvement. Only checked completions publish witnesses or best-found incumbents.
Repair refutations and exhausted windows carry no source proof authority.

Generated linear neighborhoods initialize their private completion root before
preparing a Boolean center. Known root values replace the default false values;
free Booleans keep that default. The copied prefix stays private across slice
interruptions, and no generated condition is checked before the copy completes.
Each Boolean visit spends heuristic instructions and the shared move allowance;
root preparation retains the completion owner's native work and deadlines.
Explicit caller conditions retain their pins and can contradict root facts.
Root values guide proposals only; complete source checks still authorize witnesses.

The quadratic source adapter uses the same condition/completion contract with
exact algebraic objective ordering. Single-coordinate releases and adaptive block
releases repair finite assignments and continuous windows together. Native repair
work also spends heuristic instructions; finite repair decisions retain their
native decision charges. Checked neighborhood optima remain best-found source
incumbents. See [quadratic strategy execution](../theories/polynomial-real.md#quadratic-strategy-execution).

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

[LocalSearchEngine](../../klause/src/commonMain/kotlin/com/eignex/klause/localsearch/LocalSearchEngine.kt)
owns model preparation, exclusive acquisition, strategies and restart policies.
Each retained handle owns its cursor, terminal verdict and idempotent release through
[LocalSearchRun](../../klause/src/commonMain/kotlin/com/eignex/klause/localsearch/LocalSearchRun.kt).
Concrete satisfaction and optimization handles advance the existing streams.
`LocalSearchRunControl` combines lifetime and segment cancellation and retains the
instruction checkpoint; `LocalSearchProgress` owns residual observations, warm-state
capture and incremental solve-wide charging at stream checkpoints. Assignment buffers,
RNG and unfinished initialization belong to the retained state. Model projections and
complete immutable preparation indexes can be shared; mutable run progress cannot.

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

Residual factor details retain up to sixteen ordered ComparisonClause literals or
linear terms, their omission count, comparison and bound. Integer Linear and
ReifiedLinear activities come from the committed grader payload; unsupported
constant or grader shapes are tagged explicitly. Captured coordinates also identify
their maintained definition kind and up to sixteen direct input references with an
omission count, without traversing dependency cones. These references are lowered
model coordinates, not source identities. The existing `ls-residuals` opt-in emits
these additive CLI details; disabled reporting leaves them absent.

Detail keys use `literal.<position>=i<id>:<op>:<constant>` and
`term.<position>=<signed-coefficient>:i<id>` in lowered factor order, with
`omittedLiterals` or `omittedTerms`. Row `op` and `bound` retain canonical signs.
`activity=unsupported-grader` marks exact or other graders without an authoritative
Long sum; `row=unsupported-constants` marks wider or real constant shapes without
substituting rounded constants. Each captured `i<id>` or `b<id>` has
`<coordinate>.definition.kind`; `none` means the active network has no maintained
definition, and does not imply writability. Maintained definitions add
`.definition.inputs` (integer references followed by Boolean references, `-` when
empty) and `.definition.omittedInputs`. Inputs are direct declared read sets, not
recursive cones or an assertion that every input affects the current output.
CLI pairs use `lsBestResidualFactorDetails.<factor-id>.<key>`; existing coordinate
pairs retain their format. Captures own detached metadata and never grade or mutate
the assignment. Coordinate-only construction and the legacy constructor/copy
compatibility descriptors remain available; copies retain captured details.

## Selected scoring projections

`ScoringProjectionProvider` selects an invariant factory, scoring participation and
assignment-check authority from immutable factor data. The factory receives the
worker's move domains; built-in linear rows retain exact sums when those domains
can exceed the machine-integer range. A custom factory must preserve the source
relation at zero violation and consistent move deltas for its advertised role.

An explicit inert score contributes no occurrences and keeps its separate source
check obligation. Unsupported scoring refuses construction. Returning `NoInvariant`
for a declared active role is rejected. Captured factories open private mutable
mechanisms for sibling engines; projection selection contains no assignment payloads.

An inert descriptor requires an explicit reason and cannot claim an exact check:
its absent mechanism needs an enforcing peer or separate source certification.

Open heuristic models retain their prepared scoring capture separately from the
neutral source. Scoring preparation and candidate checks consume that capture;
private pinned-point checks retain the selected descriptors after factor selection.
Scheduling windows and dense-timeline admission restrictions apply to built-in
scorers. A selected alternate scorer receives the actual move domains and owns its
own numeric and resource limits.
