# 18. Theory services

Part III followed a solve through sessions, presolve, search, local search and
portfolios. Those chapters treated arithmetic as a participant that search
consults. This part opens that participant. A theory service enforces one
arithmetic fragment of the shared source model: search supplies Boolean choices
or arithmetic decisions, and the theory checks their consequences and contributes
authoritative coordinates or conflicts. Frontends select no concrete theory, and
finite propagation keeps Bool+Int state only.

This chapter defines the contract every theory shares. The chapters after it
describe the theories in order of increasing expressiveness, each building on the
ones before it. Every theory chapter uses the same five sections: scope and
boundaries, solving and composition, results and proof authority, state and
resources, and implementation.

## Theory directory

| Chapter | Source fragment | Main mechanism |
|---|---|---|
| [19. Difference logic](19-difference.md) | Integer or real difference constraints with Boolean guards | Graph potentials and negative cycles |
| [20. Linear real arithmetic](20-linear-real.md) | Linear real constraints with Boolean structure | Exact rational LP checks; the difference graph for admitted fragments |
| [21. Linear integer arithmetic](21-linear-integer.md) | Linear integer constraints with Boolean structure | Linear real checks plus integer reductions and branching |
| [22. Linear mixed arithmetic](22-linear-mixed.md) | Coupled linear integer/real constraints | The integer route with real completion and point recovery |
| [23. Nonlinear integer arithmetic](23-nonlinear-integer.md) | Polynomial Int/Real/Bool satisfaction | Incomplete integer search with exact polynomial real completion |
| [24. Polynomial real arithmetic](24-polynomial-real.md) | Polynomial real constraints, optionally beside finite factors | Exact algebraic completion and cylindrical algebraic decomposition |
| [25. Transcendental real arithmetic](25-transcendental.md) | exp/log/sin/cos constraints with mixed declarations | Certified enclosures, polynomial relaxation and symbolic checks |

Difference logic is the smallest fragment, and the linear real theory reuses its
graph. The linear integer and mixed theories add integrality to the same linear
machinery. Nonlinear integer arithmetic fixes integers and asks the polynomial real
theory to complete the rest; the transcendental theory relaxes its functions into
that same polynomial theory. [Input semantics](04-input-semantics.md) states the
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
[Provider extensions](30-api.md#projection-provider-extensions) define the public
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

[Search](14-search.md) owns decisions, levels, retraction and shared reasons. Its
neutral `SourceBoundAtom` describes exact source-coordinate predicates that each
arithmetic adapter interprets with the same meaning. Registered arithmetic peers use
[coordinate agreement](14.4-theory-completion.md#arithmetic-coordinate-agreement)
to check one authoritative typed point; unsupported combinations keep combined
ownership or return indeterminate.

[Local search](15.4-lifetime.md#continuous-candidate-completion) requests
completion before publication, and [portfolios](16-portfolio.md) share only
accepted witnesses and justified deductions. A contradiction in a pinned candidate
or repair neighbourhood is not a global refutation.

## Results and proof authority

An exact witness keeps its authoritative integer, rational or algebraic values and
passes the required source checks. Refutation requires a completed proof over the
relevant source scope and alternatives. A relaxation point is not a witness for
omitted integrality or finite factors.

Feasibility support does not imply support for an objective, an attained optimum or
unboundedness; each theory chapter states those limits.
[LP certification](27-lp-certification.md) owns linear numerical evidence. Current
certificates are in-memory;
[independent proof export](01-introduction.md#independently-checkable-proof-files)
is a target capability.

## State and resources

Source descriptions are shared read-only; live query, arithmetic and traversal
state belongs to an invocation or retained worker. Retraction invalidates evidence
whose premises disappear, and published coordinates outlive the producing workspace.
Each theory keeps its own native work units within the
[resource allowances](11-resources.md). Cancellation and exhaustion never become
refutation.

## Implementation

[Theory.kt](../klause/src/commonMain/kotlin/com/eignex/klause/theory/Theory.kt) and
[TheorySearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/TheorySearchComponent.kt)
define the batch contract and its search bridge. The encoders in
[theory](../klause/src/commonMain/kotlin/com/eignex/klause/theory) translate source
factors into theory formulas. Each theory lives in its own subpackage:
`theory.difference`, `theory.qflra` (linear real, integer and mixed), `theory.qfnia`,
`theory.qfnra` and `theory.transcendental`.

---

Previous: [17. Original-source derivations](17-evidence.md) · [Contents](README.md) · Next: [19. Difference logic](19-difference.md)
