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
so the engine never depends on presolve. Finite rounds follow.

Source and finite rounds share one bounded-fixpoint scheduler. A pass above the
cheapest tier reruns only after other passes changed at least 1% of the model and met
its observed cost/yield threshold: charged analysis and rebuild work, divided by the
pass's entry weight (unit weight for self-metered passes) and retained yield,
estimates the intervening changes needed before another analysis. Yield is the
largest of net occurrences removed, factors removed, reported columns narrowed or
columns fixed, with a floor of one; distinct new Boolean unit pins count as fixed
columns, existing units and finite root fixings do not. Empty and interrupted calls
keep their cost, and only completed misses can park a pass. Unmetered calls use the
1% threshold without accounting scans.

Three passes track the premises of a completed miss instead, in both lanes, and wake
when a relevant revision changes while unrelated factor, pin and range changes keep
them parked: parity elimination watches XOR and integral equality rows, participating
Boolean pins and odd-coefficient integer ranges; coefficient strengthening watches
implied integral equalities, complete integer or Boolean rows and participating
integer inequality endpoints; opt-in row sparsification watches complete integral
rows only. Tracking reads changed factors and participating columns, charges the
phase allowance, stops on its cancellation, is bounded by live participating columns
and dies with its lane; interrupted tracking discards its state and publishes no
revision. A changed revision establishes eligibility, not an obligation to spend, and
other parked analyses keep their completed misses. These signals are deterministic
spending heuristics, not predictions or proofs of search savings, and cancellation
and phase allowances still bound optional work.

Presolve and an LP-relaxation harvest iterate to a joint fixpoint for at most four
rounds, the harvest rerunning only when it tightened something. Automatic harvest
skips clausal satisfaction unless rewrites expose cardinalities with stronger LP rows,
and propagator-only symmetry does not change that eligibility; explicit
`+lp-harvest`, nonclausal sources and optimization keep it.
[Wide-integer pre-bake bounds](08-presolve-reductions.md#pre-bake-lp-bounds) use a separate safety gate.

The finite invocation carries its source-template policy through pre-bake LP checks,
harvest and the consuming workers; disabling templates keeps those LP calls and their
allowances but skips template capture and use. Harvest eagerly prepares compatible
immutable source-row and exact-bound templates, and the selected projections carry
them across finite rebuilds: unchanged definitions keep their buffers, changed
bounds, rows, objectives and component joins replace the affected component
snapshots, and namespace remaps and semantic-scope changes require fresh bindings.
Later LP consumers instantiate their own mutable owners from these definitions; see
[LP preparation](20-linear-programming.md#exact-source-row-projections). A no-op
preparation keeps the canonical model handle, and the later engine bake rebinds the
shared finite-template seed to its own source and checks its current bounds before
reuse. Template construction spends the same allowance, unfinished preparation
publishes no reusable state, and reuse changes assembly work only, not pass
admission, probe selection, reconstruction guarantees or source authority.

The round-boundary diminishing-return check counts live factors plus integer-domain
endpoint spans, saturating at `Long.MAX_VALUE` in incremental and rebuilt hosts
alike, so saturation can hide reductions; the source lane counts live factors only. A
round below the abort fraction (0.001 by default) ends iteration; this structural
heuristic supplies no runtime guarantee.

| Family | Passes |
|---|---|
| Linear | Coefficient strengthening, Diophantine tightening, bound fusion, affine elimination, sub-sum aggregation, row sparsification, dependent equalities, singleton projection |
| Structural | Redundancy and subsumption, structural reduction, extensional composition, comparison-clause folding, duplicate-column merging, dual fixing, table value substitution |
| Boolean | Original-clause subsumption, implication graph, bounded variable elimination, blocked clause elimination, at-most-one clique merging, XOR unit derivation |
| Symmetry | Symmetry breaking, objective symmetry and value precedence |
| Probing | Failed literals, integer bounds and holes, open ranges |
| Around the rounds | Tuple packing, binary column substitution, LP harvest, difference-system posting |

`PresolveEmphasis` selects elective cost tiers: `off` disables them, `conservative`
applies the cheap tier once, `default` iterates cheap and moderate tiers to a
fixpoint and `aggressive` adds the exhaustive tier (probing, implication graph,
bounded variable and blocked-clause elimination, LP harvest), all within 16 rounds. Passes can
be enabled or disabled by name, but a pass that would break the query's required
guarantee stays disabled. Explicitly enabling a problem pass under `off` gives both
schedules the default round cap; an explicit zero round cap still suppresses them.

### Rewrite representation controls

Affine elimination orders pivots by estimated fill (Markowitz) by default.
`AffinePivotOrder.REWRITE_COST` keeps that order between definitions but tries an
equality's unit pivots by smallest rewrite payload: the sum of the current incident
factors' `structuralKeyWeight`, excluding the definition, read through the maintained
occurrence index, with ties in declaration order. Scoring charges and polls the phase
allowance and never builds factor keys or speculative replacements. The ordinary
admissibility, domain, objective and reconstruction checks apply to the preferred
pivot, a rejected pivot falls back to the first admitted declaration-order pivot, and
an equality without an admitted pivot is not scored. The estimate describes
construction size; downstream search still determines total cost.

The same policy can fold an asserted integer definition through word-sized
`ReifiedLinear` consumers, keeping the channel's Boolean output and comparison in
both directions; conditional equalities never supply asserted pivots, and coefficient
checks, the per-row absorption cap and carried domain bounds apply as for
unconditional rows. Single-partner unit translations and sign reversals also keep
native `ComparisonClause` disjunctions, translating every affected bound and reversing
the inequality for a negative scale; an unrepresentable bound declines the pivot.
Other pivot policies keep their consumer admission.

`PresolveConfig.preserveStructure` is an opt-in performance control. Structured
factors are all factors except plain `Linear` and `Clause`; binary partition rows
have equal nonzero coefficients and right-hand side over declared integer bounds
within `0..1`. Affine elimination protects every integer coordinate of these factors,
including aliases and exact objective translations, and structural reduction keeps
them; unrelated linear reductions stay eligible. Protection discovery charges and
polls the phase allowance, and an incomplete discovery publishes no affine
elimination.

`structuralImpactLimit` enables a bounded delta view for affine, structural and
extensional reductions; zero disables all its scans. Its visits, capped per
productive invocation including reconstruction, charge the containing phase
allowance. It counts removed and added factors, structured factors, binary partition
rows, mixed Boolean/integer factors, Table cells, support width, integral declared
linear nonzeros and equality definition inputs (non-integral or wide rows are
explicitly uncounted), and reports affine reconstruction definitions, input edges and
recovery depth. A partial view carries `Complete=0`, and a diagnostic stop keeps the
already completed sound delta. An absent counter is zero only in a complete view; an
absent view is unobserved, and a partial view cannot establish a zero count.

These counts are proxies: mixed-factor incidence for channels, support width
for explanation scope, equality inputs and depth for definition cones. They
do not establish a worker's installed channels, actual reasons, local-search
sweep or emitted LP nonzeros. Neither `preserveStructure` nor the impact view
changes correctness admission, query guarantees or source reconstruction, or
suppresses other passes. A smaller representation alone does not qualify a default
policy change.

## Guarantees and source mappings

`PresolvePass.guarantees` separates equisatisfiability, objective preservation,
witness reconstruction, bijection and complete coverage by reconstructed source
assignments. Objective preservation requires protecting objective columns or
carrying an exact replacement objective with the factor delta. A satisfiability
reduction can keep an optimum while losing source multiplicity, so successful
reconstruction does not establish a bijection.

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
open columns, approximate semantics and cyclic expansion. Each attempt is bounded
in integer slots, surviving terms, depth and charged visits.
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

A private rebuild lets a single-core clausal satisfaction worker continue on a
reduced private copy of its source while the canonical worker keeps its state.
`rootRebuildCaptureVisits` first enables a bounded propositional opportunity view
inside root simplification; zero disables it. The view accepts only a baked Boolean
clause model at an unconditional, consistent fixpoint, since assumptions, scoped
assertions, conditional cutoffs and unfinished propagation supply no global
authority. Over the identical canonical factor objects it separates newly fixed
columns and clause removal or shortening from newly eligible elimination
candidates: an unfixed column with positive and negative occurrence counts `p` and
`n` qualifies when `0 < p + n` and `p * n <= p + n`, counted only if it failed that
bound at the baked root. This is a sufficient clause-count proxy for one elimination,
not a resolvent construction, admission decision or prediction of search savings;
learned clauses are excluded from it. The capture shares root-class and containing
inprocessing work, cancellation and debt, keeps no array beyond the call, and an
incomplete view withholds all candidate totals. An unsupported or partial
observation cannot establish absence of opportunities, and the view itself creates
no replacement or publication authority.

Direct backtrack parameters default to zero private work. Ordinary single-core mixed
clausal satisfaction enables one automatic private trial within fixed source-size
bounds; explicit work settings, worker counts, recipes, heuristic edits and source
annotations keep their selected policy, and enumeration, optimization and an
explicit Boolean proof-task request do not activate it. The automatic default owns a
fixed modeled private byte grant separate from canonical learned-memory scratch:
`private-rebuild-bytes` sets it, otherwise an explicit learned-memory scratch setting
also bounds the trial; direct parameters use `privateRebuildBytes`, falling back to
`learnedMemoryScratchBytes`. `privateRebuildWork=-1` selects that automatic parity
admission with a fixed cumulative work grant: its first unconditional root performs
one funded source scan, and admission requires a completed basis of rank at least
128 covering at least half the source clauses (mixed views also need 16 free
coordinates). A miss keeps canonical traversal without root-pin elimination or
learned-equivalence retries. Zero disables trials, and positive values fund bounded
manual trials.

A trial starts at an unconditional consistent root: it pauses canonical traversal,
simplifies a private copy with global pins and applies bounded source reductions with
typed reconstruction. The new baked model has a fresh identity, mappings compose
through the pinned, eliminated and baked objects, and column slots stay stable.
Unchanged immutable clauses are kept by reference and changed rows are copied, so
plain-Boolean classification is preserved; each baked model owns its projection,
watches and search state. A consumer opens only after an actual elimination, newly
proved parity pins or a completely covered affine view. Assumptions, external
components, arithmetic and LP-enabled workers keep their canonical traversal, and
enumeration and optimization never use a trial.

A trial follows global root pins beyond the baked root and the last attempted view,
or owned learned binary equivalences. An unchanged root performs no elimination and
keeps its completed miss, and an insufficient grant skips the root-pin scan. All
attempts share one cumulative preparation, trial and checking allowance. Root-pin
elimination admits only a non-growing raw resolution shape, measured with saturated
occurrence counts and parent literal volumes; tautology and duplicate savings can
make other pivots useful, so a refusal establishes no absence of opportunities.
Refused columns stay protected for that view, and elimination is restricted to the
initially admitted columns, so an unrelated declined pivot cannot consume the
allowance. When one column is admitted, the pass works on its complete
incident-clause neighbourhood, which commutes with the untouched rows, and transports
dropped local factor indices back to the full source; an incomplete scan publishes no
view. Elimination receives half the allowance left after source and neighbourhood
preparation; its local cap keeps complete earlier pivots, and cancellation of the
containing call declines the generation. Baking, opening and canonical checks share
the remainder, and reconstruction charges the larger of the source-entry model and a
bound covering every composed sample copy and eliminated-clause replay.

#### Parity views

A bounded source scan recognizes two- and three-column parity equations from their
complete two- and four-clause Boolean encodings, over a global pin snapshot captured
before grouping and bound to the immutable source identity; satisfied clauses are
omitted and false literals removed from their keys. Each remaining clause excludes
one assignment, and the complete parity class of exclusions entails the opposite
parity equation. A preparation-only GF(2) matrix substitutes the captured pins and
eliminates forward from higher source slots, then back-substitutes over its free
coordinates. A pivot is globally forced only when its complete free-coordinate
dependency cancels, and pins are emitted only after complete elimination; incomplete
clause groups, duplicate exclusions and partial matrix work grant none, and a
derived contradiction supplies no source refutation authority.  Complete absence
of encoded rows parks the scan; a completed miss can retry after canonical global
pins grow. The private model installs proved values as unit clauses and composes
its reconstruction over the fixed source view; canonical search state stays
unchanged. Closing or declining releases the snapshot with its generation.

When every source clause belongs to a complete parity encoding, is tautological or is
satisfied by captured global facts, the rebuilt model can replace the encoded
equations with their free-coordinate view. A private pivot slot is fixed to a
placeholder with no canonical literal authority, and reconstruction evaluates the
owned affine basis from the free coordinates, preserving every source solution. The
complete coverage proof is required before any encoded clause is dropped, and
canonical learned clauses are withheld because their pivot literals lack a supported
projection.

A mixed source instead keeps its uncovered clauses and replaces only proved parity
encodings with short source equations and an independent reduced XOR basis, choosing
per pivot the shorter of the triangular system row and its free-coordinate expansion
so that local unit propagation survives. Coverage records each original clause's
excluded assignment, so extra exclusions in an otherwise complete group remain
constraints. Each replacement row has at most 64 coordinates; wider rows decline
the row view. A funded mixed consumer owns a residual affine expression for every
source coordinate: each independent assigned fact eliminates one free coordinate,
zero residuals imply source values and contradictory values supply conflicts.
Reasons have at most 64 literals, and wider pin reasons withhold the deduction.
Undo or an interrupted update invalidates the cache, which rebuilds from the
immutable seed and current assignments; the seed is shared, while each session
owns its matrix and observations, and all updates spend propagation work. An
unfunded joint view keeps the per-row system. Unlike the affine view, the mixed
view keeps original coordinate meanings, including its live pivots, so it can keep
supported canonical warm clauses through the fixed-value projection. For small
spans the consumer branches on free coordinates by its own conflict activity,
through a filtered view that preserves source IDs; pivots keep their meanings
and propagation decides them, and larger spans keep the configured selector.

#### Learned transfer

A learned binary that closes a reciprocal source binary, or two owned binaries, can
prove two unfixed columns equal or complementary. Only non-permanent global learning
from the canonical worker qualifies; the capture binds its exact source object, owns
its clauses and rechecks that both columns remain unfixed, and its consequences stay
valid after the canonical entries are reclaimed. A signed substitution replaces the
higher-numbered column by the retained one with `CopyLiteral` reconstruction, and an
interrupted substitution publishes no view. Discovery is bounded per root
observation, remembers a bounded history of attempted relations, and retries only
after learned counts or compaction revisions change; exhausted scans establish no
absence of other equivalences.

Unused elimination allowance can copy the canonical worker's non-permanent global
learned clauses over Boolean source columns into the private model, after global pins
remove satisfied clauses and false literals. A clause mentioning an eliminated column
follows equivalent-literal substitutions and the actual elimination parents in order:
a pure pivot's satisfying value lifts every projected model, and other pivots resolve
with one shortest opposite-polarity parent, so each result is entailed by the projected
source. Each input yields at most one bounded-width clause over retained columns;
a tautological or wider resolvent withholds only that input, and an empty consequence
declines the generation without source refutation authority. Copies own their
buffers, share no learned-store indexes or exchange cursors, survive canonical
forgetting, and an exhausted transfer grant keeps the completed subset.

#### Private worker contract

The private worker owns fresh watches, learning and search state; canonical exchange
cursors and pooled incumbent guidance stay with the retained canonical worker, and
unsupported translations never attach to the private model. Every private witness
reconstructs and completes a canonical clause check before publication, with partial
checks keeping their cursor across slices. Private refutations grant no canonical
proof authority and, like declines and exhausted trials, resume the retained
traversal. Closing releases the private consumer, reconstruction and cursors after
their final drains. Preparation, trials, reconstruction and checks spend the original
containing deadline and work, cooperative overspend carries to later slices, and the
charged-work total includes all of them. The scratch allowance gates a declared
private buffer and reference-slot model that excludes object headers, the canonical
worker and allocator overhead.

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
- Immutable LP templates keep their namespace, raw/finite, scope, row, bound,
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

A smaller model, template hit or successful scan establishes neither net benefit nor
permission to enable rebuilding: the cost includes reconstruction and checking,
worker reopening, lost warm state, overlapping memory and obsolete-generation
cleanup, and missing costs remain unobserved.

### Finite reconstruction

Finite presolve reconstructs original integer, Boolean and real coordinates before
source checking. A reconstruction belongs to its original source and prepared model;
a foreign coordinate frame cannot reuse it.

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
bakes, finite-session construction/materialization, source encoder rebuilding,
finite exact-real difference assembly and source descriptor selection, row
installation and ownership, including with elective passes disabled.
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
