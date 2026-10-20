# 20. Linear real arithmetic

Linear real constraints combine real coordinates with constant coefficients and
Boolean structure. They generalize the difference rows of the
[previous chapter](19-difference.md) to arbitrary rational coefficients. Exact
rational checks decide the active linear system, and shared search explores the
Boolean alternatives. Strict inequalities require an interior point, not a point
on the closure's boundary.

This chapter introduces the linear component that the integer and mixed theories in
the next two chapters extend. It follows the
[shared theory contract](18-theories.md); integrality belongs to
[linear integer](21-linear-integer.md) or [linear mixed](22-linear-mixed.md)
arithmetic.

## Scope and boundaries

The pure-real route requires real variables, no source integer variables, and
factors wholly represented by admitted exact linear forms. Source bounds,
reification, disjunction and strictness remain authoritative. Nonconstant real
products belong to [polynomial real arithmetic](24-polynomial-real.md).

[Difference logic](19-difference.md#real-graph-admission-and-checks) supplies a
graph mechanism for an admitted pure-real subfragment. General linear rows use the
LP-backed route; a graph admission decline does not reject a supported linear
source.

## Solving and composition

The linear component sits on two shared lifecycles at once: the
[search session](14-search.md) owns Boolean decisions and retraction, and the
[LP engine](26-lp-architecture.md) owns numerical state and its scoped bound
assertions. The component translates between them: it turns assigned source rows
into LP bounds, and LP verdicts into conflicts, branches and witnesses.

### Rows as bounds

The component follows the slack-variable design of Dutertre and de Moura: every
normalized multi-column term gets one slack column defined by an equality row, so
every asserted atom becomes a bound on a single column. The LP model therefore
holds the structural source columns with their declared bounds, plus one logical
column per distinct term, and asserting or retracting an atom only moves a bound.

A term is normalized before it is looked up. Zero coefficients are dropped, and the
first remaining coefficient is scaled to one; the scale is retained, and a negative
scale flips an upper bound into a lower one. `2x + 4y <= 6` and `-x - 2y >= -3`
therefore share one slack column and one bound. Terms that appear in unconditional
rows are installed with the initial model; terms first seen under a Boolean
activator, a branch or a disjunct get their definition row appended on first use.
Definitions are not scoped, so a term defined in one branch is reused by its
siblings instead of being rebuilt.

At the root, columns whose lower and upper bounds coincide are substituted into
the term as constants. The substitution keeps the premises of both fixing bounds,
so a conflict that relies on the substituted value still names why the column was
fixed.

### A check

A check proceeds in four steps:

1. **Assert.** The component collects declared bounds and every row whose Boolean
   activator and coefficient Booleans are assigned. A row's truth selects either
   the row or its exact complement; an equality asserts two bounds. Each asserted
   bound carries the Boolean literals that justify it. Rows belonging to a source
   disjunction stay out until search selects a disjunct.
2. **Check.** For an admitted pure-real difference fragment, the graph check runs
   first and needs no LP. Otherwise the LP solves the asserted system with exact
   certification. Partial Boolean states are checked too, but only once at least
   one arithmetic row has a determined truth value, so a contradiction can surface
   before every Boolean is decided without solving an empty system.
3. **Explain.** An infeasible system produces a conflict built from the certified
   infeasibility support: each cited row contributes its premise literals and each
   cited bound the premise recorded when it was asserted. The explanation is
   checked against the live LP state; if a cited row or bound has changed, no
   conflict is reported, rather than one with stale premises. The explanation
   enters ordinary shared conflict learning.
4. **Complete.** Once every Boolean is assigned, the component decides what
   remains open, described below. When all choices are made, the exact candidate
   point is checked against every selected source row before it becomes a witness.

Resolved source rows stay fixed until the LP scope containing their assertion is
popped. Retraction pops that scope together with the Boolean decisions that
justified its rows, restores the component's node snapshot for the surviving
decision level and discards candidates, so a witness never outlives its premises.

### Disjunctions and disequalities

A source disjunction is registered with search as one atom per disjunct. A disjunct
that is a single non-strict or strict comparison over source coordinates is named
by its exact bound, so the same predicate reached by another route shares the
atom. Other disjuncts get a private name. The component holds the disjunction
itself rather than handing search a clause, because a learned clause could be
forgotten by a database reduction. It propagates like a clause: when every disjunct
but one has failed, the last is implied with the failed disjuncts as reason, and
when all have failed the failures form the conflict.

A disequality `t != b` is satisfied by either strict side. If the current LP bounds
on `t` already exclude one side, the component asserts the other, citing that
bound's premise. Otherwise, at a complete Boolean state, it registers the split
`t < b` against `t > b` and lets search choose.

### Strict inequalities

A strict row becomes a strict bound on its column. The floating simplex solves the
closure, where strict and non-strict bounds coincide, so a point on the boundary
proves nothing either way. Certification decides strictness with a margin
auxiliary: it maximizes a slack `t` added to every strict row and accepts only an
exact witness with positive `t`, or refutes only with an exact bound `t <= 0`.
[LP certification](27-lp-certification.md#refinement-and-strict-inequalities)
defines that check.

### Coordinate agreement

When the linear component shares a model with other components, it can publish
its selected rows as exact source-bound atoms, and it can check a candidate
assembled by others. That check reads every source coordinate from the assembled
model, rejects symbolic or algebraic values it cannot represent, and tests the
point against the rows selected by the current Boolean state and published integer
bounds. A missing coordinate declines as a representation stop rather than
failing the candidate.

### Polarity guidance

While Booleans remain open, a completed partial point from the LP or the graph can
guide which polarity search tries first for a primitive comparison. Positive
comparisons keep strictness, equality and disequality, and every row sharing an
activator must agree, or that activator receives no preference. These guesses
only order exhaustive Boolean splits; they supply no values, facts, conflicts or
verdict. The default null `openHintFlips` allowance enables direct preferences for
one linear completion owner; compound source-clause preferences are opt-in through
a positive allowance, and zero disables steering. Graph-first sources keep
preferences only beside a Boolean cardinality over three or more literals. No extra
LP solve, graph check or allowance is created for hints.

## Results and proof authority

`ExactLraAssignment` captures Boolean values and rational real coordinates.
Completed source checks admit witnesses. Exact conflicts, bounds and rays follow
[LP certification](27-lp-certification.md); a floating status alone is insufficient.
Strict infeasibility requires a completed strict-feasibility check, not failure to
find a positive margin within a budget.

Open real objectives with complete affine source capture use
`RationalTheoryDescent`'s retained linear authority invocation. Shared Boolean
traversal, exact source bounds, attained witnesses, strict approach paths and
feasible improving rays determine its result. `OpenTheoryOptimum.linearResult`
keeps original-direction evidence and separates the incumbent value from an
unattained limit; a limit is not an attained optimum. Unsupported source capture
keeps the theory's strict-improvement route, and incomplete optimization withholds
global claims while preserving checked incumbents.
[Input semantics](04-input-semantics.md) defines each frontend's exact or tolerance
policy.

## State and resources

Immutable row addresses, forms and Boolean dependencies can be shared by fresh
components over one canonical immutable fragment. `prepareExactLiraSource` returns
an opaque factory only after complete, uncancelled preparation; each opening binds
fresh callbacks, cancellation and mutable arithmetic owners. Scoped assertions,
graph potentials, LP bases and witness candidates remain invocation-owned.
Retraction restores their premises and invalidates incompatible evidence.
Open work and numerical limits keep their own currencies, and a stopped source
check withholds the witness or proof.

Optional hint storage admits at most 65,536 source row, term, coordinate and index
entries, and hint arithmetic admits 4,096-bit normalized rationals. Its work
charges the current operation token, and a cancelled scan publishes nothing.
Arithmetic decisions, retraction and restart invalidate preferences.

## Implementation

[QfLraSystem](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLraSystem.kt)
builds the asserted source system and its premise literals.
[ExactLinearFragment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines `supportsExactLra`.
[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
shares the linear checking machinery with the integer and mixed theories, without
integer branching for a pure-real source.
[RealDifferenceSystem](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/RealDifferenceSystem.kt)
is the graph-first check.
[LiveQfLraSystem](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLraSystem.kt)
owns slack columns and term normalization, and
[LpPropagator](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
owns scoped bounds, their premises and conflict explanation. The pipeline names
this route `EXACT_LRA`.

---

Previous: [19. Difference logic](19-difference.md) · [Contents](README.md) · Next: [21. Linear integer arithmetic](21-linear-integer.md)
