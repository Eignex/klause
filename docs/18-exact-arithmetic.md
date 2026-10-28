# 18. Exact arithmetic

Exact kernels certify arithmetic facts for source-aware callers. They own immutable
values, numerical certificates and private workspace; callers own formula truth,
strategy and publication. Kernels read no factors, finite domains or solver verdicts
and charge work to the containing token.

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

Ordered multivariate remainder division retains irreducible terms while reducing
the remaining terms by the supplied divisors. The input differs from its remainder
by a polynomial combination of those divisors. Prepared cursors retain this work
across suspension and expose only the completed remainder.
One caller-owned divisor index serves multiple dividends. Leading coordinates
exclude impossible monomial divisions; a merge of the retained buckets preserves
the supplied divisor order, including constant divisors. Index construction,
lookups and division charge the containing arithmetic token.

Bareiss elimination computes Sylvester determinants with exact division.
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

Evaluation at a rational coordinate clears the coefficient denominators by their
positive least common multiple. Homogeneous integer Horner evaluation retains the
coordinate denominator power, then one normalization returns the full exact
rational image. Preparation and evaluation use the requesting caller's work and
cancellation scope; owned polynomials keep their existing read admission.

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

Sign checks first use the scalars' owned isolating bounds and exact polynomial
enclosures. A strictly positive lower bound or strictly negative upper bound
certifies the corresponding sign; only a singleton zero enclosure certifies zero.
An enclosure containing or touching zero leaves exact image construction and
root comparison available. This applies to complete multivariate points and
lifting candidates with algebraic coefficients. Enclosure work belongs to the
requesting caller, and a private representation refusal retains the scalar route.

Polynomial images retain an owned primitive root and their rational polynomial
expression in that root. Expressions are reduced modulo its defining polynomial,
without requiring irreducibility. Operations between images of the same indexed
root combine those expressions before elimination, and comparisons use their
difference at the root. Image data keeps no arithmetic context; refinement retains
it, and the requesting caller pays for every use. A captured base that exceeds the
caller's representation limits leaves ordinary scalar arithmetic available.

Specialization captures coefficients at earlier exact coordinates and trims
vanished leading coefficients by exact sign. With rational coefficients, lifting
isolates the polynomial directly. When the coefficients depend on one irrational
coordinate, or several known images of the same indexed root, specialization
keeps their rational polynomial expressions in that root. Scalar coefficient images
are materialized only for scalar polynomial evaluation, under the requesting
caller's resource context. A linear polynomial has one root, computed by exact
division in the selected root's field and retained as a shared image. A private
representation refusal leaves joint elimination available. A joint resultant
supplies a rational annihilator for lifting. Sturm variations
over the ordered field of the captured root select its specialized roots from that
annihilator's isolating intervals; one-sided endpoint signs exclude boundary roots
and retain distinct repeated roots. Reducible defining polynomials are restricted
to factors containing the selected root before field division. With unrelated
irrational coordinates, lifting eliminates independent coefficient annihilators
and checks each candidate by exact algebraic evaluation. Every route returns all
and only the specialized polynomial's distinct real roots.

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

`formats.json.ExactRealJson` reads and writes these closed values directly. Rational
leaves are fraction strings; `root` selects a one-based distinct real root using
rational polynomial coefficients in ascending order. Structured `pi`, `exp`, `log`,
`sin`, `cos`, `add`, `multiply` and `power` nodes retain exact expression authority.
Decoding checks root selection and function domains. Display approximations and
isolating intervals are not part of the interchange identity.

`ExactRealEquality` pins a native real column to one of these values. Algebraic
equalities have polynomial encodings with strict isolating bounds. Symbolic pins
select exact function completion over the other factors' complete polynomial
encodings and the original column declarations. Unsupported encodings or objective
operations retain unknown. `SourceStrategy.solveSample` publishes the native typed
witness without parsing symbolic expression text.

Only an original-source check turns these values into a witness: a symbolic value
describes one coordinate, not that a formula holds. `Sample.symbolicReals` captures
the expressions beside rational and algebraic coordinates, and replacing an
approximate coordinate discards their exact metadata. A rational-only objective
evaluator refuses a nonrational symbolic coordinate. `TranscendentalAssignment`
exposes the expressions through `OpenTheoryAssignment`, and published values
outlive the invocation that checked them.

## Numerical dependencies

Klause owns LU, reach, pivot policy, Forrest–Tomlin updates, checked arithmetic and
scratch lifetime. Koblas supplies validated CSC containers, vector/indexed kernels
and sparse primitives; Kumulant supplies online statistics and adaptive bandits.
Neither selects models, routes or result semantics.

The [root build](../build.gradle.kts) pins immutable timestamps for KMP roots and
platform modules. Modules consuming Kumulant declare strict Koblas constraints at
their dependency roots, overriding transitive pins and preserving publication
constraints. Root timestamps alone are insufficient because Gradle metadata redirects
to platform snapshots. Concrete versions belong to the build.

### Consumer contracts

| Consumer | Integration contract |
|---|---|
| `simplex.basis` | Zero-based sorted unique CSC, owned factor copies, explicit stored zeros, retained permutations and fixed-dimension ownership. Workspace scatter fuses index access with checked arithmetic under private unique-touch membership. Dense LU uses windowed GER in owner-local buffers; arithmetic guards and failed-attempt work stay in Klause. |
| `util.SparseSlices` | Validated slice workflows shared by the LP engine and basis: windows, indices and overlap are checked before mutation, and checked dot keeps input order and nonfinite diagnostics. |
| `lp.engine` | Selected-column pricing reuses the current owned CSC's validated support through `dotColumn`; column updates use indexed kernels. Scaling and refinement keep guarded powers of two and exact accumulation. No dense vendor Level 2/3 call is required. |

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
