# 10. Local search

Local search changes assignments to reduce constraint violations and improve an
objective. Incremental scores guide moves; maintained definitions keep functional
outputs consistent. Candidates require [source validation](07-presolve.md#source-witness-validation)
and arithmetic completion before publication. Unsuccessful search supplies no
refutation or optimum proof.

This chapter describes strategies, preparation and definitions.
[Neighborhoods and restarts](11-neighborhoods.md) covers scoring probes, structured
moves and continuous completion. Sessions and containing resources follow
[chapter 6](06-sessions.md).

## The walk

A run holds one complete assignment of the working model and repeats one loop:

1. **Generate.** Move sources propose candidate moves for the current assignment.
   A move is a primitive `BoolFlip`, `IntSet` or `RealSet`, or a `Compound` of
   primitives applied as one transition.
2. **Score.** Each candidate is graded by the change it would cause in the
   model's violation, and at feasibility in its objective.
3. **Select.** Tabu, configuration checking and an acceptance rule choose one
   candidate, or none.
4. **Apply.** The chosen move is committed; maintained definitions recompute
   their outputs and every affected factor reconciles its score.
5. **Restart.** When the walk stalls, a restart policy perturbs or replaces the
   assignment.

Each factor contributes an `Invariant`: a maintained constraint with a graded
**violation degree**, zero when satisfied and growing with the distance from
satisfaction. The hard cost is the sum of those degrees, not a count of violated
factors, so tight arithmetic and global constraints give the walk a gradient. A
candidate's `deltaIf*` result must equal the exact degree change the move would
cause; committed moves reconcile cost and violated membership from the exact
post-move degrees. The immutable invariant projection is shared; mutable payloads,
the assignment, weights and random state belong to each `LocalSearchState`.

Unguarded unit-integer `Distinct` maintains multiplicities of exact offset values.
A source move updates every operand reading that coordinate, including aliases;
probes restore the counts and discard zero-count keys. Its excess grade matches
full exact collision grading. Bounded repair proposals choose unused affine values
through source-domain moves and the ordinary move sink's ownership filters.
Atomic source-value swaps use the pair-swap kernel and are scored against every
affected factor; they do not require implicit seeding.
When a plain seeded `AllDifferent` owns the chosen source, repair delegates bounded
swaps to that owner's existing kernel. Reciprocal domains, pins and defined
coordinates still filter both endpoints, and the owner's feasibility is preserved.
Other affine and reified operands retain their complete grading path.

Optimization uses two phases. While the hard cost is positive, the walk fights for
feasibility. At zero cost it descends on the objective, either through the
strategy's own feasibility-preserving moves or, for violation-native strategies, by
treating an `objective ≤ incumbent` ratchet as one more constraint. Every strategy
therefore optimizes on an optimization model; a satisfaction model ends at its first
feasible point.

## Strategies and moves

`SourceDrivenStrategy` collects, scores and filters candidates, then delegates the
choice to acceptance. A recipe combines move sources, scoring, acceptance and
schedule with tabu, configuration checking and optional perturbation.

### Move sources

A `MoveSource` reads the state and pushes candidates into a caller-owned
`MoveSink`; it never mutates the assignment. Sources are deterministic given the
state's random generator, and fill a reused sink without allocating per-move
objects for primitives. Each declares a **phase** and a **pool**:

- The phase says when the driver consults it: only while infeasible, only at
  feasibility, or in either phase.
- The pool says how its moves compete. Noise-eligible moves can be taken by a
  random or noise draw. Score-only moves are coordinated multi-variable escapes,
  which a random draw would turn into destructive perturbations, so they are
  selected only by score.

`MoveSourceCatalog` names the shared sources:

| Source | Moves |
|---|---|
| `violated` | Repair suggestions from sampled violated factors' invariants |
| `structured`, `elected` | Moves that keep a satisfied factor satisfied (for example a sum-preserving pair shift); `elected` samples the elected structural globals |
| `objective` | Steps on objective decision variables in their improving direction; feasible phase only |
| `frontier` | Adjacent-value steps and flips on variables of factors neighbouring a violated factor |
| `argmin` | Jumps of hot-spot variables to their weighted-violation argmin |
| `pair-swap` | Random Boolean or integer pair exchanges for feasible objective descent; score-only |
| `stall-swaps` | Value exchanges between same-shaped integer variables during the feasibility fight; score-only |
| `ejection-chains` | Directed repair chains grown from a violated factor into the factors each step breaks; score-only |
| `flip-propagate` | A Boolean flip plus the literals it forces through the binary implication graph; score-only |
| `clique-swap` | Atomic exchanges within recognised at-most-one cliques; score-only |
| `stall-kick` | One flattened perturbation from a random walk over the variable–factor graph |
| `unused-values` | Opt-in owner moves of a satisfied implicitly seeded plain AllDifferent onto unused values |

The opt-in `extended-*` variants of `violated`, `structured` and `elected` draw
richer moves that cost more to generate and are not always net-positive, such as a
Circuit 2-opt reversal, an AllDifferent 3-cycle or a DP-optimal accepting run for
Regular. Default arms never pay for them. Each
configured source carries a per-pick candidate cap and an enable gate. The reserved
feasible structural draw in strict descent cycles elected globals and retains bounded
[board swap coverage](11-neighborhoods.md#board-swap-coverage) between picks and slices
within the existing resample window. The schedule
axis gates the plateau-escape sources on its stall signal.

Every source closes its candidates through the ordinary move sink, which applies
pin, definition and implicit-owner filters. A compound touching a pin is rejected
whole; parts on defined or foreign-owned coordinates are dropped. The driver then
applies configuration and tabu filters, scoring and acceptance. An optional internal
tally counts offered, pin-rejected, trimmed, admitted and committed compounds for
diagnostic consumers; it draws no random values and changes no candidate.

Value-driven channeling moves coordinate an integer change with sibling indicator
flips and counter-shifts. The driving coordinate is claimed directly; storage for
additional claims is allocated only when a sibling claims another integer.
The first claim wins, preventing conflicting sibling shifts. Indicator flips retain
their final parity when carried through binary integer channels.

Internal objective proposal controls can select term or cone-leaf integer hotspots
independently of adjacent or geometric leaf steps. They require every cone to be
maintained by the live network and its exact term form to match the canonical
objective. Grading and best-feasible ordering retain that canonical objective;
proposal controls confer no source feasibility or proof authority.

`ObjectiveGuidance.Geometric` installs canonical grading with geometric leaf
proposals during search initialization. It reuses the supplied functional cones
and the live invariant network, checking exact canonical term agreement in
resumable preparation batches. Unsupported cones keep canonical execution.
Each pick visits at most eight Boolean/integer leaves through the ordinary sink;
a retained cursor advances before synthesis so expensive or blocked leaves cannot
starve later coordinates. Integer proposals use bounded geometric distances and
domain endpoints, with checked word-range arithmetic. The cursor survives slices
and assignment restarts. A fresh search rechecks the objective and network and
discards the old cursor; model replacement requires its own solver state.
Preparation, candidate synthesis, scoring probes and closure spend the containing
active deadline. Move/restart allowances retain their existing units and do not
count candidates as moves. `geometricProposalRuns`, `geometricProposalDeclines`
and `geometricProposalCandidates` distinguish qualification from sink offers;
offers are neither distinct source points nor accepted transitions.

### Scoring

The scoring axis values a candidate, lower being better:

- **Weighted** sums each affected factor's degree change times its learned weight.
  The weights form the gradient constraint-based local search uses to learn which
  constraints resist repair.
- **Raw** uses the unweighted degree change.
- **Break** counts the satisfied factors a move would break, the focused signal of
  the WalkSAT and probSAT family, with the shaped objective folded in.

`CostShaping` decides how the objective enters the score. The default
`FeasibilityFirst` ignores the objective until the hard cost reaches zero. Linear
shaping mixes a scaled objective in from the start so descent can pass through
slightly infeasible neighbours, and saturating shaping caps the violation
contribution. Shaping steers descent only; best-feasible tracking and publication
are unchanged.

### Acceptance

The acceptance rule chooses from the noise-eligible and score-only pools:

| Rule | Choice |
|---|---|
| `Greedy` | Lowest score over both pools, ties broken uniformly |
| `GreedyDescent` | As greedy, but only a strictly improving move; otherwise none, so the engine restarts |
| `WalkSatNoise` | With probability *noise* a uniform noise-pool move, otherwise greedy |
| `ProbSat` | Roulette over the noise pool weighted by `(eps + score + shift)^(-cb)` |
| `Skew` | Greedy on `score + alpha·moveSize`, admitting small worsening moves |
| `Metropolis` | First noise-pool candidate passing the Metropolis test at the schedule's temperature |

Stochastic rules draw only from the noise pool and fall back to the deterministic
best of the score-only pool. A strategy also declares how it conducts the feasible
phase: `SelfOwned` strategies descend through their own picks, while
`RatchetAsConstraint` strategies are violation-native and receive the objective
ratchet described in the [overview](#the-walk).

### Schedules

The schedule axis bundles the tempo policies, all driven from one per-round
feedback snapshot:

- **Temperature** for Metropolis acceptance, with cooling, reheating and
  combinators.
- **Violation weights.** When the walk stalls, `WeightSchedule` bumps every
  violated factor's weight and optionally relaxes all weights toward their seeded
  baseline. CBLS escalates and then smooths with a probability; Feasibility Jump
  decays and then escalates on every bump.
- **Noise**, steered by a controller that raises it on stalls and lowers it on
  improvement, or by a bandit over bump-on-stall profiles.
- **Restart cadence**: fixed, Luby, stagnation-driven, anchored perturbation of the
  best assignment, or iterated local search over a population of feasible basin anchors.
  Learned weights survive restarts; the assignment, tabu clock and
  configuration-checking flags reset.

### Tabu and configuration checking

`TabuFilter` removes moves whose variable changed within the tenure; a compound is
tabu if any part is. When every candidate is tabu, the default aspiration drops the
filter to keep the walk live. The improving aspiration also admits a tabu move that
lowers violations, or at feasibility one that strictly improves the objective
without breaking anything. Tenure can be constant, drawn from a random band, or grow
with the step count. The tabu clock advances once per committed move that changes a
coordinate; compound parts share that value, and scoring probes consume no tenure.

Configuration checking restricts candidates to variables whose neighbourhood
changed since their last flip, falling back to the full pool when every candidate
is blocked.

### Named recipes

A `LocalSearchRecipe` pairs a strategy for the feasibility fight with an optional
second strategy for the optimize phase, plus switches for per-move invariant
maintenance and implicit seeding on restart. The portfolio wraps one recipe per arm.

| Recipe | Sources | Scoring | Acceptance | Schedule |
|---|---|---|---|---|
| CBLS | Violated repairs; structured and objective moves at feasibility; elected globals; stall-gated frontier and escapes | Weighted | WalkSAT noise, raised while stalled | Weight bump and smoothing, stall signal, targeted kick |
| Feasibility Jump | `argmin` | Weighted | Greedy | Weight decay and bump, random hot-spot kick |
| WalkSAT | One sampled violated factor | Break | WalkSAT noise, optionally adaptive | Optional noise controller |
| probSAT | One sampled violated factor | Break | probSAT roulette | Optional adaptive or bandit break exponent |
| Simulated annealing | One sampled violated factor | Break | Metropolis | Temperature |

CBLS samples its sources, so a pick with no improving move at feasibility is
usually a missed draw rather than a local optimum; it resamples a bounded number
of times before restarting. Axis edits rewrite one axis of a curated recipe
consistently across both phases.

## Preparation and definitions

A run prepares an invariant projection, an initial assignment and a definition
network. Maintained outputs follow their inputs; inverse repairs turn requests on
those outputs into admissible searched-coordinate moves.

### Projections and preparation

The immutable local-search projection is prepared on demand and reused across
draws; mixed-pool arms over the same model share it, including ALNS's inner local
search. Each variable's occurrence index lists each active factor once, so a move
updates every invariant reading the coordinate exactly once. Assignments, random
generators, weights and invariant payloads belong to each live state.

Unmaintained flat Boolean objective folds have a private reader index from leaf
coordinates to priced terms. A primitive flip evaluates only those terms; compound
flips combine parity and visit each affected term once. Cost differences retain exact
integer cancellation before conversion to a score. Preparation resumes in batches
of 256 entries and admits at most one million Boolean coordinates, 250,000 terms
and two million reader entries. A maintained-network probe uses its saved coordinates
to score the actual changed Boolean leaves, then restores the committed state.
Nested, mixed and opaque objective cones use the generic evaluator. The index
supplies heuristic scores;
source witness and objective checks retain admission authority. Maintenance checks
for the linear term view resume in batches of 256 definitions before move scoring.

Invariants substitute a new value into every position that reads the moved
coordinate. Product residuals are computed exactly, with a private 128-bit
temporary on overflow, so an overflowing product cannot satisfy a signed-word
endpoint.

Ground Table invariants share bounded immutable relation postings across factors
using the same relation arrays. A coordinate edit updates distances only for rows
matching its old or new value in each occurrence column. Worker-private distance
buckets maintain the exact minimum and select the two closest repair rows, with
ties ordered by row index. Candidate scoring uses a private count histogram and
visits each affected row once; repeated-coordinate probes retain a row-stamp array.
Compound probes use accepted-edit maintenance and restoration.
Interval tables and relations outside the index budget retain exact row scans.
Each live Table payload holds three integers per row plus bucket heads and counts.

MDD invariants retain forward and backward minimum-Hamming-distance layers. An
accepted coordinate edit propagates from its first and last occurrence until the
layers stabilize beyond all changed positions. A reusable widest-layer scratch
buffer avoids rebuilding the lattice. Cost-only edits keep the distance lattice
and cached path weight; word edits reconcile the weight before exposing the exact
cost gap. A single-position
candidate recombines one layer, while repeated-position candidates recompute the
distance with every occurrence substituted.

Satisfaction slices adapt their cancellation polling to elapsed move cost, with
clock checks inside the polling window. Read-only extensional scoring and repair
scans poll in work batches; interruption discards their candidate scratch without
changing the committed assignment. Reversible compound probes observe interruption
between fully reconciled primitive edits and definition outputs, then restore
their saved coordinates without interruption. Individual primitive probe edits
and restoration remain atomic work.

Retained runs apply extensional integer moves through a private operation cursor.
Table postings and interval scans, MDD distance layers, factor reconciliation and
maintained definition outputs advance between cancellation checks. A suspended
operation owns the assignment exclusively, retains its selected move, and supplies
no candidate or committed-move count until all payloads, degrees and definitions
agree. Closing the run discards its cursor and private state. Other invariant kinds,
allocations and individual definition evaluations remain cooperative atomic work.

Preparation is resumable. A handle keeps private cursors for buffers, assignment
loading, implicit seeding, definition readers, repair graphs, warm weights and
factor payloads, and advances in batches of at most 256 items between cancellation
polls. A suspension draws no extra random values and repeats no completed work.
Partial state stays private until all factors are scored and the initial greedy pass
completes; shared indexes are published only when complete. Preparation consumes
time but no move allowance. Individual allocations and factor visits stay atomic, so
these are cooperative boundaries rather than elapsed-time bounds.
[Portfolio preparation revisits](12-portfolio.md#preparation-revisits)
admit every initial sibling before extra visits.

### Initialization and implicit table moves

`GreedyInit` walks the variables in shuffled order and commits, for each, the value
that minimizes the current cost: a Boolean flip is kept unless it raises the cost,
and an integer tries every value of a domain of at most 16 values or 16 sampled
ones. It is one forward pass that never raises the cost; the aim is a low-violation
start, not feasibility. Tabu and configuration marking are suppressed during it, and
pinned, defined and implicitly owned coordinates are skipped.

Implicit seeding gives elected structural globals a satisfying start. Implicit table
moves select values from a support row intersected with root domains; frozen
coordinates keep their values and an unreachable row produces no partial move.
Tables reading defined integers decline implicit seeding, because a definition could
rewrite them outside the chosen row. Built-in random restarts apply implicit seeding
and the definition sweep before costs are initialized.
Lexicographic seeding preserves its deciding first coordinates when either appears
again in a suffix, so suffix initialization cannot erase the strict ordering.

An owned AllDifferent's swaps and rotations keep its occupied value set. The opt-in
`unused-values` source lets a satisfied owner without optional entries or excepted
values leave it: one owned coordinate takes an unused value of its domain, or an
alternating path of two or three owned coordinates shifts into held values and ends
at an unused one. Pinned, defined and foreign-owned coordinates never take part, a
path is published whole, and the move is scored like any other. The scan is bounded,
so a miss is not exhaustion; a scope whose domains fit the scan and hold no unused
value, such as a small closed permutation, draws no random value and leaves the
trajectory unchanged.

### Source definition materialization

Definitions come from two places. A source can supply neutral `DefinitionHint`
relations, as FlatZinc and XCSP3 do through `SourceSetup`, and the consumer can
infer native definitions from its working factors. `DefinitionalSweep.fromSource` reads the
hints from the unchanged source and builds the dependency graph with separate
integer and Boolean namespaces. Without hints, absolute-value, product, power,
quotient and remainder outputs are still maintained.

`PortfolioBuilder` requests the view only after composing the actual local-search or
ALNS configurations, so a complete-only pool never builds it. Each worker constructs
its own invariant network. Original coordinates map through the retained source plan
and [presolve hint mapping](07-presolve.md#guarantees-and-source-mappings), which
admits exact alias and affine reconstruction where supported. Construction spends the
containing allowance, cancellation discards the whole view, and a declined view
keeps ordinary heuristic execution.

When no move sweep survives, a functional, self-owned optimization recipe over a
WCNF source can use the complete Boolean cost sweep captured with its objective.
Every definition must be a priced flat Boolean fold over free leaves, and every priced term must have
one unique definition. Capture admits at most one million Boolean coordinates,
250,000 terms and two million literal readers, charging visits to preparation.
The worker builds its network through ordinary resumable initialization; source
validation and the canonical objective still admit incumbents. Existing move
sweeps, other source formats, canonical or geometric guidance, ratcheted recipes
and recipes without per-move invariants retain their selected definition view.

Competing, cyclic and cycle-dependent outputs remain searched. Source bounds decide
when signed-word kernels are safe; wider intermediates use exact evaluation.
`functionalObjective(LinearObjective)` builds an optional gradient view from the
materialized cones that agrees with the canonical objective at every feasible
assignment. Source factors stay enforced, and hints grant no feasibility or proof
authority.
Native Element cones evaluate their selected constant or variable cell. An invalid
index retains the stored result, matching the maintenance node's no-write behavior.
Live-network scoring reads the canonical terms after the complete maintained probe,
including output-domain clamping.

Experimental channel views can leave admitted Boolean-value, Element-value or
predicate outputs searched while retaining the other nodes in dependency order.
Removing a definition adds no inverse and relaxes no source relation. An inconsistent
searched output has a maintained counterpart only when the complete source assignment
survives reconstruction unchanged.
Native inverse-assignment role views retain both source arrays, their domains and
index offsets, and transpose the sides read by the existing repair and swap kernel.
Aliased arrays and competing native channels decline this view.

### Definitions and repairs

A maintained definition is a one-way node: when its inputs move, the network
recomputes the output in topological order. Defined outputs are not searched, and
independent constraints on a defined output still contribute violations. Requests
to change a maintained output become inverse repairs that change admissible searched
inputs; every target must lie in its root domain and pass pin, frozen and
implicit-owner filters, and the complete move is graded before acceptance.

| Definition | Inverse repair |
|---|---|
| Absolute value | Magnitude or either operand sign |
| Integer power | Every admissible exact signed root |
| Min/max | Affine input repairs and bounds on non-witness operands |
| Unit Boolean fold | Signed source through nested folds |
| DivMod | Correlated quotient/remainder, reconstructed dividend or exact divisor repair |
| Element | Selected cell or matching index, with bounded alternatives |
| Affine | One/two-input inverse with unit output coefficient; single repairs before bounded joint moves |

Nested repairs follow at most 16 definitions. Overflow, nonintegral inverses and
unsupported definitions decline the candidate without stopping the scan. Each state
owns its traversal workspace and marks each affected node once. It can reuse its last
single-coordinate cone when the network has no selection-dependent Element readers.
Definition evaluations are cached per state: coordinate writes invalidate direct
readers and mark the written coordinate's producer for reconciliation, including on
probe rollback. Propagation visits pending members of the full affected cone in
topological order, scanning the shorter of its node list and packed word span.
Evaluation caches start on the first affected move. Writes can enqueue later nodes;
blocked outputs remain pending for a later visit with live pin and implicit-owner
checks. An unchanged intermediate does not prevent repair of an inconsistent
descendant. Recomputation after external assignment writes clears every evaluation,
and network replacement discards both caches. Reentrant use throws before mutating
the workspace.

`LocalSearchState.inspectMove` exposes the ordinary reversible probe for explicit
diagnostic consumers. It captures the candidate assignment, maintained outputs,
factor grades and objective delta, then restores the committed state and activity.
It consumes no random values; assignment and degree copies are charged to the
caller. Inspection supplies neither source validation nor proof authority.

Boolean-only clause models close native proposals under clause implications before
ranking. Pending falsifications of a longer clause share one queued visit; newly
forced literals can enqueue it again. Forced literals and mandatory clause visits
propagate to a fixpoint, then a newly broken longer clause
gets a replacement supporter ranked by break score, objective delta and literal
order. Conflicts reject the whole proposal. These closures are heuristic proposals,
not deductions; every clause and pin is kept for full grading.

Retained Boolean clause walks with definition repairs charge candidate-factor,
objective and implication inspections to
their [slice allowance](06-sessions.md#decisions-moves-and-slices). A completed choice
can pause the walk before its native instruction grant ends, retaining the assignment
and paying any atomic overspend from later slices. Optional phase diagnostics include
the cumulative inspection visits and their Boolean repair-graph subset. Both
operation kinds retain fractional charges across slices under their separate prices.

### Maintained literal channels

A literal channel links a Boolean indicator to a unary predicate over an integer,
such as `b ⇔ x = 3`. Inference derives one only from a unique unit equality,
disequality or ordered comparison whose other Boolean occurrences are channels.
Mixed integer and Boolean definitions are ordered together; competing or cyclic
outputs stay searched, and diversification kicks never flip maintained outputs.

Repairs into a channel change an admissible Boolean input or backsolve the predicate
into a searched integer, rounding ordered inverses toward the requested truth
through domain holes. Clause, cardinality and weighted Boolean-row repairs backsolve
through integer inputs the same way. Pinned and implicitly owned outputs are not
rewritten; their definition factors keep any resulting violation.

### Coordinated channels and tuple moves

Value-driven moves carry equality indicator flips through connected unit equality
channels, so a single `IntSet` moves its indicators with it; repeated flips are read
by their final parity.

Violated Table rows keep single-coordinate repairs and add at most two joint tuple
repairs. Tuple moves protect every table coordinate from counter-shifts, so a
protected changed coordinate rejects the whole move and the chosen row stays
supported. Satisfied linear equalities counter-shift the first admissible partner
whose coefficient divides the driving drift.

---

Previous: [9. Learning and theory completion](09-learning.md) · [Contents](README.md) · Next: [11. Neighborhoods and restarts](11-neighborhoods.md)
