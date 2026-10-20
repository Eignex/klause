# 16. Portfolios

Complete search and local search have complementary strengths, and Klause rarely
runs just one of them. A portfolio runs multiple strategies over one source model
and objective. Workers can improve incumbents, establish bounds or complete a
proof; the coordinator schedules their work and admits shared results only with the
authority required for that claim. One worker's failure to find a solution does not
refute the model.

Execution has three parts: prepare retained workers, give them bounded slices of
the allowance from [chapter 11](11-resources.md), and verify their publications
with the [source validation](13-validation.md) service before sharing or returning
them. Slices preserve state; reseeding or retiring a worker has its own lifetime and
accounting rules. [LP certification](27-lp-certification.md) owns numerical proof
checks.

The [solver vision](01-introduction.md#shared-model-breadth) requires an available
complete-search/local-search pair for every supported model. Current admission gaps
remain implementation limits.

This chapter describes how an invocation is assembled and which strategies it
admits. Its subchapters follow the life of a worker's output:

1. [Witnesses and proof scope](16.1-portfolio-witnesses.md): which results a worker
   may publish, how the pool checks them, and what exhaustion establishes.
2. [Scheduling](16.2-portfolio-scheduling.md): slices, deferred preparation,
   coverage before the first witness, reseeding and incumbent guidance.
3. [Worker recipes](16.3-portfolio-recipes.md): the pools chosen for particular model
   structures, and exact theory strategy execution.

## Invocations and recipes

Finite, linear open, polynomial, nonlinear-integer, quadratic and transcendental
source execution supply retained recipe factories to `PortfolioInvocation` with the
routing plan and current executable projection. The invocation owns worker slots,
seed offsets, parallel quotas, scheduler controls, result verification and cleanup;
mutable services registered with a slot close when the slot is released, and each
replica gets private state. Native reservations divide the complete family's
allowance across its slots. Heuristic-only factories carry witness authority, and
`FIXED` rejects multiple cores.

`SourceExecutionPolicy` sets finite arm defaults and LP ceilings before worker
construction; explicit `arms`, `ls` or `bt` settings take precedence. Native
arithmetic routes reject finite-only explicit policy instead of ignoring it. Recipe
defaults carry no witness or proof authority.

## Prepared strategy admission

Source adapters consume the [prepared plan](03.2-engine-projections.md#engine-projections-and-factor-capabilities)
for strategy and operation admission. A component contract records the direct
projection and required completion actually selected by preparation. Semantic
identifiers and worker labels do not establish support. The direct factor inventory
cannot replace whole-source completion, including continuous products.

Admission is separate from procedure completeness and claim verification: integer
traversal can run without a termination guarantee, heuristic workers have witness
authority only, and unsupported components decline with explicit causes.

Portfolios bind `EngineInvocation` to the retained plan, actual execution model and
objective, and captured pins. Terminal admission consumes `CompletedEngineResult`
with the actual result, so a conditioned or foreign completion cannot finish a weaker
invocation. Original-source derivation capture is the subject of
[chapter 17](17-evidence.md).

## Statistics

The portfolio owns `SolveStats.run.wallMs` for each satisfaction or optimization
invocation, spanning initialization, all segments, verification and disposal; it
replaces worker run windows rather than summing them. Worker counters merge
independently, and per-arm scheduled times can overlap across parallel lanes.

| Field | Meaning |
|---|---|
| Arm `maxMs`, `initMs` | Longest elapsed segment and handle opening cost |
| `portfolioReseedStaleThreshold`, arm `reseeds` | Effective stale threshold and reseed count |

`initMs` measures opening the handle. Preparation deferred into slices contributes to
segment time and live propagation counters; it is not included in opening time.
[Search statistics](14.5-primal-and-statistics.md#statistics) define propagation and conflict
accounting.

---

Previous: [15.4 Completion, restarts and ownership](15.4-lifetime.md) · [Contents](README.md) · Next: [16.1 Witnesses and proof scope](16.1-portfolio-witnesses.md)
