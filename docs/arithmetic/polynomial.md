# Exact polynomial and algebraic arithmetic

This kernel performs exact polynomial operations and represents real roots without
rounding them to floating values. The theory uses those operations to construct
and check nonlinear real assignments. The kernel itself checks arithmetic results,
while the caller decides what they establish about a source formula.

The sections follow polynomial representation, root identity, algebraic operations
and resource limits. [Polynomial theory](../theories/polynomial-real.md) owns formula decisions;
[API value ownership](../api.md#exact-assignment-values) owns public witness access.

## Role and boundaries

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

Constant coefficient powers check their numerator/denominator bit-index lower
bound before native exponentiation. A required index beyond Int representation
returns the checked degree/dimension resource decline, preserving exact source
semantics without wrapped dimensions. Unit and zero coefficients retain their
exact inexpensive power operations.
