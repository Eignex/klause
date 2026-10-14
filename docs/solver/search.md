# Search and propagation

Complete search explores alternatives, restores state when a branch fails and
checks complete assignments before publication. Finite propagation narrows Bool+Int
domains; arithmetic components enforce the parts of the source model they own.
Learning reuses conflicts only when their explanations remain valid in that scope.

The traversal loop is shared: establish a fixpoint, check or choose a decision,
apply it, and retract to another alternative after conflict. Exhausting alternatives
supports a refutation only if all required checks completed. Interruption leaves the
unexplored space undecided.

This page owns traversal, finite state and reasons. [Sessions](sessions.md) owns
caller assumptions; [presolve](presolve.md) owns reconstruction;
[local search](local-search.md) and [portfolios](portfolio.md) own their strategy
state and scheduling. [Model ownership](../model.md) defines the engine views.

## Shared component search

[solver.search](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/)
owns decisions, levels, branching, retraction, complete checks, restarts and model
assembly. CP and theory components contribute residual state and reasons through the
same `SearchComponent` lifecycle. Batch `Theory` services participate through
`TheorySearchComponent`; directly incremental arithmetic components use the lifecycle
without a batch service. `TheoryComponent` is a deprecated compatibility marker.
Participation supplies no completeness guarantee: indeterminate required checks prevent
exhaustion from becoming a source refutation. See [theory roles](../theories.md#extension-roles-and-completeness).
The session exchanges Boolean literals, semantic integer bounds and
opaque theory decisions rather than native CP domains.

[SearchSession](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
coordinates component callbacks, conflicts and complete checks.
[SearchTrail](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchTrail.kt)
owns decisions, Boolean values, reasons and publishers, integer facts, undo levels and
pending assertion delivery.
[SearchLearning](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchLearning.kt)
owns shared clauses, attachment and propagation cursors, unit reawakening, reduction and
telemetry. Retraction removes trail facts and restores integer premises before clamping
the learned propagation cursor and notifying components. Root rebuild clears facts while
retaining learned clauses and reawakening units. Native CP clause state remains CP-owned.
[SearchRun](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchRun.kt)
contains the single traversal loop and owns frames, decisions, restarts, cancellation
cadence and resumable/terminal state.

Components obtain the invocation token, native allowance accounting and typed stop
diagnostics from `SearchContext.executionControls`. Alternate coordinators supply
those services through the same contract. [Resource allowances](resources.md#component-execution-controls)
owns their units, cancellation scopes and diagnostic precedence.

Internal components with retained root preparation implement `PreparingSearchComponent`.
Their cursor belongs to the post-seed context and returns no component verdict until
preparation completes. A suspended cursor does not enter propagation, branching or
model assembly. The enclosing handle resumes it with the same context and native
allowance; closing discards it. [Portfolio preparation](portfolio.md#polynomial-root-preparation)
defines polynomial checkpoints and the distinction between slice suspension and
solve-wide cancellation.

Solution-guided LP bounding may read verified peer values before its first local
witness. During that descent, the peer values precede saved phases and numerical
LP value preferences; variable selection and all branch alternatives remain intact.
An admitted local witness ends that ordering priority. LP-free search retains its
first descent before importing solution-guided peers. Importing a hint retains the
trail and learned state and supplies no local incumbent, pruning or publication
authority.

### Lazy branch selection

`SearchBranchProposal` supplies exhaustive, mutually exclusive alternatives without
requiring the deductive component lifecycle. `SearchBrancher` retains its public
component interface for compatibility. Installed and separately supplied legacy
branchers can also implement the proposal interface to declare a category; legacy
branchers without a category participate as finite proposals. Arithmetic built-ins
declare arithmetic proposals. Categories describe the supplying search mechanism,
so a finite heuristic can still propose a Boolean decision.

`SearchBranchSelection.Default` consults the supplied Boolean heuristic first,
then installed components in installation order, then separate legacy branchers,
then independent proposals. `Priority` captures each of Boolean, finite and
arithmetic exactly once and consults them in that order, preserving the relative
proposal order within each category. Reading a category performs no probe. A
selected proposal prevents later probes; null falls through and an empty list
exhausts the residual node. Default selection retains the single-component path.

Each run captures its selection policy separately from Boolean variable selection
and model continuation. Selection changes neither component installation nor
propagation order. The run applies every returned alternative through the same
trail, accounting, cancellation, restart and backjump machinery. Required unknown
checks still prevent exhaustion from becoming a refutation. Invocation scopes and
conditioned traversal floors follow [sessions](sessions.md).

A conditioned run retains the decision level at which it opens. Restarts return
to that level, and learned backjumps below it fall back to chronological traversal.
Exhaustion is then conditional. The query owner retracts the condition after the
run; the source root and its immutable atom meanings stay with the session.

Fractional source integers split at exact floor/ceiling thresholds. CP decisions use
Long values; open theories use arbitrary-precision thresholds. Stable complementary
atoms must exist before first-UIP learning. Boolean atom names belong to one immutable
source root; changing root meaning requires a fresh session. Unnameable conflicts remain
chronological and unexplained.

[SourceBoundAtom](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SourceBoundAtom.kt)
owns neutral exact source-coordinate bounds, terms and normalization limits. Integer
splits prove an integral activity and retain arbitrary-precision adjacent thresholds;
rational splits retain complementary strictness. Normalization copies, combines and
orders source terms before registration. Unsupported source kinds, coefficient growth,
term/bit limits and registry capacity decline without installing a partial pair.
Retraction and restart preserve immutable atom meanings and Boolean names. Positive
scalar multiples remain distinct. The linear adapter alone converts these predicates
to LP rows and checks source ids against its source model. Retained linear queries
and LP cut-premise naming consume the neutral predicates; legacy registered payloads
resolve through the same canonical meaning. Deprecated linear-package
constructors share canonical equality and names through `SourceBoundPredicate`; they
retain compatibility payload access when they create the first registration. Neither
an atom nor its registration establishes multi-component witness agreement.

Exact affine optimization contexts use the same session and chronological frame
stack. Their model-facing authority component records Boolean and integer coverage
alongside checked source bounds or refutations. Resolving one optimized node prunes
that node without issuing a learned clause. Only a complete coverage tree can prove
a global optimum or limit; a checked source ray can finish existentially. A slice
pause preserves the current node and pending alternatives. See
[linear certification](../lp/certification.md#refinement-and-strict-inequalities).

Complete assignments use typed source-keyed values from each owner. The open executor
reads those coordinates through its source-bound assembler and retained reconstruction
mapping, rather than decoding concrete components. All required checks finish before
assembly. Missing, unmapped or multiply represented coordinates leave publication
unavailable; decoding supplies neither agreement between peer fragments nor proof
coverage. Published snapshots retain exact values after their producing components close. CP-learned Boolean clauses
remain natively owned by CP while their shared consequences reach theory peers. The
shared store does not duplicate the CP pin.

Open linear arithmetic can prepare immutable source row addresses, exact forms and
Boolean dependencies once for one canonical theory fragment through
`prepareExactLiraSource`. Its opaque factory opens a fresh component for each source
session. The direct public component constructor prepares those same inputs for its
own component. Neither route shares Boolean levels, atom registrations, LP state,
reductions, outcomes or model-contribution callbacks between openings. A partial
initialization belongs to its original context and must be closed before opening a
replacement; immutable preparation does not make it reinitializable. A resolved
comparison remains asserted through its LP scope; deeper checks reuse it until
retraction removes that scope. Root
checks can substitute newly fixed columns. Disequalities re-evaluate their justified
direction against active bounds, and complete witnesses still check source rows.

Source-bound registrations pair prepared execution descriptors with component
factories. The collection validates canonical source identity and rejects duplicate
factor and coordinate ownership before invoking a factory. Standard CP,
Boolean and arithmetic opening uses that collection; the traversal does not select
concrete arithmetic routes. Factories create fresh component instances and retain
their optional branching, model-blocking and conflict-resolution capabilities.
Repeated opening shares read-only source data and completed projections, preserving
the default component and branch order.

## Arithmetic coordinate agreement

Prepared registration collections with several arithmetic completion groups install
`SourceCoordinateAgreementComponent` after their ordinary participants. Each group
must implement `SourceCoordinateAgreement`; unsupported groups leave the combined
result indeterminate. Standard preparation retains one combined arithmetic owner
and installs no agreement participant or contribution guards.

The exact-linear adapter exchanges normalized source bounds through the neutral
atom registry. Peers enforce the same predicates, including strictness, guarded
rows, arbitrary-precision integers and active branch conditions. Published facts
stay at their shared decision level; retraction removes conditions whose premises
disappear. Their source validity belongs to the publishing adapter. Unexplained
publications cannot provide missing antecedents for shared learning.

Each source integer/real coordinate has one declared registration owner. Explicit
peer collections guard numeric contributions against those declarations; duplicate
keys remain rejected by normal assembly. After fragment checks, agreement reads
that existing immutable typed snapshot and validates it against each peer's current
source rows and conditions. It neither decodes another witness nor combines private
fragment points. A peer requirement can constrain an owner's completion; a rejected
chosen point alone does not refute all other source completions.

Rational linear agreement admits exact integers and rational real coordinates.
Missing, oversized, algebraic/symbolic or otherwise unsupported contributions remain
indeterminate rather than rounded. Combined ownership remains the default for those
fragments. Source deductions that prove a conflict can refute their active scope;
global exhaustion still requires complete traversal coverage and source proof
admission. Agreement participation itself supplies neither general theory
combination, procedure completeness nor optimization/bound authority.

Agreement uses the existing invocation cancellation, complete-check accounting and
caller native work meter, including coordinate reads and source-row discovery.
It opens no fresh solver, ledger or allowance. Scalar-row
normalization and atomic arithmetic can overspend cooperatively; interruption
withholds a witness. All native peers remain owned by the invocation resource
registry, and published snapshots outlive their producers.

## Finite propagation

Fresh presolve rebuilds retain the originating cancellation token and accounting
destination. See [presolve cancellation and accounting](presolve.md) for ownership,
probe-count scope and cooperative deadline limits.

[PropagationSession](../../klause/src/commonMain/kotlin/com/eignex/klause/propagation/PropagationSession.kt)
owns the finite-domain trail and fixpoint. Sessions over one immutable model can share
built-in stateless propagation projections and occurrence indexes. Incremental payloads, scratch arrays,
watchers and failure contexts belong to each state. Failure clauses and premise-variable
sets use separate state storage, leaving reversible payloads intact; each fire clears
its previous failure, and undo discards pending failure context. The native SAT lane
keeps its own state and leaves general CP failure storage unallocated.

Prepared capability summaries read the selected projection descriptors. Built-in
providers declare their existing execution routes; replacements supply their own
filtering, scoring and check roles.
`SOUND_FILTERING`, `BOUNDS_FILTERING` and `DOMAIN_FILTERING` describe deduction
mechanisms; they do not promise full consistency at every size or resource limit.
AllDifferent filtering selects its cached incremental path before the full matching
fallback. The fallback retains value mapping, matching, Hall reasons, pruning and
cache publication in one private phase with borrowed state.

Complete finite checks pin the full assignment and run a completed CP fixpoint.
They stop after an interrupted root or before further coordinate pins when their
cancellation token expires. Interrupted verification remains indeterminate.
Local-search invariant satisfaction is a separate check from its graded score.
Continuous arithmetic and floating objective scores require the existing source
publication checks; a zero heuristic score is not an exact certificate.

Gaussian XOR and difference-system helpers deliberately omit local-search scoring: their
retained source rows enforce the assignment. Symmetry helpers also omit scoring because
source witnesses need not satisfy a chosen CP representative. The finite difference
system is posted after presolve only when its fragment has a guarded edge and an edge
between two unfixed root integer columns. Edges to zero or root-fixed columns act as
unary bounds and retain their original propagators. An unconditional edge between
unfixed columns can still join unary guarded bounds. Objective-bound overlays
deliberately omit CP propagation. Every inert built-in role carries a reason. A sound LP
family can decline emission under feature, resource or domain gates, and omitted rows
weaken the relaxation.

Product bounds clamp corner products to signed-word endpoints instead of wrapping them.
Repeated operands use nonnegative square bounds and exact integer square-root inverse
bounds. The inverse uses signed-word division comparisons, so it cannot overflow or
publish an approximate endpoint. Mixed-sign input domains retain their interior values;
complete assignment checks still enforce the complete relation.

### Custom factor publication checks

Custom factors retain the `Propagator` and `Invariant` extension paths. A custom
`Propagator` without an `Invariant` maps to `NoInvariant`, which contributes no
local violation. Before publishing a zero-score candidate, standalone LS and its
ALNS consumers pin every Boolean and integer coordinate in a private propagation
state containing the uncovered constraints. A completed full-assignment fixpoint
checks the candidate; root consistency alone does not. Custom propagators must
reject invalid complete assignments, as required by their CP execution path.
Constraints requiring continuous coordinates without an exact local checker
explicitly decline finite local-search execution.

Rejection applies only to the candidate. Interrupted checking withholds publication
without refuting the source or its assumptions. Caller completion runs after the
uncovered finite checks and retains responsibility for exact arithmetic completion.
Completion preserves the verified discrete coordinates; replacement or cancellation
withholds the witness.
Covered invariants and deliberately redundant built-in factors do not require this
extra propagation check. A custom `Invariant` supplies local checking and scoring
through its interface contract but does not supply a CP propagator.
Structural-only custom factors refuse CP and local-search execution with route-specific
errors. Custom implied linear rows remain available to LP relaxation independently of
either execution interface.

### Shared projection preparation

Shared table preparation transfers no-op verdicts only under matching contiguous column
domains. Each private propagation projection retains one preparation context across its
chunks. MDD preparation sharing includes the initial and accepting states; value
relabeling prepares an index over the relabeled transition array. Reversible live
tuples, diagram reachability and all assignment-dependent payloads remain state-owned.

`SearchComponentSet` contains mutable participants and permits one session
construction attempt. Its claim is consumed before atom attachment, including when
construction fails; reuse rejects without touching a second registry or component
initialization. Ordinary push/pop and conditioned runs operate within that one
session. Repeated solves open fresh sets from prepared registrations. Direct
`SearchSession` construction remains available to adapters that own their component
instances and installation lifetime.

Prepared factories receive an opening owner for construction-time resources.
The invocation owns every installed closeable peer independently of its CP/theory
compatibility accessor. Failure and terminal cleanup follow the reverse registration
contract in [sessions](sessions.md#theory-invocation-scopes); factory selection and
resource ownership do not change traversal order or specialized capabilities.

### Branching and global deductions

Finite branching receives the native conflict's full variable and factor attribution,
including implication-graph variables for learned conflicts. VSIDS bumps each implicated
variable once per conflict, including a failed decision already in that set. CHB applies
one conflict reward to each attributed variable. Conflicts
cascading from a native learned assertion also update activity and failure counters;
they have no fresh branch decision to blame. Successful CP fixpoints deliver newly
reported deductions to propagation-aware selectors in both satisfaction and optimization
search. Phase saving captures determined values before native trail
retraction, retaining propagated polarities as well as decision polarities across
backjumps and restarts.

Boolean-only models journal each pin's variable id and tag without integer-state padding.
Mixed finite models retain aligned integer-state columns for domain retraction.
Integer undo replays complete saved domains and interior carves through a typed
restoration phase, borrowing captured callback references. Boolean retraction and
reverse log traversal belong to the caller. Callback order, bound/hole histories
and restored literals follow the same trail lifetime.

Native learned unit clauses watch their asserting literal and the deepest false premise.
An immediately conflicting learned clause watches its two deepest premises. Retraction
therefore opens a watched premise before an unwatched premise can require a fresh unit
deduction.

General CP optimization builds its private state in retained batches before traversal.
The projection owns completed occurrence indexes; the state owns domains, literal/event
watchers, payload slots and its root queue. Assumption installation retains each pending
decision and fixpoint until it commits its trail mark. Shared component publication is
batched after those assumptions reach a complete fixpoint. Publication into the private
shared session does not publish a solver result. Closing or failing installation releases
that cursor without exposing partial state. Eager propagation/session constructors keep
their public behavior. The [portfolio contract](portfolio.md#private-cp-preparation)
defines handoff and [resources](resources.md) define the accounting boundary.

Dom/wdeg initializes from the session's prepared occurrence indexes. Failure weights and
its heap belong to the solve, retain conflict bumps across restarts, and apply bumps
received before the first selection once during initialization. Fresh selectors isolate
solves that share a projection.

Cumulative edge-finding runs in both time directions over fixed durations, demands and
capacity. Reversed deductions tighten latest starts and cite the historical integer
bounds and definitely-present task premises. Arithmetic ranges that cannot be
represented safely decline the reversed pass.
Window preparation owns active-task selection and checked time coordinates.
The edge scan borrows those task, bound, energy and demand arrays for tree
activation and bound deductions. Forward and reversed scans retain their
historical explanation and propagation lifetimes.

### Explanation lifetime

Propagators supply sound source reasons for deductions and conflicts. An assignment's
undo lifetime can be deeper than the effective level of its reason: Boolean pins use the
deeper of that level and the current decision depth so an asserting backjump retains its
consequence. Reified arithmetic skips reason construction when a settled relation agrees
with its assigned indicator. New indicator pins and opposing assignments retain the pin
and conflict explanation protocol. Single-sided linear indicator deductions record the
existing lazy linear payload during trailed search. Shared Boolean premise export
resolves this payload into historical literals before traversing its proof graph.
Conflict analysis resolves it against the bounds at the pin, rather than later domains;
untrailed and two-sided deductions retain eager explanations. Lazy-reason resolution
retains eager and memo hits in the caller. A marker builder combines extended
premises or asks its factor for historical literals before the caller checks and
memoizes the completed reason. Linear bound propagation
constructs a reason only when the proposed bound tightens the live domain; unchanged
bounds skip eager explanations and lazy payload allocation. Single-variable equality and
disequality indicators over root domains contained in `{0, 1}` propagate as direct
channels: an indicator fixes its integer value, and a fixed integer pins the indicator.
Wider domains retain the general linear propagator.

Factor cores traverse the full reason graph with session-owned dense-id scratch. The
traversal clears visited ids between extractions and reads learned native clause
variables directly, retaining the same factor-core membership across restarts and
learned-clause compaction.

Assumption cores retain the complete applied seed prefix when propagation refutes it,
or the full assumption set when a search tree is exhausted. Decision-level conflict
summaries guide traversal; they do not enumerate every transitive assumption premise.
Returned cores are jointly refuted with the hard model and need not be minimal.
Assumption seeding stops before subsequent pins after cancellation or an interrupted
fixpoint. Its applied prefix remains incomplete and cannot certify the full seed.

Clause minimization reuses its redundancy memo storage within an analyzer. It clears
the memo before each conflict because levels and antecedents belong to the frozen
reason graph of that analysis, rather than the variable across search paths.

Native Boolean propagation keeps a session-local truth byte per Boolean variable for
single-load literal checks. Pins and undo mirror the canonical assignment bitsets;
open, false and true remain distinct. The general lane does not allocate this mirror.

Native learned clauses retain one replacement-scan cursor per live clause. Scans start
after the last replacement and wrap over the complete clause, so undo can reopen any
earlier literal. Clause compaction preserves the cursor with its surviving watches;
base-clause storage remains immutable and uses the fixed scan order.

Native learned-database reductions pack surviving literals and metadata forward in
their existing buffers, then truncate the tails. Survivor order, watches and scan
cursors remain aligned without allocating replacement column buffers.
Root Boolean assignments' learned-factor reason ids follow the same renumbering;
discarded handles clear while their frozen literal antecedents retain the deduction.

The native CP analyzer and shared first-UIP analyzer have distinct counters and
ownership. A shared conflict with usable reasons can assert and backjump. Sound
resolvents that cannot assert retain chronological fallback. Root refutations, unusable
explanations and native CP analysis are outside the shared asserting/non-asserting
counters.

### Table explanations

Table explanations use build-local caches for historical domains, carve positions and
materialized literals. These caches do not cross propagation or undo. Ground supports
use membership/value-set checks, and sparse root members avoid walks over large
unconditional holes. Support gathering can stop after all live values of a ground column
are covered, while every tuple is still checked for feasibility. The reversible live
prefix is committed once per completed sweep, including empty-prefix conflicts; sibling
branches restore filtering from trailed state.

Construction-time root propagation folds unary Table restrictions whose expanded
supports total at most 4096 values. It intersects a sorted survivor set with the current
domain without walking that domain's span, so local search samples from the same
unconditional restrictions. Larger unary relations and multi-column tables retain
deferred preparation. Search-time filtering retains STR2 explanations.

LP explanations cite selected source bound witnesses and recursively expanded row,
fixing and cutoff premises. Current CP propagation and source LP conflicts do not
include an optional exact-theory row-propagation scanner or explanation weakening pass.
See [LP consumer contracts](../lp/architecture.md#consumers-explanations-and-cuts).

## Learned-clause retention

The shared learned store has its cap off by default. When capped, reduction runs at
restart boundaries after pending attachments are drained. Glue clauses, units and
clauses used for propagation since the last reduction survive. Remaining capacity keeps
lower LBD first, then older insertion for ties. Protected clauses can leave the retained
count above the nominal cap.

Selection uses a Boolean drop mask. A bounded LBD histogram applies when the maximum
score is at most 4096 and at most four times the eligible count; sparse ranges use the
packed sort with the same ordering. This mechanism preserves the retention rule. The
native CP store keeps its own reduction policy.

See
[SearchSession](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
and
[ClauseDb](../../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ClauseDb.kt).

## Theory completion

A component's partial consistency check or relaxation witness does not accept a
complete source assignment. Every participating owner must finish its required
check before publication. Extra theory components also constrain finite LP primal
proposals and transferred initial candidates; ordinary traversal remains available
when a proposal cannot be completed. Mixed root rounding and feasibility-pump
proposals satisfy the discrete fixpoint under caller assumptions. When the caller
supplies an original-source tolerance check, rounding first offers the LP point's
continuous coordinates with each discrete target to that same composed verifier.
Unaccepted float-coordinate proposals retain discrete completion through the
real-leaf owner. Exact invocations require that owner's certified coordinates.
Only a complete checked
witness reaches incumbent admission; an incomplete or unbounded completion does not
publish a heuristic result. The pump can continue after a declined completion under
its existing root allowance. Root primal proposals must improve the available local
or external incumbent cutoff before admission; a completed candidate that misses
that cutoff permits further pumping without renewing its budget. An integral distance
optimum with no fractional support uses seeded perturbations of live discrete
coordinates to escape a declined rounding. Fixed coordinates remain fixed, and
source completion and admission check every proposed assignment. Mixed root pumping
can use the current peer's discrete assignment as its first target. Continuous
coordinates are completed independently; the peer target remains a proposal and
must improve the available cutoff before publication.

After a declined nearest rounding, mixed root probes try a peer discrete target
and upward/downward roundings of the retained LP point before distance pumping.
Each distinct proposal receives propagation and full source verification or real completion. Directed
rounding reuses the root solve and retains its time/work allowance; it neither
changes the relaxation nor installs a source bound. This permits a rounded
capacity proposal to retain the continuous flow that nearest rounding can remove.
Root primal solves use the remaining cumulative root and LP work allowances rather
than the adaptive node-query allowance. A stopped dual iterate supplies only a
bounding candidate and cannot supply a primal target. Primal/dual fallback opens
a fresh solve with the remaining allowance; it does not renew the root budget.

Root primal diagnostics distinguish rounding and pump pinning from proposal
completion and cutoff admission. `PRIMAL_ROUNDING_ROOT` and
`PRIMAL_PUMP_ROUND_ROOT` record whether a discrete proposal was produced;
`PRIMAL_PROPOSAL_ROOT` distinguishes discrete verification, real completion,
final verification, cancellation and non-improving outcomes. Their phase times
are inclusive and overlap native solve and verification phases. These observations
carry no witness or proof authority.
`PRIMAL_LP_PRIMAL_ROOT` and `PRIMAL_LP_DUAL_ROOT` distinguish completed optimal
candidates from stopped or unavailable points before rounding.

[Theory contracts](../theories.md) define the fragment-specific checks and evidence.
Difference graphs, exact linear systems and polynomial completion keep their own
state while sharing the traversal's source facts and decision lifetime. Integer
integrality and finite factors cannot be dropped to obtain an arithmetic witness.

Open quadratic integer optimization supplies exact complementary wide predicates
through a theory brancher. Its optimization node policy prunes against certified
real bounds and checked feasible incumbents, without turning objective cutoffs
into source infeasibility conflicts. Closed integer nodes continue chronologically
through the shared frames. See [nonlinear integer quadratic objectives](../theories/nonlinear-integer.md#quadratic-objectives).

## Contributed model ownership

Components contribute immutable values through typed keys, with one owner per key.
The assembled model captures its map and each typed key's ownership hook at publication;
reads retain the associated value type without copying. Exact source integer and real
keys preserve arbitrary-precision and rational coordinates alongside the compatibility
keys. Opaque legacy values remain borrowed read-only. Extensions retain open keys for
arrays, functions and native theory values; their captured payloads must be immutable.
See [API migration and mappings](../api.md#typed-contributions-and-migration).

## Monotone clausal primal probe

Pure Boolean clausal optimization with at most 64 positive-cost variables can seed
incumbents through greedy feasibility repairs when those variables occur only positively
in hard clauses. A private satisfaction repair first obtains a feasible assignment with
unaccepted cost variables true. Caller and baked-root pins form its stable scope.
Temporary cost and pure-literal pins enter private projection; source reconstruction
restores their original coordinates.

Before trial repair, free true values become false when that single change preserves
every hard clause. A retained clause walk tries cheap cost values in descending weight
order. Unit closure fixes caller, root and cost assumptions; focused moves change only
free variables. Clause penalties increase when no candidate move improves the weighted
violation and survive between trials. Each trial permits at most one move per source
variable, retains its assignment and random state across slices, and restores the
previous feasible assignment on failure. Moves spend the caller's shared node budget at
the existing local-search rate; clause and assignment visits charge the owning arm's
work.

The initial feasible model and remaining costly values use private projected
satisfaction repairs. Unit propagation removes fixed literals and satisfied clauses. Two
bounded Boolean elimination passes reuse the presolve mechanism and its source
reconstruction records, then surviving variables receive dense private coordinates. Each
projected trial owns a fresh satisfaction session that resumes across slices. Lifting
restores fixed source values, expands surviving coordinates and applies elimination
records in reverse order. Projection, construction and search work remain charged to the
arm. The containing arm owns overspend debt; private sessions enforce their current
slice allowance without repaying the same debt again. Work before a private slice is
included in the containing arm's cumulative charge. The original model and its ordinary
search remain intact. Private infeasible verdicts contribute no outer proof.

Only feasible trials retain their cheaper cost assumptions. Clause-local polishing
removes unnecessary costly values. Each polishing activation has a 100 ms cap and
retains the whole-solve deadline; it can finish a feasible model after its search slice
expires. The composed verifier checks the full original proposal before publication. A
completed proposal can be verified and delivered while the arm repays work debt, which
continues to block further search. Verification retains its 100 ms cap and the
whole-solve deadline.

Private satisfaction uses the SAT preset with inprocessing and target capture disabled
and starts fresh Boolean decisions with false. Initialization and cumulative work are
charged to the owning arm. The private clausal walk prices literal scans, clause
bookkeeping and assignment visits at the propagation work rate. Each move still spends
the solve-wide move allowance; its literal work is not also priced as a generic
constraint local-search instruction. The complete probe receives at most half the
remaining solve allowance in active execution time, capped at 5 seconds. Trials retain
the caller's solve-wide node budget and the whole-solve cancellation. The probe runs
once before ordinary optimization and is skipped behind an existing shared incumbent or
within repair fragments.

Search counters distinguish eligible probe starts, trial starts, returned models,
completed improving proposals, and proposals verified and installed as arm incumbents.
The CLI emits these counters together when a probe starts, including zero counts for
stages it did not reach. Outcome counters distinguish private refutations from
incomplete stops without a model. These counters describe heuristic progress and carry
no proof.

## Statistics

| Field | Meaning |
|---|---|
| `propagations` | Finite Boolean, singleton, bound, hole and survivor-set deductions reported by completed search fixpoints; excludes decision and root pins |
| `learned` | Learned consequences observed by the traversal, including native CP learning |
| `caNotApplicable`, `caNonAsserting` | Native CP analyses declining a usable or asserting consequence; exported by every CLI format |
| `propagationWork` | Monotonic dispatch, literal, watcher, level and linear-term/reason visits, including work across undo |
| `rootPropagationWork`, `rootPropagationMs` | Initialization root-fixpoint work and time, including private preparation |
| `propagationMs` | Fixpoint elapsed time; some counted reason scans are outside it |
| `openAssertingConflicts` | Shared first-UIP analyses yielding an asserting backjump |
| `openNonAssertingConflicts` | Sound shared conflicts retaining chronological fallback |
| `openReductionNs` | Total shared reduction selection and watch-rebuild time |

A nonzero non-asserting count is a reason to capture the conflict and inspect component
reasons. Zero does not establish complete factor reason coverage. Missing metrics remain
missing, including root theory refutations that never enter shared search. Ratios of
visit work to fixpoint time are coarse because their scopes differ.

The CLI exports these native work/time fields under `nativeCpPropagationWork`,
`nativeCpRootPropagationWork`, `nativeCpPropagationMs` and
`nativeCpRootPropagationMs` when the aggregate snapshot has positive native work
or time. Root work and time are subsets of the respective propagation totals,
not additive phases. Worker snapshots aggregate their native measurements; these
values are not an invocation wall-time partition. An absent native block cannot
classify open-theory root or propagation activity, whose elapsed times are not
measured by these native providers. A zero emitted millisecond value can result
from truncating an evidenced sub-millisecond measurement. The
[CLI invocation fields](../cli.md) distinguish frontend routing, selected solve
pipeline, declared theory and reported backend without extending search clocks.

`openReductionNs` is zero when the cap is off or no reduction pass is needed.

## Selected propagation projections

`PropagationProjectionProvider` selects a source-bound factory and its filtering and
pinned-assignment-check capabilities during preparation. Engine projection creation
rejects a factory whose `NoPropagator` result disagrees with its declared inert role.
An unsupported role refuses construction. Alternative deductions must remain sound
for the original factor, including watcher subscriptions and explanations.

Packed SAT propagation requires built-in selected clause descriptors. Replaced
clause deduction uses the general CP lane so native propagation cannot bypass it;
unchanged delegated descriptors retain native eligibility. Built-in mechanisms
retain owner-local table/MDD preparation.

An inert descriptor requires an explicit reason and cannot claim an exact check:
its absent mechanism needs an enforcing peer or separate source certification.

Selected partial or inert finite deduction keeps an original-source check at a
complete point. The prepared check plan is immutable; each point uses a private
propagation state over original factors. Its work contributes to the invocation's
CP work and time counters. If those factors have no complete original checker,
the leaf stays indeterminate. Source-real checks remain the arithmetic completion's
responsibility; objective overlays remain enforced by objective policy.

Source preparation retains captured factor projection descriptors when selecting
open ownership and optimization rounds. Domain closure retargets those descriptors
without changing coordinate ownership. A factor rewrite reselects providers before
constructing the compact CP view. Complete arithmetic still owns its original
source predicates and exact certificates; a selected finite relaxation is a sound
bounding view rather than source-completion authority.

Prepared CP registrations reuse built-in stateless projection data and open custom
mutable deduction mechanisms separately for each session. A clause assigned to a
custom CP projection is omitted from the shared default clause propagator. The
Boolean peer retains canonical Boolean coordinates and branching; complete point
checks retain the clause's original source authority when selected filtering is inert.

Polynomial and quadratic workers retain the prepared finite-factor descriptors when
opening their private finite projection. Exact arithmetic still checks its original
source fragment; finite-provider selection does not replace that authority or its
resumable lifecycle. See [the polynomial route](../theories/polynomial-real.md).


## Selected theory encoders

Prepared factor encoders supply the full relations used by shared arithmetic
components. Admission and component construction consume the same selected capture. Variable
ownership is independent of factor participation: finite declarations retain finite
state and choices, while a propagator and encoder can consume one factor together.
Component descriptors distinguish primary `factorIndices` from overlapping
`participatingFactorIndices`, retaining one authoritative coordinate owner. Linear relations retain conjunction/disjunction structure, row activators,
Boolean terms, strictness and original factor attribution. Integer difference admission
checks complete selected relations and the safety of their combined graph. Polynomial
installation conjoins factor relations and all private auxiliary definitions before
completion checks the combined system.

Source integer/real coordinates retain their typed publication keys. Finite integer
embeddings in polynomial queries are private reals whose fixings come from finite state;
an unfixed interval remains a relaxation and supplies no witness authority. Conditioned
linear products capture the same source integer pins as their completion scope. The
original source checker remains required when numerical execution proposes a point.
Relaxation selection cannot omit an encoder's enforcing relation or grant completeness.

Exact linear components wait for finite-owned coordinates before proposing arithmetic
splits or accepting their final completion. Finite publications supply scoped bounds
and exact fixed values; the finite owner retains hole membership. Numeric contributions
come only from the declared coordinate owner. An isolated arithmetic service with a
point outside a source value set withholds that witness instead of treating its bound
hull as the source. Retraction invalidates both peers' scoped outcomes.

A composed finite registration omits duplicate original point checks for factors
whose mandatory arithmetic peer installs a complete enforcing encoding. The
finite adapter still rejects interrupted fixpoints, and every arithmetic check
must succeed before the shared leaf is accepted. This policy belongs to that
registration; standalone finite projections retain their original check obligations.
