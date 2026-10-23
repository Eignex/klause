# 7. Presolve and validation

With a scope and an allowance in place, the first thing a solve does is prepare
the model. Presolve reduces it before search while retaining the information
needed to interpret results in the original source. It can fix variables,
substitute expressions, remove redundant constraints or strengthen bounds. The
required preservation guarantee depends on the operation: satisfiability,
optimization and counting need different contracts.

Preparation applies only reductions justified for that query, records
reconstruction and objective mappings, and charges its work to the preparation
phase of the [resource allowance](06-sessions.md#resource-allowances). This
chapter gives the shape of the pipeline, the guarantees each reduction must
declare, and how rebuilt models and phase accounting are owned. It closes with
source witness validation, the check every engine's assignments must pass. The
search that runs on the prepared model is the subject of the
[next chapter](08-search.md).

## Pipeline

Presolve runs in phases over one model. A source phase works on the declared model
before the root bake and catches cheap refutations, such as a gcd-indivisible
equality, before propagation would narrow a wide domain one step per round. The
root bake then establishes the propagation fixpoint of the root model; optional
failed-literal and singleton arc consistency tiers extend it from the presolve lane,
so the engine never depends on presolve. Finite rounds follow under one
bounded-fixpoint scheduler shared with the source lane: a pass above the cheapest
tier reruns only after other passes changed at least 1% of the model and met its
observed cost/yield threshold. Charged analysis and rebuild work, divided by the
pass's entry weight and retained yield, estimates the intervening changes needed
before another analysis. Yield is the largest of net occurrences removed, factors
removed, reported columns narrowed or columns fixed, with a floor of one. Distinct
new Boolean unit pins count as fixed columns; existing units and finite root fixings
do not. Empty and interrupted calls retain their cost; only completed misses can
park a pass. These signals are deterministic spending heuristics, not predictions or proofs of search
savings. Unmetered calls use the 1% change threshold without adding accounting
scans. Cancellation and phase allowances still bound optional work. Presolve and
an LP-relaxation harvest iterate to a joint fixpoint for at most four rounds, the
harvest rerunning only when it tightened something. Automatic harvest skips clausal
satisfaction unless rewrites expose cardinalities with stronger LP rows;
propagator-only symmetry does not change that eligibility. Explicit `+lp-harvest`,
nonclausal sources and optimization keep harvest eligibility.
[Wide-integer pre-bake bounds](#pre-bake-lp-bounds) use a separate safety gate.

The round-boundary diminishing-return check counts live factors plus integer-domain
endpoint spans. Incremental and rebuilt hosts use the same nonnegative measure,
saturating spans and totals at `Long.MAX_VALUE`; saturation can hide reductions.
The default abort fraction is 0.001. This structural heuristic supplies no runtime
guarantee.

| Family | Passes |
|---|---|
| Linear | Coefficient strengthening, Diophantine tightening, bound fusion, affine elimination, sub-sum aggregation, row sparsification, dependent equalities, singleton projection |
| Structural | Redundancy and subsumption, structural reduction, comparison-clause folding, duplicate-column merging, dual fixing |
| Boolean | Original-clause subsumption, implication graph, bounded variable elimination, blocked clause elimination, at-most-one clique merging, XOR unit derivation |
| Symmetry | Symmetry breaking and value precedence |
| Probing | Failed literals, integer bounds and holes, open ranges |
| Around the rounds | Binary column substitution, LP harvest, difference-system posting |

`PresolveEmphasis` selects elective cost tiers: `off` disables them, `conservative`
applies the cheap tier once, `default` iterates cheap and moderate tiers to a
fixpoint and `aggressive` adds exhaustive probing, all within 16 rounds. Passes can
be enabled or disabled by name, but a pass that would break the query's required guarantee stays
disabled. Explicitly enabling a problem pass under `off` gives both schedules the
default round cap; an explicit zero round cap still suppresses those schedules.

## Guarantees and source mappings

`PresolvePass.guarantees` separates equisatisfiability, objective preservation,
witness reconstruction, bijection and complete coverage by reconstructed source
assignments. Objective preservation depends on the pass context protecting objective
columns. A satisfiability reduction can keep an optimum while losing source
multiplicity, so successful reconstruction does not establish a bijection.

Affine elimination carries `AffineValue` records through source and finite presolve,
evaluated with exact integer arithmetic and replayed in reverse elimination order.
Finite Boolean elimination, blocked-clause repair, implication merging and singleton
projection retain the same typed rebuild records as their source forms. Binary
substitution records each integer's carrying literal and the source Boolean namespace
size; reconstruction restores later Boolean reductions before reading those literals,
then truncates appended Booleans. Composition preserves that order and the earliest
source namespace.
`SourceMapping` binds a phase and its reconstruction to the actual source and reduced
model objects; composition requires the preceding target to be the following source,
and equal variable or factor counts do not establish that association. A
reconstructed column is excluded from direct definition hints, with two exact
exceptions: a column rebuilt as an alias of a retained column is renamed onto it, and
a linear definition substitutes a single affine reconstruction over retained columns,
passing a rebuilt output to the reconstruction's single partner. Ordered search hints
can follow integer aliases of the form `x = y + c` for translation-invariant variable
selectors with minimum or maximum values; bound-based selectors and other value
choices require direct coordinates. Untouched columns keep their source orientation
and ordered branching hints. A custom
reconstruction without direct correspondence withholds hint projection.

Objective adjustments map reduced values into source units as `scale * value +
offset`, with positive exact rational scale, and compose by multiplying scales.
Current reductions protect objective columns and use the identity adjustment.
Independent source evaluation checks the composed adjustment against reconstructed
witnesses. A mapping never issues a proof certificate. Optional
`SourceMapping.captureSolve`, `captureMinimize` and `justification` retain reduced
evidence and typed obligations for
[original-source derivations](13-evidence.md); a transformed terminal cannot gain
original admission from a reduced label, and original-source witness checking
remains required.

Enumeration, counting and sampling require a bijection even when a pass is
explicitly enabled, so multiplicity-changing reductions stay off for them. Projected
enumeration deduplicates the requested coordinates; hash cells stop after `cap + 1`
distinct projections. An exhausted cell supports an exact count or uniform draw; an
interrupted cell supplies only a lower bound.

`SourceSetup.execute` runs preparation-only requests. Finite requests return a
`PreparedOnly` envelope; open linear requests return
`SourceExecutionResult.Prepared` with the original model and native preparation.
These results carry diagnostics only, with no search publication or terminal.

## Rebuild ownership

For a selected private float grid, finite presolve reconstructs into the private grid
model first, then the invocation's explicit grid map lifts to original real
coordinates before source checking. Source and engine plans keep separate
namespaces, and a foreign source, target or consumer cannot reuse the map; see
[private float projections](02-model.md#private-float-projections).

[PresolveShared.rebuildProblem](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveShared.kt)
and [RootBaker.reseed](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/RootBaker.kt)
carry the originating cancellation token and immutable `ProblemSettings` into every
fresh `BakedProblem`, so deadlines, meters and the pre-bake span gate keep their
scope across rebuilds and reseeds. A fired token skips root-probe phases and stops
propagation at its polling boundaries; partial propagation is sound but can forgo
deductions. Cancellation is cooperative, and probe caps do not bound wall time.
Incremental finite sessions expose their established domains, captured Boolean pins
and root conflicts through a trusted root view, without another propagation bake.
Every nonempty delta invalidates that view's captured deductions. Final materialization uses
the same path unless optional probing requires a fresh bake over the live factors;
an established conflict needs no probing.
Incremental interior exclusions visit current members, skipping numeric gaps without
materializing a value array. Column visits and each 64-member stride charge and poll
the phase token. If installation stops, a reseed from the compact complete domain
delta preserves restrictions that a factor rewrite may depend on; completed atom
mutations and conflicts remain authoritative.
Finite probing and implication harvesting retain the pass token, including its
native-work meter. Value precedence uses that token throughout value-orbit
discovery and stops posting chains when it fires.

## Phase allowance and telemetry

[PresolveBudget](../klause/src/commonMain/kotlin/com/eignex/klause/presolve/PresolveBudget.kt)
owns one deterministic phase allowance across routing, passes, probes and rebuilds.
The CLI also bounds optional work by an elapsed ceiling; `presolve-work-limit=unlimited`
removes only the work cap. Host speed can therefore affect which reductions finish.
[Resource configuration](06-sessions.md#configuration-and-lifetime) owns these controls.

Root construction reports a size proxy over columns, factors and structural keys;
propagation reports factor fires, literal reads and wakeups, including SAT BCP.
Each entry or propagation unit costs 53 phase units, charged once through native
meters and flushed before polling. Mandatory construction remains charged under a
zero allowance. Optional probes and tiers stop at the next polling boundary; a
completed failed probe keeps its deduction, while an interrupted hypothetical
branch contributes none. Atomic work can overrun the allowance.

### Observations

The effort snapshot retains work, allowance, rounds, pass calls and root work even
when preparation changes nothing or stops. Source preparation excludes theory search;
polynomial encoder rebuilding is preparation, and routes without factor preparation
retain their counters with zero phase time.

`PresolveInvocationStats` records each pass's lane, exclusive elapsed time and
phase work, including its construction/propagation share. Outcomes distinguish an
eligibility skip, completed empty analysis, retained reductions, refutation and
observed interruption. Namespace and candidate-shape checks precede entry scans;
factor-count gates read the finite session's maintained live count. Closed discrete
sources skip open-range probing, and integer-row-only analyses require integer columns.
Entry size is cached per immutable factor revision and maintained from finite-session
deltas. Entry analysis also caches source unit pins; finite calls read maintained
root fixings. Dimensions, available occurrence counts and explicit column narrowings
describe yield without another whole-model scan.

`PresolvePreparationStats` separately attributes pre-bake LPs, root and engine
bakes, finite-session construction/materialization, source encoder rebuilding and
finite exact-real difference assembly, including with elective passes disabled.
Nested observations own their time and work exclusively. Pass work, preparation
phase work and any unattributed remainder account for the phase total.

Operations under containing controls preserve their cancellation token and forward
native charges unchanged, recording `nativeWork` separately from phase units.
Observation neither reprices that work against the elective allowance nor renews
it; the temporary recorder restores the containing meter on exit. The
[CLI statistics](20-cli.md#statistics) expose bounded lane/pass/outcome and
preparation-stage summaries of this ledger, including preparation-only requests.

Stop observation reuses a directly observed token only for its owning budget and
records interruption on the active invocation at polling time. Composed tokens keep
their added predicates even when their meter is shared. The source pipeline binds
the elective phase allowance; required encoder selection keeps the containing token.

## Selected reductions

The passes listed under [Pipeline](#pipeline) share the guarantees above. Candidate
and term visits charge and poll the pass token under bounded scans. Interruption
retains completed sound reductions and discards unfinished analysis. The following
families describe the premises and preservation guarantees of those reductions.
Both lanes poll after entry charging and meter factor, column, term, signature
and rewrite visits. Bound fusion, duplicate-column and subsumption scans accumulate
those charges and flush them and poll the pass token every 64 visits or 4,096 units,
and immediately at entry and before publishing, so a stop overruns by at most one
stride. Duplicate-column aggregation, bound fusion and source duplicate/domination
indexing discard unfinished transactions. Structural reduction
retains only fully collected factor rewrites and their bound tightenings; factor
hooks and structural-key construction remain atomic.

### Linear reductions

Coefficient strengthening divides an integral row by its coefficient gcd and rounds
the bound inward; an indivisible equality refutes the source. Bounded integer
inequalities can also clamp coefficient magnitudes against the complementary cover
threshold. Magnitudes, shifts, widths and activities use checked arithmetic; an
overflow declines the lift and retains any completed gcd reduction. Activity and
clamp eligibility are scanned without term-sized scratch; output arrays are allocated
only when a nonfixed coefficient exceeds the threshold. Diophantine tightening
restricts each coordinate of `sum(a_i * x_i) = b` to its implied residue class,
narrowing bounds and, in finite state, carving off-class values.

Dependent-equality removal uses fraction-free elimination over unconditional integer
equalities and refutes rationally inconsistent blocks. It drops only factors whose
whole meaning is one redundant equality; conjunctions and relaxations can supply
premises but remain intact. Binary partition equations retain their propagation and
branching structure, including scaled equations equivalent to `sum(x_i) = 1` over
declared integer bounds within zero and one. They still supply elimination premises.
Guarded or disjunctive rows are excluded, and interrupted elimination publishes no deduction.

Sub-sum aggregation contracts a defining equality's partner terms to its output
variable, retaining the equality. A unit pivot and two distinct integer partners
are required; models with fewer than three integer columns skip entry analysis.
Rarest-partner incidence and row widths bound
candidate scans by indexed size and an absolute work cap.

Opt-in `+sparsify` subtracts retained unconditional integer equalities from complete
single-row factors, accepting only fewer nonzero terms. Positive target scaling
preserves the comparison; no domain, objective or variable elimination is involved.
Overlap incidence, width, ratio and checked coefficient-growth limits bound attempts.
If every term cancels, a zero term represents the constant comparison.
Both row-rewrite passes discard unfinished attempts and keep completed replacements
with their unchanged equality premises.

Bound fusion combines matching sides into an equality or refutes crossing bounds.
These reductions preserve the solution set. Affine singleton elimination and
singleton inequality projection require reconstruction; projection stays off for
solution-set-sensitive queries.
Singleton projection meters occurrence construction, row specialization, candidate
and rewrite scans through the invocation token in both lanes. It polls at bounded
strides and before publishing each row with its reconstruction; an interrupted
occurrence index supplies no projection, and completed rows survive a later stop.

### Structural reductions

Structural reduction calls each factor's `structuralReduce` hook under current
domains; source execution restricts this to closed integer columns. Both lanes skip
the final `Clause` type's unchanged hook before visiting its columns, retaining the
factor-level charge and cancellation poll. Other factor types retain their hooks.
Ground tables with at most 4096 tuples allocate survivor storage on the first rejected tuple,
copying the retained prefix once; unchanged tables keep their tuple payload.
Redundancy and subsumption use indexes maintained across rounds. Duplicate removal and index
retraction identify stable factor occurrences, so repeated references to one immutable
factor retain one representative. Clique-implied subsumption admits
only unconditional positive Boolean knapsacks and derives activity bounds from
retained clauses/cardinalities, so a dropped row cannot justify its own removal.
Strict-subset domination uses checked arithmetic for extra activity and scaled
bounds; an overflowing attempt supplies no reduction. Its independent `subsume-subset`
observation excludes cheap duplicate drops. Equal support widths skip comparisons;
an anchor-incidence index selects smaller rows. Source and finite rounds retain
completed comparisons and resume interrupted scans. Changed rows or activities
invalidate affected comparisons; unrelated changes leave them cached.
Normalized rows are kept per factor occurrence across calls. Each call admits widths
up to 128 and at most 65,536 normalization and comparison visits, retaining at most
65,536 failed pairs; upkeep linear in the current rows and candidates is charged
outside that cap, so bounded calls over an unchanged model reach their comparisons.
Completed drops survive interruption.

Comparison-clause folding combines sole-use reified indicators into one comparison
disjunction. It requires both Boolean and integer columns before entry accounting;
unrelated real or open columns do not exclude integer comparisons. Finite preparation
leaves a clause reified in its source and finite rounds when
[binary-column substitution](#binary-integer-channels) would convert a 0/1 column one
of its comparisons reads: that comparison is a Boolean literal of the column, which
substitution keeps in the clause lane for search and learning, while the folded clause
would hold the column in the integer lane. Comparisons over columns substitution
cannot convert still fold. Explicit `+comparison-clause` folds every clause; open
preparation substitutes no columns and always folds them.
Reconstruction restores their truth after integer reconstruction and
before source checking, and excludes them from direct search/definition hints.
Duplicate-column merging replaces matching linear columns by their representable
sum; an unrepresentable sum declines the merge, and a call that declines every
merge leaves the model unchanged. Reconstruction computes the feasible split
interval and difference exactly, including at extreme source endpoints, and undoes
successive folds last first.
Dual fixing selects a bound when neither occurrences
nor the objective prefer the opposite direction. These change the solution set.

Boolean cover dominance extends dual fixing: a positive-cost unfixed Boolean read
only by positive unconditional cover clauses can be pinned false if a strictly
cheaper nonnegative-cost column covers every one of its clauses. Other readers,
unit pins and finite root fixings exclude either column. Bounded incidence indexing
and exact subset checks select replacements; incomplete indexing publishes nothing.
Strictly decreasing costs make replacement chains acyclic, preserving an optimum.
Like dual fixing, this rule is excluded from complete-coverage queries.

### Boolean reductions

Opt-in `+clause-subsume` removes a clause implied by a retained subset and shortens
clauses by self-subsuming resolution. It indexes clean Boolean clauses of width at
most eight, with separate candidate and literal visit caps. Completed edits remain
equivalent against the live database, preserving every assignment and objective,
including arithmetic-shared columns; counting needs no reconstruction.

Implication harvesting probes live, unfixed Boolean candidates; an empty set builds
no probe root. Mutual-implication cycles merge representatives and transitive binary
clauses drop. Interrupted reachability restores candidate edges, and unfinished SCC
discovery publishes no merges.

Bounded variable elimination resolves an objective-free Boolean occurring only in
clean clauses when neither distinct clause count nor literal volume grows. Each
pivot is bounded to 4,096 pairs, width 64 and 65,536 visits; an unfinished pivot
publishes nothing. A completed empty resolvent immediately refutes the model in
either lane without materializing an empty factor, even if later work is cancelled.
Blocked-clause elimination removes clauses whose resolvents on one literal are all
tautologies. Each clause attempt is capped at 65,536 visits across candidates,
opposite occurrences and literals; each visit charges and polls the pass token.
Incomplete blocking checks keep their clauses, while completed removals retain
their repairs. Both passes need witness reconstruction and can change source
multiplicity. Their shared database construction charges and polls column,
factor, literal and occurrence-index visits; interrupted construction publishes no
database or rewrite. BVE also meters its stable initial degree ordering before
attempting a pivot. Source and finite calls use the same pass token and work units.

At-most-one merging replaces pairwise exclusions with a cardinality when at least
two factors drop. Default clausal optimization retains exclusions for native SAT
propagation and learning; `+amo-clique` or aggressive emphasis permits conversion.
XOR unit derivation uses bounded GF(2) elimination and emits only forced values and
refutations.

### Symmetry and probing

Symmetry breaking verifies candidate transpositions by remapping every factor, then
orders verified orbits while protecting objective columns. Value precedence breaks
value symmetry. Both stay off for solution-set-sensitive queries.

Boolean probing retains failed-literal units and bounds implied by both polarities;
integer and open-range probing extend this to bounds and holes. Compilation, incidence,
row and graph visits charge and poll the pass token. An interruption retains completed
unconditional deductions and retracts the active hypothetical branch. Candidate,
entry and representation limits bound optional analysis, not solve time.
Finite SAC ordering shares one lazy propagator-filtered integer incidence index per
ordering call; fixed columns require no incidence. Interior-hole candidates follow
present domain successors in ascending order, skipping accumulated exclusions without
enumerating numeric gaps or materializing wide domains.

Source probing and symmetry defer to finite preparation for closed discrete-only
models. With real columns they may run in the source lane even when integer
columns are closed or absent; generator lex and value-precedence chains remain
finite-owned.

### Pre-bake LP bounds

The finite pipeline runs pre-bake LP bound tightening when a factor couples two
nonfixed integer columns whose spans exceed the large-span threshold, or couples a
wide integer column to a continuous one. It queries at most 64 columns, widest
first, and every accepted tightening requires the LP bound certificate.

### Binary integer channels

Binary-column substitution turns single-variable reified relations over a 0/1
integer into Boolean equivalences when every reader supports the replacement,
reusing an existing channel literal where one exists. General reified rows,
value-reading globals and objective columns keep their integers. Substituted values
are reconstructed from their literals, preserving source values and counts.

## Source routing and encoder selection

Presolve also decides, together with routing, which owner enforces each part of the
prepared model: finite propagation, an arithmetic theory or both.

Routing attempts bound closing before rejecting a polynomial composition on open
integer declarations. Proved bounds keep declared holes and factor identities,
unrelated finite globals keep their finite owner, and a surviving open side stays
arithmetic-owned. Invocation-local fixings are never permanent source bounds. A
declined composition keeps its finite owners, and the missing completion withholds
whole-query admission.

Optional passes and bound closing run under the presolve phase token, while required
component installation runs under the containing invocation token. Exhausting the
phase can forgo reductions, but never skips a required encoder or turns an uncovered
source into a supported fragment. Source rewrites reselect encoders against the new
canonical factors; original value sets, reconstruction and assertion premises stay
authoritative after selection.

An unchanged source can reuse its captured open component plan only with the
identical model object and factor-projection binding. Rewrites, appended objective
rows, different bindings and resource-declined captures require fresh planning.
Only immutable selections cross this boundary; each execution opens private search
state.

Factor-backed polynomial execution runs the same source-safe schedule before
rebuilding polynomial ownership and encodings. Its typed source mapping reconstructs
Boolean and integer coordinates before original-source checking and publication,
including streamed optimization incumbents. Native formulas without a canonical
factor view keep their native preparation; disabling presolve is accepted on every
route, while individual reductions require their input representation.
Original-source checking after reconstruction spends the containing arithmetic
allowance and retains an already checked publication if later work stops.

## Source witness validation

Presolve hands search a transformed model, and the following chapters describe
the engines that look for assignments of it. Before any of those engines can
publish an assignment, it must be checked against the original source. Every engine
in the rest of this part relies on this check: a solver's own view of the model is
never the authority on whether a point is a solution.

`solver.validation` owns reusable checks of proposed source coordinates. Search,
local search, LP adapters, evidence and pipeline or portfolio orchestration call
these services; checking does not require an orchestration owner. A check has three
outcomes. Acceptance means the point satisfies every checked source obligation.
Rejection refutes the proposed point, never the source. Indeterminate means a
required checker was unavailable or the check was interrupted. Publication
identity, restrictions, objective scope and certificate admission remain with the
[portfolio](12-portfolio.md#witnesses-and-proof-scope) and [evidence](13-evidence.md) callers
described later in this part.

### Finite points

`composedFixpoint` creates a private propagation session for the full Boolean and
finite integer point and checks the selected original-source relations. The point is
the session's private root, so incompatible caller pins still refute it. Prepared
custom providers keep their selected factories and required original checks.
Cancellation or unavailable required checking returns indeterminate. Every exit
reports the propagation work through the caller's accounting hook, and no scratch
state survives a check.

Exact point checking also validates integer absolute-value and distinctness
relations on authoritative arbitrary-width values. `AllDifferent` compares every
present position, preserving presence-literal polarity and exempt values, and
missing coordinates reject the point.

### Open and real coordinates

`verifyOpenWitness` checks discrete coverage, declared integer bounds and value sets,
then every original relation touching a real coordinate, then projects the remaining
discrete relations into a singleton-domain finite check. An unavailable real checker
stays indeterminate. It invents no bounds for open declarations, and
arbitrary-width integers go to their exact theory checker instead.

`verifyRealCoordinates` checks authoritative rational coordinates against source
bounds and supported exact relations, including strict and reified rows. Missing
coordinates or a factor without a complete checker stay indeterminate; algebraic,
symbolic and wide-integer checks belong to their specialized owners. Visits are
charged to the caller, and rational powers compare normalized numerators and
denominators, using bit-length bounds to refute impossible equalities before
allocating.

Finite real domains compare their captured binary64 literals as exact rationals. An
isolated algebraic root matches a rational member when the literal lies inside its
isolating interval and zeroes its defining polynomial, without opening a polynomial
solver.

### Projected and tolerance-qualified witnesses

`verifyProjectedSourceWitness` combines original relation checking with integer
declarations and a private composed fixpoint over captured descriptors. A caller
callback can add a restriction but cannot replace relation coverage. Exact policy
uses the full original real graph. Nonzero tolerance has a complete adapter for
unconditional non-strict linear comparisons and active indicator rows; strict or
reified tolerance semantics stay indeterminate. A private-grid relation cannot
certify its own original real relation; see
[private float projections](02-model.md#private-float-projections).

`finiteWitnessVerifier` combines the finite point check with exact real checking or
the caller's explicit tolerance policy, checking pins and deduced bounds directly on
the completed point. A tolerance callback requires complete finite real coordinates
and does not change exact evidence semantics. See
[model ownership](02-model.md#published-assignments-and-objectives) for coordinate
lifetimes.

Original linear-source checking evaluates original rational rows and declaration
bounds before normalization. Integrality stays exact; row slack is the larger of the
absolute tolerance and the relative tolerance times the larger of bound magnitude and
the sum of absolute term activities, with cancelling terms kept separate. Accepted
points record whether nominal relations also hold, and tolerance acceptance never
transfers nominal proofs. Unmetered checks poll cancellation at most every 64 visits.
A captured acceptance check carries a separate permission for floating optimum
completion, which zero tolerance withholds.

### Dependencies

The shared services depend on source and factor descriptions, propagation, neutral
sample and verification contracts, exact rational arithmetic and model-facing exact
row views. They do not depend on pipeline, portfolio, backtracking, local-search,
evidence orchestration or concrete theories.

---

Previous: [6. Sessions and resources](06-sessions.md) · [Contents](README.md) · Next: [8. Search and propagation](08-search.md)
