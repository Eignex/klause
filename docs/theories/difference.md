# Difference logic

Difference constraints describe bounds on the distance between two coordinates,
such as `x - y <= c`. A graph represents each admitted row as an edge. Feasible
potentials supply coordinates; a negative cycle supplies a contradiction.

This theory has integer and real uses within the common
[theory service contract](../theories.md). It does not create finite domains for
open variables.

## Scope and boundaries

The open integer route requires complete difference coverage for every relevant
integer factor, including declared bounds and both meanings of reified rows.
Numerical safety is part of admission. An implied difference relaxation alone does
not establish that a whole factor belongs to the fragment.

Pure-real difference rows are handled inside the linear-real component when they
meet its graph admission conditions. Other linear rows retain the applicable
[linear integer](linear-integer.md), [linear real](linear-real.md) or
[linear mixed](linear-mixed.md) route. The theory graph and the incremental
finite-domain difference propagator have separate state owners.

## Solving and composition

Shared search selects Boolean alternatives and passes their active guarded edges
and integer bound facts to the component. Graph checks can detect conflicts before
a complete Boolean assignment. Complete checks require settled guards and supply
coordinates for the variables this theory owns.

The pipeline can compose theory-owned columns with a finite projection where the
ownership plan admits that decomposition. Source variables keep their identities;
extracting a graph never authorizes dropping factors outside its fragment.

## Results and proof authority

Completed graph feasibility supplies an integer potential sample or a rational
real assignment. A captured, verified negative cycle justifies a conflict; usable
Boolean guards can be negated into a shared explanation. Missing explanation
coverage retains chronological conflict handling rather than inventing a clause.
A stopped graph check is indeterminate.

Objective admission is separate from graph feasibility. The open minimizer must
keep every improving cutoff within a supported theory; an arbitrary linear
objective can leave the difference fragment. [Input semantics](../formats.md)
qualifies the route's published result.

## State and resources

Immutable edges and fragment descriptions belong to
[arithmetic.difference](../../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference).
Each search component owns its active facts and captured assignment; retraction
invalidates the assignment. Graph operations poll the caller's cancellation.
Representation or safety declines retain another admitted route or an undecided
result, rather than an invented bounded model.

## Implementation

[DifferenceTheorySolver](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/difference/DifferenceTheorySolver.kt)
checks complete Boolean assignments.
[DifferenceSearchComponent](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/difference/DifferenceSearchComponent.kt)
participates in shared traversal and publishes applicable root bounds.
[DifferenceFragments](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/DifferenceFragments.kt)
defines source coverage and numerical admission.

## Real graph admission and checks

The real difference component uses a difference graph when every arithmetic row is a
one-column bound or a two-column difference with an integral normalized bound. Boolean
guards select active edges. With `n` graph vertices, scaling bounds by `n+1` and
subtracting one from strict edges preserves every simple cycle's sign, including
zero-weight strict cycles. Feasible graphs supply rational potentials; witnesses still
pass the complete source-row check. A verified negative predecessor cycle supplies a
conflict clause containing the complements of its active signed guards. Immutable
unconditional source rows and declared bounds need no Boolean premise; assigned root
guards remain in the clause. Cycle capture reuses the feasibility traversal rather than
running another graph sweep. The component checks that every clause literal is false
in the current shared context before publishing the conflict for ordinary learning.
Cancelled checks withhold the proof, and missing cycle capture retains the shared LP
route. Private source-bound branches and overridden LP certification policies also
retain that route.

Admitted pure-real difference components under the production certification policy
defer LP construction and check the active graph at partial and complete Boolean states,
including the root. A feasible partial graph supplies consistency without a numerical
guide or a model contribution. Complete graph points still pass the complete source-row
check before publication. Missing cycle evidence, declined source validation and private
source-bound branches retain the shared LP route. Late LP installation reaches the
current decision scope before asserting resolved source rows, so retraction removes their
guards and original witness premises together. Unconditional source rows can be reasserted
after that scope is popped; declared bounds remain immutable root facts.

If every Boolean is published at the root, initialization waits for those publications
to reach the theory before checking the pinned arithmetic.

Pure-real local-search completions keep their source rows and Boolean pins, skipping
source presolve before the selected exact real theory certifies the leaf. Mixed
integer/real completions retain source preparation. Bounded potentials from an earlier
feasible graph seed relaxation, which must still settle every active edge. Fragments
outside graph admission retain the full shared LP solve and its numerical guide.

Graph admission is capped at 10,000 real variables and 100,000 edges. Larger models,
fractional normalized bounds, unsafe Long magnitudes, disequalities, private
disjunctions, Boolean arithmetic beyond shared clauses/cardinalities, and mixed
integer/real models retain the exact LP route. Overridden LP certification policies
retain the LP route because graph evidence has no LP certifier identity. No finite CP
domains are created by this graph.

Complete selected encodings also retain Boolean-only source relations. The shared
Boolean participant enforces its own clauses; difference-component relations outside
that ownership pass an exact Boolean-row check before graph witness publication.
An admitted edge fragment alone cannot authorize omitting that Boolean enforcement.
Source value-set membership remains required for contributed integer coordinates;
a bound-hull point outside a declared set supplies no witness or refutation.
