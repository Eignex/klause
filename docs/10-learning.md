# 10. Learning

Search learns from scoped deduction reasons. This chapter follows explanation
lifetimes, conflict analysis and learned-clause retention. Traversal and propagation
are described in [search](09-search.md).

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
[LP consumer contracts](20-linear-programming.md#consumers-explanations-and-cuts).

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
and explicit completeness; see [sharing accounting](14-portfolio.md#shared-channels).
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
Native learned payloads belong to individual rows; required reference containers
and metadata stay protected at allocation granularity while a retained reason needs them.
This floor is conservative, rather than a minimum compacted representation. Pressure may release optional pass
buffers, while interrupted propagation/publication retains them. Close detaches the
native generation; reseeding preserves compatible learning and invalidates obsolete pass
snapshots.

Reclamation runs at safe propagation/branch boundaries independently of restarts.
Unchanged ownership reuses its maintenance result across short resumed slices; store,
trail, atom, pass and scope changes invalidate that decision rather than spending a
whole slice repeatedly before search advances.
An interrupted optional observation waits for ownership change before retrying; direct
reclamation attempts remain retryable. Retired storage cleanup precedes observation
and resumes while its funded cursor advances, even when ownership sampling is partial.
Changed work, scratch or byte-limit settings renew the maintenance opportunity.
An incomplete sample supplies no byte-limit or protected-floor total. Cancellation
withholds further maintenance until ownership changes.
`learned-memory-work` and `learned-memory-scratch-bytes` bound preparation and scratch.
General watch cleanup resumes across bounded maintenance slices without renumbering
learned handles. Retired Boolean and bound-atom postings are removed independently;
Boolean back-pointer deletion and swap-pop publication share one funded transaction.
Watcher movement rewinds the current scan prefix. Small posting arrays trim within
the same work and scratch limits. Posting and blocker arrays trim in separate funded
transactions, so their combined copy need not fit one grant. The Boolean
back-pointer index uses direct slots for source factors and eight startup rows,
a bounded sparse directory for later factors, two packed positions per factor
and a balanced overflow for additional positions. Source preparation reserves the
startup rows; pressure releases unused reservations. Paired clause watches install
and retire through one row lookup, and paired swap-pop moves update together.
Empty rows and directory branches
release their primitive and reference allocations. Deletion and a swapped watch's
position update publish together after bounded funding.
Primitive node fields and reference slots remain separately owned; live and saved
reasons protect the index. Ordinary index mutations retain their work charges. Initial
source-index construction records `propagationPreparationIndexWork` separately from
propagation work, including private source-check construction. Its CPU and elapsed
cost remain within the containing source invocation. Deferred
array trims retain their minimum grant requirements and retry when a later grant
can fund them, independently of further logical retirements.
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

Satisfaction traversals count constructed sessions by their actual propagation
backend. The CLI exposes `nativePropagationNativeSatSessions` and
`nativePropagationGeneralSessions`; retained traversal slices and reseeding keep
the same construction observation. A constructed session alone establishes neither
search work nor source completion.

Propagation is two-watched-literal unit propagation over a trail pointer, without
per-factor dispatch or integer order literals, reading a per-session truth byte per
variable. A satisfied blocker settles a posting before its clause is read. Learned clauses own copied literal arrays in bounded reference pages,
with a wrapping replacement-scan cursor per clause. Each page grows from one slot
to its fixed maximum; full compaction trims a partial last page. Empty pages release their arrays
without changing live clause handles. Reductions compact their handle and reference columns without
copying surviving literal arrays. The lane
shares the general trail, pins, antecedents and conflict analyzer.

A bounded window over non-permanent
learned clauses retains the lowest LBD and stable-handle keys after permanent and
tier-protected ownership. Its max-heap shares the candidate index with retained
survivors. Funded heap steps, cursors and pending retirement survive later restarts
and appended clauses; compaction resets them before handles change. Policy updates
and reason-ownership checks are charged before publication. A later use protects a pending
eviction; units, pending rechecks and current root reasons survive.
Retired rows stop propagation and export and release their owned literal array.
Admission, reference-array growth and release debit the containing work ledger.
A short retirement slice suspends before publication and resumes with its stable handle.
Policy columns and watch postings retain their separately funded cleanup. Native
watch cleanup preserves live posting order with resumable read and write cursors.
Retired tails release through the posting pages' funded removal path; filling a
retired hole moves the next live posting without copying the remaining tail.
Propagation rewinds an affected scan before its next cleanup step. Cursor updates,
posting swaps and removals are charged and cancellable before mutation, and the
scan reuses the fixed cleanup state.
Automatic indexed retirements mark the two current watched literal postings before
retiring a row. Each posting owns one retirement epoch; a completed sweep clears only
epochs captured by that sweep. A newer retirement remains pending across suspended
cleanup. Clean postings can be skipped only after a conservative sweep covers every
retirement preceding index initialization. Generic policy fallback keeps the full scan,
and remapping conservatively marks retained postings. Epoch storage, initialization,
retirement reads and writes, and cleanup transitions debit their owning ledgers.
Selected retirements and short indexed-retirement grants conservatively advance the
index baseline in a separately funded preparation step before their atomic retirement.
That preparation survives a short grant; the physical sweep covers unmarked postings
without enlarging the atomic step.
Payload page directories keep their first page directly and use a bounded compressed index
for later pages. Fixed directory state belongs to propagation preparation.
Index lookups debit the active
maintenance grant or the containing propagation ledger. A cached page avoids
repeated lookups within one page. Empty page headers enter a separate funded
unlink queue after their row arrays release; a revived header survives that queue.
Directory cleanup does not remap live handles or consume the metadata row-release
transaction. Ownership reports include unfinished directory nodes and report
`learnedRetiredSlots` separately from active clause count. A page owns a disjoint pool
of copied arrays. Its observation separates total and cumulative protected payload
bytes under one immutable allocation identity; only required rows protect payload, while a
required row protects the complete page reference allocation. Directory protection
includes the union of required branch paths; primitive and reference pools report
their cumulative protected subsets without per-node ownership scratch. Permanent-row checks read the policy
column directly. Reference-page and directory capacities are observed separately.
Window calls, charged work, visits and permanent, active and retained counts remain
available in the released session's lifecycle totals.

General CP ranks learned clauses, pseudo-Boolean constraints and integer rows in
the same candidate index. Permanent arithmetic constraints contribute to its floor.
Pure-Boolean clauses keep a current-reason count updated by pinning and retraction;
Boolean undo stores no historical factor handle. Admissions classify these clauses,
and full compaction remaps their counters with their factor IDs. Their windows use
those counts and pending-attachment flags without a store-sized protection mask.
Other constraints use a funded snapshot covering current Boolean and integer reasons,
saved integer bounds and pending first assertions. When its store-sized allocation
exceeds the slice allowance, a candidate checks those owners directly without scratch.
Each owner visit is funded; an interrupted check retains the candidate.
Eligible retired factors become inert
stable-ID slots; propagation queues and active readers skip them. Integer rows own
their position in a dense reference cache, allowing retirement to unlink a row
without scanning other rows. The cache keeps its first 64 references together and
uses bounded reference pages for its tail. Growth and trimming charge copied directory
slots and the partial page; an empty tail releases its directory without copying
retired references. Cache capacity, directory slots and position fields remain part of
modeled ownership; removing the last row releases the cache owner and its arrays. Full
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
gcd normalization. One analysis retains only its most recent normalized source, keyed
by projection identity, signed guard and inequality side. Reusing that immutable row
still validates each endpoint's historical guard, raw requested bound and membership.
The cached source is released with the analysis. Each combination must remain
conflicting. A retained row must propagate against the session's historical domains at its first-UIP clause's native
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
Within one fixpoint publication, complete conjunctions of active named assertions
can share proof subgraphs, including nested inherited conjunctions. Validation
admits at most 4,096 proof-reference visits and expands a repeated conjunction once.
An unavailable, unnameable or inactive leaf withholds reuse. Proof-reference visits
charge native work, and the publication memo ends with its callback. Complete
memoized nodes also supply the current traversal; separate temporary proof storage
is allocated only for nodes that cannot enter that bounded memo.
Native proof traversal keeps literal identifiers and child references in parallel
stacks. Inherited-proof validation allocates its stack and seen set only when a
conjunction branches. Conjunctions retain every child, and reference visits keep
their work, cancellation and local-limit checks.
Fixpoint facts already implied by shared integer endpoints retain the shared
premise without reconstructing a native proof. Endpoint strengthenings use the
complete proof path; observers and cancellation checks still cover every deduction.

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
Depth declines retain the native level boundary and distinguish original factor
seeds from retained learning; ownership alone does not certify source suitability.
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

Native forgetting starts at restart boundaries with a configured cap. A resumable
root continues a pending window before branching while its row, heap or cleanup
state advances, taking each further attempt inside the same slice until the slice
expires. Each attempt keeps the same bounded work and scratch allowance and
debits the containing slice. Cleanup polls cancellation before every bounded
mutation. Worker cleanup shares its worker's checkpoint countdown; unbound cleanup
reads the session and caller tokens on its first 64 polls and then once per 64.
A completed pass waits for the next restart before
aging tiers again; an unfunded attempt permits search to proceed. Continuation
reads existing ranking state without adding retained storage. Reaching the active
quota still completes the pending pass; it does not start another tier epoch.
Permanent
and glue clauses (default LBD at most 2) survive; remaining space favors lower LBD.
Cap eligibility and survivor quotas use active constraints; stable retired slots do not
consume the quota or count as newly retired in a sweep event. Below the active cap,
retired-slot cleanup uses the containing work and scratch grant without aging tier policy.
An unfunded cleanup remains retryable at later restarts. The SAT preset enables
capped tiered retention:

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

General and custom restart-cap selection uses the bounded handle-preserving window. The candidate index
retains the best eligible LBD keys as a max-heap; each funded step can resume without
a whole-store selection buffer. Permanent clauses, protected reasons and policy survivors
reduce the heap quota. A pending eviction that gains protection also consumes that
quota before the next heap step. Mid rows
survive their demotion window, and classification clears survivor reuse flags.
An unfunded next-row transaction suspends the cursor for a later grant. A completed
row classification retains its pending admission while heap comparisons wait;
cleanup comparisons finish before publishing their candidate mutations.
Current or historical reasons can retain additional rows above the soft cap.
The default Native policy retains its active non-permanent LBD/handle heap across reductions. Native
LBD keys remain immutable between admission and handle remapping. New admissions,
first-use notifications and MID aging carry the next classification work; unchanged
LOCAL rows need no policy transaction. Private epoch markers preserve a demoted
MID row for its demotion window and prevent duplicate effects when use and aging
queues overlap. A completed callback retains its result before another funding
check. Uses after classification enter the next epoch and remain protected by the
fresh eviction check. CORE, permanent, glue, unit and reason protection retain their
ordinary authority.

Four primitive FIFO queues share an owned page pool. Bootstrap reserves pages in
resumable atomic transactions while the generic owner continues selection within
the same grant. A completed generic window yields its candidate array before the
incremental owner begins classification. Reservation alone starts no tier epoch,
and spare reserved pages release through funded unlink steps. Selected retirement
detaches an unindexed reservation owner without restarting its generic tier epoch.
An empty window releases its pages and leaves subsequent admissions to fresh
reservation. Subsequent admission funds its additional capacity
before publishing a row. Heap removals leave a processed gap in the candidate
array, while new admissions remain in its unread suffix. Protected roots return
through that gap before suffix compaction. Restoration rechecks liveness and drops
an externally retired retry without publishing its handle. Sparse marker cleanup completes the
epoch, and funded unlink steps release spare pages. Below-cap retirement cleanup
prunes stale handles without aging tiers. External tier changes request a rescan.
Released metadata can erase retired comparison keys while a sift is suspended.
After stale handles are removed, a funded heap repair completes before selection
resumes. Another retirement restarts that cleanup without another tier callback.
Handle remapping invalidates the heap, queues and markers under the full-remap
preparation grant. Index scalars, references and page capacity belong to learned
ownership accounting. Grants unable to bootstrap use the generic window. Custom
policies first finish a funded index abort without further retirement, restore
candidate handles and clear private markers, then enter the generic window.
An abort completes a pending removal from the index without retiring a live row.
Native post-undo replay protects its queued clause handles and learned units without
freezing unrelated retirement. Incomplete trail propagation, conflicts and root
publication still defer the window.
An empty General integer-row cache detaches its obsolete event overflow index in a
funded transaction. A surviving cache trims its reference array when the copy fits
the work and scratch grant. Nonempty event cleanup retains its event and posting
cursors across grants. It removes retired subscriptions and empty index entries
without remapping live handles; posting arrays trim in separately funded transactions.
A retained entry avoids repeating its lookup before an affordable copy. Deferred
trims retry when later grants can fund them. A completed pass exposes a continuation
before testing newly appended retirements. Unfinished cursors, postings and index
nodes remain in owned primitive and reference totals.
General retirement replaces its payload with an inert stable-ID slot and releases
its watch-position payload, integer-row cache entry and applicable deferred/diagnostic
aliases. General and Native metadata share a paged owner with at most sixteen rows
per page. General pages include propagator and mutable-payload references. The first
page belongs directly to its owner; later pages belong to a bounded sparse index that
owns their release. A page table reaches every installed page by its index without
visiting that index, so a read takes the same path whatever the store has grown to.
Its first chunk covers 128 pages for General metadata and 2048 for Native metadata;
that chunk and other fixed startup ownership belong to state preparation. A later
chunk exists while one of its pages does. Admissions fund page growth, index
insertion, each new chunk and growth of the chunk directory. Releasing the last page
of a later chunk releases the chunk and trims the directory. Header unlinking clears
the page's table slot before releasing the page.
Native learned payloads cache 2048 page lookups, including absent pages, with fixed
primitive index tags. Both arrays and their owner references belong to state
preparation and ownership accounting, separately from payload-row reference capacity.
A cold lookup funds each visited index node
before publishing its cached result. Page installation replaces a cached absence;
funded header unlinking clears its cached page reference before returning.
A page queue and per-page retirement masks resume row release without scanning
permanent rows or looking up another page. Retained import/use counters survive
released metadata. Empty arrays release within their row transaction; empty headers
and index branches wait for separately funded unlinking. Revived pages survive
unlinking without renumbering live handles.
Retired postings stay owned until propagation or funded cleanup removes them. Selection and subsequent retired-slot compaction share
one work grant; compaction keeps every active row and receives only its remainder.
Root simplification transfers its fresh residual literal array into the learned store;
other admissions copy borrowed literal arrays. Native learned-watch cleanup scans
resumably before whole-store preparation under
the same grant. Sixteen-entry posting pages release at tail boundaries, and an empty
posting list releases its directory. Posting mutations rewind an affected scan prefix;
no learned handle or reason is remapped by this scan. A completed pass is reused until
another learned retirement requires a scan. Metadata release precedes watch scanning,
so permanent postings cannot delay its progress. Appended retirements extend the page
queue. Candidate-array trimming receives a separate funded copy transaction and
retains spare capacity when its work or scratch does not fit.
Every retirement flags the posting lists of its two watched literals. Propagation
entering a flagged list first drops its retired postings in one ordered pass,
debiting each read, move and released page, and a cleanup scan suspended on that
list starts over. Live postings keep their order, so the sweep that follows visits
live postings only. A learned clause visit reads its watch positions and scan
cursor from one metadata row. A sweep debits its posting and truth reads when it
leaves the list or pins a literal, with the same total as one debit per read. Funded cleanup keeps its read and write cursors across slices.
It checks the tail before filling retired holes and releases empty posting pages
through the same owner.
Capacity changes replace the disjoint metadata pool identity. A protected row retains
its complete page allocation; sparse-index protection includes the union of its
required branch paths. Primitive and reference pools report cumulative protected
subsets without per-node ownership scratch. Unfinished headers remain owned until
unlinking. Full generation remapping funds traversal, row copies, queue reset and
trimming before handles change.
Tier classification publishes within each funded row transaction. Arithmetic reason
snapshots and native/general full physical compaction require their complete funding.

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

---

Previous: [9. Search and propagation](09-search.md) · [Contents](README.md) · Next: [11. Search policies and completion](11-search-policies.md)
