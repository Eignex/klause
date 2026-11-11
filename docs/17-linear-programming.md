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
Within a feasibility-pump invocation, admitted preparation can retain its completed
root assembly for later distance objectives. Reuse requires a compact discrete
layout without auxiliary columns or row premises, cheaper objective mapping than
completed construction, and a combined 262,144-unit storage ceiling. The invocation
keeps its source and propagated root fixed. Shared finite domain tables require
contiguous input domains and retain a bounded endpoint snapshot; changed bounds
or holes retire the assembly before another objective consumes it. Stable source
declarations need no repeated domain scan. Each objective gets private costs and
a fresh numerical solver; preparation, declined mapping and interrupted work spend
the same LP allowance. Pinning and source acceptance still check each proposal.
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

Earned selective reuse is the default source-preparation policy. Admitted
preparation validates row identity and exact bound sides before retaining selective
metadata and enabling pump assembly reuse. Node engines own their numerical
component partitions. Ordinary node, primal and shaving consumers
carry disabled, object, complete-index and admitted controls through
objective-specific engines and standalone sparse admission. `LpPlan.sourcePreparation`
selects this policy, and an explicit `LpConfig.sourcePreparation` overrides it
without enabling numerical techniques. The finite pipeline carries the same choice
through pre-bake LP checks and harvest before constructing the engine. Admitted construction
first completes its LP without speculative metadata and marks the existing
namespace token. A later completed consumer can allocate a 16-unit source-scoped
record and accumulate construction work. The marker carries no work credit or
numerical state. A preparation attempt spends
no more inspection work than those completed builds supplied. Deferred exact columns
debit the consuming LP when materialized; their potential storage remains within the
262,144-unit ceiling. A failed attempt requires further completed construction
before retrying; its inspection and replacement record still spend the current
allowance. Admission work supplies no additional solver grant. Numerical recipe and
repair eligibility ignore the preparation choice in an otherwise unchanged default
plan. It neither creates an LP owner nor disables an LP-free proof path.
Component-owned and admitted sparse-trial engines keep their explicit preparation
route. LP harvest also eagerly prepares definitions for its later engine bake.
Admitted ordinary consumers compile unary integer bound conditions into source rows.
The stated source root bounds and the constructed column bounds must both imply a
condition before the row is omitted. An open required side or a widened constructed
bound keeps its row. Row and bound changes use the current source binding. Each
check spends the current allowance. Regional, gated
and component-owned root layouts retain their row definitions. A later consumer
that can omit bound rows compiles missing conditions within its allowance, retaining
unrelated row and endpoint definitions. Interrupted compilation preserves the last
complete snapshot; repeated consumers reuse the completed conditions. Numerical owners and
source row maps are assembled independently for the emitted rows.
A selected view whose handoff checked
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
snapshots within the same containing allowance and storage cap. Admission records
share those source/finite publication slots and keep no predecessor. Their capture
and replacement debit the containing allowance before publication; cancellation
withholds the record and the next consumer. Construction funding has its own phase
observation and supplies neither a checked result nor source-acceptance authority.

Template construction polls and charges the containing token, including failed
attempts, and caps visits and retained storage at 262,144 modeled units each.
An incomplete attempt publishes no snapshot. Compatible views share one atomic
publication slot per source/finite scope, including temporary presolve bakes.
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

The trivial objective bound caches Boolean contributions only with no retained
decisions. Its baseline contains permanent root facts; later pins and backtracks
update a reversible penalty. Before installation is safe, each read scans the
current assignment. A propagator's effective reason level does not establish that
the search is at its root.

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

The model-facing bounding callback supplies the final returned node relaxation's
primal and dual point to finite search hints after cut re-solves. Only an optimal
result supplies a matching objective sample. An unfinished cut solve retains the
preceding immutable point; cancellation withholds publication. Checked cut
infeasibility supplies no numeric gain. Directional history accepts only compatible
retained model and row scopes; it adds no certification request.
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
exact scalar width and row-intersection fill risk. The screen reads the same source-root
boxes as the retained layout, without constructing a propagation fixpoint. Custom emitters decline before
trial assembly because they have no source-size reservation. Sparse storage is reserved
before assembly, along with its construction work. Assembly cancellation and numerical
admission refusal retain that work charge; cooperative preparation polls cancellation
before publishing another chunk. The sparse storage allowance is independent of the
ordinary dense cost proxy. The root trial uses the ordinary
bound, fixing and conflict consumers with their existing exact checks; cuts and
expanded hulls stay outside its allowance. Admitted sparse roots enable the ordinary
root primal consumers within the remaining root and LP allowances.
An admitted persistent discrete layout with CP-variable columns transfers its completed
source assembly into the same private engine. Its source reservation remains charged;
exact-state installation and numerical construction spend the remaining allowance.
Discarded adoption releases the layout without refunding its source charge.
The initial root bound and subsequent root propagation share the existing scoped numerical owner;
handoff requires matching source identities and root scopes, and live bound adoption
rechecks authority before reusing its basis. Consultations without selected root cuts install
source-root domain views without constructing a propagation session. They read no live search restriction;
the compact immutable layout remains available for later node adoption, while other layouts
retain their source emissions. Cut-bearing consultations keep propagation-derived root bounds,
preserving completed cut-owner results across later node adoption.
Completed cold layout construction and template imports debit the containing LP allowance.
Fixed-layout installation uses the supplied root preparation token; the installed
owner keeps its ordinary invocation cancellation for subsequent operations.
An interrupted cold base charges its materialized columns, rows and coefficient entries before
discarding them; that charge adds no retained authority.
Retained source fragments charge each fresh emission before preparing the next region,
including partial materialization on interruption. Exact-import refusal and abandoned
publication keep those charges. Unchanged fragments add no assembly fee; restored cached
fragments retain their source-mapping fee without another emission charge.
The containing worker token and remaining root work cap bound their preparation. The
root-local clock gates numerical queries and checking. A completed source layout can
remain private for later node queries after that consultation expires; a containing-phase
interruption withholds an incomplete installation. A declined retained source layout
retires that attempt and uses the existing standalone numerical route within the
same remaining work and storage limits.

Private construction can pause under [root preparation](#root-preparation), without
publishing partial numerical authority.

An admitted sparse root advances the scoped owner's floating cursor in adaptive
work quanta. Pauses publish no point or bound and charge only the new work. A
pivoting pause can grow the next quantum under the existing adaptive ceiling;
the operation keeps its original cumulative work and pivot limits. Consultations
continue only within the remaining root, invocation and cancellation allowances.
Each advance binds the current consultation token while retaining the owner lifetime
and original cumulative limits. A paused original-objective query keeps root rounding
pending; the worker yields before discrete traversal can invalidate its root state.
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
later pinning still polls its fresh token. A completed auxiliary point keeps its
original problem identity and can transfer to ordinary integer value ordering
under a retained-root plan with the same objective. This handoff requires the
owned complete point, matching source coordinates and the existing guidance
storage and transport allowance. A different source or objective, an unfinished
or masked auxiliary proposal, source real variables or disabled LP branching
withholds it.
Retention and actual auxiliary value use have separate observations. The transfer
carries no basis, certificate or numerical-owner authority; ordinary search keeps
its alternatives and complete source checks. The first pinning attempt and its resumes
use the same private coordinate array, so a completed pin prefix belongs to that
guide. Rounding uses the stored coordinates without another numerical solve; its
propagation and source-publication checks still run. An incomplete or differently
optimized point supplies no retained guidance.
Rounding pins use probe fixpoints without materializing implication lists;
an interrupted pin restores the last complete propagation state and withholds its proposal.
Pure discrete rounding uses nearest targets. Retained-root plans additionally try
upward and downward targets within the remaining allowance. The directions reuse
one private propagation session, rewinding completed declined attempts to its root.
Completed propagation work is charged before another direction is admitted. An interrupted attempt retains
its direction and completed pin prefix with the exact stored guide and objective.
Later slices replace the probe token and charge only new propagation work.
Interrupted initial propagation, a changed guide, a finished sequence of attempts,
and engine close discard the session. A completed declined sequence can retain its
completion marker until a fresh consultation transfers its coordinates to ordinary
value ordering, without repeating propagation. Cancellation during source admission
keeps the completed pins and their direction for another admission attempt.
Retention renews no root, worker or invocation allowance.
A pausable discrete worker with a decision allowance can transfer eligible retained
coordinates directly to its ordinary complete traversal before private pinning.
The transfer releases the private rounding state and supplies value preferences;
the existing traversal owns backtracking and complete source checks. Masked child
coordinates can transfer without completing the numerical parent or the missing
coordinates. LP branching opt-outs, LP tree mode and workers with no decision
allowance retain the primal completion path. The handoff keeps the same storage,
work, guide identity and root-state guards as a completed rounding attempt.
Before its first incumbent, an integer-only traversal can visit declared factor
components of its undetermined columns contiguously while using these coordinates. Preparation admits at least
two coupled components, charges its graph work to the remaining LP allowance and
keeps primitive membership arrays inside the same guide storage cap. Prior paid LP
work also reduces the graph's remaining preparation ceiling. Within each component,
known fractional coordinates precede integral and unknown coordinates, with source
order breaking ties. The stable primitive sort reuses graph scratch and charges
its passes and comparisons to the same preparation allowance. Membership walks
charge ordinary CP work and revisit retracted variables. At each source node,
preparation can use its existing funded allowance before another LP query. A ready
component guide defers additional node queries until the first incumbent, while
inherited checked bounds, combinatorial node bounds and complete CP checks remain
active. Only the simplex arm defers; other bounds keep their normal cascade. Those nodes supply
no new directional observations; ordinary node LP policy resumes after an incumbent
or guide expiry. Declined preparation keeps ordinary branching; guide replacement, restart, reseed and close discard the
ordering. Completed integer component assignments can retain private Long value
preferences across ordinary restarts and numerical-guide replacement. Capture first
checks that every member is fixed; it charges array initialization and member copies
to the remaining preparation and LP allowances inside the same one MiB guidance cap.
Interrupted copies retain only visited value preferences and no component result.
Completed component values precede retained root LP targets; uncached coordinates
keep eligible root LP preferences. The ordinary frame stack keeps every alternative,
and full source checks still own
assignment acceptance. These values supply no bound, proof or LP-point authority.
Capture observations retain initialization, completed copy and declined or interrupted work.
Ordinary value use and the first value use after a restart have separate observations.
Explicit target reset, reseed and close release them. A restored source root can refresh expired preferences from its current
private numerical owner, after rebuilding the current domain and cut view. The
read requires matching model and exact state at depth zero, preserves child
invalidation, charges transport and copies only currently available coordinates.
Completed and interrupted component results share the authority check and private
snapshot read without a numerical query. A declined lookup can replay the same
engine's owned coordinate buffers as soft preferences under the
[root preparation contract](#root-preparation). Restart expires existing readers;
explicit reset releases pending buffers. An attempt stopped before spending work
can retry in a fresh consultation;
a paid decline retains its charge. These choices supply no directional-history, bound or proof authority.
Continuation observations distinguish a pending interruption from a cancelled run,
spent root or LP work, and a call without an eligible interruption. They record the
existing decision without polling another token or changing its checks.

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
The fixed layout starts from source-root boxes and adopts the query's propagated
bounds, without a second root fixpoint for that layout.
Noncompact exact layouts use retained source emissions instead of the compact base adapter.
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
compatible child deductions. Parent scale inspection charges the shared checking
meter without materializing parent multipliers. The aggregate maps multipliers and
reduced costs back to the current parent authority; each child alternative is combined with the other
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
Root consumers lift available child dual candidates with zero multipliers on blocks
without a result, reserving their copied vector inside the existing checking allocation. The whole-model
bound-only checker verifies the candidate under the remaining root work and cancellation
allowance before source projection. A refused check publishes no bound or point; the numerical
query still yields after its adaptive quantum.
Closed integral authority first checks rounded integer multipliers in fixed-width
arithmetic with bounded work, scratch and cancellation polling. Rational reconstruction
receives only the remaining checking work and allocation after a declined integer attempt.
Node consumers can prune against the existing source-objective cutoff after
charging aggregation and certificate work; they do not publish branching coordinates
or train directional history from the partial aggregate.
Root rounding can copy finite returned child coordinates into a separate masked
guide before bound checking. Unknown columns keep ordinary domain choices.
An unknown binary integer column tries its other value after a conflicting zero
pin, through the same cancellation-aware probes and propagation-work charge.
A later rounding consultation can snapshot existing results from its current scoped
owner before another numerical query. The read uses the fresh consultation token
and requires matching retained guide, objective, root model and current exact-state
identities. Invalidating the active search view preserves this read when the scoped
owner still holds that root state. Numerical queries retain their active-view requirement.
While a component cursor is suspended, a fresh read can copy only children that
have returned against their current exact state. The advancing cursor and its
unfinished child supply no coordinates. The copy neither advances numerical work
nor changes the cursor, basis or authority; its buffers remain private and spend
the same guidance work and storage limits.
After parent cancellation, a fresh read keeps only earlier returned children with
optimal, WORK or PIVOTS stops. Cancelled or failed child coordinates stay unknown.
This supplies no additional bound, certificate or complete-point authority.
A finished rounding attempt can transfer its private root coordinates and availability
mask to ordinary finite-search value ordering. Components with known finite targets precede
components without targets; ties retain source order. Members retain fractional-first
ordering within their component. Availability and boundary scans spend the existing
preparation work allowance and reuse its admitted scratch storage. The retained root owner and objective
must still match at transfer. No coordinate buffer is copied; the target header, protected column maps and
all simultaneously live guidance buffers share the existing byte limit. Each lookup
charges the ordinary cumulative LP work allowance. Fresh node hints take precedence;
unknown coordinates preserve the configured choices. These root targets train no
reduced-cost or directional history and supply no query or proof authority. A new
root guide, restart, assumption reseeding and engine close discard them.
The private coordinate and availability buffers spend the existing guidance byte
limit and root work before allocation. This guide carries no complete LP point or
proof authority; every proposed source assignment remains fully pinned and checked.

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
revalidated after pop or remap. A retained load reuses the checked mapping of the same
source definition within that load; parent proof rows use the numerical owner's
existing row-identity index. Mapping limits inspect one immutable coefficient
snapshot per expression and recheck a derived proof when it differs from the
validated input proof. Admitted preparation can retain that immutable input's arithmetic
validation in its owning cut pool after an earlier completed mapping. A later
successful remapping whose immutable input extent covers the capture retains a
16-unit record within a 262,144-unit ceiling; capture,
lookup and retirement debit the current LP allowance, including cancelled capture.
The record contains only its exact source identity and arithmetic limits. Every
mapping checks the current model namespace, assumptions, guards, column geometry
and any derived proof. Changed limits or a different immutable source reject the
record. Eviction and replacement release it without retaining a prior generation.
After input retention, a pool can capture one primitive ordered column layout and
completed source-to-column row projections. The layout records source kinds and
IDs for canonical unit-scale, zero-offset columns; every remap validates the whole
layout once against its current immutable source map before any projected row is
used. A changed layout discards projections and retains their arithmetic-input
validation. Affine layouts, auxiliary proof definitions and mappings that construct
a derived proof keep full numerical mapping. Capture, binding, replacement and
invalidation debit the same LP allowance, with cancellation withholding publication.
Layouts and row projections share the pool's storage ceiling. Each projected row
keeps private coefficient buffers and returns a fresh cut with copied arrays;
source guards and assumptions stay current, and numerical owners normalize rows
for their own origins and bases. No source map, prior layout chain, witness or
numerical result is retained by a projection.
Selection considers efficacy, parallelism,
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
Borrowed numerical traversals report changes in authority-owned cut counters from
their initial snapshot; maximum and peak fields retain their lifetime values.
Joint repair reports its shared authority's cut counters once, including root
preparation and owner retirement, and accumulates query-local consumption separately.

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
Exact zero endpoint contributions and discarded residuals omit products and
right-side updates from the arithmetic allowance, retaining endpoint normalization,
bound validation and cancellation visits.
The explicit point-selection policy also proposes the complementary available
continuous sides within the same row, work and scalar limits. Missing opposite
sides retain their original premise; identical side choices produce one candidate.
Both variants retain their exact bound derivations and require fresh checking
before selection. Ordinary source separation keeps its preferred side policy.
At the first available point, point selection can replace its unselected candidate
definitions with independently chosen nearer continuous endpoints. Exact premises
still authorize every row. The replacement shares the original generation's work
limit and retains the source-row template; unavailable preparation leaves its prior
candidates intact. Each immutable template attempts this preparation once.
Point scoring admits at most one additional checked cut at each usable point.
An addition waits for its completed selected query before enabling another scan
at the next point. Only a strictly stronger checked bound, a checked incumbent or
terminal evidence enables that immediate continuation. An unchanged or unavailable
bound, an unfinished round and a scan without an addition keep a seven-query
discovery cooldown. Feedback applies only to the same immutable candidate
generation. An adopted current recipe or fully installed checked family
ages on each usable point without that discovery cooldown. Four completed scans
without an addition retire that
generation's unselected candidates from further scoring. Installed rows keep aging
after discovery retires and expire when they have been slack in four usable scoring
scans without an intervening tight observation. Tightness uses the model-facing pool's tolerance.
Full pools remain eligible for aging; incomplete scans do not advance it. A
replacement generation receives its own lifetime. Remaining installed cuts keep
their ordinary source checks and scope retraction. Retirement only weakens the
relaxation, retaining cut definitions and checked coverage. An empty selected mask
stays distinct from the ordinary all-cut recipe and cannot reinstall expired rows.
After discovery retires, installed-row scans omit objective projection and candidate
comparison vectors. Their point projection, activity checks and age state still
debit the scoring and containing source allowances.
Retired unselected candidates
are omitted from proof replay as well as scoring. Masked-query reuse distinguishes
whether unselected candidates are checked; installed cuts retain fresh source
verification under the current scope.
Managed current masks and fully checked selected families retain unselected and
retired row definitions as inactive numerical rows. A completed scoring scan with
an installed or newly selected cut can retain the candidate layout within the
same pool and preparation limits. Layout retention does not change discovery
cooldown or authorize
reusing point-directed definitions as the ordinary current recipe.
Their free logical columns impose no restriction and carry zero certificate
multiplier. This preserves a compatible numerical owner across additions and retirement.
Suspension maps source row IDs through equality packing; both sides of a packed
equality must have matching activity. An incompatible mask declines retained
guidance, keeping failed mapping and ordinary fallback costs in the containing
allowance.
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
Numeric views without Boolean coordinates retain immutable premise rows; their
scopes and integer pins remain checked on every proof. These views carry no
source-specific extension checker. Boolean-bearing sources reconstruct their
substituted rows independently.
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
Pair inspection skips source rows wider than the support cap while retaining
bounded inferences from other rows. It withholds complete coverage and source-point
satisfaction when a skipped row has not been checked.
An immutable template can append up to 64 explicit proposals before the shared
checked-candidate pass. Existing candidate IDs are preserved; appending produces
a new template identity. Full owner and coordinate indices must belong to that
template, and copying and indexing costs debit the containing source allowance.
The proposal producer owns and funds derivation construction separately.
Point-directed admission reserves appended proposals within a 64-candidate family
and retains the existing candidate prefix in the remaining slots. Source-row owners
and derivations retain their original indices; the new family is checked before
selection. Generation and copying costs include discarded candidates. Ordinary
template appends retain their full existing family.
`source-aggregate-cuts=true` enables a template policy for numerical frontier
traversal when source separation is enabled. A sparse index pairs source rows with
opposite signs on a shared continuous coordinate. Each pair uses its first indexed
pivot and positive exact weights to cancel that coordinate; singleton source bounds
participate in the same recipe. At least one row has integer activity. Checked
rounding uses the first source bound sides for remaining continuous coordinates.
Preparation traverses consumer-owned immutable premises in source order without
copying their map. Iterator and callback setup, row visits and retained indexing
spend the generation's allowance. Each source row's coefficient snapshot is shared across its generation's pair
attempts; each weighted combination owns a separate accumulator.
Incidence indexing funds each lookup key and reference/list growth, reserving bucket
storage when the bucket is created. Ordinary aggregate-only buckets retain their source-ordered lists.
An interrupted index publishes no proposals.
Rows wider than the support cap are skipped with incomplete coverage. The cached family retains at most 64
proposals under 500,000 preparation work and 8 MiB modeled allocation, with
per-attempt limits shared with explicit aggregation. Stopped or limited
preparation reports its decline and leaves only completed proposals available.
Point selection queries the current recipe before preparing optional aggregate
pairs. A checked current incumbent or terminal bypasses discovery.
Point-directed mixed-row pairs skip ranking when only one pair is possible.
Larger row sets admit ranking only when its bounded projection and sorting estimate
uses at most half the remaining generation work and allocation; declined ranking
retains source order. Admitted discovery visits rows and incidence partners by increasing normalized absolute slack at the
relaxation point, with original source ordinals breaking ties. Unavailable or
nonfinite projections follow scored rows in source order. Projection, sorting and
temporary index storage debit the existing generation allowance. Source owners,
exact derivations and ordinary aggregate-only source order stay fixed. With numeric
invocation bounds, a funded linear partition places finite seed rows containing a
scoped integer coordinate before other seed rows. Each group and the partner buckets
retain their slack and source ordering. Scope incidence supplies
only an ordering preference. Endpoint authority still comes from checked derivations.
With `source-cut-fractional-order=true`, point aggregation ranks complete finite
rows by maximum integer fractionality per support entry, then normalized slack and
source order. A funded traversal keeps at most eight generation-local seed cursors
and visits one pair per cursor before revisiting it for continuation. Opposite-sign
partner buckets can use a funded stable support-size order; equal supports retain
their point and source order. A nonseparating pair retains its immutable aggregate
and proof snapshots until that seed's next turn. The initial cursor envelope and
each optional bucket copy/sort must fit within one quarter of their remaining
work and allocation before setup. Declined cursor setup retains ordinary streaming;
a declined bucket order retains that bucket's point and source order. Every visit,
lookup, sort and snapshot debits the same generation allowance. The cursors and
bucket copies expire with the call. With no fractional support it retains the
ordinary scoped preference. Its projections,
score storage and sorting debit the existing generation allowance; declined ranking
keeps source order.
Fractional discovery permits at most two attempts for the current immutable generation
context. A later attempt requires a newer completed paid query and a fully checked,
active, installed pool; a cached point cannot unlock it. Failed preparation consumes
an attempt. Both passes preserve existing definitions and their installed mask and
retain the same per-pass generation and containing source allowances. Paused augmented
queries resume their pending pass without reserving another attempt.
Completed nonterminal fractional augmentation retains a separate immutable baseline
branch guide for the same verification scope. Ordinary traversal uses that guide
while consuming the strongest checked bound; the augmented relaxation point and
fractional coordinates retain their own meaning. Snapshot copies debit the existing
source allowance. Pauses, cancellation and terminal results withhold the guide.
With `source-cut-preparation-reuse=true`, point calls can retain one immutable
source-incidence index within the numerical owner. Reuse requires the same
generation context object and support allowance; a new context or owner release
drops it. Partial preparation is never retained. The index stays within 65,536
modeled work and 1 MiB of modeled allocation, including charged reference copies
and a prepaid constant release. Each point gets fresh seed and incidence lists,
ordering and bound-side selection under the existing generation allowance.
Retaining an index supplies no proof authority; query certification still checks
the complete source premises and scope. Reuse is disabled by default.
Incidence lookup, partition storage and copy-back share the containing generation
allowance. The partition's estimate admits at most half the remaining work and
allocation after slack ordering; a declined partition retains that order. Incomplete
scoring withholds proposals and retains its charged work. When a funded
exact comparison finds the same ordered cut families, owners and inequalities as
the fully checked active current recipe, preparation keeps its query and proofs
without another proof pass or scoring scan. Comparison work and allocation share
the containing point allowance; an incomplete comparison supplies no reuse claim. A completed
nonterminal point can prepare aggregates alongside point-directed row alternatives
under 65,536 work and 1 MiB modeled allocation. Ordinary aggregate-only queries keep
their 500,000-work and 8 MiB limits. An installed aggregate recipe with at most 64
fully checked rows can enter aging without replacing its query.
Pure-integer point discovery uses the existing integer-row producer limits of
48 multi-coordinate source rows and 16 proposals. It tries half rounding on each
row and positive two-row combinations, retaining only finite separating proposals.
A direct-row proof cites that row and its required singleton endpoint premises;
the aggregate API retains its two-to-four-original-row requirement. The integer
recipe slice takes at most one quarter of the generation's remaining work and
allocation, capped at 16,384 work and 256 KiB, leaving capacity for continuous-row
aggregation. Its scans, scalar normalization, failed attempts, proof references and
retained rows debit the same containing generation and source funding. A shared
immutable-generation index supplies stated integer endpoints to both recipe kinds.
An established invocation bound can replace a missing or weaker stated endpoint.
It contributes a typed bound assumption rather than an original-source premise or
row owner. Every cut verification resolves that assumption in the current scope;
retracting the bound withholds the cut. Scoped endpoint comparison, storage and
publication spend the same generation allowance.
It traverses the same consumer-owned premises and retains endpoints only for coordinates
requested by completed aggregates. A fully funded scan records both present and
missing sides; interrupted scans publish no partial cache. Singleton coordinate
inspection reads immutable keys without copying a coefficient map. The first
funded scan captures coordinates in a source-ordered array, including singleton
rows outside the requested coordinates. Iterator storage, array initialization and
scalar lookups debit the requesting recipe's allowance. The coordinate index
publishes only with a completed endpoint batch. Later scans charge each indexed
probe and read coefficients only for requested integer coordinates; the index
retains no coefficient pairs.
Endpoint storage and cache extension debit the same requesting allowance.
Completed batches retain their funded coordinate sets and endpoint maps without
copying prior batches. Each lookup charges every batch probe, and batch publication
reserves its object and list growth after the source scan completes. Failed scans
or unfunded publication preserve only earlier complete batches.
Integral coefficients containing a unit entry establish unit scale through a funded
scan, avoiding denominator and gcd temporaries. Other scales retain generic exact
normalization. Unit-scale normalization reuses the private immutable coefficient
snapshot. A funded
coefficient scan recognizes a half recipe whose stated side choices round every
coefficient to zero before allocating its proof and scalar accumulators. Other
recipes retain their bound validation and exact proof construction. Both paths
spend the same requesting allowance.
Input truncation or a stopped slice withholds complete eligibility coverage.
A pure-integer nonterminal point enters this discovery under the aggregate policy.
When both source endpoints are stated, aggregate rounding selects the endpoint
nearest the point independently for each continuous coordinate. One-sided coordinates
retain their stated side; unavailable finite distances retain the ordinary choice.
Aggregate proposal retention requires the same finite normalized violation used
by point selection, so satisfied proposals leave room for later separating pairs.
Point separation reads immutable coefficients without copying their map; callback
and iterator setup and each visited scalar debit the generation's allowance.
When nearest-endpoint rounding does not separate, a completed continuous residual
can retry the same aggregate with the ordinary stated sides that retain negative
residuals. Identical side choices require no additional derivation. Each attempt
replays its exact source-bound premises and shares the generation's work,
allocation, cancellation and proof limits. A completed pure-integer aggregate can
also try half scaling. Each odd coefficient cites an established singleton integer lower
bound to round down, or an established upper bound to round up when no lower bound is
available. An endpoint uses its stated premise or a typed invocation-bound assumption;
even coefficients require no bound. A positive combination of that authority gives
integral activity, and integer rounding supplies the conclusion.
The proposal retains every supporting owner and uses the aggregation-MIR family.
Missing established sides decline the attempt; scoped domains do not fabricate source premises.
An unproductive half-scaled aggregate can score single substitutions to an established
upper side on odd integer coordinates. Exact RHS changes and supporting strictness
determine each rounded endpoint; finite normalized violation chooses at most one
additional proof. That proof rebuilds its positive combination with the selected
source side and is checked before admission. If that substitution supplies no
separating cut, a funded scan can select several established upper sides together when
the point is nearer those endpoints. Lower sides retain ties and unavailable
finite distances. At least two selected upper sides permit one additional exact
half-rounding proof; an interrupted scan publishes no choice. Each rounded odd
coefficient cites its selected singleton premise, and the complete proof requires
source verification before admission. The singleton index, side scoring,
exact arithmetic and nested proofs share the same generation allowance. A nonseparating seed or continuation tries these alternatives before
following one opposite-sign continuous incidence path through
at most four distinct original source rows. After retaining a separating proposal,
at most one later nonseparating seed can continue under the remaining generation
allowance. Cancellation or exhaustion preserves earlier completed proposals.
A continued proposal equal to a retained inequality is redundant when the earlier
proof uses a subset of its owners and both proofs use only source premises.
Duplicate comparison and proof traversal debit the same allowance;
scoped-assumption proofs remain distinct.
For the next residual coordinate,
continuation excludes rows whose union with the selected original source-coordinate
keys exceeds the assembly support limit, including keys cancelled in the current
residual. The funded union and each candidate scan share the generation allowance.
It ranks eligible unvisited rows by finite source slack divided by the absolute
pivot coefficient at the current point. Ties retain source order. If no score is
available, the first eligible row is the fallback. The full scoring scan and scalar
projections debit the same generation allowance; a stopped scan publishes no
choice. A continuation skips an exact non-strict inverse of a selected source row
when their required positive weights cancel that entire row. This avoids returning
through the opposite side of an equality. Unequal weights and nonzero RHS sums
remain eligible; the exact comparison shares the generation allowance.
Ranking only chooses premises: exact positive rescaling preserves
the earlier combination while cancelling the next coordinate. Singleton continuous bounds remain part of mixed
rounding. Residual capture, continuation scans, term weights, failed derivations
and projection share the generation's existing allowance. Each attempt retains
the configured row limit, and greedy paths withhold exhaustive coverage.
Projection and declined proposals spend the containing generation allowance.
Unavailable projections withhold complete eligibility coverage.
Only proposals selected against that point enter a replacement mask. An
unproductive attempt retains the current recipe and its checked bound; selected
aggregates follow the same bounded scoring, aging and retirement as other rows.
Masked queries retain their adopted current or completed point-prepared template.
Candidate row IDs belong to that same checked template.
Aggregation is disabled by default.

### Root preparation

Finite satisfaction and optimization share root hull pruning and cut harvesting;
objective shaving and incumbent-relative fixings are optimization work. Root
preparation runs once under its containing time and work allowance; a cancelled
phase contributes only completed deductions. Interrupted root rounding retains its
relaxation layout and basis for later slices. A completed rounding LP can retain its
coordinates under the same bounded guidance allowance after charging the solve, avoiding another LP solve
when pinning resumes. Every retry consumes the remaining root and LP allowances.
Root rounding and pumping propose discrete assignments for mixed
models, but a relaxation point supplies a target, never witness authority.
An original-objective rounding probe can reuse a prepared retained root owner while
its exact model and root scope remain current. Its query adopts the current token
and spends the smaller of the remaining primal, root and worker grants. Incomplete
numerical work retains the private owner for an eligible later rounding slice;
queries with changed authority or objective use ordinary construction. Complete points and
masked child-coordinate guides can steer rounding; every proposed assignment
still requires source verification.
An integer guide with known fractional coordinates can prepare a private component
probe before its handoff to ordinary traversal. The admission scan charges visited
columns and reads only coordinates marked known; unknown payloads supply no target.
The private session charges its root propagation before preparing the partition.
Guides without known fractional targets retain their direct handoff. A probe over
independent integer components reuses its charged structural order and tries nearest, upward and
downward preferences within each component, including after a global nearest
attempt declines. A failed direction retracts only that component's pins; completed earlier components stay
in the private propagation session. Interrupted attempts retain their completed
fixpoint and current component and direction. A fresh session restarts this progress.
After all three directions decline, a funded private repair can revisit earlier pins
within the current component. It explores nearest and adjacent target values, or the
domain endpoints for unknown targets, using an owned bounded frame stack. Completed
earlier components remain pinned. Interrupted probes roll back to their last complete
fixpoint; pending alternatives resume under the next invocation token. An eligible
partial-owner refresh preserves an active private repair with matching source,
objective, relaxation and target identities instead of copying a replacement guide.
Owner invalidation and changed identities keep their ordinary retirement behavior. Frames count
against the same guidance storage cap, and propagation and retraction spend the remaining
cumulative root and worker work. When both candidates conflict and their reported
levels map to the private frames, those hints can guide retreat past later choices.
Missing or foreign levels retain chronological retreat. The
reason scan and every skipped frame's retraction spend the same work allowance;
interrupted retreat retains its target and charges. One private support index per
frame shares the guidance byte cap. These level hints carry no refutation authority;
ordinary complete search keeps its alternatives.
Exhausting the candidate choices or allowance supplies
no infeasibility authority. Completed earlier integer values can remain soft preferences
for complete search. No partial assignment is a witness.
Component progress and its retained order share the existing guidance storage cap;
all graph, propagation and value-copy work spends the containing allowances.
The preparation cap counts cumulative LP transport and construction overhead;
numerical pivot work spends the containing LP and root allowances separately.
A paid numerical query does not consume the preparation cap. Neither budget
renews across component-guide replacement or a fresh consultation.

A complete traversal restart expires existing preference readers while retaining
one generation of the engine's owned coordinate buffers. Fresh root-owner lookup
precedes replay; a declined lookup can expose those buffers through a fresh value
hint handle without a numerical query. Both pending and replacement storage count
against the same guidance cap, including their overlap during replacement. Replay
requires the owning source root, an uncancelled token and remaining funding; it
transfers no model, basis or certificate authority. Explicit guide retirement and
engine closure discard the pending generation.

Retained discrete root layouts prepare in private chunks before the first numerical
consultation. Objective-column batches and factor batches charge materialized construction
before yielding to the worker's slice. A paused builder retains only its source-root
assembly buffers; no partial layout, point or certificate is available to consumers.
Resumption keeps the same builder and cumulative root and LP funding. Containing
cancellation or spent cumulative funding retires it. Completion prepares the source
layout, private exact state and numerical owner before the consultation-local numerical clock starts.
Chunked assembly uses its supplied containing token; the relaxer's invocation token does not
turn a between-chunk slice boundary into construction cancellation.
Numerical construction uses the same remaining cumulative root and LP allowance; completed
and declined preparation charges that allowance before a query. Only a complete private owner
survives a pause. Each query adopts its own cancellation token before using that owner's basis.
The consultation adopts that state only while its source-root model, exact state and root scope remain current.
Incumbent validation can retire pending construction, and engine closure releases its private buffers.
Atomic projection and row emission retain the worker's ordinary overspend accounting.

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
or proofs. An admitted sparse root query can separately copy nonzero row multipliers
from its suspended numerical owner and submit them as a dual proposal. The copy
performs no basis solve and can lag a pivot. Only the existing exact check against
the unchanged root model can establish a bound. Snapshot work and storage spend
the containing root/checking allowances; zero, nonfinite, cancelled or declined
proposals leave the private cursor suspended. The query retains its strongest checked
bound while numerical work remains, including stronger completed-query evidence. A
later consultation stop can return that completed certificate through the ordinary
source projection and lower-bound publisher while the floating cursor stays suspended. A completed point remains heuristic even when bound certification declines.
Dual-only certification checks exact Lagrangian evidence under its own sparse input,
arithmetic and allocation limits. A refused binary dual candidate enters the bounded
dual-vector rational reconstruction schedule under the same work, allocation and
attempt limits. A retained-basis proposal uses the first attempt before residual
scaling and denominator selection; those scans serve the generic vector fallback.
The first checked bound ends that schedule without primal recovery
or exact feasibility continuation. A retained basis supplies a dual proposal by
reusing logical-column layout, peeling singleton equations and variables without
fill, and solving admitted residual components with the rational basis factors.
Factorization and complete verification share the certificate budget; an unresolved
component retains its proposed entries. A dimension-limited component can instead
propose an admitted subset of dual coordinates for its open basic directions.
Repair targets the exact residuals whose signs require absent bound sides,
even when the full open set fits the dimension cap. Admissible one-sided
residuals need not be zero. With no such basic residual, an admitted full open
subsystem remains available for other column residues. A repair adds open basic
directions whose residuals require missing sides after an earlier subsystem solve,
retaining prior equations in the expanded proposal. Every expansion adds a direction
and shares the original dimension, work, allocation and scalar limits. Every untouched
direction remains subject to the complete dual check after the proposal changes its multipliers.
Row-scaled floating elimination selects those coordinates within 16,384 projected
cells and the containing certificate budget. Nonfinite or weak projected pivots
decline; the selected exact submatrix must still factor and solve under the
ordinary rational limits. Other
proposed multipliers stay fixed; finite column boxes account for their residual
reduced costs. This proposal retains the dimension, fill, scalar and cumulative
spending limits and must pass the complete exact dual check. Basis-derived bounds
require both rational and exact-basis policy acceptance; their observer work is charged once across the
two mechanisms. A refused common denominator can propose
independent continued-fraction entries under the same denominator and spending
limits; approximation tolerances select proposals only. Every proposal must pass
the complete exact reduced-cost and bound-side check. Diagnostics retain the
missing side with its exact reduced cost, the refused common denominator, peeling
counts, the largest proposed restricted dimension and residual factor costs,
including failed proposals. Accepted bounds retain row multipliers for
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

An optional per-context query observer runs after a solve session adopts its exact
model and before numerical work begins. It receives a session-local owner and query
sequence, the borrowed working model, retained status, pricing, claim, offset,
acceptance mode and work/refinement allowances. A basis capture owns its copied
headings and statuses. A consumer must encode a model capture before returning
and bound its own retention and work within
the containing allowance. Observation grants no witness, bound or proof authority;
ordinary contexts allocate no query records.

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
64-KiB modeled storage envelope. Mutable working models require a complete bounded value key.
A distinct exact state invalidates the package even when it shares source definitions.
A weaker checked bound cannot satisfy an
attainment request. Continuation retains cumulative import and feasibility work;
an unfinished import requires the same authority, ordered headings and statuses.
A fixed-width overflow promotes the last complete tableau to arbitrary precision,
retaining imported columns, basis order, statuses and strict-side infinitesimals.
Promotion and abandoned arithmetic spend the same cumulative work and allocation
allowance; interrupted conversion publishes no partial tableau. Compact cases keep
fixed-width arithmetic. Fresh source verification still governs acceptance.
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
structural authority replaces the owner. A completed owner in the same source scope can append
up to 64 checked cut rows when structural columns, objective, every existing emitted row
and logical column retain identical exact values and metadata. Equality-packing changes
fall back to replacement. Compatibility and layout setup use at most 65,536 modeled work
and one MiB, debiting the containing source allowance. Append construction and basis work,
including failed attempts, remain charged there. Source sides rebind and inactive masks
retract through the same scoped trail. A grant pause retains completed sparse admission
and append state for the same query, without another operation reservation. Guidance
waits for renewed funding; cancellation publishes no changed guidance.
Each query lifts multipliers through its own
source-side mapping and checks the resulting derivation in its source scope.
Auxiliary multiplier searches own separate temporary solvers. Neighborhood
invocations can borrow a retained authority, leaving its cleanup to the caller.
Borrowers retain that authority's cumulative funding allowance.
Floating source coordinates are guidance and supply no witness or proof authority.

`solver.pipeline.SourceNumericalFeedback` delivers completed queries to decision
consumers. Latest-query consumers own a single bounded slot that each completed
query replaces. Prefix-only consumers and attached inspection observers retain a
separately bounded archive of the first queries. A prefix-only control reads the
archive alone and reports later queries as retention-limited. An attached archive
and latest delivery share one record construction. Each archived record exposes exact-valued
relaxation coordinates, generating cut
masks, active source rows and scoped checked bounds/refutations, with independent
point availability. A consumer names the projection it reads: the point, installed
cut activity or the refutation. Delivery without an archival copy holds only the
requested projection and records it, so a withheld field is explicit rather than
unavailable, and the query state still reports the actual point outcome. Full records
retain the template's immutable generation scope separately from the current
verification scope, charging its retained conditions to the capture allowance.
A missing template leaves generation provenance unavailable. An attached
inspection may explicitly retain at most two immutable generation contexts,
charging references and their retained extent to the same archive allowance.
Their identity belongs to the generating template; a rescoped verification copy
does not establish preparation-cache compatibility. Releasing this optional
retention drops its context references without retaining a numerical solver owner.
Captured-pool replay preserves row definitions and their identifiers, appends supplied
consequences within the same row ceiling, and selects the combined pool by an explicit
mask. Import and copying debit its query allowance; selected derivations are checked
afresh. It owns a fresh authority and retains no numerical owner from the capture.
Installed cut activity keeps the installed, checked cuts with active owners; their violations
are evaluated on demand while that query is still delivered, and a replaced,
released, cancelled or oversized evaluation declines. A callback without a request
receives the full record, and an external observer keeps its full archive. Such coordinates can contain numerical residuals and require
full source checking before publication. Compatibility requires the same source
premises, semantics, objective and exact query restrictions. A cutoff attribution
requires its actual strict objective premise; an acquisition without that premise
cannot claim a cutoff-conditioned conflict. Cancellation, pauses, missing queries,
oversized queries, retention and funding exhaustion remain explicit unavailable
outcomes. Detaching a consumer releases its latest slot and leaves an external
observer's archive in place. The late-collection counter counts deliveries outside
an inspection prefix, including every result from a latest-query consumer without
an archive; it does not measure delayed execution.
Acquisition returns the existing authority result alongside feedback, preserving
recovered incumbents for separate source checking and acquisition credit. A funded
cutoff helper constructs a query-local strict row in the captured context's
objective units and direction. Callers retain any frontend scale and coordinate
mapping; the helper does not infer that outer conversion.
Bounded refutation-support projection visits captured premise and assumption
references and retains the complete Boolean substitution and query cutoff. An
unsupported projection declines; it performs neither proof replay nor scope
reduction. Its coordinate set supplies ranking only.

The opt-in `source-cut-branching=true` consumer orders numerical frontier integer
splits by near-tight,
installed checked cut supports, preferring smaller supports and greater fractional
distance from an integer, with source-order ties and fallback. It requests the point
and installed cut activity, and evaluates a cut only when its support holds a
fractional candidate that could change the choice. Both complementary
alternatives remain in coverage. Feedback never supplies pruning or parent proof
authority. Construction, replacement, retention and scoring debit the containing
source LP funding cumulatively; evicting a delivered query neither refunds that
ledger nor issues another LP solve. An explicit archive retains at most 64 records, 250,000
entries and 8 MiB modeled storage, and a delivered query has the same entry and
byte allowance on its own. The archive is a prefix: once a record or allowance fills
it, every later query is constructed for delivery alone. Archived records are charged
for copies of the source rows, cut definitions and cited derivations they reference.
Delivery-only records keep references to those immutable structures, which the
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
Advisory incumbent completion maps source-column objective coefficients into each
connected residual. Nonconstant components use the existing bounded sparse
objective solve directly; zero-cost components retain small feasibility completion.
Only the checked point leaves this recovery request, with no global bound or refutation.
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

A retained optimal basis can provide advisory objective costs per unit of a source
coordinate, using the model's minimization orientation, for at most two basic
structural columns. Each inverse-transpose row reads
current nonbasic orientations and reduced costs, then converts column and objective
scales. The caller funds the complete bounded analysis before allocation or basis
access. Changed models, unfinished solves, missing directions and cancellation
retain unavailability. This read preserves the retained optimum and carries no
bound, infeasibility or certificate authority.
Matrix-scan funding counts nonbasic-column entries, matching the traversed columns.
A paired read shares reduced costs from the current dual vector when its fully
funded plan costs less than independent scans. The temporary reduced-cost vector
belongs to that read, and the complete combined analysis is funded before allocation.

Explicit directional probes compare the actual floating LP objective increase at
the two adjacent integer bounds of at most two structural columns. Each child
owns an immutable bound-state fork relative to the parent. One private numerical
owner adopts successive sibling states under the parent's immutable numerical
transform, retaining its private factors. It shares immutable CSC and row views;
its first solve starts from the retained parent basis. A private query declines
unsafe scalar refreshes or numerical fallback instead of rebuilding the shared
matrix. The parent state, optimum, factors and
metrics remain untouched. Common objective constants are excluded before gain
subtraction; objective scaling maps every result to minimized source units.
Both directions of every candidate require a complete optimal floating result
and a finite nonnegative gain. Infeasibility, an unfinished query, cancellation
or failed preparation withholds the entire comparison. These estimates train no
history and supply no pruning or certificate authority. Scoped owners recheck state,
ownership and cancellation after observations before returning a comparison.
Completed-child observations report preparation, solve and completion deltas;
the final observation retains any unfinished work and decline. Every exit releases
the private owner. Refreshes prepay scalar-vector visits and copies, while the
initial private workspace pays for the backend's separate source snapshots.
The immutable matrix views retain no mutable parent factors or solve vectors.
Each sibling refresh forks the immutable parent scalar vectors, so tracked bound
changes fund only their visits and the vectors they can copy.

Child-value consultations instead accept the basis of a completed current
iterate, including a bound-only result. Every child still requires a complete
optimal floating solve. Their finite objective values share minimized source
units and omit shared objective offsets, including column origins and the external
constant; negative values are valid.
They do not subtract a nonoptimal parent's objective or supply a parent-optimum
claim. The caller funds linear basis validation and its scratch before allocation.
Busy owners, changed states and incomplete children withhold the whole comparison.

Projection, bound-state construction, numerical scaling, workspace setup,
objective scans and native retirement share one positive caller work allowance
with the floating solves. Each next child receives a share of the remaining
work; an exhausted grant is never passed as the floating API's unbounded zero.
Private numerical owners admit each charged kernel before execution within that
positive grant. An unfunded kernel retires partial numerical state and withholds
the comparison while retaining the funded prefix's charge. Basis repair whose
cost cannot be reserved declines in these owners.
Construction reserves its scaling bound before allocation and retains actual
preparation work when the query completes. Scratch factors use bounded entry
and construction work limits, and every scratch owner closes on all exits.
Failed and partial work remains in the reported preparation and solve metrics.
Decline diagnostics distinguish exhausted work, cancellation, unavailable owners,
unfinished solves, stale points, missing optima and invalid splits. A declined
comparison retains its completed-child count without supplying partial advice.
Preparation preflights retain workspace and scalar requirements, remaining work,
identity or scaled reuse, and the refusing preparation stage. These fixed observations
grant no work and construct no numerical owner. The caller owns probe admission and cadence.

A retained numerical owner can expose a private solve cursor. It suspends between
iterations, retaining the current phase, factors, artificial seats, pricing state
and cumulative solve limits. A pause returns no numerical result or certificate.
An opt-in bounded progress trace samples scaled objective and basic-bound
infeasibility separately for boxed dual, ordinary dual, primal feasibility and
primal objective phases. It counts degenerate steps, flips and priced columns;
factor-policy metrics identify rebuild triggers. These observations carry no
coordinate, bound or infeasibility authority.
Retained sparse consumers publish maxima of bounded trace snapshots at cursor pauses
under separate `Paused` fields. Phase flags identify the last sampled phases in
those snapshots; a capped trace need not describe the latest iteration. Maxima can
come from different consultations or workers and do not form a trajectory.
Reading a snapshot neither advances the cursor nor constructs a numerical owner.
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

The default backend can export and import defensive symbolic pivot-order proposals,
including after Forrest-Tomlin updates. Each owner copies and validates the
permutations; replay checks every numerical pivot and retains failed proposal and
fallback work. Proposals carry no numerical factors or exact-order authority.

The default backend also offers bounded owned copies of its current numerical
basis. Each private owner retains immutable LU matrices, permutations and published
Forrest-Tomlin coefficient slices. Adjacency containers, orders, transform lists,
row arithmetic status, scratch and meters stay private. Updates replace published
buffers; refactoring or retiring one owner preserves its siblings. Update counts and
fill/stability limits remain intact. Source keys and a producer generation check
reject stale, singular, repairing, closed or cancelled offers. The caller funds
ordinary source/workspace construction separately from private structural-copy work
and reserves entries before allocation. Partial copies publish no owner. Exact
ordering offers retain their ordinary freshness checks; numerical copies supply
no certificate authority.

Mutable coefficient inputs are defensively captured before use. Private backend
copies retain the producer's captured immutable source snapshots across retirement.
Their factor-builder forks allocate fresh construction buffers, and all mutable
numerical state stays private. These copies omit repeated coefficient capture;
ordinary construction retains its defensive snapshots. Retaining source bytes
supplies neither a basis identity nor certificate authority.

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
request by itself. Reuse requires the production acceptance policy. Immutable exact
authority matches by state identity; mutable working models require a complete
bounded capture key. Bounds, scope, active rows, matrix, objective,
origins, strictness and integrality remain part of that authority. These independent
packages retain no floating vectors or ordered basis; exact factor reuse still requires
its existing matrix and ordered-basis compatibility checks. Edits and owner retirement
invalidate checked packages. Cache admission and arithmetic budgets retain their limits.

Exact dual checks validate the complete input before skipping products with exactly
zero multipliers, right-hand sides or selected endpoints. Skipped entries retain
their visit charges and cancellation polling; reduced-cost signs, required bound
sides and source support remain part of the complete check in both fixed-width and
arbitrary precision. Captured authority records whether any structural bound side
is open. Fully boxed structural models accumulate the objective in the column pass.
With an open structural side, dual verification checks every required side before
accumulating right-hand-side and endpoint objective terms. The fixed-width check
charges bounded storage for selected reduced costs; both checks charge their endpoint
pass within the same certification allowance. The captured applicability flag is
charged with the input authority.

Claim phases report inclusive certification time, existing charged work and terminal
refusal, including unsupported authority, missing bound sides, scalar limits, singularity,
resource stops and policy refusal. These work totals overlap the certifier ledger and
must not be added to it. Bound reconstruction additionally reports its
validation and exact-input capture prefix, including stopped prefixes, through the
optional phase observer. Its work and time are subsets of the inclusive bound
certificate phase. Exact-basis candidate reconstruction and fixed-width and arbitrary-precision
dual verification report separate child phases, retaining overflow and declined prefixes.
Their work and time are disjoint from input capture and remain subsets of inclusive
certification. Vector reconstruction and support remain in the containing phase.
A reconstructed candidate supplies no authority until the complete certificate check
and policy accept it.
Separate consumption observations count accepted leaf packages
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

Rational work escalates from `Frac128` to `BigFraction`. Production exact
continuation promotes a retained complete tableau on fixed-width overflow; other
bounded operations restart from authority. Every attempt is charged. An internal
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

Finite floating guidance is projected onto the closure of its prepared exact column
sides before conversion to source coordinates. Interior values retain their floating
representation, absent sides stay open, and nonfinite advice declines. This projection
supplies no feasibility or proof authority; witnesses and certificates still require
their complete source checks.

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
Completed nonterminal node queries classify their strongest checked bound against
the standing incumbent as absent, short of it, or reaching it. Cut-supported
classifications are subsets of these observations. Terminal claims and native
bound pruning retain separate counters; comparison counts provide no bound value
or gap magnitude.
A checked partial bound retained during a paused numerical query can close that
same node after the incumbent improves. The consumer invalidates unfinished guidance
and retains the bound and its cut support without another numerical query. Caller
cancellation and slice pauses still precede this closure.
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
Opt-in fractional-attempt observation reserves one of its record slots for the
first attempted query, its paid point, current and actual generation scopes, and
preparation outcome. Funding and operation snapshots bracket preparation. Its
128-work, 8-KiB metadata frame and captured entries/bytes debit the containing
source allowance as well as the observer limits. A temporary journal shares the
remaining observer limits and containing funding; it keeps at most 16 unchecked
rejected inequalities with 128 coefficients/owners and 256-bit scalars, and at most
64 event names. Completed records retain scalar snapshots and immutable values
without retaining the journal's live budget. Inspection cancellation withholds new
output without revoking already retained immutable evidence.
The journal can attribute disjoint generation phases to accepted containing-meter
charges. Parent spans close before child derivations; interrupted spans retain
their paid prefix. Rejected rows keep funded immutable cumulative phase costs
within their seed-row or source-pair execution segment. Immediate continuations
share that attempt ordinal, so its successive prefixes overlap. A deferred
continuation starts a new ordinal after intervening seeds; its prefix contains
only resumed work. Both segments remain in the complete generation totals. Bound substitution, rounding and their remaining
proof construction form one phase. Indexing and ordering remain in the containing
totals. Fixed phase snapshots and record copies share the existing observer and
source allowances; absent or limited observation supplies no complete cost.
The first retained fractional attempt can reserve a separate 128-work, 8-KiB frame
for its augmented query's scope, installed mask and scalar funding, owner, basis,
fill and certification snapshots. Each observed call charges one additional work
unit to both ledgers. Pauses retain one frame and count resumed calls;
completion, failure, scope change or invalidation ends the interval. An unfinished
or stopped observation supplies no complete cost. Snapshots include observation
charges and any intervening work sharing the containing budget; they overlap the
existing totals and supply no independent proof or whole-source timing claim.
Imported cut proof records preserve their original scope, claimed inequality and
nested derivation. Bound and refutation imports keep their claim semantics;
depth or cancellation limits leave an explicit unavailable derivation.
Observing performs no solve. Explicit replay creates a fresh owner with separate
cumulative work, allocation, operation and cancellation allowances; it rechecks
source derivations and cannot publish deductions into the original traversal.
Replay masks name the complete captured cut pool in its recorded order, including
family and mixed-row metadata. Import and template validation spend the replay
allowance; generation does not replace the captured definitions. Partial pools,
unavailable family tags and out-of-range masks decline without numerical authority.

`source-point-cuts=true` opts the retained frontier into point-based separation.
Ordinary linear-source execution binds cut separation, selection and advisory
branching controls to its admitted numerical companions. The containing source request owns these settings and removes
them from finite worker recipe options; they change neither companion admission
nor lifetime. Joint repair binds separation, point selection and aggregation to its
shared numerical owner for both root preparation and retained neighborhood queries.
Its advisory root center and neighborhood order use the paid point preceding
selection when the selected query remains nonterminal without a checked incumbent.
A selected witness or terminal keeps its selected guidance. The baseline point
uses the selection round's existing funded retention and carries no proof authority;
the strengthened bound and selected rows retain their own result channels. This
requests no additional LP query and changes no generation allowance.
Fractional-order aggregation follows a completed nonterminal current point query
whose entire retained cut pool is checked and installed. Partial pools defer
without spending the template's generation attempt. The coverage scan charges
`1 + 2 * poolRows` work and 64 modeled bytes to the containing allowance; counters
record readiness, deferral and the attempted retained, checked and installed sizes.
The continuation keeps the query's checked definitions, installed mask, strongest bound and paid exact
point, then appends funded proposals under the 64-row ceiling in the same numerical
generation scope. It attempts one augmentation per immutable template using only
the containing allowance's remaining work, memory and operations. Generation and
the additional query have separate charged costs. A candidate selector must retain
every still-checked installed ID before adding a row. An immutable template
identity keeps that prepared pool compatible with explicit masks while preserving
its original complementary provenance; each query rechecks the selected derivations.
A paused candidate query retains the completed current result and resumes its
existing operation. Decline, cancellation or exhausted funding restores the current
recipe and selector, retaining all failed-attempt costs. Cancellation withholds
advisory data. Restoring the recipe does not promise a free numerical basis rebuild.
Other point preparation uses point-directed row alternatives.
A new template first runs the ordinary checked cut recipe; a completed point can
add one selection round at that node. A trial that admits no candidate retains
the ordinary template, checked query and numerical owner for later queries of that
template. Successful selection retains its checked mask instead.
Integer-only templates and aggregate-only cut families with at most 64 fully checked
installed cuts adopt that mask directly from the current recipe, without another
LP query or a root miss.
Their later completed points age installed rows under the same scoring limits.
Complementary endpoint mode retains an adopted current template; its mask refers
to the installed definitions. Integer-only templates also share endpoint modes
because they add no continuous bound alternatives.
The current baseline, candidate preparation and any replacement keep their
separate containing costs. Query matching compares immutable source data without
copying public snapshots. Funding preparation traverses the captured premises and
charges each visited row, coefficient, guard and integer pin; cancellation retains
the visited prefix's charge. The score combines normalized violation,
inverse square-root support and objective parallelism. The model-facing cut pool
and source selector share the greedy parallelism filter. When a singleton objective
coordinate occurs in no candidate row, a unique pair of opposite, non-strict,
unconditional source rows can express its scoring costs in the other coordinates.
This one-step projection admits at most 128 row entries and 4,096 combined numerator
and denominator bits per scalar. Row visits, exact pair checks and floating
projection spend the scoring allowance. Ambiguous, scoped or nonfinite definitions
keep the raw scoring costs; exhaustion or cancellation retains the installed mask.
The numerical objective and proof premises retain their original representation.
Retired installed-row scans omit this objective projection. Floating scores guide
selection; source inequalities and their derivations remain exact rationals.
Nonfinite projections supply no score. Each call visits at most 8,192 support
entries with 65,536 modeled scoring work and 1 MiB modeled allocation, within the
containing cumulative source allowance. A template retains at most 64 selected IDs.
Changing its source layout clears those IDs; every query rechecks guards, pins and
derivations. Unselected cut rows are omitted from masked queries unless they belong
to an adopted current family or a fully installed selected family, whose inactive
definitions retain its numerical layout. Changed row layouts invalidate numerical
cursors through the ordinary compatibility checks. Changed activity invalidates completed guidance before
reusing the owner and its basis.
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
