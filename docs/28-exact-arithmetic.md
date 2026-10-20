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

The quadratic kernel checks rational evidence for bounds, attainment, approach
paths and improving rays. It is a numerical mechanism, independent of source
models and search policy. A checked kernel certificate still needs a source-aware
adapter before it can justify a published solver result.

[Model representation](03-model.md#quadratic-objective-representation) owns exact
source terms; [current coverage](04-input-semantics.md#solving-coverage) distinguishes
these primitives from an exposed quadratic optimization route.

### Model and source boundary

`arithmetic.quadratic` owns rational quadratic models and exact certificate checks
in the existing `klause` module. It reads only `simplex.exact` and neutral `util`
controls, independently of factors, source lowering and search. It preserves the
closed `lp.engine` dependency list. Its model denotes
`constant + linear^T x + x^T Hessian x / 2` with an exactly symmetric Hessian.
The source [objective representation](03-model.md#quadratic-objective-representation)
retains literal cross coefficients; callers own Hessian conversion, source
mappings, objective direction and invocation assumptions.

### Candidate generation

`QuadraticCandidates` uses floating penalty-gradient iterations only to propose
active rows. Nonfinite arithmetic declines the proposal. Selected rows and all
source equality rows form an exact rational KKT system; rank-revealing elimination
rejects inconsistent systems and chooses zero free coordinates in consistent
singular systems. The reconstructed anchor and multipliers still require the
independent certificate checks below. A numerical active-set selection or a
rational stationary candidate alone supplies no bound or optimum. Both proposal
and reconstruction retain the containing numerical control; their work is not
refunded when a candidate fails certification.

### Certificate checks

Exact Schur elimination checks positive semidefiniteness, including singular
matrices: a zero diagonal with a nonzero remaining row rejects convexity. A bound
certificate supplies a rational anchor and row multipliers. Inequality multipliers
are nonnegative; equality multipliers may have either sign. Exact Lagrangian
stationarity and positive semidefiniteness establish a global lower bound even
when the anchor is infeasible. Feasibility and equality of the objective with the
bound additionally establish an attained optimum.

A strict-boundary anchor alone does not establish nonattainment. An infimum
certificate requires a feasible endpoint and a closure-feasible anchor defining
a feasible straight approach path, objective convergence to the bound, and either
positive strict-row dual weight or a positive-definite Hessian excluding another
feasible minimizer. Other nonattainment cases decline certification. Unboundedness
requires an exactly feasible base and a recession direction preserving every row,
with negative quadratic curvature or zero curvature and negative objective slope.
This check also admits a valid negative-curvature ray in a nonconvex model;
nonconvex stationarity cannot certify a global bound.

### State and resources

Each check polls cancellation and charges its containing native control. Work and
matrix limits interrupt without evidence. Certificates retain their captured
model; numerical evidence cannot establish a source result until its adapter
checks source semantics and scope. The [quadratic source pipeline](24-polynomial-real.md#quadratic-strategy-execution)
uses these mechanisms for admitted subproblems and owns optimization traversal,
source reconstruction and result publication.

## Exact polynomial and algebraic arithmetic

This kernel performs exact polynomial operations and represents real roots without
rounding them to floating values. [Polynomial theory](24-polynomial-real.md) uses
those operations to construct and check nonlinear real assignments and owns formula
decisions; [API value ownership](30-api.md#exact-assignment-values) owns public
witness access.

### Role and boundaries

`arithmetic.polynomial` owns mathematical mechanisms in the existing `klause`
module. It depends only on itself, `simplex.exact` and `util`. Source syntax,
formula truth, projection-family selection, traversal and result publication belong
to callers. Kernel completion certifies an arithmetic operation, not a solver
verdict. The package never allocates finite CP domains or reads solver state.

### Exact polynomials and projection

`Polynomial` captures sparse `BigFraction` coefficients and immutable `Monomial`
keys. Terms combine exactly; zero coefficients disappear. Variable IDs are
nonnegative integers, and lexicographic monomial comparison inspects the largest
variable first, so callers remap IDs to choose an elimination order. Derivatives,
substitution and exact division retain source coefficient authority without
floating conversion.

Bareiss elimination computes polynomial-ring Sylvester determinants with exact
polynomial division and row swaps. Resultants include zero/shared-factor and
constant cases. Principal subresultant coefficients use the truncated Sylvester
matrices for indices 0 through one less than the smaller degree; index zero is the
resultant. For two inputs affine in the eliminated coordinate, the sole principal
subresultant is evaluated directly as a difference of coefficient products, without
a dense matrix. Policy chooses which polynomials, reducta and coefficient families
to project.

Prepared subresultant cursors let a caller suspend a long elimination at its own
checkpoints. They retain private matrices and partial products, charge the same
native operations as the eager path and expose only a completed polynomial or
subresultant family. Native interruption and representation failures remain
terminal; callers own cursor disposal and the meaning of suspension. Optional
context-local observers record operands for diagnostics; they are disabled by
default and do not select operations or change limits.

### Rational roots and scalar identity

`RationalPolynomial` captures ascending rational coefficients and trims trailing
zeros. Euclidean gcd, square-free decomposition and Sturm sequences isolate all
distinct real roots; multiplicities are retained separately and root lists count
repeated roots once. Nonpoint rational isolating intervals contain exactly one root
and exclude endpoint roots. Point intervals certify a rational root.

`AlgebraicReal` is an owned read-only scalar. Its public metadata contains an
independent ascending rational coefficient list, a 1-based index among the
polynomial's distinct real roots in increasing order, and exact rational isolating
bounds. Defining polynomials need not be minimal or irreducible. Refinement creates
another value with the same root identity, and values survive arithmetic context
disposal.

Comparison uses exact gcd/common-root tests and interval separation. Polynomial
sign evaluation first detects zero with gcd, then refines exact rational interval
enclosures until a nonzero sign is certified. Equal values with different defining
polynomials compare equal. Structural object identity is not numerical equality;
consumers use the arithmetic context's comparison operation.

### Algebraic arithmetic and lifting

Rational polynomial evaluation reduces modulo the scalar's defining polynomial.
Nonconstant results obtain a rational annihilating polynomial by resultant
elimination. Addition and multiplication eliminate their scalar defining
polynomials; reciprocal reverses coefficients after exact nonzero checking.
Certified interval enclosures select the unique result root, refining operand and
candidate intervals as needed. No fixed precision determines equality or order.
Rational sector samples are chosen strictly between ordered algebraic boundaries,
or beyond the finite bound of an unbounded sector.

Evaluation owners cache completed work for one caller: an arithmetic context
retains root counts for immutable defining polynomials, a point evaluation retains
coordinate powers, and an affine-line evaluation retains rational univariate images
along one captured line. Each owner charges capture, lookups and inserts to the same
arithmetic token; an interrupted operation installs no entry, and published values
retain no cache or control owner. Points expose their captured coordinates so
callers can publish the same immutable values they evaluated.

Specialization evaluates coefficients at exact earlier coordinates and trims
vanished leading coefficients by exact sign; `AlgebraicPolynomial.isZero` retains
nullification as a separate state. When every coefficient is rational, lifting
isolates the actual rational polynomial directly. Otherwise lifting forms a rational
annihilator by eliminating algebraic coefficient variables against their defining
polynomials, which yields a superset of the specialized roots; the nonzero leading
coefficient's annihilator keeps that elimination from vanishing identically. Every
candidate root is then checked against the actual specialized polynomial with exact
algebraic arithmetic, so the retained ordered roots are all and only its distinct
real roots.

### Invocation resources

Every arithmetic operation polls the caller's `Cancellation`; coefficient and
elimination steps charge deterministic work to that same token.
`PolynomialInterrupted` publishes no partial arithmetic result. Callers map
interruption to their invocation's termination reason.

`PolynomialLimits` defaults to 65,536 dense coefficients and 262,144 elimination
matrix cells per allocation. Each cap can be null to disable it within checked
integer dimensions. Degree addition, dense expansion and matrix dimensions are
checked before allocation, and constant-coefficient powers check their bit size
before exponentiation.
`PolynomialResourceLimit` distinguishes dense coefficients, matrix cells and degree
overflow. No cap truncates a polynomial, changes accepted mathematical semantics or
supplies a refutation; kernel callers translate declines into an unknown/resource
outcome. Completed immutable values remain valid after an interrupted subsequent
operation.

## Certified transcendental enclosures

`arithmetic.transcendental` supplies rational enclosures for real exp, natural log,
sin and cos. It is a numerical mechanism in the existing `klause` module and
depends only on `simplex.exact` and `util`. It owns no source formula, finite domain,
search state or solver verdict. An enclosure can establish separation or supply a
relaxation; it does not certify a source equality from overlapping endpoints.

### Arithmetic and domain

`RationalEnclosure` contains inclusive exact rational endpoints. Addition,
multiplication, negation, reciprocal and nonnegative integer powers retain all
values in the input intervals. Reciprocal rejects any interval containing zero.
Exp is defined on all reals. Natural log enclosure requires a strictly positive
lower endpoint; a crossing interval needs domain handling by its source owner.
Sin and cos are real functions with radian arguments and range `[-1, 1]`.

The series are the [exponential definition](https://dlmf.nist.gov/4.2.E19),
[logarithmic series](https://dlmf.nist.gov/4.6.E4),
[sine and cosine series](https://dlmf.nist.gov/4.19) and
[arctangent series](https://dlmf.nist.gov/4.24.E3). Remainders and interval
operations use rational arithmetic throughout. No floating constant or libm
evaluation has enclosure authority.

### Reduction and remainders

For nonnegative exp input, repeated halving gives `0 <= r <= 1/2`. Sum terms
through degree `n`. The first omitted term is `r^(n+1)/(n+1)!`; every subsequent
ratio is at most `r/(n+2)`. The geometric tail bound is therefore that omitted
term divided by `1-r/(n+2)`. Positive interval squaring reverses the halving.
Negative arguments use reciprocal, and zero has point value one. Monotonicity
combines endpoint enclosures for an interval argument.

For positive log input, exact powers of two give `x = 2^k*r` with `1 <= r < 2`.
Set `z=(r-1)/(r+1)`. The sum of `2*z^(2j+1)/(2j+1)` for `0 <= j < n` is a
lower bound. Its positive tail is at most
`2*z^(2n+1)/((2n+1)*(1-z^2))`. The same series encloses `log(2)`; signed interval
multiplication by `k` retains `log(x)=log(r)+k*log(2)`. Monotonicity combines
endpoint enclosures. Log of one has point value zero.

Pi is enclosed as `4*(atan(1/2)+atan(1/3))`. Tangent addition gives one;
the positive sum lies below `pi/2` and therefore equals `pi/4`. Each alternating
arctangent sum is enclosed between consecutive partial sums. Period reduction
chooses an arbitrary exact integer multiple of the enclosed `2*pi`. The uncertainty
in that product remains part of the reduced interval; choosing a quotient never
asserts an exact period from rational pi endpoints.

Sine and cosine Taylor polynomials through degree `2*n` at a rational reduced
center have error at most `abs(center)^(2*n+1)/(2*n+1)!`, because every derivative
has absolute value at most one on the real line. The input and reduction radii
add through the same derivative bound. Intersecting the result with `[-1,1]` is
sound. Wide input or reduction uncertainty returns that entire range, preserving
interior extrema. More series terms can resolve large period uncertainty; resource
exhaustion does not replace it with an approximate argument.

### Affine relaxations

Exp convexity gives a secant upper bound using the upper endpoint enclosures.
At anchor `c`, write `L <= exp(c) <= U` and `f=1+x-c`. The rational line
`L*f+(U-L)*min(0,1+a-c)` is below the exact tangent throughout `[a,b]`, including
negative tangent factors. Log concavity gives a chord lower bound using lower
endpoint enclosures, and the upper line `log(c).upper-1+x/c`.
Each relaxation retains its domain. Applying it outside that domain requires a
separate justification. Point intervals use function enclosures directly.

### Refinement and resources

The caller chooses a positive series term count. Refinement preserves exact
semantics and can stop without deciding a relation. Special values retain point
enclosures; a nonpoint enclosure is not an equality tolerance.

Each arithmetic owner retains cumulative work across operations and refinements,
polls cancellation and charges the caller meter once per arithmetic step. The
optional work cap and rational bit cap decline by exception without a partial
result. Cross-product bit bounds are checked before allocation. Checked term and
counter dimensions prevent overflow. Completed intervals remain immutable after
interruption or owner disposal. Source consumers must translate incomplete work
into unknown, rather than a witness or refutation.

## Exact analytic real coordinates

`arithmetic.real` retains authoritative real expressions beyond algebraic scalars.
It combines the existing [polynomial kernel](#exact-polynomial-and-algebraic-arithmetic) with certified
[transcendental enclosures](#certified-transcendental-enclosures). It owns mathematical values and
operations; the source service owns formula truth, integrality and publication.

### Values and identity

`ExactRealValue` captures an immutable `ExactRealExpression`. Algebraic leaves
retain their original `AlgebraicReal` metadata and rational coordinates. Other
nodes represent pi, exact real exp/log/sin/cos applications, sums, products and
nonnegative integer powers. Operand lists are captured, and getters return
independent lists. Values retain no cancellation token or mutable numerical owner.

Function construction requires a certified positive log argument. Exp, sine and
cosine accept any defined real argument. Algebraic operations keep algebraic
authority. Recognized exact identities include exp/log inverses, zero special
values, rational half-integer multiples of pi, symbolic cancellation and matching
`sin(x)^2+cos(x)^2`. Identity checks compare algebraic leaves exactly rather than
using their defining-polynomial object identities or rounded bounds.

Comparisons first use exact algebraic checks or applicable symbolic identities.
Exp and log preserve exact argument ordering. Otherwise rational enclosures can
certify strict separation. Overlapping nonpoint enclosures supply no equality or
ordering verdict. Refinement can finish without certification; that result retains
unknown and does not introduce a tolerance or altered source semantics.

### Source and publication boundary

Only an original-source check can turn these values into a solver witness. A
symbolic value describes one exact coordinate; it does not itself attest that a
formula holds. Source checks retain Boolean choices, exact integer integrality,
strict inequalities and invocation assumptions.

`Sample.symbolicReals` captures exact expression coordinates alongside its existing
rational and algebraic surfaces. Replacing approximate coordinates discards the
associated exact metadata unless the caller supplies replacement authority.
Approximate coordinates remain heuristic/display data. A rational-only exact
objective evaluator refuses a nonrational symbolic coordinate instead of deriving
an exact objective from its approximate view.

`TranscendentalAssignment` exposes the same `OpenTheoryAssignment` interface and
typed symbolic metadata. Functional text retains exact expressions and algebraic
root metadata. Published values survive later queries, cancellation and disposal
of the invocation that checked them.

---

Previous: [27. LP certification](27-lp-certification.md) · [Contents](README.md) · Next: [29. Numerical dependencies](29-numerical-dependencies.md)
