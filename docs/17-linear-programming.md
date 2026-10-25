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
the default factory; `ComponentLpSolver` composes it for component-wise OBBT.
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

### Bounding and explanations

[LpPropagator](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
maps source decisions to bound assertions, retracts through the trail and supplies
certified bounds, fixings and Farkas conflicts under an effort governor. Farkas
explanations cite the selected bound witnesses and all contributing row premises;
every learned literal is false at the conflict and implied by the cited antecedents.
One integer or disjunction leaf cannot refute its sibling branches.

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

### Cuts

Cuts aggregate exact source rows and apply their rounding rule's bound and
integrality checks. Local cuts keep their substitutions and premises and are
revalidated after pop or remap. Selection considers efficacy, parallelism,
orthogonality, age and row caps; separators check actual column integrality, so
continuous columns decline. Root harvesting caps retained cuts at the original row
count, and root and node separation keep their round, depth, pool and work
allowances. The [portfolio](12-portfolio.md#lp-arm-admission) decides when
an aggressive LP companion runs.

Explicit bound-ordered sparse frontiers consume single-row Chvatal–Gomory rounding.
Other guidance calls can opt in through the adapter. The adapter
normalizes an integer-only source activity by its exact rational coefficient gcd
and retains a cut only when rounding tightens its right side. Continuous activity
declines. Preparation visits at most 65,536 modeled row/support operations with
4,096-bit input and denominator limits. Cut definitions and source-row ownership
stay in the retained template, ahead of changing invocation-bound rows. Every
query rechecks active cuts against the exact numeric source context under one
shared 100,000-step proof allowance and premise view; inactive
Boolean guards or integer pins disable the owner and its cuts together. Retraction
restores those activation masks. Unverified cuts remain inactive and require an
identity row layout. LP certificates cite the nested source rounding
derivation, and final source checking repeats Boolean substitution independently.
Preparation and proof traversal report separate work observations, including
interrupted queries. Separation and
proof policy belong to the adapter, outside `lp.engine`.

### Root preparation

Finite satisfaction and optimization share root hull pruning and cut harvesting;
objective shaving and incumbent-relative fixings are optimization work. Root
preparation runs once, under a common time and work allowance, before shared
initialization publishes its bounds; a cancelled phase contributes only completed
deductions. Interrupted root rounding retains its relaxation layout and basis for
later slices, charging every retry to the remaining root and LP work allowances.
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
or exact feasibility continuation. Its accepted bound retains row multipliers for
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

### Leaf completion

Finite optimization keeps a pinned real-leaf request across portfolio slices.
The discrete assignment, assembled row authority, source encoding and required
certification remain owned by that request. Numerical pauses preserve the live
solve; a completed floating solve can wait for certification in a later slice.
Source encoding and relaxation assembly are atomic preparation of the request.
Neither pause consumes the discrete model or records an indeterminate region.
Reseeding and shared objective-floor imports are deferred while completion is
pending, and explicit rebind or close discards its numerical owners before changing
the traversal.

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

`LinearAuthority.advance` consumes the shared sparse preparation, floating and
bound-certificate policy through that same scoped owner. One admitted operation
survives unchanged consultations; changed source scope, sides or active rows revoke
its admission and numerical cursor before another query. A pause publishes no
coordinates or certificate. Compatible work and pivot allowance promotions retain
paid preparation, phase-local values and the numerical cursor. A funding stop at an
iteration boundary keeps that cursor and reports its limiting resource; unchanged
stops and completed declines are cached. A promoted allowance can resume a funding
stop. Authority edits and cancellation retire it. Completed coordinates propose exhaustive integer splits
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
Residual completion seeks feasibility; attainment still requires checked objective agreement.
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
restarts the operation from authority, and every attempt is charged. Owner-local
exact factors require the same matrix and ordered basis. An off-status feasible
point cannot furnish a claimed warm basis.

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
`NumericalLinearRequest.solve` serves MPS through the same numerical authority, and
these adapters never confer source semantics on a rounded or tolerance model.

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
