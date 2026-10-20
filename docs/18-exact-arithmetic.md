# 18. Exact arithmetic

The theories of Part IV decide fragments that linear programming cannot express:
quadratic objectives, polynomial constraints and real functions. This chapter
describes the exact kernels they compute with and the external numerical libraries
underneath them. Like the [LP engine](17-linear-programming.md), each kernel is a numerical mechanism: it
certifies an arithmetic fact, while a source-aware caller decides what that fact
establishes about a formula or objective. None of them reads factors, finite
domains, search state or solver verdicts.

The four kernels share one pattern: an immutable exact value or certificate, a
check that is either completed or declined, and work charged to the caller's
cancellation token. Quadratic certificates come first because they reuse only the
rational simplex primitives. Polynomial and algebraic arithmetic and transcendental
enclosures follow as independent kernels, and exact analytic reals combine those
two. The chapter closes with the numerical dependencies the LP engine builds on.

## Exact quadratic certificates

`arithmetic.quadratic` checks rational evidence for bounds, attainment, approach
paths and improving rays. It reads only `simplex.exact` and `util`. Its model
denotes `constant + linear^T x + x^T Hessian x / 2` with an exactly symmetric
Hessian; callers own conversion from the
[source objective](02-model.md#quadratic-objective-representation), mappings and
direction.

### Candidate generation

`QuadraticCandidates` uses floating penalty-gradient iterations only to propose
active rows. Selected rows and all equality rows form an exact rational KKT system;
rank-revealing elimination rejects inconsistent systems and fixes free coordinates
to zero in singular ones. A proposed anchor still requires the checks below, and
its work is not refunded when certification fails.

### Certificate checks

Exact Schur elimination checks positive semidefiniteness, including singular
matrices. A bound certificate supplies a rational anchor and row multipliers
(nonnegative for inequalities): exact stationarity plus semidefiniteness give a
global lower bound even at an infeasible anchor, and feasibility with equal
objective gives an attained optimum.

An infimum certificate needs a feasible endpoint and a closure-feasible anchor
defining a straight approach path, objective convergence to the bound, and either
positive strict-row dual weight or a positive-definite Hessian; a strict-boundary
anchor alone proves nothing. Unboundedness needs an exactly feasible base and a
row-preserving direction with negative curvature, or zero curvature and negative
slope; this also admits rays in nonconvex models, where stationarity cannot certify
a bound.

### State and resources

Each check polls cancellation and charges its containing control; work and matrix
limits interrupt without evidence. The
[quadratic source pipeline](16-nonlinear-arithmetic.md#quadratic-strategy-execution)
owns traversal, reconstruction and publication.

## Exact polynomial and algebraic arithmetic

`arithmetic.polynomial` performs exact polynomial operations and represents real
roots without rounding them. It depends only on `simplex.exact` and `util`.
[Polynomial theory](16-nonlinear-arithmetic.md#polynomial-real-arithmetic) owns formula decisions and projection
policy, and [API value ownership](19-api.md#exact-assignment-values) owns public
witness access.

### Exact polynomials and projection

`Polynomial` captures sparse `BigFraction` coefficients and immutable `Monomial`
keys. Lexicographic comparison inspects the largest variable first, so callers remap
IDs to choose an elimination order. Derivatives, substitution and exact division
never convert to floating values.

Bareiss elimination computes Sylvester determinants with exact division.
Nonconstant exact division keeps a private sparse remainder and subtracts each
shifted divisor in place; only a completed quotient becomes an immutable polynomial.
Resultants handle zero, shared-factor and constant cases; principal subresultant
coefficients use the truncated Sylvester matrices, with a direct formula when both
inputs are affine in the eliminated coordinate. Prepared cursors let a caller
suspend a long elimination at its own checkpoints and charge the same work as the
eager path.

### Rational roots and scalar identity

`RationalPolynomial` captures ascending rational coefficients. Euclidean gcd,
square-free decomposition and Sturm sequences isolate all distinct real roots;
nonpoint isolating intervals contain exactly one root, and point intervals certify
a rational root.

`AlgebraicReal` is an owned read-only scalar: an ascending rational coefficient
list, a one-based index among the distinct real roots, and exact isolating bounds.
Defining polynomials need not be minimal. Comparison uses exact gcd and
common-root tests and interval separation, and sign evaluation refines intervals
until a nonzero sign is certified. Equal values with different defining
polynomials compare equal; object identity is not numerical equality.

### Algebraic arithmetic and lifting

Addition and multiplication eliminate the operands' defining polynomials by
resultants, and reciprocal reverses coefficients after an exact nonzero check;
certified enclosures then select the unique result root. No fixed precision decides
equality or order. Rational sector samples lie strictly between ordered algebraic
boundaries. Evaluation owners cache root counts, coordinate powers and line images
for one caller, charging lookups to the same token; published values keep no cache.

Specialization evaluates coefficients at earlier exact coordinates and trims
vanished leading coefficients by exact sign. With rational coefficients, lifting
isolates the polynomial directly; otherwise it eliminates algebraic coefficients to
a rational annihilator, a superset of the roots, and checks every candidate against
the specialized polynomial. The retained roots are all and only its distinct real
roots.

### Invocation resources

Every operation polls the caller's `Cancellation` and charges deterministic work;
`PolynomialInterrupted` publishes no partial result. `PolynomialLimits` defaults to
65,536 dense coefficients and 262,144 elimination matrix cells, and dimensions are
checked before allocation. No cap truncates a polynomial or supplies a refutation;
callers translate declines into an unknown resource outcome.

## Certified transcendental enclosures

`arithmetic.transcendental` supplies rational enclosures for real exp, natural log,
sin and cos, depending only on `simplex.exact` and `util`. An enclosure can
establish separation or supply a relaxation; it never certifies a source equality
from overlapping endpoints.

### Arithmetic and domain

`RationalEnclosure` has inclusive exact rational endpoints, and interval arithmetic
keeps every value of its inputs. Reciprocal rejects intervals containing zero, and
log requires a strictly positive lower endpoint. The
[exponential](https://dlmf.nist.gov/4.2.E19), [logarithmic](https://dlmf.nist.gov/4.6.E4),
[sine and cosine](https://dlmf.nist.gov/4.19) and
[arctangent](https://dlmf.nist.gov/4.24.E3) series are evaluated in rational
arithmetic; no floating constant or libm call has enclosure authority.

### Reduction and remainders

Exp halves a nonnegative argument to `0 <= r <= 1/2` and bounds the tail
geometrically by the first omitted term over `1-r/(n+2)`; squaring reverses the
halving, and negative arguments use the reciprocal. Log writes `x = 2^k*r` with
`1 <= r < 2` and sums the series in `z=(r-1)/(r+1)`, whose positive tail is at most
`2*z^(2n+1)/((2n+1)*(1-z^2))`. Monotonicity combines endpoint enclosures for
interval arguments.

Pi is enclosed as `4*(atan(1/2)+atan(1/3))`, each alternating sum lying between
consecutive partial sums. Period reduction subtracts an exact integer multiple of
the enclosed `2*pi` and keeps its uncertainty in the reduced interval. Sine and
cosine Taylor polynomials through degree `2*n` have error at most
`abs(center)^(2*n+1)/(2*n+1)!`; results are intersected with `[-1,1]`, and wide
uncertainty returns that whole range.

### Affine relaxations

Exp convexity gives a secant upper bound and a rational line below the tangent at
any anchor; log concavity gives a chord lower bound and a tangent upper line. Each
relaxation holds only on its domain.

### Refinement and resources

The caller chooses the series term count; refinement can stop without deciding a
relation. Each arithmetic owner keeps cumulative work, polls cancellation and
declines without a partial result at its work or rational bit cap. Consumers
translate incomplete work into unknown.

## Exact analytic real coordinates

`arithmetic.real` keeps authoritative real expressions beyond algebraic scalars by
combining the [polynomial kernel](#exact-polynomial-and-algebraic-arithmetic) with
[transcendental enclosures](#certified-transcendental-enclosures). It owns values
and operations; the source service owns formula truth and publication.

### Values and identity

`ExactRealValue` captures an immutable `ExactRealExpression`: algebraic leaves, pi,
exp/log/sin/cos applications, sums, products and integer powers. Log requires a
certified positive argument. Recognized identities include exp/log inverses, zero
special values, rational multiples of pi, symbolic cancellation and
`sin(x)^2+cos(x)^2`. Comparison uses exact algebraic checks and identities first,
then strict separation of enclosures; overlapping enclosures give no verdict.

### Source and publication boundary

Only an original-source check turns these values into a witness: a symbolic value
describes one coordinate, not that a formula holds. `Sample.symbolicReals` captures
the expressions beside rational and algebraic coordinates, and replacing an
approximate coordinate discards their exact metadata. A rational-only objective
evaluator refuses a nonrational symbolic coordinate. `TranscendentalAssignment`
exposes the expressions through `OpenTheoryAssignment`, and published values
outlive the invocation that checked them.

## Numerical dependencies

External libraries supply data containers and kernels within Klause's owned
[LP engine](17-linear-programming.md). They do not select source models, solving
routes or result semantics. This section covers what comes from each dependency,
how it is pinned, and the consumer and runtime obligations at that boundary.

Klause owns sparse and small dense LU, reach, pivot policy, Forrest–Tomlin updates, checked arithmetic and scratch
lifetime. Koblas supplies validated CSC containers, indexed/vector Level 1 kernels and generic sparse
primitives. There is one floating simplex with exact certification; vendor selection does not change
numerical authority or create another solver.

### Dependency provenance

#### Koblas

The [build](../klause/build.gradle.kts) pins immutable timestamps for both the KMP root and every platform module. A root timestamp
alone is insufficient because its Gradle metadata redirects to mutable platform snapshots. Strict
platform constraints propagate into klause publications.

The pins consume Koblas revision `74cf0237084597cce8eeef47ddd9c3613c30cf8e`,
published by its [snapshot workflow](https://github.com/Eignex/koblas/actions/runs/38446077479).
Koblas coordinates use group `com.eignex`:

| Module | Version | Binary SHA-256 |
|---|---|---|
| koblas | `0.1.1-20261013.182449-263` | `4bb78bbe9448b937e2bf88601e22c9414c5d8000b5ec75024be2d9c8444161b5` |
| koblas-jvm | `0.1.1-20261013.182449-266` | `36c06138be124ab6df809e7f9dde8f5dad4efd890585fc1350724b26662bd57d` |
| koblas-linuxx64 | `0.1.1-20261013.182449-262` | `27020e001be9346a675c0246b053e10f71d68e2577a139c3d99715d94b3c6492` |
| koblas-linuxarm64 | `0.1.1-20261013.182449-263` | `26224afb64a272b51e723bacca20e1b5e01b20473cb42d3f6b66c454eabd1004` |
| koblas-macosarm64 | `0.1.1-20261013.182206-286` | `8534ca098a41e671db582d14f664770895ec9b84b845d85f72ce80e12cadc36f` |

The build does not consult Maven Local. For source integration, use Gradle's supported composite
substitution with an isolated checkout at the revision under test:

```sh
./gradlew --include-build /path/to/isolated/koblas :klause:jvmTest --max-workers=2
```

Dependency updates must update every platform pin together.

#### Kumulant

Kumulant supplies online statistics and adaptive bandits. Its contextual-bandit
features use Koblas vector types, so Klause strictly pins the Koblas coordinates
above for both direct and transitive consumers.

The pins consume Kumulant revision `9df19df5c48e2cce2caf0a612e51e39430903352`,
published by its [snapshot workflow](https://github.com/Eignex/kumulant/actions/runs/38456722493).
Kumulant coordinates use group `com.eignex` and version prefix `0.3.4-20261013.194059-`:

| Module | Build | Binary SHA-256 |
|---|---:|---|
| kumulant | 67 | `81bf47d09d6135b0857fc49510d17fbeac5dd958c5932753656d021bdfb78bae` |
| kumulant-jvm | 67 | `0b8e8e18088fca26f6045879083cbc11bd1a571dffe36b8cf0a90a1f1fab4c23` |
| kumulant-linuxx64 | 67 | `844f4b33057686b7ab47898757ae053f3aa377a508bc93cd0c7fbfc27ffa3a1a` |
| kumulant-linuxarm64 | 67 | `a705527909d08e53a8b1a05f1051da970980a6183ed2aea315ba9cb8fa6f5416` |
| kumulant-macosarm64 | 66 | `f0ce4c7f2f76c8aae200f8b443d4c4d2ad211733ebc540e7854916425ba1678c` |

### Consumer contracts

| Consumer | Integration contract |
|---|---|
| `simplex.basis` | Zero-based sorted unique CSC, owned factor copies, explicit stored zeros, retained permutations and fixed-dimension ownership. Dense LU uses windowed GER in owner-local buffers; arithmetic guards and failed-attempt work stay in Klause. |
| `util.SparseSlices` | Validated slice workflows shared by the LP engine and basis: windows, indices and overlap are checked before mutation, and checked dot keeps input order and nonfinite diagnostics. |
| `lp.engine` | Pricing and column updates use indexed kernels. Scaling and refinement keep guarded powers of two and exact accumulation. No dense vendor Level 2/3 call is required. |

Unchecked reductions may reassociate or fuse arithmetic according to the selected
implementation, so Klause does not use vendor `iamax` to choose pivots. Exact proof
checks, work and cancellation budgets remain solver responsibilities. The `GerWindow`
route always uses portable panel kernels, with Klause's overflow, underflow and
finite checks around each call.

### Runtime support

JVM launchers and Gradle execution tasks enable `jdk.incubator.vector` and
`--enable-native-access=ALL-UNNAMED`. `-Pkoblas.noSimd=true` omits the module for portable-kernel
verification. Embedded JVM callers supply these runtime options themselves.

Koblas selects one platform composition with portable kernels; eligible dense calls
can use an installed host library (oneMKL or AOCL on Linux x64, ArmPL or OpenBLAS on
Linux arm64, Accelerate on macOS), with LP64 integers and one thread per call.
`koblas.vendor` exposes the resolved library. Klause bundles no vendor payload.

---

Previous: [17. Linear programming](17-linear-programming.md) · [Contents](README.md) · Next: [19. Library API](19-api.md)
