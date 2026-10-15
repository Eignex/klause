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

SMT-LIB lowering represents reified unit-coefficient pairwise integer
differences with equality as a conjunction of their lower and upper
comparisons. Disequality is the negation of that conjunction. Each comparison
retains both signed guarded meanings, allowing complete difference coverage
without dropping the equality's false branch. General linear and wide integer
equalities and unary bounds retain their direct reified factors; graph numerical admission still
applies to the emitted bounds.

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

The open integer component also refutes unassigned comparison guards from active
source paths. A guarded edge is impossible when an existing path closes a checked
negative cycle through it. Its clause-form reason includes the negated guard and
every active path guard; immutable source rows and declared bounds need no Boolean
premise. A contradictory member refutes its entire signed row group, whereas
satisfying one member supplies no entailment. Shared source clauses propagate these
primitive facts through equality and disequality gates.

This optional closure uses only immutable base edges. Transient published integer
bounds remain in ordinary feasibility checks but are excluded from proof paths.
The existing completed cycle sweep supplies a feasible numerical reweighting;
a point influenced by transient bounds gives those bounds no explanation authority.
Sparse shortest-path queries charge the containing token and validate original path
edges and overflow-free sums before publishing. A sweep processes at most 64 open
heads. Deferred heads remain searchable, and representation decline retains complete
checks. Guard activity changes and retraction invalidate completed query coverage;
no reduced distance is interpreted using a different potential. A published guard
ends the sweep until the shared fixpoint reconciles its new activity.

The stateless path operation lives in `arithmetic.difference`, with caller-owned
activity, potentials and scratch. Finite incremental repair, trail reconciliation
and cached zero routes remain owned by propagation. Open component state remains
private to its search opening. Abandonment exports no path or implication and
renews neither an allowance nor a deadline.

The default integer pipeline retains its ordinary Boolean polarity selection and
installs no difference-point hint provider. An explicitly requested internal provider
can retain a feasible potential from the same completed partial cycle sweep.
It uses that point only to prefer a Boolean comparison's first polarity; every
edge belonging to that signed guard must agree. Checked arithmetic
overflow, interruption or missing signed coverage supplies no preference. The point
is private to the current completed fixpoint and is invalidated by initialization,
propagation and retraction. A changed decision level or stopped invocation also
withholds it.

A separate numerical seed retains the last completed partial potential and its
exact sorted source-node identities. A following feasibility sweep may repair
that seed against every current active edge, including transient bounds. Retraction
can retain this arithmetic start while withholding hints and cached proof paths
until a fresh completed fixpoint. Changed node identities or overflowing repair
discard the seed and retain the zero-start check under the same live owner. A
containing stop supplies no fallback, point or conflict. Root initialization and
opening cleanup discard the seed. It carries no assignment or explanation
authority, and complete source witness checking remains independent.

An explicitly composed internal adapter recognizes complete conjunction definitions
with two or more inputs through every implication pair and the full reverse clause. Nested
signed definitions extend primitive comparison preferences through conjunctions
and De Morgan disjunctions. Missing leaves, ambiguous or cyclic definitions supply
no compound preference. Sparse dependency values are private to one charged
consultation. Earlier source gates can therefore use the same potential as their
comparison leaves. Caller and deferred hints retain precedence. Source variable
order and both complementary alternatives remain intact; a potential or derived
gate preference never supplies a fact, explanation, model or verdict.
An available direct comparison retains its primitive preference. Remaining declared
Boolean and compound choices can use the semantic-clause guidance in
[shared search](../solver/search.md).

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
Optional guard propagation batches unmetered external polls within one operation,
checks native exhaustion on every primitive charge, and forces live polls before
allocation and proof publication. Metered tokens keep eager polling. A stop discards
query scratch and resets the origin cursor without retaining cancellation across
later calls; [resource allowances](../solver/resources.md) defines the boundaries.
Representation or safety declines retain another admitted route or an undecided
result, rather than an invented bounded model.

Explicitly requested hint storage admits at most 65,536 edge incidences and source
factor/index entries. These are private index representation bounds; declining an index retains
ordinary graph initialization and complete coverage. Index and preference visits
poll the invocation and charge its attached work meter. Capturing a point performs
no second graph solve, creates no allowance and does not change the open-policy
decision/check currency. The caller owns an explicitly composed adapter; closing the
planned search releases its borrowed component point and private arithmetic seed.

## Implementation

[DifferenceTheorySolver](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/difference/DifferenceTheorySolver.kt)
checks complete Boolean assignments.
[DifferenceSearchComponent](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/difference/DifferenceSearchComponent.kt)
participates in shared traversal and publishes applicable root bounds.
[DifferenceFragments](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/DifferenceFragments.kt)
defines source coverage and numerical admission.

## Real graph admission and checks

The real difference component uses a difference graph when every arithmetic row is a
one-column bound or a two-column difference with a rational normalized bound. Boolean
guards select active edges. Preparation clears denominators using a checked positive
common denominator `d`. With `n` graph vertices, scaling bounds by `(n+1)*d` and
subtracting one from strict edges preserves every simple cycle's sign, including
zero-weight strict cycles. Potentials divided by `(n+1)*d` supply rational witnesses
that still pass the complete source-row check. A verified negative predecessor cycle supplies a
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
guide or a model contribution. With default primitive guidance, the first live
consultation after this admission supplies no preference and releases optional hint
rows and values. Registration and all capture work already spent remain charged; later checks
skip released capture in the same opening. An unavailable point or a caller preference
that bypasses the component does not trigger release. Explicit hint allowances retain
their existing graph-point preferences. This policy changes neither graph admission
nor the source candidate, graph state or private-branch LP fallback. Complete graph
points still pass the complete source-row check before publication. Missing cycle
evidence, declined source validation and private
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

Denominator retention, common-denominator scans, Euclidean steps and weight preparation
charge and poll the current arithmetic operation token. The original exact row
normalization remains atomic; these charges do not meter every big-integer instruction
or renew an allowance. A stopped preparation publishes no graph, and a stopped graph
check remains interrupted.

Graph admission is capped at 10,000 real variables and 100,000 edges. Larger models,
denominators, denominator multiples or scaled weights outside the safe Long representation,
disequalities, private disjunctions, Boolean arithmetic beyond shared clauses/cardinalities, and mixed
integer/real models retain the exact LP route. Overridden LP certification policies
retain the LP route because graph evidence has no LP certifier identity. No finite CP
domains are created by this graph.

Complete selected encodings also retain Boolean-only source relations. The shared
Boolean participant enforces its own clauses; difference-component relations outside
that ownership pass an exact Boolean-row check before graph witness publication.
An admitted edge fragment alone cannot authorize omitting that Boolean enforcement.
Source value-set membership remains required for contributed integer coordinates;
a bound-hull point outside a declared set supplies no witness or refutation.
