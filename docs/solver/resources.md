# Resource allowances

Allowances belong to the scope that owns the counter. Resuming a handle, reseeding an
arm, entering a repair or rebuilding a presolve model does not renew a containing
allowance. A completed atomic operation can overspend; its work stays charged and the
next polling boundary stops optional work. An exhausted allowance cannot certify that
a search space was exhausted. Only a completed engine proof can do that.

## Configuration and lifetime

The CLI parses `auto`, `unlimited` and a nonnegative remaining amount as distinct
settings. Omission selects `auto`. For `node-limit`, automatic and unlimited both resolve
to no solve work cap; `0` resolves to an exhausted `NodeBudget`. For
`presolve-work-limit`, automatic derives work from the preparation time policy,
unlimited removes the work cap and `0` stops optional preparation. The preparation
clock and whole-solve deadline still apply. The legacy nonpositive
`klause.presolve.budget.ms` disables the derived preparation work cap and its clock;
an explicit work setting can still supply an exhausted or finite work allowance.

Built-in `withCancellation` adapters replace the invocation token while retaining any
separately bound lifetime scope. `withLifetimeCancellation` composes a persistent token
that bounds construction and every later invocation or retained slice. Ordinary
parameter cancellation bounds one-shot execution and retained-handle construction;
each retained slice supplies its own invocation token. These scopes keep their earliest deadline without retaining an old
slice deadline. Portfolio construction and the first search segment share the offered
segment deadline. ALNS bootstrap construction uses its shortened phase token; later
bootstrap slices retain the containing lifetime scope and rearm their invocation clock.

## Units and boundaries

| Allowance | Unit and owner | Unlimited / zero | Stop surface |
|---|---|---|---|
| CLI `-t` | Milliseconds, one deadline anchored before loading | Omitted deadline / immediate deadline | Cooperative solve cancellation; mandatory work can overrun |
| `NodeBudget` | Solve or worker quota: CP decisions plus floor(5 × counted LS instructions / 4) | Missing budget / exhausted budget | Budget exhaustion; completed evidence remains valid |
| `BacktrackParams.maxDecisions` and `maxInstructions` | Decision attempts, native traversal operation | `Long.MAX_VALUE` or missing instruction cap / no decisions | Incomplete result, never a proof of exhaustion |
| `SliceBudget` | Retained handle: search nodes plus policy-priced LP, propagation and inprocessing | Negative slice work / no new scheduled work | Pause; completed constructor or terminal results can return |
| Portfolio base, maximum and growth | Shared schedule, node equivalents | Dedicated lanes offer unbounded work / zero base or maximum rejected | Work or time pause |
| LS `maxFlips` and `maxInstructions` | Native instructions, operation or retained handle; sample streams renew their per-sample flip cap | `Long.MAX_VALUE` or missing instruction cap / no moves | Budget exhaustion without proof |
| ALNS instruction allowance | Declared outer repair allowance, retained ALNS handle | Missing cap / no outer iterations | Anytime result without proof |
| `PresolveBudget` | Phase work across routing, source/finite schedules, probes, rebuilds and repairs | Missing meter / no optional work | Completed sound reductions retained |
| Presolve rounds and probe caps | Native schedule rounds or probe calls, local schedule/tier/bake | Emphasis defaults; no unlimited sentinel / skip relevant work | Forgo reductions; aggregate telemetry can exceed one local cap |
| LP work and iteration limits | Native LP work proxy and pivots, individual solve or split component | Legacy zero delegates to the engine / an exhausted caller token prevents entry | Incomplete solve may retain certified bounds; no optimum proof |
| Open-theory limits | Open policy work, leaves and decisions, retained complete theory scope | `Long.MAX_VALUE` / exhausted scope | Unknown budget exhaustion; completed proofs only |
| `QfNraParams.maximumWork` | Native normalization, CAD and Boolean work, one invocation | Null / zero prevents entry | Unknown before verdict publication |
| Neutral kernel controls | Caller-owned native work, optional maximum and cancellation | Missing maximum / zero maximum is exhausted | Kernel interruption without solver result semantics |

Local-search initial preparation retains its progress between cancelled invocations.
Buffer allocation, assignment seeding and definition preparation spend wall time without
search instructions; resuming them neither renews nor charges the containing move
allowance. Only committed walk/restart instructions advance that ledger. Invocation
expiry leaves private preparation paused; closing discards its cursor. Individual
runtime allocations and factor calls remain atomic and may overrun the deadline; see
[initialization boundaries](local-search.md#projections-and-preparation).

General CP optimization construction retains the same private cursor across invocation
expiry. Index construction, state fills, assumption propagation and model-facing LP/search
installation checkpoint in fixed batches. Construction scans and primitive allocations
spend wall time without decision/move charges. Completed propagation visits retain their
existing slice pricing; final installation transfers fractional marks and debt once.
Individual runtime allocations and primitive/factor calls can still overrun a checkpoint;
see [private CP preparation](portfolio.md#private-cp-preparation).

The solve-wide decision/move currency excludes propagation and LP work. Ordinary LS
instructions and CP repair decisions charge the same containing `NodeBudget`. The
private clausal primal search and walk also charge that ledger. Their decisions and
walk instructions are not included in the enclosing arm's `nodes` and `lsMoves`
counters, so those exported counters alone cannot reconstruct the solve charge. ALNS
charges each outer iteration's declared flip allowance once; the inner LS repair is
not billed again for its moves, while CP repair decisions remain additional native
work. CLI statistics expose `nodeBudgetLimit`, `nodeBudgetSpent` and
`nodeBudgetRemaining` from the containing ledger, including cooperative overspend. Scheduler coverage for finite ALNS segments uses the offered segment allowance;
it is distinct from actual inner work. Native bootstrap/repair counters and
`alnsOuterAllowance` retain those distinctions in statistics.

Slice pricing belongs to solver policy: LP work costs one node per 600 units,
propagation work one per 4000 visits, inprocessing probes one per 12 and subsumption
visits one per 18000. Fractional LP and propagation marks survive slices and the
preparation-to-search transition. Overspend debt is settled once and repaid by later
slices. A nested private search leaves debt ownership with its containing budget.
Relative counters and saturating additions prevent a large allowance from wrapping.

## Parallel cap

Built-in finite portfolios with more than one lane partition the remaining solve
allowance over every materialized worker, including replicas. Integer division gives
equal shares, with remainder units assigned in worker order. Sequential portfolios
retain one shared allowance. Open portfolios partition their LS decision/move
allowance across LS workers; complete theory work retains its separate native scope.

Quotas persist on the containing budget across reopens and reseeds. They are not
redistributed after a worker finishes, fails or is cancelled. A quota can therefore
stop a worker while other quota remains unused. Synchronization retains every native
charge in the aggregate counter. Atomic overspend remains visible and can exhaust the
whole-run cap before another worker spends its quota. Low-level callers sharing a
budget directly receive synchronized cooperative accounting, rather than reservations.
Parallel scheduling and stopping order are not reproducible.

`Cancellation` and `WorkMeter` live in the neutral `util` package within `klause`. Their units come
from the caller's adapter; they contain no portfolio pricing. Composing tokens carries
one available meter, rather than charging all possible ancestors. Presolve's adapter
explicitly owns native root-work conversion. Numerical kernels consume native controls;
solver policy owns conversions and result/proof semantics.

The [polynomial real theory](qfnra.md#results-and-invocation-resources) retains one
counted scope through its final publication poll. Its native work reaches a supplied
caller meter once and remains separate from legacy open-theory policy counters.
Kernel representation or allocation declines produce a resource-unknown result.
Public `PolynomialTheoryParams.maximumWork` is nullable for unlimited native work;
zero prevents entry. Dense coefficients default to 65,536 and must be positive or
unlimited. Matrix cells default to 262,144 and accept zero or unlimited. Disabling
an allocation cap retains checked native dimensions. `PolynomialTheoryExecution`
reports these counters and the resource cause separately from `SolveStats`.
Timeout and external cancellation keep separate common termination reasons.

The SMT CLI starts its whole-run deadline before admission and retains that deadline
through polynomial solving. Source parsing polls separately and charges its own exact
coefficient-folding units when a caller meter exists. Parsing is excluded from native
engine counters. `nra-work-limit`, `nra-dense-coefficients` and `nra-matrix-cells` own
the polynomial limits; `node-limit` and legacy open-work settings do not price them.

See [sessions](sessions.md) for handle ownership, [presolve](presolve.md) for phase
telemetry, [portfolio scheduling](portfolio.md) for coverage and
[LP certification](../lp/certification.md) for partial numerical evidence.

A `PolynomialTheoryInvocation` shares one cumulative native allowance across its
conditioned checks. Every check receives only the remaining allowance and forwards
its observed arithmetic work once to the containing caller meter. Query condition
assembly belongs to that work scope. New finite branches and retractions do not renew
it. Independent invocations receive independent ledgers.
