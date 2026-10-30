# 7. Presolve and validation

Presolve fixes variables, rewrites relations and tightens bounds before search.
Each reduction declares the preservation guarantee required by satisfaction,
optimization, enumeration or counting, and retains source reconstruction. Preparation
charges work to the [containing allowance](06-sessions.md#resource-allowances).
[Source validation](#source-witness-validation) checks reconstructed engine points
before publication.

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
pass's entry weight (unit weight for self-metered passes) and retained yield,
estimates the intervening changes needed
before another analysis. Yield is the largest of net occurrences removed, factors
removed, reported columns narrowed or columns fixed, with a floor of one. Distinct
new Boolean unit pins count as fixed columns; existing units and finite root fixings
do not. Empty and interrupted calls retain their cost; only completed misses can
park a pass. Parity elimination tracks revisions of XOR and integral equality rows,
participating Boolean pins and odd-coefficient integer ranges in both lanes. A
relevant revision releases its completed miss; unrelated factor, pin and range
changes retain parking. Tracking reads changed factors and participating columns,
charges the same phase allowance and stops on its cancellation. Coefficient
strengthening also parks a completed miss until an implied integral equality,
a complete integer or Boolean row, or a participating integer inequality endpoint
changes.
Opt-in row sparsification wakes on complete integral row changes; domains, pins and
objectives alone do not change its premises. Each lane owns its block revisions,
retracts dropped occurrences and releases columns when their last inequality disappears.
The state is bounded by live participating columns and dies with the lane. Interrupted
tracking discards its state and publishes no revision. Both wakeups retain the
containing allowance and existing cost/yield backoff; a changed revision establishes
eligibility, not an obligation to spend. Other parked analyses retain their completed
misses for the schedule. These signals are
deterministic spending heuristics, not predictions or proofs of search
savings. Unmetered calls use the 1% change threshold without adding accounting
scans. Cancellation and phase allowances still bound optional work. Presolve and
an LP-relaxation harvest iterate to a joint fixpoint for at most four rounds, the
harvest rerunning only when it tightened something. Automatic harvest skips clausal
satisfaction unless rewrites expose cardinalities with stronger LP rows;
propagator-only symmetry does not change that eligibility. Explicit `+lp-harvest`,
nonclausal sources and optimization keep harvest eligibility.
[Wide-integer pre-bake bounds](#pre-bake-lp-bounds) use a separate safety gate.

LP harvest carries compatible immutable source-row and exact-bound templates across
finite rebuilds through the selected projections. Unchanged definitions retain their
buffers; changed bounds, rows, objectives and component joins replace the affected
component snapshots. Namespace remaps and semantic-scope changes require fresh
bindings. The later model-facing LP consumers instantiate their own mutable owners
from these definitions; see [LP preparation](17-linear-programming.md#exact-source-row-projections).
A no-op preparation preserves the canonical model handle. Compatible projections
share the completed immutable finite-template seed for the later engine bake. That bake rebinds the
index to its own source and checks its current bounds before reuse.
Template construction spends the same containing allowance, and unfinished preparation
publishes no reusable state. Template reuse changes assembly work, not pass admission,
probe selection, reconstruction guarantees or source authority.

The round-boundary diminishing-return check counts live factors plus integer-domain
endpoint spans. Incremental and rebuilt hosts use the same nonnegative measure,
saturating spans and totals at `Long.MAX_VALUE`; saturation can hide reductions.
The default abort fraction is 0.001. This structural heuristic supplies no runtime
guarantee.

| Family | Passes |
|---|---|
| Linear | Coefficient strengthening, Diophantine tightening, bound fusion, affine elimination, sub-sum aggregation, row sparsification, dependent equalities, singleton projection |
| Structural | Redundancy and subsumption, structural reduction, extensional composition, comparison-clause folding, duplicate-column merging, dual fixing |
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

### Rewrite representation controls

`AffinePivotOrder.REWRITE_COST` retains Markowitz ordering between definitions and
tries the smallest rewrite payload among an equality's unit pivots first. It sums
the current incident factors' `structuralKeyWeight`,
excluding the definition, through the maintained occurrence index. Ties retain
declaration order. Scoring charges and polls the phase allowance and never builds
factor keys or speculative replacements. The ordinary admissibility, domain,
objective and reconstruction checks apply to the preferred pivot; a rejected pivot
falls back to declaration order. This opt-in estimate
describes construction size; active downstream search still determines total cost.

`PresolveConfig.preserveStructure` is an opt-in performance control for affine and
structural reduction. Structured factors are all factors except plain `Linear`
and `Clause`; binary partition rows have equal nonzero coefficients and right-hand
side over declared integer bounds within `0..1`. Affine elimination protects every
integer coordinate of these factors, including aliases and exact objective
translations. Structural reduction retains these factors. Unrelated linear
reductions remain eligible. These controls do not change correctness admission,
query guarantees or source reconstruction, and do not suppress other passes.
Protection discovery charges and polls the phase allowance; an incomplete discovery
publishes no affine elimination.

`structuralImpactLimit` enables a bounded delta view for affine, structural and
extensional reductions. Support counts cover discrete Boolean and integer inputs.
Each factor, support entry, declared row term and affine
reconstruction dependency visits and charges the containing phase allowance.
The cap is per productive invocation, including both sides and reconstruction;
zero disables all diagnostic scans. Counts describe removed and added factors,
structured factors, binary partition rows, mixed Boolean/integer factors, Table
cells, support width, integral declared linear nonzeros and equality definition
inputs. Table cells use their stored array length without scanning the payload.
Non-integral or wide declared rows are explicitly uncounted. Affine reconstruction
reports definitions, input edges and maximum recovery depth, with scratch space
bounded by the visit cap. A partial view carries `Complete=0`; a diagnostic stop
retains the already completed sound delta. Its visits join the invocation's work.
Counters are sparse: an absent counter is zero only in a complete view; an absent
view is unobserved, and a partial view cannot establish a zero count.

Mixed-factor incidence is a channel proxy; support width is a potential explanation
scope; equality inputs and reconstruction depth describe potential definition-cone
changes. They do not establish a worker's installed channels, actual reasons,
maintained local-search sweep or emitted LP nonzeros. Those consumers retain their
own admission and runtime statistics. A smaller representation alone does not
qualify a default policy change.

## Guarantees and source mappings

`PresolvePass.guarantees` separates equisatisfiability, objective preservation,
witness reconstruction, bijection and complete coverage by reconstructed source
assignments. Objective preservation requires protecting objective columns or carrying
an exact replacement objective with the factor delta. A satisfiability reduction can keep an optimum while losing source
multiplicity, so successful reconstruction does not establish a bijection.

`SourceMapping` binds reconstruction to the actual source and reduced objects;
composition requires one stage's target to be the next stage's source. Matching
counts do not establish identity. Affine values replay with exact arithmetic in
reverse elimination order. Boolean elimination, blocked-clause repair, implication
merging and singleton projection retain typed reconstruction too.

Binary substitution restores later Boolean reductions before reading carrying
literals, then truncates appended Booleans, preserving the earliest namespace.
Definition hints can rename retained aliases and substitute a single affine
reconstruction over retained columns; a rebuilt output passes to its single partner.
Ordered search hints can follow `x = y + c` aliases only for translation-invariant
variable selectors with minimum/maximum values. Bound-based selectors and other
value choices require direct coordinates. Untouched columns retain their orientation;
a custom reconstruction without correspondence withholds hint projection.

Objective adjustments map reduced values into source units as `scale * value +
offset`, with positive exact rational scale, and compose by multiplying scales.
Finite preparation can substitute exact integral aliases and translations `x = y + c`
in a linear objective, combining coefficients and retaining the constant in that
objective. Source and finite rounds, LP harvest and search use the replacement;
later passes protect its remaining nonzero columns. The adjustment stays identity,
so bounds and incumbents retain their original units and normalized direction.
Checked coefficient and constant arithmetic declines overflow and the non-negatable
signed minimum. Definition-specific objective admission precedes occurrence and
overflow scans and pivot cost selection. A rejected definition leaves other pivots
of that equality and translations through other definitions available.
Wider affine expressions, approximate semantics, open-theory
preparation and protection-only objectives retain objective-column protection.
`SeparableObjectiveView` can expand a single integer output through retained
mandatory integral equalities and unit pivots without rewriting the model or its
objective. It combines coefficients and offsets in the same normalized objective
units, declining checked overflow,
open columns, approximate semantics and cyclic expansion. One attempt permits at
most 4096 integer slots, 256 surviving terms, depth 16 and 32768 charged visits.
Breadth-first selection assigns each defining row one pivot before expansion, so
shared partners cannot reverse a row selected for another output.
Conditional rows supply no definition authority. A failed or interrupted attempt
leaves the original costs available; its work remains charged.
Default single-core mixed placement optimization can prepare a second finite view
with objective columns protected when affine substitution removed an objective
coordinate. This elective preparation spends the remaining preparation allowance;
its effort joins the invocation totals. The rewritten model retains complete-search
ownership. The [portfolio](12-portfolio.md) uses the protected view only for checked
local-search proposals over a compatible discrete namespace.
Finite holes and projected hints follow the same reconstruction as factor substitution.
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

### Private worker qualification

`rootRebuildCaptureVisits` enables a bounded propositional opportunity view inside
root simplification. Zero disables it. The view accepts only a baked Boolean clause
model at an unconditional, consistent fixpoint. Assumptions, scoped assertions,
conditional cutoffs and unfinished propagation supply no global authority. It compares
the baked root with the current root over the identical canonical factor objects,
separating newly fixed columns and stable-ID clause removal/shortening from newly
eligible elimination candidates. For an unfixed column with positive/negative
occurrence counts `p` and `n`, the diagnostic requires `0 < p + n` and
`p * n <= p + n`. It counts only candidates that failed that bound at the baked
root. This is a sufficient clause-count proxy for a single elimination, not a
resolvent construction, literal-growth bound, admission decision or prediction of
search savings. Objective preservation, other pass families and learned-clause
remapping require separate qualification. Learned clauses are excluded from this
canonical-source proxy; retaining them can increase elimination and import costs.

The capture shares root-class and containing inprocessing work, cancellation and
overspend debt. A scan charges initialization, factor/literal/column visits and
disposal; it withholds all candidate totals on an incomplete view. Four temporary
arrays use `18 * columns` modeled primitive bytes, excluding headers and allocator
overhead, with columns bounded by one tenth of the visit cap. No array survives
the call. Repeated complete views are observations, not distinct opportunities.
An unsupported or partial observation cannot establish absence of opportunities.

Qualification of a replacement worker requires the following authority and ownership
contract. The diagnostic itself creates no replacement or publication authority.

`privateRebuildWork` funds bounded private Boolean satisfaction trials. An
unconditional consistent root pauses its canonical traversal, simplifies a private
copy with the root's global pins and runs source BVE with typed reconstruction.
The new baked model has a fresh identity; mappings compose through the pinned,
eliminated and baked objects. Column slots remain stable. Only an actual elimination
opens a consumer. Assumptions, external components, arithmetic and LP-enabled
workers retain their canonical traversal. Enumeration and optimization do not use
this trial.

Pinning retains unchanged immutable source clauses by reference and copies only
shortened rows. Column counts stay identical, preserving the clauses' plain-Boolean
classification. Each new baked model owns its projection, watches and search state;
the shared factor input carries no mutable search state.

A trial requires global root pins beyond the baked root and the last attempted
view. An unchanged baked root performs no BVE. The root-pin scan charges its full
column visit count and records its time; an insufficient grant skips the scan.
An unchanged root trail retains a completed miss. All attempts share one
cumulative preparation, trial and checking allowance. A bounded admission scan
uses saturated occurrence counts and parent literal volumes over the residual
clauses. It admits a non-growing raw resolution shape: a single parent on one
side satisfying `n * (w - 2) <= w`, or two parents on each side with total width
at most eight. Tautology and
duplicate savings can admit other useful pivots, so a proxy refusal establishes
no absence of opportunities. Width and per-pivot budgets still apply. Temporary
arrays own `24 * columns` primitive bytes inside the declared admission model;
initialization, disposal and visits consume the containing allowance.
The scan retains a protected-column set for every refused shape inside that
model. BVE restricts this trial to the initially admitted columns, so an unrelated
declined pivot cannot consume its resolution allowance. Later opportunities among
protected columns wait for a fresh root view. This selection is a heuristic, not
an exhaustive elimination pass.

When one column is admitted, the pass indexes its complete incident-clause
neighborhood. Outside rows contain no pivot literal and remain unchanged; resolving
the pivot commutes with those rows. The neighborhood keeps canonical column slots
and transports every dropped local factor index back to its captured full source.
An incomplete scan publishes no view. Multiple admitted columns retain the full
database path. Neighborhood scanning and index transport debit preparation, while
the kernel retains its local pass cap.

BVE receives half the allowance remaining after source and neighborhood preparation. Its local cap
retains complete earlier pivots when later analysis stops; cancellation of the
containing call declines the generation. Baking, opening and canonical checks
share the remaining containing allowance.
Private baking adapts the root engine's construction and propagation meter into
that allowance; those visits join preparation work rather than remaining on an
unobserved temporary root state.
Opening reserves another construction visit count for the fresh consumer before
allocating its state. Reconstruction charges the larger of the source-entry model
and a bound covering all composed sample copies and eliminated-clause replay.
The latter uses the actual reconstruction descriptors; repeated elimination can
retain more replay literals than one source view contains.
Mapping composition separately charges its descriptor copies during preparation,
including trials that produce no witness. The descriptors add their modeled
primitive/reference storage before composition and copied-learning admission.

Unused BVE allowance can copy globally learned clauses into the private model.
Only this canonical worker's non-permanent clauses over retained Boolean columns
qualify. Clauses mentioning an eliminated column need an unsupported resolution
translation and are skipped. Global pins remove satisfied clauses and false
literals. Each copied consequence owns its literal buffer and consumes the same
work and modeled-memory grants; an exhausted local transfer grant retains the
completed subset. The mapping composes through the enriched model's exact identity.
These copies preserve consequences without sharing learned-store indexes or
exchange cursors. Their work and time are preparation subsets; private baking
and propagation include the added clauses.

The private worker owns fresh watches, learning and search state. Canonical exchange
cursors and pooled incumbent guidance stay with the retained canonical worker;
unsupported translations never attach to the private model. Every private witness
reconstructs and completes a canonical clause check before publication. Partial
checks retain their cursor across slices. Private refutations grant no canonical
proof authority and resume the retained traversal. Declines and exhausted trials
also resume it. Closing releases the private consumer, reconstruction and cursors
after its final drains. Preparation, trial, reconstruction and checks consume the
original containing deadline/work; cooperative overspend carries to later slices.
The scratch allowance gates a declared private buffer/reference-slot admission model;
it excludes object headers, the canonical worker and allocator overhead. Physical
overlap memory and total cost require separate measurement. Zero disables this
unqualified trial policy.

- A canonical source handle, its source scope, semantics and exact objective define
  publication identity. Only globally justified facts enter an updated source view;
  an incumbent cutoff, neighborhood, assumption or branch condition remains attached
  to its own scope. A fresh worker and mapping/import generation identify every
  private reduced namespace. Equal dimensions or revision numbers do not bind maps.
- Reconstruction composes typed `SourceMapping` stages through the actual intermediate
  objects, retaining objective units and the query's required preservation guarantee.
  Every candidate lifts to canonical coordinates and passes source checks before
  publication. Reduced infeasibility or optimality alone supplies no canonical proof.
- Imported clauses and bounds carry their original scope and premises. An exact
  supported translation into the new namespace is required before attachment;
  eliminated coordinates, missing premises, changed guards or incompatible objective
  units decline import. Duplicate suppression and import cursors belong to their
  generation. A retained incumbent is checked in canonical coordinates and projected
  only through a supported inverse; failure leaves it advisory.
- Immutable LP templates retain P3's namespace, raw/finite, scope, row, bound,
  objective and component compatibility checks. Mutable bases, factors, propagation
  queues, watches and search cursors belong to one worker. No live shared SAT arena
  is eliminated or remapped. Lost bases, cuts, phases, learned state and continuations
  are charged as cold-start or revalidation costs, even when a template survives.
- Preparation and replacement overlap under the original containing deadline and
  work allowance. Construction, source checks, reconstruction, import, publication,
  failed attempts and retirement remain charged; reopening does not renew a budget.
  Publication is an atomic handoff after complete checks, with cancellation before
  handoff leaving the established worker valid. A partial replacement publishes nothing.
- Resource admission covers both generations, canonical/mapping payloads, retained
  templates, pass buffers and scratch. Count shared payload once and private indexes,
  capacities and reference slots per owner. Modeled owned/protected bytes cover only
  their declared owners; sampled peaks and aliased reason totals are not heap totals.
  Live/historical reasons, sibling undo, pending assertions and attachments remain
  protected until their last consumer releases them. Close/reseed drops obsolete
  generation cursors, imports, buffers and maps only after final drains; suspension
  retains them. Replacement requires bounded cancellable reclamation preparation and
  safe publication or an explicit defer/decline when the protected floor prevents it.

The cost decision requires compatible actual consumers and the complete lifecycle,
including reconstruction/checking, worker reopening, lost warm state, overlapping
memory and obsolete-generation cleanup. Missing costs remain unobserved. A smaller
model, template hit or successful scan establishes neither net benefit nor permission
to enable rebuilding.

### Finite reconstruction

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
Completed root-probing refutations remain terminal across empty finite schedules,
no-op incremental returns and later optional stops. Finite preparation retains that
verdict before selecting an engine projection; interrupted hypothetical branches
contribute no refutation.
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

Paused open-theory workers retain the completed preparation snapshot through close
or cancellation between slices. Decisions and setup cancellation carry the native
result's snapshot. Reading these observations neither adds preparation time again
nor charges theory time to preparation.

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

Dependent-equality removal uses fraction-free elimination over unconditional exact
integer, real and mixed equalities and refutes rationally inconsistent blocks. Tagged
source coordinates keep the namespaces disjoint. Integral constants retain arbitrary
width; finite binary64 constants are read as exact rationals and their denominators
are cleared. Nonzero source feasibility tolerance excludes the pass. It drops only factors whose
whole meaning is one redundant equality; conjunctions and relaxations can supply
premises but remain intact. Binary partition equations retain their propagation and
branching structure, including scaled equations equivalent to `sum(x_i) = 1` over
declared integer bounds within zero and one. They still supply elimination premises.
Guarded or disjunctive rows are excluded. Rows admit at most 4096 terms and 4096-bit
constants, with at most 262144 entries across the block. Sparse elimination bounds
row fill, total entries and intermediate product bit lengths by the same limits;
an exceeded bound discards the unfinished elimination. Candidate and term visits
charge the phase allowance. Interrupted elimination publishes no deduction.

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
Unrepresentable affine constants, partner coefficients or carried-bound arithmetic
retain the source definition. A residue projection whose numerator exceeds the
signed word retains its equality, since division can still yield a representable
source value. Partner spans must fit the bounded scan without subtraction overflow.
Singleton projection meters occurrence construction, row specialization, candidate
and rewrite scans through the invocation token in both lanes. It polls at bounded
strides and before publishing each row with its reconstruction; an interrupted
occurrence index supplies no projection, and completed rows survive a later stop.

### Structural reductions

Opt-in finite `+tuple-packing` replaces positive ground Tables partitioning one
plain AllDifferent by Boolean placement choices. Each Table chooses exactly one
distinct row; each occupied value has capacity one across all choices. When the
value universe equals the scope size, every capacity row requires exact coverage.
Rows with repeated occupied values or entries outside established root domains
are excluded. Two groups requiring the same value refute the packing even when
every scalar coordinate retains support. Cardinality propagation, Boolean branching
and the ordinary core LP projection consume the shared incidence rows.

The substitution requires exclusive coordinate ownership by the partition's Tables
and AllDifferent, with stated bounds and no objective use, optional positions,
exceptions or repeated roles. Other uses retain the original factors. Original
integer slots are pinned; typed reconstruction reads their selected tuple before
truncating fresh Boolean
coordinates. Deduplicated rows give one Boolean extension per source assignment,
preserving solution multiplicity. Admission caps a scope at 4,096 coordinates,
retained choices at 65,536, tuple cells at 262,144 and construction at 1,048,576
charged visits. Interrupted construction publishes no partial substitution.

Opt-in `+extensional` preserves shared tuples across ground Tables and MDDs. Table
semijoins retain rows with a matching tuple on at least two shared columns. MDD
composition intersects the diagram with a trie of the table's shared-column
projection, in diagram order, retaining its edge weights and cost coordinate.
Private table columns stay in the original table. The composed diagram uses ordinary
MDD filtering and explanations; every restriction follows from unconditional source
factors, independently of transient domains. Repeated roles and interval tables
decline this mechanism.

Three ground Tables can also transmit a cycle through columns outside a target
Table. Each neighbor is indexed by its target columns and the remaining shared
columns. A target row survives exactly when the two neighbor support sets
intersect. This streams the completed three-relation join projected back into
the target, without materializing joined rows or adding search-time state.
Surviving target rows retain every private column and duplicate occurrence;
other factors, including AllDifferent over private indices, remain constraints.
Omitted columns outside the three Tables overapproximate their wider cluster:
a surviving row certifies support only in these three relations, never source
satisfiability. Assumptions and transient domains supply no premises.

A call attempts at most 64 cyclic projections, each with 262,144 visits and
65,536 stored key/support cells, including one cell per set entry. Scope selection,
indexing, support intersections and row copies share the call allowance. Incomplete
projections publish nothing. Ordinary Table propagation, explanations and undo
consume completed restrictions; there is no retained cluster propagator.

Each attempt admits scopes up to 64, tables up to 262,144 cells and diagrams up to
65,536 states and 131,072 records. Product states and records have the same caps;
an attempt has 2,097,152 visits, and a call has 8,388,608 visits and 1,048,576 retained
cells. A metered variable-occurrence index selects overlapping tables. Visits charge
and poll the invocation token; declined payloads incur no payload-wide entry charge.
An unfinished intersection
publishes nothing, while completed replacements survive a later interruption.
Dead product states are removed before publication; a diagram already satisfying
the projection stays unchanged, preventing repeated root products.

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

Opt-in finite `+table-substitution` excludes an integer value when a strictly
cheaper value preserves every complete tuple of every reader. Only unconditional
ground Tables with distinct coordinate roles qualify; channels, posted breaks and
other readers exclude the coordinate. Both values belong to the captured root
domain, including its holes and opening pins. Exact linear coefficients determine
strict descent by ordering values, avoiding product or difference overflow.
Every excluded value reaches a retained cheaper replacement through an acyclic
chain. Substitutions preserve all other coordinates and the optimum; identity
reconstruction keeps source witness checking and objective units unchanged.

This restriction belongs only to the captured optimization model, objective and
assumptions. Fresh objectives or pins require fresh preparation. It supplies no
source-feasibility nogood and stays disabled for feasibility and complete-coverage
queries, including counting, enumeration and sampling. Admission permits 4,096
integer columns, candidate spans of 64 integers, Table scopes of 64 and payloads of
65,536 cells. At most 65,536 reader entries and 262,144 charged visits cover
occurrence construction, candidate pairs, tuple-cell comparisons and domain copies.
An unfinished call publishes no exclusion; its work remains charged.

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
refutations. Source units and established finite root pins substitute into signed
parity rows before elimination, alongside fixed integer values. Constant odd rows
refute even when no columns remain; interrupted collection or elimination publishes
no deductions, and established pins are not emitted again.

### Symmetry and probing

Metered default finite satisfaction preparation runs one bounded integer-probing
pilot before the finite rounds. Unmetered callers leave it unfunded. Its private
incremental root retains completed exclusions across probes. The first 128 calls
are funded; each failed probe funds another 32. Probes and repairs share this credit
within the configured per-column and total caps, with a work stop at one eighth of
remaining phase work or 50 million units, whichever is smaller. Bounds precede
interior values. A completed refutation remains terminal; incomplete coverage
records interruption. Explicit
integer-probe overrides select the exhaustive tiers or disable the pilot.

Symmetry breaking verifies candidate transpositions by remapping every factor, then
orders verified orbits while protecting objective columns. Value precedence breaks
value symmetry. Both lanes hold unread integer and Boolean columns fixed, including
columns retained only for reconstruction. Value-orbit discovery ignores unread
domains, and value pins and precedence chains use only columns with live factor
readers. Value orbits intersecting an active objective column's domain stay fixed.
Active-column scans charge and poll the pass token; an interrupted scan
posts no break. Both passes stay off for solution-set-sensitive queries.

The opt-in finite `objective-symmetry` pass runs before `symmetry` and admits
variable permutations under the exact current replacement objective. Candidate
colors combine root domains or Boolean fixings with signed objective coefficients;
verification checks those declarations, every coefficient and the complete factor
multiset. Constant offsets are invariant. Scalar orders additionally require each
adjacent single transposition to pass verification, so a lockstep matrix orbit
receives only the existing generator lex and stabilizer propagation. Value symmetry
still protects objective domains. Protection-only contexts, real coordinates,
source symmetry annotations and solution-sensitive operations decline this pass;
pure local-search plans disable it. Default admission retains objective-column
protection. Discovery and verification share the preparation allowance.

Boolean probing retains failed-literal units and bounds implied by both polarities;
integer and open-range probing extend this to bounds and holes. Compilation, incidence,
row and graph visits charge and poll the pass token. An interruption retains completed
unconditional deductions and retracts the active hypothetical branch. Candidate,
entry and representation limits bound optional analysis, not solve time.
Open-range probing caches its invocation meter and charges every visit exactly.
Full cancellation polls occur at entry, root-row and hypothetical-branch boundaries,
and within scans every 64 visits or 4,096 work units. An interrupted scan can spend
at most one polling stride beyond a stop; hypothetical retraction always completes.
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
value-reading globals keep their integers. Exact finite optimization first exposes
0/1 integer costs as a Boolean objective, reusing signed comparison literals or
adding exact equality channels. Fixed costs enter the constant when representable; signed coefficients coalesce
exactly. A fresh positive channel retains the source coefficient when signed reuse
or a constant shift exceeds the objective's storage width. Mixed
integer readers retain their columns and channels. Substituted values are
reconstructed from their literals, preserving original costs and source coordinates.
Continuous models, approximate semantics and solution-set-sensitive invocations
retain their objective form.

## Source routing and encoder selection

Routing attempts bound closure before declining nonlinear composition. Proved bounds
preserve declared holes and factor identities; surviving open sides remain
arithmetic-owned and unrelated finite globals retain their finite owner.
Invocation-local pins never become permanent source bounds.

Optional passes and bound closing run under the presolve phase token, while required
component installation runs under the containing invocation token. Exhausting the
phase can forgo reductions, but never skips a required encoder or turns an uncovered
source into a supported fragment. Source rewrites reselect encoders against the new
canonical factors; original value sets, reconstruction and assertion premises stay
authoritative after selection.

An unchanged source can reuse its captured open component plan only with the
identical model object and factor-projection binding. A certified source deletion
can retain built-in descriptors and completed immutable linear or difference rows
through an ordered retained-factor mapping. Declarations, source coordinates,
semantics, settings and implied flags must agree, and reconstruction must be empty.
Ownership and complete-fragment admission are rebuilt against the reduced source;
retained rows receive its factor attribution while sharing immutable variable
metadata and condition snapshots. Private real auxiliaries, conditioned rows,
custom providers, changed dependencies, different bindings and resource
declines require fresh preparation. Selection, row preparation and ownership have
separate preparation observations. Each execution opens private search state.

Factor-backed polynomial execution runs the same source-safe schedule before
rebuilding polynomial ownership and encodings. Its typed source mapping reconstructs
Boolean and integer coordinates before original-source checking and publication,
including streamed optimization incumbents. Native formulas without a canonical
factor view keep their native preparation; disabling presolve is accepted on every
route, while individual reductions require their input representation.
Original-source checking after reconstruction spends the containing arithmetic
allowance and retains an already checked publication if later work stops.

## Source witness validation

`solver.validation` checks proposed coordinates against original source obligations,
independently of the engine's transformed view. Acceptance checks the point;
rejection refutes only the point; unavailable or interrupted checking is
indeterminate. [Portfolio admission](12-portfolio.md#witnesses-and-proof-scope) and
[evidence](13-evidence.md) separately check identity, restrictions and claim scope.

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

Original linear checks use rational rows and declaration bounds before normalization.
Relative slack keeps cancelling terms separate; integrality is exact. Accepted
points record whether nominal relations also hold. Tolerance acceptance transfers
no nominal proof and carries a separate floating-completion permission, withheld at
zero tolerance. [MPS semantics](05-arithmetic-formats.md#tolerance-publication)
defines the publication policy.

### Dependencies

The shared services depend on source and factor descriptions, propagation, neutral
sample and verification contracts, exact rational arithmetic and model-facing exact
row views. They do not depend on pipeline, portfolio, backtracking, local-search,
evidence orchestration or concrete theories.

---

Previous: [6. Sessions and resources](06-sessions.md) · [Contents](README.md) · Next: [8. Search and propagation](08-search.md)
