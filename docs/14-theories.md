# 14. Theory services and difference logic

Part III followed a solve through sessions, presolve, search, local search and
portfolios. Those chapters treated arithmetic as a participant that search
consults. This part opens that participant. A theory service enforces one
arithmetic fragment of the shared source model: search supplies Boolean choices
or arithmetic decisions, and the theory checks their consequences and contributes
authoritative coordinates or conflicts. Frontends select no concrete theory, and
finite propagation keeps Bool+Int state only.

This chapter defines the contract every theory shares and then describes the
smallest theory, difference logic. [Linear arithmetic](15-linear-arithmetic.md) and
[nonlinear arithmetic](16-nonlinear-arithmetic.md) follow in order of increasing
expressiveness. Each theory is described in the same five parts: scope and
boundaries, solving and composition, results and proof authority, state and
resources, and implementation.

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

Difference logic is the smallest fragment, and the linear real theory reuses its
graph. The linear integer and mixed theories add integrality to the same linear
machinery. Nonlinear integer arithmetic fixes integers and asks the polynomial real
theory to complete the rest; the transcendental theory relaxes its functions into
that same polynomial theory. [Input semantics](03-input-semantics.md) states the
current route limits.

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

## Shared composition contract

The [factor and ownership contract](01-introduction.md#high-level-factors-and-composed-reasoning)
allows finite propagation and theory encoding for the same source factor. Schema
compilation, library preparations and format frontends select the same factor
encoders; native nonlinear snapshots already contain theory formulas.

The pipeline selects ownership from the source model. A theory-owned integer is not
materialized as a guessed finite domain, and real coordinates remain
arithmetic-owned. When a model also contains finite factors, each engine projection
keeps the part it can enforce and completion checks the rest. Arithmetic completion
waits for authoritative finite choices before contributing its final point.

[Search](08-search.md) owns decisions, levels, retraction and shared reasons. Its
neutral `SourceBoundAtom` describes exact source-coordinate predicates that each
arithmetic adapter interprets with the same meaning. Registered arithmetic peers use
[coordinate agreement](09-learning.md#arithmetic-coordinate-agreement)
to check one authoritative typed point; unsupported combinations keep combined
ownership or return indeterminate.

[Local search](11-neighborhoods.md#continuous-candidate-completion) requests
completion before publication, and [portfolios](12-portfolio.md) share only
accepted witnesses and justified deductions. A contradiction in a pinned candidate
or repair neighbourhood is not a global refutation.

## Results and proof authority

An exact witness keeps its authoritative integer, rational or algebraic values and
passes the required source checks. Refutation requires a completed proof over the
relevant source scope and alternatives. A relaxation point is not a witness for
omitted integrality or finite factors.

Feasibility support does not imply support for an objective, an attained optimum or
unboundedness; each theory chapter states those limits.
[LP certification](17-linear-programming.md#lp-certification) owns linear numerical evidence. Current
certificates are in-memory;
[independent proof export](01-introduction.md#independently-checkable-proof-files)
is a target capability.

## State and resources

Source descriptions are shared read-only; live query, arithmetic and traversal
state belongs to an invocation or retained worker. Retraction invalidates evidence
whose premises disappear, and published coordinates outlive the producing workspace.
Each theory keeps its own native work units within the
[resource allowances](06-sessions.md#resource-allowances). Cancellation and exhaustion never become
refutation.

## Implementation

[Theory.kt](../klause/src/commonMain/kotlin/com/eignex/klause/theory/Theory.kt) and
[TheorySearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/TheorySearchComponent.kt)
define the batch contract and its search bridge. The encoders in
[theory](../klause/src/commonMain/kotlin/com/eignex/klause/theory) translate source
factors into theory formulas. Each theory lives in its own subpackage:
`theory.difference`, `theory.qflra` (linear real, integer and mixed), `theory.qfnia`,
`theory.qfnra` and `theory.transcendental`.

## Difference logic

Difference logic is the smallest arithmetic fragment. A difference constraint bounds the distance between two coordinates, such as
`x - y <= c`. A graph represents each admitted row as an edge from `y` to `x` with
weight `c`. Feasible potentials supply coordinates; a negative cycle supplies a
contradiction. The [linear real theory](15-linear-arithmetic.md) in the next chapter
reuses this graph for its pure-real difference fragments.

### Scope and boundaries

The open integer route requires complete difference coverage for every relevant
integer factor, including declared bounds and both meanings of reified rows. An
implied difference relaxation alone does not admit a factor. Integer edges are
tightened exactly: a row with a common coefficient `g` floors its bound by `g`, and
a row with no integer solution is left to the general linear path.

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

General pure-real completion can retain a necessary difference subset before the
full LP check. Selected edges keep their signed source guards and immutable
declared bounds; general rows, Boolean coefficient rows, disjunctions and unsupported
disequality directions contribute no edge. This owner exposes checked conflicts
and optional numerical proposals. A feasible subset supplies no positive source
coverage; the [whole-source primal check](15-linear-arithmetic.md#whole-source-point-proposals)
must validate every omitted relation before a proposal can become a witness. Current
guard checks, private-branch restrictions and production certification requirements
remain the same as for complete graph conflicts.

The subset admits at most 10,000 real columns, 100,000 source factors and 65,536
selected edge or sparse units, with 4,096-bit rational admission and charged
normalization. Preparation and sweeps use the current operation and arithmetic
check allowance. Representation declines retain the LP route; containing interruption
withholds all output.

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
