# Linear mixed integer/real arithmetic

Mixed linear constraints couple integer and real coordinates in one source model.
The linear relaxation checks real feasibility, and integer branching enforces the
source integrality requirements. A feasible continuous point is not enough to
accept the mixed model.

This theory follows the [shared theory contract](../theories.md) and uses the same
linear machinery as [linear integer](linear-integer.md) and
[linear real](linear-real.md) arithmetic.

## Scope and boundaries

Every theory-owned factor must have an admitted exact linear form. Integer and
real source namespaces stay distinct, including through lowering, reductions and
witness reconstruction. Open integer ranges remain theory-owned. Supported finite
factors can remain in a separate finite projection when the ownership plan admits
that composition.

A finite integer × real product becomes linear once its integer operand is fixed.
That leaf-completion case does not admit an unfixed bilinear factor into the open
linear theory. Shared nonlinear products use
[polynomial completion](polynomial-real.md) within that route's limits.

## Solving and composition

Shared Boolean choices select active rows and comparisons. The exact component
checks the linear residual, branches at rational fractional integer values and
retracts assertions with their decision scope. Exact reductions can preserve
integer structure while shrinking the system. Reconstruction recovers original
integer and real coordinates before acceptance.

Complete workers and open local search can share checked incumbents under the
same source and objective. Candidate integer windows guide heuristic search; they
do not bound the source model for a refutation. [Portfolios](../solver/portfolio.md)
owns admission and worker scheduling.

## Results and proof authority

`ExactLiraAssignment` retains arbitrary-precision integers and exact rational reals.
Acceptance checks integrality, declared bounds, source rows, strictness and Boolean
structure. Bounds, conflicts and unboundedness require the corresponding exact
numerical and source packages. Mixed unboundedness requires an integer-feasible
point and an integrality-preserving improving ray.

Complete affine source capture uses the retained linear authority invocation for
integer, Boolean and real costs. Fractional integer points request adjacent exhaustive
integer splits. Global bounds, attained optima and limits require complete checked
coverage; an improving ray requires a source-feasible base and integral lattice steps.
`OpenTheoryOptimum.linearResult` retains full original-source directions and separates
the incumbent from independently checked bounds and limiting values. A continuous
relaxation or neighborhood refutation cannot replace those source checks.

Without complete affine capture, specialized integral descent or strict-improvement
theory rounds retain their existing admission. Unsupported or interrupted checks
withhold global optimization claims. Objective admission and source correspondence
remain owned by preparation and the source adapter.

## State and resources

The invocation owns LP assertions, reduction caches, integer branches and candidate
assignments. The shared [linear source preparation](../solver/search.md#shared-component-search)
factory retains only immutable row forms, source addresses and Boolean dependencies;
each opening binds fresh callbacks, cancellation and mutable arithmetic owners.
Finite projections own their domain state separately. A source rewrite
or objective change needs matching reconstruction and proof scope; a retained basis
alone cannot transfer result authority. [Resources](../solver/resources.md) and
[LP certification](../lp/certification.md) define the containing and native budgets.

## Implementation

[ExactLinearFragment](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines `supportsExactLira`.
[ExactLiraSearchComponent](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
implements exact mixed checks and complementary integer decisions.
[ComponentPlan](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/ComponentPlan.kt)
owns finite/theory decomposition; the open route is `EXACT_LIRA`.
