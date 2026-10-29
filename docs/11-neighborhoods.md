# 11. Neighborhoods and restarts

A local move can affect definition outputs and several factors. Scoring must grade
that whole transition and restore every probe. This chapter describes those checks,
structured global moves, arithmetic completion and the retained run owner.

## Scoring and probe restoration

A primitive move without definition readers is scored directly from its
invariants' `deltaIf*` results. Compounds without definition readers sum their
parts' linear objective contributions for distinct coordinates; repeated-coordinate
linear deltas use a probe of the final values.

Moves that feed definitions score the complete propagated assignment through a
reversible **probe**. A probe applies all compound inputs together, runs one
definition pass over the affected cone, reads hard cost and objective, then
restores the saved inputs and outputs directly. Feasible grading reads hard cost and
objective from the same probe, rejecting a hard violation before valuing the
objective. Probes record no activity or best-cost observations, and weighted probes
rescan only factors whose degrees change, in the order a full scan would use.
Cancellation between reconciled primitive edits or definition outputs restores the
saved coordinates before returning. A primitive probe edit and its restoration
remain atomic; read-only extensional scores poll within their scans.

A functional objective whose every cone definition is a node of the live network
scores as the linear form of its terms: the probe has already propagated the cone, so
its delta comes from the probed and restored term values. A gradient view built from
other definitions evaluates its cones after the probe against the committed value,
which the state evaluates once per assignment change.

`ArgminJump` builds coordinated integer targets and maintained-Boolean inverse
repairs through the generic move sink before comparing their complete weighted
deltas. Each sampled coordinate contributes at most one strict improvement over
staying put. Small domains are enumerated; wider ones keep a sampled-target cap.

### Linear repairs

Linear repairs offer nearest-clamped targets in term order. Inequality repairs add
targets rounded through domain holes toward the requested truth, and reified repairs
keep body targets consistent with the retained or flipped indicator.

### Comparison clauses

Comparison-clause scores substitute a target into every matching literal and keep
the minimum saturated shortfall. Violated clauses offer admissible literal bounds as
well as neighbouring values, rounding toward the satisfying side of domain holes.

## Selected scoring projections

`ScoringProjectionProvider` selects a factor's invariant factory, scoring
participation and assignment-check authority from immutable factor data. The
factory receives the worker's move domains; built-in linear rows keep exact sums
when those domains can exceed the machine-integer range, and use compact signed-word
scoring when activity and deltas fit. A custom factory must preserve the source
relation at zero violation and supply consistent deltas for its advertised role.

An explicit inert score contributes no occurrences and needs an explicit reason; it
cannot claim an exact check, so an enforcing peer or separate source certification
must cover the factor. Unsupported scoring refuses construction, and returning
`NoInvariant` for a declared active role is rejected.

## Continuous arithmetic scores

Mixed `ExactLinear` rows scale their exact constants into a floating heuristic.
Each state owns a balanced tree of coefficient-value products. Single-coordinate
proposals replace one leaf and reduce its path without writing the tree; committed
moves and probe rollback rebuild that same path. Reinitialization rebuilds all
leaves after restarts or external writes. The fixed reduction order bounds work
to one coefficient and a logarithmic number of additions per proposal and avoids
history-dependent drift, so no periodic drift correction is needed by this score.
Its rounding can differ from a sequential row sum. Strict boundaries, reification
and integer indicators use the resulting activity; nonfinite enabled activities
remain violated. These degrees grant no source acceptance authority.

`ContinuousProduct`, real `Power`, `RealProduct` and `RealAbs` grade floating
assignments with native invariants while retaining their exact source relations.
Scores substitute every aliased role together, repairs respect declared real bounds
and keep both inverse signs, and nonfinite arithmetic never scores zero.
Finite-choice products also grade operand membership and guard truth and propose
the nearest declared operand. These scores are heuristic: zero violation requires
exact completion of the whole source relation in
[Completion, restarts and ownership](#continuous-candidate-completion).

`GridProduct` and `GridPower` instead grade their declared rounded index relation
with integer deltas, respecting aliases, domain holes and channel ownership. Exact
grid membership supplies no continuous-source witness; approximation metadata and
whole-source validation remain required.

## Board swap coverage

After a strict feasible descent's sampled pick misses, the reserved structural draw
cycles through every elected global from a random start. A selected AllDifferent board
retains its pair progress; other elected globals keep their ordinary structured proposals.
Each board starts at a random unordered pair and visits cyclic pair ranks
without repetition, using a random coprime stride to spread partial passes across ranks.
A pick keeps the ordinary limit of four proposals and 24
visits. Both inputs pass pin, definition, domain and proposing-owner filters before
the complete compound enters ordinary generated-move scoring and acceptance.

The live state retains its board and pair cursor across picks and suspended slices.
A cancelled selection restores ungraded pair progress. Assignment changes,
restarts, definition changes and ownership reseeding invalidate coverage.

Coverage uses the existing resample window and never delays its restart boundary.
Strict descent with a zero resample cap starts coverage directly.
An incomplete pass reports a feasible stall rather than a board local optimum.
Configuration filtering, non-improving tabu aspiration and partial continuous
completion also withhold that classification when they can hide an improvement.
ILS applies the same anchor acceptance rule to feasible stalls and completed
descents. Neither notification grants global proof authority.

## NValue neighborhoods

A satisfied NValue factor offers count-preserving coordinate moves and bounded
**value-class elimination**: remove one distinct value by moving every coordinate
holding it to surviving values its root domain admits, and reconcile the count for
the factor's mode. Frozen, maintained or foreign-owned coordinates and moves that
would change presence literals decline the whole class. Count-decreasing repairs use
the same operator when NValue is the only violation. Each call offers at most four
eliminations of at most 32 coordinates. An elimination usually breaks surrounding
constraints, so sampled eliminations are reconciled on a scratch assignment by
bounded repairs of clauses, parity, Element cells and channels, and only a complete
zero-violation proposal is offered.

## Cumulative neighborhoods

An integer move updates every task reading the coordinate as a start, duration or
demand, including aliases and coordinates shared with capacity, and deltas use the
final combined load rather than summing independent scores. Overload start swaps
filter on the joint profile change of both coordinates, applying and restoring only
the affected footprints; at most four are offered, around the peak task and its
partners.

Overload repair can also remove a conditional duration through its binary channel
or swap duration choices between task pairs, with per-worker cursors rotating the
starting task so early candidates cannot consume every call's budget. During
infeasibility, ranking adds a fraction below one grade unit per overloaded factor,
so moves that shrink an overload rank ahead at equal integer cost, and the weight
schedule treats a lower fraction as progress.

A satisfied resource offers compaction and reorder moves for tasks of any duration.
Its latest-ending task and one sampled task move to the earliest start that every
Cumulative sharing the start coordinate admits, read from each timeline without the
task's own load. When a demand or presence is a defined channel of `x = v`, up to
three other values of `x` are tried with the start, so a task can change machine
and start in one move. The sampled task also swaps order with its successor, and up
to 32 tasks are left-justified in start order as one compound, each placed on the
timelines its predecessors already moved on. Each compound is replayed on every
timeline it reaches and kept only when none gains overload. Moves are declined when
their definitions recompute another scheduling coordinate or the start is shared
with a Diffn. Single moves and the block each stop after 32,768 timeline reads, and
a call offers at most four moves. An admitted move can reconcile up to two scalar
envelopes outside the resource, such as a makespan bound fed by task ends.

## Diffn neighborhoods

A satisfied Diffn layout offers up to four adjacency, boundary-compaction and paired
reorder or translation moves, alongside equal-footprint swaps. Relocations sample at
most eight rectangles and check at most 24 targets, reading aliased positions and
sizes at their final values and declining moves that affect more than eight
rectangles. Admitted relocations reconcile scalar envelopes as Cumulative moves do.
Geometry checks establish only the Diffn's own satisfaction; complete scoring covers
the rest.

## Completion, restarts and ownership

A candidate needs authoritative continuous values before publication. Completion and
restart state belong to the retained run.

### Continuous candidate completion

Finite moves change discrete coordinates; continuous coordinates are completed for
each finite candidate rather than searched. A finite projection uses
`CandidateCompletion` when the source requires arithmetic coordinates, even if its
assignment has no continuous buffer. The completion service checks the pinned
finite candidate against those constraints; a rejected or indeterminate candidate
cannot refute the source model. Completed samples carry authoritative coordinates
and source scope.

When all declarations are continuous, the first candidate requests completion
regardless of its floating score, since an exact rational solution need not have a
zero-violation floating representation. From an incumbent with a continuous
objective term, a pick admits at most two completion probes, and a finite move and
its completed reals commit as one transition. Probes are rate-limited by the
instructions they charge, and completion consumes the same move allowance.

Numerical completion uses the source tolerance callback when one is supplied;
otherwise it needs exact certification. Exact completion uses bounded point recovery
and sparse row repair, theory-backed linear completion keeps a worker-private
conditioned query, and polynomial completion must accept the unchanged finite point.
A completed witness is heuristic incumbent evidence, never an optimum proof.

[Theory contracts](14-theories.md) define the supported graph, linear and polynomial
checks. A propagation-only custom factor receives a private
[full-coordinate finite check](08-search.md#custom-factor-publication-checks), and an
uncovered continuous constraint declines finite local-search execution.

### Exact coordinate neighborhoods

Models whose coordinates are all decided by an exact theory use a different move
owner. The retained exact-coordinate driver proposes rational coordinate windows and
Boolean pins to a source-bound completion adapter. A coordinate repair relaxes one
pin; compound repairs relax a block, and adaptive neighborhood search weights block
sizes by accepted improvements. Centers, radii, random state, weights and incumbents
survive slices. A rejected repair widens the windows without changing the source
domain or establishing infeasibility.

The linear adapter asserts exact rational bounds and strict objective cutoffs into
a private retained theory search and retracts them after every verdict, so sibling
conditions inherit nothing. Native work shares one allowance across repairs.

The quadratic adapter uses the same contract with exact algebraic objective ordering,
repairing finite assignments and continuous windows together
([quadratic strategy execution](16-nonlinear-arithmetic.md#quadratic-strategy-execution)).
Transcendental sources use the same move owner with original-source function
completion ([transcendental real arithmetic](16-nonlinear-arithmetic.md#transcendental-real-arithmetic)). In every case
only checked completions publish incumbents, and repair refutations or exhausted
windows carry no proof authority.

### Joint linear repair

Explicit numerical linear local search and ALNS use `JointLinearRepair` with the
same retained neighborhood driver. A source LP point guides the initial integer
rounding and orders fractional seed coordinates. Later proposals follow source
row connectivity, including integer indicator triggers and paths through real
columns, to free at most 32 related integers. Each walk visits at most 8,192
adjacency entries; its index admits at most
1,048,576 coordinates, rows and coefficient entries in total. Other integers stay
pinned to the current center, while the continuous residual remains free under
every source row.

Explicit diagnostic callers can identify original integer declarations separately
from integer backings of original real columns. Backings participate in connectivity
as bridges, remain free in every residual and consume no discrete seed or freedom
quota. Ordinary callers retain canonical integer eligibility.

With supplied source roles, the diagnostic source-input pass tries a narrow
neighborhood for each eligible seed after a checked cutoff changes, before wider
nonadaptive proposals. Pending candidates and cumulative funding survive each
slice. Adaptive proposals keep their selected widths; the pass supplies no coverage
or proof authority. Production callers omit source roles and retain their ordinary
proposal schedule.

Each candidate runs the shared numerical traversal with a borrowed source LP
authority. Candidate coverage and compatible LP state survive slice boundaries;
changed pins retire candidate coverage before opening another query. Search and LP
allowances span candidates, and the driver charges native work as heuristic
instructions, including sparse preparation and bound certification. Root and
candidate consultations advance bounded quanta under the invocation's cancellation
token; a paused consultation retains its admitted operation and check allowance.
A checked center supplies a candidate incumbent; a scoped bound can close a
nonimproving neighborhood before its remaining alternatives are explored.
A completed candidate must pass the original-source witness check
before it can update the center or publish an incumbent. Local bounds, refutations
and exhausted alternatives never leave the heuristic as global claims.

The mixed repair attempt also retains its termination reason, feasible point and
conditioned bound independently of the driver's improvement verdict. A feasible
center can accompany a cutoff closure; a slice pause has no terminal reason, while
lifetime cancellation, budget exhaustion and unavailable completion remain distinct.
These observations grant no proof outside the exact candidate pins and cutoff.

### Restart anchors

The engine passes its accepted best feasible incumbent to optimization restart
policies; before the first one, random restarts diversify the feasibility walk. Anchoring and custom
policies keep the best assignment in private packed storage, updated only on a strict
improvement, and each restart receives a fresh copy so a policy cannot alter the
anchor. Fixed-cadence and Luby policies keep no copy. Anchored perturbations restore
exact pins after their random draws and clear tabu state. ILS crossover also
starts a fresh tabu epoch while preserving cumulative write activity.

ILS also receives zero-score snapshots at feasible stalls and completed descents.
Those notifications classify the working walk and supply heuristic population
anchors; they do not grant original-source witness admission. The engine's checked
best remains a separate restart input. A source-feasible archive must validate its
points against the original source and preserve that separation.

Retained ILS restarts over unconditional AllDifferent boards report searched-input
outcomes after ordinary preparation and the enabled greedy-repair pass. Changed
inputs pass pin, fixed-domain, definition, root-membership and implicit-owner
filters. An unchanged board explicitly declines diversification, distinguishing an
unchanged policy result, preparation erasure, repair erasure and unavailable inputs.
The outcome stays pending across a suspended repair and is omitted for an
interrupted incomplete pass. Each decline consumes the ordinary restart work unit;
the subsequent walk and anchor-adaptation rules retain their existing allowances.
These outcomes describe input changes, not feasibility or arrival in a new basin.
Immutable coordinate and owner indexes are cached per state; eligibility filters
read live assumptions, definitions and ownership. Snapshots contain one value per
distinct board coordinate and are released when
the restart transition completes or closes.

### ALNS incumbent acquisition

ALNS alternates destroying part of an incumbent with repairing it. Adjacency
destruction grows breadth-first through factor occurrences; repair scopes release
both ends of unit Boolean definitions, and caller pins stay in the repair
assumptions.

The internal opt-in `SemanticBlockRemoval` selects complete mandatory
AllDifferent scopes, contiguous windows in a resource's current start order, or
placement contact blocks within two hops. Resource packets include start,
duration, demand, presence and capacity roles; rectangle packets include both
positions and variable sizes. Shared mutable roles expand their packets to a
fixed point. Optional or excepted assignment shapes and non-positive placement
geometry decline explicitly. The default removal palette is unchanged.

Block closure follows the supplied immutable definition network upstream from
selected roles to searched inputs, then downstream from those inputs to affected
outputs. Conservative Element readers retain alternative selections. Downstream
outputs do not release unrelated inputs of a shared expression. Root-fixed integer
roles stay fixed; a required caller pin declines the block. Complement pins use
the existing repair scope, and ordinary CP retains every source constraint.
Selection charges visits to the caller's work meter and caps visits, searched
inputs and working coordinates. Cancellation or a limit returns an explicit
decline without partial freedoms. Selection retains only bounded diagnostics;
it has no resumable cursor and does not renew an interrupted allowance.

Before and during its bootstrap, ALNS checks a supplied assignment and the latest
verified shared incumbent. A candidate must cover all coordinates, satisfy the
current model and assumptions and pass the shared source witness checker; ALNS
re-evaluates it against its own objective. An interrupted check leaves the candidate
eligible for a later slice, adoption closes the bootstrap handles once, and seed
acquisition renews no allowance. Imported samples carry no worker state or proof
authority.

Finite CP conflict-guided removal reads a bounded prefix of the repair's existing
variable-selection conflict attributions. Its scores name coordinates in the same
baked model, retaining the originating complement pins and each attribution's
objective cutoff, including local incumbent tightenings. A hint
can rank a later freedom at the same center; it cannot assert a clause, prune a repair
or establish that changing a supported input admits improvement. A changed model,
objective, caller pins or center, a new repair, cancellation and close retire the snapshot.
Missing callbacks and captured empty supports are distinct outcomes. Both use the
ordinary random removal fallback. Capture admits at most 32 conflicts and 4,096
coordinates; oversized supports decline as a whole.
Attributions identify activity inputs without a premise-complete branch refutation.
Invocations with pooled starting witnesses decline capture because their initial
cutoff tightening need not notify the variable selector.

The pipeline's internal `FiniteNumericalRemoval` consumes scoped numerical
feedback without owning numerical capture or proof traversal. It ranks finite
coordinates by exact disagreement with captured relaxation points or membership
in captured refutation supports, with seeded ties and ordinary random fallback. It admits
an unchanged complete Boolean/integer coordinate frame and exact caller/query
pins; unmapped coordinates and additional deduced restrictions decline. It checks
the normalized objective, original premises, semantic checker and actual cutoff
row before retaining at most 4,096 scores. Missing feedback, missing points,
agreement and empty captured support remain distinct outcomes. Each signal requests
its projection, the point or the refutation, and a delivered query without it is
unrequested rather than agreement or an empty support. Scoring and
selection debit the producer's containing funding; cancellation or exhausted
funding declines selection as a whole. Model, objective, center or caller-pin
changes retire guidance, and close releases its query scope. These rankings
neither assert deductions nor establish that a selected repair can improve.
When supplied, the immutable definition network closes both guided and fallback
selections through the shared block closure. Required caller pins decline the
whole block; closure visits and bounded working storage debit the same funding.

The opt-in `SourceRows` completion turns each supported seed into a packet through
the original source premises. A seed plans one domain step: toward its relaxation
value for disagreement, in each admissible direction for refutation support. Every
active row the plan violates admits compensating inputs, supported inputs first and
then in source order: an input moves just far enough within its root domain, or a
guard or indicator changes and deactivates the row. Caller-pinned and root-fixed
inputs never compensate. Each member records the row that added it. A packet whose
row cannot be restored is unrepairable, a seed without a step is immovable and an
oversized packet is limited; none of these contributes partial freedoms. Selection
takes whole complete packets, those covering more supported seeds first, while they
fit the request, then fills the remaining places by the ordinary ranking. A request
smaller than every complete packet, or guidance without one, declines with no
freedoms rather than falling back to random removal. Completion debits the same
funding and retires with the snapshot. The plan is only a hint: the repair enforces
every row, pin and definition, and completion asserts no deduction.

### Handles and exclusive ownership

Local-search handles retain assignment, RNG, factor payloads, restart progress and
incumbent across slices. The scheduler charges each observed instruction delta once;
closing or reseeding discards the state. A cancelled slice stays paused and keeps
exclusive solver ownership. Other searches throw until completion, failure or close;
portfolio workers use separate solvers. [Sessions](06-sessions.md) define the outer
lifetime contract.

A selected extensional move retains its application cursor across slices. Partial
payload and definition updates stay private until reconciliation finishes. An
unfinished application contributes no move instruction and supplies no publishable
candidate.

[LocalSearchEngine](../klause/src/commonMain/kotlin/com/eignex/klause/localsearch/LocalSearchEngine.kt)
owns model preparation, exclusive acquisition, strategies and restart policies. Each
handle owns its cursor, terminal verdict and release through
[LocalSearchRun](../klause/src/commonMain/kotlin/com/eignex/klause/localsearch/LocalSearchRun.kt).
`LocalSearchRunControl` combines lifetime and segment cancellation and keeps the
instruction checkpoint; `LocalSearchProgress` owns warm-state capture and charging.
Projections and immutable indexes can be shared; run progress cannot.

### Residual diagnostics

An internal bounded repair trace records immutable centers, restart policy output,
prepared and repaired assignments, and the following walk's endpoint. A declared
descent rule and seed accompany each capture. Policy local-optimum announcements,
feasible stalls, interrupted descents and unresolved repairs remain distinct;
none grants global proof authority. Pending restart phases survive slices and close
as censored observations. ALNS records requested and effective scopes through the
same definition closure and caller-pin filtering used to build its repair pins.
Its best objective threshold, exact discrete value and immutable best witness remain
separate from a current center accepted by a non-monotone policy. A cancelled
closure leaves its effective scope explicitly unavailable.
Capture has separate coordinate accounting and consumes no search random draws.
Its default frame limit is 4096 coordinates; an explicit diagnostic allowance can
retain at most 8192 coordinates per complete frame. Oversized frames decline as a whole.

Optional residual reporting records, at each strict cost improvement, the
maintained violation degrees per factor kind and up to eight representative
factors, identified in the lowered local-search model. Workers keep the observation
at the lowest total. A zero residual establishes neither completion nor source
feasibility. The
[CLI](20-cli.md) renders these observations when requested.

---

Previous: [10. Local search](10-local-search.md) · [Contents](README.md) · Next: [12. Portfolios](12-portfolio.md)
