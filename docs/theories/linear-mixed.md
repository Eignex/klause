# Linear mixed integer/real arithmetic

Mixed linear constraints couple integer and real coordinates in one source model.
The linear relaxation checks real feasibility, and integer branching enforces the
source integrality requirements for open integer columns. Finite choices come from
the authoritative finite owner. A feasible continuous point is not enough to
accept the mixed model.

This theory follows the [shared theory contract](../theories.md) and uses the same
linear machinery as [linear integer](linear-integer.md) and
[linear real](linear-real.md) arithmetic.

## Scope and boundaries

Every theory-owned factor must have an admitted complete selected linear encoding.
Compound formulas and scoped real auxiliaries belong to the private projection;
source publication retains the original declaration counts. Integer and
real source namespaces stay distinct, including through lowering, reductions and
witness reconstruction. Open integer ranges remain theory-owned. Finite declarations retain their domain state and choices, including holes. A bounded
linear factor can participate in both propagation and selected theory encoding.
Unrelated finite factors retain their own enforcement path.

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

With the default null `openHintFlips` allowance, one linear completion owner can
reuse completed partial LP points for the same direct primitive Boolean preferences as the
[linear-real component](linear-real.md#solving-and-composition). Custom registered
providers retain precedence; explicit zero and positive allowances retain their
existing real-coordinate producer admission and opt-in behavior. Integer-only
sources use the same default path under [linear-integer semantics](linear-integer.md).
No source-clause index, Boolean draw or extra LP check is created by the default
path. Preferences remain guesses about branch order; a
fractional source integer is not a model value and integrality remains a
complete-check requirement. Integer branches, retraction, stops and opening close
retain the component point lifetime and existing work owners.

Automatic Boolean branching selects conflict activity for a complete mixed worker
with one admitted default linear point owner. Explicit source-order or activity
selections retain their policy. Private queries, explicit hint allowances, custom
registered providers and multiple completion owners retain their existing source
order under automatic selection. This changes only exhaustive Boolean variable
ordering; arithmetic checks and source publication retain their authority.

Open mixed traversal consults arithmetic proposals before the finite fallback. A completed
continuous point can guide adjacent exhaustive integer splits while bounded declarations
retain CP ownership. Integral point coordinates guide a finite equality and its remaining
lower/upper alternatives; CP enforces domains, holes and unrelated finite factors on each
alternative. A point supplies no pin or publication authority. Missing points, unresolved
disjunctions and stops retain the ordinary finite fallback. Complete checks still require
all finite coordinates to be fixed, and retraction discards the point with its LP scope.

With the default null hint allowance and one registered linear completion owner,
a complete mixed worker can prefer an unresolved row of its current source
disjunction or a disequality side using an existing exact point or an optimal float point's copied model
prefix. Frontend lowering coordinates remain present; only LP normalization
columns are omitted. Boolean and finite choices retain their order and must already
be complete.
Bounded exact comparison evaluation substitutes known Boolean inputs, preserves
strictness and skips guarded rows. Named predicates use their registered threshold
and strictness, including integer lattice rounding. Disequality ties and unavailable
coordinates retain their original side order. These point values only order exhaustive choices;
a float optimum supplies no witness, implication, bound or refutation. Custom
providers, explicit allowances, multiple completion owners, private queries and
pure integer or real models retain their selected ordering. The comparison work
uses the current operation and retained hint stop; it adds no index, numerical
query, draw or allowance.

A complete worker can try witness recovery when an existing LP float point lacks
an exact primal package. After Boolean and source disjunction choices are complete
and every finite integer is fixed, it rounds the copied model prefix's integer coordinates as
private guesses and substitutes them into all selected source rows and current
branch bounds. It first checks the exact IEEE real prefix and rationally reconstructed
source reals against those rows, independently of LP auxiliary columns. If neither
point passes, a residual with wider rows can repair its guessed real prefix through
paired opposite, non-strict source equations. Bounded sparse elimination retains
free-coordinate guesses and reconstructs pivot values. If that guess fails full-row
validation, the same retained pivots reduce the remaining inequalities. A supported
difference residual can complete the free real coordinates before pivot reconstruction.
The kernel distinguishes exact row contradiction from representation or resource
decline. This guessed-integer caller treats both as failed point attempts; neither
can refute the source model. The exact [pure-real](linear-real.md) caller can
consume a negative result over a necessary source-row subset as a local refutation.
Every included row retains its known Boolean inputs and current source premises;
unknown guards, unresolved source choices and unknown Boolean terms are omitted.
That caller may retain the original paired-equation and false-residual row identities
for a source-premised conflict; guessed integer recovery requests no such authority.
It can also check an entirely admitted reduced difference residual for a negative
cycle, retaining every original input-row premise. A graph failure for guessed
integers remains a failed point attempt, with no source refutation authority.
A general residual is partitioned by connected real coordinates. Each component
uses the difference checker when supported; other components can request exact points
through the existing [source LP owner](../lp/architecture.md), sharing the reduction
owner's operation allowance and unchanged small-model admission. Without equation
repair, an entirely supported real difference graph can also complete the integer
guesses. The full rows,
strictness, integrality and source reconstruction still gate acceptance. Failed or
unsupported guesses supply no conflict, bound or exhaustion claim. Graph and prefix
checks add no LP solve; general components request one only after those checks fail.
Every stage uses the current operation allowance; private neighborhood openings
retain their own completion path.

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
