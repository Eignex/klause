# Exact LP certification

Certification turns a numerical candidate into evidence about the exact active
linear model. Feasible points, objective bounds, infeasibility and unboundedness
require different packages; one cannot substitute for another. The engine first
obtains a candidate, checks the required exact conditions and returns only the
strength of claim established by those checks.

[LpSolve](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpSolve.kt)
owns these numerical checks. A floating optimum,
failed check or resource decline cannot prune, exhaust search or prove an exact
result. MPS tolerance acceptance has its own
[publication contract](../formats/mps.md).

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
unboundedness. Integer certificates expose exact multiplier snapshots; rational certification and
reconstruction retain owned multiplier coordinates with their numerical bound.
Reconstruction charges the retained snapshot allocation. Optional
[original-source capture](../solver/evidence.md) consumes these typed values without
adding model or solver dependencies to the numerical engine. It must justify each
mapped endpoint against original declarations, rows or invocation restrictions.
Missing source origins withhold derivation coverage even when numerical certification
has completed.

Ceiling an objective bound requires a proved integral objective
lattice. Combine packages only when authority, scope and objective units match.
Exact rejection of one candidate is not a refutation of the model. Cross-arm
publication also requires the [portfolio model and objective scope](../solver/portfolio.md#witnesses-and-proof-scope);
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

Zero-objective continuation imports the active strict lower and upper sides. Its
rational tableau keeps one infinitesimal coefficient beside each nonbasic seat
and computed coordinate: strict lower seats use `l + epsilon`, and strict upper
seats use `u - epsilon`. Lexicographic feasibility preserves the matrix equations.
A completed feasible state selects a positive rational epsilon below every
slack crossing and verifies the finite point against the unchanged exact source.
Such an interior point carries no warm-basis claim. Strict conflicts still require
the existing exact ray and source-premise checks. Missing interiors and interrupted
materialization decline; they cannot establish infeasibility. The additional
arithmetic, scalar growth, storage and verification share the continuation's
cumulative allowances, with unchanged dimension and pivot caps. Nonzero objectives
retain closure continuation and their existing strict-refinement policy.

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
[rational normalization evidence](../evidence/numerical-kernels.md#rational-normalization).

A verification attempt makes at most one factor-factory call, charging its
internal reorder/precision restarts. Owner-local exact factors require the same
matrix and ordered basis. Edits still require fresh RHS, seats, costs and proof
checks; reuse does not replenish spending.

## Mixed deduction operations

`LpDeductionCertificate` exposes exact source-objective bounds, reduced costs,
owned multiplier snapshots and movement limits for integral coordinates.
`IntegerCertificate` implements this interface while retaining its concrete
certificate fields and integer certification entry points. Node bounding invokes
mixed operations when exact authority contains continuous declarations; integer
models retain integer certification. The integer-specific lower-bound policy
retains its integral-objective gate.

`nodeDualCertificate` checks a supplied dual vector against an `LpExactState`.
Integral-valued data admits Int128 evaluation even when structural declarations
include real coordinates. Fractional data uses an exact rational Lagrangian.
Every nonzero continuous reduced cost needs its finite source minimizing endpoint;
a free continuous coordinate requires exact cancellation. Probe bounds supply no
premises. Objective scale, external constants and shifted integer origins retain
source units. Real coordinates are never movement targets.

A missing endpoint can admit repair through an active row containing just that
continuous coordinate. Repair preserves logical-column endpoint signs and
rechecks the complete certificate. Continued-fraction reconstruction proposes
another multiplier vector when repair declines. Additional candidates can use a
source objective expression, a blended vector or an equality eliminating its
continuous term. An objective lattice requires exact declared integrality or a
checked defining equality; its row remains part of the support. Additional
candidates share the same allowance and return complete certificates separately.
A finite minimizing endpoint also permits a candidate that cancels its nonzero
continuous reduced cost through an active singleton-continuous row. Logical
endpoint signs must remain supported, and the complete adjusted vector is checked
again. At most eight row adjustments are tried, returning one completed candidate.
The operation requires a primary certificate, independently of an objective lattice
or objective-expression candidate. A charged eligibility scan precedes workspace
allocation. This candidate can expose an integral fixing direction even when the original
certificate already has valid finite endpoint support. An equality-derived
candidate takes precedence; otherwise finite-endpoint cancellation precedes a
blended candidate. The operation returns at most three complete certificates.

`mixedRowBounds` projects active mixed rows onto integral structural coordinates.
Each interval projection cites the exact minimizing or maximizing side of every
other term; it excludes its target's own side. Missing supporting sides decline
that direction. Source origins precede integer rounding, and strict supporting
endpoints exclude equality. Integral data uses checked Int128 arithmetic, with a
rational fallback after overflow or fractional admission. The caller may select
rows incident to specified columns. Integral-only rows allocate no projection terms.

After interval projection, a fixed base logical equality can restrict a target
through the gcd of the other integer coefficients. Finite real intervals and
strict endpoints determine the admissible integer residue interval. At most
sixteen values in the interval-tightened target box are checked. Open supporting
intervals and wider boxes decline. Prefix and suffix gcds and exact residue
products share the arithmetic allowance. These deductions retain both endpoint
directions of other terms and the prior target side bounding the searched prefix
or suffix. Their integer declarations must map to CP integer or Boolean variables.

`LpExplanation` admits a row consequence only with complete row and endpoint
premises. Separately scoped real or logical sides without a supported CP
interpretation withhold it. Certificate identity binds all deductions to their
exact state; pop, bound changes and objective edits prevent reuse across states.
The caller owns applying consequences and retracting them with their scope.

Node bounding uses the strongest completed certificate for pruning and can use
weaker certificates for independent conditional fixings. Exact rational cutoff
gaps and endpoint movements remain in minimized source units. A directly integral
source objective supplies its native lattice for mixed models; pure integer CP
models retain their separate Long objective-range guard. Continuous coordinates
remain outside CP fixing targets. Objective certification and fixing precede row
projection under the shared allowance. Feasibility queries without an objective
operation can still apply row bounds. Root row projection considers all mixed
rows; during search it considers rows incident to narrowed integer endpoints or
Boolean pins. The CP trail retracts deductions with their supporting branches.

Mixed operations share 128-bit scalar admission, 250,000 work units and 16 MiB of
metered allocation. Failed attempts retain their charges. Ordinary cancellation
polls use a 64-call/4096-work stride; work-metered cancellation is immediate and
publication polls directly. Resource decline can retain completed independent
projections; cancellation discards unpublished deductions. `NODE_DEDUCTION`
phase outcomes describe these numerical attempts and their work. Outcome-specific
entries overlap the aggregate deduction phase and must not be summed with it.
`NODE_ROW_FIXING` records actual row consequences separately from objective-relative
fixing. These operations add no LP solve, cut or publication authority.
Source-evidence capture still requires original premises and a complete multiplier
snapshot.

## Continuation rejection details

Exact continuation records the first completed source-point comparison that
rejects its candidate: a strict endpoint equality or an ordinary bound violation,
separating structural columns from logical row sides. Fixed-width overflow drops
that lane's incomplete observation; wide verification supplies the completed
comparison. This first failure does not establish that the remaining bounds hold.

SMT continuation decline keys pair that rejection with strict-refinement outcomes
from the same certification operation. Checked numerical witnesses and conflicts,
declined attempts, completed operations without a strict attempt, missing reports,
observed stops and aborted operations remain distinct. Cached prior metrics cannot
supply a new attempt. These detail counts do not increment continuation invocation
or work totals and execute no additional numerical query or comparison.

Additional categorical detail keys identify the early source-authority or late
unchecked-point continuation call and whether its float result is present. Up to
two refinement records from the same certification operation retain preferred
basis selection, work/allocation availability before and after direct-cost debit,
run-entry availability and the final observed decline stage. Missing stages remain
unreached; an absent refinement report, a stopped operation, an aborted callback
and an unavailable excess record remain distinct. These records read existing
primitive ledgers and guards, execute no numerical query or source comparison and
cannot replenish allowance. Route and entry counts project the original rejections;
they must not be added to invocation, work or rejection totals.

A missing report or stopped operation cannot establish that strict refinement was
unreachable. Rejection details carry no feasible-point, source-model or refutation
authority; the ordinary exact checks and consumer publication policy still decide
which packages can be used.

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

The model-facing `lp.optimization` adapter accepts consumer-owned exact affine
source premises, objective identity/direction and invocation restrictions. It
projects their closure to the existing LP owner, reconstructs nonnegative source
row combinations for bounds, and checks source witnesses and recession directions.
Boolean substitution is checked under complete invocation pins. A limit requires
a checked strict global bound and a closure endpoint with that exact objective;
the feasible base and endpoint have identical Boolean and integer coordinates.
Positive rational mixtures then approach the endpoint while satisfying every strict
source row. Fractional or differing integer coordinates request an exhaustive
integer split rather than inheriting the continuous limit. Missing packages retain
separately checked bounds and witnesses without issuing a global result.

These adapters do not confer original-source semantics on a rounded or tolerance
model. Consumers supply the authoritative source context and its correspondence.
The adapter's bounded numerical owners share one source operation budget through
closure, feasibility and dual reconstruction. Production traversal and result
publication retain their separate admission contracts.

`OpenTheoryPipeline.optimizeLinear` executes a complete exact affine context with
the existing shared search session. Boolean and integer alternatives retain typed
coverage trees. A branch optimum or limit only becomes a global result after all
required alternatives have checked bounds or refutations. An improving source ray
is existential and can finish without covering siblings. An interrupted traversal
retains its checked incumbent and any independently proved global bound; an isolated
branch bound cannot fill that global field.

`LinearOptimizationResult` carries the original scope, typed terminal evidence,
incumbent and global bound separately. Its public derivations retain Boolean
substitution, source combinations and approach coordinates. The approach base can
differ from the best incumbent; its Boolean and integer coordinates stay fixed along
`endpoint + t * (base - endpoint)` for rational `0 < t <= 1`. Accepted callbacks
carry original-direction rational values and never publish closure endpoints.
Callback failure retracts and closes the invocation before propagating.

Ordinary `OpenTheoryRequest` execution binds affine authority to the prepared plan's
original source and original objective direction. Its exact IR binder requires
complete represented affine conjunctions; omitted disequality, guarded equality or
unsupported Boolean capture does not acquire complete authority. A source owner can
supply complete exact rows, objective and original-coordinate semantics through
`withLinearAuthority`; source preparation preserves that binding. Rounded frontend
values do not replace the supplied source data.

Complete affine capture checks declared rows through `LinearSourceSemanticsChecker`
and checks original integer value sets separately. Exact declarative rows need no
finite propagation projection to verify a point. Open integer columns do not acquire
finite domains; finite value sets retain their membership constraints.

`RationalTheoryDescent.linearResult` carries the retained typed completion across
its scalar resumable interface. Attainment and refutation retain source evidence.
A limit or full lattice ray has no scalar completion representation, so its scalar
compatibility result remains `BestFound(Unsupported)` rather than an exhausted
optimality signal. `OpenTheoryOptimum.linearResult` exposes the actual exact terminal,
bound and incumbent separately; its scalar objective remains in minimized units.

A numerical authority decline without a terminal retires that proof attempt and
continues the existing exact feasibility/strict-improvement descent. Both phases
share one `OpenTheorySolveState`, including work, checks, decisions and source-LP
diagnostics. The current slice retains its original work ceiling and deadline.
Fallback witnesses must satisfy the captured source scope and retain exact original
objective values. Exact declarative rows install its Boolean pins and numeric bounds
in the fallback search; an external incumbent can seed further improvements. Independently
checked root bounds survive separately. Feasibility exhaustion after a declined
authority attempt cannot issue a source terminal, and numerical admission limits
are unchanged.

Retained linear snapshots expose only previously checked root bounds and accepted
incumbents. Closing a paused descent retains that partial carrier without running
another theory check or manufacturing a terminal claim. Completed results remain
readable after closure.

`LinearPortfolioTerminal` verifies the retained handle against its worker and
consumer `EngineInvocation`, including actual plan/model/objective references,
original and execution conditions and captured source scope. Witness-only workers
cannot publish a global linear terminal. Missing capture withholds independent
acceptance; foreign runtime bindings reject. The supporting source derivation is
then checked against the consumer-owned original context. The existing portfolio
terminal hook owns serialized publication and retirement; the linear adapter adds
no scheduler or claim issuer. See [portfolio publication](../solver/portfolio.md).

`linearWitnessVerifier` applies the existing original-context witness checker even
when a candidate already carries an IR witness attestation. `linearValue` evaluates
the captured affine objective using authoritative rational coordinates and wide
integers, in original units and direction. Uncaptured objectives and uncertified
real coordinates decline. Neither helper issues a proof certificate; a pool's
minimized comparison value and approximate telemetry remain separate policy.

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
[dispatch evidence](../evidence/numerical-kernels.md#cancellation-polling) supports retaining the
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
