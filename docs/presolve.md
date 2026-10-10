# Presolve transformations, cancellation and accounting

## Guarantees and source mappings

`PresolvePass.guarantees` separates equisatisfiability, objective preservation,
witness reconstruction, bijection, and complete coverage by reconstructed source
assignments. Objective preservation is conditional on the pass context protecting
objective columns. A satisfiability reduction can retain an optimum while losing
source multiplicity; its successful reconstruction does not establish a bijection.
`preservesSolutionSet` retains the raw solution-set classification for compatibility.

Affine elimination carries `AffineValue` records through both source and finite
presolve. Records own their coefficient arrays and evaluate with exact integer
arithmetic; residue division must be integral and finite results must fit `Long`.
Reconstruction runs in reverse elimination order. `SourceMapping` binds a phase
and its composed reconstruction to the actual source and reduced model objects.
Composition requires the preceding target to be the following source. Equal
variable counts and factor counts do not establish this association.

Objective adjustments map reduced values into source units as `scale * value +
offset`, with positive exact rational scale. Composition multiplies scales and
transports the later offset through the earlier scale. Current reductions protect
objective columns and use the identity adjustment; binary substitution only
zero-extends Boolean weights. Independent source objective evaluation checks the
composed adjustment against reconstructed witnesses. A mapping discards reduced-model
witness certificates when its source differs
from its target and never issues a proof certificate; source publication retains
its complete checker.

Sensitive queries require a bijection even when a pass is explicitly enabled.
Affine elimination and other multiplicity-changing reductions remain disabled
for enumeration, counting and sampling. Projected enumeration deduplicates the
requested Boolean and integer coordinates while retaining terminal exhaustion,
indeterminate checks and decision-budget interruption. Hash cells stop after
`cap + 1` distinct projections, rather than full witnesses. An exhausted cell
supports an exact count or uniform draw; an interrupted cell supplies only a
lower bound and cannot support either. This implementation can still visit many
full witnesses of one projection and retains the per-cell decision budget.

## Rebuild ownership

[PresolveShared.rebuildProblem](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveShared.kt)
carries the originating cancellation token into a fresh `BakedProblem`.
[RootBaker.reseed](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/RootBaker.kt)
preserves that token when it creates a problem carrying probe deductions. The
deadline, work-stop predicate and work meter therefore keep their originating
scope across fresh rebuilds and reseeds. Rebuilds and reseeds also preserve the
source model's immutable `ProblemSettings`; the pre-bake span gate cannot change
when another invocation changes ambient configuration.

A fired token skips root-probe phases and stops cancellation-aware propagation
at its polling boundaries. Partial propagation is sound but can forgo deductions.
Cancellation is cooperative: propagation-state construction and work between
polls can exceed a deadline. Probe-call caps do not establish a wall-clock bound.

## Phase allowance and telemetry

[PresolveBudget](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveBudget.kt)
owns the phase's deterministic work allowance. Its tokens carry the budget as their
work meter, so explicit `Cancellation.charge` calls reduce the original allowance.
Rebuilding a problem does not create another allowance or reset consumed work.

RootBaker records probe calls through the token's `PresolveBudget` meter. Counts
include Boolean probes, integer bound/hole SAC and repair calls, accumulated
across tiers and reseeds. Integer limits apply per variable and per tier within
one bake; an aggregate count above one tier's cap is not a cap violation by itself.
Round entries include empty schedule scans and accumulate across source and
finite schedules. Neither aggregate is a single-pass count.

Probe telemetry is distinct from charged work. The propagation path counts its
own factor fires without charging them through this token. Retaining the meter
preserves the charge destination and probe counters; it does not price every
construction or propagation operation into the phase allowance. Work and timing
measurements must retain these scope differences.

[PresolveSharedTest](../klause/src/commonTest/kotlin/com/eignex/klause/presolve/PresolveSharedTest.kt)
covers expired-deadline SAC, probe telemetry across successive rebakes, and
charges consuming the originating allowance.

## Clausal objectives

Default clausal optimization retains binary exclusion clauses rather than merging
at-most-one cliques into cardinalities. This preserves native SAT propagation and
learning and avoids repeated clique preparation. An explicit `+amo-clique` or
aggressive emphasis permits conversion; nonclausal models and feasibility queries
keep their configured clique policy.

The CLI pairs its work allowance with a shared elapsed-time ceiling of the same
derived duration. Routing and source/finite preparation retain that ceiling
without restarting it. Either limit can stop optional passes; partial reductions
remain sound, but elapsed cancellation can change the deductions and selected route
with host speed. The solve-wide deadline still applies, and mandatory construction
and work between cancellation polls can overrun the preparation ceiling. An explicit
nonpositive presolve budget disables both phase limits.

At-most-one clique replacement is published only after clique analysis and factor
matching finish within their cancellation scope. A cancelled replacement leaves
the input factors intact, preserving their propagation representation for search.
Earlier completed passes keep their sound reductions.

## Pre-bake LP bounds

The finite pipeline runs the pre-bake LP feasibility check when a declared integer
span exceeds the large-span threshold captured in the model settings. Its optimization-based bound
queries use the same threshold per variable, in widest-first order. Narrow
columns retain their declared domains until the root bake and ordinary presolve;
a wide column does not trigger bound queries over unrelated narrow columns.
Every accepted tightening still requires the LP bound certificate, and skipping
a query retains all source constraints and feasible values.

## Binary integer channels

Binary-column substitution accepts single-variable reified equalities `b ↔ (x = 0|1)`
when every reader of `x` supports the Boolean replacement. These channels become
Boolean equivalences; general reified rows and value-reading globals retain their
integer columns. Objective integer columns remain excluded. Substituted integers
reuse an existing channel literal when one is available, including its polarity;
columns without a channel receive a fresh Boolean. Reusing a channel removes its
redundant equivalence clauses. Integers are pinned during search and reconstructed
from their value literals, preserving source values and solution counts for either
indicator polarity.
