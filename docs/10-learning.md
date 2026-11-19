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
cycles, cancellation and its fixed literal, step and visit limits keep the certified
prefix; its work is charged to the same propagation work and containing token. An
empty projection supplies no verdict.

### Table explanations

Table filtering keeps STR2 explanations, built from build-local caches of historical
domains that never cross propagation or undo. The reversible live prefix is committed
once per completed sweep, and sibling branches restore filtering from trailed state.
Ground table deductions record their bound or excluded value at the actual pin's
trail position. Historical cell checks consider only tuples whose target cells fall
in that removal and choose one blocking cell per tuple. Interval deductions and table
conflicts reconstruct the general historical support relation.

Ground relations share an immutable value-to-row index across projection siblings and
sessions, built within a fixed primitive-buffer budget; larger relations keep the scan
path. A reason visits only the postings of its target value or bound range, then
checks the deduction's historical domains. Mutable live prefixes and explanation caches
remain state-local. Index construction and historical reconstruction poll the state's
invocation token: interrupted construction publishes no shared index or reason memo,
and a stopped lazy reason is unavailable rather than an empty premise list, so
conflict analysis discards its unfinished learned result. A completed index stays
available across retraction and later invocations.

An internal opt-in conflict analysis can retry one unextended ground-table reason,
within fixed row, cell, column and premise limits, against the frozen literals of the
ordinary minimized clause that its original reason did not introduce. It keeps
target-domain scope premises and chooses only blockers present at the deduction's
trail position. The retry replaces an asserting ordinary clause only for a
lexicographically smaller backjump level, LBD or clause size; a local decline keeps
ordinary learning. Frontier-dependent reasons belong only to that analysis and never
enter the marker-only lazy memo.

Several unsupported interior values commit as one domain mutation. Their individual
lazy reasons refer to the pre-batch trail boundary, and each removal keeps its own
carve witness for existing and late equality atoms. Undo restores the prior domain and
removes the batch's witnesses together. Root propagation folds small unary Table
restrictions, so local search samples from the same restrictions.

MDD explanations reconstruct full symbol membership at the deduction's trail
position. A cut path cites the bound or interior removal that excludes its symbol;
later carves cannot become premises of an earlier deduction. Weighted path reasons
use the same historical membership. Each value removal records its symbol, and the
symbol postings (the same bounded immutable representation as Table values) select
that symbol's edges for the cut; storage declines keep the indexed graph scan.

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

Opt-in finite attribution keeps fixed family aggregates for factor fires, charged
work, elapsed nanoseconds, deductions and conflicts. Lazy explanation attempts,
completed supports, memo hits and interrupted work stay distinct; an interrupted
build supplies neither a support nor a memo entry. First-UIP resolution credits the
seed and each resolved antecedent, and that family set travels with the learned
object's admission and later use events. Several families can be credited for one
learned object, so these counters cannot be summed as disjoint costs; they describe
participation and consumption, not causal benefit.

Reason identities and learned provenance are kept in bounded tables; eviction or
exhaustion affects only diagnostics, and a missing identity falls back to the
unattributed family. Retirement releases a clause's provenance and the diagnostic
entries aliasing its literals; compaction preserves provenance by object identity.
The native SAT lane credits its clause family without constructing general propagator
projections, and its fire counter counts propagation calls rather than factor
invocations. Disabled attribution keeps no diagnostic maps. Attribution preserves
propagation order, cancellation, historical trail boundaries and reason authority;
eager support cost is part of factor propagation, and lazy reconstruction overlaps
the containing solve's work and time.

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
derived reasons follow ordinary traversal. Boolean pins and stamped atoms expose a
leaf only through a null recorded reason; lazy markers, empty premise lists and
off-trail derived atoms do not establish a leaf. The preflight shares the containing
propagation work and token, changes no premise's deduction-time trail boundary, and
an interrupted preflight withholds the unfinished analysis result.

Two analyzers share this construction. The native CP analyzer learns over CP reasons
and stores into the native clause database. The shared first-UIP analyzer in
`SearchLearning` learns over component reasons; a shared conflict with usable reasons
asserts and backjumps, while a sound resolvent that cannot assert keeps chronological
fallback. Unexplained component publications cannot supply missing antecedents.

The numerical frontier also uses the shared clause-resolution operation on checked
source refutations. Each retained resolvent carries an exhaustive integer-split
derivation over its two input proofs. It enters the numerical coverage tree only
after a new source check, preserving proof authority independently of clause names
and queue order. This bounded opt-in policy keeps complete Boolean scope and
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
Clauses attributed to another origin keep it and are not re-exported. PB and integer
rows are not clause-portable. Shared component clauses remain session-local; source
refutations import only into conditions that imply their recorded support.

Retraction removes assignments, not admitted deductions. Learned units and objects
whose implications retract are examined again after undo. Pending attachments survive
earlier conflicts and cancellation. Suspension keeps stores and pending attachment;
cancellation withholds unfinished analysis or checking and grants no refutation.
Retiring a source cache releases its indexes and names under the containing
allowance. An optional recheck after a consumed implication retracts can retire with
its object; it does not keep a reason of its own. First assertions and live or
historical native reasons remain protected. Native fixpoint cancellation stays sticky
until its component owner retracts the interrupted decision; replacing the token alone
does not resume that partial state.

The common counters are `learning{Kind}{Stage}`, with kinds `Clause`, `Pb`,
`Integer` and `SourceRefutation`, and stages `Constructed`, `Admitted`, `Asserted`,
`Consumed` and `Retired`. Construction counts candidates presented to admission,
including duplicates and declines; the other stages count each stored object once in
its retained lifetime. Native and shared reports remain separate. Source assertion
means a checked application in a compatible condition, and a returned unit can still
be declined by traversal before consumption. These counters describe ownership and
use, not proof coverage or independent validation.

### Learned memory

Opt-in sharing observations report clause payload, allocated index capacity and
copied trail-reason payload, through the charged, cancellable, bounded observation
described under [sharing accounting](14-portfolio.md#memory-observation). Observation
never invokes retirement or materializes a reason. An owner-aware aggregate counts
each primitive payload or allocated index once, including aliased reasons, and prices
modeled references at eight bytes per slot; completeness covers the enumerated owners,
not the worker's whole heap. Diagnostic payload categories can alias and cannot be
summed to reconstruct the ownership totals. Partial data and historical peaks cannot
authorize a byte-limit decision.

The opt-in `learned-memory-bytes` soft limit reads that deduplicated ownership:
native and general clauses, arithmetic rows, private index capacity, copied reasons,
pending attachments and retained inprocessing buffers, with source arenas shared by
workers counted once per observation. Its protected floor covers permanent
assertions, live and saved reasons, pending first assertions and interrupted root
publication, together with the metadata, reference containers and postings those
rows need. The floor is conservative, not a minimum compacted representation.
Pressure can release optional pass buffers; interrupted propagation or publication
keeps them. Close detaches the native generation, and reseeding keeps compatible
learning while invalidating obsolete pass snapshots.

Reclamation runs at safe propagation and branch boundaries, independently of
restarts. `learned-memory-work` and `learned-memory-scratch-bytes` bound each attempt.
Unchanged ownership reuses its last maintenance result across short resumed slices,
and store, trail, atom, pass or scope changes invalidate it, so search does not spend
whole slices repeating a decision. An interrupted optional observation waits for an
ownership change, while direct reclamation stays retryable; retired-storage cleanup
precedes observation and resumes while its cursor advances. Changed work, scratch or
byte-limit settings renew the opportunity, and cancellation withholds further
maintenance until ownership changes.

Consecutive clause-cap cleanup attempts share the active search worker's stop-polling
cadence, including sessions created under a preparation token. Mutation checkpoints
and continuation checkpoints consume the same countdown; full token reads decide
whether an interruption pauses the slice or ends the search.

Outside propagation, learned-store maintenance (reduction windows, retired-slot
and watch cleanup, page and index release, and compaction) runs in bounded steps
funded from the containing work and scratch grant. Each step is charged before
it publishes. A step the grant cannot fund defers with its cursor and the store
intact, and search continues, above the soft limit if protected state or unfunded
preparation requires. Cleanup and page or directory release never remap live
handles; handles change only through compaction, which invalidates or rebuilds
the reduction windows, heaps, queues and candidate cursors indexed by them.
Full native or general compaction runs a cancellable preflight of all arrays,
watches, reasons, queues and event indexes with a conservative envelope for its
indivisible publication, defers without mutation on cancellation or an oversized
envelope, and otherwise remaps the whole generation and trims retained capacity.
Native storage envelopes are funded before allocating the retention mask or scanning
clause ownership, so an unfunded fixed envelope defers without a pass over the clauses.
Work debt and bounded steps promise no hard pause latency. Thresholds and default
retention are separate policy decisions.

## Native SAT lane

A pure-Boolean model whose factors are all clauses runs on a dedicated lane. The
`nativeSat` setting auto-dispatches by default: the lane is selected exactly when the
propagation projection is eligible, and a custom clause projection makes it
ineligible. Clause literals are packed into an immutable arena whose clause indices
match the model's factors, so reasons still identify source factors, and the arena is
shared read-only across portfolio workers. Satisfaction traversals count constructed
sessions by their actual propagation backend; a constructed session alone establishes
neither search work nor source completion.

Propagation is two-watched-literal unit propagation over a trail pointer, without
per-factor dispatch or integer order literals, reading a per-session truth byte per
variable; a satisfied blocker settles a posting before its clause is read. The lane
shares the general trail, pins, antecedents and conflict analyzer. Learned clauses
own copied literal arrays in bounded reference pages, with a replacement-scan cursor
per clause. Reductions compact handle and reference columns without copying surviving
literal arrays, and empty pages release their arrays without changing live handles.

Reduction uses a bounded window over non-permanent learned clauses that keeps the
lowest LBD and stable-handle keys after permanent and tier-protected ownership. Its
heap steps, cursors and pending retirement survive later restarts and appended
clauses; compaction resets them before handles change. A later use protects a pending
eviction, and units, pending rechecks and current root reasons survive. A retired row
stops propagation and export and releases its literal array. Retirement marks the
posting lists of its two watched literals; propagation entering a marked list first
drops its retired postings in one ordered pass, charged to propagation, so the sweep
that follows visits live postings only. Funded cleanup otherwise removes retired
postings while preserving live posting order, and a cleanup scan suspended on a list
that propagation changed starts over. Window calls, charged work and permanent,
active and retained counts stay in the released session's lifecycle totals.

General CP ranks learned clauses, pseudo-Boolean constraints and integer rows in the
same candidate index, and permanent arithmetic constraints contribute to its floor.
Pure-Boolean clauses keep a current-reason count updated by pinning and retraction,
which full compaction remaps with their factor IDs. Other constraints are checked
against current Boolean and integer reasons, saved integer bounds and pending first
assertions, through a funded snapshot or, when that does not fit the slice, directly
per owner; an interrupted check keeps the candidate. Retired factors become inert
stable-ID slots that propagation queues and active readers skip. Integer rows own
their position in a reference cache, so retirement unlinks a row without scanning the
others; full compaction rebuilds cache positions and remaps integer-event
subscriptions.

Root simplification retires a pure-Boolean clause it has replaced through an opaque
admission handle captured before the replacement is admitted. Captured handles hold
no clause payload, survive factor-ID remapping, and are rejected as stale after a
later compaction generation. The caller authorizes retirement only after replacement
propagation and required root and peer publication finish; authorized requests stay
kernel-owned when optional pass buffers are released, and a round-robin continuation
rechecks current reasons and pending attachments so a protected request does not
block later ones. Reseeding or closing the scope invalidates outstanding handles.
Selected retirement reports logical disabling, payload unlinking and physical
release separately.

## Pseudo-Boolean learning

`pbLearning` auto-enables cutting-plane analysis for pure-Boolean models. Explicit
enablement admits Boolean conflicts in mixed models. The seed, each loaded source
constraint and each reconstructed reason must contain only Boolean coordinates;
integer order, equality and hole atoms keep clause fallback before Boolean indexing.
An asserting native PB result takes precedence over shared clause analysis and stays
in the CP store. Other mixed conflicts keep shared clause analysis.

The resolvent is a linear `≥` nogood seeded from the violated bound. Each step
rounds the reason up after division by its pivot coefficient, then adds it to cancel
the pivot. Weakening and saturation keep coefficients bounded and canonical. These
nonnegative combinations with Chvátal-Gomory rounding preserve implication. Exact
coefficients and degrees survive scaling, weakening, division and retention; degree
reduction uses the positive-literal degree captured before changing signed terms.
Mandatory arbitrary-precision and reified PB projections can supply arithmetic sources.
A reified side adds its inactive indicator with the body's complete positive degree,
so it remains conditional after undo. Indicator deductions use the opposite
implication's contrapositive. Negated equalities keep clause fallback. Retained
rows whose complete activity fits signed-word propagation use covering watches;
wider rows propagate exact slack with a static watch on each literal. Both expose
arithmetic reasons to later conflicts and use ordinary clause retention.

PB analysis and integer-row analysis each receive a work and cumulative modeled
allocation allowance from `arithmetic-learning-work` and
`arithmetic-learning-allocation-bytes`, inside the containing propagation scope.
Operand sizes price exact arithmetic. A local decline keeps clause resolution;
containing cancellation withholds the unfinished result. PB assertion sorts literals
by retained level and scans exact available weight and suffix maxima. A permanent
retention mark prevents forgetting; it does not establish logical scope. Source-valid atom
channeling clauses and incumbent restrictions can both have that mark. Observers
of enforcing incidence read complete PB terms and established values, because the
weighted covering watch set is only a propagation wakeup index. Unknown constraint
kinds or unnameable atoms withhold a complete-incidence claim.

## Integer row learning

`integerRowLearning` is opt-in and independent of pseudo-Boolean learning. Mandatory
finite `Linear` inequalities and the explaining half of equalities provide integer
rows. Mandatory `ExactLinear` rows provide the same source when every coordinate is
integer and the complete compact or arbitrary-precision projection preserves their
constants, term order and relation. Real or activated exact rows keep clause fallback.
Reified integer rows preserve the signed indicator selecting the inequality or
equality side, including the strict integer complement of an inequality. Each
resolved endpoint must name a historical arithmetic source with its exact guard
false-literal and historical guard truth. Compact lazy markers retain their deduction
boundary; eager arithmetic endpoints reconstruct that boundary from the bound journal.
Original and retained arithmetic rows can supply both conflict seeds and endpoint reasons.

The raw requested bound, reconstructed at the deduction's trail position, must
support the eliminated live endpoint; a hole-snapped stronger endpoint keeps clause
fallback. Interior holes in the remaining terms stay in the actual domains and do
not prevent a source-implied combination. Historical exclusions clipped by a pivot
move remain conservative: a bounded tail walk uses historical membership to exclude
later carves. Disequalities and unsupported projections keep clause fallback.

A selected variable-array `Element` can seed a conflict from either side of its
selected equality. Its positive index-equality atom keeps the source index, offset
and selected cell identity, including when the atom is created after selection. Only
a bound conflict of that equality is arithmetic; Element support explanations remain
clause reasons and cannot supply a numerical endpoint reason. Repeated cells keep
their selected index guard, and a result alias supplies no arithmetic row.

Opposite-sign nonnegative combinations use arbitrary-precision integer coefficients,
exact activity and gcd normalization with downward rounding of the upper bound.
Normalized rows retain sorted coordinates; cancellation merges their terms in linear
time and removes zero coefficients before normalization.
Ordered source coordinates use array normalization directly; unordered, repeated or
zero terms pass through aggregation before normalization.
Once the coefficient gcd reaches one, normalization copies the canonical terms and
keeps the bound without further gcd or identity divisions. Activity accumulates checked
signed words and promotes the partial sum at the first product or sum overflow.
Wide coefficients enter exact accumulation directly; failed word attempts remain
charged to the containing allowance.
Saturation expresses the row as a positive cover over integer distances from declared
box endpoints and caps each weight at its degree. If ordinary cancellation loses the
conflict, scaled mixed-integer rounding can reduce the reason before another
combination. These reductions cite only declared domains, never live branch bounds.

Endpoint discovery uses a heap ordered by historical deduction position, with
coordinate order breaking ties. An analysis caches validated source identities and
normalized inequality sides; every consultation still checks historical guard truth,
raw requested bounds and membership. Incompatible guards decline before historical
bound reconstruction. Discovery, arithmetic, candidate ordering and duplicate scans
share a per-analysis work allowance and cumulative modeled allocation allowance,
inside the containing propagation work and cancellation scope. Scalar work accounts
for operand word sizes. Exhaustion discards the optional derivation and keeps the
completed clause fallback; containing interruption withholds unfinished analysis.
Row width, decision depth, derivation length and retained-row count have no separate
fixed limits. Retained rows use ordinary learned-store retention.

Unsupported seed projection kinds keep their completed clausal analysis without
constructing integer discovery state. A box reduction whose degree already covers
every coefficient and needs no rounding retains its normalized row without rebuilding
term storage.

Each combination must remain conflicting. A retained row must propagate against the
session's historical domains at its first-UIP clause's native backjump and at the
mapped shared target, preserving clausal backjump selection. The raw assertion target
must have been a member of its historical domain: a later carve cannot remove that
earlier membership, and a historical hole cannot supply a raw bound assertion.
Foreign unavailable bound premises keep clause analysis. A retained row suppresses a
new consequence only with identical normalized coefficients, a stronger bound and an
unconditional or identical guard; a duplicate intermediate can continue its derivation.

Rows keep at most one distinct signed guard; a source needing a different guard
cannot join the combination. A guarded row asserts only where its guard already holds at both
the native and mapped shared target. An inactive guard withholds arithmetic
propagation, its activation wakes the row through a literal watch, and guard
negations remain premises of every retained-row deduction and conflict.

Rows belong to the prepared session's coordinates and source factors. Assignment
reconstruction does not authorize replaying a row in another preparation, assumption
container or objective context, and only the owning store attaches the typed object.
Opt-in integer learning gives finite satisfaction, enumeration and optimization
traversals a session-local shared atom registry that reserves the prepared Boolean
coordinates before naming integer decisions and their complements. The registry is
bounded; an exhausted registry stops the traversal as indeterminate. Without names,
a non-root integer decision cannot supply a shared premise and mapped admission
must decline. Every native conflict cascade repeats the mapped-target assertion
and complete premise checks before admitting a typed result; an unavailable
mapping keeps its clausal fallback, including cascades reached through an LP
consequence. Within one fixpoint publication, complete conjunctions of active
named assertions can share bounded proof subgraphs, and conjunctions retain every
child; an unavailable, unnameable or inactive leaf withholds reuse, and the memo
ends with the publication callback. Fixpoint facts already implied by shared
integer endpoints keep the shared premise without reconstructing a native proof.

Rows share the learned store and integer bounds propagation. A successful row
assertion also keeps its first-UIP clause in the native store; the two constraints
can prune different branches, publish their deductions together from the undo
position before either assertion, and follow their own retention policies. Retained
row deductions freeze eager historical clause premises, including the signed guard,
without compactable factor IDs. Arithmetic reuse reconstructs their deduction boundary
from the bound journal and validates the retained row's raw historical consequence.
Integer bound event subscriptions wake rows after undo and are remapped or removed on
forgetting. A live bound reason or a reason saved in undo protects its row from
retirement; after those references retract, retirement removes its guard and endpoint
subscriptions, and later conflicts use the retained rows' clause explanations. Only
clauses are shared across arms. Row satisfaction tests the complete maximum activity;
a satisfied endpoint literal cannot discharge the row or certify a root contradiction.

## Arithmetic diagnostics

A rolling capture can focus on the first admitted integer or PB row, retaining its
derivation and later consumers inside the fixed event and word allowances.

Single-core finite source execution can attach a native-session observer through
the ordinary portfolio factories. The hook wraps each selected recipe's selector
notification, preserving its fresh selector, choices and other notifications. It
identifies the worker and exposes its actual session before branching, including
replacement sessions. Observation does not select a recipe or grant source authority;
its work spends the containing invocation allowance.

An optional native-session `ArithmeticLearningTrace` records bounded arithmetic
analysis events before conflict retraction: seed and fallback literals, integer
endpoints and historical row domains, signed guards, native and shared targets,
decline categories and stored arithmetic consumers. Historical domain holes exclude
later carves, and storage identities survive factor compaction and retire with their
constraint; only changed bounds or Boolean pins count as consumer events. The trace
is absent by default, stops with explicit truncation at its fixed limits or an
arithmetic value outside its signed-word event format, reads
existing state without reconstructing reasons or running propagation, and supplies no
proof or source-mapping authority; an incomplete trace cannot establish exhaustive
applicability.

Successful integer cancellation records the selected variable, source factor,
historical deduction position and whether the reason needed mixed-integer rounding.
These choices bind a long derivation without guessing its pivots from the final row.

## Learned-clause retention

Native forgetting starts at restart boundaries with a configured cap. Permanent and
glue clauses (default LBD at most 2) survive; remaining space favors lower LBD. Cap
eligibility and survivor quotas count active constraints, so stable retired slots do
not consume the quota. A resumable root continues a pending window before branching
while it advances, taking further attempts within the same slice; an unfunded
attempt lets search proceed and stays retryable at later restarts. A completed pass
waits for the next restart before aging tiers again, and reaching the active quota
completes the pending pass without starting another tier epoch. Below the cap,
retired-slot cleanup uses the containing grant without aging tier policy. The SAT
preset enables capped tiered retention:

| Tier | Initial classification | Lifetime |
|---|---|---|
| Core | At/below glue threshold | Never deleted |
| Mid | Through LBD 6 | Survives reduction; idle clauses demote to local |
| Local | Above LBD 6 | Aggressively deleted; conflict use promotes to mid |

Tiers persist independently of later LBD. [ClauseDb](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ClauseDb.kt)
and [SearchLearning](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchLearning.kt)
own the native and shared stores.

General and custom cap selection uses the same handle-preserving window: a max-heap
over the best eligible LBD keys that each funded step can resume without a
whole-store buffer. Permanent clauses, protected reasons and policy survivors reduce
the heap quota, and a pending eviction that gains protection also consumes it.
Current or historical reasons can keep additional rows above the soft cap. The
default native policy keeps its LBD and handle heap across reductions; native LBD keys
are immutable between admission and handle remapping, and only new admissions,
first uses and mid-tier aging carry classification work. Epoch markers keep a demoted
mid row for its demotion window and prevent duplicate effects when use and aging
overlap; a use after classification enters the next epoch and stays protected by the
fresh eviction check. Core, permanent, glue, unit and reason protection keep their
ordinary authority. External tier changes request a rescan, a custom policy first
aborts the incremental index without retiring a live row, and post-undo replay
protects its queued clause handles and learned units. Incomplete trail propagation,
conflicts and root publication defer the window.

General retirement replaces a row's payload with an inert stable-ID slot and releases
its watch positions, integer-row cache entry and deferred or diagnostic aliases.
General and native metadata live in pages reached through a fixed-path page table;
admission funds page and index growth, and releasing a page's last row releases it
without renumbering live handles. Retired postings stay owned until propagation or
funded cleanup removes them, and selection and the following retired-slot compaction
share one grant, compaction receiving only the remainder. Root simplification
transfers its fresh residual literal array into the learned store; other admissions
copy borrowed arrays. A protected row keeps its complete metadata page.

Shared learning uses stable handles and bounded owned indexes for clauses, LBD
ranking, watches, signatures and live reasons, and is uncapped by default. Capped
reduction keeps glue clauses, units and recent clauses, then retires eligible clauses
in descending LBD order, older clauses winning ties. A window fixes its admission
boundary, so appended clauses enter a later window, and epochs keep recent-use
protection without a whole-store reset. The modeled-byte soft limit runs
independently at propagation boundaries; units, glue clauses, live reason owners and
pending first attachments form its floor, while copied trail reasons and queued
component publications stay privately protected. Logical retirement disables
propagation before watch, signature and index removal releases payload and metadata,
and a partial release keeps its owner and cursor across calls. Root reseeding releases
obsolete copied reasons; terminal release drops shared learning without rewriting
lifetime counters. A cancelled automatic scan permits one propagation opportunity
before another attempt, including when a suspended decision retracts and retries.

---

Previous: [9. Search and propagation](09-search.md) · [Contents](README.md) · Next: [11. Search policies and completion](11-search-policies.md)
