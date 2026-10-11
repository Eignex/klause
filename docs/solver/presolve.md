# Presolve transformations, cancellation and accounting

## Guarantees and source mappings

`PresolvePass.guarantees` separates equisatisfiability, objective preservation, witness
reconstruction, bijection, and complete coverage by reconstructed source assignments.
Objective preservation is conditional on the pass context protecting objective columns.
A satisfiability reduction can retain an optimum while losing source multiplicity; its
successful reconstruction does not establish a bijection. `preservesSolutionSet` retains
the raw solution-set classification for compatibility.

Affine elimination carries `AffineValue` records through both source and finite
presolve. Records own their coefficient arrays and evaluate with exact integer
arithmetic; residue division must be integral and finite results must fit `Long`.
Reconstruction runs in reverse elimination order. `SourceMapping` binds a phase and its
composed reconstruction to the actual source and reduced model objects. Composition
requires the preceding target to be the following source. Equal variable counts and
factor counts do not establish this association.

Objective adjustments map reduced values into source units as `scale * value + offset`,
with positive exact rational scale. Composition multiplies scales and transports the
later offset through the earlier scale. Current reductions protect objective columns and
use the identity adjustment; binary substitution only zero-extends Boolean weights.
Independent source objective evaluation checks the composed adjustment against
reconstructed witnesses. A mapping discards reduced-model witness certificates when its
source differs from its target and never issues a proof certificate; source publication
retains its complete checker.

Sensitive queries require a bijection even when a pass is explicitly enabled. Affine
elimination and other multiplicity-changing reductions remain disabled for enumeration,
counting and sampling. Projected enumeration deduplicates the requested Boolean and
integer coordinates while retaining terminal exhaustion, indeterminate checks and
decision-budget interruption. Hash cells stop after `cap + 1` distinct projections,
rather than full witnesses. An exhausted cell supports an exact count or uniform draw;
an interrupted cell supplies only a lower bound and cannot support either. This
implementation can still visit many full witnesses of one projection and retains the
per-cell decision budget.

## Rebuild ownership

[PresolveShared.rebuildProblem](../../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveShared.kt)
carries the originating cancellation token into a fresh `BakedProblem`.
[RootBaker.reseed](../../klause/src/commonMain/kotlin/com/eignex/klause/presolve/RootBaker.kt)
preserves that token when it creates a problem carrying probe deductions. The deadline,
work-stop predicate and work meter therefore keep their originating scope across fresh
rebuilds and reseeds. Rebuilds and reseeds also preserve the source model's immutable
`ProblemSettings`; the pre-bake span gate cannot change when another invocation changes
ambient configuration.

A fired token skips root-probe phases and stops cancellation-aware propagation at its
polling boundaries. Partial propagation is sound but can forgo deductions. Cancellation
is cooperative: propagation-state construction and work between polls can exceed a
deadline. Probe-call caps do not establish a wall-clock bound.

## Phase allowance and telemetry

[PresolveBudget](../../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveBudget.kt)
owns the phase's deterministic work allowance. Its tokens carry a native-work adapter,
so explicit `Cancellation.charge` calls reduce the original allowance. Rebuilding a
problem does not create another allowance or reset consumed work.

RootBaker records probe calls through the token's phase adapter. Counts include
Boolean probes, integer bound/hole SAC and repair calls, accumulated across tiers and
reseeds. Integer limits apply per variable and per tier within one bake; an aggregate
count above one tier's cap is not a cap violation by itself. Round entries include empty
schedule scans and accumulate across source and finite schedules. Neither aggregate is a
single-pass count.

Root propagation reports native work through a narrow propagation-owned meter on the
existing cancellation surface. Presolve owns its conversion: construction entries and
native propagation work each cost 53 phase units. Construction entries count Boolean,
integer and real columns, factors, and each factor's structural-key weight. This is a
deterministic size proxy, including state and projection preparation; it does not count
allocated bytes or every instruction. Native propagation work is the existing counter
of factor fires, literal reads and wakeup entries, including native SAT BCP. Internal
propagator loops without counter increments are outside that proxy.

Every fresh root, probe and repair reports construction once, flushes native counter
deltas before cancellation polls, and flushes the remainder on return, including seed
conflicts and propagation conflicts. Repeated polls do not bill the same delta twice.
A reseed bills its fresh root; an already-baked input does not bill another construction.
The incremental presolve state uses the same adapter across delta propagation and resets
only its native-counter cursor when a reseed creates a fresh state. Added factors report
one construction entry plus their structural-key weight. Pass views and materialization
retain the phase token for any root work they perform; completed pass deltas remain
mandatory even if their application exhausts the allowance. Incremental presolve polls
from its first factor fire rather than using search's per-node cancellation floor.
The pipeline has no additional base-bake estimate. CLI effort telemetry retains
`presolveRootConstructionEntries` and `presolveRootPropagationWork` separately from
converted `presolveWork` and probe calls, accumulated across the same phase.

A spent work allowance or fired outer token prevents entry into another probe, opposite
polarity, repair or tier. A completed failed probe retains its proven forcing, bound
exclusion or hole even when no allowance remains for repair. Conditional deductions
from a successful or interrupted hypothetical probe are not published. Completed repair
deductions remain sound if propagation stops before its fixpoint. Boolean probing keeps
all incoming integer deductions when it returns a partial result.

Construction is mandatory and charged after completion, even under a zero allowance.
Propagation polls at its existing boundaries (every 256 CP factor fires or native SAT
trail entries); a single fire, native BCP trail entry or construction can exceed the
remaining allowance. Final counter flushing can also exhaust the allowance after a
short fixpoint completes. These weights and polling intervals are not a hard time bound.
`PresolveBudget(0)` stops optional probing; a phase without a budget adapter has no native
charges and retains outer cancellation. The legacy nonpositive millisecond budget
selects that unlimited-work policy; an explicit `presolve-work-limit=0` instead supplies
an exhausted phase allowance. Counters and converted charges saturate at `Long.MAX_VALUE`.

[RootBakerTest](../../klause/src/commonTest/kotlin/com/eignex/klause/presolve/RootBakerTest.kt)
covers work exhaustion across probing tiers, retained exclusions, repair/reseed billing,
zero and unlimited allowances, outer cancellation and root conflicts.
[PresolveSharedTest](../../klause/src/commonTest/kotlin/com/eignex/klause/presolve/PresolveSharedTest.kt)
covers inherited phase accounting across rebuilds.

## Clausal objectives

Default clausal optimization retains binary exclusion clauses rather than merging
at-most-one cliques into cardinalities. This preserves native SAT propagation and
learning and avoids repeated clique preparation. An explicit `+amo-clique` or aggressive
emphasis permits conversion; nonclausal models and feasibility queries keep their
configured clique policy.

The CLI pairs its work allowance with a shared elapsed-time ceiling of the same derived
duration. Routing and source/finite preparation retain that ceiling without restarting
it. Either limit can stop optional passes; partial reductions remain sound, but elapsed
cancellation can change the deductions and selected route with host speed. The
solve-wide deadline still applies, and mandatory construction and work between
cancellation polls can overrun the preparation ceiling. An explicit nonpositive presolve
millisecond budget disables both derived phase limits. `presolve-work-limit=unlimited`
removes only the work cap; an explicit finite or zero work allowance remains independent
of that clock. See [resource allowances](resources.md).

At-most-one clique replacement is published only after clique analysis and factor
matching finish within their cancellation scope. A cancelled replacement leaves the
input factors intact, preserving their propagation representation for search. Earlier
completed passes keep their sound reductions.

## Pre-bake LP bounds

The finite pipeline enters pre-bake LP feasibility and bound tightening when a factor
couples at least two distinct nonfixed integer columns whose spans exceed the large-span
threshold captured in the model settings, or couples a wide integer column to a
continuous column. Isolated wide outputs with narrower integer inputs use ordinary root
propagation.

Bound queries use the same threshold per variable, in widest-first order, for at most 64
columns. Narrow columns remain in the relaxation but retain their declared domains until
the root bake and ordinary presolve; a wide column does not trigger bound queries over
unrelated narrow columns. Every accepted tightening still requires the LP bound
certificate, and skipping a query retains all source constraints and feasible values.

## Binary integer channels

Binary-column substitution accepts single-variable reified relations whose truth differs
at 0 and 1 when every reader supports the Boolean replacement. These channels become
Boolean equivalences; general reified rows and value-reading globals retain their
integer columns. Objective integer columns remain excluded. Substituted integers reuse
an existing channel literal when one is available, including its polarity; columns
without a channel receive a fresh Boolean. Reusing a channel removes its redundant
equivalence clauses. Integers are pinned during search and reconstructed from their
value literals, preserving source values and solution counts for either indicator
polarity.
