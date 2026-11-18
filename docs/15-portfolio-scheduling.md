# 15. Portfolio scheduling and workers

The portfolio scheduler allocates bounded opportunities among admitted workers.
Worker recipes supply complementary search behavior under the shared publication and proof contracts.

## Scheduling

The scheduler assigns bounded slices, revisits pending preparation and reseeds stale
workers under the containing [allowance](06-sessions.md#resource-allowances).
Scheduling changes work order, never publication authority.

Every worker belongs to a family: backtrack (complete search), local search, or
large-neighborhood search around the incumbent. A local-search or LNS worker's
whole state is visible, so a segment without progress is evidence against its
family; a backtrack worker can approach a proof while showing nothing, so its
silence is not.  The policy first draws a family by Thompson sampling, so a
family's share of time does not grow with its arm count, and then an arm of that
family by discounted Thompson sampling over the arms' rewards. Family priors come
from the model's class: complete search leads on pseudo-Boolean, continuous and
open models, local search on finite-CP satisfaction, and clausal, mixed-integer and
finite-CP optimization models start even. Priors stay fixed while evidence fades
with a short half-life, so a plateaued local search hands time to complete search
and wins it back when it improves. Before the first incumbent a backtrack segment
supplies no family evidence, while an observable family is credited for progress
and charged for a working segment without it; an LNS worker still bootstrapping
competes inside local search while other LNS workers wait unless nothing else is
eligible, and a backtrack family draw runs its least-worked arm.

A segment's reward comes from a per-arm ledger of progress signals: first
feasibility, incumbent improvement, objective floor raises, root fixings, short
learned clauses, local-search violation reductions, and peers' uses of shared
clauses, cuts and bounds. Each signal an arm can earn is scored as its rate per unit
of work against the rate of the rest of the pool, so a long segment does not score
higher for being long and signals in unrelated units need no weights. Credit an arm
earns while idle, from peers using what it shared, settles at the next segment of
any arm. The first incumbent starts a new phase, at invocation start when an
incumbent already exists: pooled rates and unsettled credit reset, and the arm policy
keeps a `phase-retention` share of its evidence (a quarter by default).

Each selection takes the first applicable rule: an early core trial, then the
initial probes (each eligible arm runs one base slice, in order), then owed minimum
shares and the feasibility coverage floor, then preparation revisits, then auxiliary
proof workers, bounded auxiliary trials and the retained proof floor, then a
continuing preparation, and finally the family and arm draw. A segment's evidence
weighs the share of its slice it spent; a failed segment earns nothing and weighs as
a full one. When a parallel pool has a lane per arm and no deferred workers, every
arm runs on its own lane for the whole solve.

### Slice accounting and coverage

The counted schedule starts at 5000 node equivalents, grows by 1.5 after an ordinary
scheduled segment, and caps at 150000. Growth belongs to the shared schedule,
rather than to each arm. Initial probes and preparation revisits use the base
allowance and do not grow the schedule. Counted segments also keep an elapsed-time
cap, and a segment takes at most half the remaining time divided by the number
of distinct declared families in the pool (a probe, its probe-weighted share of
half). The finite CLI exposes `slice-work`, `slice-max-work` and `slice-growth`
and reports the effective settings.

Backtrack arms charge decisions, propagation visits and LP work to their scheduler
at the [slice prices](06-sessions.md#resource-allowances); the solve-wide
decision/move cap keeps its separate currency. Slices yield before consuming an
alternative or after a completed node. Large finite scans inside a fresh shared
decision assertion poll the containing slice; interruption retracts that assertion
and retains its alternative for retry. Completed propagation visits are charged at
these polls, and a pending sibling is never skipped. Extensional domain history and
decision atom maintenance retain pending completion or retract with the interrupted
pin. The conversion is not an elapsed-time guarantee: immutable domain construction,
non-interruptible factors, retained-level deductions or LP setup can overrun a
slice. Local-search arms follow the
[retained handle contract](13-neighborhoods.md#handles-and-exclusive-ownership).

Retained Boolean clause walks with definition repairs price scoring inspections
alongside their native instructions. The scheduler passes both allowances through
session-ownership and objective-coordinate wrappers and charges auxiliary work to
the containing ledger, including atomic overspend. Native instruction limits retain
their own currency; [local search](12-local-search.md) owns the inspected operations.

Clause-learning credit follows each search instance's cumulative counter. Opening a
replacement handle or an independent one-shot search resets that counter's cursor;
retained slices earn only new clauses. Root-fixing maxima and the best violation
remain shared across the worker's searches within the invocation.

#### Private CP preparation

General CP optimization handles keep one private construction cursor through
projection, indexes, watch registration, root propagation, assumptions, LP planning
and shared-search installation, checkpointing every 256 entries. A handoff that
reaches its segment deadline returns before search, and the next segment resumes
without restarting completed steps. A paused cursor cannot publish an assignment or
an incomplete fixpoint as a refutation; a completed root conflict can. The packed
native-SAT path and public satisfaction constructors stay eager. See
[finite propagation](09-search.md#finite-propagation).

An unfinished retained LP root layout remains pending private preparation through the
construction wrapper, so the same bounded preparation-revisit policy can resume it.
Completed construction retains its charged work when the worker enters numerical
queries.

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
arms retire without claiming exhaustion.

### Preparation revisits

Before the first incumbent, an optimization arm with pending private preparation, or
a local-search arm that has not executed an instruction, receives preparation
revisits after all initial siblings have been admitted. Unfinished arms rotate in arm
order at the base allowance until they enter search or terminate; no revisit delays
an unadmitted sibling.

Before the first witness, family selection rewards observable progress toward
feasibility. Shared clauses, root fixings and objective floors stay credited
but earn no extra turns, because deduction throughput does not predict the first
witness; violation reductions can, and a working heuristic segment without progress
supplies plateau feedback. Preparation and zero-work pauses supply no failure signal.

Complete workers keep an elapsed coverage floor until the first witness or
refutation: together they are owed one family share, the total active time
less one base time allowance of lag, divided by the number of families among
the active workers, counting local search and LNS as one. Below-floor workers
run before ordinary selection. No fixed time is promised to a worker, and
because coverage depends on elapsed time, a single lane and seed do not make
the schedule reproducible.

### Retained proof coverage

With an accepted incumbent, retained non-restarting complete workers share half the
active time since its last improvement. The free worker furthest below its floor runs
before ordinary reward selection; accepted improvements renew the accounting window.
Coverage accrues without a catch-up delay, so long competing segments do not consume
a short run's proof allocation before coverage begins. Initial sibling probes,
explicit minimum shares and bounded auxiliary trials keep precedence, and segment
overruns can delay coverage.

This floor protects exhaustive traversal whose progress can stay invisible until a
proof completes. Only live resumable backtrack workers with source-proof authority
qualify; improvement-only workers, auxiliary proof workers and unproductive auxiliary
incumbent trials keep their separate policies. Heuristics remain under reward
selection, as do restarting workers in the default scope. Scheduling grants no proof
authority and does not change incumbent validation or terminal admission.

`portfolio-proof-share=S` sets the share from 0.5 through 1 (default 0.5), and
`portfolio-proof-workers=complete` also covers complete sessions with internal
restarts (default `retained`). Both scopes require source proof authority and a
resumable session and exclude witness and improvement-only workers. Both controls
apply to curated single-core finite optimization through the solve facade, where
source annotation workers stay in the curated pool, and reject explicit recipes, arm
counts, satisfaction and parallel execution. They change only the floor after an
incumbent; work diagnostics apply the share to charged work.

### Probe, reward and start controls

Two opt-in start controls, `portfolio-lp-start` and `portfolio-probes`, reshape the
start of a curated single-core mixed finite integer optimization. They require the
default arm count, a source without Boolean or real columns and no source search
annotations, except where noted, and reject explicit pools, arm counts and backtrack
edits; `portfolio-probes` also requires a containing deadline and rejects the work
diagnostic schedule. Neither control adds or removes a worker, changes a worker's
slot, seed or private state, renews a work or time allowance, or grants witness,
bound or proof authority; initial attempts and complete-worker coverage stay owed.

- `portfolio-lp-start=after-probes` defers the curated LP workers' first probes until
  the other initially eligible workers have each had one attempt, or an accepted
  incumbent admits them sooner; failed attempts also advance this boundary.
- `portfolio-probes=leader-half` gives the leading initially eligible complete
  worker half the probe budget, the others sharing the rest; `lp-transfer` moves
  half of each eligible LP probe's weight to the leading complete non-LP worker.
  Shares bound each offer against remaining time and fix no durations.
- `portfolio-probes=heuristic-second` gives the first eligible local-search worker
  the probe after the leading complete worker. It also accepts Boolean columns,
  including clausal and pseudo-Boolean sources, and keeps annotation workers.
- `portfolio-probes=family-work` delays further initial local-search queries until
  one eligible worker alone has spent the base work allowance; failed workers' work
  is not combined, and a retired or unavailable sampler lets the next eligible one
  supply its own sample. `heuristic-work` applies the gate to LS and ALNS, including
  workers that need an incumbent; `alns-work` keeps LS eligible and defers only ALNS.
  Probe fractions keep the full initial pool as denominator. With segment
  publications, pending offers finish their checks before the sample settles.

`portfolio-reward` replaces only the reward of a finite portfolio schedule; it changes
neither worker admission nor complete-worker coverage and supplies no witness or
proof authority. `source` keeps ordinary feasibility rewards but, once a source
incumbent exists, rewards only accepted strict improvements and terminal closure;
violation reductions, deductions and sharing credit stay observable without earning
turns. `source-rate` also keeps feasibility rewards before the first incumbent and
then prices finite accepted objective gains against observed factory and segment
time, including preparation and coordinator checks, comparing a worker's rate with
the other workers' cumulative rates; accepted terminal closure earns a unit reward.
Failed and refuted segments earn zero while their cost counts, unproductive time
stays owed until the worker's next priced gain, and the first witness supplies no
invented gain; gains accumulate on a common normalized scale. `source-rate-family`
also prices gains per family: after an incumbent every paid segment supplies
fractional evidence weighted by its time, plus the worker's first factory cost, in
base slices, and the family evidence keeps the `phase-retention` share across the
first witness. Classification priors stay fixed, and source preparation, final
retirement and independent checks stay in the containing run's accounting.

### Phase progress and cost

`PortfolioProgress` separates feasibility, strict incumbent improvement and accepted
terminal closure. Segment phases follow selection, except an accepted terminal moves
its segment to proof closure. Candidate construction and repeated proposals are not
accepted progress. Closure credit describes the coordinator's source authority, not
an independently checked proof. `PortfolioRewardPolicy` is an opt-in callback on the
settled phase, accepted count, native work, inclusive time and contribution deltas;
it can replace the rate reward while family priors, clause-use credit and
complete-worker coverage keep their owners. The default reward does not score
terminal closure; the callback can. Idle downstream-use credit reaches the callback
with zero new work or time. Auxiliary productivity and retirement use the ordinary
accepted and use evidence, so a custom score cannot extend a trial or change its
admission.

Each arm keeps one summary per phase and one lifecycle summary, separating factory
construction, handle initialization, pending preparation, verification requests
(including refuted or indeterminate checks), accepted incumbents and retirement.
Phase times run through reward settlement and include initialization and checking.
Arm active time also includes settlement overhead and in-lane retirement; retirement
during final cleanup is outside it. Invocation time includes all these scopes and
coordinator costs. Source preparation precedes this scope, and source
reconstruction, independent checking and process retirement belong to the
containing run; nested times must not be added to invocation time.

`portfolio-progress=true` streams lifecycle and settled progress through the
portfolio event sink, dated by one invocation clock. Reads record versions
separately from private accepted imports. These observations establish consumption,
not a useful deduction or objective gain, change no selection or funding, and confer
no proof authority; no trace is retained in the solver. With reporting enabled, each
worker also emits one continuation observation for its first selected improvement
segment and one exit observation at retirement or cleanup. The exit observation
separates the removal cause from the last native return, before coordinator proof
reinterpretation, and records the last external cutoff with the pool version read
with it; it does not describe a worker's private cutoff. Exit capture adds no pool
reads, cancellation polls or handle inspection, and disabled reporting constructs no
continuation event and reads no handle accounting. A zero-work pause alone
establishes neither exhaustion nor lost continuation, since repaying earlier slice
debt can consume the entire offered allowance. Physical events do not make mixed
numerical scheduling reproducible.

### Reproducible diagnostics

`portfolio-work=N` opts finite Boolean/integer portfolios into a single-lane
diagnostic schedule with a containing charged-work limit. `PortfolioDiagnostics`
provides the same control to low-level callers. Selection retains seeded reward
sampling and stable arm order, but minimum shares, feasibility coverage and retained
proof coverage use charged work. Auxiliary preparation and search retire by work, and
deferred core search uses a work allowance. Numerical companions and parallel
execution reject this mode.

Each segment offers the smaller of its work allowance and the containing remainder.
Initialization work reported by a retained handle is included. A zero-work return
charges its offered allowance, bounding private preparation and pauses that expose
no finer counter; the trace shows this reservation. Each coordinator admission
request charges one additional unit, separate from measured time. Atomic work may
overspend. An uncounted, non-resumable worker is rejected. Work limits preserve
incomplete results and do not establish exhaustion.

The caller's cancellation remains an emergency stop. Its deadline is hidden from
worker policy, segment time caps are absent, and an emergency interruption ends the
invocation before another selection. Timing statistics remain physical observations.
Reproducibility applies to completed logical work on identical source, parameters,
worker seeds and numerical platform; a clock-interrupted final segment is partial.

The optional streaming listener records scheduler seed, selected slot and worker,
offered and charged work, import cursors (with compaction generations), reward
updates, reseeds, retirements and termination. Events run inline and the solver
retains no trace buffer. Finite source invocations forward them through
`SearchEvent.PortfolioDiagnostic`; CLI verbose output renders them without a clock.

### Boolean task sharing

Boolean region attribution uses the [task coverage protocol](09-search.md#boolean-task-coverage)
with the same source, full assumptions, objective cutoff and mapping identity.
Schedule events and read, import or use counts do not establish region coverage. The
task board is separate from worker selection: it changes neither the scheduling rules
nor terminal admission and owns no retained solver state.

The executor keeps one repair handle per worker and rotates ordinary-sized bounded
slices across disjoint tasks, reusing each private session for sibling cubes. Each
lane holds its original session's exclusive owner and reports only its own work;
source bindings, seed positions and channel origins stay on the original workers.
Source exhaustion is emitted only after whole-board coverage. Closing a lane returns
its unresolved region, and idle lanes are excluded from selection. Construction,
reseeding, failed attempts and retirement keep their containing allowances and
cumulative counters; the board supplies no independent proof certificate.

Admission requires one canonical pure Boolean model, exactly matching source and
execution conditions, no objective, and every worker running the same supported
deterministic recipe once seed, allowance, hooks and captured pins are removed: bare
input-order/minimum-value search without native propagation, or native first-fail
with Luby256 restarts. An omitted native backend resolves to native propagation. Mixed
shapes, private model generations, uncaptured conditions and active or opaque cut
hooks decline; a pooled cut adapter, dormant under the required LP-off recipe, is
omitted from task queries. A separate engine bake can keep the unbaked plan only with
identical Boolean coordinates and the same factor objects in the same order.

In probe-first mode the board waits until a whole-source query pauses on a live
Boolean decision. That decision's region stays with the probe engine, which can keep
the decision as its conditioned root without reseeding, and the opposite region goes
to the peer; unsupported paused state starts its task from the complete cube
condition. Source and caller-condition admission precede the probe, and task identity
is allocated only when the pause creates the board. Probe work is part of each
worker's cumulative work, not an extra charge. Without a live decision the query
continues, and a fast completion creates no board.

The source pipeline admits single-core satisfaction portfolios with two identical
native first-fail recipes and the ordinary work schedule automatically, polling
cancellation while it checks clauses and mappings; other shapes solve ordinarily.
`boolean-proof-tasks=false` disables admission, and `true` also permits the bare
recipe, reports declines, and rejects optimization and parallel requests. Task
statistics distinguish partitions, closures, probe effort, retained-root transfers
and declines; they confer no proof authority.

### Reseeding and arm failures

Optimization portfolios can reseed stale resumable arms after an incumbent;
`reseed-stale-threshold` defaults to 3, with 0 disabling it. Restart-configured
backtrack arms count equivalent base work segments, so short fragments cannot
destroy retained traversal; accepted improvements clear the count, and reseeding
waits while bounded preparation or interrupted root rounding owns pending work.

Complete-search arms without a restart schedule retain their traversal, because
closing a proof gap need not produce a better incumbent. Reseeding preserves
terminal verdicts, a closed handle's totals merge into its arm once, and reseeding a
handle does not remove its worker.

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
better pooled incumbents when traversal resumes, keeping their trail and learned
state.

At its first root, a non-restarting retained CP worker with a shared cutoff adopts
the current pooled assignment as a starting incumbent once it satisfies the worker's
pins. An equal-cutoff witness can supply local objective propagation without priming
guidance or earning progress. Restarting workers keep their discovery and root-primal
startup path. Pool membership supplies no proof authority.

## Numerical companions

A finite portfolio over a linear source can seat two numerical companions beside its
finite workers: a numerical frontier that traverses the LP relaxation, placed first,
and a joint-repair local search over integer neighborhoods with residual LP
completion, placed last. The leading frontier does not shift the finite workers'
seed positions. `FiniteCompanionArms` owns companion admission, construction, arm
order, preparation telemetry and scheduler shares; the finite portfolio only seats
the arms it returns.

### Companion admission

Automatic admission appends both companions to curated sequential mixed optimization
with the default arm count over binary integers and continuous columns, with finite
integer bounds, no Boolean columns, at most 4,096 numeric columns and 16,384 factors,
and LP bounding enabled. Explicit recipes and arm counts (including an explicit
default-sized count), source annotations, parallel pools, purely integer models and
the work diagnostic schedule keep their finite composition. Complete affine capture
must also be available; a missing capture declines both companions without removing
finite workers. Capture omits the redundant propagated difference graph from the
relaxation. The companions keep the ordinary finite recipes, arm identities, seeds,
sharing channels and completion authority.

`companion-integers=true` lifts only the binary restriction to general bounded
integers; every other check remains. `companion-after-incumbent=true` defers both
companions' queries until the pool accepts a source incumbent, while factory
construction still belongs to the invocation. `companion-admission=funded` requires
that deferral and a containing deadline, admits the pair only when both requested
lifetimes fit, and postpones shared affine capture and private construction to the
first companion query, within that query's time grant, the construction segment
ceiling and the time left after both lifetimes. `progress-funded` also waits for a
strictly improving accepted incumbent after the first witness; rejected proposals,
repeated points, deductions, idle sharing credit and an incumbent from before the
invocation do not count, and waiting workers receive no query or capture. Deferred
capture is attempted once for the pair inside the opening worker's query and
lifetime; cancelled queries and empty time grants cannot start it. Each worker
rechecks its remaining lifetime before opening, and a late start or unavailable
capture retires it without a source verdict. None of these controls changes finite
recipes, indices, seed positions, coverage floors, work and allocation ceilings or
reseeding rules.

Ordinary preparation captures the shared affine model before worker factories run.
Its time spans difference-factor filtering and immutable capture, excludes
factories, queries, candidate checks and retirement, belongs to the containing solve
time, and grants no allowance or authority. Final invocation counters report capture
attempts, time and readiness once; worker summaries do not repeat them.

### Companion scheduling

The frontier gets the first probe at weight three and other probes weight one; the
shared probe allowance is split by these weights, so a small numerical proof can
complete before finite handles open. Finite probes keep their order and seed
positions. Before the first incumbent, joint repair is owed 30% of scheduled search
time beside the finite reservations, because its root preparation earns no reward
until a proposal completes.

Each companion keeps four seconds of active search by default; `companion-active-ms`
sets a lifetime of at most 60,000 milliseconds inside the unchanged solve deadline.
Frontier segments take at most one second and joint-repair segments 250
milliseconds, further limited by the containing offer, and each segment caps native
work at 5,000. Atomic preparation and certification can overrun; overspend retires
that worker at its next turn without claiming exhaustion, and workers cannot reseed
to renew these limits. The two owners divide the containing numerical work and
allocation ceilings equally and keep their query funding across slices.

With source-incumbent completion enabled, built-in backtrack workers see the
canonical incumbent only as the finite upper cutoff: an exact improvement with the
same cutoff bits keeps their [retained proof coverage](#retained-proof-coverage)
window, while full-point numerical consumers keep the exact-incumbent window, and a
changed or non-finite cutoff renews both. Other portfolio routes and workers keep
exact-incumbent accounting.

Native [incumbent guidance](#incumbent-guidance) in a companion portfolio comes from
the same checked canonical exchange that supplies the cutoff, read-only and for the
same model and objective, withholding non-finite floating projections. Each native
subscriber follows that exchange's version sequence, while ordinary native
publication keeps its producer exchange and source checks; root proposals drawn from
it still pass local validation, and guidance imports carry no improvement credit or
proof authority. An internal opt-in observer can record guide adoption, cutoff reads
and branch attempts in bounded per-worker streams; it reads no additional cutoff, and
its preference flag describes configured precedence, not proof that a saved value
changed a decision.

### Companion authority

Companions consume the finite scheduler's checked exact incumbent exchange and
publish witnesses after rechecking their complete coordinates against the captured
affine source. Joint repair has witness authority only. Its full-source check of a
completed point is an atomic publication step charged to its active lifetime. When
exact incumbent completion supplies an original-source callback, repair also checks
each completed point's exact original feasibility and adjusted objective before
publication; working-model or tolerance acceptance alone cannot admit it.

A numerical optimum or refutation completes the finite invocation only after its
typed terminal verifies against the same captured working model and objective; the
finite pipeline keeps original-source reconstruction and qualification. Other
numerical terminals and incomplete coverage carry no completion authority, and
finite workers' ordinary terminal admission stays available throughout.

The frontier retains its checked root-global bound across pauses and retirement, and
finite results carry this evidence separately from additive counters. Objective-
preserving preparation maps it through the captured positive scale and offset, and
an unavailable mapping withholds it. Linear source qualification restores original
units and direction and keeps strictness; restricted or inexact nominal views
withhold a global bound. Output labels it a native nominal-source claim: tolerance
coverage is separate, and publication confers no independent validation or terminal
status. Reading the retained bound requests no LP work.

Private companion admission of pooled incumbents counts missing exact coordinates,
rejected or unavailable native source checks and non-improving points. An incumbent
accepted by finite tolerance policy can lack the exact source authority a private
numerical cutoff requires; availability and reads do not bypass these guards.
Finite numerical factories can withhold shared assignments independently of
completion.

### Incumbent completion and repair

`source-incumbent-completion=true` (default false) lets both companions use an
approximate incumbent's Boolean and finite integer coordinates as advisory pins, with
approximate real coordinates guiding rational reconstruction without authority. A
reconstructed point must satisfy every active affine row exactly; otherwise
connected continuous residuals use the exact recovery path, priced by their source
objective costs where those are nonzero and as feasibility completion otherwise.
Recovery supplies a checked point and no global bound. Linear sources reconstruct the
point through the captured presolve mapping, check the original declarations and
rows, and compare the original objective with the adjusted working value; these
advisory seeds need exact original feasibility even where ordinary finite witness
admission permits source tolerance. Original-source witness and exact objective
checks precede incumbent admission, cutoff use or neighborhood centering.
An already exact pooled point must pass the same original-source callback before
import; exact coordinates alone do not establish feasibility in a presolved source.
These checks spend the consuming companion's existing funding and lifetime.

Each companion keeps at most sixteen recovery attempts without eviction, identified
by the full source restrictions, rows, coordinates, objective, interpretation,
checker, seed values and coordinate frame. Failed unchanged attempts stay declined,
and paused recovery keeps its progress under the same cumulative allowance. The
pair can reuse at most sixteen successful completions under the same identity;
each belongs to its producing owner and is removed when that owner closes, only
immutable coordinate snapshots cross workers while producer certificates and
failed or pending work stay private, and consumers keep their own witness and
objective checks. Preparation, recovery, checking, lookups and retained allocation
spend the consuming companion's existing LP funding, and closure releases its
residual owners. Unsupported symbolic payloads, integers outside the recovery
scalar limit and keys beyond the remaining allocation are refused before retention.

Sharing completions grants no work, lifetime, bound or terminal authority. The
control changes neither worker admission nor lifetimes.

With completion enabled, a checked pooled center starts joint repair in a small
connected integer neighborhood that frees at most two integers, without a root
relaxation; the all-pinned rounded-root bootstrap serves a center without that
handoff. Before a checked center exists, root repair can keep the ordinary
pre-selection relaxation point for advisory centering and neighborhood order,
separately from point-selected bounds. Before the first proposal, a newer exact
pooled point (never an advisory one) can replace the center and tighten the cutoff,
keeping pins on unchanged discrete coordinates without another residual completion.
The pair can keep one advisory point from the frontier's paid root relaxation to rank
seeds by disagreement with the center, debiting the producing and consuming workers'
existing funding; a missing, closed or unaffordable hint falls back to ordinary seed
order without delaying a start or requesting a relaxation, and the repair owner
releases the snapshot on retirement. Guidance carries no witness, bound or proof
authority. Continuous columns stay free, cutoff checks stay exact, and later
proposals keep their captured conditions. Imported points remain query incumbents
and centers and earn no discovery reward: only a point the repair query produces
enters companion publication and original-source rechecking.

From the availability of a dimension-compatible incumbent through root and center
preparation and the first proposal, joint repair receives continuation after owed
complete-worker coverage, within its active cap, until the proposal finishes or the
companion's allowance retires it. This trial earns no incumbent reward and renews no
work, allocation or active time; a declined center clears the request, and later
proposals use ordinary selection.

`companion-repair=adaptive` requires completion and follows the small start with
the adaptive width sequence; fixed repair is the default. When a small trial is
refuted or improves without changing any Boolean or integer coordinate, the next
proposal frees at most one more connected integer, within the ordinary
thirty-two-integer and traversal ceilings, before the adaptive sequence resumes; a
disconnected component can supply fewer coordinates. A continuous-only improvement
still enters ordinary exact publication. Completion or decline of that proposal ends
the trial, and cancellation, exhaustion and the active, work and allocation limits
still bound it.

With progress reporting, joint repair streams a bounded number of comparison events
per worker, distinguishing offered, completed and discovered objectives against the
captured cutoff and the current pool, in exact working-objective arithmetic. The
comparison describes the observation site before pool admission, and an offered
point's real coordinates may still be advisory. Diagnostic evaluation spends the
existing LP funding, reporting time stays inside the worker's active lifetime, and
disabled reporting does no extra scoring. Companion statistics separate active-time
retirement, checked completions, publication checks and the actual width of opened
queries; these counts grant no publication or proof authority.

## Statistics

`SolveStats.run.wallMs` spans initialization, slices, verification and disposal for
one invocation; it replaces worker windows rather than summing them. Worker counters
merge independently, and parallel arm times may overlap. Arm `initMs` measures
handle opening; deferred preparation belongs to segment time and live counters.
`maxMs` is the longest segment, and `reseeds` counts arm reseeds. Lifecycle snapshots
retain the first incumbent-availability time; subscribed lifecycle streams retain
every accepted incumbent version. [Search statistics](11-search-policies.md#statistics) owns
propagation and conflict accounting.

An internal observer on a captured mixed finite source request follows the recipe
factory into native backtrack LP sessions, naming the worker and its last observed
external cutoff. That cutoff and a checked source publication remain separate
observations; neither supplies a query's missing private premises or promotes a
captured LP model to source proof authority.

## Worker recipes

Recipes select workers from model structure. Explicit pools, heuristic backtrack
edits and source annotations retain their requested composition. In single-core
mixed clausal satisfaction, private-rebuild work/byte and learned-memory scratch
settings alone preserve the default two-worker pool and its complete-search time
reservation; custom backtrack edit callbacks keep their selected composition. That
pool admits a bounded automatic private parity trial without changing canonical
learned-memory allowances or the reservation; explicit private work zero disables it,
and caller scratch and private-byte settings bound its preparation. Defaults live in
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
- Optimization needs neighborhood improvement and objective cores. The core worker
  is eligible when every objective term is Boolean, including exact views of 0/1
  integer costs, and the model has no continuous columns.
- A source annotation needs selector diversity, and its own search order ranks first
  for it.
- Optimization with a non-continuous objective over pure integer linear rows, or
  with a binary equality objective, needs LP bounding twice, which seats the
  aggressive emphasis beside the default. Pure integer linearity reads complete
  unconditional row conjunctions with 64-bit integer constants, including normalized
  exact source rows; a propagator-only symmetry factor preserves it, and every other
  factor must qualify. An exact binary integer objective view keeps it through its
  Boolean rows, value channels and redundant difference propagation; changed
  objective coefficients discard that capture. LP preparation reads the same capture
  to keep bounding over clause and cardinality rows under its dimension ceiling. See
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
reads no ALNS rank and is seated by the same rule. A rank that runs out repeats its
recipes on fresh seeds; the annotation, the core worker and ALNS never repeat.
Seated arms count toward the configured pool size.

Each rank is its catalog's credit order restricted to eligible recipes. Three leads
depend on the model: clausal satisfaction ranks its balanced clause-only recipe and
fixed-break probSAT first, pseudo-Boolean local search ranks the bandit flip walk and
jump moves first, and scheduling optimization ranks the primary-repair plateau recipe
beside the full one, both on iterated local-search restarts. An injected pool
replaces its engine's rank with the selected recipes in the given order, and its
first recipe is seated ahead of the needed roles: complete search first on an
optimization, local search first on satisfaction.

Single-engine pools take the prefix of their engine's rank, without the scheduling
lead; a backtrack-only pool of at least two arms gives the annotation its last slot.
A curated sequential mixed optimization pool larger than the default searches for its
first solution with the default roster and admits the remaining arms after the first
incumbent.

Portfolio statistics report each arm's seat as `role=` on its `arm.` line: the role
the arm was seated for, or `rank/` and its declared role when it filled a slot by
rank, as every arm of a single-engine pool does. A selected `WorkerAdmission` event
carries the same name.

On continuous columns with a continuous objective, single-core pools reserve half
the scheduled time for the default LP arm until the first incumbent. A default-size
sequential pool over continuous columns evaluates that arm as an auxiliary: without
progress it receives a [bounded evaluation](#auxiliary-incumbent-evaluation), and an
unproductive trial retires it without claiming exhaustion.

### Satisfaction pools

Finite CSP palettes admit `support-aware` after the SAT guard and conflict-driven
core when a ground Table has 2–32 positions, at most 65,536 tuples and at most 32
root values per position. It uses conflict-driven variable selection and completed
extensional support densities for value order under a bounded ranking allowance;
unavailable counts and wider domains fall back to random value order without
propagation probes. `propagation-guided` follows it under the same gate, keeping
support ordering and probing residual domains when counters decline; its local work
allowance belongs to one value ordering, while all its work also consumes the
containing worker slice, and explicit maxSD keeps its cancellation policy without a
local cap. [Search](09-search.md) owns probe restoration, coverage and accounting.
Explicit pools keep their recipes, optimization and other model classes decline
automatic admission, and MDD-only models keep the ordinary palette.

The satisfaction rank places dom/wdeg and first-fail ahead of the free engine and
the selector bandit; the optimization rank places the selector bandit ahead of LinUCB
and the free engine. A finite-CP roster that needs selector diversity therefore
seats support ordering on an admitted Table model, dom/wdeg on other satisfaction
models and the selector bandit on optimization.

The satisfaction backtrack palette includes conservative LP after default and
aggressive LP; applicability, model class and LP ceilings order and filter it. Root
variable shaving and hull pruning remain explicit plan options, and curated
satisfaction workers harvest root cuts only with an explicit `lp-root-cuts=true`.

Curated backtrack-only clausal and pseudo-Boolean satisfaction pools without source
annotations enable bounded [root maintenance](11-search-policies.md#restarts-phases-and-inprocessing)
on every complete worker. Each slice gives a shared inprocessing grant, most of it
for failed-literal probing with a per-probe ceiling and a small share for cheap
simplification; clausal pools give subsumption and vivification no grant, while
pseudo-Boolean pools keep their recipe's grants inside the shared ceiling. Clausal
pools require some non-inprocessing work before the first optional maintenance slice;
pseudo-Boolean pools have no warmup, and both space optional probing by further
non-inprocessing work. Consistent-probe binary capture is disabled unless
`root-probe-implications=true`. Clausal pools set `root-probe-binary-gate`, so
native workers without root facts or known binary clauses scan new learned binaries
before admitting global probes. Pseudo-Boolean pools set `root-probe-general-gate`,
which admits one initial global batch, then prioritizes new short learned clauses
when general workers have no root facts or binary hints; an empty completed
neighborhood probe prioritizes its opposite polarity inside the same grants. These
allowances stay inside each worker's containing slice, and
`inprocessing-probe-interval=0` follows the shared root trigger instead of that
spacing. `root-maintenance=false` keeps stock root defaults without changing
explicit root or probe settings, and backtrack edits apply after the automatic
defaults. Named pools keep their selected parameters, and native clausal proof
admission keeps its own policy.

Clausal complete-search recipes use clause learning, EMA restarts and a true-first
saved phase; their balanced and stable variants differ in VSIDS decay (0.975, 0.99)
and database cap (4000, 2000). Pseudo-Boolean models keep their cutting-plane
palette. Clausal satisfaction leads with the balanced recipe and, in a curated
sequential pool, reserves 98% of active time for it after the initial probes, because
a refutation emits no incumbent progress from which rewards could predict it. Its
local-search slots lead with fixed-break probSAT.

### Boolean objective core search

The internal non-retained MaxSAT drivers keep relaxed soft clauses eligible for
later cores, preserving their blockers through weight splits. The weighted threshold
driver bisects the bounded cost range using cost-only assumptions; a refutation
raises the bound only past the tested cutoff.

Mixed finite optimization rosters seat a retained core-guided complete worker for
the `objective-cores` role; other finite integer constraints remain admissible, and
Boolean cutoff consumers also accept its cost view. The worker assumes all positive
residual objective terms false and asks for a satisfying assignment. A refutation
returns a core: a set of terms at least one of which must be violated. The worker adds
the core's minimum residual weight to an exact lower bound and relaxes the core with a
reified cardinality, replacing its violation count `s` by `1 + sum(k = 2..n, s >= k)`;
only the first unspent threshold of each count is materialized. Weights and the bound
use arbitrary-precision integers.

Objective terms, residual-core updates, weight scans, core projection and query-pin
selection keep private cursors across slices, and a completed pin set stays with its
query model across interrupted openings. Preparation and private retirement spend the
same cumulative allowance as query traversal. Augmented queries keep the source's
established root domains, deductions and captured projections; their additional
constraints propagate in the query session. General query sessions keep projection
construction, root propagation and assumption seeding across slices. Native clausal
opening is atomic, so an interrupted opening keeps its charges, model and pins and
retries with the retained general preparer rather than repeating native construction
every slice. Closing discards query pins, auxiliary buffers and cursors while keeping
completed observations.

Published incumbents project away auxiliary selectors, and optimality requires exact
equality between the original objective and the accumulated bound. Auxiliary clauses
never enter shared channels. On a single lane with another complete worker, the core
worker joins after the first incumbent once ordinary complete workers stall, with the
allowance described in [resources](06-sessions.md#resource-allowances); parallel pools
admit it immediately, and `core-guided` selects it directly.

Curated single-lane mixed source execution instead selects an early reason-refined
core trial for nonuniform Boolean objectives over clauses, cardinalities and
pseudo-Boolean rows or their reifications only, within fixed size limits on Boolean
columns, factors and literal entries, when pseudo-Boolean row constants fit signed
words and the sum of the objective's absolute coefficients and constant fits the
exact scalar range below `2^53`. It excludes integer and real columns. Eligibility
scanning is cancellable and charges the containing invocation; unsupported or
interrupted eligibility keeps the ordinary delayed, unrefined policy. The trial keeps
ordinary complete-worker coverage, worker positions, seeds and its auxiliary
allowance, and explicit core settings, arm counts, recipes, annotations, backtrack
edits and work diagnostics keep their selected policy.

`core-early=true` admits the eligible bounded trial after initial complete-worker
coverage and before heuristic first probes, in a mixed optimization pool on one lane
with automatic backtrack workers and a Boolean objective. It keeps its opportunity
across preparation and query slices until it finishes or its cumulative allowance
stops it, on either side of the first incumbent. Worker positions, seeds,
complete-worker coverage and auxiliary limits stay fixed, including the containing
work allowance in diagnostic schedules, and the control does not expand core
eligibility.

`core-refinement=none|reasons|deletion` selects the refinement for curated and
directly selected core workers. Reason refinement projects a seed proof through
the [historical reasons](10-learning.md#explanation-lifetime) of the existing
query, without another propagation session or solve; unsupported seed conflicts
and tree exhaustion keep their certified cores. Deletion tries removing each
member of a completed core with a fresh propagation refutation of the augmented
hard model and the caller pins, over built-in projections of clauses and reified
cardinalities within small fixed size limits. It never removes the final member,
keeps the certified core on a stop, shares the worker's work and active-time
allowances, performs no search and does not promise a minimal core: members
whose refutation needs search stay. Caller conditions stay attached to every
augmented query.

Selecting `core-guided` directly also accepts exact integer costs over columns whose
source bounds are closed on both sides; curated pools admit it only for Boolean
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
