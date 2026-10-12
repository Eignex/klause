# Certified transcendental enclosures

`arithmetic.transcendental` supplies rational enclosures for real exp, natural log,
sin and cos. It is a numerical mechanism in the existing `klause` module and
depends only on `simplex.exact` and `util`. It owns no source formula, finite domain,
search state or solver verdict. An enclosure can establish separation or supply a
relaxation; it does not certify a source equality from overlapping endpoints.

## Arithmetic and domain

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

## Reduction and remainders

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

## Affine relaxations

Exp convexity gives a secant upper bound using the upper endpoint enclosures.
At anchor `c`, write `L <= exp(c) <= U` and `f=1+x-c`. The rational line
`L*f+(U-L)*min(0,1+a-c)` is below the exact tangent throughout `[a,b]`, including
negative tangent factors. Log concavity gives a chord lower bound using lower
endpoint enclosures, and the upper line `log(c).upper-1+x/c`.
Each relaxation retains its domain. Applying it outside that domain requires a
separate justification. Point intervals use function enclosures directly.

## Refinement and resources

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
