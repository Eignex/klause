# 19. Difference logic

Difference logic is the smallest arithmetic fragment and the first theory in this
part. A difference constraint bounds the distance between two coordinates, such as
`x - y <= c`. A graph represents each admitted row as an edge from `y` to `x` with
weight `c`. Feasible potentials supply coordinates; a negative cycle supplies a
contradiction. The [linear real theory](20-linear-real.md) in the next chapter
reuses this graph for its pure-real difference fragments.

## Scope and boundaries

The open integer route requires complete difference coverage for every relevant
integer factor, including declared bounds and both meanings of reified rows. An
implied difference relaxation alone does not admit a factor. Integer edges are
tightened exactly: a row with a common coefficient `g` floors its bound by `g`, and
a row with no integer solution is left to the general linear path.

SMT-LIB lowering represents a reified unit-coefficient integer difference equality
as the conjunction of its two comparisons, each keeping both signed guarded
meanings. Pure-real difference rows are handled inside the linear real component
when they meet its graph admission conditions; other linear rows keep the
[linear real](20-linear-real.md), [linear integer](21-linear-integer.md) or
[linear mixed](22-linear-mixed.md) route. The theory graph and the finite-domain
difference propagator have separate state.

## Solving and composition

Shared search selects Boolean alternatives and passes their active guarded edges
and integer bound facts to the component. Bellman-Ford relaxation from a virtual
source computes potentials; a relaxation that would overflow is not treated as an
improvement, so a wrapped sum cannot invent a cycle. Graph checks can detect
conflicts before a complete Boolean assignment.

### Guard propagation

The open integer component refutes unassigned comparison guards from active source
paths, following Cotton and Maler's difference-constraint propagation. A guarded
edge is impossible when an existing path closes a negative cycle through it. Path
queries run Dijkstra over reduced weights `w(e) + p(u) - p(v)`, which are
non-negative under a feasible potential `p`. The reason clause contains the negated
guard and every active path guard; source rows and declared bounds need no premise.

Transient published integer bounds take part in feasibility checks but are excluded
from proof paths. A sweep processes at most 64 open heads; deferred heads remain
searchable. Activity changes and retraction invalidate completed query coverage.

### Relative intervals

An entirely theory-owned integer graph can branch on small directly proved relative
intervals before its ordinary Boolean choices. Opposite unconditional source edges
bound `x-y` without bounding either coordinate absolutely. Each split registers
`x-y <= c` and `y-x <= -c-1` as exclusive, exhaustive alternatives through the
shared atom registry; no finite domain or fixed anchor is introduced.

### Polarity guidance

A completed partial potential can guide which polarity of a comparison guard search
tries first. The default integer pipeline installs no such provider. A numerical
seed keeps the last completed potential for the next sweep. Neither supplies a
fact, explanation or verdict.

### Real graph admission and checks

The real component inside the linear theory uses the graph when every arithmetic
row is a one-column bound or a two-column difference with a rational bound. Strict
edges are encoded exactly: preparation clears denominators with a common
denominator `d`, scales bounds by `(n+1)*d` for `n` vertices, and subtracts one
from each strict edge. That preserves the sign of every simple cycle, including
zero-weight strict cycles, and the scaled-back potentials are rational witnesses
that pass the complete source-row check. A verified negative cycle supplies a
conflict clause of the complements of its active guards.

Admitted pure-real components defer LP construction and check the graph at partial
and complete Boolean states. Missing cycle evidence, declined source validation and
private source-bound branches fall back to the shared LP route. Admission is capped
at 10,000 real variables and 100,000 edges; larger models, unsafe weights,
disequalities and mixed integer/real models keep the exact LP route.

Finite preparation can also install a redundant real graph from one-term and
two-opposite-coefficient `ExactLinear` rows and `RealElement` selections. It can
publish conflicts and exclusions, never source real coordinates.

## Results and proof authority

Completed graph feasibility supplies an integer potential sample or a rational real
assignment. A verified negative cycle justifies a conflict whose explanation negates
every active signed guard. A cycle containing a transient shared integer bound has
no captured reason, so it keeps chronological conflict handling. A stopped graph
check is indeterminate. Boolean-only source relations stay enforced by the shared
Boolean participant.

Objective admission is separate from graph feasibility: every improving cutoff must
stay within a supported theory, and an arbitrary linear objective can leave the
difference fragment.

## State and resources

Immutable edges and fragment descriptions belong to
[arithmetic.difference](../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference).
Each search component owns its active facts and captured assignment. Graph
operations poll the caller's cancellation and charge its meter.

Private representations are bounded: registered edges and hint indexes share a
65,536-entry envelope, and intervals wider than 255 keep ordinary graph search.
Reaching a bound keeps ordinary Boolean search rather than inventing a bounded
model.

## Implementation

[DifferenceGraph](../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference/DifferenceGraph.kt)
computes potentials and negative cycles;
[DifferencePaths](../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference/DifferencePaths.kt)
runs the reduced-weight path queries.
[DifferenceSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/difference/DifferenceSearchComponent.kt)
participates in shared traversal, and
[DifferenceFragments](../klause/src/commonMain/kotlin/com/eignex/klause/solver/DifferenceFragments.kt)
defines source coverage and numerical admission.
[RealDifferenceSystem](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/RealDifferenceSystem.kt)
is the real graph used inside the linear component.

---

Previous: [18. Theory services](18-theories.md) · [Contents](README.md) · Next: [20. Linear real arithmetic](20-linear-real.md)
