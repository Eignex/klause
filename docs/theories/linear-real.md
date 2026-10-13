# Linear real arithmetic

Linear real constraints combine real coordinates with constant coefficients and
Boolean structure. Exact rational checks decide the active linear system; shared
search explores Boolean or arithmetic alternatives. Strict inequalities require
an interior point, rather than a point on the closure's boundary.

This theory follows the [shared theory contract](../theories.md). Integer
integrality belongs to [linear integer](linear-integer.md) or
[linear mixed](linear-mixed.md) arithmetic.

## Scope and boundaries

The pure-real route requires real variables, no source integer variables, and
factors wholly represented by admitted exact linear forms. Source bounds,
reification, disjunction and strictness remain authoritative. Nonconstant real
products belong to [polynomial real arithmetic](polynomial-real.md).

[Difference logic](difference.md) supplies a graph mechanism for an admitted
pure-real subfragment. General linear rows retain the LP-backed route; a graph
admission decline does not itself reject a supported linear source.

## Solving and composition

Shared decisions activate rows and comparisons. The arithmetic component retains
those assertions through their decision scope, checks the residual system and
contributes exact source coordinates at a complete check. Disequalities require
an applicable direction and remain part of the source check.

Linear completion also serves finite mixed models and local-search candidates.
Boolean pins and source rows stay intact through completion. The shared search
session owns traversal; [LP architecture](../lp/architecture.md) owns numerical
state; the source adapter owns projection and reconstruction.

## Results and proof authority

`ExactLraAssignment` captures Boolean values and rational real coordinates.
Completed source checks admit witnesses. Exact conflicts, bounds and rays follow
[LP certification](../lp/certification.md); a floating status alone is insufficient.
Strict infeasibility requires a completed strict-feasibility check, not failure to
find a positive margin within a budget.

Open real objectives with complete affine source capture use `RationalTheoryDescent`'s
retained linear authority invocation. Shared Boolean traversal, exact source bounds,
attained witnesses, strict approach paths and feasible improving rays determine its
result. `OpenTheoryOptimum.linearResult` retains original-direction evidence and
separates the incumbent value from an unattained limit. A limit is not an attained
optimum. Unsupported source capture retains the theory's strict-improvement route;
incomplete optimization withholds global claims and preserves checked incumbents.
[LP certification](../lp/certification.md) defines the affine authority checks.
[Input semantics](../formats.md) defines each frontend's exact or tolerance policy.

## State and resources

Immutable row addresses, forms and Boolean dependencies can be shared by fresh
components over one canonical immutable fragment. `prepareExactLiraSource` returns
an opaque factory only after complete, uncancelled preparation; its openings retain
the public component's source semantics. The direct constructor still prepares its
own rows. Scoped assertions, graph potentials, LP bases and witness candidates
remain invocation-owned. Retraction restores their premises and invalidates
incompatible evidence. Open work and numerical limits retain their own currencies;
a stopped source check withholds the witness or proof.

## Implementation

[ExactLinearFragment](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines `supportsExactLra`.
[ExactLiraSearchComponent](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
shares the linear checking machinery without integer branching for a pure-real source.
[QfLraSystem](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLraSystem.kt)
owns model-facing linear assertions; the pipeline names this route `EXACT_LRA`.

The complete source-order traversal may use an already produced partial real-bearing
point to order a reified Boolean comparison. Fixed source coordinates and known
Boolean coefficient values are read exactly. This optional guide keeps both sides
and withholds ambiguous, unsupported or interrupted evaluation. Source row checks,
strict feasibility and completed witness publication remain unchanged.

Mixed linear points may contain fractional integer coordinates and still guide
order. [Integer feasibility](linear-integer.md) and the complete original-source
witness checks remain the only assignment authority; a point phase cannot establish
integrality. Pure-integer sources keep their existing branching policy.
