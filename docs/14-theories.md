# 14. Theory services and difference logic

A theory enforces an arithmetic fragment under shared search decisions and contributes
coordinates or conflicts. The common contract below applies to difference, linear,
polynomial, nonlinear-integer and transcendental services. Finite propagation keeps
Boolean/integer domains; arithmetic components own open integers and reals.

## Theory directory

| Theory | Source fragment | Main mechanism |
|---|---|---|
| [Difference logic](#difference-logic) | Integer or real difference constraints with Boolean guards | Graph potentials and negative cycles |
| [Linear real arithmetic](15-linear-arithmetic.md) | Linear real constraints with Boolean structure | Exact rational LP checks; the difference graph for admitted fragments |
| [Linear integer arithmetic](15-linear-arithmetic.md#linear-integer-arithmetic) | Linear integer constraints with Boolean structure | Linear real checks plus integer reductions and branching |
| [Linear mixed arithmetic](15-linear-arithmetic.md#linear-mixed-arithmetic) | Coupled linear integer/real constraints | The integer route with real completion and point recovery |
| [Nonlinear integer arithmetic](16-nonlinear-arithmetic.md) | Polynomial Int/Real/Bool satisfaction | Incomplete integer search with exact polynomial real completion |
| [Polynomial real arithmetic](16-nonlinear-arithmetic.md#polynomial-real-arithmetic) | Polynomial real constraints, optionally beside finite factors | Exact algebraic completion and cylindrical algebraic decomposition |
| [Transcendental real arithmetic](16-nonlinear-arithmetic.md#transcendental-real-arithmetic) | exp/log/sin/cos constraints with mixed declarations | Certified enclosures, polynomial relaxation and symbolic checks |

Linear integer and mixed services add integrality to the real-linear machinery.
Nonlinear integer search fixes candidates for polynomial real completion;
transcendental reasoning weakens functions into polynomial constraints.
[Input semantics](03-input-semantics.md#solving-coverage) records route limits.

## Extension roles and completeness

`Theory<A>` checks one source fragment in a batch under a complete Boolean
assignment and active semantic bounds. `TheorySearchComponent<A>` bridges that
service into shared decisions, propagation, complete checks and retraction. A
decided batch answer is cached only while its conditions remain active.

`SearchComponent` is the common incremental participant contract for finite
propagation and arithmetic; incremental arithmetic need not implement `Theory`.
Optional branching, blocking and conflict capabilities extend that lifecycle.

`EncoderProvider` selects immutable whole-factor installers for a consumer's
`EncodingTarget`. `EncoderSupport` distinguishes direct, equivalent, conditioned,
relaxation-only and unsupported representations. `TheoryEncoding` supplies typed
source coordinates, original factor attribution and the receiving allowance.
Prepared relations are combined within one arithmetic owner before its checks.
[Provider extensions](19-api.md#projection-provider-extensions) define the public
obligations.

Installation follows four rules:

- **Preflight before allocation.** A request checks its entire ordered source scope
  before allocating a receiver. Unsupported and relaxation-only descriptors decline
  enforcement; an ineligible whole source does not suppress a separately admitted
  connected subset.
- **One charged scope.** Admission charges the containing work meter. A stop is
  latched for that preparation and withholds its fragments; a new scope cannot
  refresh the allowance.
- **No partial relations.** A failed or interrupted installation publishes no
  enforcing relation, and relaxation support cannot substitute for enforcement.
  Retracting a conditioning pin invalidates its derived state and witness.
- **Private definitions stay private.** Private existential real coordinates,
  affine values and Boolean formula names are scope-owned, exactly defined and
  omitted from source publication.

A linear consumer can complete an integer-conditioned encoder by covering every
value of its required finite declarations, keeping the integer guards on each
relation; the cover is capped at 4096 cases. Within one planning call, a completed
full-source linear or difference installation can also supply the other target's
fragment, sharing immutable relations but never mutable state.

Participation and completeness are separate. A batch service can be sound and
incomplete; `TheoryCheck.Cancelled` leaves the fragment undecided, and an
indeterminate required leaf cannot justify source exhaustion.

Relations installed by one linear encoding request share one immutable snapshot
of its complete integer and Boolean conditions. The snapshot owns its maps, and
attribution access returns defensive copies. Sharing conditions preserves every
premise without copying the entire assignment for each installed factor.

## Shared composition contract

[Prepared ownership](02-model.md#factor-projection-selection) lets propagation and
encoding consume the same factor while retaining one primary coordinate owner.
Arithmetic completion waits for authoritative finite choices. Native nonlinear
snapshots already contain their formulas.

[Shared search](08-search.md) owns decisions, levels and retraction. Neutral
`SourceBoundAtom` predicates mean the same thing in every adapter; multiple
arithmetic groups use [coordinate agreement](09-learning.md#arithmetic-coordinate-agreement)
to check one contributed point. [Heuristic completion](11-neighborhoods.md#continuous-candidate-completion)
checks candidate restrictions without giving their refutations global authority.

## Results and proof authority

Each theory states its feasibility and objective authority separately. Witnesses
retain authoritative exact values; proofs must cover their invocation and every
required alternative. [Result guarantees](01-introduction.md#results-and-completeness),
[source validation](07-presolve.md#source-witness-validation) and
[LP certification](17-linear-programming.md#lp-certification) define shared checks.

## State and resources

Live assertions, arithmetic state and traversal belong to an invocation or worker;
source snapshots are read-only and published values outlive the workspace.
Retraction invalidates conclusions whose premises disappear. Native work spends the
containing [resource allowance](06-sessions.md#resource-allowances).

## Implementation

[Theory.kt](../klause/src/commonMain/kotlin/com/eignex/klause/theory/Theory.kt) and
[TheorySearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/TheorySearchComponent.kt)
define the batch contract and its search bridge. The encoders in
[theory](../klause/src/commonMain/kotlin/com/eignex/klause/theory) translate source
factors into theory formulas. Each theory lives in its own subpackage:
`theory.difference`, `theory.qflra` (linear real, integer and mixed), `theory.qfnia`,
`theory.qfnra` and `theory.transcendental`.

## Difference logic

A difference constraint `x - y <= c` is an edge from `y` to `x` of weight `c`.
Feasible graph potentials supply coordinates and negative cycles supply conflicts.
The linear real component reuses the graph for admitted real difference fragments.

### Scope and boundaries

The open integer route requires complete difference coverage for every relevant
integer factor, including declared bounds and both meanings of reified rows. An
implied difference relaxation alone does not admit a factor. Integer edges are
tightened exactly: a row with a common coefficient `g` floors its bound by `g`, and
a row with no integer solution is left to the general linear path.
Coefficient magnitudes and reversed bounds must fit the graph's signed integer
range; otherwise the row stays on the general linear path.

SMT-LIB lowering represents a reified unit-coefficient integer difference equality
as the conjunction of its two comparisons, each keeping both signed guarded
meanings. Pure-real difference rows are handled inside the linear real component
when they meet its graph admission conditions; other linear rows keep the
[linear real](15-linear-arithmetic.md), [linear integer](15-linear-arithmetic.md#linear-integer-arithmetic) or
[linear mixed](15-linear-arithmetic.md#linear-mixed-arithmetic) route. The theory graph and the finite-domain
difference propagator have separate state.

### Solving and composition

Shared search selects Boolean alternatives and passes their active guarded edges
and integer bound facts to the component. Bellman-Ford relaxation from a virtual
source computes potentials; a relaxation that would overflow is not treated as an
improvement, so a wrapped sum cannot invent a cycle. Graph checks can detect
conflicts before a complete Boolean assignment.

#### Guard propagation

The open integer component refutes unassigned comparison guards from active source
paths, following Cotton and Maler's difference-constraint propagation. A guarded
edge is impossible when an existing path closes a negative cycle through it. Path
queries run Dijkstra over reduced weights `w(e) + p(u) - p(v)`, which are
non-negative under a feasible potential `p`. The reason clause contains the negated
guard and every active path guard; source rows and declared bounds need no premise.

Transient published integer bounds take part in feasibility checks but are excluded
from proof paths. A sweep processes at most 64 open heads; deferred heads remain
searchable. Activity changes and retraction invalidate completed query coverage.

#### Relative intervals

An entirely theory-owned integer graph can branch on small directly proved relative
intervals before its ordinary Boolean choices. Opposite unconditional source edges
bound `x-y` without bounding either coordinate absolutely. Each split registers
`x-y <= c` and `y-x <= -c-1` as exclusive, exhaustive alternatives through the
shared atom registry; no finite domain or fixed anchor is introduced.

#### Polarity guidance

A completed partial potential can guide which polarity of a comparison guard search
tries first. The default integer pipeline installs no such provider. A numerical
seed keeps the last completed potential for the next sweep. Neither supplies a
fact, explanation or verdict.

#### Real graph admission and checks

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

### Results and proof authority

Completed graph feasibility supplies an integer potential sample or a rational real
assignment. A verified negative cycle justifies a conflict whose explanation negates
every active signed guard. A cycle containing a transient shared integer bound has
no captured reason, so it keeps chronological conflict handling. A stopped graph
check is indeterminate. Boolean-only source relations stay enforced by the shared
Boolean participant.

Objective admission is separate from graph feasibility: every improving cutoff must
stay within a supported theory, and an arbitrary linear objective can leave the
difference fragment.

### State and resources

Immutable edges and fragment descriptions belong to
[arithmetic.difference](../klause/src/commonMain/kotlin/com/eignex/klause/arithmetic/difference).
Each search component owns its active facts and captured assignment. Graph
operations poll the caller's cancellation and charge its meter.

Private representations are bounded: registered edges and hint indexes share a
65,536-entry envelope, and intervals wider than 255 keep ordinary graph search.
Reaching a bound keeps ordinary Boolean search rather than inventing a bounded
model.

### Implementation

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

Previous: [13. Original-source derivations](13-evidence.md) · [Contents](README.md) · Next: [15. Linear arithmetic](15-linear-arithmetic.md)
