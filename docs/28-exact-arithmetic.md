# 28. Exact arithmetic kernels

The theories of Part IV decide fragments that linear programming cannot express:
quadratic objectives, polynomial constraints and real functions. This chapter
describes the exact kernels they compute with. Like the LP engine of
[chapter 26](26-lp-architecture.md), each kernel is a numerical mechanism: it
certifies an arithmetic fact, while a source-aware caller decides what that fact
establishes about a formula or objective. None of them reads factors, finite
domains, search state or solver verdicts.

The four kernels share one pattern: an immutable exact value or certificate, a
check that is either completed or declined, and work charged to the caller's
cancellation token. Quadratic certificates come first because they reuse only the
rational simplex primitives. Polynomial and algebraic arithmetic and transcendental
enclosures follow as independent kernels, and exact analytic reals close the
chapter because they combine those two.

## Exact quadratic certificates

`arithmetic.quadratic` checks rational evidence for bounds, attainment, approach
paths and improving rays. It reads only `simplex.exact` and `util`. Its model
denotes `constant + linear^T x + x^T Hessian x / 2` with an exactly symmetric
Hessian; callers own conversion from the
[source objective](03-model.md#quadratic-objective-representation), mappings and
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
[quadratic source pipeline](24-polynomial-real.md#quadratic-strategy-execution)
owns traversal, reconstruction and publication.

## Exact polynomial and algebraic arithmetic

`arithmetic.polynomial` performs exact polynomial operations and represents real
roots without rounding them. It depends only on `simplex.exact` and `util`.
[Polynomial theory](24-polynomial-real.md) owns formula decisions and projection
policy, and [API value ownership](30-api.md#exact-assignment-values) owns public
witness access.

### Exact polynomials and projection

`Polynomial` captures sparse `BigFraction` coefficients and immutable `Monomial`
keys. Lexicographic comparison inspects the largest variable first, so callers remap
IDs to choose an elimination order. Derivatives, substitution and exact division
never convert to floating values.

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

---

Previous: [27. LP certification](27-lp-certification.md) · [Contents](README.md) · Next: [29. Numerical dependencies](29-numerical-dependencies.md)
