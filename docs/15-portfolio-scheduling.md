# 15. Portfolio scheduling and workers

The portfolio scheduler allocates bounded opportunities among admitted workers.
Worker recipes supply complementary search behavior under the shared publication and proof contracts.

## Scheduling

The scheduler assigns bounded slices, revisits pending preparation and reseeds stale
workers under the containing [allowance](06-sessions.md#resource-allowances).
Scheduling changes work order, never publication authority.

Clause-learning credit follows each search instance's cumulative counter. Opening
a replacement handle or an independent one-shot search resets that counter's cursor;
retained slices earn only new clauses. Root-fixing maxima and the best violation
remain shared across the worker's searches within the invocation.

`portfolio-probes=heuristic-second` gives the first initially eligible local-search
worker the probe after the leading complete worker. It applies to curated single-core
mixed finite Boolean/integer optimization with a containing deadline. Source search
annotations retain their annotation workers. Deferred, improvement-only
and auxiliary workers keep their admission rules; early core trials keep their priority.
The remaining probes retain their order, and all workers retain their identities, seeds,
probe weights and work limits. Initial attempts and silent complete-worker coverage
remain required. This option changes the state and time available to later probes;
it grants no additional source allowance.

`portfolio-probes=family-work` delays additional curated local-search starts until
one available heuristic has spent the existing base work allowance. Complete
workers retain their initial probes and silent coverage, and probe fractions keep
the full initial pool as their denominator. Observed failed worker work stays recorded but
does not complete another worker's sample. An unavailable or retired first worker
allows the next eligible heuristic to start its own sample. Reaching the allowance
opens the remaining heuristic starts for the invocation; it grants no work or time.
The same curated single-core finite integer scope applies. `heuristicStart` statistics
retain required work, observed work by worker and completion separately per invocation.
With `portfolio-publications=segment`, pending private offers finish their checks
before the scheduler settles that query's sample work. Checking contributes time,
not native sample work. Factory materialization and shared preparation precede
scheduling; this gate controls queries.

The worker sampler owns primitive success and trial totals per arm. Restricted
selection reads those totals directly; fading preserves the prior and the
accumulator's arithmetic order. The library selector's snapshots synchronize when
that selector is used.

The opt-in `portfolio-reward=source` ablation retains ordinary feasibility rewards.
After a source incumbent exists, only an accepted strict incumbent or terminal
closure earns reward. Violation reductions, created deductions and delayed sharing
credit stay observable without earning improvement turns. Ordinary rewards remain
the default. The experiment changes neither admission nor complete-worker coverage;
construction, preparation, checking and retirement retain their ordinary accounting.

`portfolio-reward=source-rate` prices finite positive accepted objective gains against
observed factory and segment time. Segment time includes preparation, initialization
and coordinator checks. Failed and refuted segments earn zero reward while their
cost remains in the peer comparison. Unproductive time and feasibility setup remain
owed until that worker's next priced gain. Its gain rate is compared with other workers'
cumulative accepted-gain rate; common objective rescaling preserves this comparison.
Segments without a strict gain retain ordinary feasibility rewards. A segment
that finds the first witness and then improves it prices those later accepted
gains against its full startup and segment cost. The first witness itself supplies
no invented objective gain. Publication gains accumulate in a common normalized
scale, including batches whose raw sum would overflow. Phase diagnostics retain
the segment's selection phase, and the ordinary ledger keeps its phase boundary.
Idle credit earns no improvement reward, and
accepted terminal closure earns unit reward. Accounting is private to an invocation.
Source preparation, final retirement and independent checking keep their containing
run costs; this score supplies neither witness nor proof authority.

`portfolio-reward=source-rate-family` retains the worker rate and also prices gains
by family. Observed factory and segment costs from every sibling remain owed until
the family's next accepted gain, whose rate is compared with other families'
cumulative rates. After an incumbent, every paid segment supplies fractional success
and failure evidence, including zero-yield complete-search segments. Evidence and
its fading follow paid segment time plus the worker's first factory cost, measured
in base-slice durations. Splitting equal exposure across segments preserves its
weight; zero-time observations add no evidence or fading. This prices objective
yield without changing the selected complete-worker coverage scope or share;
silence supplies no proof verdict.
Before an incumbent, feasibility feedback uses the ordinary rules. After the
first-witness segment settles, learned family evidence retains the configured
`phase-retention` share. Classification priors and selected silent complete-worker
coverage stay fixed. Shared source preparation, retirement and independent checks
remain in containing-run accounting.

### Reproducible diagnostics

`portfolio-work=N` opts finite Boolean/integer portfolios into a single-lane
diagnostic schedule with a containing charged-work limit. `PortfolioDiagnostics`
provides the same control to low-level callers. Selection retains seeded reward
sampling and stable arm order, but minimum shares, feasibility coverage and retained
proof coverage use charged work. Auxiliary preparation and search retire by work. Deferred core search uses a work
allowance. Numerical companions and parallel execution reject this mode.
ALNS operator selection and inner repair seeds derive from its worker seed.

Each segment offers the smaller of its work allowance and the containing remainder.
Initialization work reported by a retained handle is included. A zero-work return
charges its offered allowance, bounding private preparation and unsuccessful pauses
that expose no finer counter; this reservation is explicit in the trace. Each
coordinator admission request charges one additional unit, separate from measured
time.
Atomic work may overspend. An uncounted, non-resumable worker is rejected. Work limits preserve
incomplete results and do not establish exhaustion.

The caller's cancellation remains an emergency stop. Its deadline is hidden from
worker policy, segment time caps are absent, and an emergency interruption ends the
invocation before another selection. Timing statistics remain physical observations.
Reproducibility applies to completed logical work on identical source, parameters,
worker seeds and numerical platform; a clock-interrupted final segment is partial.

The optional streaming listener records scheduler seed, selected slot and worker,
offered and charged work, import cursors, reward updates, reseeds, retirements and
termination. Clause cursors include compaction generations; cut cursors identify
their append position. Incumbent reads record changed accepted versions, and floor
reads record changed supplied values as double bits. Empty drains remain visible. Events run inline and the solver
retains no trace buffer. Finite source invocations forward these events through
`SearchEvent.PortfolioDiagnostic`; CLI verbose output renders them without a clock.
The ordinary scheduler remains the default.

### Boolean task sharing

Boolean region attribution uses the [task coverage protocol](09-search.md#boolean-task-coverage)
with the same source, full assumptions, objective cutoff and mapping identity. Schedule
events and read/import/use counts do not establish duplicate region coverage. The task
board is separate from worker selection: it changes neither these scheduling rules nor
terminal admission and owns no retained solver state. The internal Boolean task
executor keeps one repair handle per worker, including a direct Boolean satisfaction
implementation, and rotates ordinary-sized bounded
slices across disjoint tasks, reusing each private session for sibling cubes.
Construction, reseeding, failed attempts and retirement retain their
containing allowances and cumulative worker counters; it supplies no independent
proof certificate. An internal portfolio coordinator
keeps source bindings, seed positions and channel origins on the original workers.
Each lane holds its original session's exclusive owner, reports only its own work
and emits source exhaustion after whole-board
coverage. Closing one lane returns its unresolved region; idle lanes are excluded
from selection. Runtime admission requires one canonical pure Boolean model, exactly
matching source and execution conditions, absent objectives and identical supported
deterministic recipes. Supported shapes are bare InputOrder/IndomainMin without
native propagation and the native first-fail recipe with Luby256.
An omitted native backend resolves to native propagation. Every worker must match
the same full recipe after removing seed, allowance, invocation hooks and captured
pins; mixed shapes decline. Private model generations and uncaptured conditions decline.
A separate engine bake can retain the unbaked plan only with identical Boolean
coordinates and the same factor objects in the same order. Another baked generation
does not establish this relationship.
A known pooled cut adapter is dormant under the required LP-off satisfaction
recipe and is omitted from task queries; active or opaque cut hooks decline.
A separate internal probe-first mode postpones the board until a whole-source
query pauses with a live Boolean decision. It reserves that decision's matching
region for the retained probe engine before exposing the opposite region to its
peer. Source and caller-condition admission precedes the probe; task identity and
mapping authority are allocated only when that pause creates the board.
The adopted lease can retain the live Boolean decision as its conditioned root,
continuing descendant frames without reseeding. Unsupported paused state begins
the task with its complete cube condition, as do other leases.
The original builder solver and immutable native projection remain available
through the transfer. Probe work is a subset of each worker's cumulative work, not
a refunded or additional charge. Without a live decision the same source query
continues; fast completion creates no board.

The source pipeline automatically admits single-core satisfaction portfolios with
two identical native first-fail recipes and the ordinary work schedule under these
source guards. Admission polls caller cancellation while checking clauses and source
mappings. Other shapes continue ordinary solving. Explicit
`boolean-proof-tasks=false` disables admission; `true` also permits the bare
non-native recipe and reports declines. Explicit enablement rejects optimization
and parallel requests; omission leaves them on ordinary execution. Direct internal
switches remain disabled by default. Result statistics distinguish actual partitions,
native task closures, probe effort, retained-root transfers and explicit declines. An admitted fast probe
retains its effort counters even when it creates no board. Combining schedules
preserves their distinct observations.
These counters do not establish independent checking or confer proof authority.

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
node. Large finite scans inside a fresh shared decision assertion poll the containing
slice; interruption retracts that assertion and retains its alternative for retry.
Completed propagation visits are charged at these polls, and a pending sibling is
never skipped. Extensional domain history and decision atom maintenance retain
pending completion or retract with the interrupted pin. The conversion is not an
elapsed-time guarantee: immutable domain construction, non-interruptible factors,
retained-level deductions or LP setup can overrun a slice. Local-search arms
follow the
[retained handle contract](13-neighborhoods.md#handles-and-exclusive-ownership).

Retained Boolean clause walks with definition repairs price scoring inspections
alongside their native instructions. The scheduler passes both allowances through
session-ownership and objective-coordinate wrappers and charges auxiliary work to
the containing ledger, including atomic overspend. Native instruction limits retain
their own currency; [local search](12-local-search.md) owns the inspected operations.

#### Private CP preparation

General CP optimization handles keep one private construction cursor through
projection, indexes, watch registration, root propagation, assumptions, LP planning
and shared-search installation, checkpointing every 256 entries. A handoff that
reaches its segment deadline returns before search, and the next segment resumes
without restarting completed steps. A paused cursor cannot publish an assignment or
an incomplete fixpoint as a refutation; a completed root conflict can. The packed
native-SAT path and public satisfaction constructors stay eager. See [finite propagation](09-search.md#finite-propagation).

An unfinished retained LP root layout remains pending private preparation through the
construction wrapper, so the same bounded preparation-revisit policy can resume it.
Completed construction retains its charged work when the worker enters numerical queries.

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
checkpoint. [Polynomial execution](19-nonlinear-arithmetic.md#decision-procedure)
owns reconstruction, cumulative probing and refutation authority.

#### Auxiliary incumbent evaluation

Deferred core workers retain each complete sibling's initial charged coverage
across incumbent improvements. Once an incumbent admits the ordinary core worker,
its first base trial can follow the initial sibling probes without waiting for an
improvement-free epoch. Further trials require fresh complete-family work. The
existing containing work and auxiliary time allowances bound both preparation and
search.

An auxiliary arm keeps its preparation and receives a bounded search trial after an
incumbent arrives, running before ordinary selection once every initial sibling has
been admitted. The trial is capped at twelve maximum work allowances and four base
time allowances after preparation, and does not advance the shared ladder; overspend
consumes the trial. Productive arms return to the ordinary policy and unproductive
arms retire.

### Preparation revisits

`portfolio-lp-start=after-probes` defers the curated LP workers' first probes in
single-core mixed finite integer optimization without Boolean or real columns.
The other initially eligible workers each receive one attempt, or an accepted
incumbent admits the LP workers sooner. Failed attempts also advance this boundary.
Every worker keeps its slot, seed, private state and source binding; admission then
returns to the ordinary initial probes and complete-worker coverage. The existing
containing deadline and probe accounting remain in force. This control does not
remove a worker, change rewards or renew an allowance. Explicit recipes and arm
counts reject it; `ordinary` is the default.

With multiple initial workers, `portfolio-probes=leader-half` gives the leading
initially eligible complete worker half the probe budget; the others share the rest.
Every sibling still receives a probe. The existing total probe reservation, segment
caps and containing deadline stay fixed; worker slots and seeds do not change.
`portfolio-probes=lp-transfer` halves each initially eligible LP probe's relative
weight and transfers that weight to the leading complete non-LP worker. Other
siblings keep their ordinary relative shares. Deferred LP workers supply no weight;
without eligible donors, the initial shares remain ordinary. Shares bound each
offer against remaining time, so they do not fix durations or native work prefixes.
Both controls require a containing deadline and curated single-core mixed integer
optimization without Boolean or real columns. Explicit recipes and work diagnostics
reject them; `ordinary` is the default. Deferred LP admission retains its separate boundary.

`portfolio-probes=family-work` delays additional initial LS queries until one eligible
LS worker independently spends the existing base work allowance. The
`heuristic-work` mode applies that gate to LS and ALNS queries, including workers
whose ordinary eligibility requires an incumbent. The first eligible retained
heuristic supplies the sample; retirement or temporary unavailability permits the
next eligible worker to supply its own sample. Failed workers' work is not combined.
Both modes retain eager factory construction, worker positions and private seeds,
the full available probe denominator, ordinary incumbent eligibility, complete-worker
coverage and all containing allowances. `heuristicStart` observations report each
invocation's participating workers, work and sample completion. These modes require
the same curated elapsed scope as the weighted probe controls and can compose with
query-boundary publication coalescing.

`portfolio-probes=alns-work` keeps the initial LS queries eligible and defers only
ALNS. Each initial LS worker can supply the sample, but one worker must independently
reach the existing base allowance; their work is not combined. ALNS keeps its ordinary
eligibility when no sample provider is available. Sample observations contain the LS
providers, while ALNS query lifecycle observations report its actual admission.
Worker construction, complete-worker coverage, probe fractions and allowances follow
the same rules as the other paid-work modes.

`heuristicStart` completion time uses the portfolio invocation origin shared by
worker lifecycle times. It is recorded after sampled search and deferred checks,
before subsequent worker admission, and remains absent when unobserved.

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
active time since its last improvement. The free worker furthest below its floor runs before
ordinary reward selection; accepted improvements renew the relevant accounting
window. Coverage accrues without a catch-up delay, so long competing segments
do not consume a short run's proof allocation before coverage begins. Initial sibling
probes, explicit minimum shares and bounded auxiliary trials retain precedence.
Segment overruns can delay coverage.

Checked-completion companion portfolios distinguish canonical point updates from
the finite upper cutoff supplied to built-in backtrack workers. An exact improvement
with the same finite cutoff bits retains those workers' accounting window; their
separate assignment exchange remains available. Full-point numerical consumers keep
the exact-incumbent window. Changed or non-finite cutoffs renew both windows. Other
portfolio routes and workers retain exact-incumbent accounting.

`portfolio-proof-share=S` selects a share from 0.5 through 1 for curated single-core
finite optimization through the solve facade. The default is 0.5. Source search
annotation workers remain in the curated pool. Explicit recipes, arm counts,
satisfaction and parallel execution reject this control.
It changes only the retained proof floor after an incumbent; rewards, reseeding,
initial probes, minimum shares and worker admission retain their own policies.
Work diagnostics apply the same share to charged work.

`portfolio-proof-workers=complete` includes complete retained sessions with internal
restarts in this floor. The default `retained` covers only non-restarting workers.
Both scopes require source proof authority and a resumable session, exclude witness
and improvement-only workers, and use the same incumbent epochs, shares and budget.
The scope control accepts only the same curated single-core optimization routes.

This share protects exhaustive traversal whose progress can stay invisible until a
proof completes. Only live resumable backtrack workers with source-proof authority
qualify; improvement-only workers, auxiliary proof workers and unproductive auxiliary
incumbent trials keep their separate policies. Heuristics remain under reward
selection, as do restarting workers in the ordinary retained scope. Scheduling grants
no proof authority and does not change incumbent validation or terminal admission.

### Phase progress and cost

`PortfolioProgress` separates feasibility, strict incumbent improvement and accepted
terminal closure. Segment phases follow selection, except an accepted terminal moves
its segment to proof closure. Candidate construction and repeated proposals are not
accepted progress. Closure credit describes the coordinator's source authority,
not an independently checked proof. `PortfolioRewardPolicy` is an opt-in callback
on the settled phase, accepted count, native work, inclusive time and contribution
deltas. It can replace the rate reward while family priors, clause-use credit and
complete-worker coverage retain their existing owners. The default reward formula
does not score terminal closure; the callback can inspect that distinct signal.
Idle downstream-use credit reaches the same callback with zero new work or time.
Auxiliary productivity and retirement use the ordinary accepted/use evidence, so an
experimental score cannot extend a trial's allowance or change its admission state.

Each arm retains at most three phase summaries and one lifecycle summary. They
separate factory construction, handle initialization, pending preparation, verification
requests (including refuted or indeterminate checks), accepted incumbents and handle
retirement. Phase times run through reward settlement; initialization and checking
are included. Arm active time also includes settlement/observer overhead and in-lane
retirement. Retirement during final cleanup is outside arm active time. Invocation
time includes all these scopes and coordinator costs.
Source preparation precedes this scope and retains its own invocation accounting;
source reconstruction, independent checking and process retirement belong to the
containing source run. Nested times must not be added to invocation time.

`portfolio-progress=true` streams physical lifecycle and settled progress through
the existing portfolio event sink. A single invocation clock dates creation, first
offered query (including preparation), retirement and incumbent availability. Reads
record versions separately from private accepted imports. Numerical consumption
means its bound supplier actually read the imported incumbent; repair consumption
means a proposal used the checked imported center. These establish consumption,
not a useful deduction or objective gain. No trace is retained in the solver.
Physical events do not make mixed numerical scheduling reproducible; finite
single-lane work diagnostics keep their separate restrictions.

Private companion numerical and repair admission retain bounded `numericalExternal*` and
`jointRepairExternal*` stage counts for missing exact coordinates, rejected or
unavailable native source checks, and non-improving points. Numerical admission also
distinguishes cancellation and unavailable objective evaluation. An incumbent can be
accepted by finite tolerance policy while lacking the exact source authority a
private numerical cutoff requires; availability and reads do not bypass these guards.
Only the companion owners enable these counts; child-query anchor points and other
worker-local warm starts do not count as portfolio peer admissions.

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
propagate, and source-refuted claims remain quarantine faults. Source and quadratic
publication records share an invocation-local observer: it installs the latest
record and version before calling the output callback, retains a callback failure
as the primary exception, and suppresses a distinct later execution failure.

### Incumbent guidance

FlatZinc search hints select tiered variable and value heuristics without the
free-search preset's phase saving or restarts. Optimization updates guidance only
after admitting a complete improving incumbent; solution-guided selectors adopt
better pooled incumbents when traversal resumes, keeping their trail and
learned state.

Finite portfolios with numerical companions supply native guidance from the same
checked canonical exchange that supplies their cutoff. Each native subscriber uses
that exchange's version sequence; ordinary native publication keeps its producer
exchange and source checks. The guidance source is read-only, belongs to the same
model and objective, and withholds non-finite floating projections. Native root
proposals drawn from it still pass local validation. Guidance imports carry no
improvement credit or proof authority.

An internal opt-in observer records checked guide adoption, the last scalar cutoff
read, private incumbent standing and attempted or committed branches. Each worker
streams at most 16 adoption and local-incumbent events of each kind, and 128 attempt
and commit events of each kind. Each adoption or local incumbent opens a window
of eight attempts and commits within those limits. It reads no additional cutoff and evaluates no
counterfactual value order. Its preference flag describes configured precedence,
not proof that a saved value changed a decision; exact cutoff context is unavailable.
Oversized exact local objectives and opaque theory decision payloads are withheld.

At its first root, a non-restarting retained CP worker with a shared cutoff adopts the
current pooled assignment as a starting incumbent once it satisfies the worker's pins. An equal-cutoff witness can
supply local objective propagation without priming guidance or earning progress.
Restarting workers retain their discovery and root-primal startup path. Pool membership
supplies no proof authority.

## Worker recipes

Recipes select workers from model structure. Explicit pools, heuristic backtrack
edits and source annotations retain their requested composition. In single-core
mixed clausal satisfaction, private-rebuild work/byte and learned-memory scratch settings
alone preserve the default two-worker pool and its complete-search time reservation.
Captured option keys distinguish these resource settings from heuristic edits;
custom backtrack edit callbacks retain their selected composition. The ordinary
two-worker clausal pool admits a bounded automatic private parity trial without
changing canonical learned-memory allowances or complete-search time reservation.
Explicit private work zero disables it; caller scratch and private-byte settings bound
the private preparation. Defaults live in
[portfolio construction](../klause/src/commonMain/kotlin/com/eignex/klause/portfolio).

### Roles and the mixed roster

Every catalog arm declares the one role it covers in a roster:

| Role | Arms |
|---|---|
| `first-solution` | Local-search recipes on fixed, Luby or no restarts, and the primary-repair plateau recipe |
| `learning-proof` | The SAT guard, the conflict-driven core and the clausal proof recipes |
| `lp-bounding` | The LP recipes: default, aggressive, best-bound tree and conservative emphasis |
| `objective-cores` | The core-guided worker |
| `selector-diversity` | The source annotation and the recipes that change only the variable or value order |
| `neighbourhood-improvement` | Hybrid ALNS and the other local-search recipes whose restarts perturb their incumbents |

The model's kind and facts name the roles it needs, in seating order. A role named
twice seats its two best arms; a role no eligible arm covers seats none.

- Optimization needs a learning proof, then a first solution. Satisfaction needs them
  in the opposite order: a sequential pool warms arms in roster order, local search
  cannot close a trivial optimum, and its first solution ends a satisfaction run.
- Every model needs LP bounding, seated when its relaxation offers an LP recipe
  something to enable under the `--lp` ceiling.
- Optimization needs neighbourhood improvement and objective cores. The core worker
  is eligible when every objective term is Boolean, including exact views of 0/1
  integer costs, and the model has no continuous columns.
- A source annotation needs selector diversity, and its own search order ranks first
  for it.
- Optimization over pure integer linear rows with an integer objective needs LP
  bounding twice, which seats the aggressive emphasis beside the default. Eligibility
  reads complete unconditional row conjunctions with 64-bit integer constants,
  including normalized exact source rows. A propagator-only symmetry factor preserves
  it; at least one row conjunction is required, and every other factor must qualify.
  An exact binary integer objective view retains it through its Boolean rows, value
  channels and redundant difference propagation, and a binary equality objective
  qualifies the same way; changed objective coefficients discard that capture. LP
  preparation reads the same capture to retain ordinary bounding over
  clause/cardinality rows under the existing dimension ceiling. See
  [ordinary tableau admission](20-linear-programming.md).
- A finite-CP model over 0/1 integers, or one without a relaxation, needs selector
  diversity.

A mixed roster reads three ranks: local search, complete search and, on an
optimization that offers an objective and has more arms than cores, the one hybrid
ALNS arm. Optimization reads local search first and satisfaction complete search
first. The best arm of a role is the first to declare it when the ranks are read one
position at a time. The roster seats the best arm of each needed role, then fills the
remaining slots by rank: the engines take turns, each seating its next unseated arm.
Neither step reads the pool size or the core count, so among pools with more arms
than cores a smaller roster is a prefix of a larger one. A pool with a core per arm
reads no ALNS rank and is seated by the same rule. A rank that runs out repeats its recipes on
fresh seeds; the annotation, the core worker and ALNS never repeat. Seated arms
count toward the configured pool size.

Each rank is its catalog's credit order restricted to eligible recipes. Three leads
depend on the model: clausal satisfaction ranks its balanced clause-only recipe and
fixed-break probSAT first, pseudo-Boolean local search ranks the bandit flip walk and
jump moves first, and scheduling optimization ranks the primary-repair plateau recipe
beside the full one, both on iterated local-search restarts. An injected pool
replaces its engine's rank with the selected recipes in the given order, and its
first recipe is seated ahead of the needed roles: complete search first on an
optimization, local search first on satisfaction.

Single-engine pools take the prefix of their engine's rank, without the scheduling
lead; a backtrack-only pool of
at least two arms gives the annotation its last slot. A curated sequential mixed
optimization pool larger than the default searches for its first solution with the
default roster and admits the remaining arms after the first incumbent.

Portfolio statistics report each arm's seat as `role=` on its `arm.` line: the role
the arm was seated for, or `rank/` and its declared role when it filled a slot by
rank, as every arm of a single-engine pool does. A selected `WorkerAdmission` event
carries the same name.

On continuous columns with a continuous objective, single-core pools reserve half
the scheduled time for the default LP arm until the first incumbent. A default-size
sequential pool over continuous columns evaluates that arm as an auxiliary: without
progress it receives a [bounded evaluation](#auxiliary-incumbent-evaluation), and an
unproductive trial retires it without claiming exhaustion.

#### Satisfaction pools

Finite CSP palettes admit `support-aware` after the SAT guard and conflict-driven
core when a ground Table has 2–32 positions, at most 65,536 tuples and at most 32
root values per position. It uses conflict-driven variable selection and completed
extensional support densities for value order, with the ordinary 65,536-visit
ranking allowance. Unavailable counts and wider domains fall back to random value
order without propagation probes. The recipe uses ordinary complete-worker slices
and family coverage; other recipes retain their value orders. Explicit pools keep
their requested recipes, and optimization and other model classes decline automatic
admission. MDD-only models retain their ordinary default palette.

The same finite-Table gate admits `propagation-guided` immediately after
`support-aware`. It keeps completed support ordering and uses bounded residual-domain
probing when counters decline. The cheap and ordinary conflict-driven recipes keep
their earlier slots. Its local work allowance belongs to one value ordering, while
all work also consumes the ordinary containing worker slice. Explicit maxSD keeps
its ordinary cancellation policy without a local work cap. [Search](09-search.md) owns probe restoration,
coverage and accounting.

The satisfaction rank places dom/wdeg and first-fail ahead of the free engine and
the selector bandit; the optimization rank places the selector bandit ahead of LinUCB
and the free engine. A finite-CP roster that needs selector diversity therefore
seats support ordering on an admitted Table model, dom/wdeg on other satisfaction
models and the selector bandit on optimization.

The satisfaction backtrack palette includes conservative LP after default and
aggressive LP; applicability, model class and LP ceilings order and filter it. Root
variable shaving and hull pruning remain explicit plan options, and curated
satisfaction workers harvest root cuts only with an explicit `lp-root-cuts=true`.

Curated backtrack-only clausal and pseudo-Boolean satisfaction pools enable bounded
[root maintenance](11-search-policies.md#restarts-phases-and-inprocessing) on every complete worker.
A clausal safe-root slice shares a 4,096-work grant, with 3,072 work for probing.
A pseudo-Boolean slice shares 16,384 work, with 12,288 work for probing.
Cheap simplification receives 256 work in either pool. Up to two probes share the
class grant, with an 8,192-work per-probe ceiling. Clausal pools give
subsumption and vivification zero class grants; pseudo-Boolean pools retain their
recipe's auxiliary grants inside the shared ceiling.
Consistent-probe binary capture is disabled in this pool; explicit
`root-probe-implications=true` enables it without changing failed-literal probes or
cheap binary maintenance. Root scans examine at most 256 clauses per slice. Natural-root scheduling
uses a 32-work interval, eight-clause growth and a 32-work cooldown. Clausal pools
require 4,096 non-inprocessing work before the first optional maintenance slice;
pseudo-Boolean pools have no warmup. After the first eligible probe slice, optional
probing requires 32,768 additional non-inprocessing work. Clausal pools enable
`root-probe-binary-gate`: Native workers without root facts or known binary clauses
scan new learned binaries before admitting global probes. Cheap scans and pending
continuations keep their own eligibility.
Pseudo-Boolean pools enable `root-probe-general-gate`, which admits one initial
global batch under the existing grants, then prioritizes new short learned clauses
when Boolean General workers have no root facts or known binary hints.
An empty completed neighborhood probe prioritizes its opposite polarity inside the
existing batch and work grants.
It preserves ordinary rotation after either signal and leaves integer endpoints unchanged.
`inprocessing-probe-interval=0` follows the shared root trigger instead.
These allowances remain inside each worker's containing slice. The
`root-maintenance=false` option retains stock root defaults without changing explicit
root or probe settings. Backtrack edits apply after the automatic defaults. Named
pools and source annotations keep their selected parameters; native clausal proof
admission retains its own policy.

Clausal complete-search recipes use clause learning, EMA restarts and a true-first
saved phase; their balanced and stable variants differ in VSIDS decay (0.975, 0.99)
and database cap (4000, 2000). Pseudo-Boolean models keep their cutting-plane
palette. Clausal satisfaction leads with the balanced recipe and, in a curated
sequential pool, reserves 98% of active time for it after the initial probes, because
a refutation emits no incumbent progress from which rewards could predict it. Its
local-search slots lead with fixed-break probSAT.

#### Boolean objective core search

The internal non-retained MaxSAT drivers keep relaxed soft clauses eligible for
later cores, preserving their blockers through weight splits. The weighted threshold
driver bisects the bounded cost range using cost-only assumptions; a refutation
raises the bound only past the tested cutoff.

Mixed finite optimization rosters seat a retained core-guided complete worker for
the `objective-cores` role. Other finite integer constraints remain
admissible. Boolean cutoff consumers also accept this cost view. The
worker assumes all positive residual objective terms false and asks for a satisfying
assignment. A refutation returns a core: a set of terms at least one of which must be
violated. The worker adds the core's minimum residual weight to an exact lower bound
and relaxes the core with a reified cardinality, replacing its violation count `s` by
`1 + sum(k = 2..n, s >= k)`; only the first unspent threshold of each count is
materialized. Weights and the bound use arbitrary-precision integers.

Objective terms, residual-core updates, maximum-weight scans, core projection and
query-pin selection retain private cursors across slices. A completed pin set stays
with its query model across interrupted openings; resuming does not repeat its
threshold scan. Preparation visits and private retirement spend the same cumulative worker allowance as
query traversal. Augmented queries retain the
source's established root domains, deductions and captured projections; their
additional constraints propagate in the query session.
General query sessions retain projection construction, root propagation and
assumption seeding across slices. Native clausal opening stays atomic; an
interrupted opening retains its charges, model and selected pins and retries with
the retained general preparer. This prevents repeated native construction from
using every subsequent slice without reaching traversal.
Closing discards query pins,
auxiliary buffers and preparation cursors while keeping completed observations.

Curated single-lane mixed source execution selects an early reason-refined core
trial for nonuniform Boolean objectives over at most 16,384 Boolean columns and
16,384 factors, with at most two million literal entries in clauses, cardinalities
and pseudo-Boolean rows or their reifications. Pseudo-Boolean row constants must
fit signed words. It excludes integer and real columns and requires the sum of the
absolute objective coefficients and constant to fit the exact scalar range below
`2^53`. Eligibility scanning is cancellable and charges the containing invocation.
The trial retains ordinary complete-worker coverage, worker positions, seeds and
its existing auxiliary allowance. Explicit core settings, arm counts, recipes,
annotations, backtrack edits and work-driven diagnostics retain their selected
policy. Unsupported or interrupted eligibility retains the ordinary delayed,
unrefined core policy.

`core-refinement=none|reasons|deletion` explicitly selects the private policy for
curated and directly selected core workers.
Seed-proof projection uses the
[historical reasons](10-learning.md#explanation-lifetime) of the existing query,
without another propagation session or solve; unsupported seed conflicts and tree
exhaustion keep their certified cores. Caller conditions remain attached to every
augmented query.

A separate deletion control tries deleting each member of a
completed core with a fresh propagation refutation of the augmented hard model and
the retained caller pins. It admits at most 32 members, 4,096 columns, 4,096 factors
and 32,768 structural entries and propagation visits, over built-in projections
of clauses and reified cardinalities. It never removes the final member and retains
the original certified core on a stop.
Its construction, trials and disposal share the core worker's work and active-time
allowances. It performs no additional search and does not promise a minimal core;
tree refutations unavailable to propagation keep their original members.

Selecting `core-guided` directly also accepts exact integer costs over columns whose
source bounds are closed on both sides; curated pools still admit it only for Boolean
objectives. A cost `c·x` starts at its cheapest root value and becomes the gaps between
consecutive root values crossed away from it: each gap is a threshold `[x ≥ v]`, or
`[x ≤ v]` when `c < 0`, weighted by `|c|` times the gap. Only the first unspent
threshold of each column is materialized, so a wide contiguous column can still spend
the worker's whole allowance one value at a time. Scalar proofs count the full integer
cost magnitude.

The directly selected worker can expand a retained finite sum-output definition
through the bounded [separable cost view](07-presolve.md#guarantees-and-source-mappings).
Only its private soft costs use that expression. The original output coordinate,
defining factors and objective retain witness and publication authority; floors
stay in the same units and exact attainment still checks the original objective.
Curated eligibility reads the original objective, so an integer output alias does
not enable an additional default worker. Expansion and refused attempts spend the
worker's containing preparation allowance.

Published incumbents project away auxiliary selectors, and
optimality requires exact equality between the original objective and the
accumulated bound. Auxiliary clauses never enter shared channels. On a single lane
with another complete worker, the core worker joins after the first incumbent once
ordinary complete workers stall, with the allowance described in
[resources](06-sessions.md#resource-allowances); parallel pools admit it immediately, and `core-guided`
selects it directly.

`core-early=true` admits the eligible bounded core trial after initial ordinary
complete-worker coverage, before heuristic first probes, in a mixed optimization pool
on one lane with automatic backtrack workers and a Boolean objective. The trial keeps
its opportunity across retained preparation and query slices until it finishes or
its cumulative allowance stops it, on either side of the first incumbent. Worker
positions, seeds, complete-worker coverage and auxiliary limits stay fixed, including
the containing work allowance in diagnostic schedules. The control does not expand
core eligibility.

### Exact theory strategy execution

Linear, polynomial, nonlinear-integer and quadratic routes supply factories to the
same invocation. Replicas keep private arithmetic state, traversal and quotas across
slices. Typed terminals are admitted only after source, condition, objective,
attainment and strictness checks.

Linear sources can pair complete traversal with
[exact-coordinate neighborhoods](13-neighborhoods.md#exact-coordinate-neighborhoods),
including real-only models and wide integers. Those handles retain centers, radii
and adaptive weights; window refutations never become source proofs.

---

Previous: [14. Portfolios](14-portfolio.md) · [Contents](README.md) · Next: [16. Original-source derivations](16-evidence.md)
