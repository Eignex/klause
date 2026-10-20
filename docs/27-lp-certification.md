# 27. LP certification

The [previous chapter](26-lp-architecture.md) followed a linear model from assembly
to a floating solve. This chapter covers the step that gives that solve any
authority: certification turns a numerical candidate into evidence about the exact
active linear model. Feasible points, objective bounds, infeasibility and
unboundedness require different packages; one cannot substitute for another.

The chapter first lists those packages, then the mechanisms that produce them:
safe objective bounds, exact reconstruction and refinement, Farkas and recession
checks, and strict inequalities. The later sections cover deductions for mixed
models, the model-facing optimization adapter, tolerance recovery and the
resource rules every mechanism shares.
[LpSolve](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolve.kt)
owns these checks. A floating optimum, failed check or resource decline cannot
prune, exhaust search or prove an exact result.

## Proof packages

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
lattice. Combine packages only when authority, scope and objective units match.
Exact rejection of one candidate is not a refutation of the model.

Certificates expose exact multiplier snapshots, so optional
[original-source capture](17-evidence.md) can consume them without adding model
or solver dependencies to the engine. That capture must justify each mapped
endpoint against original declarations, rows or invocation restrictions; missing
source origins withhold derivation coverage even after numerical certification.
Cross-arm publication also requires the
[portfolio model and objective scope](16.1-portfolio-witnesses.md).

Finite consumers keep source-point acceptance separate from permission to accept
a floating optimum. A zero-tolerance source still checks every candidate under its
captured policy, and a source check or restrictive filter grants no floating
optimum permission. `LpCertificationPolicy` is the immutable acceptance seam:
certifiers always compute and report their observations, and the policy decides
which successful results may be used.

## Safe objective bounds

The cheapest certificate is a bound from the floating duals alone. Slack-form rows
are equalities, so the Lagrangian

```text
L(y) = y^T b + sum(j) min over [l(j), u(j)] of d(j) z(j),   d = c - M^T y
```

bounds the minimized objective for every multiplier vector `y`; rounding the duals
only weakens it (Neumaier and Shcherbina, 2004). `safeObjectiveLowerBound`
evaluates it exactly from the authoritative Long or binary view and projects the
result downward to a finite Double. A negative reduced cost needs a finite upper
side, and a missing lower side requires a nonpositive reduced cost.

The integer variant rounds the duals to integer multipliers at a capped
power-of-two scale and accumulates in Int128. Any nonfinite dual, out-of-range
multiplier or overflow declines; the caller keeps the node. An integral objective
lattice lets the bound be ceiled. Continuous models can use a scaled-integer
rationalization whose scale divides back out exactly, but only for a verified
integer source objective lattice. Node bounding takes the tighter of the accepted
safe and integer bounds.

Variable bounds use the same argument with a single-column objective. A bound that
only reached the private probe magnitude standing in for an infinite side reports
the variable unbounded rather than a spurious finite bound.

## Certification mechanisms

When a safe bound is not enough, the dispatcher selects applicable mechanisms under
consumer budgets. They are not a mandatory order: continuation can start from a
current numerical basis, and refinement can prefer source-basis verification before
corrections. Denominator, tolerance and stall heuristics schedule work; they do not
authorize proofs or bypass policy withholding.

| Mechanism | Acceptance checks |
|---|---|
| Integer Lagrangian | Round dual multipliers at a power-of-two scale and aggregate in Int128. Check finite exact box support, absent-side signs and logical columns. Can certify bounds, fixings or Farkas without a basis. |
| Rational reconstruction | Continued fractions, geometric retries and whole-vector common denominators. Guard zero/nonfinite values and check equations, bounds, signs and complementarity exactly. |
| Residual refinement | Solve scaled rational corrections through the same floating engine and retained basis; accumulate exactly and reconstruct. Budgeted basis verification and auxiliary ray solves can complete the package. |
| Rational basis verification | Exact FTRAN gives basics; BTRAN gives duals/Farkas candidates. Verify the complete proof. Exact singularity requests repair; resource exhaustion declines. |
| Exact continuation | Import the numerical basis pivot by pivot, checking conflicts and repairing unseatable basics with logicals. Short then full Bland feasibility effort uses one cumulative state. A missing basis declines. |

Integer aggregation uses Int128. Rational reconstruction, factors and continuation
escalate from `Frac128` to `BigFraction`; residual refinement uses `BigFraction`.
Oversized authority starts wide, and fixed-width overflow restarts the whole
operation from authority. Failed attempts and precision restarts are charged. A
verification attempt makes at most one factor-factory call; owner-local exact
factors require the same matrix and ordered basis, and edits still require fresh
right-hand sides, seats, costs and proof checks.

An off-status feasible point cannot furnish a claimed warm basis. Private solve
contexts can request bounded sparse point repair, off by default; feasibility from
that repair does not establish objective attainment.

## Farkas and recession checks

For `M = [A | I]`, a Farkas contradiction requires

```text
rho^T b > sup { rho^T M x : l <= x <= u }
```

or the reversed equivalent. Free columns require zero aggregate coefficient;
one-sided columns require a finite supported endpoint. Equality is contradictory
when a nonzero selected contribution is strict. An exact transpose solve alone
is insufficient. Bounds and scoped rows contribute their source premises.

Unboundedness requires a feasible point and direction `r` with `M r = 0` and
`c^T r < 0` for minimization. Recession signs are zero for doubly bounded columns,
nonnegative for lower-only, nonpositive for upper-only and unrestricted for free
columns. Mixed-integer publication also requires an integer-feasible point and
an integrality-preserving ray. Finite optimization lifts the pair to source
coordinates and rechecks it against factors before publication.

Bounded counter-proofs retain feasible witnesses that refute contradictory
infeasibility candidates and objective bounds that refute incompatible ray claims.
They are keyed to source/state/policy and invalidated on relevant edits. A retained
bound that loses authority cannot block a legitimate unboundedness package.

## Refinement and strict inequalities

For exact candidates `x,y`, refinement constructs

```text
M dx = s(p) * (b - M x)
s(p) * (l - x) <= dx <= s(p) * (u - x)
c(work) = s(d) * (c - M^T y)
x += dx / s(p)
y += dy / s(d)
```

including slack reduced costs, then reconstructs exact values from the accumulated
corrections. Temporary models have separate authority and revisions; auxiliary
proofs map back explicitly, source state restores on every exit, and child owners
close.

Feasibility auxiliaries shift the box to contain zero and maximize `0 <= tau <= 1`
with column `-b * tau` and zero right-hand side. A witness at one proves
feasibility; an exact upper bound below one refutes it. Recession auxiliaries
normalize improvement and still require a separate feasible point.

Floating solves the closure of strict rows, so a boundary point is insufficient for
either strict feasibility or refutation. The strict-margin auxiliary adds
`a(i) x + t <= b(i)` to strict upper rows, negates strict lower rows, retains
non-strict rows and maximizes `0 <= t <= 1`. An exact witness at positive `t`
proves strict feasibility; an exact bound `t <= 0` or auxiliary infeasibility
refutes it; other outcomes decline. A strict infimum need not be attained.

Zero-objective continuation handles strict sides directly. Its rational tableau
keeps one infinitesimal coefficient beside each strict seat (`l + epsilon`,
`u - epsilon`), and lexicographic feasibility preserves the matrix equations. A
completed state selects a positive rational epsilon below every slack crossing and
verifies the finite point against the unchanged exact source. Such an interior
point carries no warm-basis claim, and a missing interior cannot establish
infeasibility.

## Mixed deduction operations

Models with continuous columns cannot use the integer certificate's lattice
reasoning directly. `LpDeductionCertificate` generalizes it: exact
source-objective bounds, reduced costs, owned multiplier snapshots and movement
limits for integral coordinates. Integer models keep `IntegerCertificate` and its
integral-objective gate.

`nodeDualCertificate` checks a dual vector against an `LpExactState`, in Int128
for integral data and with an exact rational Lagrangian otherwise. Every nonzero
continuous reduced cost needs its finite minimizing endpoint, and a free continuous
coordinate requires exact cancellation. When an endpoint is missing, bounded repair
can cancel the reduced cost through an active row containing only that continuous
coordinate, and continued-fraction reconstruction or a source-objective, blended or
equality-eliminating vector can propose alternatives. Every candidate is checked
completely; at most three are returned, and real coordinates are never movement
targets.

`mixedRowBounds` projects active mixed rows onto integral structural coordinates.
Each interval projection cites the exact supporting side of every other term and
declines when one is missing. Rows with one continuous column can pair with another
singleton row on that column so a weighted sum cancels it; dense rows can
substitute active singleton-continuous equalities. A fixed equality can then
restrict a target through the gcd of the other integer coefficients. Each
completed projection retains every defining row and logical-column premise.

`LpExplanation` admits row consequences and objective explanations only with
complete row and endpoint premises. Certificate identity binds deductions to their
exact state; pop, bound changes and objective edits prevent reuse. The caller
applies consequences and retracts them with their scope. Node bounding prunes with
the strongest completed certificate and can use weaker ones for independent
conditional fixings. When no mixed certificate completes, an accepted safe
objective bound still supports continuous-objective pruning in minimized source
units. These operations add no LP solve, cut or publication authority, and share
one allowance: 128-bit scalar admission, 250,000 work units and 16 MiB of metered
allocation.

## Model-facing linear optimization

The engine certifies an `LpModel`; open linear models need the same guarantees in
original-source terms. The `lp.optimization` adapter accepts consumer-owned exact
affine source premises, objective identity and direction, and invocation
restrictions. It projects their closure to the LP owner, reconstructs nonnegative
source row combinations for bounds, and checks source witnesses and recession
directions. A strict infimum is reported as a limit: a checked strict global bound,
a closure endpoint with that objective, and a feasible base with identical Boolean
and integer coordinates, approached along `endpoint + t * (base - endpoint)` for
rational `0 < t <= 1`.

`OpenTheoryPipeline.optimizeLinear` runs a complete affine context in the shared
search session. A branch optimum becomes global only after every required Boolean
and integer alternative has a checked bound or refutation; an improving source ray
is existential and needs no siblings. `LinearOptimizationResult` keeps the original
scope, typed terminal evidence, incumbent and global bound separately.
`NumericalLinearRequest.solve` serves MPS through the same numerical authority
without exact-theory descent.

When numerical authority declines without a terminal, the exact
feasibility/strict-improvement descent of the
[linear theories](20-linear-real.md) continues in the same `OpenTheorySolveState`,
with the same work ceiling and deadline. Portfolio publication goes through
`LinearPortfolioTerminal`, which verifies the handle against its worker and
consumer invocation before the [portfolio](16-portfolio.md) publishes it. These
adapters never confer original-source semantics on a rounded or tolerance model.

## Tolerance recovery

MPS finite-leaf tolerance recovery can justify a tolerance gap with exact basis
duals. `exactBasisDuals` refines the duals on one floating factorization of the
basis: each step solves for a correction from the exact residual `c(B) - B^T y`
scaled by a power of two, which stays in dyadic integers when every basic column
and cost is dyadic. Continued fractions then recover duals with a shared
denominator, and `B^T y = c(B)` is checked exactly. Non-dyadic basic authority
declines. Exact duals default to 400 steps, 16384 bits and 1 GiB of metered
allocation, with no internal time cap. They do not turn a floating primal into an
exact witness.

## Resources and cancellation

Consumer, dimensions, fill, bit growth, deterministic work and memory gate exact
work. Reconstruction, refinement, rational factors, exact duals and continuation
have no default internal clock cap but keep work and memory limits and caller
cancellation; callers can supply time caps, and root LP keeps a deadline backstop.
Dimension guards run before any authority, matrix or factor setup and decline
without allocating; each call checks its own dimensions, so no eligibility verdict
is cached.

Preparation caps take precedence. Fallback, phase changes, retries and nested
operations share an invocation's allowance, and replacing an owner or continuing
from a short to a full continuation keeps cumulative work, allocation, pivots and
time. Setup, failed attempts, precision restarts and cleanup are charged; counters
saturate rather than wrap. Cheap exact arithmetic charges operand word-length
squared.

Cancellation adapters preserve deadline metadata and work-meter forwarding.
Reconstruction and refinement poll ordinary tokens every 64 calls or 4096 work,
but resource caps are checked on every charge, work-metered tokens stop
immediately, and publication checks cancellation directly. Cancelled evidence is
discarded. A continuous leaf that stays undecided can be skipped without learning a
nogood; its incomplete flag prevents exhaustion or optimality publication.
Numerical failure, exact singularity, work or pivot stops, dimension and precision
declines and cancellation keep distinct diagnostics. Continuation rejection details
record the first source comparison that rejected a candidate; they carry no
feasibility or refutation authority.

## Validation

Check original-source rows, bounds, integrality, objectives, rays and conflict
antecedents independently, including scope after pop/remap. A production checker
calling itself is not independent validation. No completeness claim follows from
a retained basis, a smaller clause or implementation coverage.

---

Previous: [26. LP architecture](26-lp-architecture.md) · [Contents](README.md) · Next: [28. Exact arithmetic kernels](28-exact-arithmetic.md)
