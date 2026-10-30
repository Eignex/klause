# 17. Linear programming

Finite relaxations, mixed-integer models and [linear theories](15-linear-arithmetic.md)
share one floating revised simplex with exact certification. Model-facing adapters
assemble source-aware rows; the engine solves and certifies them; consumers interpret
the result in source coordinates. Shared search owns integer and Boolean alternatives.
LP feasibility alone establishes no integrality.

## Boundaries

```text
CP / COP, mixed-integer models, open theories, shared search
                         |
           model adapters, LpPropagator, effort policy
                         |
         exact model, bound trail, float solve, certification
                         |
       simplex.basis, simplex.exact, lp.lattice, util
```

[RevisedSimplex](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/RevisedSimplex.kt)
is the floating implementation and
[ProductionLpEngineFactory](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpEngineFactory.kt)
the default factory; `ComponentLpSolver` composes independent blocks for solving and certification.
`lp.engine` depends only on `util`, `simplex.exact`, `lp.lattice` and
`simplex.basis`; model assembly, factors, propagation, presolve and search stay
outside it, and consumers use interfaces and factories. See
[system boundaries](01-introduction.md#system-architecture).

`lp.lattice` is a leaf over `util`, owning exact integer matrix reductions and
triangular-block bounds. Its sparse fraction-free elimination polls cancellation
between row combinations and after bounded chunks of support entries, charging
only visited work. An interrupted reduction returns no rows, pivots, dependency
indices or inconsistency claim.

## Exact source row projections

Native exact linear rows keep their rational source meaning independently of
finite filtering and numerical emission. Compact coefficients use the integer LP
builder; wider coefficients use an exact positive row normalization whose rational
entries remain certificate authority beside the floating projection. A
normalization that cannot project to finite nonzero floating values declines.

Exact-linear preparation keeps templates for every source row and original column.
Unconditional rows are installed up front; guarded, Boolean-dependent and
disjunctive rows materialize when asserted, and a row enabled by an integer
indicator records both bound premises and retires when the pin retracts. Bounds
retract with the search scope while definitions stay available to siblings. These
definitions supply no witness authority: publication checks the original rows.

Compatible discrete preparation views retain immutable source-row templates and exact
integer endpoint metadata through their selected projections. Compact rows retain
canonical integer memberships and reusable coefficient buffers, reading primitive
source constants at their declared width. Each build maps source coordinates into
its own columns; the numerical builder copies coefficients into its private rows.
Guarded rows read the current pin on every emission. Bounds, premises and numerical
owners belong to the current build.
Templates retain neither a basis nor a checked claim.
Exact bound columns materialize only when an import needs them. Each endpoint
definition retains one completed normalized column; changing an open upper side
replaces the cached column without linking prior versions. Preparation reserves
pending construction work and retained storage inside its existing ceilings;
actual construction charges the consuming import. An already exact import retains
its matching column directly, without constructing a duplicate definition.
Completed and interrupted template preparation and import work also debit the owning
LP allowance. Import reports its actual charges before publishing authority and
rechecks cancellation after that debit.
Retained-source assembly and fixed-layout installation adopt the same immutable exact bound objects after a
complete match against the current normalized column, including its origin, tag,
integrality and both bound sides.

Factor identity preserves a row definition; row rewrites replace it. Factor
memberships retain their source integer and Boolean arrays without rebuilding a
tagged list. Component construction folds repeated factor roles, and integer and
Boolean coordinates remain distinct even when their numeric IDs coincide. Component
snapshots retain their row definitions, exact endpoints, stated bound sides and
objective coefficients. Unchanged row incidence retains the component partition.
Bound or objective changes replace affected snapshots;
joining rows replace merged components and retain unrelated ones. Explicit namespace
remaps, changed dimensions or semantics and source-to-finite transitions reject the
prior binding. A foreign source cannot use a prepared template index. Continuous,
custom and unsupported projections retain their ordinary emission paths.

Ordinary unchanged preparation validates ordered factor identities, integer endpoints,
stated bound sides, the objective constant and every component cost before sharing
the completed collections. Source-factor order indexes rows directly, and the
integer-bound map indexes exact endpoints without component lookup maps.
Component references retain the cost-coordinate
order. A failed match uses selective reconstruction; a source revision alone cannot
authorize reuse. The new source binding owns no predecessor snapshot, so repeated
compatible preparations retain no generation chain.

An internal selective-metadata preparation mode validates row identity and exact
bound sides before retaining the row index. A selected view whose handoff checked
ordered factor identities carries the same immutable layout token, so subsequent
template preparations need no duplicate row scan. Other rewrites and explicit
namespace remaps carry fresh tokens and validate their actual factor identities.
Bound changes replace their exact objects and retain unrelated row and bound definitions.
This mode retains no duplicate component partition or component-bound maps; numerical
consumers assemble those definitions in their own layout. Template component observations
therefore describe only the ordinary mode. Switching back to that mode constructs its
partition before complete-index reuse. The selective mode's source definitions carry no objective authority:
every assembly reads costs and the objective constant from its current objective,
so changing that objective requires no source-template cost scan or replacement.
Every templated mode can adopt compact integer bound columns during construction after
checking the normalized origin, width, stated sides, integrality and source tag.
Fixed-layout imports and retained source-fragment imports create no temporary exact
column for a matching template. IEEE and retained exact-state imports keep the
full exact-column compatibility check.
An identical stable source-domain table authorizes endpoint metadata reuse without
a repeated scan. Shared domain arrays require endpoint checks, including raw views
carrying a finite session's table; model identity or type alone supplies no such proof.
The ordinary mode retains its complete-match check. Both modes publish only completed immutable
snapshots within the same containing allowance and storage cap.

For retained source emission, the internal selective mode can use the owner's existing
column catalog instead of preparing whole-source metadata. A completed earlier fragment
supplies an immutable bound definition only after its source coordinate, normalized
origin, integrality and both bound sides match the current compact integer column.
Its lower endpoint must be a stated source bound; an invented lower clamp supplies no definition.
Conditional, auxiliary, IEEE and noncompact columns keep ordinary construction.
An empty catalog creates no speculative definitions. Each lookup charges the current
allowance; interrupted imports report that work and withhold publication after debit.
These reuses have their own observation, separate from introductions into the owner.
The catalog retains no additional cache or numerical result authority.

Template construction polls and charges the containing token, including failed
attempts, and caps visits and retained storage at 262,144 modeled units each.
Cold preparation first checks a necessary work lower bound within the same cap.
The check follows the selected mode's row, bound and component stages and charges
its own inspection; a refusal publishes no template. Persistent base assembly reads
declared root domains, and live source bounds enter through the scoped adapter.
Noncompact exact and IEEE layouts use retained source emission.
An incomplete attempt publishes no snapshot. Compatible views share one atomic
publication slot per source/finite scope, including temporary presolve bakes.
An emission update attempts missing template preparation once before its first dirty
region. A refusal leaves all its regions on ordinary emission; a later dirty update
can retry under its current allowance.
Preparation and
actual row/bound emission have separate observations in the existing LP phase ledger;
they do not establish numerical result reuse or certified deduction consumption.
Template observations accumulate fixed scalar totals per owning sink; immutable
phase maps materialize at snapshot, retaining declined preparation work and observed
zero emissions without a map allocation per consumer.
Sparse-trial assembly uses the same source templates and keeps its own admission,
factorization and certification allowances.

Element hulls share an index-value selector across properties of the same integer
index. Constant properties use one joint distribution, preserving their choice
correlations; variable entries keep their bounded big-M links. Admission enumerates
at most 1024 root choices within each array's index range, excluding root holes,
and charges shared columns once against the aggregate LP size guard. Open source
index bounds decline the hull. Selector presence follows live index membership;
source definitions identify the index and value independently of the emitting
factor or its array offset. Retained factor emissions reuse those definitions and
may retain duplicate channel rows. Their row estimate remains conservative.
Automatic admission treats all properties sharing an index as one candidate,
declines incomplete groups and admits the smallest complete groups within the
existing aggregate size budget. The ordinary small-hull threshold applies per
group. Each engine applies its selected indexes to retained rebuilds; changing the
objective resolves the caller's admission intent again. Root pruning removes a
complete index group together.

The objective-cone relaxation follows integer, Boolean and real objective support
through supported linear and Boolean factors. Ordinary, exact and continuous rows
extend the same closure; pinned real atoms and integer indicators retain their
activation premises when emitted. Integer big-M reifications and optional hull
families stay outside this projection. Disconnected rows are omitted, while a
connected dominant component remains subject to the ordinary size and work guards.

## Numerical authority

Optional simplex diagnostics retain bounded progress observations per numerical
invocation. A retained invocation replaces its completed observations while
compatible factors remain owned by the scoped solver. A paused invocation keeps
its diagnostics alongside the cursor. Observations supply no admission, work
allowance or certificate authority and do not select numerical policy.
Completion observers receive the charged invocation totals and completed trace;
a pause withholds that completion and retains its observations with the cursor.

The exact matrix, costs, objective sense/scale/constant, bounds, strictness,
integrality, origins, source premises, ordered basis and nonbasic statuses determine
the problem. Floating factors, values and reduced costs are revision-keyed working
state; pricing weights are heuristic history.

The model uses `[A | I] x = b` with bounded structural and logical columns.
`ExactLpModel` supports arbitrary bounds and origins; re-centering changes bounds,
RHS and objective constant together. Compact Long inputs keep authority beyond
`2^53`, wider data uses rationals, and a Double input denotes its exact IEEE value.
Projection to Double must not infer fixedness from equal rounded endpoints. The
engine supports missing bound sides and FREE statuses. MPS decimal authority is
separate; see [MPS semantics](05-arithmetic-formats.md).

## Consumers, explanations and cuts

Consumers decide when to request certified bounds, fixings, conflicts or witnesses
and how to apply and retract them. Their policy stays outside the engine.

Finite tuple-packing substitution exposes placement selectors as ordinary Boolean
columns. Per-Table choice rows and shared occupied-value capacities use the core
cardinality projection, independently of scalar Table hull admission. Native source
reconstruction recovers the selected tuples; an LP point alone supplies no witness.

### Bounding and explanations

[LpPropagator](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
maps source decisions to bound assertions, retracts through the trail and supplies
certified bounds, fixings and Farkas conflicts under an effort governor. Farkas
explanations cite the selected bound witnesses and all contributing row premises;
every learned literal is false at the conflict and implied by the cited antecedents.
One integer or disjunction leaf cannot refute its sibling branches.

The model-facing bounding callback supplies pre-separation floating optima and
checked infeasibility outcomes to finite search hints. Directional history accepts
only compatible retained model and row scopes; it adds no certification request.
[Finite guidance](08-search.md#finite-lp-directional-guidance) owns selection and
history lifetime.

A node query that makes pivots but stops on its work allowance before returning a
point grows the adaptive request within its existing ceiling and remaining
cumulative allowance. Such queries retain every work charge but do not consume
the effort governor's point-assessment warmup. Completed queries assess that
preceding work too; numerical stops, zero-pivot stops and infeasibility candidates
provide no growth feedback.

For mixed models, node bounding uses the
[mixed deduction operations](#mixed-deduction-operations),
keeps the strongest checked objective bound and applies only integral consequences
with complete explanations. For a bound `L`, reduced cost `d(j)` and cutoff `U`, a
fixing allows movement at most `(U - L) / abs(d(j))` from the minimizing endpoint.
Objective changes retract dependent bounds, cuts and clauses, or restore an
objective-independent region.

The explicit `+sparse-trial` LP option selects a bounded root trial for compact
closed unconditional linear sources. Emphasis levels leave it disabled. Admission bounds
source assembly, then inspects emitted nonzeros, connected-component dimensions,
exact scalar width and row-intersection fill risk. Custom emitters decline before
trial assembly because they have no source-size reservation. Sparse storage is reserved
before assembly, along with its construction work. Assembly cancellation and numerical
admission refusal retain that work charge; cooperative preparation polls cancellation
before publishing another chunk. The sparse storage allowance is independent of the
ordinary dense cost proxy. The root trial uses the ordinary
bound, fixing and conflict consumers with their existing exact checks; cuts and
expanded hulls stay outside its allowance. Root rounding preserves the ordinary
admission's probe intent; sources declined by that guard gain no automatic probe.
The initial root bound and subsequent root propagation share the existing scoped numerical owner;
handoff requires matching source identities and root scopes, and live bound adoption
rechecks authority before reusing its basis. A declined retained source layout
retires that attempt and uses the existing standalone numerical route within the
same remaining work and storage limits.

An admitted sparse root advances the scoped owner's floating cursor in adaptive
work quanta. Pauses publish no point or bound and charge only the new work. A
pivoting pause can grow the next quantum under the existing adaptive ceiling;
the operation keeps its original cumulative work and pivot limits. Consultations
continue only within the remaining root, invocation and cancellation allowances.
Authority edits discard the cursor before adoption. Completed floating results
enter the existing exact bound check, independently of dense exact continuation.
The sparse root charges its last numerical delta before admitting bound-only
rational checking under the remaining work and a 16 MiB checking reservation
inside the trial's storage cap. Exhaustion,
cancellation or certificate refusal withholds the root bound; checking acquires
no dense basis continuation.

Completed root points can supply private rounding guidance under the same
objective identity. A live root owner copies at most one MiB within the remaining
root and LP allowance. A closed auxiliary rounding solver transfers its completed
output array into the same private storage cap after its work is charged. The
constant-size handoff can preserve a completed point across a slice interruption;
later pinning still polls its fresh token. Rounding uses the stored coordinates
without another numerical solve; its propagation and source-publication checks
still run. An incomplete or differently optimized point supplies no retained guidance.
Rounding pins use probe fixpoints without materializing implication lists;
an interrupted pin restores the last complete propagation state and withholds its proposal.

Preparation is capped at eight million modeled work units; root and node LP work
stop at 150 million units or the containing invocation's smaller allowance.
Preparation admission respects the remaining caller root, slice and LP work limits;
tighter root deadlines and work shares remain in force. Bounded atomic numerical
operations can finish past a work boundary, and the ledger retains their actual cost.
The two-GiB modeled storage envelope includes source authority, sparse owners,
scratch and capped factor construction. Each sparse factor attempt reserves entries
before writing input, fill or factors, and stops at two million entries or eight
million work units. Declines publish no factors or exact rank claim. Cancelled
and failed attempts retain their work charges. Component estimates alone do not activate
component-owned node solvers. Existing work, pivot, factor-fill, certificate and
consumed-deduction statistics accompany the admission estimates. Sparse admission
never changes the dense exact-continuation cell or column caps.

### Immutable component ownership

The explicit `lp-component-ownership=true` setting lets finite node bounding retain
one numerical owner per independent row-coupled block. Each child keeps its basis
and exact result authority. Empty structural rows form one logical-column child;
its ordinary solve and exact checks retain feasibility, strict sides and logical
objective costs. Bound, cost, row activation and premise changes
invalidate only affected children; unchanged exact child states reuse optimal
floating results and successful bounds under the same certification policy.
Global trail revision numbers alone do not invalidate an unchanged child.
Prepared root cuts belong to the initial immutable layout even when its row
revision is nonzero.
Explicit query work and pivot allowances cover the collection, including failed
children, for retained node queries and fresh root tableau queries. Reused results
add no numerical work or factorization charges.
Each consultation binds its current cancellation token, including unchanged
children; identical exact authority retains their factors and completed evidence.
An ordinary component root uses the same scoped owner for its initial bound and later
node queries. Selected global cuts enter through the retained cut adapter before the
root solve; the passed selection also governs an empty cut list.
When no numerical owner exists, preparation partitions the complete source and cut
layout together. A declined live component append falls back to the ordinary owner.
The original node-depth and cut policies remain in force. This root-bound operation
shares the bounded retained-root work,
factor storage and checking envelope; incompatible source or cut layouts use a fresh root
solver within the remaining allowance.
Scoped preparation reserves the graph scan, partition scratch and child restriction
storage before constructing children. A declined or interrupted split retains its
spent preparation work; child numerical preparation receives the remaining grant.
Explicit component ownership funds cold preparation from the remaining shared LP
and invocation allowances, separately from the adaptive numerical query quantum.
Without a timed shared LP allowance, preparation uses the remaining bounded root
work grant. Ordinary component roots make one adaptive numerical query before
primal heuristics run; unfinished owners remain available to later node queries.
Ordinary node queries keep their individual numerical limits. Sparse-trial root-bound
consultations retain a cumulative continuation grant.
Preparation is charged before the query receives its remaining allowance.
The first scoped query completes this charge before freezing a continuation grant;
an interrupted preparation supplies neither a numerical query nor a prior query's charge.
Finite basis entry limits are divided among the immutable column blocks; local
cut replacements retain those shares. A logical-only child reserves its identity
factor share first, within the same containing entry limit.

Child bounds remain exact rationals until their sum, isolated-column contributions
and the original objective constant, scale and sense are combined. Rounding applies
to that aggregate. Bound support lifts every contributing row and side to the
current parent state, including its current assertion witnesses. Floating child
optima alone supply no global optimum or conflict authority.

Completed exact integer components can retain node deductions separately from their
numerical results. A query uses the parent's common dual-rounding scale and reuses
compatible child deductions. The aggregate maps multipliers and reduced costs back
to the current parent authority; each child alternative is combined with the other
children's strongest bounds for fixing. An alternative visits only its changed
child's columns; its other reduced costs are already covered by the stronger
primary deduction. Retained deduction payloads occupy at most eight
MiB inside the shared 16 MiB checking envelope, and assembly and checking work are
charged even on a decline. Mixed columns, non-unit objective scaling and unsupported
integer certificates use the whole-model deduction route. The independent safe
objective check remains part of the node bound decision.

At owner construction, blocks are ordered by row-and-column dimensions. After a work or
pivot stop, completed blocks can contribute their checked dual bounds while
unfinished blocks contribute freshly checked zero-dual box bounds. Missing
minimizing sides decline the aggregate. Every contribution uses the current
child authority, and only their exact sum is projected to source units. This
bound supplies no complete point, cut tableau or attainment claim.
Node consumers can prune against the existing source-objective cutoff after
charging aggregation and certificate work; they do not publish branching coordinates
or train directional history from the partial aggregate.

The same explicit setting dispatches root tableau separation to the owning block.
Lifted cuts retain all row multipliers, bound substitutions, integrality premises
and source addresses; the ordinary source mapper and cut pool check and retain
them. Appended rows confined to existing blocks replace only those children and
retain the other blocks' results. Cross-block rows, new columns and other structural
edits retire the collection and prepare the ordinary owner. Mutable component
merging and automatic expansion stay outside
this setting. Persistent callers requiring one retained basis keep the monolithic
constructor.

### Cuts

The internal `composeBooleanBranches` traversal option augments partial-row
feasibility queries with common real consequences. It combines at most two rows
per alternative of one unresolved source Boolean, using positive weights 1 and 2
and exact positive normalization. Equal coefficient vectors yield the weaker
right side; strictness survives only when both alternatives establish it. Each
consequence imports an existing `BooleanSplit` proof, so mutually exclusive row
guards are discharged by exhaustive coverage rather than simultaneously activated.
The partial LP consumes only source-checked consequences. Its final refutation
rechecks dormant numeric premises with their original guards and integer pins.

This option is disabled by default. Preparation caps source rows at 32, Booleans
at four, support at eight, scalar width at 128 bits, derived rows at 128 per
alternative and retained consequences at 16. A containing work/allocation ledger
also bounds failed construction; local ceilings are 65,536 work and 1 MiB modeled
allocation. Capped scans report incomplete coverage. Proofs retain the exact source
revision, objective and invocation restrictions; retraction rebuilds conditional
queries, and no consequence becomes an original premise or witness authority.

Cuts aggregate exact source rows and apply their rounding rule's bound and
integrality checks. Local cuts keep their substitutions and premises and are
revalidated after pop or remap. Selection considers efficacy, parallelism,
orthogonality, age and row caps; separators check actual column integrality, so
continuous columns decline. Root harvesting caps retained cuts at the original row
count, and root and node separation keep their round, depth, pool and work
allowances. The [portfolio](12-portfolio.md#lp-arm-admission) decides when
an aggressive LP companion runs.

Cut family identity follows portable proofs and retained rows without contributing
to proof authority or deduplication. Finite root and node phases name the ordinary,
structural or retained-component admission recipe and distinguish
construction, mapping refusal, admission, efficacy selection, tightness observations,
capacity or inactivity retirement, and premise or scope retraction. A tight or
selected row establishes no deduction. Nonzero exact certificate support is reported
separately when a checked bound, conflict or reduced-cost deduction is consumed.
Source traversal likewise distinguishes installed rows, checked combinations and
actual bound or conflict closures.
Finite observations count contributing row uses; source-frontier observations count
participating families per closure. Several rows or families can support one
deduction, without establishing its marginal benefit over a no-cut control. A
stronger accepted safe bound cites its own dual support rather than a weaker
integer certificate's rows.

Per-family solve and certificate observations are inclusive containing costs:
several participating families share the same solve, factor construction and exact
attempt. They overlap each other and the ordinary LP totals and must not be added
together. Failed reconstruction, continuation import, refinement and basis work keep
their existing charges and decline reasons. Assembly and factor-fill observations
include replacement owners; compatible component reuse is distinct from another
solve. Telemetry retains aggregate counters by the fixed family and recipe set,
without retaining cut histories or granting additional numerical allowances.
Terminal finite results and standalone linear results take their final lifecycle
snapshot after releasing numerical owners. Rebindable handles remain live until
their caller retires them; portfolios read the final handle snapshot after close.

Explicit bound-ordered sparse frontiers consume single-row Chvatal–Gomory rounding
and bounded mixed-integer residual rounding.
Other guidance calls can opt in through the adapter. The adapter
normalizes an integer-only source activity by its exact rational coefficient gcd
and retains a cut only when rounding tightens its right side. Mixed rows normalize
their integer activity and cite unconditional source bounds for every continuous
term, preferring the side that retains a negative residual. Exact shifting and
complementing precede mixed rounding; missing sides and integral shifted right
sides decline. At most 64 mixed cuts are retained per template. Eligibility and
decline observations describe only rows visited within the preparation allowance.
The explicit point-selection policy also proposes the complementary available
continuous sides within the same row, work and scalar limits. Missing opposite
sides retain their original premise; identical side choices produce one candidate.
Both variants retain their exact bound derivations and require fresh checking
before selection. Ordinary source separation keeps its preferred side policy.
Preparation visits at most 65,536 modeled row/support operations with
4,096-bit input, denominator and intermediate scalar limits. Cut definitions and source-row ownership
stay in the retained template, ahead of changing invocation-bound rows. Every
query rechecks active cuts against the exact numeric source context under one
shared 100,000-step proof allowance and premise view; inactive
Boolean guards or integer pins disable their source rows and dependent cuts.
A cut with several source owners requires every owner to be active. Retraction
restores those activation masks. Unverified cuts remain inactive and require an
identity row layout. `source-cuts=false` disables this separation in numerical
frontier traversal. LP certificates cite the nested source rounding
derivation, and final source checking repeats Boolean substitution independently.
Preparation and proof traversal report separate work observations, including
interrupted queries. Separation and
proof policy belong to the adapter, outside `lp.engine`.

Explicit aggregate construction combines two to four source rows with positive
exact weights and normalizes the integer activity by its rational coefficient gcd.
Complete continuous cancellation permits integer rounding. Optional mixed rounding
instead cites an active source bound for each remaining continuous coordinate,
preferring the side that retains a negative residual.
The candidate retains every source owner and its nested combination and rounding
derivation. Construction bounds combined support at 128 coordinates, retained
scalars at 256 bits and proof references at 16 KiB. Each attempt has 65,536 work
units and 1 MiB of modeled allocation, with failed attempts charged to the same
containing observer and cancellation token. These candidates require source
verification before admission.
An immutable template can append up to 64 explicit proposals before the shared
checked-candidate pass. Existing candidate IDs are preserved; appending produces
a new template identity. Full owner and coordinate indices must belong to that
template, and copying and indexing costs debit the containing source allowance.
The proposal producer owns and funds derivation construction separately.
`source-aggregate-cuts=true` enables a template policy for numerical frontier
traversal when source separation is enabled. It projects a continuous coordinate by combining its
source row with the first singleton source bound of opposite coefficient sign.
It applies checked rounding to the residual, using the first source bound sides
for the remaining continuous coordinates. The cached family retains at most 64
proposals under 500,000 preparation work and 8 MiB modeled allocation, with
per-attempt limits shared with explicit aggregation. Stopped or limited
preparation reports its decline and leaves only completed proposals available.
Aggregation is disabled by default.

### Root preparation

Finite satisfaction and optimization share root hull pruning and cut harvesting;
objective shaving and incumbent-relative fixings are optimization work. Root
preparation runs once, under a common time and work allowance, before shared
initialization publishes its bounds; a cancelled phase contributes only completed
deductions. Interrupted root rounding retains its relaxation layout and basis for
later slices. A completed rounding LP can retain its coordinates under the same
bounded guidance allowance after charging the solve, avoiding another LP solve
when pinning resumes. Every retry consumes the remaining root and LP allowances.
Root rounding and pumping propose discrete assignments for mixed
models, but a relaxation point supplies a target, never witness authority.

The internal root-only preparation policy can give source projection and numerical
owner construction the remaining cumulative LP allowance while keeping each
floating query under its adaptive solve allowance. It requires a finite containing
allowance. Source-edit preparation, including a declined edit, charges that same
governor before a solve and is excluded from the solve's later work report.
Its interruption policy can grow the adaptive request when a query makes pivots
and stops on work before producing a point. Growth stays under the existing
adaptive ceiling and remaining cumulative allowance; missing reasons, zero-pivot
stops and numerical or infeasibility candidates supply no such feedback.
Returned root guides can supply a recorded lower bound only through the existing
certificate policy and the same objective projection used by the standalone root
operation. Recording this bound supplies no additional pruning authority.
Ordinary admission and node preparation use the shared adaptive allowance.

The internal checkpointed root policy keeps one numerical operation across
consultations of unchanged exact authority. Adaptive allowances limit each advance;
the operation retains its cumulative work and iteration limits, scaling, phase,
artificial bounds and basis. Each consultation reports only its new work. A pause
publishes no point, bound or conflict. Changed bounds, objectives, row authority or
trail scope discard the cursor before numerical adoption; cancellation and owner
cleanup retire its factors. Ordinary admission remains independent of this policy.

Sparse source preparation transposes emitted row support into exact CSC and retains
row/column adjacency. Its explicit work, modeled allocation and scalar-width limits
are independent of the exact-primal dimension guard. Admission reserves preparation
once; resumed consultations keep the same admission and charge only active work
and time. Closing that admission prevents further consultations.
A changed scoped query spends a fresh operation and can reuse the prepared matrix
without reserving its construction again. `SparseSourceLpPolicy` carries the
preparation, floating, consultation and certificate allowances together.

Retained MIP callers additionally fund consultations through `SourceLpFunding`.
Grant pauses preserve admitted sparse state; measured work and cumulative sparse
construction allocation survive owner replacement. Caller cancellation closes the
owner, while funding exhaustion is an incomplete resource result. Per-operation
shape, fill, scalar, iteration and certificate limits still apply to every query.

Identity row layouts are the default. An explicit unconditional-equality policy
combines adjacent closed, exactly opposing inequalities into one row with a fixed
logical column. The emitted multiplier's sign selects the original inequality when
lifting row weights. Conditional templates retain their identity layout even when
their guards currently hold: a later sibling can retract either side. Source
lifting and ordinary evidence checking still decide whether the bound is usable.

The scoped root-guidance operation advances the existing floating cursor under
finite cumulative and per-consultation work limits. Pauses publish no coordinates
or proofs. A completed point remains heuristic even when bound certification declines.
Dual-only certification checks exact Lagrangian evidence under its own sparse input,
arithmetic and allocation limits. A refused binary dual candidate enters the bounded
dual-vector rational reconstruction schedule under the same work, allocation and
attempt limits. The first checked bound ends that schedule without primal recovery
or exact feasibility continuation. A retained basis supplies a dual proposal by
reusing logical-column layout, peeling singleton equations and variables without
fill, and solving admitted residual components with the rational basis factors.
Factorization and complete verification share the certificate budget; an unresolved
component retains its proposed entries. Basis-derived bounds require both rational
and exact-basis policy acceptance; their observer work is charged once across the
two mechanisms. A refused common denominator can propose
independent continued-fraction entries under the same denominator and spending
limits; approximation tolerances select proposals only. Every proposal must pass
the complete exact reduced-cost and bound-side check. Diagnostics retain the
missing side with its exact reduced cost, the refused common denominator, peeling
counts and residual factor costs. Accepted bounds retain row multipliers for
source lifting and rechecking. Preparation, floating stops, certificate declines and completed checked
bounds have separate outcomes and costs; factor fill and density describe the
completed numerical solve. Sparse preparation alone does not authorize ordinary
admission or bound the fill of a numerical basis.

A completed infeasibility candidate can instead enter bounded Farkas reconstruction
under the same certificate policy. The exact ray checker produces row multipliers
and bound-side support without primal recovery. Source lifting restores extracted
coordinate sides and the original row sequence before checking a scoped refutation.
Only this checked derivation closes a numerical traversal leaf or a repair query;
unknown, paused and declined consultations leave it open. Retained completed results
charge certificate work once, and scope edits discard their authority before reuse.
An immutable traversal invocation retains its checked global bound when fresh
coverage supplies an equal or weaker value, avoiding repeated verification of the
growing sibling proof. A new repair scope owns a separate invocation.

`source-refutation-reuse=true` enables a traversal-owned index of checked source
refutations. Exact cited coordinate sides select candidates; full scope implication
and a fresh source import check authorize closing the matching leaf before its
numerical query. Reused refutations emit no fresh numerical feedback. Only
refutations enter the index. Incumbent-bound closures and
failed improvement queries supply no unconditional conflict. The original source,
objective, invocation window and complete Boolean substitution remain bound to the
opening, including when it borrows a numerical owner.

`source-refutation-propagation=true` additionally enables integral unit-prefix
deductions and the same refutation index. Only a single missing inclusive bound on
a source integer with an integral endpoint is eligible. Complete Boolean scope,
opening pins and all other restrictions must already hold. The augmented scope's
fresh import check proves the excluded sibling; named prefix facts justify its
complement through shared search. Missing names, unsupported endpoints and stopped
checks leave the alternatives available. Source-bound registration and clause
construction spend the containing numerical funding; the session registry admits
at most 4,096 atom pairs and explanations at most 4,096 references.

`source-refutation-resolution=true` enables the unit consumer and bounded pairwise
resolution over already named integral predicates. Reduced refutation scopes become
clauses; shared clause resolution removes a complementary pivot, and a source
integer-split derivation checks the two imported proofs. Complete Boolean
substitutions and opening restrictions are retained. A checked resolvent can exclude
a queued region that neither input refutation covers individually. Unit reasons
use the named established support rather than the entire decision prefix. Stronger
endpoints can justify a premise; weaker or unnamed predicates decline. The index
retains the resolvent and its proof under the same cumulative work/allocation and
storage limits; unavailable checks supply no exclusion.

The index retains at most 256 entries and 1 MiB of modeled proof/scope storage,
evicting the oldest entry when either capacity is reached. Support traversal admits
at most 4,096 nodes and depth 64; each retention or lookup shares at most 100,000
verification polls across its candidates. Source, restriction and proof scalars
retain 4,096-bit numerator and denominator limits. Source reconstruction, proof replay,
indexing, allocation and eviction charge cumulative source LP funding. Unknown or
cancelled checks close no additional alternative. Metadata visits and scratch
allocation are charged before their source maps are copied. Ordinary traversal
keeps reuse disabled. Cumulative reuse allocation has a separate admission limit:
the smaller of 64 MiB and one eighth of the source funding remaining at opening.
Reaching that limit clears the cache, stops further reuse work and preserves
ordinary numerical traversal under its remaining cumulative funding.

### Leaf completion

Finite optimization keeps a pinned real-leaf request across portfolio slices.
The discrete assignment, assembled row authority, source encoding and required
certification remain owned by that request. Numerical pauses preserve the live
solve; a completed floating solve can wait for certification in a later slice.
Source encoding and relaxation assembly are atomic preparation of the request.
Neither pause consumes the discrete model or records an indeterminate region.
Reseeding and shared objective-floor imports are deferred while completion is
pending, and explicit rebind or close discards its numerical owners before changing
the traversal. An exact repair query disables tolerance-optimum acceptance and
compares the certified completion's exact objective with its rational strict
cutoff. Missing exact coordinates or an unrepresentable result leave coverage
incomplete; neither a rounded tie nor a declined candidate closes the cutoff.

Mixed witness recovery can submit a connected real residual to `SourceLp` after
fixing integer guesses, within its `SourceLpBudget`. Only exact primal coordinates
are consumed, and a failed guess supplies no conflict or bound. Real array selection
emits index-range rows on every build, and a fixed index adds its equality with both
index premises. In every case the original source rows decide publication.

## Bound trail and warm state

[LpBoundTrail](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpBoundTrail.kt)
intersects assertions exactly and keeps a source premise for each accepted side.
Batches publish witnesses only for a committed prefix; crossed bounds cite both
sides, and pop restores bounds and deactivates scoped rows.

A nonbasic bound change updates the basic values by `-B^-1 A(j) delta(x(j))`; pop
reseats nonbasics and recomputes `x(B) = B^-1 (b - N x(N))`. Compatible edits and
pops retain factors; missing sides require status and dual-feasibility repair, and
objective edits invalidate dependent proofs.
[LpScopedSolver](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpScopedSolver.kt)
and [CpLpAdapter](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/CpLpAdapter.kt)
hold bound trails, active-row identity and verification caches.

Checked packages retain accepted bounds and witnesses under the same exact state
and production acceptance policy. Immutable exact-state identity admits reuse without
serializing the source matrix; retained vectors and support share a 4,096-unit,
64-KiB storage envelope. Mutable working models require a complete bounded value key.
A distinct exact state invalidates the package even when it shares source definitions.
A weaker checked bound cannot satisfy an
attainment request. Continuation retains cumulative import and feasibility work;
an unfinished import requires the same authority, ordered headings and statuses.
A ready feasibility tableau can rebind compatible bounds under its remaining
allowance, with publication checked against the current source. Refinement admits
one unseeded and one exact-point-seeded attempt per state, limits and claim, while
spending remains cumulative across those changes. Repeated unsuccessful requests
supply no infeasibility authority. Reconstruction still checks each floating
candidate; candidate rejection supplies no reusable proof package.

Numerical source traversal retains a `LinearAuthoritySession` per invocation.
Its fixed matrix has free structural columns; extracted source sides are asserted
in a replaceable scope. Child and sibling queries retract that scope and retain
compatible factors. Source sides bind as one checked batch in column and side
order, retaining witness IDs and assertion depth. Unprojectable batches use the
trail's scalar path; a declined batch retires the owner without publishing guidance.
A conflicting prefix retains its witnesses before the remaining sides are asserted.
Cancellation withholds the new scope identity.
Source row templates and numeric coordinate addresses are
prepared once per Boolean substitution. Conditional rows remain in the matrix;
their logical sides are suspended while their pins are unestablished and restored
on retraction. Objective edits invalidate dependent certificates; changed
structural authority replaces the owner. Each query lifts multipliers through its own
source-side mapping and checks the resulting derivation in its source scope.
Auxiliary multiplier searches own separate temporary solvers. Neighborhood
invocations can borrow a retained authority, leaving its cleanup to the caller.
Borrowers retain that authority's cumulative funding allowance.
Floating source coordinates are guidance and supply no witness or proof authority.

`solver.pipeline.SourceNumericalFeedback` delivers completed queries to decision
consumers. The journal keeps two separately bounded channels: an inspection archive
of the first queries, and a single latest-query slot that each completed query
replaces. Archive retention therefore never withholds a later query; a prefix-only
control reads the archive alone and reports later queries as retention-limited.
Both channels share one record construction. Each archived record exposes exact-valued
relaxation coordinates, generating cut
masks, active source rows and scoped checked bounds/refutations, with independent
point availability. A consumer names the projection it reads: the point, installed
cut activity or the refutation. A query delivered after the archive stops holds only
the requested projection and records it, so a withheld field is explicit rather than
unavailable, and the query state still reports the actual point outcome. Installed
cut activity keeps the installed, checked cuts with active owners; their violations
are evaluated on demand while that query is still delivered, and a replaced,
released, cancelled or oversized evaluation declines. A callback without a request
receives the full record, and an external observer keeps its full archive. Such coordinates can contain numerical residuals and require
full source checking before publication. Compatibility requires the same source
premises, semantics, objective and exact query restrictions. A cutoff attribution
requires its actual strict objective premise; an acquisition without that premise
cannot claim a cutoff-conditioned conflict. Cancellation, pauses, missing queries,
oversized queries, retention and funding exhaustion remain explicit unavailable
outcomes. Detaching a consumer releases its latest slot and leaves an external
observer's archive in place.
Acquisition returns the existing authority result alongside feedback, preserving
recovered incumbents for separate source checking and acquisition credit. A funded
cutoff helper constructs a query-local strict row in the captured context's
objective units and direction. Callers retain any frontend scale and coordinate
mapping; the helper does not infer that outer conversion.
Bounded refutation-support projection visits captured premise and assumption
references and retains the complete Boolean substitution and query cutoff. An
unsupported projection declines; it performs neither proof replay nor scope
reduction. Its coordinate set supplies ranking only.

An internal research consumer orders integer splits by near-tight,
installed checked cut supports, preferring smaller supports and greater fractional
distance from an integer, with source-order ties and fallback. It requests the point
and installed cut activity, and evaluates a cut only when its support holds a
fractional candidate that could change the choice. Both complementary
alternatives remain in coverage. Feedback never supplies pruning or parent proof
authority. Construction, replacement, retention and scoring debit the containing
source LP funding cumulatively; evicting a delivered query neither refunds that
ledger nor issues another LP solve. The archive retains at most 64 records, 250,000
entries and 8 MiB modeled storage, and a delivered query has the same entry and
byte allowance on its own. The archive is a prefix: once a record or allowance fills
it, every later query is constructed for delivery alone. Archived records are charged
for copies of the source rows, cut definitions and cited derivations they reference. Once the archive stops, a
delivered record keeps only references to those immutable structures, which the
authority prepared and still holds. A full delivered record is charged per referenced
row, coordinate and objective entry, for its scope, point, cut violations and checked
evidence. A requested record is charged for what it allocates: its point once when
captured, its installed cuts, and one reference each for the source context, scope,
query lists, evidence, sibling bound and incumbent. An on-demand cut evaluation spends the
delivered query's remaining allowance and the same cumulative ledger; it issues no LP
query and renews no allowance.

`LinearAuthority.advance` consumes the shared sparse preparation, floating and
bound-certificate policy through that same scoped owner. One admitted operation
survives unchanged consultations; changed source scope, sides or active rows revoke
its admission and numerical cursor before another query. A pause publishes no
coordinates or certificate. Compatible work and pivot allowance promotions retain
paid preparation, phase-local values and the numerical cursor. A funding stop at an
iteration boundary keeps that cursor and reports its limiting resource; unchanged
stops and completed declines are cached. A promoted allowance can resume a funding
stop. Fixed-lifetime CP consultations keep their initial allowance when later calls
report decreasing remaining work. Authority edits and cancellation retire the cursor.
Completed coordinates propose exhaustive integer splits
that add new scoped restrictions, including fractional source-bound endpoints.
Opposed coordinate sides refute a branch through checked source combinations
without another numerical query. Integral-looking
points require original-source witness checking, and checked bounds are lifted into
the current source scope. Rejected integral proposals first reconstruct real
coordinates, retaining restored source endpoints, then substitute their unchanged
exact integer assignment and complete
connected continuous residuals with the source LP owner. Strict rows remain strict;
inactive premises stay excluded. Residual solves share the invocation's source
operation allowance. Recovery bounds input entries, scalar width, work and modeled
allocation, debits their cumulative source funding, and retains completed components
across funding pauses for an unchanged query and guide. Every
recovered point requires a fresh complete original-source witness check. Frontier
leaves and joint repair consume those checked incumbents through the same authority.
After feasibility recovery passes the source check, the authority can refine the
continuous objective with the exact discrete coordinates substituted into the
active rows. Refinement uses the original working objective direction and scale,
shares cumulative source funding, and caps its numerical lifetime at the ordinary
sparse floating allowance. A point attaining an already checked bound needs no
refinement query. Exact objective comparison precedes row verification for an
improving proposal. Refinement retains its cursor across funding pauses and accepts
only an exact strict objective improvement that passes a fresh original-source
witness check. A decline, strict endpoint or rejected refinement keeps the accepted
feasible point. Attainment still requires checked objective agreement with a bound;
a fixed-pin objective solve supplies no global bound or refutation.
Residuals rejected by small exact input admission can prepare a sparse feasibility
query with the shared source budget. Its floating cursor survives funding pauses;
the lifetime uses the caller's remaining cumulative funding and consultation work
requests follow its current grant, without renewing paid preparation or numerical work.
Completed coordinates undergo bounded rational reconstruction and primal-only exact
basis verification. These checks publish no dual bound or conflict.
Primal-only verification removes basic logical unit columns before factoring the
remaining structural core, then recovers their values and checks all original
column and row sides under the same allowance. The most recent
unchanged residual retains its completed point or hard decline across proposals;
changed exact rows, endpoints, strictness or column count retire that result.
Small-input admission, sparse preparation, floating termination and primal
verification report separate decline reasons and costs.
Small unresolved queries can use bounded exact completion; recovery declines supply
neither a conflict nor exhaustion authority.
Source reconstruction restores exact fixed coordinates and matching projected
bound endpoints before checking a proposed witness.

Rows have stable identities. Deactivation removes slack bounds, strictness and
integrality, and is rejected while surviving assertions need the row; global
source-valid cuts can survive pop. Appends and compaction prepare a fresh
fixed-dimension owner with warm headings and publish it before retiring the donor;
live factors are never resized. A projection that cannot import exact authority
declines without a partial layout: node bounding withholds deductions and leaf
completion returns indeterminate.

## Floating simplex and basis factors

[SimplexWorkspace](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexWorkspace.kt)
owns fixed-dimension scratch for one engine, with distinct indexed carriers for
entering spikes, pivotal rows, RHS and dual solves so a basis backend can reuse the
form it produced. [SimplexBasisLifecycle](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexBasisLifecycle.kt)
owns headings, statuses and the lazy basis backend, and invalidates them on
retirement even when close fails. `SimplexPricingState` owns Devex weights and
`SimplexObservations` per-solve counters; `RevisedSimplex` coordinates allowance
charging, recovery and publication.

A retained numerical owner can expose a private solve cursor. It suspends between
iterations, retaining the current phase, factors, artificial seats, pricing state
and cumulative solve limits. A pause returns no numerical result or certificate.
An opt-in bounded progress trace samples scaled objective and basic-bound
infeasibility separately for boxed dual, ordinary dual, primal feasibility and
primal objective phases. It counts degenerate steps, flips and priced columns;
factor-policy metrics identify rebuild triggers. These observations carry no
coordinate, bound or infeasibility authority.
The owner rejects another solve or authority adoption while that cursor is live;
closing it unwinds the suspended operation and retires its factors. Synchronous
calls consume the same operation without suspension.

A prepared all-logical native basis can seat a dual-infeasible free singleton
in place of its fixed logical column. The columns are scalar multiples, so the
opening preserves nonsingularity and the exact region. Repricing seats the other
nonbasics on their available minimizing sides; unresolved dual infeasibility
continues through primal repair. The opening pivot and its work consume the same
invocation allowances as subsequent iterations.

Primal feasibility repair minimizes the convex sum of basic-bound violations
along an entering edge. It passes bound crossings while the directional slope
remains negative, then pivots at the minimizing crossing or flips the entering
column at its opposite side. Tied crossings prefer a larger pivot. The entering
spike updates basic values between fresh RHS solves, with a refresh after eight
updates or a factor rebuild. Feasibility and terminal pricing are rechecked on
fresh values; a positive residual infeasibility never supplies a primal point.
Feasibility gradients are recomputed after each step. The sparse breakpoint
workspace, ordering and value updates consume the same floating work and
iteration allowance as other pivots.

Selected structural-column pricing passes the current owned CSC matrix to Koblas's
validated column dot, reusing its support without copying columns. An unscaled
fallback replaces that matrix with the scaling view and retires the basis backend;
pricing retains no column views across replacement. Stored zeros and current
coefficient values follow the selected indexed kernel's arithmetic.

Dual leaving selection uses verified Devex, reselecting when a maintained weight
falls below `0.25 * norm(rho)^2`. Entering selection uses a scaled Harris window,
a ministep floor and a breakpoint walk. Zero-objective solves choose between
`MIN_BOUND_SUPPORT` (default) and `LARGEST_PIVOT`. Refactorization responds to
backend requests, sampled residuals, factor fill or per-solve work growth, and a
hard update cap. Unavoidable repeated solve work does not justify rebuilding
unchanged factors.

Power-of-two scaling guards lost nonzeros, overflow and underflow, and results map
back to unscaled authority. Safeguards include small-pivot checks, refactor and
recompute, logical repair, unscaled fallback and bounded Bland recovery on primal
stalls. A dual-infeasible cold slack basis can try bounded dual phase I on a working
copy; an artificial box never authorizes a source proof, ray or bound.

[simplex.basis](../klause/src/commonMain/kotlin/com/eignex/klause/simplex/basis)
owns sparse and small dense elimination, DFS reach, Forrest-Tomlin updates, logical
repair, indexed forward/transpose solves and rational factors. Each owner has fixed
dimension, both permutations and three-valued update results.

Basis scatter checks slice windows and disjoint input buffers before traversal,
then combines index access and checked multiply/add in one pass. Slice arrays
carry no cached validation: initial support is copied from validated CSC, while
Forrest-Tomlin replacements come from owner-local scratch. Private workspace
marks register each slot once until clear, including exact cancellations, so
dimension-sized touched storage needs no capacity-counting prepass. A failed
arithmetic operation leaves every modified slot registered for cleanup.

Empty indexed right-hand sides return zero after checking the owner and factors;
the basis meter records a successful solve with zero entry visits. They consume
no basis-transformation allowance; nonempty requests retain the engine's
backend-independent shape and update charge.
Dense partial-pivot LU is selected only for dimensions 2–6 with at least 50% density in the selected
basis; it produces the same CSC factors as sparse LU, and a numerical decline
retries sparse. Rational factors reuse orderings but never approximate numeric
factors, and only complete exact rank determination establishes singularity. Koblas
supplies the containers and kernels; see
[integration contracts](18-exact-arithmetic.md#numerical-dependencies).

## Selected factor relaxation providers

`RelaxationProjectionProvider` selects emitters from immutable factor data; a
descriptor declares whether it supplies a sound relaxation, and `NONE` emits no
rows. A custom emitter must preserve every source solution and is rebuilt against
live domains, which disables retained layouts. A sound relaxation supplies bounds
and refutations, not feasibility: real leaf completion keeps the original built-in
source rows, and omitting a row never removes its constraint from the witness check.
Circuit arcs, Boolean RLT rows and scheduling families use only factors that keep
their built-in descriptors.

## LP certification

Certification checks numerical proposals against the exact active model. Different
claims need different evidence packages; a floating optimum or failed check cannot
prune or prove a result. [LpSolve](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolve.kt)
owns these checks.

### Proof packages

| Package | Establishes |
|---|---|
| Certified objective bound | A bound in the current model's objective units |
| Feasible witness | All active rows and bounds, with source reconstruction and required integrality |
| Attained optimum | A feasible witness and equal exact objective bound |
| Infeasibility | An exact conflict with supported source premises |
| Unboundedness | An exact feasible point and an improving recession direction |
| Indeterminate | No complete required package within the available policy/resources |

A bound does not imply feasibility or attainment, and a point does not imply
unboundedness. Ceiling an objective bound requires a proved integral objective
lattice. Combine packages only when authority, scope and objective units match;
exact rejection of one candidate is not a refutation of the model.

Certificates expose exact multiplier snapshots, so
[original-source capture](13-evidence.md) can consume them without adding model
dependencies to the engine. `LpCertificationPolicy` is the immutable acceptance
seam: certifiers always compute and report their observations, and the policy
decides which results may be used. A source check grants no permission to accept a
floating optimum.

Model-facing solves state an explicit claim: bound, contradiction, feasible completion or
attained optimum. Bound requests use dual-only reconstruction without primal recovery;
feasible completion stops when a checked point is available, retaining the trivial
exact bound of a constant objective. Contradiction requests
may stop on a checked counter-witness and never optimize it. Attainment requires the
same exact witness–bound equality as the result contract. Tolerance acceptance retains
its separate source-defined verdict and supplies no exact attainment claim.

Retained certification owners reuse the existing single-package counter-result store.
A checked bound can answer a bound request, but cannot answer a completion or attainment
request by itself. Reuse requires the identical immutable exact state, complete capture
key and production acceptance policy. Bounds, scope, active rows, matrix, objective,
origins, strictness and integrality remain part of that authority. These independent
packages retain no floating vectors or ordered basis; exact factor reuse still requires
its existing matrix and ordered-basis compatibility checks. Edits and owner retirement
invalidate checked packages. Cache admission and arithmetic budgets retain their limits.

Claim phases report inclusive certification time, existing charged work and terminal
refusal, including unsupported authority, missing bound sides, scalar limits, singularity,
resource stops and policy refusal. These work totals overlap the certifier ledger and
must not be added to it. Separate consumption observations count accepted leaf packages
and actual bound or conflict pruning; a successful check alone is not consumption.
Source optimization records deterministic claim work and outcomes in its existing stage
diagnostics; its source budget reports inclusive active time.
Heuristic LP seeding consumes floating coordinates directly and requests no certificate.

### Safe objective bounds

The cheapest certificate is a bound from the floating duals alone. Slack-form rows
are equalities, so the Lagrangian

```text
L(y) = y^T b + sum(j) min over [l(j), u(j)] of d(j) z(j),   d = c - M^T y
```

bounds the minimized objective for every multiplier vector `y`; rounding the duals
only weakens it (Neumaier and Shcherbina, 2004). `safeObjectiveLowerBound`
evaluates it exactly and projects the result downward to a finite Double. A
negative reduced cost needs a finite upper side, and a missing lower side requires
a nonpositive reduced cost.

The integer variant rounds the duals to integer multipliers at a capped
power-of-two scale and accumulates in Int128; nonfinite duals or overflow decline.
An integral objective lattice lets the bound be ceiled. Node bounding takes the
tighter of the accepted safe and integer bounds. Variable bounds use the same
argument with a single-column objective.

### Certification mechanisms

When a safe bound is not enough, the dispatcher selects applicable mechanisms under
consumer budgets. They are not a mandatory order, and denominator, tolerance and
stall heuristics only schedule work; they never authorize proofs.

| Mechanism | Acceptance checks |
|---|---|
| Integer Lagrangian | Round dual multipliers at a power-of-two scale and aggregate in Int128. Can certify bounds, fixings or Farkas without a basis. |
| Rational reconstruction | Continued fractions and common denominators; check equations, bounds, signs and complementarity exactly. |
| Residual refinement | Solve scaled rational corrections through the same floating engine and basis; accumulate exactly and reconstruct. |
| Rational basis verification | Exact FTRAN gives basics; BTRAN gives duals or Farkas candidates. Singularity requests repair. |
| Exact continuation | Import the numerical basis pivot by pivot, repairing unseatable basics with logicals, then run Bland feasibility. |

Rational work escalates from `Frac128` to `BigFraction`; fixed-width overflow
restarts the operation from authority, and every attempt is charged. An internal
consumer context or continuation request can opt into arbitrary precision before
the first tableau. This selects arithmetic without changing cumulative limits,
import identity or current-source verification. A retained tableau keeps its
arithmetic across requests. Owner-local exact factors require the same matrix and
ordered basis. An off-status feasible point cannot furnish a claimed warm basis.

A fully imported exact feasibility tableau can retain its own basis across bound
assertions and retraction when the source matrix, right-hand side and objective
identity remain compatible. Nonbasic columns are reseated against current bounds
and strictness; basic values are recomputed from the retained exact tableau.
Rebinding publishes only complete seats and retains cumulative work, allocation
and pivot spending. Changed layouts and incomplete imports use fresh continuation.
Every returned point or conflict is verified against the current source authority
and premises; retained feasibility supplies no prior proof package.

The exact tableau stores `[A | I | b]`. Pivoted logical columns already contain
`B^-1 I` and supply the Farkas multipliers, sharing storage with the constraint
columns. Admission counts `m * (n + m + 1)` stored cells under the 65,536-cell
ceiling and 4,096 total columns. Callers may impose a row limit; the default
uses the cell envelope. Work, allocation, scalar size and pivot limits also
apply to the complete operation, including import and current-source verification.

### Farkas and recession checks

For `M = [A | I]`, a Farkas contradiction requires

```text
rho^T b > sup { rho^T M x : l <= x <= u }
```

or the reversed equivalent. Free columns require zero aggregate coefficient and
one-sided columns a finite supported endpoint; equality is contradictory when a
selected contribution is strict. Bounds and scoped rows contribute their premises.

Unboundedness requires a feasible point and direction `r` with `M r = 0` and
`c^T r < 0` for minimization, with recession signs following each column's bounded
sides. Mixed-integer publication also requires an integer-feasible point and an
integrality-preserving ray, and finite optimization rechecks the pair in source
coordinates. Retained counter-proofs (witnesses against infeasibility candidates,
bounds against ray claims) are keyed to source, state and policy and invalidated on
relevant edits.

### Refinement and strict inequalities

For exact candidates `x,y`, refinement constructs

```text
M dx = s(p) * (b - M x)
s(p) * (l - x) <= dx <= s(p) * (u - x)
c(work) = s(d) * (c - M^T y)
x += dx / s(p)
y += dy / s(d)
```

then reconstructs exact values from the accumulated corrections. Temporary models
have separate authority, source state restores on every exit, and child owners
close. Feasibility auxiliaries shift the box to contain zero and maximize
`0 <= tau <= 1` with column `-b * tau`: a witness at one proves feasibility, and an
exact bound below one refutes it.

Floating solves the closure of strict rows, so a boundary point proves neither
strict feasibility nor refutation. The strict-margin auxiliary adds
`a(i) x + t <= b(i)` to strict rows and maximizes `0 <= t <= 1`: an exact witness
at positive `t` proves strict feasibility, an exact `t <= 0` refutes it, and other
outcomes decline. Zero-objective continuation instead keeps an infinitesimal beside
each strict seat, then picks a positive rational epsilon below every slack crossing
and verifies the point against the exact source. A strict infimum need not be
attained.

### Mixed deduction operations

Models with continuous columns cannot use the integer certificate's lattice
reasoning directly. `LpDeductionCertificate` generalizes it with exact
source-objective bounds, reduced costs, multiplier snapshots and movement limits
for integral coordinates; integer models keep `IntegerCertificate`.

`nodeDualCertificate` checks a dual vector against an `LpExactState`. Every nonzero
continuous reduced cost needs its finite minimizing endpoint; when one is missing,
bounded repair or alternative vectors are tried, each checked completely, and real
coordinates are never movement targets. `mixedRowBounds` projects active mixed rows
onto integral coordinates, citing the supporting side of every other term and
cancelling single continuous columns through paired rows or singleton equalities.

Certificate identity binds deductions to their exact state, so pop, bound changes
and objective edits prevent reuse. Node bounding prunes with the strongest
completed certificate; when none completes, an accepted safe objective bound still
supports pruning. These operations share one allowance: 128-bit scalar admission,
250,000 work units and 16 MiB of metered allocation.

### Model-facing linear optimization

Open linear models need the same guarantees in original-source terms. The
`lp.optimization` adapter accepts exact affine source premises, objective identity
and invocation restrictions, reconstructs nonnegative source row combinations for
bounds, and checks source witnesses and recession directions. A strict infimum is
reported as a limit: a checked strict bound, a closure endpoint and a feasible base
approached along `endpoint + t * (base - endpoint)`.

Retained exact row multipliers can supply the source bound combination directly.
The source verifier checks that derivation before admission; missing or rejected
multipliers fall back to a separate combination solve under the same allowance.
Non-strict coordinate inequalities become exact column sides, with their original
cited row and scale retained. Residual objective and conflict coefficients lift
through those sides into the full source row sequence. Strict inequalities remain
rows in strict queries; contradictory sides retain the row representation.
Preparation and lifting share the operation's cancellation and active-time scope.
Admission applies the existing dimension, entry and scalar limits to the working
matrix, objective and selected sides; every published claim still checks against
the complete source context.

The internal active-row consultation uses a zero objective and a separately
checked partial-scope refutation. Source scanning admits at most 100,000 visits
within the remaining funded work, with 4,096-bit input and Boolean-substitution
rationals; incomplete capture declines.
The shared numerical journal retains the admitted numeric subset and its checked
refutation in the original partial scope, with no point or objective bound.
Separately funded active-row consultation can consume a retained delayed state;
it uses the same production checker and cannot change the captured traversal.
Boolean substitution precedes independent integer-pin admission. Sparse closure
conflicts use the existing exact certificate lift; small strict systems can use
the exact strict-feasibility operation. Preparation, modeled allocation and
verification debit the traversal's cumulative source allowance. Retraction revokes
pending numerical work, and feasible partial systems leave complete checking in
charge of witnesses and optimization.

`OpenTheoryPipeline.optimizeLinear` runs a complete affine context in the shared
search session. A branch optimum becomes global only after every required Boolean
and integer alternative has a checked bound or refutation; an improving source ray
needs no siblings. When numerical authority declines, the exact descent of the
[linear theories](15-linear-arithmetic.md) continues in the same state and allowance.
A checked incumbent can close a numerical node whose scoped bound excludes a
better objective. Coverage retains that bound without requiring a local optimum
or another split; a global optimum still requires the checked root witness and
the complete composed bound. Missing bounds and uncovered siblings remain unresolved.
Fractional branching retains every eligible exact integer coordinate and selects
source order. An internal opt-in history records nonnegative checked immediate-child
bound gains per unit movement,
separately for the lower and upper split. Two observations in each direction permit
a balanced gain estimate to choose a coordinate; absent, nonfinite or all-zero estimates
retain source order. These estimates guide exhaustive splits only and supply no
pruning or proof authority. Indicator splits and integral strict-limit proposals
do not train fractional history.

An internal aggregate branch policy considers paired active integer rows with
32 or fewer terms. It rounds coefficient ratios at four scales, divides out their
integer gcd, and uses exact shifted-coordinate bounds to admit only a primitive
direction whose feasible activity lies strictly between adjacent integers.
Missing shift bounds retain coordinate branching. Open coordinates use one-sided
bounds directly. The policy has 128-row, 32-attempt, 16,384-work and 1 MiB modeled
allocation limits per invocation, with 128-bit inputs and 512-bit intermediate
fractions. Failed attempts debit the containing LP allowance. The two enforced
aggregate restrictions stay in the coverage tree and original-source checker.
It is disabled by default and declines active-row and refutation-reuse modes;
finite enumeration and the exact theory's Hermite branch order retain their own
owners.

`NumericalLinearRequest.solve` serves MPS through the same numerical authority, and
these adapters never confer source semantics on a rounded or tolerance model.

Internal numerical selection observations retain completed query scopes, exact
relaxation coordinates before primal recovery, cited rows and the generating cut
mask. Candidate violations use those coordinates, including satisfied installed
cuts. An explicit empty mask prepares and checks candidates without inserting
them. Changing a mask revokes the query's admission and cursor through the ordinary
active-row compatibility checks. Source guards and integer pins are checked for
every query; candidate scoring supplies no proof authority.

The optional observer has separate record, entry, modeled byte and cancellation
allowances. It retains immutable source values and derivations, never a numerical
owner or live workspace. Incomplete capture and unavailable points remain unknown.
Observing performs no solve. Explicit replay creates a fresh owner with separate
cumulative work, allocation, operation and cancellation allowances; it rechecks
source derivations and cannot publish deductions into the original traversal.

`source-point-cuts=true` opts the retained frontier into point-based separation.
The first relaxation uses its retained checked mask; a completed point can add
one selection round at that node. The score combines normalized violation,
inverse square-root support and objective parallelism. The model-facing cut pool
and source selector share the greedy parallelism filter. Floating scores guide
selection; source inequalities and their derivations remain exact rationals.
Nonfinite projections supply no score. Each call visits at most 8,192 support
entries with 65,536 modeled scoring work and 1 MiB modeled allocation, within the
containing cumulative source allowance. A template retains at most 64 selected IDs.
Changing its source layout clears those IDs; every query rechecks guards, pins and
derivations. Unselected cut rows are omitted from masked queries, and changed row
layouts invalidate numerical cursors through the ordinary compatibility checks.
Completed base bounds survive a paused or declined separation round. Checked base
incumbents and terminals bypass extra separation. The ordinary default activates
all accepted source cuts.

### Tolerance recovery

MPS finite-leaf tolerance recovery can justify a tolerance gap with exact basis
duals. `exactBasisDuals` refines the duals on one floating factorization, solving
for a correction from the exact residual `c(B) - B^T y` scaled by a power of two,
which stays dyadic when every basic column and cost is dyadic. Continued fractions
then recover the duals and `B^T y = c(B)` is checked exactly; non-dyadic authority
declines. Exact duals do not turn a floating primal into an exact witness.

### Resources and cancellation

Dimensions, fill, bit growth, deterministic work and memory gate exact work.
Dimension guards run before any allocation, and nothing is cached as eligible.
Exact mechanisms have no default internal clock cap but keep work and memory
limits and caller cancellation; root LP keeps a deadline backstop. Fallback,
retries and nested operations share one invocation allowance, and setup, failed
attempts and cleanup are charged. Cheap exact arithmetic charges operand
word-length squared.

Reconstruction and refinement poll ordinary tokens every 64 calls or 4096 work,
while resource caps are checked on every charge. Cancelled evidence is discarded.
A completed continuous check that stays undecided is skipped without learning a nogood, and
its incomplete flag prevents exhaustion or optimality publication. Numerical
failure, singularity, work stops, precision declines and cancellation keep distinct
diagnostics.

---

Previous: [16. Nonlinear arithmetic](16-nonlinear-arithmetic.md) · [Contents](README.md) · Next: [18. Exact arithmetic](18-exact-arithmetic.md)
