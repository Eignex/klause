# 9. Learning and theory completion

Search learns from scoped deduction reasons and asks arithmetic owners to complete
finite assignments. This chapter follows conflict analysis, clause retention and
restarts, then theory completion and primal proposals. Traversal and propagation
are described in [search](08-search.md).

## Explanation lifetime

Propagators supply sound source reasons for deductions and conflicts. An assignment's
undo lifetime can be deeper than the effective level of its reason: Boolean pins use
the deeper of that level and the current decision depth, so an asserting backjump
retains its consequence.

Reasons are built lazily where that is safe. Single-sided linear indicator deductions
record a lazy payload during trailed search, which analysis resolves against the
bounds at the pin by reconstructing them from the undo log; untrailed and two-sided
deductions stay eager. Reasons are built only when a bound actually tightens the live
domain. A reified equality whose target value is a hole keeps that removal as a
premise, because bounds alone cannot justify the conflict.

Mandatory unit-integer `Distinct` deductions retain the Hall source positions in a
lazy payload. Explanation reconstructs their bounds and holes at the deduction's
trail boundary; later carves cannot enter the earlier reason. Offset identities are
immutable, so the premises name only original integer coordinates. Matching failures
retain the deficient Hall set. Within the value-walk allowance, bounds can lift
across values already occupied by that set, and holes inside its union need no premise.

Assumption cores keep the applied seed prefix when propagation refutes it, or the full
assumption set when the tree is exhausted. Cores are jointly refuted with the hard
model and need not be minimal; an interrupted seeding cannot certify the full seed.

An internal opt-in seed projection starts from the historical forcing clause of a
Boolean pin contradicted by the next named assumption. It resolves eager Boolean
antecedents until every remaining literal is negated by an applied named pin, keeping
transitive premises rather than using their decision levels as proof. Root units
must belong to the captured baked model. Integer atoms, lazy or unavailable reasons,
cycles, cancellation and local limits retain the certified prefix. The projection
admits at most 128 literals, 128 resolution steps and 8,192 visits, charged to the
same propagation work and containing token. An empty projection supplies no verdict.

### Table explanations

Table filtering keeps STR2 explanations, built from build-local caches of historical
domains that never cross propagation or undo. The reversible live prefix is committed
once per completed sweep, and sibling branches restore filtering from trailed state.
Ground table deductions record their bound or excluded value at the actual pin's
trail position. Historical cell checks consider only tuples whose target cells fall
in that removal and choose one blocking cell per tuple. Interval deductions and table
conflicts reconstruct the general historical support relation.
Ground relations share a value-to-row index across projection siblings and sessions.
Each value uses a primitive row posting or a tuple mask, whichever needs fewer bytes.
Index construction admits at most 32 MiB of primitive buffers including sort scratch;
larger relations keep the scan path. A reason visits only the target value or bound
range's postings, then checks the deduction's historical domains. Mutable live
prefixes and explanation caches remain state-local.
An internal opt-in conflict analysis can retry one unextended ground-table reason
against the ordinary minimized clause's frozen literals that its original reason
did not introduce. Reconstruction admits at
most 256 rows, 4096 cells, 64 columns and 64 distinct considered premises; bound deductions
with more than 62 historical target holes decline. It retains target-domain scope
premises and chooses only blockers present at the deduction's trail position.
The minimized frontier admits at most 64 literals and the replay at most 4096 atom
and Boolean variables.
The retry runs both minimization passes and replaces an asserting ordinary clause
only for a lexicographically smaller backjump level, LBD or clause size. A local
decline retains ordinary learning. Frontier-dependent reasons belong only to that
analysis and never enter the marker-only lazy memo.
Support-index construction and historical reconstruction poll the state's invocation
token. Interrupted construction publishes no shared index or reason memo. A stopped
lazy reason is unavailable; it cannot become an empty premise list, and conflict
analysis discards its unfinished learned result. A completed immutable index remains
available across state retraction and later invocations.
Several unsupported interior values commit as one domain mutation. Their individual
lazy reasons refer to the pre-batch trail boundary, and each removal retains its
own carve witness for existing and late equality atoms. Undo restores the prior
domain and removes the batch's witnesses together.
Root propagation folds unary Table restrictions with at most 4096 expanded supports,
so local search samples from the same restrictions.

MDD explanations reconstruct full symbol membership at the deduction's trail
position. A cut path cites the bound or interior removal that excludes its symbol;
later carves cannot become premises of an earlier deduction. Weighted path reasons
use the same historical membership.
Each value removal records its symbol. Shared sparse/dense symbol postings select
that symbol's edges for the cut; storage declines retain the indexed graph scan.
Table values and MDD symbols use the same bounded immutable posting representation.

### LP explanations

LP explanations cite source bound witnesses and recursively expanded row, fixing and
cutoff premises, including rows used to substitute continuous terms; an unexpressible
scope withholds the consequence. With LP learning enabled, a bounded deduplicating
pool admits each row tightening as an implication in the persistent CP session, so it
survives undo and propagates in sibling branches. These clauses assume no incumbent
cutoff. See
[LP consumer contracts](17-linear-programming.md#consumers-explanations-and-cuts).

An external fixing enters CP at the active decision depth without a CP factor owner.
Its explicit premises, when supplied, remain its reason. An immediate contradiction
has no factor conflict clause to analyze; conflicts reached by the ensuing fixpoint
use the factor that actually failed. An unrelated preceding factor's context cannot
provide a fixing's level or conflict reason.

## Propagation and explanation attribution

Opt-in finite attribution retains fixed family aggregates for factor fires, charged
work, elapsed nanoseconds, deductions and conflicts. Lazy explanation attempts,
completed supports, memo hits and interrupted work remain distinct; an interrupted
build supplies neither a support nor a memo entry. Historical Table retries record
attempted work even when they decline, and accepted retries separately.

First-UIP resolution credits the seed and each resolved antecedent. Its family set
travels with the learned object's admission and subsequent native use events.
Immediate assertion, first consumption and later force/conflict events are distinct:
consumption follows the learned lifecycle below, while later events exclude the
synchronous admission fixpoint. Several contributing families may receive credit
for one learned object; these counters cannot be summed as disjoint total costs.
They describe participation and consumption, without asserting causal benefit.

One state owns at most 1,024 reason identities of at most 64 words each and 1,024
learned provenance entries. Reason eviction and provenance exhaustion affect only
diagnostics; missing identities use the unattributed family. Retirement releases
learned provenance and diagnostic ring entries aliasing that clause's literals;
compaction preserves retained provenance by object identity. The native SAT
lane retains packed propagation and credits its clause family without constructing
general propagator projections. Its fire counter counts BCP calls rather than
general factor invocations. Disabling attribution retains no diagnostic maps.

The controls preserve propagation order, containing cancellation, historical trail
boundaries and reason authority. Eager support observations occur at reason delivery;
their construction cost is included in factor propagation. Lazy reconstruction and
refinement work overlap the containing solve's work and time.

## Conflict analysis

`ConflictAnalyzer` walks the implication graph backwards from a conflict, resolving
each step against the antecedents of the resolved variable in reverse assignment order,
until exactly one literal at the current decision level remains: the first unique
implication point. The graph spans Boolean variables and integer order literals
uniformly. Factors emit per-factor conflict reasons and record antecedents on every
force, so the analysis resolves through non-clause deductions instead of treating
them as leaves. A variable with no antecedent is a decision, assumption or root fact.

Folding an antecedent into the nogood is delegated to a `ConflictResolvent`:
`ClauseResolvent` is the default and sound fallback, and the pseudo-Boolean resolvent
below is the alternative. A learned constraint carries its backjump level, its LBD
(the number of distinct decision levels it touches) and whether it asserts after
backjumping; a root conflict learns nothing. The variables seen during analysis form
the bump set for VSIDS and CHB.

`ClauseMinimizer` shortens every first-UIP clause before it is emitted: first by
self-subsuming resolution, then by binary-resolution minimization. Dropping a
redundant literal yields a stronger implied clause, so the result stays an asserting
nogood. The redundancy memo is cleared before each conflict, since levels and
antecedents belong to that analysis.

An opt-in redundancy preflight checks remaining sibling leaves, cycles, coupled
integer atoms and cached failures before the first child expansion of a DFS frame,
when that child has an uncached recorded lazy reason. Eager, cached and off-trail
derived reasons follow ordinary traversal without a speculative sibling scan.
Boolean pins and stamped atoms expose a leaf only through a null recorded reason;
lazy markers, empty premise lists and off-trail derived atoms do not establish a
leaf. A failed sibling retains the candidate clause literal without constructing
unneeded historical supports. Metadata probes and preflight visits share the
containing propagation work and token. It changes no premise's deduction-time trail
boundary, and an interrupted preflight withholds the unfinished analysis result.

Two analyzers share this construction. The native CP analyzer learns over CP reasons
and stores into the native clause database. The shared first-UIP analyzer in
`SearchLearning` learns over component reasons; a shared conflict with usable reasons
asserts and backjumps, while a sound resolvent that cannot assert keeps chronological
fallback. Unexplained component publications cannot supply missing antecedents.

The numerical frontier also uses the shared clause-resolution operation on checked
source refutations. Each retained resolvent carries an exhaustive integer-split
derivation over its two input proofs. It enters the numerical coverage tree only
after a new source check, preserving proof authority independently of clause names
and queue order. This bounded opt-in policy retains complete Boolean scope and
opening restrictions; unsupported predicates cannot produce a resolvent.

## Learned-object authority and lifetime

Native clauses, shared clauses, pseudo-Boolean rows, integer rows and source
refutations keep specialized stores under the same lifecycle contract:

| Boundary | Contract |
|---|---|
| Construction | A candidate supplies no authority until its owner admits its derivation. Native conflict analysis uses historical reasons; shared analysis requires expressible component premises. |
| Admission | The store owns the retained payload and its scope. Clauses and arithmetic rows must follow from the session model with every conditional premise represented. Source refutations require checked source revision, objective and conditions. |
| Assertion | A retained object is evaluated against the current assignment before it can supply consequences. Imports are examined even when their watched root facts were assigned before attachment. |
| Consumption | A deduction or conflict reaches the owning search state. Checking or retaining a candidate alone is insufficient. A source unit is consumed only when traversal installs its split and implication; a reused refutation closes its covered node. |
| Retirement | Watchers, queues and identities move together. Native compaction retains objects referenced by live pins or saved integer reasons, then remaps both live and historical IDs. Shared pins own copied clause reasons. Checked source evidence remains immutable after cache eviction. |

An assumption is a decision premise, never an unconditional source fact. Learned
clauses preserve those premises through resolution. Permanent objective bounds and
model blockers belong to the containing search scope. Clause exchange requires
compatible problem and objective contexts; repair pools filter permanent assertions.
Clauses attributed to another origin retain it and are not re-exported. PB and
integer rows are not clause-portable. Shared component clauses remain session-local; source
refutations import only into conditions that imply their recorded support.

Retraction removes assignments, not admitted deductions. Learned units and objects
whose implications retract are examined again after undo. Pending attachments survive
earlier conflicts and cancellation. Suspension retains stores and pending attachment; cancellation
withholds unfinished analysis or checking and grants no refutation. Retiring a
source cache releases its indexes and names under the containing allowance.
An optional recheck after a consumed implication retracts can retire with its object;
it does not retain a reason of its own. First assertions and live or historical native
reasons remain protected. Native fixpoint cancellation stays sticky until its
component owner retracts the interrupted decision; replacing the token alone does
not resume that partial state.

The common counters are `learning{Kind}{Stage}`, with kinds `Clause`, `Pb`,
`Integer` and `SourceRefutation`, and stages `Constructed`, `Admitted`,
`Asserted`, `Consumed` and `Retired`. Construction counts candidates presented
to admission, including duplicates and declines. The other stages count each
stored object once in its retained lifetime; reattachment and repeated use do not
inflate them. Native and shared reports remain separate. Source assertion means a
checked application in a compatible condition; a returned unit can still be declined
by traversal before consumption. These counters describe ownership and use, not
proof coverage or independent validation.

Opt-in bounded sharing observations report primitive clause payload, allocated
watch/signature/stage/reason-index capacity and copied trail-reason payload.
Native observations include their specialized store and saved reasons at exchange
boundaries. Both use one charged, cancellable observation with bounded scratch
and explicit completeness; see [sharing accounting](12-portfolio.md#shared-channels).
Observation never invokes retirement or materializes a reason. Partial data and
historical peaks cannot authorize a byte-limit decision.
Payload counts and index capacity have separate ownership;
reference counts exclude platform object layout. The bounded attribution reason ring
and its modeled map references participate in this observation. An owner-aware aggregate counts
each primitive payload or allocated index once, including aliased reasons; modeled
references use eight bytes per slot for byte-limit policy. Completeness covers the
enumerated owners, rather than the worker's entire heap. Active and historical
reason protection retains its ordinary authority through compaction.

The opt-in `learned-memory-bytes` soft limit consults identity-deduplicated ownership,
including native/general clauses, arithmetic rows, private index capacity, copied reasons,
pending attachments and retained inprocessing buffers. `ownedPrimitiveBytes` partitions
into `sharedPrimitiveBytes` and `privatePrimitiveBytes`; source arenas shared by workers
count once when visited by one observation. `ownedReferenceSlots` counts modeled live
references, priced at eight bytes per slot. Platform headers, generic collection spare
reference slots and allocator overhead remain excluded. Diagnostic payload categories
can alias and cannot be summed to reconstruct these ownership fields.

Protected ownership includes permanent assertions, live and saved reasons, pending first
assertions and interrupted root publication. Required metadata, reference containers
and posting allocations share the protection of the clauses they index.
Native packed arenas are protected at
allocation granularity while a retained reason needs them. This floor is conservative,
rather than a minimum compacted representation. Pressure may release optional pass
buffers, while interrupted propagation/publication retains them. Close detaches the
native generation; reseeding preserves compatible learning and invalidates obsolete pass
snapshots.

Reclamation runs at safe propagation/branch boundaries independently of restarts.
Unchanged ownership reuses its maintenance result across short resumed slices; store,
trail, atom, pass and scope changes invalidate that decision rather than spending a
whole slice repeatedly before search advances.
An interrupted optional scan waits for ownership change before retrying; direct
reclamation attempts remain retryable.
`learned-memory-work` and `learned-memory-scratch-bytes` bound preparation and scratch.
Native/general compaction requires a cancellable preflight of all arrays, watches, reasons,
queues and event indexes, with a conservative envelope for indivisible publication.
Cancellation or an oversized envelope defers without mutation; accepted publication
remaps the whole generation and trims retained primitive capacity. Shared search retains
stable handles and resumes separately funded owner removals. Protected
state or an unfunded preparation permits continued search above the soft limit, with an
explicit protected/deferred observation. Work debt supplies no hard pause-latency
guarantee. Thresholds and default retention remain separate policy decisions.

## Native SAT lane

A pure-Boolean model whose factors are all clauses runs on a dedicated lane. The
`nativeSat` setting auto-dispatches by default: the lane is selected exactly when the
propagation projection is eligible, and a custom clause projection makes it
ineligible. Clause literals are packed into an immutable arena whose clause indices
match the model's factors, so reasons still identify source factors, and the arena is
shared read-only across portfolio workers.

Propagation is two-watched-literal unit propagation over a trail pointer, without
per-factor dispatch or integer order literals, reading a per-session truth byte per
variable. Learned clauses live in a growable side buffer with a wrapping
replacement-scan cursor per clause, and reductions compact them in place. The lane
shares the general trail, pins, antecedents and conflict analyzer.

When complete cap selection cannot be funded, a bounded window over non-permanent
learned clauses retains the lowest LBD and stable-handle keys after permanent and
tier-protected ownership. Its max-heap shares the candidate index with retained
survivors. Funded heap steps, cursors and pending retirement survive later restarts
and appended clauses; compaction resets them before handles change. Policy updates
and reason-ownership checks are charged before publication. A later use protects a pending
eviction; units, pending rechecks and current root reasons survive.
Retired rows stop propagation and export, while their slots and arena bytes remain
owned until a funded full compaction reclaims them. Ownership reports include that
capacity and report `learnedRetiredSlots` separately from active clause count.
Window calls, charged work, visits and permanent, active and retained counts remain
available in the released session's lifecycle totals.

General CP ranks learned clauses, pseudo-Boolean constraints and integer rows in
the same candidate index. Permanent arithmetic constraints contribute to its floor.
Pure-Boolean clauses keep a current-reason count updated by pinning and retraction;
Boolean undo stores no historical factor handle. Admissions classify these clauses,
and full compaction remaps their counters with their factor IDs. Their windows use
those counts and pending-attachment flags without a store-sized protection mask.
Other constraints use a funded snapshot covering current Boolean and integer reasons,
saved integer bounds and pending first assertions. Eligible retired factors become inert
stable-ID slots; propagation queues and active readers skip them. Integer rows own
their position in a dense reference cache, allowing retirement to unlink a row
without scanning other rows. Cache capacity and position fields remain part of
modeled ownership; removing the last row releases the cache backing array. Full
compaction rebuilds cache positions and remaps integer-event subscriptions even
when no integer rows remain. The candidate cursor survives admissions and is
rebuilt when full compaction renumbers the store. Lifecycle totals identify the
packed-native or general backend and report completed ranking passes and
boundary/preparation deferrals.

Selected pure-Boolean retirement captures an opaque admission handle before a
replacement is admitted. Immutable admission keys survive compaction; a bounded
compressed binary index reuses an already-owned request instead of allocating another.
Recent use shares the lifecycle word, while admission keys occupy a remapped column.
A separate compaction generation rejects stale selections;
captured handles retain no clause payload and survive later factor-ID remapping.
The caller authorizes a request only after replacement propagation and required
root/peer publication finish. Authorized requests stay kernel-owned when optional
pass buffers are released. A bounded round-robin continuation rechecks current
reasons and pending attachments, so protected requests do not block later eligible
ones. Terminal results stay in the continuation signal until acknowledged and unlinked.
Acknowledgement and request-owner release have separate funded steps; capture also
checks its retained-metadata allowance. Deferred captures report observed minimum
work, scratch or retained bytes. A caller distinguishes a fresh-slice retry from an
unfundable class grant without accumulating credit for an atomic mutation.
Reseeding or closing the scope
invalidates outstanding handles. Selected retirement reports logical disabling,
payload unlinking and full physical release separately. The cap index repairs
externally retired heap and survivor entries through funded resumable steps before
continuing its LBD ranking; its scalar continuation state remains modeled ownership.

## Pseudo-Boolean learning

`pbLearning` auto-enables cutting-plane analysis for pure-Boolean models. Explicit
enablement admits Boolean conflicts in mixed models. The seed, each loaded source
constraint and each reconstructed reason must contain only Boolean coordinates;
integer order, equality and hole atoms retain clause fallback before Boolean indexing.
An asserting native PB result takes precedence over shared clause analysis and stays
in the CP store. Other mixed conflicts keep shared clause analysis.

The resolvent is a linear `≥` nogood seeded from the violated bound. Each step
rounds the reason up after division by its pivot coefficient, then adds it to cancel
the pivot. Weakening and saturation keep coefficients bounded and canonical. These
nonnegative combinations with Chvátal-Gomory rounding preserve implication. A step
that declines, including signed-word overflow, falls back to clause resolution.
Learned pseudo-Boolean constraints share clause retention. A permanent-retention
mark prevents forgetting; it does not establish logical scope. Source-valid atom
channeling clauses and incumbent restrictions can both have that mark. Observers
of enforcing incidence read complete PB terms and established values, because the
weighted covering watch set is only a propagation wakeup index. Unknown constraint
kinds or unnameable atoms withhold a complete-incidence claim.

## Integer row learning

`integerRowLearning` is opt-in and independent of pseudo-Boolean learning. Mandatory
finite `Linear` inequalities and the explaining half of equalities provide integer
rows. Mandatory `ExactLinear` rows provide the same source when every coordinate is
integer and the complete compact projection preserves their word-sized constants,
term order and relation. Wide, real or activated exact rows retain clause fallback.
Reified integer rows preserve the signed indicator selecting the inequality
or equality side, including the strict integer complement of an inequality. Each
resolved endpoint must name an unextended historical linear reason from the same
source row, with its exact guard false-literal and historical guard truth.

The raw requested bound, reconstructed at the deduction's trail position, must
support the eliminated live endpoint; a hole-snapped stronger endpoint retains
clause fallback. Interior holes in the remaining terms stay in the actual domains
and do not prevent a source-implied combination. Historical exclusions clipped by
a pivot move remain conservative: a sparse tail walk admits at most 64 positions,
using historical membership to exclude later carves. Disequalities, wide constants
and unsupported projections retain clause fallback.

A selected variable-array `Element` can seed a conflict from either side of its
selected equality. Its positive index-equality atom retains the source index,
offset and selected cell identity, including when the atom is created after selection.
Only a bound conflict of that equality is arithmetic; Element support explanations
remain clause reasons. Support filtering keeps its hole-aware semantics. Repeated
cells retain their selected index guard, and a result alias supplies no arithmetic row.

Opposite-sign nonnegative combinations use checked signed arithmetic and integer
gcd normalization. Each combination must remain conflicting. A retained row must
propagate against the session's historical domains at its first-UIP clause's native
backjump and the mapped shared target. Each combination checks that same native
target, preserving clausal backjump selection. The raw assertion target must have
been a member of its historical domain; a later carve cannot remove that earlier
membership, and a historical hole cannot supply a raw bound assertion. Literal counts do not establish
assertion. Foreign unavailable bound premises retain clause analysis. The producer
admits at most 16 terms, 16 combinations, 64 native levels and 64 stored rows, with
bounded duplicate lookup.
Assertion checks reconstruct each historical term domain once per combination and
reuse its exact activity for the candidate bounds. A retained row with the same
normalized coefficients and a stronger bound suppresses a new consequence only
when its guard is unconditional or identical; opposite or unrelated guards retain
separate consequences. Retirement still follows the owning row's live reasons.

Rows retain at most one distinct signed guard; combinations requiring different
guards retain clause fallback. A guarded row asserts only where its guard already
holds at both the native and mapped shared target. An inactive guard withholds
arithmetic propagation, and its activation wakes the row through a literal watch.
Guard negations remain premises of every retained-row deduction and conflict.

Rows belong to the prepared session's coordinates and source factors. Assignment
reconstruction does not authorize replaying a row in another preparation, assumption
container or objective context. Only the owning store attaches the typed object.
Opt-in integer learning gives finite satisfaction, enumeration and optimization
traversals a session-local shared atom registry, reserving the prepared Boolean
coordinates before naming integer decisions and their complements.
The registry admits at most 4,096 atoms; an exhausted registry stops the traversal
as indeterminate. Without names, a non-root integer decision cannot supply a
shared premise and mapped admission must decline.
Every native conflict cascade repeats the mapped-target assertion and complete
premise checks before admitting a typed result. An unavailable mapping retains its
clausal fallback, including cascades reached through an LP consequence.
Within one fixpoint publication, integer leaves that are flat conjunctions of
active named assertions can share complete proof subgraphs. Inherited nested or
unavailable leaves do not enter that memo. Proof-reference visits charge native
work, and the publication memo ends with its callback.

Rows share the learned store and integer bounds propagation. A successful ordinary
row assertion also retains its first-UIP clause in that native store. The two
constraints can prune different branches; their deductions publish together from
the undo position before either assertion. They follow their own ordinary retention
policies and reason protection.

Eager reasons freeze the historical clause premises without a compactable lazy
factor id. Integer bound
event subscriptions wake rows after undo and are remapped or removed on forgetting.
A live bound reason or a reason saved in undo protects its row from retirement;
after those references retract, retirement removes its guard and endpoint subscriptions.
Later conflicts use these retained rows' clause explanations. Arithmetic combination
loads original mandatory or guarded linear rows; a selected Element equality can
seed the conflict but cannot supply a numerical endpoint reason.
Only clauses are shared across arms. Row satisfaction tests the complete maximum
activity; a satisfied endpoint literal cannot discharge the row or certify a root
contradiction.

## Arithmetic diagnostics

An optional native-session `ArithmeticLearningTrace` records bounded arithmetic
analysis events before conflict retraction. It retains seed and fallback literals,
integer endpoint markers and historical row domains, signed row guards with native
Boolean or order-atom metadata, native/shared targets, decline
categories and stored arithmetic consumers. Literal metadata distinguishes cached
atom levels from the analyzer's effective conflict level. Historical domain holes
exclude later journalled carves; endpoint factor ids describe the live reason owner.
Storage identities survive factor compaction and retire when their constraint is
forgotten. Only actual changed bounds or Boolean pins count as consumer events.
Focused windows retain a selector pick or actual branch attempt after each consumer;
LP-guided attempts can bypass the heuristic selector.

The trace is absent by default. Its fixed event and scalar-word allowances and
per-payload limits stop capture with explicit truncation. Event lookups withhold a
full recorder before later payloads are constructed. The trace reads existing state,
does not reconstruct new reasons or run propagation, and supplies no proof or source
mapping authority. Source and preparation identities belong to the containing
diagnostic invocation; an incomplete trace cannot establish exhaustive applicability.

## Learned-clause retention

Native forgetting runs at restart boundaries only with a configured cap. Permanent
and glue clauses (default LBD at most 2) survive; remaining space favors lower LBD.
The SAT preset enables capped tiered retention:

| Tier | Initial classification | Lifetime |
|---|---|---|
| Core | At/below glue threshold | Never deleted |
| Mid | Through LBD 6 | Survives reduction; idle clauses demote to local |
| Local | Above LBD 6 | Aggressively deleted; conflict use promotes to mid |

Tiers persist independently of later LBD. Shared component learning is uncapped by
default; capped reduction retains glue clauses, units and recent clauses before
ranking by LBD. [ClauseDb](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ClauseDb.kt)
and [SearchLearning](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchLearning.kt)
own the respective stores.

Restart-cap candidate preparation admits its complete candidate arrays, tier columns
and worst-case ranking work against `learnedMemoryWork` and
`learnedMemoryScratchBytes` before allocating. Candidate scans and heap ranking
poll cancellation; unfinished preparation publishes no tier changes or sweep event.
Insufficient work or scratch safely defers the cap sweep. Completed selection keeps
the same LBD priorities and tier lifetimes; a sweep event requires actual retirement.

If complete candidate preparation cannot be funded, native clauses and general learned constraints use
the bounded handle-preserving window. The candidate index retains the best eligible
LBD keys as a max-heap; each funded step can resume without a whole-store selection
buffer. Permanent clauses, protected reasons and policy survivors
reduce the heap quota. A pending eviction that gains protection also consumes that
quota before the next heap step. Mid rows
survive their demotion window, and classification clears survivor reuse flags.
An unfunded next-row transaction suspends the cursor for a later grant.
Current or historical reasons can retain additional rows above the soft cap.
Native post-undo replay protects its queued clause handles and learned units without
freezing unrelated retirement. Incomplete trail propagation, conflicts and root
publication still defer the window.
General retirement replaces its payload with an inert stable-ID slot and releases
its watch-position payload, integer-row cache entry and applicable deferred/diagnostic
aliases. Stale watches skip that slot; their allocated capacity and policy columns
remain owned until compaction. Arithmetic reason snapshots and native/general full
physical compaction still require their complete funding.

Shared learning uses stable handles and bounded-height owned indexes for clauses,
LBD ranking, watches, signatures and live reasons. Count-cap windows retire eligible
clauses in descending LBD order, retaining older clauses on ties. A window fixes its
admission boundary; appended clauses enter a later window. Epochs retain recent-use
protection without a whole-store flag reset.
The optional modeled-byte soft limit runs independently at propagation boundaries.
Units, glue clauses, live reason owners and pending first attachments form its
protected floor; copied trail reasons and queued component publications remain
privately protected. Logical retirement disables propagation before separately funded
watch, signature and index removal releases payload and metadata ownership. A partial
release keeps its owner and cursor across calls. Each bounded mutation polls after
its work debit and publishes without callbacks. Retired payload and unfinished
metadata stay in ownership observations until released; partial observations supply
no byte-limit authority. Unfinished maintenance bypasses completed-result caching.
An insufficient atomic-operation grant explicitly defers while preserving the cursor.
Root reseeding releases obsolete copied
reasons; terminal release drops shared learning without rewriting lifetime counters.
A cancelled automatic scan permits one propagation opportunity before another
attempt, including when a suspended decision retracts and retries.

## Restarts, phases and inprocessing

A `RestartSchedule` decides when a run pops back to its root; the restart action
itself (popping the trail, forgetting, inprocessing) stays with the traversal. Each
driver owns its schedule instance. A single unbounded run is the default. The
configurable schedules, in precedence order, are:

- **Mode switching** dives without restarting until a first feasible solution, then
  alternates a stable stage of sparse Luby restarts that dives on the saved phase with
  focused stages of frequent adaptive restarts.
- **EMA adaptive** and **LBD adaptive** restart when recent learned LBD is worse than
  the long-run average, using moving averages or bounded windows respectively.
- **Luby** restarts after a Luby multiple of a strictly positive decision base.
  `BacktrackParams.lubyRestartBase = null` disables this schedule; zero and negative
  bases are rejected when parameters are constructed, including copies.

Both adaptive schedules block a restart while the trail is well above its average,
since the search is then driving deep toward a model. Learned constraints, activities
and saved phases survive every restart.

When enabled, `PhaseSaving` keeps the saved polarity of each variable, an optional
target phase (the deepest conflict-free Boolean prefix) and a rephase rotation through
target, saved, all-true, all-false and random polarities every 1000 conflicts by
default. A
mode-switching schedule pins the polarity source to its stage instead.

Inprocessing runs at safe complete roots in bounded round-robin slices over the
learned database. Vivification strengthens a clause by asserting the negations of its
literals under propagation; subsumption drops subsumed learned clauses and applies
self-subsuming resolution. Every pass must preserve the variable set, because portfolio workers share learned
clauses under stable variable ids and share the native arena read-only; variable
elimination runs once in [presolve](07-presolve.md) instead. Seeded searches skip
inprocessing, since derivations under assumption pins would not hold globally.

Opt-in root simplification examines bounded slices of source and learned Boolean
clauses against permanent root facts. An empty Boolean pin trail withholds the scan;
without a pinned literal it cannot satisfy or shorten a Boolean clause. Clauses
affected by a new Boolean root fact can be reached through a source-occurrence index
prepared ahead of traversal or cached by ordinary propagation. This bounded
neighborhood alternates with the ordinary clause scan,
retains its pin and occurrence positions across slices, and builds no index
inside the pass. Pin and occurrence walks admit at most 8,192 entries per slice
even with an unlimited class grant. Projections without an available index retain
the ordinary scan. An indexed candidate at the ordinary cursor advances both walks;
unchanged prefixes are not examined twice. Retired and non-clause source entries
do not defer the remaining literal window. Once available occurrence walks cover
every root pin, the ordinary cursor skips the remaining source suffix and continues
through learned clauses. Missing indexes retain the full source scan. Learned
compaction invalidates learned candidates while preserving stable source candidates
and source scan progress at an unchanged root. A changed root restarts the source
scan after learned compaction. Logical retirement preserves both root passes'
learned cursor positions; cached learned candidates are checked for liveness before
their payload is used. The separate physical mapping generation invalidates indices
after compaction or slot reuse.
A shortened
source clause enters as a permanent session-local replacement before its watches
retire; immutable source text remains available for reasons. The propagation session
owns unfinished source-watch retirement
after replacement admission. Pass reset or optional buffer release retains that record;
a funded slice retires its watches before admitting another source replacement.
Session close releases it. Satisfied source watches retire without changing variable IDs.
Learned selections capture a stable retirement handle before replacement admission;
an unfunded capture retains the candidate and admits no replacement. The root pass
stages one inactive selection at a time. Replacement propagation and required peer
publication complete before a funded root slice authorizes retirement. A satisfied
clause uses the same publication boundary for its premises. Authorized requests stay
kernel-owned through optional buffer release. Abandoned inactive captures retain a
small cancellation owner until cancellation is funded or their scope is released.
Caller metadata and kernel requests share a finite retained allowance bounded by
the configured memory scratch envelope and any learned-memory soft limit. Existing
candidate literal buffers remain part of the pass memory observation.
Learned replacements retain their imported origin. Physical compaction protects live and
historical reasons and removes obsolete watches; attaching a shortened binary installs
both implication directions and evaluates facts already assigned at the root. There
is no separate stale binary graph or whole-graph rebuild.

Opt-in failed-literal probing alternates a bounded rotating set of source Boolean
and existing integer-bound literals with coordinates from newly examined learned
clauses of two to eight literals. The global rotation alternates existing literals
with unfixed integer endpoints, including columns without existing atoms. Separate
cursors preserve endpoint progress when the atom registry grows. Each slice
snapshots both candidate spaces and visits each global candidate at most once;
scanning creates no atoms. A completed endpoint conflict names only the complementary
unit it proves; registry and threshold-index growth enter the containing work and debt.
Equality atoms decline. Bound probes use ordinary single-bound
decisions and discard implied-result payloads, retaining the same undo and cancellation
paths. A proved bound enters as a session-local clause over its semantic atom and
publishes through the original integer coordinate.
Permanent source replacements supply the same bounded priorities; their clauses and
ownership remain protected. A priority supplies no deduction without an actual probe conflict.
The learned cursor advances without replaying an exhausted unchanged neighborhood;
compaction invalidates its indices. Preparation and retained literal storage share the
probe class allowance and memory observation. Funded probing precedes cheap root
simplification so proved facts can refresh binaries in the same safe-root opportunity. Each attempt has a propagation-work ceiling within
the invocation token. A completed root conflict proves the complementary unit, which is attached and
propagated through ordinary search. Completed root facts are published to shared
components before traversal resumes. Successful and interrupted probes retract to the
complete root; interruption supplies no deduction. Speculative retractions preserve
the saved search phases while retaining ordinary unassignment notifications for
variable selectors. Clause scans and probes enter the existing inprocessing and
propagation work counters. Cursors survive suspension and
reset on reseeding; learned snapshots retain their physical mapping generation.

Both root passes require an unconditional complete decision-level-zero root. Assumptions,
seed restrictions, permanent objective cutoffs, model blockers and externally implied
root restrictions withhold these passes. Root premises can therefore be discharged
without turning a conditional fact into an unconditional clause. The structural
source replacements themselves introduce no additional scope. Probing and root
simplification are disabled by default; their batch and per-probe limits are explicit
execution settings.

The containing invocation and worker work allowance includes scans, occurrence-index
preparation, probing, attachment propagation and protected retirement. Scans enter
the propagation-work ledger; diagnostic probe and visit counts add no second charge.
Each of the four classes has a separate work ceiling inside the scheduled slice's
total ceiling. Each pass receives the containing remainder after earlier passes;
its remaining optional work is the smaller of that remainder and its class grant
after debt repayment. Zero withholds that class. Completed atomic copying, sorting and
compaction can overspend; the class and scheduled loop repay their respective debt
before admitting more work. Separate containing and per-class debt-added and
debt-repaid counters retain those charges without adding work. Reseeding resets
cursors without forgiving that debt.
Time and work cancellation retain no partial probe.
Attachment and root publication are required cleanup once a consequence is admitted:
local ceilings stop later optional work and retain the cleanup's overspend as debt.
The containing invocation still cancels them. An interrupted root attachment keeps
its queued fixpoint and completes under the next invocation before publishing root
facts or making a decision. Root publication retains its bounded scan across slices.
It retains the CP publisher so a consequence reaches peers without creating another
private decision level in its producer. Passes receive a completion marker after
consistent publication, or after a slice that requires no publication. Cancellation,
an indeterminate peer and an unfinished attachment withhold that marker. The marker
records completion without running another class slice or granting more work.

A completed root scan waits for changed input. Appended learned clauses extend its
remaining range without replaying unchanged sources; new permanent root facts or
learned compaction invalidate the completed range. Partial candidates retain their
position until admission or rejection.
Root scan positions and residual candidates, probe cursors, vivification replacement
batches and subsumption preparation survive suspension. The subsumption occurrence
window retains at most 1,024 clauses
and 8,192 literals, advances round-robin, and declines a clause exceeding its remaining
literal space. Compaction invalidates root indices through their mapping generation
and other pass snapshots through the learned revision, without changing source
variable IDs. Replacement attachment precedes retirement; active and
historical reasons retain their ordinary protection.
The retained-memory observation counts each primitive buffer once by identity,
including residual capacity, occurrence postings and replacement metadata, and models
live reference slots separately. These are modeled owners rather than a whole-worker
heap measurement. Optional preparation can be discarded under memory pressure;
an interrupted root attachment or pending peer publication protects the retained
buffers until that consequence is complete. Reseeding and closing detach pass buffers,
and vivification resets replace their containers so empty capacity is released.

Restart cadence remains a trigger. Optional propagation-work intervals and learned
clause growth also request work at natural roots, without forcing a restart. The
cooldown requires non-inprocessing work after an attempted slice and applies to
restart requests too. The growth mark excludes the pass's own attachments, so a
pass cannot earn its own next opportunity. Optional warmup starts work and growth
marks at the first eligible complete root and withholds slices until the requested
non-inprocessing work follows that root. Initialization cannot fund that warmup;
scope reset renews its origin without forgiving debt. Required attachment and
publication finish ahead of optional admission. Class rotation
prevents a cheap pass from always taking the entire offered slice. Work and growth
triggers, cooldown and warmup are disabled by default; the additional work ceilings are
unlimited unless configured. Existing general off and SAT preset controls remain.
Funded root continuation resumes retained candidates, source-watch retirement and
selected requests before optional trigger gates. It receives one root-class grant
inside the containing grant and starts no new optional scan. Protected requests
yield ordinary search after a complete protected cycle; deferred work leaves that
cycle incomplete. Ownership changes reopen protection checks, while memory
observation alone supplies no new opportunity. Capture, authorization, cancellation,
acknowledgement and retirement retain their charged work and class latency.
Per-class attempted, eligible, shortened, unit, stopped and work counters diagnose
applicability. Probe outcomes distinguish a consistent fixpoint, a completed
refutation and an interrupted attempt; these counters add no second work charge.
Attachment, retirement and continuation work are subsets of class work, which
overlaps containing propagation work. Root publication has a
separate containing charge and also cannot earn the next trigger. Per-class, scheduled
slice, continuation and publication durations keep disjoint latency buckets below
one, ten, one hundred and one thousand milliseconds, plus a bucket for at least
one second. These nested spans overlap and provide observations, not a hard pause bound. Learned lifecycle and S4
family attribution retain their own authority and completeness limits; missing
attribution does not establish zero usefulness. These observations select no
adaptive score or default threshold.

## Theory completion

Arithmetic owners complete or refute the coordinates left by finite search.
[Theory services](14-theories.md) define their fragment-specific checks.

A component's partial consistency check or relaxation witness does not accept a
complete source assignment: every participating owner must finish its required check
before publication. [Theory contracts](14-theories.md) define the fragment-specific
checks and evidence. Difference graphs, exact linear systems and polynomial completion
keep their own state while sharing the traversal's source facts and decision lifetime.
Integer integrality and finite factors cannot be dropped to obtain an arithmetic
witness.

Exact linear components wait for finite-owned coordinates before proposing splits
or accepting completion. Finite publications supply scoped bounds and fixed values
while the finite owner keeps hole membership, so an arithmetic point outside a source
value set is withheld rather than accepted on its bound hull. Retraction invalidates
both peers' scoped outcomes.

Difference components can imply comparison guards from checked active source paths;
these carry clause-form reasons and enter the shared Boolean fixpoint. See
[difference proofs](14-theories.md#difference-logic). Open quadratic integer optimization branches
on exact wide predicates and prunes against certified bounds and checked incumbents,
without turning objective cutoffs into infeasibility conflicts. See
[nonlinear integer quadratic objectives](16-nonlinear-arithmetic.md#quadratic-objectives).

### Selected theory encoders

Prepared factor encoders supply the full relations used by shared arithmetic
components; admission and component construction consume the same selected capture.
Variable ownership is independent of factor participation: finite declarations retain
finite state and choices, while a propagator and encoder can consume one factor
together. Component descriptors distinguish primary `factorIndices` from overlapping
`participatingFactorIndices`, retaining one authoritative coordinate owner.

Linear relations keep their conjunction and disjunction structure, row activators,
strictness and original factor attribution. Private real coordinates stay in the
combined exact model and are stripped before publication. Polynomial installation
conjoins factor relations with all private auxiliary definitions, and finite integers
embedded there are private reals fixed from finite state. Relaxation selection cannot
omit an encoder's enforcing relation, and the original source checker stays required.
A finite registration skips duplicate original point checks only for factors whose
mandatory arithmetic peer installs a complete enforcing encoding.

### Arithmetic coordinate agreement

Prepared registration collections with several arithmetic completion groups install
`SourceCoordinateAgreementComponent` after their ordinary participants. Each group
must implement `SourceCoordinateAgreement`; unsupported groups leave the combined
result indeterminate. Standard preparation retains one combined arithmetic owner and
installs no agreement participant.

The exact-linear adapter exchanges normalized source bounds through the neutral atom
registry, and peers enforce the same predicates at the shared decision level. Each
source coordinate has one declared owner. After fragment checks, agreement validates
the immutable typed snapshot against each peer's current rows and conditions; it
never combines private fragment points, and a rejected point does not refute other
completions. Exact integers and rational reals are admitted; unsupported
contributions stay indeterminate rather than rounded. Agreement supplies no general
theory combination or bound authority and opens no fresh allowance.

### Contributed model ownership

Components contribute immutable values through typed keys, one owner per key, and
the assembled model reads them without copying. Exact source integer and real keys
preserve arbitrary-precision and rational coordinates; extensions can add open keys
whose payloads must be immutable. See
[Typed contributions and mappings](19-api.md#typed-contributions).

## Primal heuristics and statistics

Primal heuristics propose incumbents inside complete search. Each proposal needs
full source verification and supplies no proof authority.

### Root primal proposals

On mixed models, the root LP relaxation also proposes incumbents. Nearest rounding,
directed upward and downward rounding of the retained LP point, a peer worker's
discrete assignment and a feasibility pump each produce discrete targets. Every
distinct proposal passes the discrete fixpoint under the caller's assumptions, then
real completion through the real-leaf owner or the caller's source tolerance check,
then full source verification. Only a complete checked witness that strictly
improves the current cutoff becomes an incumbent.

The opt-in `lbTreeSearch` probe extends this to a private finite trail with exhaustive
fractional and value alternatives, trying the nearer side of the LP value first. It
returns accepted witnesses only and exports no optimum, refutation, bound or recession
claim. The `lp-lbtree` portfolio recipe can enable it.

Root primal work charges the containing root ledger and solve-wide `NodeBudget`, and
a fallback solve receives only the remaining allowance.

### Monotone clausal primal probe

Pure Boolean clausal optimization with at most 64 positive-cost variables can seed
incumbents through greedy feasibility repairs when those variables occur only
positively in hard clauses. The probe runs once before ordinary optimization and is
skipped behind an existing shared incumbent or within repair fragments.

A private satisfaction repair first finds a feasible assignment, then turns off any
cost variable whose removal alone keeps every hard clause satisfied. Trials then try
to turn off the remaining cost variables in descending weight order through a clause
walk with penalty escalation, restoring the previous feasible assignment on failure.
Trials run on a projected model reduced by unit propagation and bounded Boolean
elimination, and lift their result back. Private infeasible verdicts contribute no
outer proof, and the full original proposal is verified before publication.

The probe's work is charged to its arm and the solve-wide move allowance, within at
most half the remaining time and 5 seconds.

### Statistics

Counters describe observed work, never proof coverage.
`propagations` counts deductions from completed finite fixpoints, excluding decision
and root pins; `propagationWork` also counts dispatch, watcher, scheduling and reason visits
across undo. Root figures are subsets of totals. Fixpoint time excludes some counted
reason scans.

Native CP and shared first-UIP outcomes are reported separately. Non-asserting counts
identify chronological fallback, while zero does not establish explanation coverage.
Native learned-constraint counts include integer rows; their terms do not count as
Boolean learned literals.
Missing metrics stay absent, including root theory results that never enter traversal.
`smtCompleteSearch*` stage observations overlap and cannot be summed as a work total.
[SearchStats](../klause/src/commonMain/kotlin/com/eignex/klause/solver/result/SearchStats.kt)
and [CLI statistics](20-cli.md#statistics) define the exported fields.

---|---|
| `propagations` | Finite Boolean, singleton, bound, hole and survivor-set deductions reported by completed search fixpoints; excludes decision and root pins |
| `learned` | Learned consequences observed by the traversal, including native CP learning |
| `caNotApplicable`, `caNonAsserting` | Native CP analyses declining a usable or asserting consequence; exported by every CLI format |
| `propagationWork` | Monotonic dispatch, literal, watcher, level, scheduling and linear-term/reason visits, including work across undo |
| `rootPropagationWork`, `rootPropagationMs` | Initialization root-fixpoint work and time, including private preparation |
| `propagationMs` | Fixpoint elapsed time; some counted reason scans are outside it |
| `openAssertingConflicts` | Shared first-UIP analyses yielding an asserting backjump |
| `openNonAssertingConflicts` | Sound shared conflicts retaining chronological fallback |
| `openReductionNs` | Total shared reduction selection and watch-rebuild time; zero when the cap is off |

A nonzero non-asserting count points at a component reason that did not support
first-UIP learning. Zero does not establish complete factor reason coverage. Missing
metrics remain missing, including root theory refutations that never enter shared
search.

The CLI also exports native CP work and time (`nativeCpPropagationWork` and related
fields), where root figures are subsets of the totals. Complete open-theory search
adds `smtCompleteSearch*` observations of Boolean assignment depth, LP checks and
stage counters; they stay absent until observed and overlap, so they are not a work
total. See the [CLI](20-cli.md).

---

Previous: [8. Search and propagation](08-search.md) · [Contents](README.md) · Next: [10. Local search](10-local-search.md)
