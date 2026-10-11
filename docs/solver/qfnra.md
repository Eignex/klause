# Polynomial real theory

`solveQfNra` decides a neutral `PolynomialRealFormula` over declared real and Boolean
IDs. Normalization expands exact rational polynomial expressions and retains each
atom's original left-minus-right sign and relation. The formula evaluator preserves
Boolean references and arbitrary `Not`, `And` and `Or` structure. Source parsing and
frontend routing remain caller responsibilities; the theory reads no input format.

The complete fragment is polynomial real satisfaction with rational coefficients
and Boolean structure. It does not include transcendental functions or integer
integrality. Completeness does not guarantee completion within invocation limits.
The [support policy](../architecture.md#solver-identity-and-support-policy) permits
sound incomplete mechanisms alongside complete fragment procedures. This theory's
pipeline route is a separate invocation over `PolynomialRealProblem`, rather than
a component composing arbitrary factors from `Problem` in shared search.
Shared composition is active work in
[#2621](https://github.com/Eignex/klause/issues/2621); the separate invocation boundary
describes implemented coverage rather than the intended limit of polynomial support.
In particular, rejecting local-search execution violates the required availability
of a complete-search/local-search portfolio over the same source problem.

## Projection families

[PolynomialProjection](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialProjection.kt)
constructs a complete Collins projection family for rational multivariate
polynomials. It reads the [exact polynomial kernel](../arithmetic/polynomial.md)
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

## Lifting and truth

Traversal uses an iterative stack in ascending variable order. Each projection
polynomial specializes at the exact earlier coordinates. Identically-zero
specializations add no roots; nonzero constants also add none. Kernel isolation
supplies every distinct real root, and exact comparison sorts and deduplicates the
union. Each root is a section sample; kernel rational samples lie strictly inside
the intervening and unbounded sectors. Empty families still contribute a whole-line
sector, so every declared dimension receives a coordinate.

At a prefix, only original atoms whose variables are all assigned have definite
truth. Higher atoms and every Boolean variable remain unknown. A three-valued false
formula prunes that base cell because those lower signs are invariant throughout
the cell. No prefix supplies a satisfying result. At a complete sample, exact
algebraic evaluation decides every atom and iterative Boolean search preserves
reference correlation. Unused Boolean IDs receive false. The complete assignment
is checked against the normalized source formula before capture.

Full Collins projection and exhaustive section/sector traversal provide a
sign-invariant decomposition, including degree loss and nullification. A satisfying
result requires a complete exact witness; an unsatisfiable result requires exhaustion
of every unpruned cell and its Boolean alternatives. Root indices in returned
`AlgebraicReal` values identify their rational defining polynomial's distinct roots,
not the position in a specialized lifting family.

## Results and invocation resources

`QfNraAssignment` owns Boolean and real-coordinate buffers. Array and list getters
return independent snapshots; immutable algebraic scalars survive later invocations.
Results retain counts for projected polynomials, visited prefix cells and native
work. They retain no mutable traversal, refinement or cancellation state.

`QfNraParams.maximumWork` is nullable for unlimited work; zero prevents entry. One
counted scope spans source-ID validation, normalization, projection, lifting,
Boolean search and the final publication poll. Each charge reaches the caller's
meter once, and the scope retains the caller's deadline. These native work units
are separate from legacy open-theory policy units. A stop before publication returns
`Unknown` with interruption or work-limit reason, never partial SAT or UNSAT.

Kernel allocation caps remain configurable through `polynomialLimits`. Dense,
matrix or checked-degree declines return `Unknown(ResourceLimit)` with the resource
kind. Caps do not truncate accepted polynomials or restrict their mathematical
semantics. See [kernel resources](../arithmetic/polynomial.md#invocation-resources)
and [scope composition](resources.md).

The returned family owns its level buffers. Input list mutation and mutation of a
returned list do not change captured families. Immutable kernel polynomials may be
shared. One caller-supplied arithmetic context spans construction; interruption and
kernel resource stops propagate without publishing a partial family. See
[resource allowances](resources.md) for the containing scope.

## Witness ownership

[ExactNraAssignment](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/ExactNraAssignment.kt)
captures Boolean buffers and an immutable list of exact real scalars. Scalar metadata
survives the producing arithmetic context and reconstructs each coordinate by its
rational defining polynomial and distinct-root index. Coordinates are never rounded
to rational points or binary64 for publication.

The pipeline's `OpenTheoryAssignment.ExactNra` preserves these values beside its
linear rational witnesses. Its `algebraicRealValue(id)` extension follows source
reconstruction wrappers without replacing the exact real coordinate. Point-identified
rational coordinates retain reduced rational text; other algebraic text and the typed metadata
contract are defined in the [API contract](../api.md).

## Pipeline and frontend publication

The pipeline accepts opaque `ir.PolynomialRealProblem` and shared `ir.Problem`
models through `pipelineRequest()`. `OpenTheoryPipeline.execute` selects a finite
strategy with exact polynomial completion and returns the common SAT/UNSAT/UNKNOWN
result with `PolynomialTheoryStats` and an optional native resource cause. Source declaration prefixes trim numeric conditional auxiliaries at capture.
Both verdicts receive a final stop check, and SAT capture receives another check
before publication. Timeout, external cancellation and budget/resource exhaustion
retain distinct common termination reasons. Exact source scalars survive invocation
cleanup. [Input semantics](../formats.md#polynomial-source-lowering) owns admission
and model rendering; [resources](resources.md) owns the public caps and native units.

## Conditioned theory service

`PolynomialTheoryService` captures one immutable neutral formula and its coordinate
counts. Each `PolynomialTheoryInvocation` owns a cumulative native work allowance
across fresh conditioned checks. Boolean pins and rational lower/upper bounds are
conjoined within the counted arithmetic scope. Query construction, normalization,
CAD, exact witness capture and the final verdict poll share cancellation and allocation
limits. Closing the invocation rejects further queries; captured scalar witnesses
remain readable. Invocations share no mutable traversal or refinement state.

`PolynomialSearchComponent` consumes shared Boolean facts and semantic integer bounds.
Integer columns map to theory coordinates without creating continuous CP domains.
A query over an unfixed integer interval is a real relaxation: its exact refutation
can reject the finite branch, while its witness cannot accept that branch. Complete
checks require every mapped integer and Boolean to be fixed. Conflicts without a
clause explanation remain chronological and cannot enter learned-clause exchange.
Retraction invalidates query results; the containing invocation allowance persists.

## Shared model composition

`SharedPolynomialModel` separates exactly encodable continuous factors from finite
factors. Integer columns appearing in the continuous equations receive symbolic
real coordinates for conditioned queries; their source finite domains and global
constraints remain in CP. Exact linear rows, Boolean reification, real bounds and
integer/real products become neutral polynomial formulas. AllDifferent, scheduling
and other finite factors remain intact in the finite projection. Linear relaxation
rows cannot stand in for a factor's semantics. Binary64 constants denote their exact
binary rational values.

Backtracking checks interval relaxations and fixed finite branches. Local search
generates candidates from finite factors, then pins their complete finite values in
a theory query before publication. ALNS uses that same completion in bootstrap,
finite CP repair and its inner local search. Pure-real sources use an empty finite
projection, so the local-search arm still runs and obtains an exact completion.
Rejected heuristic candidates never establish a global refutation.

`OpenTheoryPipeline.minimize` supports objectives over the finite columns using the
same projection, theory and objective in every worker. Complete traversal can prove
an optimum or infeasibility; local search and ALNS retain exact incumbents without
proof admission. Continuous objectives and all-solution enumeration remain outside
this interface. [Search](search.md), [local search](local-search.md),
[portfolio](portfolio.md) and [sessions](sessions.md) own their execution lifecycles.
