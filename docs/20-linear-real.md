# 20. Linear real arithmetic

Linear real constraints combine real coordinates with constant coefficients and
Boolean structure. They generalize the difference rows of the
[previous chapter](19-difference.md) to arbitrary rational coefficients. Exact
rational checks decide the active linear system, and shared search explores the
Boolean alternatives. Strict inequalities require an interior point, not a point
on the closure's boundary.

This chapter introduces the linear component that the integer and mixed theories in
the next two chapters extend.

## Scope and boundaries

The pure-real route requires real variables, no source integer variables, and
factors wholly represented by admitted exact linear forms. Source bounds,
reification, disjunction and strictness remain authoritative. Nonconstant real
products belong to [polynomial real arithmetic](24-polynomial-real.md).
[Difference logic](19-difference.md#real-graph-admission-and-checks) handles an
admitted pure-real subfragment; a graph decline does not reject a supported
linear source.

## Solving and composition

The linear component sits on two lifecycles at once: the
[search session](14-search.md) owns Boolean decisions and retraction, and the
[LP engine](26-lp-architecture.md) owns numerical state and scoped bound
assertions. The component turns assigned source rows into LP bounds, and LP
verdicts into conflicts, branches and witnesses.

### Rows as bounds

The component follows the slack-variable design of Dutertre and de Moura: every
normalized multi-column term gets one slack column defined by an equality row, so
every asserted atom becomes a bound on a single column, and asserting or retracting
an atom only moves a bound.

A term is normalized before lookup: zero coefficients are dropped and the first
remaining coefficient is scaled to one, flipping the bound direction for a negative
scale. `2x + 4y <= 6` and `-x - 2y >= -3` therefore share one slack column. Terms
first seen under an activator, branch or disjunct get their definition row appended
on first use, and siblings reuse it. At the root, fixed columns are substituted as
constants, keeping the premises of both fixing bounds.

### A check

A check proceeds in four steps:

1. **Assert.** The component collects declared bounds and every row whose Boolean
   activator is assigned; the row's truth selects the row or its exact complement.
   Each asserted bound carries the literals that justify it. Disjunction rows stay
   out until search selects a disjunct.
2. **Check.** An admitted difference fragment runs the graph check first. Otherwise
   the LP solves the asserted system with exact certification. Partial Boolean
   states are checked once at least one arithmetic row is determined.
3. **Explain.** An infeasible system produces a conflict from the certified
   infeasibility support: each cited row or bound contributes its premise literals.
   If a cited row or bound has changed in the live LP state, no conflict is
   reported rather than one with stale premises.
4. **Complete.** Once every Boolean is assigned, the exact candidate point is
   checked against every selected source row before it becomes a witness.

Retraction pops the LP scope together with the Boolean decisions that justified its
rows and discards candidates, so a witness never outlives its premises.

### Disjunctions and disequalities

A source disjunction is registered with search as one atom per disjunct; a disjunct
that is a single comparison is named by its exact bound, so the same predicate
reached another way shares the atom. The component holds the disjunction itself
rather than handing search a clause, because a learned clause could be forgotten by
a database reduction. It propagates like a clause: when all but one disjunct has
failed, the last is implied.

A disequality `t != b` is satisfied by either strict side. If the current bounds
already exclude one side, the component asserts the other; otherwise, at a complete
Boolean state, it registers the split `t < b` against `t > b`.

### Strict inequalities

The floating simplex solves the closure, where strict and non-strict bounds
coincide. Certification decides strictness with a margin auxiliary: it maximizes a
slack `t` added to every strict row and accepts only an exact witness with positive
`t`, or refutes only with an exact bound `t <= 0`.
[LP certification](27-lp-certification.md#refinement-and-strict-inequalities)
defines that check.

### Coordinate agreement

When the component shares a model with other components, it publishes its selected
rows as exact source-bound atoms and can check a candidate assembled by others
against the rows selected by the current Boolean state. A missing coordinate
declines rather than failing the candidate.

### Polarity guidance

While Booleans remain open, a completed partial point from the LP or the graph can
guide which polarity search tries first for a primitive comparison. Every row
sharing an activator must agree, or that activator receives no preference. These
guesses only order exhaustive Boolean splits. The `openHintFlips` allowance
controls them: null enables direct preferences, a positive value also enables
compound clause preferences, and zero disables steering.

## Results and proof authority

`ExactLraAssignment` captures Boolean values and rational real coordinates.
Completed source checks admit witnesses. Exact conflicts, bounds and rays follow
[LP certification](27-lp-certification.md); a floating status alone is
insufficient. Strict infeasibility requires a completed strict-feasibility check.

Open real objectives with complete affine source capture use
`RationalTheoryDescent`. Attained witnesses, strict approach paths and feasible
improving rays determine its result; `OpenTheoryOptimum.linearResult` separates the
incumbent value from an unattained limit. Incomplete optimization withholds global
claims while preserving checked incumbents.

## State and resources

Immutable row addresses, forms and Boolean dependencies can be shared by fresh
components over one canonical fragment. `prepareExactLiraSource` returns an opaque
factory only after complete, uncancelled preparation; each opening binds fresh
callbacks, cancellation and mutable arithmetic owners. Scoped assertions, graph
potentials, LP bases and candidates remain invocation-owned. Optional hint storage
admits at most 65,536 entries and 4,096-bit normalized rationals.

## Implementation

[QfLraSystem](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLraSystem.kt)
builds the asserted source system, slack columns and premise literals.
[ExactLinearFragment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines `supportsExactLra`.
[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
shares the linear checking machinery with the integer and mixed theories.
[LpPropagator](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
owns scoped bounds, their premises and conflict explanation. The pipeline names
this route `EXACT_LRA`.

---

Previous: [19. Difference logic](19-difference.md) · [Contents](README.md) · Next: [21. Linear integer arithmetic](21-linear-integer.md)
