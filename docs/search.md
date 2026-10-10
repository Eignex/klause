# Search and propagation

## Shared component search

[solver.search](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/)
owns decisions, levels, branching, retraction, complete checks, restarts and
model assembly. CP and theory components contribute residual state and reasons
through the same protocol. The session exchanges Boolean literals, semantic
integer bounds and opaque theory decisions rather than native CP domains.

Fractional source integers split at exact floor/ceiling thresholds. CP decisions
use Long values; open theories use arbitrary-precision thresholds. Stable
complementary atoms must exist before first-UIP learning. Boolean atom names
belong to one immutable source root; changing root meaning requires a fresh
session. Unnameable conflicts remain chronological and unexplained.

Complete assignments use source-keyed values from each owner. CP-learned Boolean
clauses remain natively owned by CP while their shared consequences reach theory
peers. The shared store does not duplicate the CP pin.

## Finite propagation and explanations

Fresh presolve rebuilds retain the originating cancellation token and accounting
destination. See [presolve cancellation and accounting](presolve.md) for ownership,
probe-count scope and cooperative deadline limits.

[PropagationSession](../klause/src/commonMain/kotlin/com/eignex/klause/propagation/PropagationSession.kt)
owns the finite-domain trail and fixpoint. Propagators supply sound source
reasons for deductions and conflicts. An assignment's undo lifetime can be deeper
than the effective level of its reason: Boolean pins use the deeper of that level
and the current decision depth so an asserting backjump retains its consequence.

The native CP analyzer and shared first-UIP analyzer have distinct counters and
ownership. A shared conflict with usable reasons can assert and backjump.
Sound resolvents that cannot assert retain chronological fallback. Root
refutations, unusable explanations and native CP analysis are outside the shared
asserting/non-asserting counters.

Table explanations use build-local caches for historical domains, carve positions
and materialized literals. These caches do not cross propagation or undo. Ground
supports use membership/value-set checks, and sparse root members avoid walks
over large unconditional holes. Support gathering can stop after all live values
of a ground column are covered, while every tuple is still checked for feasibility.
The reversible live prefix is committed once per completed sweep, including
empty-prefix conflicts; sibling branches restore filtering from trailed state.

LP explanations cite selected source bound witnesses and recursively expanded
row, fixing and cutoff premises. Current CP propagation and source LP conflicts
do not include an optional exact-theory row-propagation scanner or explanation
weakening pass. See [LP consumer contracts](lp/architecture.md#consumers-explanations-and-cuts).

## Learned-clause retention

The shared learned store has its cap off by default. When capped, reduction runs
at restart boundaries after pending attachments are drained. Glue clauses, units
and clauses used for propagation since the last reduction survive. Remaining
capacity keeps lower LBD first, then older insertion for ties. Protected clauses
can leave the retained count above the nominal cap.

Selection uses a Boolean drop mask. A bounded LBD histogram applies when the
maximum score is at most 4096 and at most four times the eligible count; sparse
ranges use the packed sort with the same ordering. This mechanism preserves the
retention rule. The native CP store keeps its own reduction policy.

See [SearchSession](../klause/src/commonMain/kotlin/com/eignex/klause/solver/search/SearchSession.kt)
and [ClauseDb](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/ClauseDb.kt).

## Portfolio slices

Resumable backtrack arms charge decisions, propagation visits and completed LP
work to their scheduler. The current conversion is one slice node per 4000
propagation visits and per 600 LP work units. Construction is charged once;
fractional charges carry forward and overspending is repaid by later slices.
Decision/move limits still count their original events.

Slices yield before consuming an alternative or after a completed node. A
pending sibling cannot be skipped when yielding, and reset discards a pending
alternative. Constructor refutations return immediately. A rejected last root
leaf can finish exhausted even if its completed work consumes the slice.

The scheduling conversion is not an elapsed-time guarantee. Propagation fixpoints,
construction, expensive factor calls, an LP solve and value-probe batches can
overrun a slice. Solve-wide cancellation remains available within propagation;
some factor internals are not covered by visit metering. Local-search segments
have separate atomic work.

Local-search optimization handles retain the live assignment, RNG, factor payloads,
restart progress and incumbent between segments. A slice pauses at an instruction
checkpoint or cancellation poll; an interrupted continuous-candidate check retains
its candidate for the next slice. Moves and restart transitions count as instructions.
The scheduler charges the observed instruction delta, while the solve-wide move
allowance is charged once across slices. Closing or reseeding discards the retained
state. Root refutations remain authoritative; an unsuccessful local-search walk is
incomplete.
An active local-search handle requires exclusive use of its solver and session;
portfolio workers use separate solvers and close a handle before reseeding it.
Optimization handles open through their session so its assumptions and state apply.
Sessions that decline resumable optimization retain their one-shot improvement stream.

The immutable local-search projection is initialized lazily and reused across draws.
Mixed-pool arms over the same model share it, including ALNS's inner local search.
Objective-bound overlays keep their own projections. Assignments, RNGs, weights and
invariant payloads belong to each live state. Projection construction, seeding,
factor calls and repair searches remain atomic work that can overrun a segment.
Built-in initial random restarts apply implicit seeding and the definition sweep
before initializing factor costs, avoiding an evaluation of the intermediate
random assignment. Custom restart policies retain their ordinary restart protocol.
ALNS skips its local-search fallback when the complete-engine bootstrap returns
without an incumbent after cancellation, avoiding fresh seeding after the deadline.
The one-way definition network contains immutable reader indexes; matching mixed-pool
arms share its lazy construction while each state applies definitions to its own assignment.
Boolean break/make vectors initialize on their first score query and are maintained
incrementally thereafter; strategies that do not query them avoid their initialization pass.
Weighted compound probes snapshot and scan only factors whose degrees change, in
factor-id order to retain the full-scan floating-point accumulation order.
A local-search arm whose initial probe pauses without an instruction receives one
base probe of its retained state before policy selection. Its preparation turn remains
charged; a second empty probe does not extend exploration again.

Optimization portfolios can reseed stale resumable arms after an incumbent;
`reseed-stale-threshold` defaults to 3 non-improving segments, with 0 disabling it.
Reseeding preserves terminal verdicts. Arm policy, scheduling and available
continuous-column capability determine which arms run; unsupported local-search
arms are filtered from mixed pools while local-search-only requests retain their
own behavior.

See [SliceBudget](../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/SliceBudget.kt)
and [portfolio](../klause/src/commonMain/kotlin/com/eignex/klause/portfolio/).

## Statistics

| Field | Meaning |
|---|---|
| `propagationWork` | Monotonic dispatch, literal, watcher, level and linear-term/reason visits, including work across undo |
| `rootPropagationWork`, `rootPropagationMs` | Constructor root-fixpoint work and time |
| `propagationMs` | Fixpoint elapsed time; some counted reason scans are outside it |
| Arm `maxMs`, `initMs` | Longest elapsed segment and handle construction cost |
| `openAssertingConflicts` | Shared first-UIP analyses yielding an asserting backjump |
| `openNonAssertingConflicts` | Sound shared conflicts retaining chronological fallback |
| `openReductionNs` | Total shared reduction selection and watch-rebuild time |
| `portfolioReseedStaleThreshold`, arm `reseeds` | Effective stale threshold and reseed count |

A nonzero non-asserting count is a reason to capture the conflict and inspect
component reasons. Zero does not establish complete factor reason coverage.
Missing metrics remain missing, including root theory refutations that never
enter shared search. Ratios of visit work to fixpoint time are coarse because
their scopes differ.
