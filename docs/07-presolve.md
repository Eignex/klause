# 7. Presolve preparation

Presolve fixes variables, rewrites relations and tightens bounds before search.
Each reduction declares the preservation guarantee required by satisfaction,
optimization, enumeration or counting, and retains source reconstruction. Preparation
charges work to the [containing allowance](06-sessions.md#resource-allowances).
[Source validation](08-presolve-reductions.md#source-witness-validation) checks coordinates that no engine
enforced before publication.

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
[Wide-integer pre-bake bounds](08-presolve-reductions.md#pre-bake-lp-bounds) use a separate safety gate.

The finite invocation carries its source-template policy through pre-bake LP checks,
harvest and the consuming workers. Disabling templates keeps those LP calls and their
allowances, but skips template capture and use.
LP harvest eagerly prepares compatible immutable source-row and exact-bound templates
for its later engine consumer. The selected projections carry these definitions
across finite rebuilds. Unchanged definitions retain their
buffers; changed bounds, rows, objectives and component joins replace the affected
component snapshots. Namespace remaps and semantic-scope changes require fresh
bindings. The later model-facing LP consumers instantiate their own mutable owners
from these definitions; see [LP preparation](20-linear-programming.md#exact-source-row-projections).
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
Planned: component presolve reduces and solves each independent component separately.

### Rewrite representation controls

`AffinePivotOrder.REWRITE_COST` retains Markowitz ordering between definitions and
tries the smallest rewrite payload among an equality's unit pivots first. It sums
the current incident factors' `structuralKeyWeight`,
excluding the definition, through the maintained occurrence index. Ties retain
declaration order. Scoring charges and polls the phase allowance and never builds
factor keys or speculative replacements. The ordinary admissibility, domain,
objective and reconstruction checks apply to the preferred pivot; a rejected pivot
falls back to the first admitted declaration-order pivot. An equality without an
admitted pivot performs no payload scoring. This opt-in estimate
describes construction size; active downstream search still determines total cost.
The same policy can fold an asserted integer definition through word-sized
`ReifiedLinear` consumers. The replacement retains the channel's Boolean output
and comparison in both directions; conditional equalities never supply asserted
pivots. Coefficient checks, the per-row absorption cap and carried domain bounds
apply to these consumers as they do to unconditional rows. Single-partner unit
translations and sign reversals also retain native `ComparisonClause`
disjunctions, translating every affected bound and reversing inequality direction
for negative scale. Unrepresentable bounds decline the pivot. Clause analysis
charges and polls the same allowance without allocating prospective factors.
Other pivot policies retain their consumer admission.

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
Boolean comparison hints can substitute unit-divisor affine inputs over retained
columns while preserving their Boolean output, relation and strictness. Expansion
charges the preparation allowance and declines cancellation or checked overflow;
other divisors retain ordinary hint admission.
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
ownership. The [portfolio](14-portfolio.md) uses the protected view only for checked
local-search proposals over a compatible discrete namespace.
Finite holes and projected hints follow the same reconstruction as factor substitution.
Independent source evaluation checks the composed adjustment against reconstructed
witnesses. A mapping never issues a proof certificate. Optional
`SourceMapping.captureSolve`, `captureMinimize` and `justification` retain reduced
evidence and typed obligations for
[original-source derivations](16-evidence.md); a transformed terminal cannot gain
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

Direct backtrack parameters default to zero private work. Ordinary single-core mixed
clausal satisfaction enables one automatic private trial within the source-size bounds
below. Explicit work settings, worker counts, recipes, heuristic edits and source
annotations retain their selected policy; enumeration, optimization and an explicit
Boolean proof-task request do not activate it.
The automatic source default owns a 4,194,304-byte modeled private grant, separate from
canonical learned-memory scratch. An explicit `private-rebuild-bytes` sets that private
grant; otherwise an explicit learned-memory scratch setting also bounds the private
trial. Direct parameters use `privateRebuildBytes`, falling back to
`learnedMemoryScratchBytes` when null.

An explicit `privateRebuildWork=-1`
selects automatic parity admission with a cumulative 4,194,304-work grant and the
configured scratch grant. It considers only Boolean satisfaction sources with
128–2,000 columns and 512–32,768 factors. Its first unconditional root performs one
funded source scan; admission requires a completed basis of rank at least 128 and
coverage of at least half the source clauses. Mixed views additionally require at
least 16 free coordinates; complete affine views retain their rank and coverage
admission. A miss retains canonical traversal
without root-pin BVE or learned-equivalence retries. Explicit zero disables this
mode; positive work settings retain their bounded manual trials.

`privateRebuildWork` funds bounded private Boolean satisfaction trials. An
unconditional consistent root pauses its canonical traversal, simplifies a private
copy with global pins and applies bounded source reductions with typed reconstruction.
The new baked model has a fresh identity; mappings compose through the pinned,
eliminated and baked objects. Column slots remain stable. A consumer opens after an actual elimination, newly proved parity pins or a completely covered affine view. Assumptions, external components, arithmetic and LP-enabled
workers retain their canonical traversal. Enumeration and optimization do not use
this trial.

Source preparation retains unchanged immutable clauses by reference and copies
shortened or substituted rows. Column counts stay identical, preserving the clauses' plain-Boolean
classification. Each new baked model owns its projection, watches and search state;
the shared factor input carries no mutable search state.

A trial follows global root pins beyond the baked root and the last attempted
view, or owned learned binary implications proving two unfixed variables equal or complementary.
An unchanged root performs no BVE. The root-pin scan charges its full
column visit count and records its time; an insufficient grant skips the scan.
An unchanged root trail retains a completed miss. All attempts share one
cumulative preparation, trial and checking allowance. Root-pin trials use a bounded admission scan that
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

A bounded source scan can recognize two- and three-column parity equations from
their complete two- and four-clause Boolean encodings. It captures the global pin
snapshot before grouping clauses, omits satisfied clauses and removes globally
false literals from their keys. Source column IDs remain unchanged. The same snapshot
conditions the matrix and reconstruction. Each remaining clause excludes one assignment;
the complete parity class of exclusions entails the opposite parity equation.
A preparation-only GF(2) matrix substitutes the canonical global pins and uses
forward elimination from higher source slots followed by affine back substitution
over its free coordinates. Private column-to-row bitsets select pivots and restrict
elimination to rows containing the pivot. Row swaps and XOR updates maintain that
index; its primitive words, reference slots, initialization and visits spend the
same modeled-byte and work grants. The index is discarded with discovery.
This ordering favors earlier slots as free coordinates.
A pivot is globally forced only when its complete free-coordinate dependency
cancels. Additional Boolean pins are emitted only after complete elimination.
Incomplete clause groups, duplicate exclusions and partial matrix work grant no
additional pins. A derived contradiction supplies no source
refutation authority.

The immutable source identity binds the captured pin snapshot. Its table,
matrix and snapshot allocation, initialization, probes, elimination and publication
consume the private work and modeled-memory grants. A dimension-only lower bound
on source entries and table storage can refuse an unfunded source before allocation.
Complete absence of encoded
rows parks the immutable-source scan; a completed miss for new pins may retry
after canonical global pins grow. The private model installs the proved values
as unit clauses and composes its reconstruction over the fixed source view.
Canonical search state stays unchanged. A changed fixed view can open a consumer
without an additional BVE pivot; its pins are counted separately from eliminated
columns. Closing or declining releases the captured snapshot with its generation.

When each source clause belongs to a complete parity encoding, is tautological or
is satisfied by captured global root facts, the rebuilt model can replace the encoded equations
with their free-coordinate view. Source column slots remain stable; a private
pivot slot is fixed to a placeholder and has no canonical literal authority.
Reconstruction evaluates the owned affine basis from the private free-coordinate
values, preserving every source solution rather than choosing a source constant.
The coverage scan charges every additional literal check. Reconstruction retains
the captured global values that justify satisfied clauses. The complete coverage
proof is required before dropping any encoded clause.
Uncovered clauses prevent the placeholder view. Canonical learned clauses are
withheld from the affine view because their pivot literals lack a supported
projection. The containing grants cover the basis and reconstruction; every
consumer point still reconstructs and checks the complete canonical source.

A mixed source can retain its uncovered clauses and replace only proved parity
encodings with their short source equations and an independent reduced XOR basis.
A complete triangular basis retains the forward-eliminated matrix and its original
right-hand sides. For each pivot the mixed view selects the shorter bounded equation
from that system or its free-coordinate expansion. Distinct pivots and dependencies
on lower coordinates keep the selected system complete, while short expanded rows
retain their early deductions. Comparison, row choices, snapshot references and
right-hand sides debit the containing work and byte grants.
The source equations retain local unit propagation that expansion over free coordinates
can weaken. Their compact captured encodings and materialized rows spend the same
private byte and work grants. Coverage records each original
clause's excluded assignment, so extra exclusions in an otherwise complete group
remain constraints. Each replacement row has at most 64 distinct coordinates;
unsupported widths decline the row view. Row arrays, coefficient projections and
fresh worker indexes share the containing work and modeled-memory grants.
A funded mixed consumer owns a residual affine expression for every source coordinate.
Each independent assigned source fact eliminates one free coordinate from all expressions
that contain it. The state owns flat coefficient and reason buffers with cached nonzero-word
counts. Zero residuals imply source values; contradictory assigned values supply conflicts.
Independent assigned facts supply clause reasons with at most 64 literals; wider pin reasons
withhold the deduction. Undo or an interrupted update invalidates the cache, which rebuilds
from the immutable seed and current assignments. Expression updates and checks spend the
containing propagation-work grant.
The immutable seed owns its snapshot, while each session owns its matrix and
primitive observations. Buffer initialization and every scan, reduction and reason
visit debit propagation work. A fresh session exposes its live propagation counter
before its root fixpoint, so work during consumer opening shares the same private
and containing stop conditions. Stopped opening work remains charged once. Modeled seed, matrix, observation and watch storage
share the private byte grant after selector reservation; an unfunded joint view
retains the per-row system. Retirement copies primitive observations before releasing
the private state.
The mixed view preserves original coordinate meanings, including its live pivots,
and can retain supported canonical warm clauses through the fixed-value projection.
Its reduced rows and covered clauses are counted separately from eliminated columns.
For source spans fitting two scans inside the normalized per-decision charge, the
private consumer prefers free coordinates with its own VSIDS conflict activity.
A filtered branching view preserves source IDs and passes conflict and unassign
notifications to that private heuristic. When no free coordinate needs a decision,
source-order fallback retains every remaining pivot decision. Pivots retain their
original meanings and propagation still decides their values. The membership mask
and activity heap spend the modeled byte grant; their initialization spends opening
work before allocation. A grant that funds the generation without that extra storage
retains source-ordered free-coordinate selection. Fresh consumers own fresh activity.
Larger spans retain the configured selector.

A learned binary can close a reciprocal source binary, or two owned binaries can
supply the pair. Only non-permanent global learning from the canonical worker
qualifies. Discovery charges retained slot visits and skips logically retired entries
before reading their literals. The capture binds its exact source object and owns its clauses; preparation
rechecks that its columns remain unfixed. Global consequences remain valid after
their canonical learned entries are reclaimed. The pinned source retains both
owned premises. A signed substitution replaces their higher-numbered column with
the retained column at the captured polarity and records `CopyLiteral` reconstruction.
The premise objects must belong to that exact pinned source. Unchanged clauses
retain their immutable buffers; affected clauses own normalized copies. Factor and
literal visits, replacement construction and delta ownership consume the local
pass allowance. An interrupted substitution publishes no changed view. Warm
consequences follow the same signed reconstruction without changing canonical coordinates. Discovery visits recent learning first, scanning at most 128 eligible binaries and
32,768 work units per root observation, inside the cumulative trial allowance.
Reciprocal matching uses a temporary fixed-capacity key table and one source walk
for the retained learned keys. Table initialization and probes consume discovery work.
Changed learned counts or compaction revisions permit another scan. A fixed history
remembers at most 32 attempted relations; its storage and 16 KiB discovery scratch
consume the existing byte grant. Exhausted scans establish no absence of other
equivalences.

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
Only this canonical worker's non-permanent clauses over Boolean source columns
qualify. Global pins remove satisfied clauses and false literals. A clause that
mentions an eliminated column can follow equivalent-literal substitutions and actual BVE parent descriptors in
elimination order. A pure pivot's satisfying value lifts every projected model,
so substituting it preserves learned consequences. Other pivots use one shortest
opposite-polarity source parent to derive a resolvent; each such consequence is
entailed by the projected source. The transfer produces at most one clause per
learned input and admits at most 64 literals per intermediate clause. A tautological
or wider selected resolvent withholds that input, without excluding other useful
translations. Every result contains only retained columns. An empty consequence
declines the generation and grants no source refutation authority.

Parent indexing, literal visits and normalization consume the transfer allowance.
Final clause-input normalization reserves work from the resulting literal count
before constructing its owned factor, including when resolution widens the input.
Projection reserves 16 KiB of modeled scratch inside the remaining private byte
grant; its temporary buffers are released after transfer. This reservation joins
the conservative private peak model, including when a projection copies nothing.
Each copied consequence owns its literal buffer and consumes the same
work and modeled-memory grants; an exhausted local transfer grant retains the
completed subset. The mapping composes through the enriched model's exact identity.
These copies preserve consequences without sharing learned-store indexes or
exchange cursors and survive canonical forgetting. Their work and time are
preparation subsets; private baking and propagation include the added clauses.

The private worker owns fresh watches, learning and search state. Canonical exchange
cursors and pooled incumbent guidance stay with the retained canonical worker;
unsupported translations never attach to the private model. Every private witness
reconstructs and completes a canonical clause check before publication. Partial
checks retain their cursor across slices. Private refutations grant no canonical
proof authority and resume the retained traversal. Declines and exhausted trials
also resume it. Closing releases the private consumer, reconstruction and cursors
after its final drains. Preparation, trial, reconstruction and checks consume the
original containing deadline/work; cooperative overspend carries to later slices.
Discovery and preparation debit the private coordinator's ledger. The canonical
state's propagation and inprocessing counters retain its own engine visits.
Converted private work joins consumer work in the containing slice budget and overspend debt.
Retired consumers retain their converted decision and propagation work separately;
the charged-work total includes preparation, trials and canonical checks.
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
Scans enforce quota independently of cancellation polling and share their containing
worker's checkpoint countdown; accounting does not change that cadence.
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
[CLI statistics](24-cli.md#statistics) expose bounded lane/pass/outcome and
preparation-stage summaries of this ledger, including preparation-only requests.

Stop observation reuses a directly observed token only for its owning budget and
records interruption on the active invocation at polling time. Composed tokens keep
their added predicates even when their meter is shared. The source pipeline binds
the elective phase allowance; required encoder selection keeps the containing token.

---

Previous: [6. Sessions and resources](06-sessions.md) · [Contents](README.md) · Next: [8. Presolve reductions and validation](08-presolve-reductions.md)
