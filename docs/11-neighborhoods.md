# 11. Neighborhoods and restarts

[Local search](10-local-search.md) values a candidate by its effect on the model,
and with definitions in place that effect reaches beyond the coordinates a move
writes. This chapter first describes how a candidate is graded exactly and
reversibly, how factors select their scoring projection and how continuous
arithmetic is scored, then the structured neighborhoods that NValue, Cumulative and
Diffn contribute. It ends with what a walk needs before it publishes and across
slices: continuous completion, exact coordinate neighborhoods, restart anchors,
ALNS seeding and the handles that own a run's state.

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

The walk moves discrete coordinates and scores them heuristically. Before anything
is published, a candidate needs authoritative values for its continuous coordinates,
and a run that lasts across portfolio slices needs state that survives suspension
without leaking into other operations.

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

### Restart anchors

Optimization restart policies receive only accepted feasible incumbents; before the
first one, random restarts diversify the feasibility walk. Anchoring and custom
policies keep the best assignment in private packed storage, updated only on a strict
improvement, and each restart receives a fresh copy so a policy cannot alter the
anchor. Fixed-cadence and Luby policies keep no copy. Anchored perturbations restore
exact pins after their random draws and clear tabu state.

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

Before and during its bootstrap, ALNS checks a supplied assignment and the latest
verified shared incumbent. A candidate must cover all coordinates, satisfy the
current model and assumptions and pass the shared source witness checker; ALNS
re-evaluates it against its own objective. An interrupted check leaves the candidate
eligible for a later slice, adoption closes the bootstrap handles once, and seed
acquisition renews no allowance. Imported samples carry no worker state or proof
authority.

### Handles and exclusive ownership

Local-search optimization handles keep the live assignment, random generator,
factor payloads, restart progress and incumbent between slices. A slice pauses at an
instruction checkpoint or cancellation poll, and the scheduler charges the observed
instruction delta once. Closing or reseeding discards the state. Root refutations
remain authoritative; an unsuccessful walk is incomplete.

An active handle holds exclusive ownership of its solver: any other search through
that solver throws `IllegalStateException` until completion, failure or close; a
cancelled slice stays paused and keeps ownership. Portfolio workers use separate
solvers.

[LocalSearchEngine](../klause/src/commonMain/kotlin/com/eignex/klause/localsearch/LocalSearchEngine.kt)
owns model preparation, exclusive acquisition, strategies and restart policies. Each
handle owns its cursor, terminal verdict and release through
[LocalSearchRun](../klause/src/commonMain/kotlin/com/eignex/klause/localsearch/LocalSearchRun.kt).
`LocalSearchRunControl` combines lifetime and segment cancellation and keeps the
instruction checkpoint; `LocalSearchProgress` owns warm-state capture and charging.
Projections and immutable indexes can be shared; run progress cannot.

### Residual diagnostics

Optional residual reporting records, at each strict cost improvement, the
maintained violation degrees per factor kind and up to eight representative
factors, identified in the lowered local-search model. Workers keep the observation
at the lowest total. A zero residual establishes neither completion nor source
feasibility. The
[CLI](20-cli.md) renders these observations when requested.

---

Previous: [10. Local search](10-local-search.md) · [Contents](README.md) · Next: [12. Portfolios](12-portfolio.md)
