# Linear integer arithmetic

Linear integer constraints combine integer coordinates with constant coefficients
and Boolean structure. The exact route checks a linear relaxation, enforces
integrality and searches the remaining alternatives. It retains open source bounds
instead of choosing a finite box for the solver's convenience.

This is a peer of [linear real](linear-real.md) and [linear mixed](linear-mixed.md)
arithmetic under the [shared theory contract](../theories.md). The implementation
shares their linear machinery.

## Scope and boundaries

The admitted factor surface consists of exactly representable linear conjunctions
or disjunctions and their Boolean structure. Nonconstant products and other
nonlinear integer factors do not become linear theory constraints. Finite globals
remain in their admitted finite projection; an open integer used by a CP-only
factor requires a valid owner or the route declines.

An entirely admitted difference fragment can use [difference logic](difference.md).
Finite declarations retain their finite domain state and choices under either
pipeline policy. A bounded linear factor can contribute both propagation and an
encoder to joint reasoning; the theory waits for authoritative finite assignments
before publishing a completed point. SMT admission remains defined by
[SMT-LIB semantics](../formats/smtlib.md).

## Solving and composition

The linear component asserts active source rows under shared Boolean choices.
Exact integer reductions and bound reasoning can simplify the remaining system.
A fractional open-integer candidate causes complementary branches at exact floor
and ceiling thresholds. Finite variables receive scoped fixings from their finite
owner, preserving bounds and holes. Shared search owns decision levels, retraction and conflict
learning; the arithmetic component owns row assertions and numerical checks.

[Open portfolios](../solver/portfolio.md) can pair the proof-producing route with
source-checked local-search workers. Current strategy admission limits remain
coverage gaps under the [vision](../vision.md#shared-model-breadth).

## Results and proof authority

`ExactLiraAssignment` carries arbitrary-precision integer coordinates, including
values beyond `Long`. Publication checks source rows and integrality. A rational
relaxation point or a failed witness check establishes neither feasibility nor
infeasibility.

The pipeline selects specialized integral descent for integer objectives without
Boolean or continuous cost when every improving row remains in its supported
fragment. Other admitted linear objectives, including Boolean costs, use exact
strict-improvement rounds through `RationalTheoryDescent`. A completed refutation
of a strict cutoff proves the checked incumbent optimal; a refutation before any
incumbent proves infeasibility. Exact objective units survive each round. Rational
descent supplies no recession certificate and need not terminate on an unbounded
objective. Mathematical decidability of linear integer arithmetic does not remove
invocation limits or establish a verdict for an interrupted traversal.

## State and resources

Source rows and forms are prepared read-only. The invocation owns asserted scopes,
integer branches, reduction caches and LP state. Retraction removes scoped premises
before evidence can be reused. [TheoryParams](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/TheoryParams.kt)
separates theory checks, open work, decisions and cancellation. Native numerical
work retains the [LP certification](../lp/certification.md) resource contract.

## Implementation

[ExactLinearFragment](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines linear factor admission.
[ExactLiraSearchComponent](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
implements integer and mixed checks within `theory.qflra`. The pipeline names the
integer-containing route `EXACT_LIRA`; this does not give pure integer sources real
variables or merge their source sorts.
