# Portfolio scheduling and publication

A portfolio runs multiple strategies over one source model and objective. Workers
can improve incumbents, establish bounds or complete a proof. The coordinator
schedules their work and admits shared results only with the authority required for
that claim. One worker's failure to find a solution does not refute the model.

Execution has three parts: prepare retained workers, give them bounded slices, and
verify their publications before sharing or returning them. Slices preserve state;
reseeding or retiring a worker has its own lifetime and accounting rules.

Finite, linear open, polynomial, nonlinear-integer and quadratic source execution supply retained recipe
factories to `PortfolioInvocation` with the routing plan and current executable projection. The invocation owns worker
slots, seed offsets, parallel node quotas, scheduler controls, result verification
and cleanup. Composed arm attribution remains distinct from materialized replica
slots. Recipes retain their source-specific pools and reservations; mutable source
services registered with a slot close when the invocation releases that slot.
Open factories allocate private completion and walk state per slot, including
replicas. Native reservations divide the complete family's allowance across its
materialized slots. Complete-only invocations divide the whole native allowance
among their admitted slots.
Heuristic-only factories carry witness authority.

The [solver vision](../vision.md#shared-model-breadth) requires an available
complete-search/local-search pair for every supported model. Current admission gaps
remain implementation limits. [Sessions](sessions.md) owns assumptions;
[resource allowances](resources.md) owns containing budgets;
[LP certification](../lp/certification.md) owns numerical proof checks.

## Witnesses and proof scope

Open portfolios transport exact assignments in `Sample` rather than through a side
registry. `exactInts` and `exactReals` carry immutable authoritative coordinates; finite
samples retain private compact arrays. Constructor inputs and exported arrays cannot
mutate a published incumbent. Exact theory and reconstruction carriers follow the
same owned snapshot contract; live producer workspaces remain private. See
[model and ownership](../model.md). Copying an unchanged assignment preserves its
coordinates and theory acceptance. Changing coordinates requires a fresh witness check.
The exact objective is evaluated from the carried assignment, including integers outside
`Long`. Open affine invocations capture the original owner's exact objective coefficients
and constant once. Their exchange retains that source value separately from the minimized
execution value used by worker cutoffs; rounded IR coefficients do not order captured
source incumbents.

`OpenTheoryPipeline.execute` and `searchWithoutTheory` observer overloads publish
`SourceIncumbentPublication` after verified installation. The payload retains the owned
source sample, exact original-direction `SourceValue` and invocation version. The final
`OpenTheoryExecution.Optimize.acceptedIncumbent` retains the latest receipt independently
of a terminal's private proof witness. Callbacks run under the existing schedule lock;
callback failures propagate after every worker and source service has closed. Complete
single-worker invocations observe their source-checked improvements without allocating
portfolio replicas.

Affine mixed workers retain `LinearPortfolioSearch` handles and publish checked typed
terminals through the same scheduler hook. Scalar worker telemetry cannot establish a
strict limit or source ray. After all workers close, the invocation folds their retained,
already checked global root bounds with the shared original-source incumbent. Private
branch bounds and terminals that the scheduler did not admit cannot finish the source.
Cancellation retains completed partial bound metadata without running additional arithmetic
or promoting an interrupted proof. A ray's private base and its exact value remain paired;
the latest accepted point can be a different, better source incumbent.

A wide exact sample exposes no finite integer array. Supported linear workers retain
arbitrary-width coordinates through exact neighborhood completion. Windowed fallback
workers project warm starts and discard theory acceptance; their seeds cannot replace
exact incumbents or justify bounds.

Portfolio evidence names its source model and objective. These are opaque in-memory
identities: a fresh or transformed model needs its own check, even if structurally
equivalent. Polynomial worker and neighborhood completion publications retain their actual prepared
invocation, execution assumptions, owned point and execution objective telemetry. A native
witness check precedes that envelope; a model/kind label cannot replace the checked point.
Polynomial and nonlinear-integer pool admission consume the native publication and check the containing
invocation, actual point/value and both producer and consumer conditions.
Quadratic workers retain their original source invocation, point and exact value in
typed witness publications. Pool and terminal admission check both conditions and
source feasibility under the shared native allowance; a shared point retains its
producing publication instead of acquiring another worker's identity.
Finite real-neighborhood adapters allocate their retained queries when a worker handle
opens, after runtime binding. Source-checked bound workers publish that actual invocation;
unbound low-level workers retain checked coordinates without a runtime certificate.
Native nonlinear-integer workers retain their request plan and explicit source pins
in the same publication envelope; arbitrary-width coordinates keep their source
declaration mapping through neighborhood and retained traversal results.
Copies of unchanged exact witnesses retain their source certificate;
coordinate changes and warm-start projections discard it. Objective changes cannot reuse
objective bounds or unboundedness claims. Unconditional variable bounds use the model
scope without an objective. Finite factories bind shared root channels to the containing
invocation and publications to the worker's actual post-edit execution assumptions.
A conditional root floor or integer window cannot prune siblings with weaker conditions.

Verification preserves three outcomes. Accepted witnesses may become incumbents;
rejected proposals quarantine their producer without proving the model infeasible;
indeterminate checks publish nothing and do not count as a fault. Finite fixpoint
cancellation stays indeterminate. Uncertified real coordinates require a complete
source-row check, including strictness, reification and declared bounds, or the
configured source tolerance check. Unsupported checkers decline.
Scoped witness admission checks the containing invocation's Boolean pins and numeric
windows before the installed source checker. A numeric real restriction needs exact
coordinates; approximate telemetry cannot establish that the point satisfies it.

Bounds, infeasibility and unboundedness require a matching completed-proof certificate
in addition to identity. Native adapters issue these certificates only for their
engines' completed proof results; open local search has witness authority only. A
missing certificate cannot justify pruning or a terminal proof, although a separately
accepted feasible point can remain the best incumbent. The certificate is an in-memory
attestation of the engine's check, not a serialized proof or an independent proof
checker. Numerical proofs remain owned by their engines; a point alone never certifies a
recession direction.

Completed-result issuance can retain the actual `SolveResult` or `MinimizeResult`
and its immutable source invocation. Runtime binding retains the existing prepared
plan, actual original/execution model and objective references, direction and owned
assumptions; explicit revision capture remains optional. It does not manufacture a
source revision for an uncaptured operation. Execution cutoff values remain in native
minimized units, separate from exact original-source claims. That hook refuses foreign
scopes and completion
kinds unsupported by the result. Worker model, objective and invocation bindings
cannot change after materialization; repeated matching binding retains witness-only
withholding. Mixed invocation derivations cannot issue a completed certificate.
Its derivation verifier separately checks the actual
requested source claim; an engine completion without captured derivations stays
unavailable for independent proof checking. The legacy kind-only issuance remains
pending consumer migration. [Original-source evidence](evidence.md) defines exact
quadratic capture and its terminal verification contract.

Independent proof export remains a [vision requirement](../vision.md#independently-checkable-proof-files).
Serializing an in-memory certificate token does not provide a derivation.

Built-in shared channels belong to the same model and objective. Workers reject foreign
pools; objective and variable bound imports require matching certificates. The shared
incumbent exchange runs the source witness checker before installing a cutoff, including
direct engine publications. The low-level public portfolio API continues to support
caller-trusted unbound workers and its explicit witness checker; source pipeline
portfolios install the scoped verification contract.

Learned-clause exchange requires at least two materialized backtrack workers, including
replicas on parallel lanes. A lone complete worker keeps its learned database locally
without exporting portable clauses. Shared preparation and incumbent channels retain
their own lifetimes even when there is no clause peer.

### Theory-backed workers

Workers that need arithmetic completion share the same source scope and require
that check before publishing a witness. A neighbourhood refutation or failed
candidate check cannot establish global infeasibility. Complete workers retain
proof authority; heuristic workers retain witness authority.

[Theory contracts](../theories.md) define the available completion routes and their
invocation lifetimes. [Resource allowances](resources.md) owns cumulative budgets
across workers.

## Slice accounting and coverage

The counted schedule starts at 5000 node equivalents, grows by 1.5 after an ordinary
scheduled segment, and caps at 150000. Growth belongs to the shared schedule, rather
than to each arm. Initial probes and private-preparation revisits use the base allowance;
revisits do not grow the schedule. Counted segments also retain their elapsed-time cap.
The finite CLI exposes `slice-work`, `slice-max-work` and `slice-growth` for controlled
comparisons, and reports all three effective settings in portfolio statistics.
The [slice policy evidence](../evidence/slice-policy.md) records the bounded
comparison and rationale for retaining the common defaults.

Resumable backtrack arms charge decisions, propagation visits and completed LP work to
their scheduler. The current conversion is one slice node per 4000 propagation visits
and per 600 LP work units. Construction is charged once; fractional charges carry
forward and overspending is repaid by later slices. The solve-wide decision/move cap retains its separate currency; see
[resource allowances](resources.md) for persistent parallel quotas and scope composition.

### Private CP preparation

General CP optimization handles retain one private construction cursor through projection,
state buffers, occurrence and event indexes, watch registration, root queue seeding and
propagation, assumptions, LP planning and shared-search installation. Long construction
scans checkpoint every 256 entries or visits, including scans inside a wide factor's
index lists. Runtime array allocation, one domain materialization, factor invocation,
and payload snapshot remain atomic operations; the batch bound is
not a hard elapsed-time guarantee. A handoff that reaches cancellation or its segment
deadline returns before entering search; the next segment starts traversal. The packed
native-SAT optimization path and public
satisfaction/repair constructors retain their eager lifecycle.

A paused cursor cannot publish an assignment or an incomplete fixpoint as a refutation.
A completed root conflict can return its refutation even when its work consumes the
slice. Successful installation transfers the prepared state and work ledger once;
assumption pins, watcher entries and LP plans are not restarted on resume. Root and
assumption propagation retain pending wakes between batches. Fractional work charges
and overspend debt carry through installation into search. Construction scans spend
wall time without manufacturing decisions or LP solve work; solve-wide decision/move
allowances are unchanged. Closing drops the private cursor and closes any installed LP
owner. Ordinary installation exceptions close the handle before propagating the failure.
See [search](search.md#finite-propagation) and [resource allowances](resources.md).

## Preparation revisits

Before the first incumbent, an optimization arm with pending private preparation or a
local-search arm that has not executed an instruction receives preparation revisits
after all initial siblings have been admitted. Unfinished arms rotate in arm order until
they enter search or terminate. Each revisit uses the base allowance and the regular
family time share; it does not grow later slices. Dedicated lanes resume their own
handles directly. No preparation revisit delays an unadmitted sibling.

Before the first accepted witness, a selected complete-search family runs its available
arm with the least charged work. Shared clauses, root fixings and objective floors
remain credited, but cannot exclude a silent search from its family's coverage. Before a
witness, observable violation reductions can earn a family more turns; complete-search
root fixings and glue clauses do not favor its family, because deduction throughput does
not predict the first witness. Those counters remain recorded. After the first witness,
arm selection also follows that policy. Initial admission, preparation revisits and
explicit minimum time shares retain precedence. A segment reporting no charged work uses
its slice allowance for coverage accounting.

Slices yield before consuming an alternative or after a completed node. A pending
sibling cannot be skipped when yielding, and reset discards a pending alternative.
Constructor refutations return immediately. A rejected last root leaf can finish
exhausted even if its completed work consumes the slice.

Local-search arms follow the [retained handle
contract](local-search.md#handles-and-exclusive-ownership).

The scheduling conversion is not an elapsed-time guarantee. Propagation fixpoints,
construction, expensive factor calls, an LP solve and value-probe batches can overrun a
slice. Solve-wide cancellation remains available within propagation; some factor
internals are not covered by visit metering. Local-search segments have separate atomic
work.

## Reseeding and arm failures

Optimization portfolios can reseed stale resumable arms after an incumbent;
`reseed-stale-threshold` defaults to 3 non-improving segments with fresh charged work,
with 0 disabling it. Debt-only pauses do not advance plateau counters. Backends can
defer reseeding while bounded preparation owns pending work; session ownership wrappers
forward that guard. A bounded preparation that earns an accepted incumbent improvement
can continue across ordinary slices before the adaptive policy chooses again. Explicit
minimum shares remain first, and normal family selection resumes when preparation
finishes or expires. The containing arm retains its work charges, slice limits and
whole-solve deadline. A pending clausal primal repair retains its private state until
its existing phase allowance finishes.
Complete-search arms without a restart schedule retain traversal because closing a proof
gap need not produce a better incumbent. Imported incumbent cutoffs still apply.
Observable search families and complete workers configured with a restart schedule
retain their plateau reseeding policy under the same incumbent. Each arm retains
completed-handle totals separately from its current cumulative snapshot. Repeated
snapshots replace the live entry; closing captures the final counters and merges the
handle once, including when reseeding or stopping the pool. Open-theory handles capture
live-round progress before releasing their traversal; closing a completed round does not
capture its counters again. Ordinary arm exceptions retire their producer and leave
siblings running. The schedule retains one diagnostic per arm with its identity,
operation, segment, charged work, exception type/message and cause trace. Type, message
and trace are capped at 128, 1024 and 4096 characters. Output callback exceptions still
propagate; source-refuted claims remain quarantine faults rather than ordinary exception
diagnostics. Reseeding preserves terminal verdicts. Arm policy, scheduling and available
continuous-column capability determine which arms run; unsupported local-search arms are
filtered from mixed pools while local-search-only requests retain their own behavior.

## Incumbent guidance

FlatZinc search hints select tiered variable and value heuristics without inheriting the
free-search preset's phase saving or restart schedule. The source hint's solution-guided
value wrapper remains available without saved phases taking precedence over it.
Optimization updates guidance only after admitting a complete improving incumbent.
Solution-guided selectors retain their first descent, then adopt better verified pooled
incumbents when traversal resumes, without discarding their trail or learned state.
Other pooled solution phasing retains its restart-boundary import policy.

## Model-specific worker recipes

These policies choose workers for particular model structures within the shared
scheduling and publication contract. Explicit worker pools retain their requested
composition.

### Scheduling neighborhood diversity

A curated sequential mixed optimization pool over a scheduling model pairs the original
plateau local-search arm with a primary-repair plateau variant in the preceding generic
CBLS slot. Both start from the original plateau arm's seed; the original arm and every
remaining worker retain their positions and seeds. The variant defers supplemental
Element repairs to complete improving fallback selection. The original arm retains
its full repair neighborhood. Explicit local-search pools, local-search-only execution,
parallel pools and non-scheduling models retain their selected recipes.

### Finite objective recipe diversity

A curated single-core mixed finite-CP optimization pool with at least six configured
slots admits the restart-level selector portfolio beside the SAT guard when its initial
backtrack pair has no LP recipe. The selector worker uses its own UCB1 policy over
variable and value heuristics at internal restarts. It occupies the conflict-driven
slot; an expanded pool containing both recipes swaps their positions. The configured
pool size, local-search positions, slice allowances and family scheduling are preserved.
Explicit recipes, backtrack edits, source annotations, continuous models and parallel
lanes retain their selected recipes.

### LP arm admission

Continuous-column optimization models rank the default LP backtrack arm after the SAT
guard and conflict-driven core. A curated sequential mixed optimization pool with at
least six configured slots appends an applicable default LP arm when its configured
backtrack slots have not admitted it. The appended arm follows the configured workers
and auxiliary ALNS arm, retaining their positions and seeds. When the objective has no
continuous coefficient, the auxiliary LP arm joins after the first incumbent.
Continuous objectives admit it before the first incumbent. LP ceilings that disable
bounding omit the auxiliary LP arm and its reservation. Explicit arm pools control
admission and techniques. Curated single-core mixed optimization reserves half the
scheduled time for this LP arm until the first incumbent when the objective has a
continuous coefficient and the prepared model has at least as many Boolean and integer
coordinates combined as real coordinates, or every prepared integer coordinate is bounded
within zero and one. Continuous-majority models with general integer coordinates retain
initial LP admission under ordinary family scheduling, allowing incumbent workers to
compete for initial time while LP can earn progress credit before the improving-phase
probe. Binary models retain reserved LP descent for their continuous objective.
Individual segments retain work charging and time caps; the remaining time follows the
bandit. After the first incumbent, all scheduled time follows the bandit. Explicit
backtrack pools and parallel lanes retain their requested scheduling. The auxiliary LP
arm receives one base improving-phase probe if it has earned no progress credit. An
unproductive probe retires the arm and closes its handle; retirement preserves the
incumbent without claiming exhaustion. A witness, objective or bound improvement, root
deductions, or used shared contributions retain the arm under normal bandit scheduling.
Configured LP slots do not retire under the auxiliary probe policy. An unproductive
auxiliary improving-phase probe does not decay the retained workers' bandit or family
evidence, preserving their policy state when the probe is discarded.

See
[SliceBudget](../../klause/src/commonMain/kotlin/com/eignex/klause/backtrack/SliceBudget.kt)
and [portfolio](../../klause/src/commonMain/kotlin/com/eignex/klause/portfolio/).

#### Satisfaction pools

The satisfaction backtrack palette includes conservative LP after default and aggressive
LP. Applicability, model class and LP ceilings order and filter the palette. Root variable shaving and hull
pruning remain explicit plan options. Root cuts follow the resolved plan. Curated
satisfaction workers require an explicit `lp-root-cuts=true` override to harvest root
cuts; explicit pools retain their requested root-cut plans.

The clausal complete-search recipes use clause learning, EMA restarts and a true-first
saved phase, without pseudo-Boolean learning or inprocessing. Their balanced and stable
variants use VSIDS decay 0.975 and 0.99, with database caps of 4000 and 2000. Curated
admission requires a clausal model; pseudo-Boolean models retain their cutting-plane
palette.

Clausal satisfaction leads its complete-search slots with the balanced recipe. A
curated sequential mixed pool reserves 98% of active time for that worker after the
initial probes: a refutation emits no incumbent progress from which family rewards
could predict the approaching proof. Explicit pools, parameter edits and search
annotations retain unreserved scheduling. Automatic single-core mixed clausal
satisfaction uses one local-search and one complete arm, avoiding probe time and clause
exchange between multiple complete workers. Explicit arm counts, named or edited
pools, annotations, custom fallback counts and parallel execution retain their
configured pool size. CLI property and environment arm counts are explicit overrides.

Clausal satisfaction leads its curated local-search slots with fixed-break probSAT,
using tabu tenure 10 and an improving aspiration rule without a strategy restart
schedule. Optimizing clausal models retain the general local-search order. Explicit
local-search pools retain their selected recipes.

## Statistics

The portfolio owns `SolveStats.run.wallMs` for each satisfaction or optimization
invocation. Its monotonic clock spans schedule initialization, all segments,
witness verification, lane completion and retained-handle disposal. Worker counters
merge independently; backend tags and timeout flags retain their existing merges.
The portfolio duration replaces worker run windows rather than summing them or
taking their maximum. Per-arm scheduled times remain inclusive segment totals and
can overlap across parallel lanes.

| Field | Meaning |
|---|---|
| Arm `maxMs`, `initMs` | Longest elapsed segment and handle opening cost |
| `portfolioReseedStaleThreshold`, arm `reseeds` | Effective stale threshold and reseed count |

`initMs` measures opening the handle. Preparation deferred into slices contributes to
segment time and live propagation counters; it is not included in opening time.

[Search statistics](search.md#statistics) and [benchmark
records](../../klause-bench/README.md#saved-results) define propagation, conflict and
saved-result accounting.

## Prepared strategy admission

Source adapters consume the [prepared plan](../model.md#engine-projections-and-factor-capabilities)
for strategy and operation admission. A component contract records the direct
projection and required completion actually selected by preparation. Semantic
identifiers and worker labels do not establish support. The direct factor inventory
cannot replace whole-source completion, including continuous products.

Admission is separate from procedure completeness and claim verification. Integer
traversal can run without a termination guarantee. Heuristic workers have witness
only authority even when a neighborhood is decided exactly. Independent source
bounds and proofs require their own checked evidence. Unsupported components and
operations have explicit decline causes; exhausted caller or representation
allowances remain invocation stops.

Finite, open, polynomial and nonlinear-integer ordinary portfolios bind `EngineInvocation` to the
retained plan, actual execution model/objective and captured source/execution pins.
Their terminal admission consumes `CompletedEngineResult` with the actual result;
exhausted optimization also retains the last finite cutoff read by that worker.
A conditioned or foreign completion cannot finish a weaker runtime invocation.
Finite materializers retain the post-edit parameter assumptions before binding; a
failed binding closes the allocated worker before the invocation unwinds slot resources.
Independent original-source derivation capture remains a separate contract.

## Exact theory strategy execution

Polynomial, nonlinear-integer and linear open mixed invocations and parallel single-strategy
requests use the same construction path as finite execution. Complete-only affine
requests supply private complete workers without adding heuristic arms. Polynomial workers keep private arithmetic
invocations; fallback ALNS retains its bootstrap and repairs. Nonlinear integer
traversal retains tuple enumeration, candidate state and arithmetic work across
slices; exact neighborhoods retain their centers and adaptive state. CLI parallelism
reaches these factories, and replicas keep private state and native reservations.
Quadratic finite and open-integer adapters supply retained traversal and exact
neighborhood factories to that invocation. Their exchange keeps original and
minimized algebraic values separately. A typed terminal publication enters the
same schedule only after source, condition, objective, authority, witness and exact
attainment/strictness checks. Scalar worker telemetry remains incomplete; the
accepted native result retains its optimum, strict limit or ray semantics. An accepted-incumbent
observer receives the installed immutable assignment, its already evaluated exact
value and a monotonic invocation version under the schedule lock. It runs only for
strict verified exchange improvements; callback failures stop the invocation and
remain primary through retained-handle cleanup. Quadratic route-level observers carry
`QuadraticIncumbentPublication` with the already evaluated original-direction value.
Final optimum folding retains the exchange's accepted assignment, value object and
version when it attains the verified bound; scalar telemetry does not drive publication.
The latest receipt is the publication incumbent for every termination. Limit/ray
certificates retain their own checked witness; rendering a terminal marker must not
re-publish that private witness as another improvement. Atomic neighborhood repairs
retain scheduler/invocation cancellation while excluding only segment deadlines.
Single-worker native paths use the same exact publication payload and propagate output
failure after cleanup. FIXED ignores additional lane requests.
Heuristic completion cannot finish a source proof. Single-engine execution retains
its own traversal semantics; FIXED runs one lane. Exact-neighborhood preparation
contributes to scheduler instruction coverage once; retained slices keep that charge
separate from moves and atomic repair charges.

Supported linear sources pair complete traversal with exact-coordinate local search
and adaptive block repair, including real-only models and arbitrary-width integers.
Neighborhood windows restrict proposals rather than source domains. Only checked
source witnesses enter the pool; neighborhood refutations carry no source proof.
Boolean or continuous linear objectives use strict-improvement theory rounds when
the integral descent does not apply. These rounds admit completed source proofs but
do not guarantee terminating optimization for every admitted source.

Finite real models with fixed discrete coordinates use the same repair driver. Mixed
products become exact linear rows under those fixed coordinates and are checked
against their original relation before publication. Their exchanges order exact
objective values and project scalar cutoffs outward. Finite portfolios, including
polynomial finite objectives and continuous costs, also use exact incumbent ordering.
Complete finite workers retain exact integral or certified real scores and project
pruning cutoffs outward. Witness-only workers retain that authority through outer
binding.

Exact neighborhood handles retain centers, radii, random state and adaptive weights
through slices. Their widening policy handles stalls without reopening the native
allowance. A completed sibling can settle a source before another worker runs.
