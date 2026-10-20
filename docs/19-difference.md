# 19. Difference logic

Difference logic is the smallest arithmetic fragment and the first theory in this
part. A difference constraint bounds the distance between two coordinates, such as
`x - y <= c`. A graph represents each admitted row as an edge from `y` to `x` with
weight `c`. Feasible potentials supply coordinates; a negative cycle supplies a
contradiction. The [linear real theory](20-linear-real.md) in the next chapter
reuses this graph for its pure-real difference fragments.

The theory has integer and real uses within the common
[theory service contract](18-theories.md). It does not create finite domains for
open variables.

## Scope and boundaries

The open integer route requires complete difference coverage for every relevant
integer factor, including declared bounds and both meanings of reified rows.
Numerical safety is part of admission. An implied difference relaxation alone does
not establish that a whole factor belongs to the fragment. Integer edges are
tightened exactly: a row with a common coefficient `g` floors its bound by `g`,
and a row with no integer solution is left to the general linear path.

SMT-LIB lowering represents a reified unit-coefficient integer difference equality
as the conjunction of its lower and upper comparisons; disequality is the negation
of that conjunction. Each comparison keeps both signed guarded meanings, so the
equality's false branch is covered. General linear and wide integer equalities keep
their direct reified factors.

Pure-real difference rows are handled inside the linear real component when they
meet its graph admission conditions. Other linear rows keep the
[linear real](20-linear-real.md), [linear integer](21-linear-integer.md) or
[linear mixed](22-linear-mixed.md) route. The theory graph and the incremental
finite-domain difference propagator have separate state owners.

## Solving and composition

Shared search selects Boolean alternatives and passes their active guarded edges
and integer bound facts to the component. Bellman-Ford relaxation from a virtual
source computes potentials over the active edges; a relaxation that would overflow
is not treated as an improvement, so a wrapped sum cannot invent a cycle. Graph
checks can detect conflicts before a complete Boolean assignment. Complete checks
require settled guards and supply coordinates for the variables this theory owns.

### Guard propagation

The open integer component refutes unassigned comparison guards from active source
paths, following Cotton and Maler's difference-constraint propagation. A guarded
edge is impossible when an existing path closes a negative cycle through it. Path
queries run Dijkstra over reduced weights `w(e) + p(u) - p(v)`, which are
non-negative under a feasible potential `p`. The clause-form reason contains the
negated guard and every active path guard; immutable source rows and declared bounds
need no Boolean premise. A contradictory member refutes its entire signed row group,
whereas satisfying one member supplies no entailment.

Transient published integer bounds take part in feasibility checks but are excluded
from proof paths. A sweep processes at most 64 open heads; deferred heads remain
searchable. Guard activity changes and retraction invalidate completed query
coverage, and no reduced distance is interpreted under a different potential. The
stateless path operation lives in `arithmetic.difference` with caller-owned
activity, potentials and scratch; finite incremental repair and trail reconciliation
remain owned by propagation.

### Relative intervals

An entirely theory-owned integer graph can branch on small directly proved relative
intervals before its ordinary Boolean choices. Opposite unconditional source edges
bound `x-y` without bounding either coordinate absolutely. Narrower intervals come
first. Each split registers `x-y <= c` and `y-x <= -c-1` as exclusive, exhaustive
integer alternatives through the shared atom registry; no finite CP domain or fixed
anchor is introduced. Retraction restores the active interval from the shared
Boolean trail. Once the Boolean skeleton is settled, graph feasibility completes the
arithmetic without further relative decisions.

### Polarity guidance

A completed partial potential can guide which polarity of a comparison guard search
tries first. The default integer pipeline installs no such provider; an explicitly
requested one uses the potential only when every edge of the signed guard agrees.
Nested conjunction and De Morgan disjunction definitions extend the preference to
compound gates. A separate numerical seed keeps the last completed potential so the
next sweep can start from it. Neither the preference nor the seed supplies a fact,
explanation, model or verdict, and both are discarded on initialization, retraction
or a changed node set.

### Real graph admission and checks

Finite preparation installs an optional redundant graph from real-only `ExactLinear`
rows with one term or two opposite coefficients, and from `RealElement` selections.
Wide coefficients, disequalities and other unsupported rows are omitted. Both signs
of a reified inequality keep their exact strictness; a reified equality contributes
only its positive conjunction. Declared finite real bounds are immutable premises.
Omission weakens deduction without changing the source model or its mandatory
complete checks. The incremental propagator owns per-state atoms, potentials and
reasons, and can publish conflicts and exclusions, never source real coordinates.

The real component inside the linear theory uses the graph when every arithmetic
row is a one-column bound or a two-column difference with a rational bound. Strict
edges are encoded exactly: preparation clears denominators with a common
denominator `d`, scales bounds by `(n+1)*d` for `n` vertices, and subtracts one
from each strict edge. That preserves the sign of every simple cycle, including
zero-weight strict cycles, and potentials divided by `(n+1)*d` give rational
witnesses that still pass the complete source-row check. A verified negative
predecessor cycle supplies a conflict clause containing the complements of its
active signed guards.

Under the production certification policy, admitted pure-real components defer LP
construction and check the graph at partial and complete Boolean states, including
the root. Missing cycle evidence, declined source validation, private source-bound
branches and overridden certification policies fall back to the shared LP route.
Late LP installation reaches the current decision scope before asserting resolved
rows, so retraction removes their guards and witness premises together.

Graph admission is capped at 10,000 real variables and 100,000 edges. Larger models,
weights outside the safe `Long` range, disequalities, private disjunctions, Boolean
arithmetic beyond shared clauses and cardinalities, and mixed integer/real models
keep the exact LP route.

## Results and proof authority

Completed graph feasibility supplies an integer potential sample or a rational real
assignment. A captured, verified negative cycle justifies a conflict; usable Boolean
guards are negated into a shared explanation. Missing explanation coverage keeps
chronological conflict handling rather than inventing a clause. A stopped graph
check is indeterminate.

Integer cycle explanations negate every active signed guard in a checked cycle of
immutable source edges and registered predicates. A cycle containing a transient
shared integer bound keeps chronological conflict handling, because that bound has
no captured Boolean reason. Boolean-only source relations stay enforced by the
shared Boolean participant; an admitted edge fragment cannot authorize omitting
them. Source value-set membership remains required for contributed integer
coordinates.

Objective admission is separate from graph feasibility. The open minimizer must
keep every improving cutoff within a supported theory; an arbitrary linear objective
can leave the difference fragment. [Input semantics](04-input-semantics.md)
qualifies the route's published result.

## State and resources

Immutable edges and fragment descriptions belong to
[arithmetic.difference](../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference).
Each search component owns its active facts and captured assignment; retraction
invalidates the assignment. Graph operations poll the caller's cancellation and
charge its meter. A stop discards query scratch without keeping cancellation across
later calls; [resource allowances](11-resources.md) defines the boundaries.

Private representations are bounded: registered edges and hint indexes share a
65,536-entry envelope, and intervals wider than 255 keep ordinary graph search.
Reaching a bound keeps ordinary Boolean search over the current valid scope rather
than inventing a bounded model. Denominator and weight preparation for real graphs
charges the current arithmetic operation; a stopped preparation publishes no graph.
Closing a search discards interval splits, private graph extensions and seeds.

## Implementation

[DifferenceGraph](../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference/DifferenceGraph.kt)
computes potentials and negative cycles;
[DifferencePaths](../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference/DifferencePaths.kt)
runs the reduced-weight path queries.
[DifferenceTheorySolver](../klause/src/commonMain/kotlin/com/eignex/klause/theory/difference/DifferenceTheorySolver.kt)
checks complete Boolean assignments.
[DifferenceSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/difference/DifferenceSearchComponent.kt)
participates in shared traversal and publishes applicable root bounds.
[DifferenceFragments](../klause/src/commonMain/kotlin/com/eignex/klause/solver/DifferenceFragments.kt)
defines source coverage and numerical admission.
[RealDifferenceSystem](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/RealDifferenceSystem.kt)
is the real graph used inside the linear component.

---

Previous: [18. Theory services](18-theories.md) · [Contents](README.md) · Next: [20. Linear real arithmetic](20-linear-real.md)
