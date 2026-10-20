# 22. Linear mixed arithmetic

Mixed linear constraints couple integer and real coordinates in one source model.
This theory combines the two previous chapters: the
[linear real](20-linear-real.md) relaxation checks real feasibility, and the
[linear integer](21-linear-integer.md) branch order enforces integrality on the
integer columns. A feasible continuous point is not enough to accept a mixed model,
and the main addition here is how a complete integral and real point is recovered
from what the LP returns.

This theory closes the linear part of the book. It follows the
[shared theory contract](18-theories.md) and shares one component implementation
with the two linear chapters before it.

## Scope and boundaries

Every theory-owned factor must have an admitted complete selected linear encoding.
Compound formulas and scoped real auxiliaries belong to the private projection;
source publication keeps the original declaration counts. Integer and real source
namespaces stay distinct through lowering, reductions and witness reconstruction.
Open integer ranges remain theory-owned. Finite declarations keep their domain state
and choices, including holes, and unrelated finite factors keep their own
enforcement path.

A finite integer × real product becomes linear once its integer operand is fixed.
That leaf-completion case does not admit an unfixed bilinear factor into the open
linear theory; shared nonlinear products use
[polynomial completion](24-polynomial-real.md) within that route's limits.

## Solving and composition

Shared Boolean choices select active rows and comparisons, and the component runs
the [integer branch order](21-linear-integer.md#solving-and-composition): finite
integers, then disjunctions and disequalities, then the reduction or plain
fractional splits. Reconstruction recovers original integer and real coordinates
before acceptance. Three mixed-specific mechanisms sit around that order.

### One LP for both kinds of column

Integer and real source columns live in the same LP model; integer columns are
marked integral and enter with their exact source bounds, so one relaxation solve
checks the coupled system. Integer bounds that finite search publishes are batched
into that LP with their premises, after the active source rows. The reduction of
the [previous chapter](21-linear-integer.md#the-double-bounded-reduction) keeps
real and integer columns apart: its echelon step never mixes an integer source
column into a real transformed column, so integrality survives the transformation
and only integer coordinates are branched.

### Finite and open integers together

A mixed model often has bounded integers owned by finite search beside open ones
owned by the theory. The component treats a finite integer as decided only when
finite search has fixed it; until then its complete check is indeterminate, and the
first unfixed finite integer is the next branch. The theory never narrows a finite
domain itself: it proposes alternatives, and finite propagation enforces bounds,
holes and unrelated finite factors on each of them.

### Point-guided finite splits

Open mixed traversal consults arithmetic proposals before the finite fallback. A
completed continuous point guides adjacent exhaustive integer splits while bounded
declarations keep CP ownership. An integral point coordinate selects a finite
equality first, followed by its lower and upper remainders; CP enforces domains,
holes and unrelated finite factors on each alternative. A point supplies no pin or
publication authority, and retraction discards it with its LP scope.

With the default null hint allowance and one linear completion owner, a complete
mixed worker can also prefer a disjunct or disequality side using an existing exact
point or the model prefix of an optimal float point. Comparison evaluation keeps
strictness, substitutes known Booleans and applies integer lattice rounding to named
predicates. Automatic Boolean branching selects conflict activity for such a
worker. These preferences only order exhaustive choices; a float optimum supplies no
witness, implication, bound or refutation.

### Witness recovery

The LP can finish with a float optimum but no exact primal package. Once every
Boolean and disjunction is chosen and every finite integer is fixed, the component
tries to turn that float point into an exact witness:

1. Round the integer coordinates of the float model prefix as private guesses and
   substitute them into all selected source rows and current branch bounds.
2. Check the exact IEEE real prefix, then rationally reconstructed reals, against
   those rows.
3. If neither passes, repair the real prefix through paired opposite non-strict
   source equations using bounded sparse elimination, and reduce the remaining
   inequalities by the same pivots.
4. Complete the free real coordinates per connected component. Real columns are
   grouped by the rows they share, using a disjoint-set union, and each group is
   solved separately: by the difference checker where its rows form a difference
   graph, otherwise by an exact point from the
   [source LP owner](26-lp-architecture.md) under the reduction owner's allowance.
   Completion is attempted only up to 65,536 row entries and a quadratic column
   bound; larger systems return to branching.

The full rows, strictness, integrality and source reconstruction still gate
acceptance. Failed or unsupported guesses supply no conflict, bound or exhaustion
claim; the component returns to ordinary branching. Graph and prefix checks add no
LP solve, and general components request one only after those checks fail.

### Shared incumbents

Complete workers and open local search can share checked incumbents under the same
source and objective. Candidate integer windows guide heuristic search; they do not
bound the source model for a refutation. [Portfolios](16-portfolio.md) owns
admission and worker scheduling.

## Results and proof authority

`ExactLiraAssignment` keeps arbitrary-precision integers and exact rational reals.
Acceptance checks integrality, declared bounds, source rows, strictness and Boolean
structure. Bounds, conflicts and unboundedness require the corresponding exact
numerical and source packages. Mixed unboundedness requires an integer-feasible
point and an integrality-preserving improving ray.

Complete affine source capture uses the retained linear authority invocation for
integer, Boolean and real costs. Fractional integer points request adjacent
exhaustive integer splits. Global bounds, attained optima and limits require
complete checked coverage; an improving ray requires a source-feasible base and
integral lattice steps. `OpenTheoryOptimum.linearResult` keeps original-source
directions and separates the incumbent from independently checked bounds and
limiting values. A continuous relaxation or neighborhood refutation cannot replace
those checks.

The [integral descent](21-linear-integer.md#objectives) rejects any objective with
a continuous term: it steps by one, and a real objective has no next value below
the incumbent and need not attain its infimum. Mixed objectives with real costs
therefore use the retained linear authority invocation or strict-improvement
rounds.

Without complete affine capture, specialized integral descent or strict-improvement
theory rounds keep their [integer-chapter](21-linear-integer.md#results-and-proof-authority)
admission. Unsupported or interrupted checks withhold global optimization claims.

## State and resources

The invocation owns LP assertions, reduction caches, integer branches and candidate
assignments. The shared linear source preparation factory keeps only immutable row
forms, source addresses and Boolean dependencies; each opening binds fresh
callbacks, cancellation and mutable arithmetic owners. Finite projections own their
domain state separately. A source rewrite or objective change needs matching
reconstruction and proof scope; a retained basis alone cannot transfer result
authority.

Every recovery stage charges the current operation allowance, and private
neighborhood openings keep their own completion path. Point-guided preferences use
the current operation and hint stop; they add no index, numerical query or
allowance. [Resources](11-resources.md) and
[LP certification](27-lp-certification.md) define the containing and native budgets.

## Implementation

[ExactLinearFragment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines `supportsExactLira`.
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
