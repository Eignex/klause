# 8. Presolve reductions and validation

The preparation pipeline applies reductions under declared preservation guarantees.
This chapter describes the reductions and the source checks required before publication.

## Selected reductions

The passes listed under [Pipeline](07-presolve.md#pipeline) declare the
[preservation guarantees](07-presolve.md#guarantees-and-source-mappings) they keep;
the families below describe their premises. In both lanes a pass polls
after entry charging and meters factor, column, term, signature and rewrite visits
against the pass token, within fixed size and visit limits. Bound fusion,
duplicate-column and subsumption scans accumulate charges and poll at bounded
checkpoints; native allowance-backed scans reserve credit before advancing and settle
used credit at publication and exit; standalone scans poll at entry and before
publishing. Interruption keeps completed sound reductions and discards unfinished
analysis: duplicate-column aggregation, bound fusion and source duplicate/domination
indexing discard unfinished transactions, and structural reduction keeps only fully
collected factor rewrites and their bound tightenings, with factor hooks and
structural-key construction atomic.

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
are cleared. Nonzero source feasibility tolerance excludes the pass wherever the slack can
relax a row: always for a source without a float interpretation, and for a source that types
its floats once it has a real coordinate. It drops only factors whose
whole meaning is one redundant equality; conjunctions and relaxations can supply
premises but remain intact. Binary partition equations retain their propagation and
branching structure, including scaled equations equivalent to `sum(x_i) = 1` over
declared integer bounds within zero and one. They still supply elimination premises.
Guarded or disjunctive rows are excluded. Row width, constant bit length and block
entries are bounded, and sparse elimination bounds row fill, total entries and
intermediate product bit lengths by the same limits; an exceeded bound discards the
unfinished elimination. Candidate and term visits
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
preserving solution multiplicity. Admission bounds scope size, retained choices,
tuple cells and charged construction visits, and interrupted construction publishes
no partial substitution.

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

A call attempts a bounded number of cyclic projections, each with bounded visits and
stored key/support cells. Scope selection, indexing, support intersections and row
copies share the call allowance. Incomplete projections publish nothing. Ordinary
Table propagation, explanations and undo consume completed restrictions; there is no
retained cluster propagator.

Each composition attempt bounds scope width, table cells, diagram and product states
and records, and visits, and each call bounds total visits and retained cells. A
metered variable-occurrence index selects overlapping tables. Visits charge
and poll the invocation token; declined payloads incur no payload-wide entry charge.
An unfinished intersection
publishes nothing, while completed replacements survive a later interruption.
Dead product states are removed before publication; a diagram already satisfying
the projection stays unchanged, preventing repeated root products.

Structural reduction calls each factor's `structuralReduce` hook under current
domains; source execution restricts this to closed integer columns. Both lanes skip
the final `Clause` type's unchanged hook before visiting its columns, retaining the
factor-level charge and cancellation poll. Other factor types retain their hooks.
Small ground tables allocate survivor storage on the first rejected tuple,
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
Normalized rows are kept per factor occurrence across calls. Each call bounds row
width, normalization and comparison visits and retained failed pairs; upkeep linear in
the current rows and candidates is charged outside that cap, so bounded calls over an
unchanged model reach their comparisons.
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
queries, including counting, enumeration and sampling. Admission bounds integer
columns, candidate spans, Table scopes and payloads, and a bounded reader-entry and
visit allowance covers occurrence construction, candidate pairs, tuple-cell
comparisons and domain copies. An unfinished call publishes no exclusion; its work
remains charged.

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
pivot is bounded in resolvent pairs, width and visits; an unfinished pivot publishes
nothing. A completed empty resolvent immediately refutes the model in
either lane without materializing an empty factor, even if later work is cancelled.
Blocked-clause elimination removes clauses whose resolvents on one literal are all
tautologies. Each clause attempt has a bounded visit count across candidates,
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
incremental root retains completed exclusions across probes. A fixed initial credit
funds the first calls and each failed probe earns more; probes and repairs share this
credit within the configured per-column and total caps, and the pilot stops at a fixed
fraction of the remaining phase work, itself capped. Bounds precede
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
and within scans at a fixed visit or work stride. An interrupted scan can spend at
most one polling stride beyond a stop; hypothetical retraction always completes.
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
first, and every accepted tightening requires an LP bound
([numerical or exact](21-lp-engine.md#numerical-and-exact-deductions)).

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
The rewrite-cost policy also projects binary costs within a mixed integer
objective when existing signed channels carry them exactly. It retains nonbinary
costs and any binary cost whose channel reuse would overflow, protecting their
integer columns from substitution. This mixed view adds no Boolean channels and
does not carry the pure binary-objective LP admission marker.
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

`solver.validation` checks coordinates that no engine enforced against original source
obligations: caller-supplied starting points, relations an engine cannot propagate or
score, transformed float-grid points and arithmetic completions. Integer search trusts
its own propagation and does not re-derive its leaves. A rejection refutes only the
point, and an interrupted check is indeterminate.

### Finite points

`FinitePointCheck` makes the full Boolean and finite integer point the private root of
a disposable propagation state and checks the selected original-source relations.
Caller pins and deduced restrictions are checked on the point itself. Built-in
propagators and occurrence indexes are shared across a checker's points. Prepared
custom providers keep their selected factories and required original checks.
Pure Boolean clause points use native clause propagation when the selected
factories permit it, without constructing the general watcher index.
Cancellation or an unavailable required check returns indeterminate, and every exit
reports its propagation work to the caller.

Local search checks the relations it cannot score this way before publishing a
zero-violation candidate. A baked source with closed domains reuses one baked view of
those relations; open declarations and values outside the declared domains get
singleton domains for each candidate, so the relations alone decide it.
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
When the arithmetic route has no selected finite propagator, a private point check
can use a built-in original relation with exact pinned-check authority. This does
not install a finite search route. A missing complete original checker remains
indeterminate, without opening an unavailable propagator.

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
uses the full original real graph. Nonzero tolerance reads every relation over a real
coordinate as [FlatZinc floats](04-finite-formats.md#flatzinc-floats) describe: rows
and residual readings hold within the slack, while strict rows, disequalities and rows
whose reifying Boolean is false hold exactly. A private-grid relation cannot
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

Previous: [7. Presolve preparation](07-presolve.md) · [Contents](README.md) · Next: [9. Search and propagation](09-search.md)
