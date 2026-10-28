# 12. Portfolios

A portfolio schedules retained workers over one source and objective. Workers share
checked incumbents and certified deductions; the coordinator owns slice allocation,
publication admission and cleanup. Prepared capabilities decide which arms can run,
while proof scope decides which claims they may make.

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

Explicit `objective-guidance=canonical|functional|geometric` selects local-search
objective guidance within an explicitly selected recipe pool (`arm`, a named
`strategy`, or LS axis edits). It cannot construct an automatic pool.
A comma-separated list expands each selected recipe into
fresh slots in that order; `arm=cbls-plateau/ils-basin` with
`objective-guidance=functional,geometric` and two LS arms runs a retained pair.
Functional guidance is the ordinary default. Canonical guidance retains linear
grading and proposals; geometric guidance retains linear grading with qualified,
bounded cone-leaf proposals. It uses the same baked model, source identity and
checked solution exchange. Unsupported cones retain canonical execution and
report a decline. Reseeding opens a fresh search, revalidating its live objective,
network and conditions. These policies grant no bound, terminal, pool or warm-start
authority beyond the worker's existing source contract.

For default single-core mixed placement optimization, an eliminated affine objective
coordinate can supply a private protected-coordinate view to the curated full
local-search slot. This applies to `Diffn` models without `Element` alternatives,
where primary and full neighborhoods offer the same repairs. The slot runs the full
plateau recipe with its configured seed offset; the primary retains the rewritten
model. Protected aliases guide infeasible repairs; the canonical objective orders
feasible incumbents. The view retires at the next scheduling boundary after the first
global witness, so canonical workers handle incumbent improvement. No arms or
allowances are added. Custom local-search recipes, named backtrack pools, explicit
arm counts and explicit presolve keep the selected representation.

The private view shares no pools, warm starts or scalar cutoffs. Its source mapping
must retain the same original model and a compatible discrete column namespace.
Each proposal is reconstructed, stripped of private certificates, checked against
the canonical prepared model and evaluated with its objective before publication.
Private terminal proofs have no source authority. Resumable preparation, instruction
accounting, cancellation and closure follow the private handle.

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

`SolveStats.run.wallMs` spans initialization, slices, verification and disposal for
one invocation; it replaces worker windows rather than summing them. Worker counters
merge independently, and parallel arm times may overlap. Arm `initMs` measures
handle opening; deferred preparation belongs to segment time and live counters.
`maxMs` is the longest segment, and `reseeds` counts arm reseeds.
[Search statistics](09-learning.md#statistics) owns propagation/conflict accounting.

## Witnesses and proof scope

[Source validation](07-presolve.md#source-witness-validation) checks coordinates.
Portfolio admission additionally checks publication identity, invocation conditions
and the authority required by the claim.

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

Incumbents carry immutable source coordinates in `Sample`, including exact integers
and reals. The source objective orders them independently of native minimized cutoff
units. Changing coordinates or projecting a warm start discards acceptance and
requires a fresh check; [model ownership](02-model.md#published-assignments-and-objectives)
defines buffer lifetimes.

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
numerical workers keep open declarations in arithmetic state.

The opt-in numerical companion recipe adds admitted finite search and joint linear
repair to a retained numerical frontier. Finite admission uses proved source bounds
and unchanged working coordinates; a declined finite projection leaves the other
workers available. Each worker owns its traversal, LP state and cumulative quota
under the containing portfolio deadline and decision/move allowance. Joint repair
uses the same cut layout at its root and in retained queries.

Companions publish witnesses only, rechecking their complete coordinates against
the captured affine source before publication. The frontier imports accepted
incumbents for scoped dominance closure. Finite search reads conservative minimized
incumbent cutoffs. Its existing floor channel accepts source-checked frontier
bounds for an ascending unit integer objective without an offset; other objective
forms retain their numerical bounds. Joint repair can center a fresh proposal on a
checked pooled incumbent;
pending proposals keep their scope until completion. Global proofs remain numerical
derivations with complete sibling coverage. The independent companion recipe keeps
the same workers and quotas while withholding incumbent and bound imports.

The automatic linear-source finite companion policy keeps the ordinary finite recipes, arm identities,
seeds, sharing channels and completion authority. It appends numerical frontier and
joint-repair workers to sequential mixed optimization over binary integers and
continuous columns, with at most 4,096 numeric columns and 16,384 factors. Complete
affine capture and LP bounding must be available; the redundant propagated difference
graph is omitted from that relaxation. Explicit recipes, nondefault pool sizes, annotations,
parallel pools and purely integer models retain their finite composition.
The numerical frontier receives the first bounded probe with weight three; other
probes have weight one. The shared half-run probe allowance is split by these weights,
so a small numerical proof can complete before opening finite handles. Finite probes
retain their order and seed positions.

Each appended worker keeps at most four seconds of active search. Numerical segments
take at most one second and joint-repair segments 250 milliseconds, further limited
by the containing offer. Each segment caps native work or counted instructions at
5,000. Before the first incumbent, joint repair is owed 30% of scheduled search time
in addition to the finite policy's reservations: root preparation earns no incumbent
reward until a proposal completes. Atomic preparation and certification can overrun;
overspend retires that worker at its next turn without claiming exhaustion. Workers
cannot reseed to renew these limits. The two owners divide the containing numerical
work and allocation ceilings equally and retain their query funding across slices.
They consume the finite scheduler's checked exact incumbent exchange. Joint repair
has witness authority. Its full-source check of a completed point uses the containing
lifetime as an atomic publication step; this time belongs to its active allowance.
A numerical optimum or refutation can complete the finite
invocation only after its typed terminal verifies against the same captured working
model and objective; the finite pipeline retains original-source reconstruction and
qualification. Other numerical terminals and incomplete coverage carry no completion
authority. Finite workers' ordinary terminal admission remains available throughout.

Companion statistics distinguish active-time retirement and checked completions.
`jointRepairPublicationChecks`, `jointRepairPublicationNs` and
`jointRepairPublicationPauses` identify completed-point verification effort.
The numerical-first recipe separately records its finite worker's actual native
optimum, refutation or cutoff exhaustion before withholding its proof authority.

## Scheduling

The scheduler assigns bounded slices, revisits pending preparation and reseeds stale
workers under the containing [allowance](06-sessions.md#resource-allowances).
Scheduling changes work order, never publication authority.

### Reproducible diagnostics

`portfolio-work=N` opts finite Boolean/integer portfolios into a single-lane
diagnostic schedule with a containing charged-work limit. `PortfolioDiagnostics`
provides the same control to low-level callers. Selection retains seeded reward
sampling and stable arm order, but minimum shares, feasibility coverage and retained
proof coverage use charged work. Candidate checks run at every improving proposal;
auxiliary preparation and search retire by work. Deferred core search uses a work
allowance. Numerical companions and parallel execution reject this mode.
ALNS operator selection and inner repair seeds derive from its worker seed.

Each segment offers the smaller of its work allowance and the containing remainder.
Initialization work reported by a retained handle is included. A zero-work return
charges its offered allowance, bounding private preparation and unsuccessful pauses
that expose no finer counter; this reservation is explicit in the trace. Each
coordinator verification request charges one additional unit, including rejected or
indeterminate checks. This logical price is separate from measured verification time.
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

Recipes select workers from model structure. Explicit pools, backtrack edits and
source annotations retain their requested composition. Defaults live in
[portfolio construction](../klause/src/commonMain/kotlin/com/eignex/klause/portfolio).

### Model-specific worker recipes

Curated sequential mixed finite optimization pools diversify both neighborhoods and
branching, without adding allowance:

| Structure | Additional diversity |
|---|---|
| Scheduling | A primary-repair CBLS variant beside the full neighborhood, both using iterated local-search restarts |
| Annotated circuit/subcircuit with first-fail/min values | Unrestricted first-fail beside the annotation's tiered scope |
| Finite objective without an initial LP recipe | A restart-level bandit over variable/value selectors (`selector-switch`) beside the SAT guard |

The latter two require at least six configured slots and preserve pool size.

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
  with 64-bit integer constants, including normalized exact source rows. A
  propagator-only symmetry factor preserves this eligibility; at least one row
  conjunction is required, and every other factor must qualify. An exact binary
  integer objective view retains this admission through its Boolean rows, value
  channels and redundant difference propagation. Its captured source rows and costs
  also preserve binary packing and equality recipes; changed objective coefficients
  discard that capture. LP preparation reads the same capture to retain ordinary
  bounding over clause/cardinality rows under the existing dimension ceiling.
- **Continuous columns.** The default LP arm ranks after the SAT guard and
  conflict-driven core and joins first-solution search under the ordinary bounded
  slices for both finite and continuous objectives. Single-core pools reserve half the
  scheduled time for it until the first incumbent when the objective is continuous.
  An auxiliary LP arm without progress then receives a
  [bounded evaluation](#auxiliary-incumbent-evaluation),
  and an unproductive trial retires it without claiming exhaustion.

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
its ordinary cancellation policy without a local work cap. [Search](08-search.md) owns probe restoration,
coverage and accounting.

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

The internal non-retained MaxSAT drivers keep relaxed soft clauses eligible for
later cores, preserving their blockers through weight splits. The weighted threshold
driver bisects the bounded cost range using cost-only assumptions; a refutation
raises the bound only past the tested cutoff.

Curated mixed finite optimization pools append a retained core-guided complete worker
when every objective term is Boolean, including exact views of 0/1 integer costs,
and the model has no continuous columns. Other finite integer constraints remain
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
threshold scan. Preparation visits and source-verification propagation spend the
same cumulative worker allowance as query traversal. Augmented queries retain the
source's established root domains, deductions and captured projections; their
additional constraints propagate in the query session.

Internal core refinement is default-disabled. Seed-proof projection uses the
[historical reasons](09-learning.md#explanation-lifetime) of the existing query,
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

Published incumbents project away auxiliary selectors and pass the source check, and
optimality requires exact equality between the original objective and the
accumulated bound. Auxiliary clauses never enter shared channels. On a single lane
with another complete worker, the core worker joins after the first incumbent once
ordinary complete workers stall, with the allowance described in
[resources](06-sessions.md#resource-allowances); parallel pools admit it immediately, and `core-guided`
selects it directly.

### Exact theory strategy execution

Linear, polynomial, nonlinear-integer and quadratic routes supply factories to the
same invocation. Replicas keep private arithmetic state, traversal and quotas across
slices. Typed terminals are admitted only after source, condition, objective,
attainment and strictness checks.

Linear sources can pair complete traversal with
[exact-coordinate neighborhoods](11-neighborhoods.md#exact-coordinate-neighborhoods),
including real-only models and wide integers. Those handles retain centers, radii
and adaptive weights; window refutations never become source proofs.

---

Previous: [11. Neighborhoods and restarts](11-neighborhoods.md) · [Contents](README.md) · Next: [13. Original-source derivations](13-evidence.md)
