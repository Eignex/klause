# Exact LP certification

[LpSolve](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolve.kt)
certifies numerical candidates against exact active authority. A floating optimum,
failed check or resource decline cannot prune, exhaust search or prove an exact
result. MPS tolerance acceptance has its own
[publication contract](../formats.md#mps).

## Proof packages

| Package | Establishes |
|---|---|
| Certified objective bound | A bound in the current model's objective units |
| Feasible witness | All active rows and bounds, with source reconstruction and required integrality |
| Attained optimum | A feasible witness and equal exact objective bound |
| Infeasibility | An exact conflict with supported source premises |
| Unboundedness | An exact feasible point and an improving recession direction |
| Indeterminate | No complete required package within the available policy/resources |

A bound does not imply feasibility or attainment. A point does not imply
unboundedness. Ceiling an objective bound requires a proved integral objective
lattice. Combine packages only when authority, scope and objective units match.
Exact rejection of one candidate is not a refutation of the model. Cross-arm
publication also requires the [portfolio model and objective scope](../search.md#portfolio-slices);
its adapter certificate does not replace these numerical checks.

## Certification mechanisms

The dispatcher selects applicable mechanisms under consumer budgets. They are
not a mandatory execution order: continuation can start from a current numerical
basis, and refinement can prefer source-basis verification before corrections.
Denominator, tolerance and stall heuristics schedule work; they do not authorize
proofs or bypass policy withholding.

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
operation from authority. Failed attempts and precision restarts are charged.
An off-status feasible point cannot furnish the claimed warm basis.

Reconstruction converts signed single-word fractions directly between
`BigFraction` and `Frac128`; wider values use both words. Both paths retain the
same bit checks, work charges, allocation allowance and cancellation checks.

`BigFraction` factories preserve reduced numerators and positive denominators.
Zero has denominator one. A unit numerator or denominator is already coprime;
an already-coprime general fraction needs no division after the GCD check.
Finite-double conversion removes trailing powers of two from the significand,
so its odd numerator is coprime to the remaining power-of-two denominator.
These factory paths preserve exact values and normalization while avoiding
redundant arithmetic. Their measured scope is recorded in
[rational normalization evidence](../evidence/rational-normalization.md).

A verification attempt makes at most one factor-factory call, charging its
internal reorder/precision restarts. Owner-local exact factors require the same
matrix and ordered basis. Edits still require fresh RHS, seats, costs and proof
checks; reuse does not replenish spending.

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

including slack reduced costs. Temporary models have separate authority and
revisions. Auxiliary proofs must map back explicitly, source state restores on
every exit, and child owners close.

Feasibility auxiliaries shift the box to contain zero and maximize
`0 <= tau <= 1` with column `-b * tau` and zero RHS. A witness at one proves
feasibility; an exact upper bound below one refutes it. Recession auxiliaries
normalize improvement and still require a separate feasible point.

Floating solves the closure of strict rows. A boundary point is insufficient for
either strict feasibility or refutation. The strict-margin auxiliary adds
`a(i) x + t <= b(i)` to strict upper rows, negates strict lower rows, retains
non-strict rows and maximizes `0 <= t <= 1`. An exact witness at positive `t`
proves strict feasibility. An exact bound `t <= 0` or auxiliary infeasibility
refutes it; other outcomes decline. Strict premises survive elimination of
auxiliary variables. A strict infimum need not be attained.

## Tolerance recovery

MPS finite-leaf tolerance recovery can obtain exact dyadic basis duals to justify
the tolerance gap, then try bounded primal cleanup and exact certification.
Exact duals default to 400 steps, 16384 bits and 1 GiB metered allocation, with
no internal time cap. Non-dyadic basic authority declines this recovery.
These duals do not turn a floating primal into an exact witness.

## Resources and cancellation

Consumer, dimensions, fill, bit growth, deterministic work and memory gate exact
work. Node LP effort uses deterministic work policy. Reconstruction, refinement,
rational factors, exact-dual recovery and exact continuation have no default
internal clock cap, while retaining work/memory limits and caller cancellation.
Source-LP operations are bounded by operation counts and per-operation limits.
Callers can supply time caps; root LP retains a deadline backstop.

Preparation caps take precedence. Fallback, phase changes, retries and nested
operations share an invocation's allowance. Short/full continuation, working
scopes and equivalent owner replacement retain cumulative work, allocation,
pivots, time and admitted-scalar history. Separate identity admission cannot
refund those costs or expose an old proof on failure. Charge setup, failed
attempts, precision restarts and cleanup; counters saturate rather than wrap.

Every certifier accounts for its model passes either through basis/continuation
metrics or its own meter. Cheap exact arithmetic charges operand word-length
squared. Scaling-view construction/refresh/fallback is a preparation phase
outside the solve iteration limit: owner preparation uses its reserved bound,
and the ledger records actual reported work. Work units, kernel visits,
allocations and elapsed time are distinct measurements.

Cancellation adapters preserve deadline metadata and work-meter forwarding.
An LP solve uses its owner token directly when no distinct call token is supplied.
Open linear search keeps the current operation in that owner adapter, avoiding a
second operation poll. A distinct call token remains an additional stop.
An open theory's LP adapter reads the current operation token and retains the
solve token between operations. It polls a distinct enclosing session token too;
when that token is already the operation's parent it does not poll it twice.
Ordered OR composition short-circuits leaf predicates. Reconstruction and
refinement stride ordinary token/clock polling at 64 calls or 4096 work, but
resource caps are checked on every charge, work-metered tokens remain immediate,
and publication checks cancellation directly. Cancelled evidence is discarded.
`shorten(fraction)` only divides remaining time when the token exposes a deadline.
Dynamic adapters keep deadline reads live; ordered composition snapshots the
earliest deadline and first work meter at construction. Adjacent library deadline
tokens backed by the monotonic clock use one earliest deadline check. Predicates
between clocks, dynamic adapters and other time sources retain their ordered
checks. The
[dispatch evidence](../evidence/cancellation-polling.md) supports retaining the
current polling contract without a dispatch performance claim.

Continuous leaf solves use solve-wide cancellation rather than a portfolio node
slice. An undecided leaf can be skipped chronologically without learning a
nogood. Its retained incomplete flag prevents search exhaustion or optimality
publication. Independently completed partial proofs still obey authority, policy
and publication cancellation checks. Numerical failure, exact singularity,
work/pivot stops, dimension/precision declines and cancellation retain distinct
diagnostics.

## Validation

Check original-source rows, bounds, integrality, objectives, rays and conflict
antecedents independently, including scope after pop/remap. A production checker
calling itself is not independent validation. Kernel replay isolates numerical
mechanisms; consumer runs establish usefulness and total cost. No general
performance or completeness claim follows from a retained basis, a smaller
clause, implementation coverage or a single workload.
