# 21. Simplex and LP certification

The LP engine solves an assembled relaxation and certifies its numerical results.
Its basis state, exact checks and resource limits remain separate from model-facing relaxation policy.

## Floating simplex and basis factors

[SimplexWorkspace](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexWorkspace.kt)
owns fixed-dimension scratch for one engine, with distinct indexed carriers for
entering spikes, pivotal rows, RHS and dual solves so a basis backend can reuse the
form it produced. [SimplexBasisLifecycle](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexBasisLifecycle.kt)
owns headings, statuses and the lazy basis backend, and invalidates them on
retirement even when close fails. `SimplexPricingState` owns Devex weights and
`SimplexObservations` per-solve counters. `PrimalSimplexPhases` owns feasibility
repair and objective iteration through a narrow basis-operations contract, sharing
the workspace and seated basis. `DualSimplexPhase` owns dual iteration, leaving
selection, Devex correction and bound-flipping entering selection. Basis operations
return the retained indexed spike used by incremental values and pivot updates;
dense ratio scans gather that same carrier in row order.
`SimplexArtificialBounds` owns boxed-dual seats
and widening state. `SimplexBasicValues` retains the coupled basic values, model,
scaling view and nonbasic statuses, invalidating them together. Its restoration
adjusts changed nonbasic seats through the retained factors.
Short update vectors use portable Koblas AXPY; larger vectors use the selected backend.
`SimplexSolveControl` owns allowance checks and work charging; every phase and
recovery restart shares one cumulative `SolveProgress`. `RevisedSimplex` coordinates
starts, recovery, cursor lifetime and result publication.

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
and a finite nonnegative gain, or the whole comparison is withheld. These estimates
train no history and supply no pruning or certificate authority. Projection,
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
for dimensions 2–6 with at least 50% density in the selected basis and a pivot policy
without entry or work limits; it produces the
same CSC factors as sparse LU, and a numerical decline retries sparse. Rational
factors reuse orderings but never approximate numeric factors, and only complete
exact rank determination establishes singularity. Koblas supplies the containers
and kernels; see [integration contracts](22-exact-arithmetic.md#numerical-dependencies).

The default backend can export symbolic pivot-order proposals, including after
Forrest-Tomlin updates; replay validates every numerical pivot and keeps the cost
of a failed proposal and its fallback. It can also hand a private owner a copy of
its current numerical basis that shares immutable LU matrices, permutations and
published Forrest-Tomlin slices while adjacency, transforms, scratch and meters stay
private. Updates replace published slices rather than mutate them, so refactoring
or retiring one owner preserves its siblings. Generation checks reject stale,
singular, repairing, closed or cancelled producers. Neither offer carries exact-order
or certificate authority.

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
tolerance, keep exact certification. Real leaf completion reads it only when the caller
also permits tolerance acceptance. Open theories and source LP optimization keep exact
certification; leaf witnesses follow the source tolerance.
An explicitly widened absolute MPS tolerance envelope uses exact deductions and
completion throughout, because its working rows already state the accepted set.

Numerical deductions compute no certificate. `FloatDeductionCertificate` sums the
Lagrangian `L(y)` from the float duals in binary64 and lowers it by that summation's
rounding plus an absolute and relative slack, so float noise never rounds an integral
bound up. A reduced cost beyond its rounding prices and cites its minimizing side; one
within it prices both sides and cites the finite ones. An open side is priced at the tightest
bound a single row implies, preferring rows whose validity a reason can cite, and the
reasons cite that row and each side the implied bound reads; a reduced cost within rounding
on a side no row bounds counts as zero. A caller whose leaves read a region wider than the
rows, as a finite search on a source with a tolerance search does, passes an `LpBoundSlack`
through `LpSolveContext`, and each priced side, a row's slack included, moves by it against
the bound. A relative slack reads a magnitude no smaller than the side's before the
lower-bound shift (a column side plus its origin, a right-hand side plus each shifted term),
so it never falls short of the slack the source bound admits. An open side is derived
from the widened row and column premises, so its perturbation follows their weights
and the division by its coefficient. Input magnitudes stay in its rounding estimate
when perturbations cancel. It supplies node pruning,
objective propagation,
reduced-cost fixing with their reasons, root bounds shared with peers, and shaving; fixing
also tries the objective box (zero multipliers) and half the duals.

A derived row can replace the uniform side displacement with `LpRowSlack`, an immutable
sum of weighted premise bands. Each term reads the larger of absolute slack and relative
slack times its retained magnitude bound; products and sums round outward. Both numerical
bounds and Farkas checks read this displacement, including when deriving an open column
side. The exact row retains the numeric metadata through scopes and layout changes, and
exact capture includes it in the source state key. Zero slack leaves every side unchanged.

Integer tableau cuts combine the bands of their structural lower bounds and input rows.
Shifting a negative integer premise to zero consumes at most one rounding unit for CG,
or twice the divisor plus one for scaled MIR; this also covers coefficient gcd rounding.
An equality's omitted logical column adds its aggregation multiplier's magnitude.
Repeated magnitudes share one accumulated weight. More than 4096 distinct magnitudes,
or an overflowing weight, withholds positive-tolerance numerical pricing while retaining
the nominal cut and its exact interpretation.

`floatFarkasRay` normalises a dual-unbounded ray and accepts it when its binary64 Farkas
margin exceeds the tolerance times the terms' size, pricing open sides at their row-implied
bounds. Each implying row is folded into the ray so its open column cancels, in binary64
and then exactly on the rounded integer ray, slacks first. That ray must pass again against
the box, each column at the side the exact sign of its combination meets, which is the side
its clause cites; otherwise the node is refuted without a learned clause. A refutation slack in
`LpSolveContext` moves every side outward first, in both checks, so the ray refutes only a
model infeasible by more than that slack. It backs infeasibility pruning, leaf refutation,
learned conflicts and presolve root infeasibility. Presolve reads the captured source
slack wherever its row semantics permit tolerance. Mixed row
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
them into [source derivations](16-evidence.md) without adding model
dependencies to the engine. `LpCertificationPolicy` is the immutable acceptance
seam: certifiers always compute and report their observations, and the policy
decides which results may be used. A source check grants no permission to accept a
floating optimum. Finite deductions consult no certifier unless exact certification is
selected ([numerical and exact deductions](#numerical-and-exact-deductions)); open theories always certify.

Model-facing solves state an explicit claim: bound, contradiction, feasible completion or
attained optimum. Bound requests use dual-only reconstruction without primal recovery;
feasible completion stops when a checked point is available, without reconstructing
unused dual candidates or alternative basis seats, retaining the trivial
exact bound of a constant objective. Contradiction requests
may stop on a checked counter-witness and never optimize it. Attainment requires the
same exact witness–bound equality as the result contract. Tolerance acceptance retains
its separate source-defined verdict and supplies no exact attainment claim.

Retained certification owners keep checked results in a single-package store. A
checked bound can answer a bound request but not a completion or attainment request.
Reuse requires the production acceptance policy and the same authority: bounds,
scope, active rows, matrix, objective, origins, strictness and integrality. Packages
keep no floating vectors or ordered basis, and edits and owner retirement invalidate
them.

Exact dual checks validate the complete input before skipping products with exactly
zero multipliers, right-hand sides or selected endpoints; reduced-cost signs,
required bound sides and source support stay part of the complete check in both
fixed-width and arbitrary precision. With an open structural side, every required
side is checked before the objective terms accumulate. Claim-phase costs are
inclusive and overlap the certifier ledger. A successful check alone is not
consumption: separate observations count accepted leaf packages and actual bound or
conflict pruning. Heuristic LP seeding consumes floating coordinates directly and
requests no certificate.
Exact point reconstruction checks every bound. It skips matrix products for zero
primal coordinates and objective products for zero objective coefficients. It
reuses converted coordinates where the source origin is zero. The complete input
is admitted before these shortcuts.
Reconstruction shares a complete immutable coefficient capture across models with
the same owned matrix. Each invocation repeats input, scalar, work and allocation
admission; bounds, premises, right-hand sides and objective values are read from
the current model. A changed matrix has a separate capture.

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

Rational work escalates from `Frac128` to `BigFraction`. Fixed-width arithmetic
reuses zero and unit operands, combines denominator-one numerators directly, and
combines equal denominators without cross multiplication. Every result retains
normalization and the overflow latch. Production exact
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
Target export, identity admission and source capture spend the same remaining caller
allowance as the tableau and verification. Admission has no separate work or allocation
ceiling. Declined attempts and discarded incompatible checkpoints retain their work,
allocation and time charges until the owner is retired. Dimension checks precede basis
export and source capture, and each copy must fit the remaining allocation allowance.
Verification inherits the continuation's coordinate, entry and scalar allowances
and spends its remaining work and allocation. Increasing a retained allowance
preserves earlier costs and complete pivots; it grants no authority to an unchecked
point or ray. Standalone solves and continuous leaf completion accept the same
continuation limits as scoped owners. Retained invocations can extend them without
replacing a compatible exact checkpoint. The solve context also supplies the
direct reconstruction, basis and refinement allowances.

Reconstruction and refinement expose their rational-basis dimension and fill
allowances. The default small basis envelope leaves restricted dual repair
available; callers can fund larger factors without changing the proof checks.
Nested refinement proofs spend the remaining refinement allowance. Dense basis
storage must fit indexed-array sizes and its allocation allowance before assembly.

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

The caller selects the maximum correction rounds. Exhausting rounds can retain a
checked point or bound but cannot establish attainment. Feasibility continuation
runs when a point is missing; an existing feasible point with a missing optimum
certificate does not trigger it, and feasibility continuation is not an optimizer.
Real-cell optimization selects 64 rounds and a 256 MiB modeled allocation envelope;
an undecided cell remains unavailable for an optimality claim.

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
[linear theories](18-linear-arithmetic.md) continues in the same state and allowance.
A checked incumbent can close a numerical node whose scoped bound excludes a better
objective, including a bound retained from a paused query after the incumbent
improves; a global optimum still requires the checked root witness and the complete
composed bound. Fractional branching keeps every eligible exact integer coordinate
in source order. An internal opt-in history of checked immediate-child bound gains
can choose the coordinate instead, and an internal aggregate branch policy, disabled
by default, can split on a primitive integer direction from paired active rows whose
feasible activity lies strictly between adjacent integers. The two aggregate split
restrictions stay in the coverage tree and the original-source checker; neither
policy supplies pruning or proof authority.

Joint repair binds separation, point selection and aggregation to its shared
numerical owner for root preparation and retained neighborhood queries. Before its
first checked incumbent, it can seed a neighborhood with integer pins cited by refuted
root queries, and `source-refutation-center-step=true`, which requires
`source-incumbent-completion=true`, lets a compatible root
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

Finite-leaf tolerance acceptance, for any source with a positive feasibility
tolerance, can justify a tolerance gap with exact basis duals. `exactBasisDuals`
refines the duals on one floating factorization, solving
for a correction from the exact residual `c(B) - B^T y` scaled by a power of two,
which stays dyadic when every basic column and cost is dyadic. Continued fractions
then recover the duals and `B^T y = c(B)` is checked exactly; non-dyadic authority
declines. When the exact duals decline or leave a reduced cost wrong-signed, one
cleanup of at most 100 primal pivots from the same basis, at a tighter pricing
tolerance, can reach an optimum that passes the same dual check. Exact duals do not
turn a floating primal into an exact witness.

### Resources and cancellation

Dimensions, fill, bit growth, deterministic work and memory gate exact work.
Dimension guards run before any allocation, and nothing is cached as eligible.
Exact mechanisms have no default internal clock cap but keep work and memory
limits and caller cancellation; root LP keeps a deadline backstop. Fallback,
retries and nested operations share one invocation allowance, and setup, failed
attempts and cleanup are charged. Cheap exact arithmetic charges operand
word-length squared.

Every preparation, construction, copy, check and retention step in this chapter
debits its containing allowance before it publishes. Capped stores (sparse owners,
factor attempts, guidance, checking, probe and basis copies) reserve storage before
allocation; root layout assembly charges its materialized extent on completion.
Declined, failed and interrupted attempts keep their charges; nothing is refunded,
and a funded step grants no further work. An interrupted step publishes nothing of
its own; a bound taken from its completed parts is published only after its own
complete check, and a later slice charges only its new work. Per-family, per-phase
and per-claim observations are inclusive and overlap the ordinary LP totals.

Reconstruction and refinement poll ordinary tokens every 64 calls or 4096 work,
while resource caps are checked on every charge. Cancelled evidence is discarded.
A completed continuous check that stays undecided is skipped without learning a nogood, and
its incomplete flag prevents exhaustion or optimality publication. Numerical
failure, singularity, work stops, precision declines and cancellation keep distinct
diagnostics.

---

Previous: [20. Linear programming](20-linear-programming.md) · [Contents](README.md) · Next: [22. Exact arithmetic](22-exact-arithmetic.md)
