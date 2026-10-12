# Exact polynomial and algebraic arithmetic

`arithmetic.polynomial` owns mathematical mechanisms in the existing `klause`
module. It depends only on itself, `simplex.exact` and `util`. Source syntax,
formula truth, projection-family selection, traversal and result publication belong
to callers. Kernel completion certifies an arithmetic operation, not a solver
verdict. The package never allocates finite CP domains or reads solver state.

## Exact polynomials and projection

`Polynomial` captures sparse `BigFraction` coefficients and immutable `Monomial`
keys. Terms combine exactly; zero coefficients disappear. Variable IDs are
nonnegative integers, ordered increasingly; lexicographic monomial comparison
inspects the largest variable first. Callers remap IDs to choose an elimination
order. Coefficient extraction uses ascending exponents; zero has degree -1 and an
empty coefficient list. Derivatives, substitution and exact division retain source
coefficient authority without floating conversion.

Bareiss elimination computes polynomial-ring Sylvester determinants with exact
polynomial division and row swaps. Resultants include zero/shared-factor and
constant cases. Principal subresultant coefficients use the truncated Sylvester
matrices for indices 0 through one less than the smaller degree. Index zero is
the resultant; leading coefficients are separately accessible. Policy chooses
which polynomials, reducta and coefficient families to project.

## Rational roots and scalar identity

`RationalPolynomial` captures ascending rational coefficients and trims trailing
zeros. Euclidean gcd, square-free decomposition and Sturm sequences isolate all
distinct real roots. Decomposition returns monic factors and retains multiplicities separately; the
input leading coefficient supplies the scalar unit. Root lists
count repeated roots once. Zero polynomials reject a finite root-list request;
nonzero constants produce an empty list. Nonpoint rational intervals contain
exactly one root and exclude endpoint roots. Point intervals certify a rational
root. Sturm open-interval counts exclude both endpoints.

`AlgebraicReal` is an owned read-only scalar. Its public metadata contains an
independent ascending rational coefficient list, a 1-based index among the
polynomial's distinct real roots in increasing order, and exact rational isolating
bounds. `rational` supplies an exact fraction when a point interval is known.
Defining polynomials need not be minimal or irreducible. A rational value can
retain a nonminimal defining polynomial; metadata still selects its exact root.
Refinement creates another value with the same root identity. Values survive
arithmetic context disposal and do not retain mutable isolation workspaces.

Comparison uses exact gcd/common-root tests and interval separation. Polynomial
sign evaluation first detects zero with gcd, then refines exact rational interval
enclosures until a nonzero sign is certified. Equal values with different defining
polynomials compare equal. Structural object identity is not numerical equality;
consumers use the arithmetic context's comparison operation.

## Algebraic arithmetic and lifting

Rational polynomial evaluation reduces modulo the scalar's defining polynomial.
Nonconstant results obtain a rational annihilating polynomial by resultant
elimination. Addition and multiplication eliminate their scalar defining
polynomials; reciprocal reverses coefficients after exact nonzero checking.
Certified interval enclosures select the unique result root, refining operand and
candidate intervals as needed. No fixed precision determines equality or order.
Rational sector samples are chosen strictly between ordered algebraic boundaries,
or beyond the finite bound of an unbounded sector.

Specialization evaluates coefficients at exact earlier coordinates and trims
vanished leading coefficients by exact sign. `AlgebraicPolynomial.isZero` retains
nullification as a separate state. Lifting forms a rational annihilator by
eliminating algebraic coefficient variables against their defining polynomials.
Independent coefficient conjugates produce a superset of the specialized roots.
The nonzero leading coefficient's annihilator excludes a zero conjugate, ensuring
the elimination cannot become identically zero through a conjugate degree drop.
Every candidate root is then checked against the actual specialized polynomial
with exact algebraic arithmetic. The retained ordered roots are all and only its
distinct real roots, including repeated/common roots and nested irrational
coordinates. Cross-coordinate polynomial evaluation retains exact equality and
sign authority even for correlated coordinates.

## Invocation resources

Every arithmetic operation polls the caller's `Cancellation`; coefficient and
elimination steps charge deterministic work to that same token. Cheap identity
operations also poll. `PolynomialInterrupted` publishes no partial arithmetic
result. Callers map interruption to their invocation's termination reason.

`PolynomialLimits` defaults to 65,536 dense coefficients and 262,144 elimination
matrix cells per allocation. Each cap can be null to disable it within checked
integer dimensions. Degree addition, dense expansion and matrix dimensions are
checked before allocation. `PolynomialResourceLimit` distinguishes dense
coefficients, matrix cells and degree overflow. No cap truncates a polynomial,
changes accepted mathematical semantics or supplies a refutation. Kernel callers
translate declines into an unknown/resource outcome. Exact completed immutable
values remain valid after an interrupted subsequent operation.

## Quadratic objective certificates

`arithmetic.quadratic` owns rational quadratic models and exact certificate checks
in the existing `klause` module. It reads only `simplex.exact` and neutral `util`
controls, independently of factors, source lowering and search. It preserves the
closed `lp.engine` dependency list. Its model denotes
`constant + linear^T x + x^T Hessian x / 2` with an exactly symmetric Hessian.
The source [objective representation](../api.md#quadratic-objective-representation)
retains literal cross coefficients; callers own Hessian conversion, source
mappings, objective direction and invocation assumptions.

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

Each check polls cancellation and charges its containing native control. Work and
matrix limits interrupt without evidence. Certificates retain their captured
model; numerical evidence cannot establish a source result until its adapter
checks source semantics and scope. These mechanisms do not expose quadratic
optimization through the pipeline.
