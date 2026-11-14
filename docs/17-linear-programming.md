# 17. Linear programming

Finite relaxations, mixed-integer models and [linear theories](15-linear-arithmetic.md)
share one floating revised simplex. Open theories always certify its results exactly;
finite search and presolve accept them within tolerances for sources that declare a
positive feasibility tolerance ([numerical and exact deductions](#numerical-and-exact-deductions)). Model-facing
adapters assemble source-aware rows; the engine solves and certifies them; consumers
interpret the result in source coordinates. Shared search owns integer and Boolean alternatives.
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

Component discovery uses Util's dense disjoint set over structural columns sharing
a row. Blocks retain first-seen column order; columns in no row stay outside child
layouts and use their bounds directly.

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
Admission counts one row for an unconditional `Linear` factor; its coefficients
are domain independent for fixed-layout planning.
Within a feasibility-pump invocation, admitted preparation can retain its completed
root assembly for later distance objectives. Reuse requires a compact discrete
layout without auxiliary columns or row premises, cheaper objective mapping than
completed construction, and a combined 262,144-unit storage ceiling. The invocation
keeps its source and propagated root fixed. Shared finite domain tables require
contiguous input domains and retain a bounded endpoint snapshot; changed bounds
or holes retire the assembly before another objective consumes it. Stable source
declarations need no repeated domain scan. Each objective gets private costs and
a fresh numerical solver; preparation, declined mapping and interrupted work spend
the same LP allowance. Pinning still realizes each proposal through propagation.
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
Planned: template retention moves out of `PreparedFactorProjections` into an LP-owned preparation cache.
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

The exact matrix, costs, objective sense/scale/constant, bounds, strictness,
integrality, origins, source premises, ordered basis and nonbasic statuses determine
the problem. Floating factors, values and reduced costs are revision-keyed working
state; pricing weights and progress traces are heuristic history and supply no
admission, allowance or certificate authority.

The model uses `[A | I] x = b` with bounded structural and logical columns.
`ExactLpModel` supports arbitrary bounds and origins; re-centering changes bounds,
RHS and objective constant together. Compact Long inputs keep authority beyond
`2^53`, wider data uses rationals, and a Double input denotes its exact IEEE value.
Projection to Double must not infer fixedness from equal rounded endpoints. The
engine supports missing bound sides and FREE statuses. MPS decimal authority is
separate; see [MPS semantics](05-arithmetic-formats.md).

`LpModel` is the normalized form the floating simplex consumes: lower bounds shifted
to zero, `>=` rows negated and logical columns appended over a Long core, with an
optional binary64 view for real data and an optional exact working state.
`authoritativeModel` imports it into `ExactLpModel`, keeping IEEE input bits.
`ExactLpModel.toIntegerModel` goes the other way only for consistent, integral,
non-strict data with zero lower sides, unit objective scale, no external objective
constant and Long values carrying no IEEE provenance; LP capture uses that integer
form.

## Consumers, explanations and cuts

Consumers decide when to request certified bounds, fixings, conflicts or witnesses
and how to apply and retract them. Their policy stays outside the engine. Floating
points, coordinates, scores, orderings and observations are guidance throughout this
section: they never supply bound, witness, refutation or certificate authority, and
ordinary search keeps every alternative they reorder.

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
bounds, fixings and Farkas conflicts under an effort governor. Farkas
explanations cite the selected bound witnesses and all contributing row premises;
every learned literal is false at the conflict and implied by the cited antecedents.
One integer or disjunction leaf cannot refute its sibling branches.

The bounding callback passes the final node relaxation's primal and dual point to
finite search hints after cut re-solves. Only an optimal result supplies an
objective sample; an unfinished cut solve keeps the preceding point, and cancellation
withholds publication. [Finite guidance](08-search.md#finite-lp-directional-guidance)
owns selection and history lifetime.

An exact state appending one bound copies only the changed side; tightening can
introduce a conflict only at that column, so the state combines it with the
predecessor's first conflict without a scan. A node query that pivots but stops on
work before returning a point grows the adaptive request within its ceiling and
remaining allowance; numerical stops, zero-pivot stops and infeasibility candidates
give no growth feedback.

For mixed models, exact node bounding uses the
[mixed deduction operations](#mixed-deduction-operations); numerical bounding skips them.
It keeps the strongest objective bound and applies only integral consequences
with complete explanations. For a bound `L`, reduced cost `d(j)` and cutoff `U`, a
fixing allows movement at most `(U - L) / abs(d(j))` from the minimizing endpoint.
Objective changes retract dependent bounds, cuts and clauses, or restore an
objective-independent region.

The explicit `+sparse-trial` LP option selects a bounded root trial for compact
closed unconditional linear sources; emphasis levels leave it disabled. Admission
bounds source assembly, then inspects emitted nonzeros, component dimensions, exact
scalar width and row-intersection fill risk on the same source-root boxes as the
retained layout. Custom emitters decline because they reserve no source size. The
trial uses the ordinary bound, fixing and conflict consumers with their exact checks;
cuts and expanded hulls stay outside its allowance, and admitted roots enable the
ordinary root primal consumers. The initial root bound and later root propagation
share one scoped numerical owner, and handoff requires matching source identities
and root scopes. The owner advances its floating cursor in adaptive work quanta; a
pause publishes nothing and authority edits discard the cursor. Completed floating
results enter the exact bound check within a 16 MiB checking reservation;
exhaustion, cancellation or refusal withholds the root bound.

Sparse-trial preparation is capped at eight million modeled work units; root and
node LP work stop at 150 million units or the containing invocation's smaller
allowance, and tighter caller deadlines and work shares remain in force. Bounded atomic numerical
operations can finish past a work boundary; the ledger keeps their actual cost.
The two-GiB modeled storage envelope covers source authority, sparse owners,
scratch and factor construction. Each sparse factor attempt reserves entries
before writing and stops at two million entries or eight million work units;
declines publish no factors or rank claim. Sparse admission never changes the dense
exact-continuation caps.

Completed root points can guide rounding under the same objective identity, within a
one-MiB guidance cap. Rounding pins through propagation with nearest targets, and
retained-root plans also try upward and downward targets; an interrupted pin restores
the last complete fixpoint. A completed point can instead transfer to ordinary integer
value ordering when its source coordinates and objective still match; source real
variables, masked or unfinished proposals and disabled LP branching withhold it.
Before the first incumbent, an integer-only traversal can visit the declared factor
components of undetermined columns contiguously, known fractional coordinates first.
A ready component guide defers simplex node queries until the first incumbent while
inherited checked bounds, combinatorial bounds and CP checks stay active. Completed
component assignments can survive restarts as private value preferences. Guide
replacement, reset, reseed and engine close release these structures.

### Immutable component ownership

The explicit `lp-component-ownership=true` setting lets finite node bounding retain
one numerical owner per independent row-coupled block. Each child keeps its basis
and exact result authority; empty structural rows form one logical-only child that
retains feasibility, strict sides and logical costs. Bound, cost, row activation and
premise changes invalidate only affected children, and unchanged children reuse
optimal floating results and checked bounds under the same certification policy.
Query work and pivot allowances cover the whole collection, including failed
children; reuse adds no charge. Prepared root cuts belong to the initial layout. An
ordinary component root uses the same scoped owner for its initial bound and later
node queries; when no owner exists, preparation partitions source and cuts together,
and a declined live append falls back to the ordinary owner. Finite basis entry
limits are divided among the blocks.

Child bounds stay exact rationals until their sum, isolated-column contributions and
the objective constant, scale and sense are combined; rounding applies to that
aggregate, and bound support lifts every contributing row and side to the current
parent state. Floating child optima alone supply no global optimum or conflict
authority. Completed integer components can retain node deductions at the parent's
common dual-rounding scale, within eight MiB of the shared 16 MiB checking envelope;
mixed columns, non-unit objective scaling and unsupported certificates use the
whole-model route, and the independent safe objective check stays in the decision.

After a work or pivot stop, completed blocks contribute checked dual bounds and
unfinished blocks freshly checked zero-dual box bounds; a missing minimizing side
declines the aggregate. That bound supplies no point, cut tableau or attainment
claim. Root consumers verify a lifted dual candidate with zero multipliers on blocks
without a result before projection. Node consumers may prune against the existing
cutoff, but publish no branching coordinates or directional history from a partial
aggregate. Returned child coordinates can feed a masked rounding guide.

The same setting dispatches root tableau separation to the owning block. Lifted cuts
keep every row multiplier, bound substitution, integrality premise and source
address for the ordinary source mapper and cut pool. Rows confined to existing
blocks replace only those children; cross-block rows, new columns and other
structural edits retire the collection for the ordinary owner. Callers that need one
retained basis keep the monolithic constructor.

### Cuts

The internal `composeBooleanBranches` traversal option, disabled by default,
augments partial-row feasibility queries with common real consequences of the two
alternatives of one unresolved source Boolean, combining at most two rows per
alternative with exact positive weights. Equal coefficient vectors keep the weaker
right side, and strictness survives only when both alternatives establish it. Each
consequence imports a `BooleanSplit` proof, so mutually exclusive guards are
discharged by exhaustive coverage rather than activated together. The final
refutation rechecks dormant premises with their original guards and pins, and no
consequence becomes an original premise or witness authority.

Cuts aggregate exact source rows and apply their rounding rule's bound and
integrality checks. Local cuts keep their substitutions and premises and are
revalidated after pop or remap. A cut pool can retain a mapping's arithmetic
validation and a projected column layout for an immutable source definition within
its 262,144-unit ceiling, but every mapping still checks the current namespace,
assumptions, guards, column geometry and any derived proof, and a projection retains
no source map, witness or numerical result. Selection considers efficacy,
parallelism, orthogonality, age and row caps; separators check actual column
integrality, so continuous columns decline. Root harvesting caps retained cuts at
the original row count, and root and node separation keep their round, depth, pool
and work allowances. The [portfolio](12-portfolio.md#roles-and-the-mixed-roster)
decides when the aggressive LP emphasis is seated.

Cut family identity follows portable proofs and retained rows without contributing
to proof authority or deduplication. Observations separate construction, mapping
refusal, admission, selection, tightness, retirement and retraction; a tight or
selected row establishes no deduction, and certificate support is counted only when
a checked deduction is consumed. Per-family solve and certificate costs are
inclusive: families sharing a solve overlap each other and the ordinary LP totals.
Terminal results take their final statistics snapshot after releasing numerical
owners; rebindable handles stay live until their caller retires them.

Numerical frontier traversal separates source cuts through its adapter, outside
`lp.engine`; `source-cuts=false` disables it. Single-row Chvatal–Gomory rounding
normalizes an integer-only activity by its exact coefficient gcd and keeps a cut
only when rounding tightens the right side. Mixed rounding normalizes the integer
activity and cites an unconditional source bound for every continuous term,
preferring the side that keeps a negative residual; missing sides and integral
shifted right sides decline. Cut definitions and source-row ownership stay in the
retained template. Every query rechecks active cuts against the exact numeric source
context under one shared proof allowance; inactive Boolean guards or integer pins
disable their rows and dependent cuts, and a cut with several source owners needs
every owner active. Certificates cite the nested rounding derivation, and final
source checking repeats Boolean substitution independently.

Point selection admits at most one additional checked cut per usable point and
waits for its completed query before scanning again; only a strictly stronger
checked bound, a checked incumbent or terminal evidence skips the discovery
cooldown. Unselected candidates retire after four completed scans without an
addition, and installed rows expire after four slack scoring scans. Retirement only
weakens the relaxation. Managed masks keep unselected and retired rows as inactive
numerical rows with free logical columns and zero multipliers, so a compatible owner
survives additions and retirement. Equality packing requires matching activity on
both packed sides. A new template first runs the ordinary checked recipe; a completed point can add one
selection round, and a successful round retains its checked mask. Scores combine
normalized violation, inverse square-root support and objective parallelism and
guide selection only. `source-point-cuts=true` opts the retained frontier into
this point-based separation; the ordinary default activates all accepted source cuts.
The containing source request owns these separation, selection and advisory
branching settings, binds them to its admitted numerical companions and removes them
from finite worker recipe options.

Explicit aggregation combines two to four source rows with positive exact weights
and normalizes the integer activity by its gcd; complete continuous cancellation
permits integer rounding, otherwise mixed rounding cites an active bound for each
remaining continuous coordinate. `source-aggregate-cuts=true`, disabled by default,
adds a template policy that pairs rows of opposite sign on a shared continuous
coordinate, with at least one integer-activity row, and retains at most 64
proposals. Pure-integer points also try half rounding and two-row combinations,
greedy continuation along at most four original rows, and half scaling whose odd
coefficients cite established endpoint premises. An established invocation bound can
stand in for a missing stated endpoint only as a typed assumption that every cut
verification resolves in the current scope; retracting it withholds the cut. Slack
and scope ordering choose premises only. Every candidate keeps its owners and nested
derivation and needs source verification before admission.

### Root preparation

Finite satisfaction and optimization share root hull pruning and cut harvesting;
objective shaving and incumbent-relative fixings are optimization work. Root
preparation runs once under its containing time and work allowance; a cancelled
phase contributes only completed deductions. Root rounding and pumping propose
discrete assignments pinned through the arm's own propagation; a relaxation point
supplies a target, never witness authority. Interrupted rounding keeps its layout,
basis and completed coordinates for later slices without another solve. An
original-objective probe can reuse a prepared root owner while its exact model and
root scope stay current, spending the smallest remaining primal, root and worker
grant.

An integer guide with known fractional coordinates can prepare a private probe over
independent integer components, trying nearest, upward and downward targets per
component. A failed direction retracts only that component's pins. When all three
decline, a bounded private repair revisits earlier pins in the component with an
owned frame stack; conflict levels that map to its frames can guide retreat, and
foreign levels keep chronological retreat. Exhausting choices or allowance supplies
no infeasibility authority, and no partial assignment is a witness. A restart keeps
one generation of the engine's owned coordinate buffers for replay as soft
preferences, without a numerical query.

Retained discrete root layouts prepare in private chunks before the first numerical
consultation. A paused builder keeps only its source-root assembly buffers; no partial
layout, point or certificate is visible. Completion prepares the layout, private
exact state and numerical owner before the consultation's numerical clock starts,
and the consultation adopts that owner only while its source-root model, exact state
and root scope remain current.

The internal root-only policy gives source projection and owner construction the
remaining cumulative LP allowance while each floating query keeps its adaptive
allowance. The internal checkpointed policy keeps one numerical operation across
consultations of unchanged exact authority, retaining scaling, phase, artificial
bounds and basis; changed bounds, objective, rows or trail scope discard the cursor.
Sparse source preparation transposes emitted support into exact CSC once per
admission, which neither authorizes ordinary admission nor bounds basis fill;
`SparseSourceLpPolicy` carries the preparation, floating, consultation and
certificate allowances, and retained MIP callers fund consultations through
`SourceLpFunding`, where caller cancellation closes the owner and exhaustion is an
incomplete result.

Identity row layouts are the default. An explicit unconditional-equality policy
combines adjacent closed, exactly opposing inequalities into one row with a fixed
logical column. The emitted multiplier's sign selects the original inequality when
lifting row weights. Conditional templates retain their identity layout even when
their guards currently hold: a later sibling can retract either side. Source
lifting and ordinary evidence checking still decide whether the bound is usable.

An admitted sparse root query can copy nonzero row multipliers from its suspended
owner as a dual proposal without a basis solve; only the exact check against the
unchanged root model establishes a bound. Dual-only certification runs under its own
input, arithmetic and allocation limits. A refused candidate enters bounded rational
reconstruction: a retained basis proposes duals by peeling singleton equations and
variables and solving residual components with rational factors, and a
dimension-limited component proposes a subset for its open basic directions that
need absent sides. Continued fractions are the last proposal. Every proposal must
pass the complete exact reduced-cost and bound-side check; basis-derived bounds need
both rational and exact-basis policy acceptance. Accepted bounds keep their row
multipliers for source lifting. A completed infeasibility candidate enters bounded
Farkas reconstruction the same way; the exact ray check yields row multipliers and
bound-side support, and source lifting restores extracted sides before checking.
Only that checked derivation closes a traversal leaf or repair query.

`source-refutation-reuse=true` enables a traversal-owned index of checked source
refutations. Cited coordinate sides select candidates; full scope implication and a
fresh source import check authorize closing a matching leaf before its numerical
query. Only refutations enter the index, and the original source, objective,
invocation window and Boolean substitution stay bound to each entry.
`source-refutation-propagation=true` adds integral unit-prefix deductions: the only
eligible case is a single missing inclusive bound on a source integer with an
integral endpoint, and the augmented scope's import check proves the excluded
sibling. `source-refutation-resolution=true` adds bounded pairwise resolution over
named integral predicates, checking each resolvent through a source integer-split
derivation. The index bounds entries, proof storage and support traversal, evicts
its oldest entry and charges cumulative source funding; reaching its separate
allocation limit clears it and leaves ordinary traversal running. Unknown or
cancelled checks close nothing.

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
Exact trail edits do not require a floating projection. An incompatible projection
retires the floating owner, while crossed sides are certified directly against the
exact state and acceptance policy. A later projectable state can prepare a fresh owner.

A nonbasic bound change updates the basic values by `-B^-1 A(j) delta(x(j))`; pop
reseats nonbasics and recomputes `x(B) = B^-1 (b - N x(N))`. Compatible edits and
pops retain factors; missing sides require status and dual-feasibility repair, and
objective edits invalidate dependent proofs.
[LpScopedSolver](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpScopedSolver.kt)
and [CpLpAdapter](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/CpLpAdapter.kt)
hold bound trails, active-row identity and verification caches.

Rows have stable identities. Deactivation removes slack bounds, strictness and
integrality, and is rejected while surviving assertions need the row; global
source-valid cuts can survive pop. Appends and compaction prepare a fresh
fixed-dimension owner with warm headings and publish it before retiring the donor;
live factors are never resized. A projection that cannot import exact authority
declines without a partial layout: node bounding withholds deductions and leaf
completion returns indeterminate.

Checked packages retain accepted bounds and witnesses under the same exact state
and production acceptance policy. Immutable exact-state identity admits reuse
without serializing the matrix; mutable working models need a complete bounded value
key, and a distinct exact state invalidates the package even when it shares source
definitions. A weaker checked bound cannot satisfy an attainment request. Exact
continuation keeps cumulative import and feasibility work; an unfinished import
requires the same authority, ordered headings and statuses. A fixed-width overflow
promotes the last complete tableau to arbitrary precision, keeping imported columns,
basis order, statuses and strict-side infinitesimals. A ready feasibility tableau can
rebind compatible bounds, with publication checked against the current source.
Refinement admits one unseeded and one exact-point-seeded attempt per state, limits
and claim; repeated failures supply no infeasibility authority.

An optional per-context query observer runs after a session or scoped query adopts
its exact model and before numerical work, borrowing the working model and copying
what it keeps within the containing allowance. Capture encoding bounds values and
bytes and withholds the whole payload on exhaustion. Observation grants no witness,
bound or proof authority, and ordinary contexts allocate no query records.

### Linear authority sessions

Numerical source traversal retains a `LinearAuthoritySession` per invocation.
Its fixed matrix has free structural columns; extracted source sides are asserted
as one checked batch in a replaceable scope, retaining witness IDs and assertion
depth, and child and sibling queries retract that scope while keeping compatible
factors. A declined batch retires the owner without publishing guidance.
Conditional rows stay in the matrix with their logical sides suspended while their
pins are unestablished. Objective edits invalidate dependent certificates and changed
structural authority replaces the owner. A completed owner can append up to 64
checked cut rows when every existing column, row and objective value and its metadata
are unchanged; equality-packing changes fall back to replacement. Each query lifts
multipliers through its own source-side mapping and checks the derivation in its
source scope. Neighborhood invocations can borrow a retained authority, leaving its
cleanup and cumulative funding with the owner.

`LinearAuthority.advance` runs the shared sparse preparation, floating and
bound-certificate policy through that scoped owner. One admitted operation survives
unchanged consultations; changed scope, sides or active rows revoke its admission
and cursor. A funding stop at an iteration boundary keeps the cursor and reports its
limiting resource, and a promoted allowance can resume it. Completed coordinates
propose exhaustive integer splits, including fractional source-bound endpoints, and
opposed coordinate sides refute a branch through checked source combinations.
Integral-looking points need an original-source witness check. A rejected integral
proposal keeps its exact integer assignment and completes connected continuous
residuals with the source LP owner, strict rows strict and inactive premises
excluded; every recovered point needs a fresh witness check. After that check, the
authority can refine the continuous objective with the discrete coordinates fixed,
accepting only an exact strict improvement that passes a fresh check; a fixed-pin
solve supplies no global bound or refutation. Residuals too large for exact input
admission use a sparse feasibility query whose completed coordinates go through
rational reconstruction and primal-only exact basis verification, publishing no dual
bound or conflict.

`solver.pipeline.SourceNumericalFeedback` delivers completed queries to decision
consumers. Latest-query consumers own one bounded slot; prefix consumers and
inspection observers share a bounded archive of the first queries. A record exposes
exact-valued relaxation coordinates, generating cut masks, active source rows and
scoped checked bounds or refutations, and a consumer names the projection it reads,
so a withheld field is explicit rather than unavailable. Compatibility requires the
same source premises, semantics, objective and query restrictions; a cutoff
attribution requires its actual strict objective premise. Cancellation, pauses,
missing or oversized queries and funding exhaustion stay explicit outcomes. The
opt-in `source-cut-branching=true` consumer orders frontier integer splits by
near-tight installed cut supports; both alternatives remain in coverage and feedback
never prunes.

## Floating simplex and basis factors

[SimplexWorkspace](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexWorkspace.kt)
owns fixed-dimension scratch for one engine, with distinct indexed carriers for
entering spikes, pivotal rows, RHS and dual solves so a basis backend can reuse the
form it produced. [SimplexBasisLifecycle](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexBasisLifecycle.kt)
owns headings, statuses and the lazy basis backend, and invalidates them on
retirement even when close fails. `SimplexPricingState` owns Devex weights and
`SimplexObservations` per-solve counters; `RevisedSimplex` coordinates allowance
charging, recovery and publication.

A retained numerical owner can expose a private solve cursor that suspends between
iterations, keeping phase, factors, artificial seats, pricing state and cumulative
limits. A pause returns no result or certificate; the owner rejects another solve or
authority adoption while the cursor is live, and closing it unwinds the operation.
An opt-in bounded progress trace samples scaled objective and bound infeasibility
per phase. A retained optimal basis can also report advisory objective costs per unit
of at most two basic structural columns, funded before allocation; the read leaves
the optimum untouched.

Explicit directional probes compare the floating objective increase at the two
adjacent integer bounds of at most two structural columns. Each child is an immutable
bound-state fork of the parent; one private owner adopts the siblings in turn,
starting from the parent basis and sharing its immutable CSC and row views, and
declines unsafe refreshes instead of rebuilding the shared matrix. The parent state,
optimum and factors stay untouched. Every direction needs a complete optimal solve
and a finite nonnegative gain, or the whole comparison is withheld. Projection,
construction, scaling and the floating solves share one positive caller allowance;
an exhausted grant is never passed on as the floating API's unbounded zero.
Child-value consultations instead accept a completed current iterate and report
child objectives in minimized source units without a parent-optimum claim.

A prepared all-logical native basis can seat a dual-infeasible free singleton
in place of its fixed logical column. The columns are scalar multiples, so the
opening preserves nonsingularity and the exact region. Repricing seats the other
nonbasics on their available minimizing sides; unresolved dual infeasibility
continues through primal repair.

Primal feasibility repair minimizes the convex sum of basic-bound violations
along an entering edge. It passes bound crossings while the directional slope
remains negative, then pivots at the minimizing crossing or flips the entering
column at its opposite side. Tied crossings prefer a larger pivot. The entering
spike updates basic values between fresh RHS solves, with a refresh after eight
updates or a factor rebuild. Feasibility and terminal pricing are rechecked on
fresh values; a positive residual infeasibility never supplies a primal point.

Columns with at most eight stored entries price and accumulate directly from the
installed CSC arrays; larger columns use Koblas's validated kernels. Dual leaving
selection uses verified Devex, reselecting when a maintained weight falls below
`0.25 * norm(rho)^2`. Entering selection uses a scaled Harris window, a ministep
floor and a breakpoint walk. Zero-objective solves choose between `MIN_BOUND_SUPPORT`
(default) and `LARGEST_PIVOT`. Refactorization responds to backend requests, sampled
residuals, factor fill or per-solve work growth, and a hard update cap. Unavoidable
repeated solve work does not justify rebuilding unchanged factors.

Power-of-two scaling guards lost nonzeros, overflow and underflow, and results map
back to unscaled authority. Safeguards include small-pivot checks, refactor and
recompute, logical repair, unscaled fallback and bounded Bland recovery on primal
stalls. A dual-infeasible cold slack basis can try bounded dual phase I on a working
copy; an artificial box never authorizes a source proof, ray or bound.

[simplex.basis](../klause/src/commonMain/kotlin/com/eignex/klause/simplex/basis)
owns sparse and small dense elimination, DFS reach, Forrest-Tomlin updates, logical
repair, indexed forward/transpose solves and rational factors. Each owner has fixed
dimension, both permutations and three-valued update results. Mutable coefficient
inputs are captured defensively before use. Dense partial-pivot LU is selected only
for dimensions 2–6 with at least 50% density in the selected basis; it produces the
same CSC factors as sparse LU, and a numerical decline retries sparse. Rational
factors reuse orderings but never approximate numeric factors, and only complete
exact rank determination establishes singularity. Koblas supplies the containers
and kernels; see [integration contracts](18-exact-arithmetic.md#numerical-dependencies).

The default backend can export symbolic pivot-order proposals, including after
Forrest-Tomlin updates; replay validates every numerical pivot and keeps the cost
of a failed proposal and its fallback. It can also hand a private owner a copy of
its current numerical basis that shares immutable LU matrices, permutations and
published Forrest-Tomlin slices while adjacency, transforms, scratch and meters stay
private. Updates replace published slices rather than mutate them, so refactoring
or retiring one owner preserves its siblings. Generation checks reject stale,
singular, repairing, closed or cancelled producers. Neither offer carries exact-order
or certificate authority.

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
claims need different evidence packages; where certification is required, a floating
optimum or failed check cannot prune or prove a result.
[LpSolve](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolve.kt)
owns these checks.

### Numerical and exact deductions

`LpSolveContext.numericalDeductions` selects how finite search and presolve consume
LP results. The mode follows the format: the finite pipeline sets it only when the source
declares a positive feasibility tolerance, which MPS and FlatZinc do by default. SMT-LIB, the
other formats, sources without a declared tolerance and `--exact`, which zeroes the
tolerance, keep exact certification. Open theories, source LP optimization and real leaf
completion never read it; leaf witnesses follow the source tolerance.

Numerical deductions compute no certificate. `FloatDeductionCertificate` sums the
Lagrangian `L(y)` from the float duals in binary64 and lowers it by that summation's
rounding plus an absolute and relative slack, so float noise never rounds an integral
bound up. A reduced cost beyond its rounding prices and cites its minimizing side; one
within it prices both sides and cites the finite ones. An open side is priced at the tightest
bound a single row implies, preferring rows whose validity a reason can cite, and the
reasons cite that row and each side the implied bound reads; a reduced cost within rounding
on a side no row bounds counts as zero. It supplies node pruning, objective propagation,
reduced-cost fixing with their reasons, root bounds shared with peers, and shaving; fixing
also tries the objective box (zero multipliers) and half the duals.

`floatFarkasRay` normalises a dual-unbounded ray and accepts it when its binary64 Farkas
margin exceeds the tolerance times the terms' size, pricing open sides at their row-implied
bounds. Each implying row is folded into the ray so its open column cancels, in binary64
and then exactly on the rounded integer ray, slacks first. That ray must pass again against
the box, each column at the side the exact sign of its combination meets, which is the side
its clause cites; otherwise the node is refuted without a learned clause. It backs
infeasibility pruning, learned conflicts and presolve root infeasibility. Mixed row
projections, strict-row solves and component bounds have no numerical form and are skipped.
Optimality, infeasibility and bound claims resting on these deductions are numerical. Under
exact certification every deduction uses the packages below.

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

Certificates expose exact multiplier snapshots, so model-facing adapters can lift
them into [source derivations](13-evidence.md) without adding model
dependencies to the engine. `LpCertificationPolicy` is the immutable acceptance
seam: certifiers always compute and report their observations, and the policy
decides which results may be used. A source check grants no permission to accept a
floating optimum. Finite deductions consult no certifier unless exact certification is
selected ([numerical and exact deductions](#numerical-and-exact-deductions)); open theories always certify.

Model-facing solves state an explicit claim: bound, contradiction, feasible completion or
attained optimum. Bound requests use dual-only reconstruction without primal recovery;
feasible completion stops when a checked point is available, retaining the trivial
exact bound of a constant objective. Contradiction requests
may stop on a checked counter-witness and never optimize it. Attainment requires the
same exact witness–bound equality as the result contract. Tolerance acceptance retains
its separate source-defined verdict and supplies no exact attainment claim.

Retained certification owners keep checked results in a single-package store. A
checked bound can answer a bound request but not a completion or attainment request. Reuse requires the
production acceptance policy and the same authority: bounds, scope, active rows,
matrix, objective, origins, strictness and integrality. Packages keep no floating
vectors or ordered basis, and edits and owner retirement invalidate them.

Exact dual checks validate the complete input before skipping products with exactly
zero multipliers, right-hand sides or selected endpoints; reduced-cost signs,
required bound sides and source support stay part of the complete check in both
fixed-width and arbitrary precision. With an open structural side, every required
side is checked before the objective terms accumulate. Claim-phase costs are
inclusive and overlap the certifier ledger. A successful check alone is not
consumption: separate observations count accepted leaf packages and actual bound or
conflict pruning. Heuristic LP seeding consumes floating coordinates directly and
requests no certificate.

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
An integral objective lattice lets the bound be ceiled. Under exact certification, node
bounding takes the tighter of the accepted safe and integer bounds. Variable bounds use the same
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
and objective edits prevent reuse. Exact node bounding prunes with the strongest
completed certificate; when none completes, an accepted safe objective bound still
supports pruning. These operations share one allowance: 128-bit scalar admission,
250,000 work units and 16 MiB of metered allocation.

### Model-facing linear optimization

Open linear models need the same guarantees in original-source terms. The
`lp.optimization` adapter accepts exact affine source premises, objective identity
and invocation restrictions, reconstructs nonnegative source row combinations for
bounds, and checks source witnesses and recession directions. A strict infimum is
reported as a limit: a checked strict bound, a closure endpoint and a feasible base
approached along `endpoint + t * (base - endpoint)`. These adapters never confer
source semantics on a rounded or tolerance model.

Retained exact row multipliers can supply the source bound combination directly;
the source verifier checks that derivation, and missing or rejected multipliers fall
back to a separate combination solve under the same allowance. Non-strict
coordinate inequalities become exact column sides that keep their original row and
scale, and residual objective and conflict coefficients lift through those sides
into the full source row sequence. Strict inequalities stay rows. Every published
claim is checked against the complete source context. Floating guidance is projected
onto the closure of its exact column sides before conversion to source coordinates.

The internal active-row consultation uses a zero objective and a separately checked
partial-scope refutation over the admitted numeric subset, with no point or
objective bound. Boolean substitution precedes integer-pin admission, and incomplete
capture declines. Retraction revokes pending numerical work, and a feasible partial
system leaves complete checking to witnesses and optimization.

`OpenTheoryPipeline.optimizeLinear` runs a complete affine context in the shared
search session. A branch optimum becomes global only after every required Boolean
and integer alternative has a checked bound or refutation; an improving source ray
needs no siblings. When numerical authority declines, the exact descent of the
[linear theories](15-linear-arithmetic.md) continues in the same state and allowance.
A checked incumbent can close a numerical node whose scoped bound excludes a better
objective, including a bound retained from a paused query after the incumbent
improves; a global optimum still requires the checked root witness and the complete
composed bound. Fractional branching keeps every eligible exact integer coordinate
in source order. An internal opt-in history of checked immediate-child bound gains
can choose the coordinate instead, and an internal aggregate branch policy, disabled
by default, can split on a primitive integer direction from paired active rows whose
feasible activity lies strictly between adjacent integers. Both restrictions stay in
the coverage tree and the original-source checker, and neither supplies pruning or
proof authority.

Joint repair binds separation, point selection and aggregation to its shared
numerical owner for root preparation and retained neighborhood queries. Before its
first checked incumbent, it can seed a neighborhood with integer pins cited by refuted
root queries, and `source-refutation-center-step=true` lets a compatible root
refutation move its center one legal integer step away from a cited bound side.
These seeds and centers are advisory: the refutation keeps its complete scope and
every discovered point needs full source checking. Each owner also keeps at most four
completed pre-incumbent regional refutations keyed by every Boolean pin, numeric
bound and cutoff; reusing one opens no query and neither reduces proof scope nor
publishes a global refutation. An optional observer captures selection and proposal
records under its own record, entry, byte and cancellation allowances, retains no
live numerical owner and performs no solve; explicit replay creates a fresh owner
that rechecks source derivations and cannot publish into the original traversal.

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

Every preparation, construction, copy, check and retention step in this chapter
debits its containing allowance before it publishes, and storage is reserved before
allocation. Declined, failed and interrupted attempts keep their charges; nothing is
refunded, and a funded step grants no further work. An interrupted step publishes no
partial layout, point, bound or certificate, and a later slice charges only its new
work. Per-family, per-phase and per-claim observations are inclusive and overlap the
ordinary LP totals.

Reconstruction and refinement poll ordinary tokens every 64 calls or 4096 work,
while resource caps are checked on every charge. Cancelled evidence is discarded.
A completed continuous check that stays undecided is skipped without learning a nogood, and
its incomplete flag prevents exhaustion or optimality publication. Numerical
failure, singularity, work stops, precision declines and cancellation keep distinct
diagnostics.

---

Previous: [16. Nonlinear arithmetic](16-nonlinear-arithmetic.md) · [Contents](README.md) · Next: [18. Exact arithmetic](18-exact-arithmetic.md)
