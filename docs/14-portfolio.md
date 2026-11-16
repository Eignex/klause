# 14. Portfolios

A portfolio schedules retained workers over one source and objective. Workers share
incumbents and certified deductions; the coordinator owns slice allocation,
publication admission and cleanup. Prepared capabilities decide which arms can run,
while proof scope decides which claims they may make.

When every arm retires undecided, satisfaction and optimization retain the same
stop reason: `Unsupported` takes precedence over `RepresentationDeclined`, followed
by the other recorded worker reasons. An unfinished optimization keeps its verified
incumbent with that reason; an arm decline alone does not imply budget exhaustion.

## Invocations and recipes

Finite, linear open, polynomial, nonlinear-integer, quadratic and transcendental
source execution supply retained recipe factories to `PortfolioInvocation` with the
routing plan and current executable projection. The invocation owns worker slots,
seed offsets, parallel quotas, scheduler controls, result verification and cleanup;
mutable services registered with a slot close when the slot is released, and each
replica gets private state. ALNS operator choices and inner repair seeds derive
from its worker seed in ordinary and diagnostic schedules. Native reservations
divide the complete family's allowance across its slots. In the polynomial,
quadratic, nonlinear-integer and transcendental portfolios, a slot binds the
backtrack seed, decision share and node quota, and the local-search seed, flip and
instruction shares and node quota; other limits keep their own work currencies,
and finite and linear open factories bind their slots directly. Heuristic-only
factories carry witness authority, and `FIXED` rejects multiple cores.

`SourceExecutionPolicy` sets finite arm defaults and LP ceilings before worker
construction; explicit `arms`, `ls` or `bt` settings take precedence. Native
arithmetic routes reject finite-only explicit policy instead of ignoring it. Recipe
defaults carry no witness or proof authority.

Named recipe pools retain the selected worker count and repeat their recipes across
the slots. `bt-arm=X` selects the backtrack recipe; `arms=1` with the `cp` engine
selects one worker. An explicit pool with one recipe can therefore have several
private replicas with separate seed offsets.

Explicit `objective-guidance=canonical|functional|geometric` selects local-search
objective guidance within an explicitly selected recipe pool (`arm`, a named
`strategy`, or LS axis edits). It cannot construct an automatic pool.
A comma-separated list expands each selected recipe into
fresh slots in that order; `arm=cbls-plateau/ils-basin` with
`objective-guidance=functional,geometric` and two LS arms runs a retained pair.
Functional guidance is the ordinary default. Canonical guidance retains linear
grading and proposals; geometric guidance retains linear grading with qualified,
bounded cone-leaf proposals. It uses the same baked model, source identity and
solution exchange. Unsupported cones retain canonical execution and
report a decline. Reseeding opens a fresh search, revalidating its live objective,
network and conditions. These policies grant no bound, terminal, pool or warm-start
authority beyond the worker's existing source contract.

For default single-core mixed placement optimization, an eliminated affine objective
coordinate can supply a private protected-coordinate view to the curated full
local-search slot. This applies to `Diffn` models without `Element` alternatives,
where primary and full neighborhoods offer the same repairs. The slot runs the full
plateau recipe; the primary retains the rewritten model. Protected aliases guide
infeasible repairs; the canonical objective orders feasible incumbents. The view
retires at the next scheduling boundary after the first global witness, so
canonical workers handle incumbent improvement. No arms or
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
[original-source derivations](16-evidence.md).

Finite recipe and numerical companion admission uses `WorkerRequirements`: supported
fragment, canonical objective or canonical objective with heuristic guidance, checked
terminal, conditioned proof or witness-only authority, working-coordinate or
checked-reconstruction publication mapping, private resumable state, and structural
construction dimensions. Dimensions estimate setup
size rather than elapsed time or retained bytes. Numerical requirements also carry
the containing per-owner LP work/allocation ceilings, active-time limit and segment
limits; LP construction and retained queries share those ceilings. Candidate checks
and typed terminal verification remain the authority boundary.

`SearchEvent.WorkerAdmission` reports these requirements and explicit decline causes,
distinguishing eligible catalog candidates from composition-selected workers.
Curated backtrack filtering reports unmet model needs; selected finite recipes report
their actual worker authority. Explicit named recipes bypass automatic filtering.
An explicit arm count, including the default-sized count, prevents automatic numerical
companions. A missing complete affine capture declines both companions without
removing finite workers. `FiniteCompanionArms` owns companion admission, construction,
arm order, preparation telemetry and scheduler shares; the finite portfolio only
seats the arms it returns.

The numerical companion retains its checked root-global bound across pauses and
retirement. Finite results carry this evidence separately from additive counters.
Objective-preserving preparation maps its scalar through the captured positive
scale and offset; an unavailable mapping withholds the bound. Linear source
qualification restores original objective units and direction and retains
strictness. Restricted or inexact nominal views withhold a global source bound.
The result and ordinary output label this as a native nominal-source claim;
tolerance coverage is separate, and publication confers no independent proof
validation or terminal status. Reading the retained bound requests no LP work.

Ordinary shared affine preparation precedes worker factories. Final invocation stage
counters report `finiteCompanionPreparationAttempts`, `finiteCompanionPreparationNs`,
`finiteCompanionPreparationReady` and `finiteCompanionPreparationUnavailable`,
including captures whose workers receive no query. The time spans duplicate
difference-factor filtering and immutable capture; it excludes factories, queries,
candidate checks and retirement. It belongs to containing solve time and grants no
new allowance or source authority.

`companion-admission=funded` requires incumbent-gated starts and a containing
deadline. The same capability gate admits candidates only when both requested
active lifetimes fit. Shared affine capture and private service construction wait
for the first companion query. Capture obeys that query's time grant, the existing
construction segment ceiling and the time left after both requested lifetimes.
Each worker rechecks its own remaining lifetime before opening; a late start or
unavailable capture retires without a source verdict. Finite recipes, indices and
seed positions remain intact, including explicit selections.

`companion-admission=progress-funded` retains funded construction and also waits for
a strictly improving accepted pool incumbent after the first witness. Rejected
proposals, repeated points, deductions and idle sharing credit do not open either
companion. Workers waiting for this improvement receive no query or capture;
remaining-lifetime checks apply when they become eligible. Their positions and
seeds remain fixed, and ordinary complete workers retain their coverage. An
incumbent retained before the invocation does not supply fresh progress. Cancelled
queries and empty time grants cannot start capture.

Deferred capture is attempted once for the pair and charged inside the opening
worker's query and active lifetime. Its preparation counters are subsets of that
query's time. `finiteCompanionAdmissionDeclined` records an unfunded initial pair;
`finiteCompanionStartDeclines` records declined opening attempts. A pair receiving
no query reports zero capture attempts. Final invocation accounting reads these
shared counters once; worker summaries do not duplicate the capture.

Finite numerical factories can withhold shared assignments independently of
completion. The shared incumbent exchange, scalar bounds, admission and lifetimes
retain their configured policies.

`source-incumbent-completion=true` lets the numerical and joint-repair companions
use an approximate incumbent's Boolean and finite integer coordinates as advisory
pins. Approximate real coordinates guide rational reconstruction without carrying
authority. A reconstructed point must satisfy every active affine row exactly;
otherwise connected continuous residuals use the existing exact recovery path.
Residuals with nonzero objective coefficients use the source-column costs in the
working objective direction, within the same sparse recovery allowance. Zero-cost
components retain feasibility completion. Recovery supplies a checked point and
no global bound; discrete pins and the complete reuse identity stay fixed.
Original-source witness and exact objective checks precede incumbent admission,
cutoff use or neighborhood centering. Supplied
exact payloads retain their direct admission checks.
Before a checked center is available, root repair can retain the ordinary
pre-selection relaxation point for advisory centering and neighborhood order.
Point-selected bounds remain separate; a checked selected root witness retains
its selected guidance. Both points belong to the same funded root selection round.
Ordinary linear sources reconstruct the completed point through the captured
presolve mapping, check the original declarations and rows, and compare the original
objective with the adjusted working value before import.
Completed advisory seeds require exact original feasibility even when ordinary
finite witness admission permits source tolerance. Tolerance-only acceptance cannot
authorize this exact handoff.

With completion enabled, a checked pooled center starts joint repair in a small
connected integer neighborhood without a root relaxation. The all-pinned
rounded-root bootstrap is reserved for a center without that checked handoff.
The pair can retain one advisory point from a numerical companion's paid root
relaxation. A matching capture ranks connected-neighborhood seeds by disagreement
with the checked center; widths, pins and exact cutoffs retain their ordinary rules.
Snapshot retention and ranking debit the producing and consuming workers' existing
work and allocation. A missing, closed or unaffordable hint uses ordinary seed order
without delaying a start or requesting a relaxation. The repair owner releases the
snapshot on retirement; guidance carries no witness, bound or proof authority.
Continuous columns stay free, source
cutoff checks remain exact, and later proposals retain their captured conditions.
Imported points remain query incumbents and centers; only a point produced by the
repair query enters companion discovery publication and original-source rechecking.
If that first proposal pauses, it receives continuation after owed complete-worker
coverage until the proposal finishes or the companion's existing allowance retires
it. This trial grants no incumbent reward and does not renew work, allocation or
active time. Later proposals use ordinary selection.

With portfolio progress reporting, joint repair streams at most sixty-four
comparison events per worker. Query sequence numbers preserve a paused query's
identity; zero denotes center preparation outside a query. Events distinguish
offered, completed and local discovery objectives, the captured cutoff, and the
current pool version and objective. Values use exact working-objective arithmetic;
an offered point's real coordinates may still be advisory. A discovery's improving,
tied or dominated comparison describes the observation site before pool admission.
Diagnostic objective evaluation spends the existing LP funding, and reporting time
stays inside the worker's active lifetime. Disabled reporting does no extra scoring.

Each companion owns at most sixteen source/point attempts without eviction. Identity
includes the full source restrictions, rows, coordinates, objective, interpretation
and checker, plus the seed values and coordinate frame. Failed unchanged attempts
remain declined; paused recovery keeps its progress under the same cumulative
allowance. Preparation, residual solving, checking and retained allocation spend the
companion's existing LP funding, and closure releases its residual owners. The
control defaults to false and changes neither worker admission nor lifetimes.
Planned: incumbent handoff to companions becomes the default.
Advisory keys retain value snapshots without producer certificates. Unsupported
symbolic payloads, integers outside the recovery scalar limit and keys beyond the
remaining modeled allocation are refused before retention.

The admitted pair can reuse at most sixteen successful original-source completions
under that same full identity and original-check callback. Each retained point belongs
to its producing completion owner and is removed when that owner closes. Only immutable
coordinate snapshots cross workers; producer certificates and failed or pending work
stay private. Lookup and copied snapshots debit the consumer's existing funding, and
each consumer retains its source witness and objective admission checks. Sharing grants
no work, lifetime, bound or terminal authority.
Consumers reserve the retained exact scalar payload under their own allocation ceiling.
Completion statistics distinguish shared checked reuse from private recovery; both
remain inside the consuming owner's completion time.

`companion-integers=true` trials the same bounded companion pair on finite general
integer/continuous sources. It changes only the binary-fragment restriction; size,
source capture, objective, LP ceiling, single-lane mixed pool and explicit-recipe
checks remain required. The binary-only gate remains the default. Work-driven
scheduling does not admit numerical companions.

## Statistics

An internal observer on a captured mixed finite source request follows the ordinary
recipe factory into native backtrack LP sessions; other LP calls are outside its
scope. Each callback names the worker
and its last observed external cutoff; it preserves source preparation, selected
recipes, seeds, sharing and companion admission. That cutoff and a checked source
publication remain separate observations. Neither supplies the query's missing
private premises or promotes a captured LP model to source proof authority.

`SolveStats.run.wallMs` spans initialization, slices, verification and disposal for
one invocation; it replaces worker windows rather than summing them. Worker counters
merge independently, and parallel arm times may overlap. Arm `initMs` measures
handle opening; deferred preparation belongs to segment time and live counters.
`maxMs` is the longest segment, and `reseeds` counts arm reseeds.
Lifecycle snapshots retain the first incumbent-availability time; subscribed
lifecycle streams retain every accepted incumbent version.
With progress reporting, each worker emits one continuation observation for its
first selected improvement segment. It records retained-handle reuse, offered time
and work, earlier slice debt repaid, measured work, the completion flag, return
disposition and remaining containing deadline. Unsupported repayment observations
remain absent. A zero-work pause alone establishes neither exhaustion nor lost
continuation; repayment can consume the entire offered allowance. These observations
confer no proof authority, change no selection or funding, and execute inline within
the segment. Disabled reporting constructs no continuation event and reads no handle
accounting.
Progress reporting also emits one worker-exit observation at retirement or invocation
cleanup. It separates the removal cause from the last native return and incomplete-result
reason, before coordinator proof reinterpretation. The last external cutoff retains its
raw floating-point bits and the pool version read with it; absence means no read, and
version zero means an empty pool. It does not describe a worker's private effective cutoff
or establish proof authority. Observation storage is bounded per worker and allocated
only with reporting enabled; capture adds no pool reads, cancellation polls or handle
inspection. Internal admission-policy progress alone does not enable exit reporting.
Reseeding a handle does not remove its worker.
[Search statistics](11-search-policies.md#statistics) owns propagation/conflict accounting.

## Witnesses and proof scope

Finite workers' points are engine results and enter the pool as published. A private
protected-coordinate view solves a differently presolved model, so its lifted point is
checked against the canonical model. Arithmetic points pass
[source validation](08-presolve-reductions.md#source-witness-validation) where their engine does
not enforce the source exactly. Claims need the authority below.

### Publication identity

Portfolio evidence names its source model and objective. These are opaque in-memory
identities: a fresh or transformed model is another identity, even if structurally
equivalent. Source arm materialization installs one immutable `SourceWorkerBinding`
before scheduler admission, capturing model and objective identity, source scope,
the producer invocation, a separate containing sharing invocation and proof
authority. Producer conditions cannot justify unconditional imports into siblings,
and a completed binding cannot promote witness-only authority.

Every worker publishes its prepared invocation, execution assumptions, owned point and
objective value. A native arithmetic publication from a private invocation is checked
against the containing invocation's pins, which its producer did not enforce. A shared
point keeps its producing publication instead of acquiring another worker's identity.

### Owned assignments

Incumbents carry immutable source coordinates in `Sample`, including exact integers
and reals. The source objective orders them independently of native minimized cutoff
units. Changing coordinates makes a new sample;
[model ownership](02-model.md#published-assignments-and-objectives) defines buffer
lifetimes.

### Verification outcomes

An arithmetic witness check has three outcomes. Accepted witnesses may become
incumbents; rejected ones quarantine their producer without proving the model
infeasible; indeterminate checks publish nothing. Uncertified real coordinates need a
complete source-row check or the configured source tolerance check.

Open theory execution publishes `SourceIncumbentPublication` after
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
[derivation verifier](16-evidence.md). Private branch bounds and terminals the
scheduler did not admit cannot finish the source.

### Shared channels

Built-in shared channels belong to the same model and objective. Workers reject
foreign pools; objective and variable bound imports require matching certificates.
The incumbent exchange installs only a finite, strictly improving objective. The low-level portfolio API also
supports caller-trusted unbound workers with an explicit witness checker. Learned
clauses are exchanged only when at least two backtrack workers exist.

`portfolio-publications=segment` coalesces private local-search offers within one
query of a curated single-core mixed finite integer optimization with a containing
deadline. It retains one immutable candidate, ranked by the worker's canonical
objective, and offers it at the query boundary. Failed queries discard pending
offers; expiration before installation withholds it. Direct public exchange offers
install immediately.
Explicit pools, parallel execution and work-driven schedules reject this policy;
the default is `immediate`. Private-coordinate workers and ALNS retain their own
publication paths. Query time includes the deferred offer, also reported in the LS
publication phase and incumbent sharing time; these overlapping measures must not be
added. `ArmSchedule.sharing.incumbents` and CLI `incumbent*` arm fields report offers,
coalescing, outcomes and dropped pending points. Exports count
installed incumbents rather than queued offers. No candidate queue or rejected-point
history survives the query.

Detailed memory sampling requires `sharing-memory=true`, independently of the
single-lane charged-work schedule (`portfolio-work`). It defaults to false;
traffic/work/use counters remain inexpensive. Each sample has separate
`sharing-memory-work` and `sharing-memory-scratch` limits (32,768 visits and
4,096 deduplication entries by default; zero is permitted).
Posting protection checks stop after finding a required owner; the full allocated
capacity remains counted.
Cancellation and exhausted optimization slices export glue through `onSearchEnd`. Other cooperative
pauses use `onSearchPause` to observe retained memory without publishing clauses or
draining scheduler credit. Uses remain pending until a restart, cancellation boundary
or uncancelled close. Both pause hooks retain the duplicate filter; `onSearchClose` releases it.
Clause accounting is reported per worker in `ArmSchedule.sharing.clauses` and as
`clause*` fields in the CLI arm record. Pool payload belongs to one shared pool;
primitive payload arrays are counted once by identity. A private owner of disjoint
arrays can report one stable pool identity with total bytes and its cumulative protected
portion. Pool reports cover the same immutable owned region within an observation;
upgrading protection preserves the owned-byte total. A weaker report retains earlier
protection. Reference allocations retain their own protection granularity.
Owner deduplication follows platform identity-hash bits through a binary tree.
Each owner uses one scratch entry; lookups charge each visited node and never invoke
user equality or hash functions. After the 32 hash bits, identity collisions follow
a charged chain. Protected-ID sets use value equality; copied-reason and payload
sets use identity. These sets follow the same hash-bit traversal and fund one
scratch entry per retained value. New owners fund their allocation before publication. Exhausting
either observation limit leaves a partial sample without complete byte totals.
Private clause-reference,
global-flag, origin, key and payload-owner array capacities are modeled separately.
Sources with no integer variables use a Boolean-only pool whose payload-owner
index reserves one array per clause; atom payloads are declined. Empty export
arrays use shared immutable zero-length arrays. `poolPayloadOwnerCapacityBytes`
reports that index allocation, included in `poolPrivateIndexBytes`, and
`poolBooleanOnly` identifies the layout. Growth and reclamation fund the selected
layout before publishing it; consumers keep the same clauses, origins and cursors.
`poolOwnedBytes` combines these modeled owners, excluding headers and allocator
overhead. Repeated worker observations must not be summed.
Worker `seenKeys` and `seenCapacityBytes` describe the session-spanning duplicate
filter, including exported and evicted keys. Its lifetime is the native session,
not the pool generation. Compaction invalidates the drain cursor but preserves
that filter. Reseeding a retained repair session keeps the filter with its
learned database. A fresh session releases its backing arrays and reimports the current
pool; resuming the same session retains both cursor and filter. Retirement records
the pool and filter capacity counters, then releases the filter without traversing
the store. An uncancelled retirement also drains uses and exports
remaining glue; a cancelled retirement skips those scans.
A retirement batch that could allocate or grow the worker's private key table
can use the pool's existing index instead. If it fits the remaining entry capacity
and has no byte limit, the pool deduplicates it under the publication lock. The
generation cannot compact during that batch, so fresh keys need not enter the
worker's retiring filter. Existing private keys still suppress earlier imports
and exports. Batches that fit the existing private backing, entry pressure and
byte limits keep the ordinary private admission and reclamation path. Capacity
refusal happens before publication; exhausting
publication funding ends the export without another allowance. Every candidate
visit and pool operation retains its work, scratch and cancellation bounds.

Glue export probes that same filter with the portable content key before copying
native literals or constructing portable payloads. An empty filter skips duplicate
probing. One temporary token buffer is
reused across the scan and released with it; no second retained duplicate index is
needed. Only completed export preparation admits publication keys. Pool eviction,
session reseeding and reason retention follow the same filter lifetime.
Native Boolean exports transfer the arena's fresh literal copy into the portable
payload. Boolean-only imports pass that payload to the receiving store's owning
copy; atom imports first translate the session-local literals. General-store
exports build separate portable arrays.
`exportDuplicates` counts previously shared candidates skipped before payload construction.
With a byte limit, key probes charge work and poll cancellation under one lookup
allowance per scan. Exhaustion discards the prepared export before admitting keys.

With a shared byte limit, each worker's duplicate filter has a separate allowance
of that size. Existing key capacity is protected with its retained session, including
reseeded assumptions. Bounded admission prepares hash growth before publishing it;
insufficient bytes, work, scratch or cancellation declines the optional import or
export and increments `seenDeclined`. Declines are separate from duplicates. A full
filter keeps its existing keys through pool generation changes, preventing repeated
imports into the retained native store. Close or a fresh session releases its arrays.
Optional exchange preparation also funds the complete drain suffix before copying
references or advancing its cursor. Export preparation admits the full candidate
scan, portable payloads and content-key construction against conservative work and
scratch bounds before constructing them. Length checks precede native literal
copies. Unfunded or cancelled preparation declines that exchange attempt. A declined
drain keeps its cursor; a declined export admits no publication keys.

The pool's optional byte limit includes payload and private capacity. Its protected
floor is zero: eviction removes optional shares while imported reasons and a
previously drained batch retain their own references. Pressure prepares a replacement
store under work, scratch and cancellation limits, retaining globals before glue and
using the existing quality order. Every rank comparison, hash probe and copy is
charged; allocation size is admitted and charged before allocation. A cancelled or
exhausted preparation leaves the current generation intact. Publication swaps the
prepared store and generation without remapping consumers. Old payload-owner and
index arrays become unreachable when the operation returns. A byte limit too small
for a share declines publication, and unfinished preparation reports a safe defer.
Neither requires a search restart. The entry cap and disabled byte-limit default
retain their ordinary policy; bounded work and scratch do not promise a hard pause
latency.

`inspected` counts entries examined, including duplicates, and
`translatedLiterals` counts imported literals. `importWork` includes drain visits,
translation scalar visits and immediate native attachment; subsequent fixpoint
work remains ordinary propagation work. Channel time includes export, compaction,
accounting and use draining. Memory sampling polls cancellation and charges each
store/reference visit and deduplication comparison before performing it. The native
and shared observers use one allowance and one scratch-entry cap; `accountingWork`
reports that work in the containing propagation allowance. `uses` drains the existing origin-use counter once,
sharing that observation with contribution rewards: it counts consumption once per
reduction window, rather than exports or checks. The retained peer-import and
consumed-peer-import counts describe retained objects, including logically retired
slots awaiting compaction, so repeated window uses do not imply additional imports.
Native totals update at admission, first consumption and physical removal; ownership
snapshots read these totals without traversing the policy columns. When every
retained Native row is permanent, payload-page word totals also determine the
protected payload capacity without a row scan. Mixed stores inspect protection
for their retained payload rows.

Learned-store observations include native arena capacity or general clause/PB/row
payload, policy columns, session watch/queue capacity, pending attachments and
active or historical antecedent arrays and their factor/position indexes, undo
journal and bound/hole histories. Typed event subscriptions/deltas, pending atom
channeling and root-source retirement indexes retain their own observations. The immutable source clause arena belongs to the prepared problem and is shared
across workers. Session watch capacity includes base
watches and cannot be added to a separate base-watch estimate. Native posting pages
report their allocated primitive capacity as one private pool per literal. A protected
posting protects its page and the containing directory, while unrelated pages remain
outside the protected floor. `sessionWatchEntries` includes retained stale postings;
`sessionWatchCapacityBytes` includes posting headers and allocated page capacity.
Native posting reference slots count allocated directory capacity and its owning link. Reason arrays are
identity-deduplicated within their observation, including saved integer/atom reasons
and lazy markers/memo results; they can alias learned payload. Reference-slot
counts describe live entries unless explicitly named capacity. Object headers,
collection spare reference capacity and allocator overhead are platform-dependent
and excluded; physical resident memory requires an external process measurement.

`PropagationSession.sharingMemory` returns a `SharingMemorySnapshot` with
`COMPLETE`, `PARTIAL` or `STALE` status. Only `completeValues` permits a consumer
to use the observed fields as a complete sample; it is null for partial or stale
data. Complete describes traversal coverage of these modeled fields, not a
deduplicated total across owners or physical bytes. Cancellation, exhausted work
or exhausted scratch returns partial data without changing deductive state. Only
finished categories are present. Scratch retains at most the configured number
of fixed-size linked entries, and each deduplication comparison is charged.
Sparse primitive maps visit every capacity slot under the same allowance; lazy
reason memos retain insertion order so iteration does not scan spare capacity.

Telemetry exports mutually exclusive `memorySampleComplete`,
`memorySamplePartial` and `memorySampleStale` flags, plus `memorySampleWork` and
`memorySampleScratchEntries`. Each observation replaces current sample fields;
partial samples cannot inherit absent fields from an earlier complete sample.
Close replaces current fields with pool and filter counters and marks the worker
sample stale without a diagnostic scan. Retained `peak*` values remain historical per-field maxima and must never
be used as a complete byte total or added into concurrent high-water marks.
`drain*` fields describe
transient batch storage. ALNS repair pools remain worker-private, skip search-conditioned permanent clauses
and decline direct global nogoods; their worker meter reports that separate owner.
The finite adapter observes its shared-search learned store and copied reasons
through a read-only callback in the native session; its lifecycle owner clears
that callback on close. These `shared*` fields belong to the coordinator,
separately from native storage. Open-theory lifecycle statistics do not trigger
memory scans from a scheduling token. A pool cap bounds its entries, not cumulative
worker seen keys, learned stores or pinned reasons. Retention and delivery policy
remain separate from these observations.

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
heuristic workers retain witness authority. [Theory contracts](17-theories.md) define
the completion routes and their invocation lifetimes.

MPS finite invocations complete continuous columns through numerical residual LP
completion, and the configured source tolerance callback reaches every worker kind.
Exact MPS invocations supply no tolerance callback, so a floating completion cannot
acquire exact authority through sharing.

Companions publish witnesses only, rechecking their complete coordinates against
the captured affine source before publication. Joint repair can center a fresh
proposal on a checked pooled incumbent; pending proposals keep their scope until
completion.

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

Each appended worker keeps four seconds of active search by default. The opt-in
`companion-active-ms` sets a positive lifetime of at most 60,000 milliseconds inside
the unchanged containing solve deadline. `companion-after-incumbent=true` defers both
companions' queries until the pool accepts a source incumbent. Factory construction
still belongs to the invocation. These controls change neither fragment admission
nor the finite recipes, coverage floors, work/allocation ceilings or reseeding rules.
Numerical segments
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
When exact incumbent completion supplies an original-source callback, repair also
checks each completed point's exact original feasibility and adjusted objective
before publication. This check shares the completion funding and publication lifetime;
working-model or tolerance acceptance alone cannot admit the point.

Opt-in `source-incumbent-completion=true` reconstructs advisory real coordinates
before residual LP completion. Reconstructed advisory seeds require exact working
and original-source feasibility plus adjusted objective agreement. Source scope, conditions,
semantics and coordinate frame identify retained attempts; failed attempts cannot
renew their allowance.

Enabled repair's first checked center frees at most two connected integers.
Before its first proposal, a newer exact pooled point can replace the prepared
center and tighten the cutoff. Working-model and original-source checks use the
existing funding. The connected-integer selection rule stays fixed; unchanged
discrete coordinates preserve the pins. Advisory points cannot supply this refresh,
and it performs no additional residual completion. Imports retain their read/use
identity and supply no discovery reward. A paused query retains its condition.
The opt-in `companion-repair=adaptive` requires `source-incumbent-completion=true`
and uses the existing adaptive width sequence after that small start. Fixed repair
is the default. If a small checked-center trial is refuted or its checked improvement
leaves every Boolean and integer coordinate unchanged, continuation covers a larger
proposal when more integer coordinates are available. A continuous-only improvement
still enters ordinary exact publication; it also requests the larger proposal before
the adaptive operator restarts at its smallest size. The discrete comparison shares
the completion funding. That proposal adds at most one integer to the small start;
later proposals retain the adaptive sequence's wider requests.
The proposal still selects connected integers, with the existing thirty-two-integer
and traversal ceilings. A disconnected component can supply fewer coordinates than
requested. Completion or decline of that larger proposal ends the trial; cancellation,
exhaustion and the existing active/work/allocation limits still bound continuation.
`jointRepairMaxFreedIntegers` records actual opened-query width, and
`jointRepairEnlargedTrialStarts`/`jointRepairEnlargedTrialCompletions` distinguish
larger requested trials from completed ones. These counts grant no publication or
proof authority. `jointRepairLargerNeighborhoodCompletions` records completed queries
that released more than two integers; its checked-incumbent counterpart counts only
their accepted point publications.
An available dimension-compatible incumbent requests continuation through root and center
preparation and the first proposal, behind owed complete-worker coverage and within
the existing active cap. Availability grants no import authority; the source checks
still complete advisory coordinates and admit the center. A declined center clears
the preparation request, and a completed or declined trial returns to
ordinary selection.

A numerical optimum or refutation can complete the finite
invocation only after its typed terminal verifies against the same captured working
model and objective; the finite pipeline retains original-source reconstruction and
qualification. Other numerical terminals and incomplete coverage carry no completion
authority. Finite workers' ordinary terminal admission remains available throughout.

Companion statistics distinguish active-time retirement and checked completions.
`jointRepairPublicationChecks`, `jointRepairPublicationNs` and
`jointRepairPublicationPauses` identify completed-point verification effort.

---

Previous: [13. Neighborhoods and restarts](13-neighborhoods.md) · [Contents](README.md) · Next: [15. Portfolio scheduling and workers](15-portfolio-scheduling.md)
