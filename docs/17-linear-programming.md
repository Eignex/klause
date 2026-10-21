# 17. Linear programming

Part V describes the numerical mechanisms that search and the theories of Part IV
call on. The linear programming engine comes first because it has the most
consumers: finite CP/COP relaxations, mixed-integer models and the
[open linear theories](15-linear-arithmetic.md) all run on one production floating
revised simplex with exact certification. Shared search owns
integer, comparison and disequality branches; LP feasibility alone does not
establish integrality.

The flow is to assemble a source-aware linear model, solve its floating working
representation, certify the requested result, then let the consumer reconstruct
or apply it. Bound changes and retraction update that same numerical scope. This
chapter follows that flow: engine boundaries, exact authority and consumer
integration come before private basis mechanics, and
[certification](#lp-certification) closes the chapter with the proof packages a
certified result carries. [Numerical dependencies](18-exact-arithmetic.md#numerical-dependencies)
describes the external kernel interfaces.

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

Several consumers share the engine, each owning its own search state. The engine
answers bound, fixing and conflict queries; the consumer decides when to ask, how
to apply the answer and how to retract it.

### Bounding and explanations

[LpPropagator](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
maps source decisions to bound assertions, retracts through the trail and supplies
certified bounds, fixings and Farkas conflicts under an effort governor. Farkas
explanations cite the selected bound witnesses and all contributing row premises;
every learned literal is false at the conflict and implied by the cited antecedents.
One integer or disjunction leaf cannot refute its sibling branches.

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

### Root preparation

Finite satisfaction and optimization share root hull pruning and cut harvesting;
objective shaving and incumbent-relative fixings are optimization work. Root
preparation runs once, under a common time and work allowance, before shared
initialization publishes its bounds; a cancelled phase contributes only completed
deductions. Root rounding and pumping propose discrete assignments for mixed
models, but a relaxation point supplies a target, never witness authority.

### Leaf completion

Mixed witness recovery can submit a connected real residual to `SourceLp` after
fixing integer guesses, within its `SourceLpBudget`. Only exact primal coordinates
are consumed, and a failed guess supplies no conflict or bound. Real array selection
emits index-range rows on every build, and a fixed index adds its equality with both
index premises. In every case the original source rows decide publication.

When the root real-factor estimate exceeds 1,000, required finite completion builds
a fresh selected residual only after every Boolean and integer is pinned. Under
exact production certification, a zero-objective residual can substitute fixed
structural coordinates and merge equal or opposite expressions into strict
intervals before the unchanged 1,000-row numerical admission. Structural columns,
origins and original row IDs remain mapped. Construction and projection share the
containing work ledger, with 10,000 rows and columns, 65,536 sparse or premise units
and 4,096-bit scalar admission.

For exact satisfaction, selected-prefix checks use the same fresh assembly and
compaction while unrelated finite coordinates remain open. They retain live
immutable finite domains, their bound intervals and tri-state Boolean snapshots;
every conditional row premise must already be assigned. The unchanged 1,000-row numerical cap and node
work allowance, including prior standalone solves, bound each query. Only an
infeasible result with a checked source clause can prune the prefix; positive
points remain relaxation values. An already available exact primal can supply
source real and finite integer coordinates for phase guidance without
another solve. Scheduled prefixes above the dense basis dimension request the
same bounded sparse recovery as required leaves; their cadence, source admission
and numerical work allowance remain unchanged. Integer columns retain fractional
relaxation values; uncarried integers take their current domain minimum. A finite
integer phase proposal takes the exact ceiling within the live domain bounds;
it supplies no integrality certificate and retains both search intervals. Mapping
and guard evaluation charge the same node and total work
ledger; admission keeps 4,096-bit scalars and a 65,536-unit hint workspace. This
guidance refreshes only when absent, within four mapping attempts over the LP
owner's lifetime, including failed transformations. Solver retirement and search
undo do not replenish them. It supplies no source witness or deduction. Required
leaf completion still waits for all finite pins and checks the original source.

Required large-leaf and scheduled prefix completion request bounded witness recovery above the
rational-basis dimension limit. A separate owner of the same exact model performs
sparse point repair and strict-margin refinement for component numerical solves.
Independent components can first recover strict points through exact continuation,
sharing the same recovery work, storage and time allowance before global refinement.
The existing refinement work, storage, scalar and auxiliary limits bound this
recovery, and every recovered point passes exact source verification.

A compacted refutation maps each supported interval to the original rows that
establish its strongest lower and upper endpoints, retaining a strict parent at
equal endpoints. It cites those rows' validity premises and both bounds of touched
structural columns. Weaker coincident rows contribute no proof condition. A freshness
snapshot retains at most 4,096 finite coordinates referenced by structural columns
or original row premises; its size is independent of unrelated source coordinates.
Source identity, captured Boolean values and finite domain state must still match,
and every emitted literal must be false in the live session. Unsupported source
scopes, bounds, storage or cancellation withhold the explanation. Required completion forwards a complete
source clause to native CP conflict analysis; the private nogood pool is a separate
consumer. Original-source checks still gate every published witness.

## Bound trail and warm state

[LpBoundTrail](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpBoundTrail.kt)
intersects assertions exactly and keeps a source premise for each accepted side.
Batches publish witnesses only for a committed prefix; crossed bounds cite both
sides, and pop restores bounds and deactivates scoped rows.
Assertion validation walks sorted scope marks together with assertion positions;
repeated marks preserve the depth of empty scopes. Shared assertion prefixes keep
their checked authority while appended assertions validate their row and witness.
Empty scopes can publish as one batch with the same assertion marks. Cancellation
leaves the predecessor intact, and a backjump through the batch restores the bounds
at its selected depth.

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

Source adapters can append neutral definition rows together before applying a
bound batch. Lookup publication completes after a committed append even if the
operation stops, and the new owner retains its stable lifetime cancellation
adapter. Bound batches keep each side's premise and per-bound modeled work debit.

## Floating simplex and basis factors

[SimplexWorkspace](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexWorkspace.kt)
owns fixed-dimension scratch for one engine, with distinct indexed carriers for
entering spikes, pivotal rows, RHS and dual solves so a basis backend can reuse the
form it produced. [SimplexBasisLifecycle](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexBasisLifecycle.kt)
owns headings, statuses and the lazy basis backend, and invalidates them on
retirement even when close fails. `SimplexPricingState` owns Devex weights and
`SimplexObservations` per-solve counters; `RevisedSimplex` coordinates allowance
charging, recovery and publication.

Dual leaving selection uses verified Devex, reselecting when a maintained weight
falls below `0.25 * norm(rho)^2`. Entering selection uses a scaled Harris window,
a ministep floor and a breakpoint walk. Zero-objective solves choose between
`MIN_BOUND_SUPPORT` (default) and `LARGEST_PIVOT`. Refactorization is triggered by
estimated work and fill, sampled residuals or a hard update cap.

Power-of-two scaling guards lost nonzeros, overflow and underflow, and results map
back to unscaled authority. Safeguards include small-pivot checks, refactor and
recompute, logical repair, unscaled fallback and bounded Bland recovery on primal
stalls. A dual-infeasible cold slack basis can try bounded dual phase I on a working
copy; an artificial box never authorizes a source proof, ray or bound.

[simplex.basis](../klause/src/commonMain/kotlin/com/eignex/klause/simplex/basis)
owns sparse and small dense elimination, DFS reach, Forrest-Tomlin updates, logical
repair, indexed forward/transpose solves and rational factors. Each owner has fixed
dimension, both permutations and three-valued update results. Dense partial-pivot
LU is selected only for dimensions 2–6 with at least 50% density in the selected
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

Certification is the step that gives a floating solve any authority: it turns a
numerical candidate into evidence about the exact active linear model. Feasible points, objective bounds, infeasibility and
unboundedness require different packages; one cannot substitute for another.

The packages come first, then the mechanisms that produce them:
safe objective bounds, exact reconstruction and refinement, Farkas and recession
checks, and strict inequalities. The later sections cover deductions for mixed
models, the model-facing optimization adapter, tolerance recovery and the
resource rules every mechanism shares.
[LpSolve](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolve.kt)
owns these checks. A floating optimum, failed check or resource decline cannot
prune, exhaust search or prove an exact result.

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

During import, exact conflict checks precede basis-changing pivots and follow the
completed target pass. An already seated target leaves the tableau, bounds and
nonbasic seats unchanged, so advancing past it only charges progress and polls
cancellation. Interrupted passes retain their completed target position.
Exact zero tableau coefficients contribute no arithmetic to value, epsilon or
pivot updates. Pivots copy the normalized row and each row with a nonzero entering
coefficient, sharing unchanged rows until a later pivot edits them. Row selection,
copies and coefficient traversal remain charged; nonzero arithmetic retains scalar
admission and pivots publish atomically.
Value sweeps select nonzero nonbasic seats once before visiting rows. Bland
selection compares a row's bounds only when its heading can precede the current
violated candidate.

Rational work escalates from `Frac128` to `BigFraction`; fixed-width overflow
restarts the operation from authority, and every attempt is charged. Owner-local
exact factors require the same matrix and ordered basis. An off-status feasible
point cannot furnish a claimed warm basis.
Certificate verification admits the complete source before evaluating candidates.
Exact zero primal columns, objective coefficients and dual multipliers contribute
no arithmetic; row bounds, strictness and proof support still receive their full
checks. Fixed-width conversion admits its converted value directly, while values
or operations outside that width restart from rational authority. Traversal and
nonzero arithmetic remain charged within the same verification allowance.

Requested sparse recovery eliminates singleton basis rows and columns without
fill, then factors each independent residual block within the existing dense
dimension limit. The full coordinate, entry, fill, scalar, work, allocation and
time limits still bound the invocation. Forward and transpose solves use the same
immutable elimination order and residual factors; changed bounds and objectives
are checked against fresh authority even when those factors are reused.

A strict point candidate can seat nonbasic bounds with an infinitesimal direction
and solve its exact basis equations. A positive rational step stays below every
bound crossing before the complete source point is checked. Violated basic bounds
can also propose inverse-row Farkas candidates. Only the full source verifier
accepts a refutation; a bad point, failed factorization or singular basis supplies
none. Recovery requests try this basis verification before spending fallback
refinement work. Source admission and query scheduling remain caller-owned.

If endpoint perturbations leave basic bounds violated, requested sparse recovery
uses Bland feasibility pivots with a zero objective. Strict sides shift their
symbolic endpoints inward by an infinitesimal; forward solves compute basic
values and directions, and transpose solves select the smallest movable entering
column or propose a Farkas row. Each changed basis is factored within the same
invocation ledger and residual dense limit before publishing the pivot. A finite
interior step or conflict then requires complete source verification. Exhausting
the pivot or arithmetic allowance preserves an independently checked bound.

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
outcomes decline. Source logical sides are substituted through `s(i) = b(i) - A(i)x`,
and each margin row retains its signed source-row map for Farkas reconstruction.
The first margin solve can spend the remaining pivot allowance while reserving
half the work for checking and correction. Zero-objective continuation keeps an infinitesimal beside
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

`OpenTheoryPipeline.optimizeLinear` runs a complete affine context in the shared
search session. A branch optimum becomes global only after every required Boolean
and integer alternative has a checked bound or refutation; an improving source ray
needs no siblings. When numerical authority declines, the exact descent of the
[linear theories](15-linear-arithmetic.md) continues in the same state and allowance.
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
A continuous leaf that stays undecided is skipped without learning a nogood, and
its incomplete flag prevents exhaustion or optimality publication. Numerical
failure, singularity, work stops, precision declines and cancellation keep distinct
diagnostics.

### Validation

Check original-source rows, bounds, integrality, objectives, rays and conflict
antecedents independently, including scope after pop/remap. A production checker
calling itself is not independent validation.

---

Previous: [16. Nonlinear arithmetic](16-nonlinear-arithmetic.md) · [Contents](README.md) · Next: [18. Exact arithmetic](18-exact-arithmetic.md)
