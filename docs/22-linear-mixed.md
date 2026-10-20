# 22. Linear mixed arithmetic

Mixed linear constraints couple integer and real coordinates in one source model.
This theory combines the two previous chapters: the
[linear real](20-linear-real.md) relaxation checks real feasibility, and the
[linear integer](21-linear-integer.md) branch order enforces integrality on the
integer columns. A feasible continuous point is not enough to accept a mixed model,
and the main addition here is how a complete integral and real point is recovered
from what the LP returns. This chapter closes the linear part of the book.

## Scope and boundaries

Every theory-owned factor must have an admitted complete selected linear encoding.
Integer and real source namespaces stay distinct through lowering, reductions and
witness reconstruction. Open integer ranges remain theory-owned; finite
declarations keep their domain state and choices, including holes.

A finite integer × real product becomes linear once its integer operand is fixed.
That leaf-completion case does not admit an unfixed bilinear factor; shared
nonlinear products use [polynomial completion](24-polynomial-real.md).

## Solving and composition

Shared Boolean choices select active rows, and the component runs the
[integer branch order](21-linear-integer.md#solving-and-composition): finite
integers, then disjunctions and disequalities, then the reduction or plain
fractional splits. Three mixed-specific mechanisms sit around that order.

### One LP for both kinds of column

Integer and real source columns live in the same LP model; integer columns are
marked integral and enter with their exact source bounds, so one relaxation solve
checks the coupled system. The
[double-bounded reduction](21-linear-integer.md#the-double-bounded-reduction) never
mixes an integer source column into a real transformed column, so integrality
survives the transformation and only integer coordinates are branched.

### Finite and open integers together

A mixed model often has bounded integers owned by finite search beside open ones
owned by the theory. A finite integer counts as decided only when finite search has
fixed it; until then the complete check is indeterminate and the first unfixed
finite integer is the next branch. The theory never narrows a finite domain itself:
it proposes alternatives, and finite propagation enforces bounds, holes and
unrelated finite factors on each.

A completed continuous point guides these splits: an integral coordinate selects a
finite equality first, then its lower and upper remainders. With the default hint
allowance, a complete worker can also prefer a disjunct or disequality side using
an exact point or the prefix of an optimal float point. These preferences only
order exhaustive choices; a float optimum supplies no witness, bound or
refutation.

### Witness recovery

The LP can finish with a float optimum but no exact primal package. Once every
Boolean and disjunction is chosen and every finite integer is fixed, the component
tries to turn that float point into an exact witness:

1. Round the integer coordinates of the float point as private guesses and
   substitute them into all selected source rows and branch bounds.
2. Check the exact IEEE real prefix, then rationally reconstructed reals, against
   those rows.
3. If neither passes, repair the real prefix through paired opposite source
   equations using bounded sparse elimination.
4. Complete the free real coordinates per connected component, grouped by shared
   rows: by the difference checker where the rows form a difference graph,
   otherwise by an exact point from the [source LP owner](26-lp-architecture.md).
   Completion is attempted only up to 65,536 row entries.

The full rows, strictness, integrality and source reconstruction still gate
acceptance. A failed guess supplies no conflict or bound; the component returns to
ordinary branching.

Complete workers and open local search can share checked incumbents under the same
source and objective through [portfolios](16-portfolio.md). Candidate integer
windows guide heuristic search; they do not bound the source for a refutation.

## Results and proof authority

`ExactLiraAssignment` keeps arbitrary-precision integers and exact rational reals.
Acceptance checks integrality, declared bounds, source rows, strictness and Boolean
structure. Mixed unboundedness requires an integer-feasible point and an
integrality-preserving improving ray.

Complete affine source capture uses the retained linear authority invocation for
integer, Boolean and real costs; `OpenTheoryOptimum.linearResult` separates the
incumbent from checked bounds and limiting values. The
[integral descent](21-linear-integer.md#objectives) rejects any objective with a
continuous term: a real objective has no next value below the incumbent and need
not attain its infimum. Unsupported or interrupted checks withhold global
optimization claims.

## State and resources

The invocation owns LP assertions, reduction caches, integer branches and candidate
assignments. The shared preparation factory keeps only immutable row forms, source
addresses and Boolean dependencies. A source rewrite or objective change needs
matching reconstruction and proof scope; a retained basis alone cannot transfer
result authority. Every recovery stage charges the current operation allowance.

## Implementation

[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
implements exact mixed checks and complementary integer decisions.
[MixedPointCompletion](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/MixedPointCompletion.kt),
[MixedEqualityRepair](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/MixedEqualityRepair.kt)
and [MixedRealCompletion](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/MixedRealCompletion.kt)
implement witness recovery.
[ComponentPlan](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/ComponentPlan.kt)
owns finite/theory decomposition; the open route is `EXACT_LIRA`.

---

Previous: [21. Linear integer arithmetic](21-linear-integer.md) · [Contents](README.md) · Next: [23. Nonlinear integer arithmetic](23-nonlinear-integer.md)
