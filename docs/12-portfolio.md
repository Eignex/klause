# 12. Portfolios

Complete search and local search have complementary strengths, and Klause rarely
runs just one of them. A portfolio runs multiple strategies over one source model
and objective. Workers can improve incumbents, establish bounds or complete a
proof; the coordinator schedules their work and admits shared results only with the
authority required for that claim. One worker's failure to find a solution does not
refute the model.

Execution has three parts: prepare retained workers, give them bounded slices of
the [resource allowance](06-sessions.md#resource-allowances), and verify their
publications with [source validation](07-presolve.md#source-witness-validation)
before sharing or returning them. Prepared admission determines which complete
and heuristic arms can execute the source; a declined arm keeps its explicit cause.

The chapter starts with how an invocation is assembled and which strategies it
admits, then follows a worker's output: which [witnesses](#witnesses-and-proof-scope)
it may publish and what exhaustion establishes, how the [scheduler](#scheduling)
hands out slices, and the [worker recipes](#worker-recipes) chosen for particular
model structures.

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

Source adapters consume the [prepared plan](02-model.md#engine-projections-and-factor-capabilities)
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
[original-source derivations](13-evidence.md).

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
[Search statistics](09-learning.md#statistics) define propagation and conflict
accounting.

## Witnesses and proof scope

A portfolio is only as sound as what it lets workers share. Every publication
carries an identity, checking it has three outcomes, and bounds, refutations and
exhaustion need extra evidence. The checks themselves are
[source validation](07-presolve.md#source-witness-validation); the portfolio
supplies publication scope and tolerance policy.

### Publication identity

Portfolio evidence names its source model and objective. These are opaque in-memory
identities: a fresh or transformed model needs its own check, even if structurally
equivalent. Source arm materialization installs one immutable `SourceWorkerBinding`
before scheduler admission, capturing model and objective identity, source scope,
the producer invocation, a separate containing sharing invocation and proof
authority. Producer conditions cannot justify unconditional imports into siblings,
and a completed binding cannot promote witness-only authority.

Every worker kind — finite, linear, polynomial, nonlinear integer and quadratic —
publishes its actual prepared invocation, execution assumptions, owned point and
objective value. Pool admission checks the containing invocation, the point and both
producer and consumer conditions after a native witness check. A model or kind label
cannot replace the checked point, and a shared point keeps its producing publication
instead of acquiring another worker's identity.

### Owned assignments

Open portfolios transport exact assignments in `Sample`: `exactInts` and
`exactReals` carry immutable authoritative coordinates, and published incumbents
cannot be mutated through constructor inputs or exported arrays (see
[model ownership](02-model.md)). Changing coordinates or projecting a warm start
discards acceptance and requires a fresh check. The exact objective is evaluated
from the carried assignment, including integers outside `Long`, and incumbents are
ordered by that source value, separately from the minimized execution value used by
cutoffs.

### Verification outcomes

Verification preserves three outcomes. Accepted witnesses may become incumbents;
rejected proposals quarantine their producer without proving the model infeasible;
indeterminate checks publish nothing and do not count as a fault. Finite fixpoint
cancellation stays indeterminate. Uncertified real coordinates require a complete
source-row check or the configured source tolerance check, and scoped admission
checks the containing invocation's pins and windows first.

Open theory execution publishes `SourceIncumbentPublication` after verified
installation, carrying the source sample, exact original-direction `SourceValue` and
invocation version. Callbacks run under the schedule lock.

### Bounds and certificates

Bounds, infeasibility and unboundedness require a matching completed-proof
certificate in addition to identity. Native adapters issue these certificates only
for their engines' completed proof results; open local search has witness authority
only. A missing certificate cannot justify pruning or a terminal proof, although a
separately accepted point can remain the best incumbent. The certificate is an
in-memory attestation, not a serialized proof; see
[proof export](01-introduction.md#independently-checkable-proof-files). A point alone
never certifies a recession direction.

Objective changes cannot reuse objective bounds. A conditional root floor or integer
window cannot prune siblings with weaker conditions. Execution cutoffs stay in native
minimized units, separate from exact original-source claims checked by the
[derivation verifier](13-evidence.md). Private branch bounds and terminals the
scheduler did not admit cannot finish the source.

### Shared channels

Built-in shared channels belong to the same model and objective. Workers reject
foreign pools; objective and variable bound imports require matching certificates.
The incumbent exchange runs the source witness checker before installing a cutoff,
including for direct engine publications. The low-level portfolio API also
supports caller-trusted unbound workers with an explicit witness checker. Learned
clauses are exchanged only when at least two backtrack workers exist.

### Exhaustion and metadata

Optimization exhaustion without an incumbent is an original-source refutation only
when the installed complete worker routes objective cutoffs through the scheduler and
used no finite shared cutoff. The scheduler retains observed cutoffs for proof
admission: a finite cutoff supports only its conditional bound, while unrestricted
exhaustion with a checked incumbent establishes attainment. Heuristic and
improvement-only arms gain no completion authority from exhaustion.

Finite metadata reports complete search only when a backtrack-family worker permits
source proofs and is neither auxiliary nor improvement-only; this metadata never
establishes a verdict.

### Theory-backed workers

Workers that need arithmetic completion share the same source scope and require that
check before publishing a witness. A neighbourhood refutation or failed candidate
check cannot establish global infeasibility. Complete workers retain proof authority;
heuristic workers retain witness authority. [Theory contracts](14-theories.md) define
the completion routes and their invocation lifetimes.

MPS finite invocations complete continuous columns through numerical residual LP
completion, and the configured source tolerance callback reaches every worker kind.
Exact MPS invocations supply no tolerance callback, so a floating completion cannot
acquire exact authority through sharing. Open numerical fallback admits complete
numerical workers whose typed terminals pass the same invocation and evidence checks;
no worker materializes a finite domain for an open numerical declaration.

## Scheduling

With publication rules fixed, the scheduler decides which retained worker runs next
and for how long. It hands out bounded slices of one shared schedule, lets expensive
preparation proceed across slices, guarantees complete search a share of time before
the first witness, and reseeds workers whose progress has stalled. None of these
decisions carries witness or proof authority; they only order work under the
[resource allowances](06-sessions.md#resource-allowances).

### Slice accounting and coverage

The counted schedule starts at 5000 node equivalents, grows by 1.5 after an ordinary
scheduled segment, and caps at 150000. Growth belongs to the shared schedule, rather
than to each arm. Initial probes and private-preparation revisits use the base
allowance; revisits do not grow the schedule. Counted segments also retain their
elapsed-time cap. The finite CLI exposes `slice-work`, `slice-max-work` and
`slice-growth`, and reports all three effective settings in portfolio statistics.

Backtrack arms charge decisions, propagation visits and LP work to their scheduler
at the [slice prices](06-sessions.md#resource-allowances); the solve-wide decision/move cap keeps its
separate currency. Slices yield before consuming an alternative or after a completed
node, and a pending sibling is never skipped. The conversion is not an elapsed-time
guarantee: a fixpoint, factor call or LP solve can overrun a slice. Local-search arms
follow the
[retained handle contract](11-neighborhoods.md#handles-and-exclusive-ownership).

#### Private CP preparation

General CP optimization handles keep one private construction cursor through
projection, indexes, watch registration, root propagation, assumptions, LP planning
and shared-search installation, checkpointing every 256 entries. A handoff that
reaches its segment deadline returns before search, and the next segment resumes
without restarting completed steps. A paused cursor cannot publish an assignment or
an incomplete fixpoint as a refutation; a completed root conflict can. The packed
native-SAT path and public satisfaction constructors stay eager. See [finite propagation](08-search.md#finite-propagation).

#### Polynomial root preparation

Polynomial complete workers defer component initialization into their retained
handle, as does clausal optimization with additional components. Root
normalization, witness probing and projection keep completed phases across slices,
and subresultant preparation checkpoints in small batches. Every continuation keeps
the same native allowance, and unfinished initialization publishes neither a point
nor a refutation.

Within a polynomial query, root reduction and exact search cooperate on committed
equivalent formulas. Snapshots keep their reconstruction fibres, both cursors
charge the same allowance, and a new snapshot can replace search only at a
checkpoint. [Polynomial execution](16-nonlinear-arithmetic.md#decision-procedure)
owns reconstruction, cumulative probing and refutation authority.

#### Auxiliary incumbent evaluation

An auxiliary arm keeps its preparation and receives a bounded search trial after an
incumbent arrives, running before ordinary selection once every initial sibling has
been admitted. The trial is capped at twelve maximum work allowances and four base
time allowances after preparation, and does not advance the shared ladder; overspend
consumes the trial. Productive arms return to the ordinary policy and unproductive
arms retire.

### Preparation revisits

Before the first incumbent, an optimization arm with pending private preparation, or
a local-search arm that has not executed an instruction, receives preparation
revisits after all initial siblings have been admitted. Unfinished arms rotate in arm
order at the base allowance until they enter search or terminate; no revisit delays
an unadmitted sibling.

Before the first witness, family selection rewards observable progress toward
feasibility. Shared clauses, root fixings and objective floors stay credited but earn
no extra turns, because deduction throughput does not predict the first witness;
violation reductions can, and a working heuristic segment without progress supplies
plateau feedback. Preparation and zero-work pauses supply no failure signal.

Complete workers keep an elapsed coverage floor until the first witness or
refutation: together they are owed one family share of active time, with one base
time allowance of lag, and below-floor workers run before ordinary selection. No
fixed time is promised to a worker, and because coverage depends on elapsed time, a
single lane and seed do not make the schedule reproducible.

### Retained proof coverage

With an accepted incumbent, retained non-restarting complete workers share half the
active time since its last improvement. The least-funded free worker runs before
ordinary reward selection; each accepted improvement resets the accounting. Coverage
accrues from that improvement without a catch-up delay, so long competing segments
do not consume a short run's proof allocation before coverage begins. Initial sibling
probes, explicit minimum shares and bounded auxiliary trials retain precedence.
Segment overruns can delay coverage.

This share protects exhaustive traversal whose progress can stay invisible until a
proof completes. Only live resumable backtrack workers with source-proof authority
qualify; improvement-only workers, auxiliary proof workers and unproductive auxiliary
incumbent trials keep their separate policies. Restarting workers and heuristics
remain under reward selection. Scheduling grants no proof authority and does not
change incumbent validation or terminal admission.

### Reseeding and arm failures

Optimization portfolios can reseed stale resumable arms after an incumbent;
`reseed-stale-threshold` defaults to 3, with 0 disabling it. Restart-configured
backtrack arms count equivalent base work segments, so short fragments cannot
destroy retained traversal; accepted improvements clear the count, and reseeding
waits while bounded preparation or interrupted root rounding owns pending work.

Complete-search arms without a restart schedule retain their traversal, because
closing a proof gap need not produce a better incumbent. Reseeding preserves
terminal verdicts, and a closed handle's totals merge into its arm once.

Ordinary arm exceptions retire their producer and leave siblings running; the
schedule keeps one truncated diagnostic per arm. Output callback exceptions still
propagate, and source-refuted claims remain quarantine faults.

### Incumbent guidance

FlatZinc search hints select tiered variable and value heuristics without the
free-search preset's phase saving or restarts. Optimization updates guidance only
after admitting a complete improving incumbent; solution-guided selectors adopt
better verified pooled incumbents when traversal resumes, keeping their trail and
learned state.

At its first root, a non-restarting retained CP worker with a shared cutoff revalidates
the current pooled assignment as a starting incumbent. An equal-cutoff witness can
supply local objective propagation without priming guidance or earning progress.
Restarting workers retain their discovery and root-primal startup path. Pool membership
alone supplies no authority; source validation and conditional proof admission still apply.

## Worker recipes

Publication and scheduling apply to every pool. Recipes decide which workers a
curated pool contains for particular model structures, and how exact arithmetic
sources supply their workers. A recipe changes pool composition, never witness or
proof authority.
Explicit pools, backtrack edits and source annotations keep their requested
composition throughout, and containing time, work and LP allowances stay shared.

### Model-specific worker recipes

#### Scheduling neighborhood diversity

A curated sequential mixed optimization pool over a scheduling model pairs the
original plateau local-search arm with a primary-repair variant in the preceding
generic CBLS slot. Both use iterated local-search restarts (basin hopping over a
population of three) from the original arm's seed. The variant routes supplemental
Element repairs into an alternative list consulted only by improving fallback
selection, while the original arm keeps its full repair neighborhood.

#### Annotated finite-domain diversity

A curated single-core mixed optimization pool with at least six configured arms
keeps unrestricted first-fail search beside a source annotation on nonbinary
finite-CP models with a circuit or subcircuit constraint, when the annotation selects
integer first-fail with minimum values. A tiered annotation restricts candidate
scope, so the unrestricted worker adds a distinct branching capability. Search tiers
follow safe translated integer aliases through presolve, including circuit successor
offsets, before admission and worker construction. It adds at
most one complete worker and no allowance.

#### Finite objective recipe diversity

A curated single-core mixed finite-CP optimization pool with at least six configured
slots admits the restart-level selector portfolio (`selector-switch`) beside the SAT
guard when its initial backtrack pair has no LP recipe. The worker restarts on a Luby
schedule with base 100 and, at each restart, a UCB1 bandit picks one of four
variable/value pairs: VSIDS with minimum value, dom/wdeg with solution-guided minimum
value, activity-based search with impact values, and conflict ordering over dom/wdeg
with minimum value. A run is rewarded when it finds a solution. The worker occupies
the conflict-driven slot, preserving pool size and allowances.

#### LP arm admission

Curated sequential mixed finite optimization pools add LP-bounded backtrack workers
for three structures, skipped when LP is unavailable, the pool is explicit or
annotated, or execution is parallel or single-engine. See
[ordinary tableau admission](17-linear-programming.md).

- **Binary packing.** For nonnegative integer capacity rows with an objective that
  rewards items, the default LP recipe takes the conflict-driven slot beside the SAT
  guard.
- **Integer linear models.** Pools of at least six slots over pure integer linear
  rows with an integer objective append an aggressive LP companion, which also runs
  under a cuts-off ceiling. Eligibility reads complete unconditional row conjunctions
  with 64-bit integer constants, including normalized exact source rows.
- **Continuous columns.** The default LP arm ranks after the SAT guard and
  conflict-driven core and joins first-solution search under the ordinary bounded
  slices for both finite and continuous objectives. Single-core pools reserve half the
  scheduled time for it until the first incumbent when the objective is continuous.
  An auxiliary LP arm without progress then receives a
  [bounded evaluation](#auxiliary-incumbent-evaluation),
  and an unproductive trial retires it without claiming exhaustion.

#### Satisfaction pools

Curated sequential mixed finite-CP satisfaction pools of at least six slots keep
dom/wdeg and first-fail in the free and selector-switch slots when the selected
complete workers contain no LP recipe. Wider pools swap an existing matching
recipe into each slot. Pool size, local-search positions and containing allowances
stay fixed; explicit, annotated and parallel pools keep their selected palettes.

The satisfaction backtrack palette includes conservative LP after default and
aggressive LP; applicability, model class and LP ceilings order and filter it. Root
variable shaving and hull pruning remain explicit plan options, and curated
satisfaction workers harvest root cuts only with an explicit `lp-root-cuts=true`.

Clausal complete-search recipes use clause learning, EMA restarts and a true-first
saved phase; their balanced and stable variants differ in VSIDS decay (0.975, 0.99)
and database cap (4000, 2000). Pseudo-Boolean models keep their cutting-plane
palette. Clausal satisfaction leads with the balanced recipe and, in a curated
sequential pool, reserves 98% of active time for it after the initial probes, because
a refutation emits no incumbent progress from which rewards could predict it. Its
local-search slots lead with fixed-break probSAT.

#### Boolean objective core search

Curated mixed finite optimization pools append a retained core-guided complete worker
when every objective term is Boolean and the model has no continuous columns. The
worker assumes all positive residual objective terms false and asks for a satisfying
assignment. A refutation returns a core: a set of terms at least one of which must be
violated. The worker adds the core's minimum residual weight to an exact lower bound
and relaxes the core with a reified cardinality, replacing its violation count `s` by
`1 + sum(k = 2..n, s >= k)`; only the first unspent threshold of each count is
materialized. Weights and the bound use arbitrary-precision integers.

Published incumbents project away auxiliary selectors and pass the source check, and
optimality requires exact equality between the original objective and the
accumulated bound. Auxiliary clauses never enter shared channels. On a single lane
with another complete worker, the core worker joins after the first incumbent once
ordinary complete workers stall, with the allowance described in
[resources](06-sessions.md#resource-allowances); parallel pools admit it immediately, and `core-guided`
selects it directly.

### Exact theory strategy execution

Polynomial, nonlinear-integer and open linear invocations use the same construction
path as finite execution. Polynomial workers keep private arithmetic invocations,
nonlinear integer traversal keeps its candidate state across slices, and quadratic
adapters supply retained traversal and exact neighborhood factories. A typed terminal
enters the schedule only after source, condition, objective, witness and exact
attainment or strictness checks. Replicas keep private state and reservations.

Supported linear sources pair complete traversal with exact-coordinate local search
and adaptive block repair, including real-only models and arbitrary-width integers.
Neighborhood windows restrict proposals rather than source domains, so neighborhood
refutations carry no source proof. Exact neighborhood handles keep centers, radii and
adaptive weights through slices and widen on stalls without reopening the native
allowance.

---

Previous: [11. Neighborhoods and restarts](11-neighborhoods.md) · [Contents](README.md) · Next: [13. Original-source derivations](13-evidence.md)
