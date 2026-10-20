# 14. Search and propagation

This chapter describes the first of the engines that run on a prepared model:
complete search. It explores alternatives, restores state when a branch fails and
checks complete assignments with the [source validation](13-validation.md) service
before publication. Finite propagation narrows Boolean and integer domains, and
arithmetic components enforce the parts of the source model they own. Learning
reuses conflicts only when their explanations remain valid in that scope.

The traversal loop is shared by every complete strategy: establish a fixpoint,
check or choose a decision, apply it, and retract to another alternative after a
conflict. Exhausting alternatives supports a refutation only if all required checks
completed; interruption leaves the unexplored space undecided.

## Shared component search

[solver.search](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search)
owns decisions, levels, branching, retraction, complete checks, restarts and model
assembly. CP and theory components contribute residual state and reasons through the
same `SearchComponent` lifecycle. Batch `Theory` services participate through
`TheorySearchComponent`; directly incremental arithmetic components use the lifecycle
without a batch service. Participation supplies no completeness guarantee:
indeterminate required checks prevent exhaustion from becoming a source refutation.
See [theory roles](18-theories.md#extension-roles-and-completeness). The session
exchanges Boolean literals, semantic integer bounds and opaque theory decisions
rather than native CP domains.

Four owners divide the work:

- [SearchSession](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
  coordinates component callbacks, conflicts and complete checks.
- [SearchTrail](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchTrail.kt)
  owns decisions, Boolean values, reasons and publishers, integer facts, undo levels
  and pending assertion delivery.
- [SearchLearning](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchLearning.kt)
  owns shared clauses, attachment and propagation cursors, unit reawakening and
  reduction. Clause identifiers and watch-list order remain stable across index
  growth and rebuilds.
- [SearchRun](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchRun.kt)
  contains the single traversal loop and owns frames, decisions, restarts,
  cancellation cadence and resumable/terminal state.

Retraction removes trail facts and restores integer premises before clamping the
learned propagation cursor and notifying components. Root rebuild clears facts while
retaining learned clauses and reawakening units. Native CP clause state remains
CP-owned.

Components obtain the invocation token, native allowance accounting and typed stop
diagnostics from `SearchContext.executionControls`; alternate coordinators supply
those services through the same contract. [Resource allowances](11-resources.md#component-execution-controls)
owns their units, cancellation scopes and diagnostic precedence. Internal components
with retained root preparation implement `PreparingSearchComponent`: their cursor
returns no verdict until preparation completes, a suspended cursor does not enter
propagation, branching or model assembly, and closing discards it.
[Portfolio preparation](16.2-portfolio-scheduling.md#polynomial-root-preparation) defines the
distinction between slice suspension and solve-wide cancellation.

## Chapter outline

The subchapters follow one pass of the traversal loop, from choosing a decision to
publishing a model:

1. [Traversal and branching](14.1-traversal.md): how decisions are proposed and
   ordered, how source bound atoms name arithmetic splits, and how components are
   registered and assembled into a session.
2. [Finite propagation](14.2-propagation.md): the finite-domain fixpoint, selected
   propagation projections, branching heuristics fed by propagation, and the checks a
   complete finite point must pass.
3. [Explanations and conflict learning](14.3-learning.md): reasons for deductions,
   first-UIP analysis, the native SAT lane, pseudo-Boolean learning, learned-clause
   retention, restarts and inprocessing.
4. [Theory completion](14.4-theory-completion.md): how arithmetic owners complete or
   refute the coordinates finite search leaves open, agree on shared coordinates and
   contribute typed model values.
5. [Primal heuristics and statistics](14.5-primal-and-statistics.md): root primal
   proposals, the monotone clausal probe, and the counters search reports.

---

Previous: [13. Source witness validation](13-validation.md) · [Contents](README.md) · Next: [14.1 Traversal and branching](14.1-traversal.md)
