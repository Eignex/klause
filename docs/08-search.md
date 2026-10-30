# 8. Search and propagation

Complete search explores alternatives, restores failed branches and checks leaves.
Its shared loop establishes a fixpoint, checks or chooses a decision, applies it and
retracts after conflict. Finite propagation narrows Boolean/integer domains;
arithmetic components enforce their owned relations. Exhaustion proves a result
only when every required check completed. [Learning](09-learning.md) reuses the
reasons those mechanisms supply.

## Boolean task coverage

`BooleanProofTasks` owns a bounded partition over at most twelve distinct Boolean
variables. Every assignment selects exactly one cube; the complete caller condition
stays in `BooleanProofContext` with the original source revision, semantics, objective
direction, exact strict cutoff and prepared mapping identity. Cubes use original source
Boolean IDs; the caller validates their translation into private coordinates. This
protocol runs no workers and supplies no solver
verdict. Its serialized caller owns execution, scoped result checking and loss detection.

Only one attempt owns a region. Loss, release and cancellation return unclosed regions;
duplicate, foreign and stale completions cannot close coverage. Checking runs before
closure and rechecks ownership afterward. Witnesses, stops and unavailable checks leave
the region pending. Native refutations and independently checked refutations remain
distinct. Complete native coverage alone supplies no independent UNSAT or optimality
verification. Replacing any context invalidates every lease and closure, including after
an incumbent tightens the cutoff. Accepted closures survive ordinary cancellation.

An internal `BooleanProofScheduler` drives a fixed pool of retained repair workers
through that board. Each worker keeps its lease and traversal across slices and
reuses its prepared session across sibling cubes. Only sample-free exhausted
queries close native coverage; checked witnesses leave their region open. Loss
retires private state and returns unresolved coverage while retaining work and
statistics. Caller-condition replacement retires every worker and invalidates
closures. Changed source or mapping identities require a fresh scheduler. The
executor admits identity-mapped pure Boolean clause satisfaction only; objectives,
numeric restrictions, symmetry reductions, unmarked cross-worker exchanges and additional
components decline. A checked witness remains terminal until the caller condition
changes; it cannot become a later sample-free refutation.
The executor can use either constant-objective repair or a retained pure Boolean
satisfaction traversal. Both retain conditioned state across siblings; satisfaction
reseeding clears the preceding query verdict and trail without renewing its
containing quota. Pooled task exchanges use a conditioned adapter: permanent
scope clauses and direct global nogoods stay private, while the same session keeps
its import filter across sibling queries. Source and mapping admission remain
separate from that export promise. Native clause queries retain the original solver's
immutable projection; sibling restarts stay above their conditioned root and keep
the containing quota.
An optional probe-first coordinator advances the whole source under the ordinary
scheduler grant before creating a partition. A paused query supplies its first live
Boolean decision after caller pins; root-fixed and unread columns supply no pivot.
The same engine transfers into the matching cube of a two-region partition, retaining
its learning, root work, statistics and containing quota. Sibling reseeding can replay
search, and that replay remains charged. A completed source probe needs neither a
partition nor a peer root. Lost probes and task attempts retain charges.
It runs through an explicit internal entry point; ordinary portfolio selection
retains its existing policy.

`BooleanCoverageAttribution` retains bounded exact region keys and their first workers.
It separates repeated entries by one worker, duplicate entries across workers and
duplicate native refutations. Exact equality gives a lower bound on overlap; entries
alone prove no completed coverage or saved work. `SearchBranchAttemptObserver` observes
a tightening alternative before assertion, including immediate conflicts. Finite
backtrack exposes it through `BacktrackParams.branchObserver`. Its Boolean adapter
captures all established Boolean values and the attempted literal; callers restrict it
to pure Boolean models with identity source Boolean coordinates, the complete variable
count and a matching context.
Hidden finite or arithmetic branches require another vocabulary. Capture limits mark
partial observations, and retirement releases retained keys. Literal payload bytes
exclude collection capacity, headers and all solver state. Observers run inline and
must not mutate the context.

## Shared component search

[solver.search](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search)
owns decisions, levels, branching, retraction, complete checks, restarts and model
assembly. CP and theory components contribute residual state and reasons through the
same `SearchComponent` lifecycle. Batch `Theory` services participate through
`TheorySearchComponent`; directly incremental arithmetic components use the lifecycle
without a batch service. Participation supplies no completeness guarantee:
indeterminate required checks prevent exhaustion from becoming a source refutation.
See [theory roles](14-theories.md#extension-roles-and-completeness). The session
exchanges Boolean literals, semantic integer bounds and opaque theory decisions
rather than native CP domains.

Five owners divide the work:

- [SearchSession](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
  coordinates component callbacks, conflicts and complete checks.
- [SearchTrail](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchTrail.kt)
  owns decisions, Boolean values, reasons and publishers, integer facts, undo levels
  and pending assertion delivery.
- [SearchLearning](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchLearning.kt)
  owns shared clauses, attachment and propagation cursors, unit reawakening and
  reduction. Clause identifiers and watch-list order remain stable across index
  growth and rebuilds.
- [SearchRun](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchRun.kt)
  owns depth-first frames, decisions, restarts,
  cancellation cadence and resumable/terminal state.
- [SearchFrontierRun](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchFrontierRun.kt)
  retains ordered alternatives and replays their shared decision prefixes through
  the session. Pauses retain the selected alternative and its replay position.

Incremental initialization advances each component's iterator with that component
as the active publisher. Synchronous initializers run inside the iterator too.
Root refresh retains the same ownership: facts already established by CP reach
peers without returning to CP as extra private decisions.

Numerical frontier traversal orders alternatives by inherited checked objective
bounds, visiting unbounded alternatives first. It keeps both children of every
split, including unresolved leaves. Improved checked incumbents reawaken those
leaves for dominance closure. An ancestor bound can close a queued child after
its derivation checks in the child's source scope, without another LP query.
Replayed decisions charge the ordinary work and
decision allowances; the retained queue is capped at 4,096 alternatives. A failed
required check or exhausted queue allowance withholds completion.

The numerical consumer keeps source conditions and derivations in a separate
coverage tree. Global bounds combine every remaining alternative, including
unvisited siblings and refuted regions; a region without a bound or refutation
withholds a global bound. Queue rank alone supplies no proof authority.

An opt-in sparse source consultation checks fully established active rows before
Boolean completion, using a zero objective. A source-checked contradiction closes
the current coverage leaf through ordinary traversal. Unchanged active rows and
numeric restrictions skip repeated numerical work; changed or retracted states
invalidate pending queries. Completed no-conflict consultation keys can survive
retraction and numerical-owner release; they retain no point or proof. Feasible
partial points supply no model or bound, so complete queries remain required.
Preparation, numerical work and source checking
share the containing cumulative source LP allowances.

Opt-in numerical refutation propagation names integer alternatives with source-bound
atoms. At a Boolean-complete prefix, one missing integral restriction in a retained
checked refutation can imply its adjacent complement. The shared trail retains a
clause reason over the established prefix facts; an unnamed premise declines the
deduction. The coverage tree retains the excluded sibling's checked refutation
alongside the surviving branch. Retraction removes the implication, and replay
restores it only after its named antecedents hold again.

Opt-in numerical refutation resolution combines checked clauses across an already
named complementary integral predicate. Shared clause resolution removes the pivot;
an exhaustive source integer split checks both imported refutations in the resulting
scope. Only a fresh checked import can exclude a queued region. Such exclusions and
unit deductions retain ordinary coverage-tree accounting. Complete Boolean scope and
opening restrictions remain premises; stopped resolution leaves alternatives open.
Unit reasons select established support predicates, including stronger endpoints,
and omit unrelated integer decisions. Clause and proof storage share the bounded
invocation-local refutation index and cumulative source allowances.

A model policy can pause required completion with `SearchModelDisposition.Pause`.
The run retains the assembled model and its decision trail, and retries that policy
before node pruning, branching or complete checks. Pausing neither blocks the model
nor records an indeterminate region. Cancellation still bounds each retry; a root
reset discards pending completion with the old traversal.

Retraction removes trail facts and restores integer premises before notifying
components; a root rebuild keeps learned clauses. Components obtain their token,
accounting and stop diagnostics from `SearchContext.executionControls`; see
[resource allowances](06-sessions.md#component-execution-controls). Components with
retained root preparation implement `PreparingSearchComponent` and return no verdict
until it completes; see
[portfolio preparation](12-portfolio.md#polynomial-root-preparation).

## Traversal and branching

An exact Boolean objective can install a permanent pseudo-Boolean strict-improvement
cutoff at a root even when other integer coordinates remain. Signed normalization
uses exact arithmetic; a cutoff outside the learned word-sized kernel declines
without changing ordinary objective bounds.

Branch proposals determine the next exhaustive split. Component hints order its
alternatives; source-bound atoms give arithmetic decisions stable Boolean names.

### Lazy branch selection

`SearchBranchProposal` supplies exhaustive, mutually exclusive alternatives without
requiring the deductive component lifecycle; `SearchBrancher` keeps its component
interface. Each proposal declares a category (Boolean, finite or arithmetic)
describing its supplying mechanism, and branchers without one count as finite.

`SearchBranchSelection.Default` consults the supplied Boolean heuristic first,
then installed components in installation order, then separate legacy branchers,
then independent proposals. `Priority` captures each of Boolean, finite and
arithmetic exactly once and consults them in that order, preserving the relative
proposal order within each category. Reading a category performs no probe. A
selected proposal prevents later probes; null falls through and an empty list
exhausts the residual node.

Selection changes neither component installation nor propagation order: every
returned alternative goes through the same trail, accounting, restart and backjump
machinery, and a required unknown check still prevents exhaustion from becoming a
refutation. A conditioned run keeps the decision level at which it opens; restarts
return there, backjumps below it fall back to chronological traversal, and
exhaustion is conditional (see [sessions](06-sessions.md)).

For an entirely theory-owned integer difference graph, source-proved relative
interval proposals precede Boolean choices through arithmetic category priority,
registering exact complementary difference predicates; see
[difference logic](14-theories.md#difference-logic).

### Polarity hints

Once a Boolean variable is selected, a component can suggest which side to try
first. Component-owned candidate hints reach ordinary Boolean traversal through the
`SearchCandidateHints` surface; caller and deferred hints keep precedence. A
preference only reorders the two sides of an already selected split, preserving the
complementary alternative. Propagation and complete component checks keep their
authority, and a hypothetical value supplies no arithmetic fact or feasibility claim.

Built-in providers derive preferences from a completed arithmetic point: a single
linear completion owner reuses the point of its last numerical check, and the opt-in
`openHintFlips` provider indexes source clauses and prefers the polarity that breaks
fewer of them. Index construction is bounded and charged to the opening token; see
[native arithmetic allowances](06-sessions.md#native-arithmetic-allowances).
Solution-guided LP bounding may also order values by verified peer incumbents until
its first local witness. Hints supply no incumbent, pruning or publication authority.

A non-restarting retained complete optimization worker with an external cutoff reads
the current pooled assignment at its first root and revalidates it before using it
as a local incumbent. A witness equal to the containing portfolio's cutoff
can seed that worker's incumbent and objective propagation at its first root; it
need not improve the containing portfolio again. Invalid assignments and assignments
above the cutoff do not seed it. This starting witness supplies bounds without
priming solution guidance or earning incumbent progress. Restarting workers retain
their discovery and root-primal startup path. Rebindable neighborhood searches retain strict
improvement admission, and exhaustion under an external cutoff keeps its conditional
result scope. Exact repair cutoffs remain rational through admission and discrete
dominance. LP pruning may use their rounded-up floating enclosure; equality in the
displayed objective cannot reject an exact improvement.

An exact repair can retain a primal point accepted by the original source checker
while its continuous completion still requires certification. The point's rational
source objective, mapped to the working scale and direction, must strictly improve
the cutoff. Source tolerance acceptance supplies no nominal exact coordinates or
optimality authority: missing certification keeps the fragment unresolved, including
when it publishes a witness. The pending point shares the completion's work and
cancellation scope, survives slices with that leaf and is discarded on reseeding or
close. A rejected point supplies no conflict.
This witness-only path publishes the point and continues chronologically through sibling
assignments; it cannot block an unresolved leaf as a proved nogood. Other traversal
modes stop incomplete when their model continuation would require such a blocker.

### Finite LP directional guidance

Finite optimization records passive immediate parent/child floating LP optimum gains
in minimized working objective units. Each gain is divided by the fractional movement
to the actual lower or upper branch. A missing or interrupted optimum, infeasibility,
negative or nonfinite gain, integral point or mismatched split supplies no numeric
sample. These advisory observations supply no certified bound or pruning authority.
The common objective constant stays in the scope key and is excluded from gain
subtraction, preserving small gains when the constant exceeds floating precision.

The finite consumer is opt-in through `BacktrackParams.lpDirectionalGains` or
`lp-directional-gains=true`, with LP bounding and branching enabled. Two recent samples
in each direction permit a balanced gain score; otherwise reduced-cost/fractionality
scoring and the configured selector keep their precedence. Directional scores compete
with each other before fallback, so objective units are not mixed with reduced costs.

Samples share one retained exact base model, active-row identity, objective mapping
and incumbent cutoff. Changed rows or cuts, layout or objective rescaling invalidate
history and pending comparisons. Restart and assumption reseeding clear history.
Ordinary sibling retraction preserves compatible observations and the immediate
parent split; a skipped child cannot borrow a deeper descendant's objective.
Directions expire after 256 node visits without a sample. At most 8,192 variable
histories and 128 ancestor splits are retained; bookkeeping charges the existing LP
work allowance and creates no extra LP query. Invocation close releases the scope.

### Leaf-completed columns

Presolve keeps every column slot, so a column whose readers were all reduced away
(folded comparison indicators, eliminated or substituted columns) stays a free
column. A one-model CP search, satisfaction or optimization, leaves such columns
undecided: [LeafColumns](../klause/src/commonMain/kotlin/com/eignex/klause/propagation/LeafColumns.kt)
names the columns no factor declares, less every objective term, and the built-in
selectors pass over them. A leaf then reads `false` or the domain minimum for each
one still free. Because nothing reads these columns, that value extends every
assignment the leaf's checks accept, and refutation is unchanged.

The columns are skipped, never fixed: assumptions and neighborhood pins still
assign them, and a later search over the same model may need either value.
Enumeration, sampling and model counting tell solutions apart by these columns,
and a supplied search component may read any column through the shared trail, so
those searches decide every column.

### Source bound atoms

Fractional source integers split at exact floor/ceiling thresholds: Long values in
CP decisions, arbitrary precision in open theories. Learning needs stable
complementary atoms, so each split is named as a Boolean before first-UIP analysis;
names belong to one immutable source root, and unnameable conflicts stay
chronological.

[SourceBoundAtom](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SourceBoundAtom.kt)
owns these neutral exact bounds. Integer splits keep adjacent thresholds, rational
splits keep complementary strictness, and terms are normalized before registration.
Coefficient growth, size limits or registry capacity decline without installing a
partial pair, and retraction preserves atom meanings. Only the linear adapter turns
the predicates into LP rows. The linear component names a source disjunction when
arithmetic branching reaches it; a group that cannot be fully named leaves the search
indeterminate.

### Assembly and registration

Source-bound registrations pair prepared execution descriptors with component
factories. The collection rejects duplicate factor and coordinate ownership before
invoking a factory, and the traversal never selects concrete arithmetic routes.
Repeated openings share read-only source data and projections while creating fresh
component instances. `SearchComponentSet` permits one session construction attempt;
push/pop and conditioned runs operate within that session, and cleanup follows
[invocation scopes](06-sessions.md#theory-invocation-scopes). Open linear arithmetic
prepares its source rows once through `prepareExactLiraSource` and opens a fresh
component per session.

Exact affine optimization records Boolean and integer coverage alongside checked
bounds; only a complete coverage tree proves a global optimum, while a checked
source ray can finish existentially. A checked node bound that excludes improvement
over the invocation's checked incumbent closes that unexpanded subtree. Its scoped
derivation remains in the coverage tree; global optimality rechecks the composed
bound with the original-source witness after every alternative is covered. See
[linear certification](17-linear-programming.md#refinement-and-strict-inequalities).

Complete assignments use typed source-keyed values from each owner, assembled only
after all required checks finish. Missing or multiply represented coordinates leave
publication unavailable. [Theory completion](09-learning.md#theory-completion) describes
how those values are contributed.

## Finite propagation

The finite-domain fixpoint runs watched propagators until no domain changes or a
conflict appears. [Explanation lifetime](09-learning.md#explanation-lifetime)
defines the reasons retained for its deductions.

An opt-in cost-aware worklist retains FIFO order when the oldest factor's measured
mechanism-family work is at most the fifteen-visit inspection-and-shift budget. Otherwise it selects
the least measured work within the oldest eight pending factors, retaining FIFO
order for ties. Each family
starts unobserved and receives a sampling opportunity; completed and interrupted
fires update a smoothed containing-work estimate. Learned provenance credits do not
rank execution: a learned clause has its clause mechanism's cost. No new timer runs.
Stable removal leaves the oldest factor in place, and after eight bypasses it must
fire. New wakes append, so every pending factor has a bounded number of earlier
dispatches. Selection and queue shifts charge the containing propagation work.
Family estimates survive retraction without retaining factor identities; queue
replacement resets the bypass count. The fixed family array belongs to the state
and participates in its retained memory observation. FIFO remains the default.
Exact dispatch, bypass, forced-opportunity and charged selection-work counters
survive queue replacement and state retirement; selection work is a subset of the
containing propagation work.

[PropagationSession](../klause/src/commonMain/kotlin/com/eignex/klause/propagation/PropagationSession.kt)
owns the finite-domain trail and fixpoint. Sessions over one immutable model share
stateless propagation projections and occurrence indexes; incremental payloads,
scratch, watchers and failure contexts belong to each state. Undo discards pending
failure context, and fresh presolve rebuilds keep the originating cancellation token;
see [presolve](07-presolve.md).

Learned integer rows subscribe to lower/upper bound events in the dynamic watch
index. Learned-store compaction removes retired subscriptions and remaps surviving
factor identities; static event and delta-consumer indexes retain their source ids.

Boolean-only models journal each pin without integer-state padding. Integer undo
replays saved domains and interior carves. Unconditional root carves are kept in
state-owned interval runs, so late equality atoms and historical queries still see
them. Exact integer pins retain their owning decision level separately from bound
decisions on the same column.

Integer domain maintenance retains an interrupted history/atom-wakeup cursor. No
subsequent mutation may start until that cursor completes or retraction discards it.
LP primal pinning stops at an interrupted fixpoint. Incremental assumption reseeding
retracts an interrupted pin, including an equality stopped between its two bounds,
and retries from the completed prefix on the next reseed. An interrupted prefix
cannot certify the requested assumption set.

Search-time holes keep their reason, level and undo position in trail order, with
a per-variable value index for lookup. Retraction removes index entries with the
discarded witness tail. Unconditional root runs remain separate from that index.
Scoped survivor restrictions retain reversible before/after domains with their level
and undo position, so late equality atoms recover conditional exclusions without
enumerating the gaps.

Root snapshot capture compares enumerable survivors with the saturated hole count,
independently of numeric span. Sparse sets are captured as ascending survivor
restrictions and reconstructed without materializing their gaps. Enumerable
domains retain exact cardinalities for exhaustive-search admission and radices,
including sets spanning both signed-word endpoints.

Layered MDD filtering watches interior removals as well as bounds. Forward and
backward reachability and weighted path bounds use full symbol membership. Shared
root reachability is admitted only when every position contains the whole diagram
alphabet; a hole inside that alphabet requires private reachability. Reversible
layers restore the filtering state across sibling branches.

Table and MDD scans poll the containing invocation token every 64 primitive visits,
or every visit for a metered token. Visits contribute to propagation work. An
interrupted factor stays queued: Table clears its completed-sweep marker, and MDD
invalidates private reachability before another delta can read it. Completed domain
deductions remain sound. Root preparation can retry the queued work; interruption
in an ordinary search fixpoint latches incompleteness and withholds model and fact
publication. Batched Table/MDD exclusions retain a cursor through their hole
history and atom maintenance. Root preparation finishes that cursor before firing
another factor; undo discards it with the domain and atom trail. Atom wakes retain
their first establishment while a resumed cursor repeats completed wakes. Fresh
decision atom cascades use the same pending completion and retract with their pin.
Bulk allocations, immutable domain construction and non-interruptible propagator
internals remain atomic.

Cumulative propagation polls the same token during task, edge-window and start-bound
scans. Its window scratch belongs to one fire, so interruption discards that scratch
and leaves the factor queued to rebuild it. Completed deductions and their historical
reasons survive; an incomplete fixpoint supplies no LP rounding witness or refutation.

Shared CP assertions also poll the search context's containing allowance. A cancelled
assertion retracts to its completed parent and retains the same alternative for a
pausing traversal to retry. Retraction clears native incompleteness only after it
has removed the interrupted native level. Publication stops before visiting another
deduction or constructing its premise; a partial publication cannot certify a leaf.
A conflict or indeterminate publication result ends the callback immediately.
Slice polls charge completed native propagation visits before deciding whether the
slice has expired.

A completed fixpoint can share Boolean- and root-grounded native subproofs with search
peers through a bounded memo of at most 4,096 entries. No memo survives a callback,
suspension or retraction.

#### Arithmetic kernels

Propagators guard signed-word arithmetic explicitly rather than wrapping. Increasing
chains reject endpoints whose successor would overflow; product bounds clamp corner
products and use exact square-root inverses for repeated operands; integer `Power`
uses exact comparisons and filters at most 256 values per fire, falling back to bounds
on wider domains. Mixed-sign inputs keep their interior values, and complete
assignment checks still enforce the full relation.

`DivMod` collects supports from at most 4,096 operand pairs before narrowing any
role. Repeated roles must agree within each tuple. Support application follows the
distinct coordinate order, tightens the lower bound before the upper bound and
then removes unsupported interior values.

#### Difference systems

The finite difference system is posted after presolve only when its fragment has a
guarded edge between two unfixed root integer columns; edges to fixed columns act as
unary bounds. Finite preparation can also append a redundant difference system over
exact real rows and real table selections, whose vertices are private arithmetic
coordinates rather than finite real domains. Source guards and table index atoms enter
the graph directly, so a contradictory selection is excluded before all discrete
columns are fixed. A consistent graph supplies no model; all original factors and
source checks remain. See
[real graph admission](14-theories.md#real-graph-admission-and-checks).

### Selected propagation projections

`PropagationProjectionProvider` selects a source-bound factory and its filtering and
pinned-check capabilities during preparation. A factory whose `NoPropagator` result
disagrees with its declared inert role is rejected. Alternative deductions must stay
sound for the original factor, including watchers and explanations.
`SOUND_FILTERING`, `BOUNDS_FILTERING` and `DOMAIN_FILTERING` describe deduction
mechanisms, not full consistency at every size.

Exact integer rows select compact signed-word propagation when their constants fit
and the sum of coefficient magnitudes is at most `Long.MAX_VALUE`, keeping residual
checks within 128 bits; wider rows keep arbitrary-precision propagation. Packed SAT
propagation requires built-in clause descriptors, so a clause assigned to a custom
projection uses the general CP lane. AllDifferent tries its cached incremental path
before full matching. Assignment-dependent payloads such as live tuples and diagram
reachability stay state-owned.

Unguarded affine integer Distinct projects unit operands onto a shared value graph
without adding finite coordinates. Exact offsets identify values; a compact
translated universe uses signed words when its width fits, otherwise value keys
remain arbitrary precision. Matching and SCC filtering remove source values directly.
Large domains use translated Hall bounds when representable, with exact fixed-value
filtering otherwise. Reasons retain source positions and reconstruct bounds and
holes at the deduction trail position. Domain snapshots and matching seeds belong
to each propagation state; changed or restored domains invalidate the snapshot.

Ground Tables with at least 128 rows and an admitted support index keep a reversible
tuple bitmap beside the STR2 prefix. Immutable domain baselines identify changed
columns; sparse postings and dense masks union the newly excluded values' rows
before the prefix is compacted. A completed cold sweep builds the bitmap. The
started marker and baselines follow undo, including restoration above a payload's
construction branch. Interval Tables and storage declines keep the full STR2 scan.

`val-selector=max-sd` ranks small integer domains by completed extensional support
fractions, with at most 65,536 factor, position, row and word visits for one ordering.
Provider discovery and diagram admission share that allowance and cancellation
polling. Tables count live
tuples through the bitmap or the small STR2 prefix. Deterministic, unweighted MDDs
with distinct sequence variables cache prefix and suffix path counts, multiplying
them at each live edge to count accepted assignments for a value. Admission allows
at most 8,192 states and edges per diagram and 1,048,576 retained cells per state.
Domain identity checks invalidate counts after narrowing or undo; interrupted
rebuilds publish nothing. Counts exceeding signed 32-bit capacity, ambiguous or
aliased diagrams, cost diagrams and storage declines retain live-edge counts
joining forward and backward reachable states, including shared-root reachability. These
counts describe relation structure and have no deduction authority. Unavailable
or incomplete counters and larger domains retain residual-domain probing. The
ordering keeps every candidate, including zero-count alternatives.

The `propagation-guided` recipe bounds a residual-domain ordering to 32 candidate
probes and 262,144 work units, including each variable visited by residual scoring.
Work cancellation is cooperative inside propagation; restoration and indivisible
propagator operations can exceed the
allowance. A cut probe restores the completed parent and retains its candidate.
Completed probes retain their order or refutation, and every untested alternative
remains available. Exhausting this local allowance does not cancel the search.
Completed support orderings, probe orderings, completed probes, hypothetical
refutations, native probe work and local cap hits have separate counters.

DivMod retains complete signed tuples for at most 4096 live operand pairs in each
state's factor payload. Each fire recomputes live supports in reusable primitive
sets against all operand and output domains, including holes and aliased roles.
Dividend-indexed tuple buckets skip rows whose dividend is absent.
Output narrowing does not remove stored tuples. The payload survives retraction;
restored operand values outside its construction domains require rebuilding it.
Larger products keep bounds filtering without tuple enumeration, and a later small
branch can construct its own bounded relation. Deductions cite the current bound
and hole premises rather than retained branch assumptions.
Retention reserves the operand product times arity plus 64 cells per payload from
a 1,048,576-cell state allowance. Replacements reuse their reservation; tombstoning
can leave unused reservation. Exhaustion keeps complete small-product filtering
with temporary supports, bounding retained storage without weakening deductions.
Temporary supports use full scanning and omit dividend-index construction.

An inert descriptor requires an explicit reason and cannot claim an exact check, so
its absent mechanism needs an enforcing peer or separate source certification.
Objective-bound overlays omit CP propagation, and a sound LP family may decline
emission; omitted rows only weaken the relaxation. A factor rewrite reselects
providers before the compact CP view is built, and a selected finite relaxation is
never source-completion authority.

### Branching and global deductions

Branching receives each conflict's full variable and factor attribution, including
implication-graph variables of learned conflicts: VSIDS bumps each implicated
variable once, and CHB rewards each attributed variable. Successful fixpoints deliver
new deductions to propagation-aware selectors. Phase saving captures values before
retraction, so propagated as well as decided polarities survive backjumps and
restarts. Dom/wdeg failure weights belong to the solve and survive restarts; fresh
selectors isolate solves that share a projection.
Each fresh finite selector receives its native-session attachment before search
and LP branch proposals; the attachment can precede root initialization.

Circuit propagation removes targets claimed by fixed incoming edges and premature
chain closures throughout successor domains, including interior values. Full
circuits also exclude self-loops; optional cycles treat an absent self-loop as
mandatory participation even when it is an interior hole. Conditional removals cite
their fixed edges and required participation premises and retract with them.
Reachability and full-circuit dominator filtering inspect the remaining arcs.

General CP optimization builds its private state in retained batches before
traversal, and assumption installation commits each fixpoint before the next;
closing releases the cursor without exposing partial state. See
[private CP preparation](12-portfolio.md#private-cp-preparation).

Cumulative edge-finding runs in both time directions over fixed durations, demands
and capacity, declining the reversed pass when its arithmetic is not safely
representable.

Range-aware cumulative prefix energy also declines unrepresentable completions,
window widths, areas and energy sums. Capacity ceilings use quotient and remainder
so their rounding cannot overflow a representable surface.

Finite backtrack workers with the LP family disabled enable a redundant unary
projection inside fixed-data mandatory cumulatives over positive-duration tasks
whose demands exceed half capacity. The original factor
enforces every task. Selection keeps source task indices and admits 3–32 tasks
from at most 128, capacities through `2^20`, durations through 4,096, and live
windows through 4,096 with time magnitudes through `2^40`. Other shapes decline.
The unary passes share the containing propagation allowance; their historical
start reasons dispatch through the original cumulative. Retraction and interrupted
fixpoints discard pending subset failure context with the containing factor.
Shared preparation and workers using LP leave this projection disabled. Its policy
belongs to the worker; propagators read only their state-owned admission flag.

Unary resources also check overload over minimum durations while a duration is
unfixed or a presence unknown. Present tasks whose minima cannot fit between their
earliest start and latest completion conflict, and an unknown task whose minimum
would overload them is ruled absent; any channel over that presence then consumes
the exclusion. Its reason cites the overloaded set's start bounds, minimum
durations and member presences, never the excluded presence. Zero minima add no
load, and unrepresentable completion times decline the check.

Tasks sharing one exact signed presence literal run together or not at all. When
an unknown literal's tasks would together overload the present tasks, the literal
is ruled out, citing the same windows, minimum durations and present members, never
the shared literal. Only identical literals are grouped: complementary and distinct
literals are checked separately. Groups are fixed when the propagator is built, so
a factor without a repeated literal does no extra work.

Diffn's constant-size sweep filters positive-area compulsory parts. Strict boxes
with a zero dimension also receive the pairwise separation check, so the sweep's
empty-area shortcut cannot admit an invalid complete assignment. Non-strict boxes
with a zero dimension impose no overlap restriction.

Minimizing a single integer variable over scheduling globals raises its root lower
bound by a strip-energy floor. Chains of two-variable unit-coefficient rows and
maximum results prove which tasks end by the objective. Their energy from the
strongest left edge, divided by the capacity, bounds it: a Cumulative uses its
declared capacity, and a constant-size Diffn projects onto either axis with the
counted rectangles' perpendicular extent. The raise cites the live start and
perpendicular bounds it reads; starts or coordinates the model leaves open are
skipped.

### Complete finite checks

Complete points pass [source validation](07-presolve.md#source-witness-validation)
under a private full-assignment fixpoint. Partial or inert deduction needs a complete
original checker or enforcing arithmetic peer; unavailable checking leaves the leaf
indeterminate.

#### Custom factor publication checks

Custom factors keep the `Propagator` and `Invariant` extension paths. A custom
`Propagator` without an `Invariant` maps to `NoInvariant`, which contributes no local
violation, so before publishing a zero-score candidate, local search and ALNS pin
every Boolean and integer coordinate in a private propagation state over the
uncovered constraints. A completed full-assignment fixpoint checks the candidate;
root consistency alone does not. Custom propagators must therefore reject invalid
complete assignments.

Rejection applies only to the candidate, and interrupted checking withholds
publication without refuting the source. A custom `Invariant` supplies local checking
and scoring but no CP propagator. Structural-only custom factors refuse CP and
local-search execution, and constraints needing continuous coordinates without an
exact local checker decline finite local search. Custom implied linear rows stay
available to the LP relaxation.

---

Previous: [7. Presolve and validation](07-presolve.md) · [Contents](README.md) · Next: [9. Learning and theory completion](09-learning.md)
