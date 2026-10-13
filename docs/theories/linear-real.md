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

On the LP-backed route, an active exact term bound can imply an undecided reified
arithmetic atom with the same normalized term. Signed scales reverse the bound
side, and strict targets require an interior bound at an equal threshold. Source
Boolean coefficients must be fixed and explained. The comparison's iff activator
is the target; a source implication guard is not implied from its consequent.
The current bound witness and fixed-column substitutions supply the reason.

Feedback follows the ordinary eager LP check. Publication clears its candidate
and repeats the shared fixpoint, so new activators and substitutions enter the
next source assertion before a complete witness. Graph-first checking retains its
separate behavior. Private source branches and custom certification policy decline
this optional feedback.

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

Open real objectives use exact strict-improvement theory rounds when the objective
cutoff remains admitted. `RationalTheoryDescent` retains checked incumbents and can
prove an attained optimum by refuting a strict improvement. It supplies neither
a recession certificate nor an unattained-limit result; unbounded objectives and
unattained infima can continue until interrupted. Finite/LP-backed optimization
retains its separate admission and certificate checks. An unattained infimum cannot
be presented as an attained optimum.
[Input semantics](../formats.md) defines each frontend's exact or tolerance policy.

## State and resources

Immutable row addresses, forms and Boolean dependencies are prepared once per
component. Scoped assertions, graph potentials, LP bases and witness candidates
remain invocation-owned. Feedback retains only an immutable-row cursor and cumulative
work counter; it looks up active sides afresh and keeps no proof or Boolean state
across checks. Each pass visits at most 64 rows, publishes at most 8 atoms and spends
at most 4096 optional work units; its component lifetime spends at most 65536.
Rows with more than 64 terms decline. Source visits, comparison lookup, bounded
premise expansion and publication are prepaid to the containing operation meter.
Retraction restores their premises and invalidates incompatible evidence. Open
work and numerical limits retain their own currencies;
a stopped source check withholds the witness or proof.

## Implementation

[ExactLinearFragment](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines `supportsExactLra`.
[ExactLiraSearchComponent](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
shares the linear checking machinery without integer branching for a pure-real source.
[QfLraSystem](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLraSystem.kt)
owns model-facing linear assertions; the pipeline names this route `EXACT_LRA`.
