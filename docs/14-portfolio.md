# 14. Portfolios

A portfolio schedules retained workers over one source and objective. Workers share
incumbents and certified deductions; the coordinator owns slice allocation,
publication admission and cleanup. Prepared capabilities decide which arms can run,
while proof scope decides which claims they may make.

When every arm retires undecided, satisfaction and optimization retain the same
stop reason: `Unsupported` takes precedence over `RepresentationDeclined`, followed
by the other recorded worker reasons. An unfinished optimization keeps its verified
incumbent with that reason; an arm decline alone does not imply budget exhaustion.
An admitted terminal or clean exhaustion takes precedence over those stop reasons,
including cancellation observed while the final retained handle closes.

## Invocations and recipes

Finite satisfaction enumeration uses one retained backtrack traversal rather than
independent first-solution workers. The first selected backtrack recipe and its
parameter edits supply the traversal; a pool without backtrack recipes uses the
leading satisfaction recipe. Additional lanes and heuristic workers do not duplicate
the listing. A solution cap or interruption retains the listed witnesses without
an exhausted-search claim.

Finite, linear open, polynomial, nonlinear-integer, quadratic and transcendental
source execution supply retained recipe factories to `PortfolioInvocation` with the
routing plan and current executable projection. The invocation owns worker slots,
seed offsets, parallel quotas, scheduler controls, result verification and cleanup;
mutable services registered with a slot close when the slot is released, and each
replica gets private state. ALNS operator choices and inner repair seeds derive
from its worker seed. Native reservations divide the complete family's allowance
across its slots. In the polynomial, quadratic, nonlinear-integer and
transcendental portfolios, a slot binds the backtrack seed, decision share and node
quota, and the local-search seed, flip and instruction shares and node quota; other
limits keep their own work currencies, and finite and linear open factories bind
their slots directly. Heuristic-only factories carry witness authority, and the
`fixed` engine rejects multiple cores.

`SourceExecutionPolicy` sets finite arm defaults and LP ceilings before worker
construction; explicit `arms`, `ls` or `bt` settings take precedence. Native
arithmetic routes reject finite-only explicit policy instead of ignoring it. Recipe
defaults carry no witness or proof authority.

Named recipe pools retain the selected worker count and repeat their recipes across
the slots. `bt-arm=X` selects the backtrack recipe; `arms=1` with the `cp` engine
selects one worker. An explicit pool with one recipe can therefore have several
private replicas with separate seed offsets.

`objective-guidance=canonical|functional|geometric` selects local-search objective
guidance inside an explicitly selected recipe pool (`arm`, a named `strategy`, or LS
axis edits); it cannot construct an automatic pool. A comma-separated list expands
each selected recipe into fresh slots in that order. Functional guidance is the
default. Canonical guidance keeps linear grading and proposals; geometric guidance
keeps linear grading with qualified, bounded cone-leaf proposals, and an unsupported
cone runs canonically and reports a decline. Reseeding opens a fresh search that
revalidates its live objective, invariant network and conditions. Every variant uses
the same baked model, source identity and solution exchange, and grants no bound,
terminal, pool or warm-start authority beyond the worker's source contract.

### Protected-coordinate view

Default single-core mixed placement optimization over `Diffn` models without
`Element` alternatives can give the curated full local-search slot a private view in
which an eliminated affine objective coordinate is protected. The slot runs the full
plateau recipe while the primary keeps the rewritten model; protected aliases guide
infeasible repairs and the canonical objective orders feasible incumbents. The view
retires at the next scheduling boundary after the first global witness, so canonical
workers handle improvement. It adds no arms or allowances. Custom local-search
recipes, named backtrack pools, explicit arm counts and explicit presolve keep the
selected representation.

The view's source mapping must retain the same original model and an equal discrete
column namespace. It shares no pools, warm starts or scalar cutoffs. Each proposal
is reconstructed, stripped of private certificates, checked against the canonical
prepared model and evaluated with its objective before publication; private
terminal proofs have no source authority. Resumable preparation, instruction
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

Finite recipes, and the [numerical companions](15-portfolio-scheduling.md#numerical-companions) seated beside
them on linear sources, are admitted through `WorkerRequirements`: supported
fragment, objective view, proof authority (checked terminals, conditioned proofs
or witnesses only), publication mapping, private resumable state, and structural
construction dimensions. Dimensions estimate setup size, not elapsed time or
retained bytes. Numerical requirements also carry the per-owner LP work and
allocation ceilings, active-time limit and segment limits; construction and
retained queries share those ceilings. Candidate checks and typed terminal
verification remain the authority boundary.

`SearchEvent.WorkerAdmission` reports these requirements and explicit decline
causes, distinguishing eligible catalog candidates from composition-selected
workers. Curated backtrack filtering reports unmet model needs; selected finite
recipes report their actual worker authority. Explicit named recipes bypass
automatic filtering.

## Witnesses and proof scope

Finite workers' points are engine results and enter the pool as published. A
private [protected-coordinate view](#protected-coordinate-view) solves a differently
presolved model, so its lifted point is checked against the canonical model.
Arithmetic points pass [source validation](08-presolve-reductions.md#source-witness-validation)
where their engine does not enforce the source exactly. Claims need the authority
below.

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

Checks run outside the schedule lock; installation runs under it, so concurrent lanes
report improvements in installation order and never report one a peer already beat.
Because re-deriving an assignment can cost more than finding it, an arm's best
pending candidate waits until checking has taken at most a fifth of the arm's time,
and whatever still waits is checked when the segment ends. The work diagnostic
schedule checks every candidate as it arrives.

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
The incumbent exchange installs only a finite, strictly improving objective. The
low-level portfolio API also supports caller-trusted unbound workers with an explicit
witness checker. A pure local-search pool has no shared pools; learned clauses are
exchanged only when at least two backtrack workers exist, and cuts only when the
scenario opts in.

In curated single-core mixed finite integer optimization without Boolean or
real columns and with a containing deadline, `portfolio-publications=segment`
coalesces private local-search offers within one query: the worker retains one
immutable candidate, ranked by its canonical objective, and offers it at the
query boundary. Failed queries discard the pending offer and expiration before
installation withholds it; no candidate queue or rejected-point history survives
the query. Direct public exchange offers, private-coordinate workers and ALNS
keep their immediate publication paths. Explicit pools, annotations, parallel
execution and the work diagnostic schedule reject the policy; the default is
`immediate`. Query time includes the deferred offer, which the LS publication
phase and incumbent sharing time also report; these overlapping measures must
not be added.

#### Clause pool

The clause pool is an append-only store of session-portable learned nogoods,
deduplicated by content key, which each worker drains through its own cursor. On one
lane it is the memory that survives a worker's handle being rebuilt. A publication
that would exceed the entry cap or the optional byte limit compacts the pool to at
most half, keeping global nogoods ahead of glue clauses in quality order and
preserving insertion order. Compaction starts a new generation: a cursor from an
older generation re-drains the whole pool and the worker's duplicate filter keeps that
drain duplicate-free, so eviction costs a stale worker one import and never a missed
or doubled clause. Evicted keys leave the pool, so a re-derived clause can re-enter.
Sources with no integer variables use a Boolean-only layout that declines atom
payloads.

Each worker's duplicate filter spans its native session, not a pool generation, and
records imported and exported keys, including evicted ones. Reseeding a retained
session keeps the filter with its learned database; resuming the same session keeps
cursor and filter; a fresh session releases both and reimports the current pool.
Glue export probes the filter before copying native literals or building portable
payloads, and only a completed export admits its keys. Cancellation and exhausted
optimization slices export glue through `onSearchEnd`; other cooperative pauses use
`onSearchPause`, which observes retained memory without publishing clauses or
draining scheduler credit. Uses stay pending until a restart, cancellation boundary
or uncancelled close. Retirement records the pool and filter counters and releases
the filter; an uncancelled retirement also drains uses and exports remaining glue,
while a cancelled one skips those scans. A retirement batch that fits the pool's
remaining entry capacity, with no byte limit, can be deduplicated by the pool's own
index under the publication lock instead of growing the worker's filter; the
generation cannot compact during that batch, and existing private keys still
suppress earlier imports and exports.

With `sharing-memory-bytes`, the limit covers each pool's payload and private index
capacity, and each worker's filter has a separate allowance of the same size. The
protected floor is zero: eviction removes optional shares, while imported reasons
and an already drained batch keep their own references. Pressure prepares a
replacement store under work, scratch and cancellation limits and charges
every comparison, probe, copy and allocation before making it; a cancelled or
exhausted preparation leaves the current generation intact, and publication swaps
the prepared store without remapping consumers. A limit too small for a share
declines that publication, and unfinished preparation defers safely; neither
requires a restart. Existing key capacity stays protected with its retained
session, including across reseeding. An import or export the filter cannot fund
is declined and counted separately from duplicates, and a full filter keeps its
keys across generations, so the retained native store never receives repeated
imports. Optional exchanges fund their complete drain suffix or export scan before
copying anything; a declined drain keeps its cursor and a declined export admits
no keys. Bounded work and scratch do not promise a hard pause latency.

`importWork` covers drain visits, translation and native attachment; later fixpoint
work is ordinary propagation work. `uses` drains the origin-use counter once,
sharing that observation with contribution rewards, so it counts consumption once
per reduction window rather than exports or checks. Per-origin counters retain
the admitted publisher's contribution within that same drain.

ALNS bootstrap and repair searches can import an existing portfolio clause pool
when its model and objective identities match. They import peers before their
private repair pool at root and restart boundaries, with independent generation
cursors and one session duplicate filter. A private copy already in the session
keeps its original attribution. Both drains and bounded filter operations charge
the receiving search. An interrupted suffix keeps its cursor, and a later grant
retries it while the filter suppresses the completed prefix. All repair exports
stay in the private pool; permanent scope clauses and direct global nogoods remain
withheld. Missing, unbound or foreign portfolio pools leave private sharing alone.

The adjacency removal choice can also read that matched peer pool to release a
complete small nogood support ([neighborhoods](13-neighborhoods.md#alns-incumbent-acquisition)).
Its optional snapshot, support scan and copies share a 4,096-unit local ceiling,
charged to the receiving execution; at most 512 requested coordinates and 16
literals enter the bounded scratch. Interrupted preparation returns the original
adjacency request without retaining payloads. Missing, empty or ineligible pools
consume no extra random draws; an interrupted reservoir scan can advance the
random stream. Neighborhood choices and changed-request counters retain publisher
origins separately from native clause use, and supply no contribution reward.

An internal single-core observer can copy the adjacency choice's exact drained
snapshot dispositions and canonical supports during that same pass. Its shared
window admits at most four completed eligible inventories and sixteen attempts,
spending at most 4,096 additional receiving work and 64 KiB of modeled metadata.
Incomplete copies retain explicit gaps and cannot establish support frequencies.
Copying can alter containing cancellation and scheduling; absent observation adds
no inventory allocation or random draws. Observation supplies no proof authority.

#### Memory observation

`sharing-memory=true` enables bounded retained-memory samples, independently of
the [work diagnostic schedule](15-portfolio-scheduling.md#reproducible-diagnostics). A sample charges every
store, reference and deduplication visit to one allowance with a scratch-entry cap,
polls cancellation, and never changes deductive state, invokes retirement or
materializes a reason. `PropagationSession.sharingMemory` returns a
`SharingMemorySnapshot` that is `COMPLETE`, `PARTIAL` or `STALE`; only
`completeValues` may be used as a complete sample, and only finished categories
appear in a partial one. Complete describes traversal coverage of the modeled
fields, not a deduplicated total across owners or physical bytes. Each observation
replaces the current fields, so a partial sample never inherits an earlier complete
one. Close records pool and filter counters and marks the sample stale without a
scan. Retained `peak*` values are per-field historical maxima and must never be used
as a total or added across workers; repeated worker observations must not be summed.

Learned-store observations cover native arena or general payload, policy columns,
watch and queue capacity, pending attachments, antecedent arrays and their indexes,
the undo journal and bound/hole histories. Pool payload belongs to the pool and is
counted once by identity; reason arrays are deduplicated by identity within one
observation and can alias learned payload. The immutable source clause arena belongs
to the prepared problem and is shared across workers. Session watch capacity
includes base watches. Modeled bytes exclude object headers, spare collection
capacity and allocator overhead; resident memory needs an external measurement. A
pool cap bounds entries, not cumulative seen keys, learned stores or pinned reasons.
ALNS repair pools are worker-private, skip search-conditioned permanent clauses and
decline direct global nogoods. The finite adapter observes its shared-search learned
store through a read-only callback that its lifecycle owner clears on close.

### Exhaustion and metadata

Optimization exhaustion without an incumbent is an original-source refutation only
when the installed complete worker routes objective cutoffs through the scheduler and
used no finite shared cutoff. The scheduler retains observed cutoffs for proof
admission: a finite cutoff supports only its conditional bound, while unrestricted
exhaustion with a checked incumbent establishes attainment. Heuristic and
improvement-only arms gain no completion authority from exhaustion.

Under an [optimality gap](09-search.md#optimality-gap), backtrack arms on the pooled
model prune against the gap cutoff of the lower of the shared and their own
incumbent. The scheduler credits an exhausted arm with the bound its search reports
having proved, never a cutoff recomputed from what the arm last read from the pool,
which a pending local incumbent may undercut. Shared clauses, cuts, floors and
variable bounds carry what an arm learned after a gap prune, so the scheduler keeps
the least gap floor of any arm in the run and reads every proved bound, the shared
objective bound included, below it. A run also ends when that shared bound closes the
gap of the pooled incumbent. The resulting optimum carries the gap bound: the cutoff
of the final incumbent raised by the proved bounds, or none when they reach the
incumbent. Incomplete arms and unpublished estimates never close the gap.

Finite metadata reports complete search only when a backtrack-family worker permits
source proofs and is neither auxiliary nor improvement-only; this metadata never
establishes a verdict.

### Theory-backed workers

Workers that need arithmetic completion share the same source scope and require that
check before publishing a witness. A neighborhood refutation or failed candidate
check cannot establish global infeasibility. Complete workers retain proof authority;
heuristic workers retain witness authority. [Theory contracts](17-theories.md) define
the completion routes and their invocation lifetimes.

MPS finite invocations complete continuous columns through numerical residual LP
completion, and the configured source tolerance callback reaches every worker kind.
Exact MPS invocations supply no tolerance callback, so a floating completion cannot
acquire exact authority through sharing.

---

Previous: [13. Neighborhoods and restarts](13-neighborhoods.md) · [Contents](README.md) · Next: [15. Portfolio scheduling and workers](15-portfolio-scheduling.md)
