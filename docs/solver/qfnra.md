# Polynomial real theory

## Projection families

[PolynomialProjection](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialProjection.kt)
constructs a complete Collins projection family for rational multivariate
polynomials. It reads the [exact polynomial kernel](../architecture.md#numerical-package-ownership)
and owns selection of projection operations and declared variable order.
The theory does not materialize finite CP domains or depend on the linear engines.

Variable IDs define ascending lifting order. Construction eliminates variables in
descending order and stores one polynomial family per declared ID, containing only
polynomials whose main variable is that ID. Lower-variable polynomials carry through
elimination. An absent dimension retains an empty family. The univariate family is
the last projection level.

For each eliminated top-variable family, the projection contains all coefficient
polynomials, every principal subresultant coefficient of each nonconstant reductum
and its derivative, and every principal subresultant coefficient between reducta
from distinct input polynomials. Equal-degree pairs use the same exact kernel
operation. Zero and rational constant projections add no boundaries. Projection
polynomials are normalized by nonzero rational scaling and deduplicated; this
normalization concerns zero loci and does not provide signed source-atom truth.

Coefficient signs preserve degree and identically-zero specializations over a base
cell. Subresultant vanishing preserves distinct-root counts and common-root
structure. Together they preserve delineability, including repeated roots, shared
roots and vanishing leading coefficients. The complete projection includes all
reducta rather than relying on a nonvanishing discriminant or leading coefficient.

The returned family owns its level buffers. Input list mutation and mutation of a
returned list do not change captured families. Immutable kernel polynomials may be
shared. One caller-supplied arithmetic context spans construction; interruption and
kernel resource stops propagate without publishing a partial family. See
[resource allowances](resources.md) for the containing scope.
