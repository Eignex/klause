# Presolve cancellation and accounting

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
owns the phase's deterministic allowance. Its tokens carry the budget as their
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
