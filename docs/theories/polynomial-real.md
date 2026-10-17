# Polynomial real arithmetic

The polynomial service checks nonlinear real constraints exactly and completes
continuous coordinates beside finite search. Backtracking, local search, ALNS and
portfolios can ask it to check the same source constraints under their current
finite assignments. Real values remain theory-owned; CP remains Bool+Int only.

## Scope and boundaries

The complete arithmetic fragment is polynomial real satisfaction with rational
coefficients and Boolean structure, also called QF_NRA. Shared composition supports
finite integer domains and exact quadratic objectives over Boolean, integer and
real source coordinates. Open integer domains and transcendental functions remain
outside this route.
Resource limits can interrupt even a complete procedure.

This theory follows the [shared theory contract](../theories.md). The sections use
its common scope, composition, result and lifetime structure before the detailed
CAD procedure. [Input semantics](../formats/smtlib.md)
owns source admission; [exact polynomial arithmetic](../arithmetic/polynomial.md)
owns mathematical operations; [search](../solver/search.md) owns finite traversal.

## Solving and composition

### Shared model composition

`PreparedSourcePlan` owns the shared source decomposition. Its captured
`SharedPolynomialModel` installs required selected polynomial encoders and connected
finite encoders, retaining original factor indices and integer embeddings for every
strategy. An encoder-only finite relation can select this route without a native
product factor. A finite filter and a complete encoder can participate together;
required peer checking replaces unavailable duplicate native point checks only in
that composed invocation. Standalone finite projections keep their own source-check
obligations. Integer columns appearing in the encoded equations receive symbolic
real coordinates for conditioned queries; their source finite domains and global
constraints remain in CP. Exact linear rows, Boolean reification, real bounds and
integer/real products and `ContinuousProduct` become neutral polynomial formulas through
the captured encoder provider. Generic equivalent row encoding belongs in `lowering`.
Typed factor attribution records integer embeddings and private defining auxiliaries.
Only participating integers allocate numeric slots; unrelated finite variables retain
finite ownership and receive no reserved numeric coordinate. AllDifferent, scheduling
and other finite factors remain intact in the finite projection. Linear relaxation
rows cannot stand in for a factor's semantics. Binary64 constants denote their exact
binary rational values. Equal finite real bounds remain source equations and are
substituted before projection, retaining their exact coordinate in captured models.
`Problem.pipelineRoute()` consumes the same prepared plan for polynomial, finite
and linear route construction. Polynomial requests and quadratic execution resolve
the captured completion data from that plan. A factor scope containing open integer
declarations can instead select the [nonlinear integer adapter](nonlinear-integer.md)
when every factor has a complete selected polynomial encoding. Missing relations or
required conditions produce a preparation decline; open integers are not boxed.
Source ownership is selected without the objective, then the original objective and
direction are bound to the same plan.
Affine continuous costs use the existing exact quadratic executor without discretization.

Backtracking checks interval relaxations and fixed finite branches. Local search
generates candidates from finite factors, rechecks their pinned finite fixpoint, then
pins their complete finite values in a theory query before publication. ALNS uses that same completion in bootstrap,
finite CP repair and its inner local search. Pure-real sources and fixed finite
projections use exact coordinate neighborhoods with the same source witness authority.
Rejected heuristic candidates never establish a global refutation.
The finite projection can request `CandidateCompletion` even without a continuous
assignment buffer. Rejected or indeterminate completions take the ordinary restart
path, while finite factor degrees guide the walk. Native theory allowance persists
across queries and neighbourhood pins.

Coordinate neighborhoods first check a pinned proposal, then relax coordinate or
block windows while preserving every source constraint. Rational centers derived
from isolating intervals guide proposals; published coordinates retain their exact
algebraic scalars. Native work stays on the containing invocation across repairs.
ALNS bootstraps within the heuristic allowance before spending its configured
improving-iteration cap.

`OpenTheoryPipeline.minimize` supports objectives over the finite columns using the
same projection, theory and objective in every worker. Complete traversal can prove
an optimum or infeasibility; local search and ALNS retain exact incumbents without
proof admission. The separate quadratic overload handles continuous quadratic
objectives; all-solution enumeration remains outside these interfaces. [Search](../solver/search.md), [local search](../solver/local-search.md),
[portfolio](../solver/portfolio.md) and [sessions](../solver/sessions.md) own their execution lifecycles.

`OpenTheoryPipeline.optimize` normalizes affine `LinearObjective` values losslessly
and returns the precise quadratic result surface. It retains the original caller
objective and source snapshot in an immutable operation request. The finite-result
`minimize(LinearObjective)` surface retains its discrete-objective contract.
Source frontend optimization uses the exact publisher, including strict limits,
source-direction bounds and algebraic objective values.

The sequential mixed pool shares one finite projection and immutable polynomial
source scope across complete backtracking, finite local search and ALNS
bootstrap/repair workers. Only the complete worker receives proof admission.
The containing theory invocation closes after portfolio handles and sessions.

### Conditioned theory service

`PolynomialTheoryService` captures one immutable neutral formula and its coordinate
counts. Numeric source selections retain their result, guard and branch expressions
in this formula. Polynomial normalization privately installs both guarded equality
alternatives under the invocation's native work and cancellation controls; the
source snapshot remains unchanged. Distinctness retains its ordered numeric operands;
query normalization constructs exact disequalities for every pair under that same
arithmetic allowance, without publishing a partial normalized formula.
Each `PolynomialTheoryInvocation` owns a cumulative native work allowance
across fresh conditioned checks. Boolean pins and rational lower/upper bounds are
conjoined within the counted arithmetic scope. Query construction, normalization,
CAD, exact witness capture and the final verdict poll share cancellation and allocation
limits. Closing the invocation rejects further queries; captured scalar witnesses
remain readable. Invocations share no mutable traversal or refinement state.

`PolynomialSearchComponent` consumes shared Boolean facts and semantic integer bounds.
Integer columns map to theory coordinates without creating continuous CP domains.
A query over an unfixed integer interval is a real relaxation: its exact refutation
can reject the finite branch, while its witness cannot accept that branch. Complete
checks require every mapped integer and Boolean to be fixed. Conflicts without a
clause explanation remain chronological and cannot enter learned-clause exchange.
Retraction invalidates query results; the containing invocation allowance persists.
Optimization installs additional component factories before its first shared
check. Finite LP primal proposals and transferred initial candidates remain
withheld until those components complete their source checks.

### Quadratic strategy execution

The quadratic overload of `OpenTheoryPipeline.minimize` uses the same source
request, finite projection and polynomial formula. Complete CP traversal visits
every finite assignment and completes its continuous objective exactly. Branch
optima and unattained limits combine in source objective order; equal limits
prefer attainment. An unknown branch withholds global optimality and
infeasibility. A checked improving unbounded branch is sufficient for source
unboundedness. Original objective direction is restored before publication.

Unconditional variable/constant source equalities are intersected with every
conditioned objective check and substituted during projection. They also seed
heuristic centers. Disjunctions and guarded equalities provide no unconditional
fixing. The neighborhood coordinate set contains original source declarations;
conditional auxiliaries remain completion-owned after source capture.

Local search proposes coordinate releases and expanding exact rational windows.
ALNS adapts release sizes from checked improvements. Each repair enumerates finite
assignments inside its neighborhood and uses exact continuous completion; source
constraints and assumptions remain mandatory. Algebraic coordinates retain their
isolating enclosures instead of being rounded to points. Neighborhood exhaustion
is not a source refutation. MIXED runs a bounded adaptive phase before complete
traversal, sharing checked incumbents and one native ledger. Its workers require
identical effective assumptions and deductions.

Termwise exact box arithmetic can supply a global bound from source declarations
and invocation pins before feasibility is known. It preserves a square's repeated
coordinate correlation, and declines an unbounded referenced coordinate. These
bounds can survive incomplete search independently of a checked incumbent.

### Objective optimization adapters

The internal `solvePolynomialObjectiveImage` service uses the same polynomial IR,
projection kernel and exact witnesses to optimize an objective over a conditioned
real/Boolean source. Source integer integrality and finite factors remain caller
obligations; this real service cannot accept a relaxation witness as an integer
assignment. The source pipeline owns integrality, finite constraints and global
branch coverage around these mechanisms.

`tryConvexPolynomialOptimum` independently admits conjunctions of affine source
rows and a quadratic objective. It retains exact row direction and strictness,
uses the [numerical candidate and certificate kernel](../arithmetic/quadratic.md),
then checks the original source formula and objective again. Candidate selection
is bounded and incomplete; a declined convex candidate leaves the image service
available. Nonlinear constraints, disjunctions and nonconvex objectives cannot
receive a convex certificate from this adapter.

The complete query can install an optional elimination observer for phase diagnostics.
It receives the selected coordinate, its boundary family and any selected required
equation before ordinary projection. It is disabled by default and does not select
operations or charge native work. Diagnostics retain read-only family references and
inspect their metadata after the query stops. A separate optional observer receives
the full private conjunction after completed graph and affine reduction, retaining
predicate relations before boundary-family construction. It shares the same disabled
default and reference-only diagnostic contract. A recorded family does not certify a
completed projection or a source verdict; [benchmark diagnostics](../../klause-bench/README.md#focused-probes)
owns the trace format and interpretation.

## Results and proof authority

### Pipeline and frontend publication

The pipeline accepts opaque `ir.PolynomialRealProblem` and shared `ir.Problem`
models through `pipelineRequest()`. `OpenTheoryPipeline.execute` selects a finite
strategy with exact polynomial completion and returns the common SAT/UNSAT/UNKNOWN
result with `PolynomialTheoryStats` and an optional native resource cause. Source declaration prefixes trim numeric conditional auxiliaries at capture.
Both verdicts receive a final stop check, and SAT capture receives another check
before publication. Timeout, external cancellation and budget/resource exhaustion
retain distinct common termination reasons. Exact source scalars survive invocation
cleanup. [Input semantics](../formats/smtlib.md#polynomial-source-lowering) owns admission,
exact quadratic objective capture and model rendering;
[resources](../solver/resources.md) owns containing allowances. The frontend passes
the retained request, objective and source direction to the pipeline and renders
its exact result; it performs no polynomial or numerical proof selection. Native
controls and units are defined below.

### Objective certificates

Objective-image checks visit projected sections/sectors in ascending order.
Every earlier cell must be refuted before a global objective claim is made; an
undecided earlier cell stops that claim. The first feasible section supplies an
attained optimum and an exact source witness. The first feasible sector with a
finite lower endpoint supplies an unattained infimum: its lower section was
refuted, while the entire open sector belongs to the objective image. A feasible
negative-unbounded sector establishes unboundedness by image invariance.
Exhausting every cell establishes infeasibility. Isolated threshold queries supply
none of this authority.

Capture removes only the objective coordinate and rechecks the original
conditioned formula and objective at the reconstructed source assignment.
In-memory image evidence retains source scope/counts, objective, projected roots
and exhausted prefix; it does not implement exported CAD proofs.

### Witness ownership

[ExactNraAssignment](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/ExactNraAssignment.kt)
captures Boolean buffers and an immutable list of exact real scalars. Scalar metadata
survives the producing arithmetic context and reconstructs each coordinate by its
rational defining polynomial and distinct-root index. Coordinates are never rounded
to rational points or binary64 for publication.

The pipeline's `OpenTheoryAssignment.ExactNra` preserves these values beside its
linear rational witnesses. Its `algebraicRealValue(id)` extension follows source
reconstruction wrappers without replacing the exact real coordinate. Point-identified
rational coordinates retain reduced rational text; other algebraic text and the typed metadata
contract are defined in the [API contract](../api.md).

## State and resources

### Results and invocation resources

`QfNraAssignment` owns Boolean and real-coordinate buffers. Array and list getters
return independent snapshots; immutable algebraic scalars survive later invocations.
Results retain counts for projected polynomials, visited prefix cells and native
work. They retain no mutable traversal, refinement or cancellation state.

`QfNraParams.maximumWork` is nullable for unlimited work; zero prevents entry. One
counted scope spans source-ID validation, normalization, projection, lifting,
Boolean search and the final publication poll. Each charge reaches the caller's
meter once, and the scope retains the caller's deadline. These native work units
are separate from legacy open-theory policy units. A stop before publication returns
`Unknown` with interruption or work-limit reason, never partial SAT or UNSAT.

Kernel allocation caps remain configurable through `polynomialLimits`. Dense,
matrix or checked-degree declines return `Unknown(ResourceLimit)` with the resource
kind. Caps do not truncate accepted polynomials or restrict their mathematical
semantics. See [kernel resources](../arithmetic/polynomial.md#invocation-resources)
and [scope composition](../solver/resources.md).

### Objective invocation resources

One native image allowance spans scope construction, projection, every fiber
check, exact reconstruction and final publication polling. Nested feasibility
checks forward charges once to that owner; their work, projection and lifting
counters remain visible. Cancellation and allocation/work exhaustion publish
unknown with the cause, never a partial global certificate.

The quadratic execution owner counts numerical proposal/certificate work and
polynomial work in separate counters under one cumulative `maximumWork` cap.
Finite branches and neighborhood repairs receive the remaining native allowance;
every charge reaches the containing meter once. Mandatory polynomial allocation
declines retain their resource cause. MIXED supplies retained worker factories to
the [common invocation](../solver/portfolio.md). Parallel single-strategy requests
materialize private replicas of their admitted arm; FIXED retains one worker. Complete
and heuristic families receive persistent native reservations; replicas divide those reservations.
Completed atomic work can overspend a segment; it cannot renew the containing allowance.
Private worker cancellation reaches every nested numerical and polynomial query.

### Invocation ownership

A polynomial pipeline request retains an immutable source snapshot and can be reused
for fresh calls. Each execution constructs its own arithmetic context, Boolean
search and CAD traversal. It exposes no resumable handle, scoped mutation or retained
refinement state. Complete exact scalar metadata remains readable after subsequent
executions or cancellation; published witnesses own only original source coordinates.
See [resource allowances](../solver/resources.md) for the containing scope.

A conditioned polynomial service invocation retains its native allowance across
queries. Each query captures its own Boolean pins and exact rational bounds and owns
fresh traversal state. Retraction discards the adapter's current result; it does not
refund work. The scope owner closes the invocation after search, while published
algebraic scalars remain independent of that lifecycle.

### Public controls and accounting

Public `PolynomialTheoryParams.maximumWork` is nullable for unlimited native work;
zero prevents entry. Dense coefficients default to 65,536 and must be positive or
unlimited. Matrix cells default to 262,144 and accept zero or unlimited. Disabling
an allocation cap retains checked native dimensions. `PolynomialTheoryExecution`
reports these counters and the resource cause separately from `SolveStats`.
Timeout and external cancellation keep separate common termination reasons.

The SMT CLI starts its whole-run deadline before admission and retains that deadline
through polynomial solving. Source parsing polls separately and charges its own exact
coefficient-folding units when a caller meter exists. Parsing is excluded from native
engine counters. `nra-work-limit`, `nra-dense-coefficients` and `nra-matrix-cells` own
the polynomial limits; `node-limit` and legacy open-work settings do not price them.

A complete worker's root query retains normalization, probing and projection preparation
between slices. Projection retains subresultant matrices, completed Bareiss cells,
exact-division reductions and monomial-product batches. Individual rational operations,
polynomial snapshots and the final cell check remain atomic with respect to slice
suspension and poll solve-wide cancellation. Pending queries expose cumulative
native charges without publishing partial results. Closing abandons private cursors while
retaining their charges. See [portfolio preparation](../solver/portfolio.md#polynomial-root-preparation).

A `PolynomialTheoryInvocation` shares one cumulative native allowance across its
conditioned checks. Every check receives only the remaining allowance and forwards
its observed arithmetic work once to the containing caller meter. Query condition
assembly belongs to that work scope. New finite branches and retractions do not renew
it. Independent invocations receive independent ledgers.

Polynomial strategy composition stops its containing search when the cumulative
native work allowance is exhausted or a representation resource declines. Workers
cannot restart the allowance or spin on completion queries after that stop. Native
resource causes and finite search telemetry remain visible in the pipeline result.

See [sessions](../solver/sessions.md) for handle ownership, [presolve](../solver/presolve.md) for phase
telemetry, [portfolio scheduling](../solver/portfolio.md) for coverage and
[LP certification](../lp/certification.md) for partial numerical evidence.

## Implementation

[SharedPolynomialModel](../../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/SharedPolynomialModel.kt)
assembles the source decomposition.
[PolynomialTheoryService](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialTheoryService.kt)
owns exact conditioned checks.
[PolynomialSearchComponent](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialSearchComponent.kt)
consumes shared facts. The pipeline uses `SourceProblemRoute.PolynomialTheory`.

### Objective-image traversal

The service adds an objective coordinate `t = f(source)` below all source
coordinates in the CAD order. Full Collins projection decomposes the objective
line into sections and sectors with invariant existential source feasibility.
Complete checks decide the fiber at each sample. Rational samples use exact
coordinate fixings. Algebraic section samples retain their defining polynomial
and unique isolating interval; no rational endpoint or floating score replaces a
root. The projected decomposition and completed fiber checks jointly justify
objective-image claims.

## Decision procedure

`solveQfNra` decides a neutral `PolynomialRealFormula` over declared real and Boolean
IDs. Normalization expands exact rational polynomial expressions and retains each
atom's original left-minus-right sign and relation. Formula evaluation preserves
Boolean references and arbitrary `Not`, `And` and `Or` structure. Source parsing
and frontend routing remain caller responsibilities.

Before projection, the existing bounded probe prefix first checks signs implied by
unconditional conjunctive atoms. Unary comparisons against zero restrict coordinate
signs; coefficient signs, monomial parity and exact sign addition/multiplication
then enclose each polynomial's possible signs. A relation disjoint from that enclosure
refutes the conjunction. Opposite-sign sums retain cancellation, and non-strict
premises retain zero. Boolean alternatives supply no unconditional sign premises.
This check shares the witness producer's coordinate, atom, term and native-work limits.
Admission decline leaves witness probing available; prefix exhaustion retains spent
work and leaves complete CAD available. It introduces no coordinate bounds or finite
domains and exports no proof artifact.

The same bounded prefix can derive necessary coordinate magnitudes from an
unconditional upper bound on a positive sum of even monomials. Each univariate
summand supplies a conservative rational magnitude bound without root extraction;
a zero upper bound forces each summand to zero. Exact closed term enclosures then
retain even-power correlation and coefficient signs. A relation disjoint from its
polynomial enclosure refutes the conjunction. Guarded premises, missing coordinate
bounds and representation declines supply no refutation. These private consequences
install no source bounds, CP domains or search boxes. They share the existing
coordinate, atom, term, degree, coefficient and native-work limits, and completed
refutations receive the containing final stop check.

Complete conjunctive equalities can supply private polynomial consequences in that
same prefix. A common monomial factor is cancelled only when original source signs
exclude zero. A variable occurring linearly in one monomial of an equality can guide
exact rewriting of divisible monomials in other rows; no source-variable division
or sign assumption is introduced. Small rows and low input coordinate degrees guide
pivot selection. Private reductions retain the original per-coordinate degree bounds,
coefficient admission and total term ceiling. Dropped rows weaken only this incomplete
refutation pass and cannot supply a witness.
Positive term pairs can cover a negative middle monomial when their exponent sum is
twice its exponent and their common monomial is source-nonnegative. Equal rational
coefficient portions form a common monomial times a binomial square. Closed sign
enclosures retain zero; a strict positive remainder remains strict beside nonnegative
squares. A relation disjoint from the resulting enclosure refutes the conjunction.
Guarded premises, unknown-zero factors, nondivisible terms, growth declines and
incomplete pairing provide no additional authority. All visits and exact operations
share the existing native owner and final stop check; the pass exports no proof artifact.

Sources containing Boolean coordinates can also be refuted by a necessary Boolean
relaxation in the same optional prefix. Every numeric atom becomes an independent
Boolean predicate; shared source references and complete gate equivalences remain
correlated. Gate propagation and iterative branching exhaust the relaxation before
publishing a refutation. A satisfying Boolean assignment supplies no real witness.
The pass admits the entire normalized source under the existing coordinate, Boolean,
atom, term and coefficient limits; node and child-reference counts each share the
term ceiling. Pure numeric sources skip this pass. Decisions, propagation, rollback
and admission debit the existing native owner, with no allowance per branch.
Admission decline and incomplete exhaustion provide no refutation. Completed
refutations retain the containing final stop check and export no proof artifact.

Before projection, bounded witness probing checks complete exact points and can
repair one coordinate at a time using roots of an equality specialized at all other
coordinates. Repaired points can guide later repairs even when the full check fails.
Each bounded complete-point check captures its coordinates and reuses completed exact
powers within that point. Its returned assignment uses those captured values.
Every original atom and Boolean relation remains checked under the same native
allowance; the cache supplies no independent acceptance authority. Later proposals
capture fresh points. Ordinary CAD checks retain their direct evaluator. See
[point arithmetic](../arithmetic/polynomial.md#algebraic-arithmetic-and-lifting).

Within the existing coupled prefix, unconditional constant-coefficient variable
equations can first guide a reconstructed rational proposal. Private elimination
substitutes each solved polynomial into the remaining conjunctive atoms; reverse
reconstruction retains every original declaration and fixed coordinate. Remaining
unary source comparisons guide the free-coordinate proposal. Nonconstant pivots,
guarded equalities and partial preparation supply no defining facts. Coefficients,
coordinate degrees and total retained terms respect the existing optional admission
ceilings. Substitution, hinting, reconstruction and complete original-formula checking
share the same native prefix as coupled guidance, without renewing it. A rejected
proposal supplies no refutation and leaves the ordinary producer and CAD available.

SAT-only definition guidance can also collect unconditional atoms from conjunctions
while leaving Boolean alternatives and negated compound formulas with the full
source checker. This collection does not replace complete conjunction admission
used by refutations. A private prepared reconstruction retains its residual
free-coordinate atoms and original definitions across proposals. Exact univariate
roots of nonlinear residual atoms guide rational proposals from successively refined
isolating endpoints. Every proposal reconstructs all dependent coordinates and checks
the complete original formula, including strict and guarded obligations. Fixed and
unused declarations remain captured. Root isolation, refinement, reconstruction and
checking share the existing coupled half-prefix, axis and proposal limits;
specialized coefficients and reconstructed rationals retain the same bit admission.
An incomplete preparation or rejected point supplies no source verdict or domain
restriction.

Nonlinear numeric conjunctions also propose coupled exact lines from positive
rational and zero centers on referenced unfixed coordinates. Source monomial co-occurrence
selects coordinate groups. Unconditional affine
equalities supply a rational completion and nullspace directions; guarded equalities
provide no affine facts. Each direction changes at least two unfixed coordinates.
A private parameter outside the original coordinate IDs substitutes the whole line
into source polynomials without capturing another substitution. Root sections and
interior sectors reconstruct complete original points, checked by the same source
sample authority. Fixed coordinates stay fixed. Rejected directions and inconsistent
affine completions supply no source refutation. Fractional elimination, substitution,
root queries, reconstruction and checks debit the existing native owner; affine row
storage respects the kernel matrix cap. Optional representation declines retain the
spent work and leave ordinary coordinate probing available. Affine row, completion,
direction and substituted coefficients decline above the same 4,096-bit admission
ceiling as input coefficients.
A fresh inequality-only coupled entry with no consumed root queries can replace
the one center with one complete dyadic point derived from source monomials.
Completed private source admission retains its already extracted immutable term
payloads. Completed conjunction indices map them to the same normalized atom and
exact polynomial identity, with the admitted payload counts. Only the admission
producer creates this state; constructor and direct coupled calls accept no payload
lists. Source retention, bounded mapping copies and identity consultations charge
the same probe owner. The coupled mapping is consumed by one entry and cleared on
exit or interruption. Probe-private immutable payloads can remain for support joins
in that same call; another query's conditions and fixings prepare fresh payloads.
Co-occurrence and guide preparation reuse those terms while retaining their traversal
and arithmetic charges. Specialized line polynomials retain fresh extraction.

Dyadic preparation reuses a matched payload without repeating its getter allocation
or sorting charges. Missing, inconsistent or direct-call metadata retains the legacy
extraction and pricing path. For more than one extracted term, the sorting proxy
charges the term count times its ceiling binary logarithm times one plus the declared
coordinate count. Comparisons scan immutable canonical monomial arrays. Reuse changes
usable work inside the same private prefix; it refunds no admission debt and retains
all actually performed storage, traversal, conversion and source-check work. The
proxy is not an exact platform-sort comparison count, wall-time bound or allocation
precharge. Initial admission getters still sort atomically without a complete sort
precharge; retained payloads supply no borrowed prepayment. Effective comparisons choose one
positive term and require its exponent weight to dominate every negative term by
a conservative rational-coefficient and term-count margin. Checked integer updates
adjust positive power-of-two coordinate scales over at most four construction
sweeps. Weight evaluation visits the monomial's immutable sparse original-coordinate
support in ascending order. The coupled support scan validates every coordinate
against the original seed dimension before excluding fixed coordinates. A bounded
atomic copy of the existing support accessor polls and charges its entering
operation, then charges the returned entries and each checked weight operation to
the same owner. It creates no retained support cache or full-dimension pricing proxy.
Each dyadic construction owns its zero-initialized exponent vector and completed
monomial weights. Its sole mutation entry advances a private revision whenever an
admitted repair changes a coordinate; only weights from that revision can be reused.
The owner exposes no mutable array or caller cache, and another attempt starts fresh.
Construction, consultations, revision updates and completed inserts poll and debit
the same native token. Interrupted weights enter no cache. Unused coordinates
contribute zero without a dense scan; constant and cached weights also poll before
returning. A complete recheck of the selected rows precedes bounded
exact shifts and point construction. Exponents admit numerator or denominator magnitudes up to the
same 4,096-bit ceiling. Failed or incomplete construction retains the one center;
unresolved positive equalities retain their affine completion and repair path.
This sufficient sign proposal adds no center, scalar repair pass, root query or
proposal allowance. All extraction, row arithmetic, storage, conversion and checks
debit the same coupled native prefix immediately. The original sample checker
alone accepts the point; domination supplies neither a verdict nor a source proof.
Fixed and unused coordinates retain their source values. Containing interruption
propagates through construction, while private phase exhaustion retains its charges
and leaves scalar probing available.
The positive center's whole-coordinate alternating direction and first co-occurrence
direction precede the zero center's uniform line. Completed bases consume the same
proposal ledger. Line cells near the center precede distant sections and sectors.
Nonlinear and multi-coordinate affine guides precede coordinate bounds. Exact
duplicate specialized polynomials share one root guide within a line; constant
guides consume no root query. A degree or representation decline skips that guide
only, retaining every source assertion in the complete point check.
Each coupled line owns its rational affine coordinate map. Complete point checks
use exact univariate images of every original normalized source polynomial at the
same algebraic line parameter, retaining coordinate correlation. Images and powers
are completed privately under the existing token and reused only within that line.
Guide eligibility does not omit high-degree or Boolean source constraints from this
check. The full Boolean formula still decides acceptance; final assignment capture
and rejected-point guidance reconstruct coordinates from the same owned line point.
Interrupted checking or capture publishes no witness and refunds no work. Ordinary
CAD point evaluation and the existing query, proposal and phase limits remain intact.

After coupled proposals, a complete numeric conjunction can join sparse rational
proposals from equalities with disjoint unfixed coordinate supports. Each support
starts with its existing zero seed and isolates original-coordinate roots while
retaining earlier choices. At most the existing four refinement passes expose an
exact rational root; nonpoint isolating endpoints supply no proposal here.
A source row is checked when its last changing support
has been chosen; coordinates outside the supports retain their proposed zero or
fixed seed. Cached source payloads retain coefficient, degree and total-term
admission. Specialization checks exact rational coefficients before isolation and
uses the existing kernel representation limits. A greedy join can decline even
when a dense or differently joined solution exists. Overlap, nonrational roots,
guarded conjunction admission and exhausted counters supply no source refutation.
Only the existing complete original-formula sample check accepts a finished point.
Each partial attempt consumes the same proposal ledger, reserving one slot for
that final check; each axis consumes the same root ledger. Support preparation,
partial checks and capture debit only the remaining original global probe prefix.
The coupled half-prefix is not moved or renewed. An unfinished join leaves ordinary
coordinate repair and complete CAD available with the retained charges.

Equalities under negation or disjunction are proposal guides, never asserted facts.
Scalar equality repair ranks a rejected proposal using the completed source-atom
signs from that proposal's full point check. The signs remain private to the check
and its immediate rank callback; ranking charges each comparison to the same owner.
Only a complete rank can replace the best proposal, with the existing strict score
tie break, root order and proposal limits. Interruption publishes neither a rank nor
a witness.
For a nonlinear conjunction of numeric inequalities and disequalities, an optional guide view
complements directly negated atoms without changing the source formula. It excludes
Boolean alternatives and unresolved positive equalities. Per-coordinate boundaries
combine roots of the specialized source polynomials. Within one boundary call at a
fixed point and coordinate, exact duplicate source polynomials share one root query
regardless of relation. A bounded local list charges payload comparisons and storage
to the same arithmetic owner and is discarded after the call. Every effective relation
remains independently scored and source-checked. Direct root queries retain their
shared debit and exhaustion behavior. Root sections and rational
interior sectors provide complete-point proposals. Negated denominator equalities
therefore guide nonzero intervals rather than requiring a zero. Rejected points are
ordered by the effective conjunctive relations. A completed rejected coupled point
with at least two nonzero referenced unfixed coordinates can replace this path's
first positive rational center. One private snapshot retains the best completed
effective-guide score, reusing exact source-atom signs from the complete point check.
Directly negated guides retain their complemented relation and original atom index.
An incomplete score or copy cannot replace the previous snapshot. Ranking and copying
debit the same arithmetic owner; optional representation declines retain their charges,
and interruption propagates to the existing phase owner.
The snapshot belongs only to this normalized query and probe. Scalar repair consumes
it once, preserving fixed and unused coordinates. The unchanged completed rejection
needs no second initial point check, but every changed coordinate proposal receives
a complete source check. Consumption polls the owner and respects exhausted query
and proposal counters. Zero and negative-one fallback centers retain their existing
order; the retained point creates no additional center, passes or allowance. Queries
with Boolean alternatives or unresolved positive equalities retain their existing
repair path. Without an eligible snapshot, inequality repair starts from the positive
rational center, retains fixed coordinates and assigns zero to unused declarations.
Affine-only normalized queries retain their uniform point checks and complete
projection allowance; short conditioned relaxation checks do not spend it on sector repairs.
After the existing coordinate repairs, a nonlinear formula with disjunctions or
unresolved Boolean structure can use remaining scalar root and proposal slots for
boundary sectors. Its atoms supply boundary guides, not a conjunction or affine
facts. Raw guide scores do not resolve Boolean correlations: every complete proposal
passes the original nested formula and query pins through the same sample checker.
This fallback shares the existing probe work, query and proposal counters. Earlier
returns and containing stops supply no further attempt or renewed allowance.
Acceptance still checks every source atom, strict relation, disequality, Boolean
alternative and query pin with the same complete sample checker. Fixed coordinates
remain fixed, and unused declarations retain complete coordinates.

This optional phase considers up to 32 real coordinates, 64 Boolean coordinates,
128 atoms and 2,048 normalized terms with input coefficients up to 4,096 bits.
Uniform rational centers and coupled lines seed at most
64 complete proposals and 32 coordinate root queries across four repair passes per
center. Coupled lines use at most half of these shared proposal and query caps, leaving
the remaining slots for coordinate repairs. Their native phase ends at the midpoint
of the same 65,536-unit probe slice. Each line spends at most four additional root
queries and eight additional proposals within those cumulative caps. Centers and
lines renew no allowance. A private phase stop retains every charge and
continues scalar probing; a containing stop remains terminal even if its predicate
later clears. Each admitted unique nonconstant coupled guide consumes one root-query
slot; coordinate repair queries retain their own shared debit. Root queries admit
coordinate or line degree at most eight. One private slice of
65,536 native units is debited to the containing query's cumulative work and caller
meter. Rejected proposals, private exhaustion and optional representation declines
leave complete CAD available. Parent stops are latched during the phase and withhold
publication; neither retraction nor a fresh proposal renews the invocation allowance.
Sparse polynomial getters and atomic exact arithmetic can overrun a cooperative
checkpoint; these counts do not impose a hard wall-time or all-allocation precharge.
Kernel dense, matrix and checked-degree limits remain unchanged.

Invocation snapshots separately observe eligible dyadic attempts, completed bases,
coupled private-work declines and the subset occurring before all dyadic payloads
are extracted. A completed base is not a source witness. Existing entry, return
and interruption outcomes update these counters without extra work, queries or
cancellation polls. A latched containing stop is not a private-work decline.
Snapshots are immutable and merge observed counts with saturation; missing producer
observations stay absent when no producer supplies them. A mixed aggregate sums only
instrumented queries and does not establish coverage of its unobserved portions.
These aggregate events identify
execution stages, not the private cause of a failed proposal or an inner cost clock.

After the unchanged optional probe, complete traversal enumerates Boolean regions
before constructing their numeric projection families. Each region is a source
implicant: assigned Boolean leaves and signed numeric predicates suffice to establish
the original formula. Numeric truth assignments are premises to check, not witnesses.
A satisfying result still requires complete exact source checking. Refutation requires
complete Boolean coverage and numeric refutation of every covered region.

### Boolean regions

[PolynomialBooleanRegions](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialBooleanRegions.kt)
uses shared Boolean propagation over the original normalized DAG, treating numeric
predicates with remaining variables as opaque leaves. A normalized constant
polynomial has one exact truth under the query's source pins. Its comparison truth
is installed before any Boolean decision, including strict and disequal relations
and their negations. These facts remain on the initial trail across region changes;
another conditioned query constructs its own facts. They do not select a real point
or reopen the optional probe.

Decisions visit source Boolean leaves before numeric
leaves, trying false before true. The bounded optional refuter retains its original
admission and charged traversal; complete region enumeration does not call it with
larger limits or reopen optional admission.

A topological proof scan distinguishes imposed gate truth from a witnessed Boolean
value. True conjunctions and false disjunctions require all children; a true
disjunction or false conjunction needs one witnessed decisive child. Negation
reverses the required truth. An iterative walk captures only the supporting leaf
literals, preserving shared reference identity. Other leaves are irrelevant within
that implicant. Each region's numeric family retains the polynomials of its signed
numeric literals in original atom order.

Private exclusion clauses prevent revisiting emitted leaf cubes, including their
irrelevant completions. They describe coverage of this traversal and never enter
shared clause learning or explanation exchange. Exhausting the remaining Boolean
abstraction covers every source model only when every emitted region was numerically
refuted. A representation-limited region can be skipped while searching another
region for SAT, but it permanently withholds global UNSAT. If no witness is found,
its resource refusal remains UNKNOWN even after Boolean enumeration finishes.
Containing cancellation or work exhaustion stops the entire query immediately.

Normalization, the single optional prefix, Boolean propagation and exclusions,
conditional projection, lifting and full source checking share one native arithmetic
and cancellation owner. Region changes and resumable checkpoints renew no allowance.
Original real and Boolean counts, fixed source values and the immutable source
formula remain captured; no region creates finite CP domains or a fresh probe.
A private counter records issued numeric regions, including constant contradictions;
another records their selected atom occurrences. Neither counts certified refutations.
Separate observations count completed constant contradictions and representation
refusals, retaining the last observed resource kind. That kind describes the last
instrumented refusal, not an exclusive cause of the whole invocation. These
observations reuse existing outcomes without another operation, poll or work charge.
Projection and lifting counts accumulate
across regions. Invocation snapshots merge observations with saturation, and an
unvisited complete stage remains absent.

### Projection families

[PolynomialProjection](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialProjection.kt)
constructs complete projection families for rational multivariate polynomials,
restricted by the region's required equalities when justified root sections are
available. Ordinary levels use Collins projection. The component reads the
[exact polynomial kernel](../arithmetic/polynomial.md) and owns selection of
projection operations and coordinate order.
The theory does not materialize finite CP domains or depend on the linear engines.

Each complete Boolean region first substitutes its total required-equation graph
sections using the same graph-selection primitive as projection, preserving every
remaining predicate's relation. Satisfied zero predicates are discharged; false zero
predicates remain contradictions. Completed graphs use the existing section counter,
including graphs completed before a later interruption or representation decline.
The region then applies exact affine inequality elimination to the exposed constraints.
A coordinate qualifies only when every occurrence is a pure linear monomial with a
nonzero rational coefficient in an inequality. Occurrences in equalities,
disequalities, powers or variable coefficients retain that coordinate for CAD.
For every lower/upper bound pair, elimination requires the upper polynomial minus
the lower polynomial to be nonnegative, strictly positive when either bound is strict. One-sided fibres
need no remaining inequality. A coordinate is selected only when these pairs do not
increase the active constraint count; every completed step removes one coordinate
from that conjunction. All work and suspension checkpoints use the same query owner.

CAD receives the equivalent reduced conjunction with the original real count and
the region's source Boolean assignment. After a reduced witness, stored graph and
interval fibres are reconstructed together in reverse order. A graph evaluates its
polynomial at the reconstructed remaining coordinates. Exact algebraic maxima/minima determine an interval;
coincident weak endpoints retain their exact value, and nonempty open intervals use
certified rational interior samples, including one-sided unbounded intervals.
Every original coordinate remains in the reconstructed assignment. The original
normalized formula receives the ordinary full source check before publication.
Incomplete elimination or reconstruction cannot publish a partial witness or
establish exhaustion. The optional probe runs once on its original scope before
these complete transformations; no reduced conjunction re-enters its admission.

Boundary canonicalization separates a polynomial's common monomial into its distinct
coordinate factors and the remaining primitive polynomial, then removes duplicate
boundaries. Sign invariance of those factors preserves the original product's sign,
including every zero-coordinate fibre and odd or repeated powers. This applies to
initial boundaries, graph substitutions and projected families under the same
arithmetic owner. Required-zero equations and retained required-nonzero polynomials
stay whole in the boundary family: graph rewrites and necessary resultants refer to
those complete relations. No product equation becomes simultaneous factor equations.
Lifting still evaluates every original selected predicate and the full source formula.

Within a Boolean region, a true equality or false disequality requires its whole
polynomial to vanish. These premises permit total graph sections: a coordinate
occurring in exactly one pure linear monomial with a nonzero rational coefficient
is solved as a polynomial in the remaining coordinates. Every region boundary,
remaining required equation and selected nonzero predicate is substituted before
further elimination. A nonzero constant required equation or a zero required
nonzero polynomial refutes that region. A product equality alone supplies no factor
zero or nonzero premise. Other coordinate
occurrences remain for subsequent projection; only total rational polynomial
graphs are substituted.

Complete regional reduction first propagates necessary coordinate signs through
its signed polynomial predicates. Private sign sets denote negative, zero and
positive real values; they do not materialize finite CP domains or provide a
witness. Monomial powers respect parity and zero, and sums of opposite signs
retain every possible sign. A coordinate sign is excluded only when the resulting
polynomial sign overapproximation cannot satisfy the predicate. An occurrence
worklist propagates each narrowing to adjacent predicates until stable or
inconsistent. An empty sign set refutes only that Boolean region. Zero fibres and
mixed-sign cancellation remain admitted. This complete proof pass shares the
query's native work and cancellation owner; it does not re-enter the bounded
witness probe or alter its original admission and attempt limits.

Required nonzero monomial predicates independently certify that each coordinate
factor is nonzero. Regional reduction can cancel those coordinates' common powers
from required zero equations, then use the resulting polynomial graphs. It never
divides signed inequalities. Coordinates without such premises retain their zero
fibres. Cancellation and graph closure alternate until stable under the same
arithmetic and cancellation owner. Any equation rewrite reaches projection even
when it eliminates no coordinate. The current region retains the nonzero premises,
every original coordinate slot and the complete original formula check; another
region or query starts with separate facts.

After graph and affine reduction, each complete region can also supply necessary
closed rational magnitude bounds. In an oriented sum of nonnegative even
monomials bounded above by a nonnegative constant, a pure even-power summand
bounds its coordinate. A zero ceiling forces that coordinate to zero; otherwise
the maximum of one and the ceiling-to-coefficient ratio is a conservative radius.
This inference takes no roots and retains boundary equality. Mixed-sign or
odd-power summands supply no such premise. Exact monomial and sum enclosures
use the retained radii; an unbounded coordinate withholds that enclosure. Only an
enclosure excluding a required relation refutes the region. Bounds belong to that
region, retain the same arithmetic token and kernel power representation checks,
and supply no satisfying assignment. An unfinished pass withholds the refutation.

Projection records a permutation of all original coordinates and any further total graph
sections. Dependencies in those sections occupy earlier lifting levels. Prefix literal checks
use those levels rather than source coordinate IDs. Fixed coordinates retain their
source values; graph coordinates are reconstructed by exact evaluation at the
selected earlier coordinates. Every original declaration remains in the complete
assignment and final source check. Section premises belong only to their Boolean
region and conditioned query. Completed section observations accumulate under the
same native owner, including sections preceding a later representation refusal;
they measure completed transformations, not accepted models or region refutations.

The returned family owns its level buffers. Input list mutation and mutation of a
returned list do not change captured families. Immutable kernel polynomials may be
shared. One caller-supplied arithmetic context spans construction; interruption and
kernel resource stops propagate without publishing a partial family.

Construction eliminates total graph coordinates when available, then selects a
justified required-root coordinate, otherwise follows the captured coordinate
preference. Required-root selection prefers the earliest eligible elimination
coordinate in that preference. Complete queries rank the current reduced region
boundaries by the sum of degrees in each coordinate, then peak degree and number
of monomials containing it. Smaller scores eliminate first, with descending source
ID resolving ties. This preference changes no polynomial, declared coordinate,
optional admission or numeric coverage. Its construction shares the original
arithmetic and cancellation owner. The projection helper accepts a complete
permutation; its unspecified preference is descending source-ID elimination.

Projection stores one polynomial family per lifting
level, containing boundaries involving that level's coordinate after later levels
have been removed. Lower-level polynomials carry through elimination. An absent
dimension retains an empty family. The univariate family occupies the first
lifting level. Equality sections can override the preference while retaining all
their coordinate dependencies at earlier lifting levels.

#### Required root sections

A selected region predicate whose truth excludes zero proves its whole polynomial
nonzero. Total graph substitutions preserve that premise. Whenever the original
or rewritten polynomial is one monomial, every coordinate in it is nonzero. For
example, `z != 0` and `z = x*y` prove both `x != 0` and `y != 0`; a sum supplies
no such factor inference. Rewritten nonzero constants discharge the premise, and
zero refutes the region. After ordinary projection eliminates a coordinate,
predicates involving it stop supplying graph rewrite facts; already proved
coordinate facts remain valid. These proof facts belong to that Boolean region
and conditioned query, and reuse the boundary substitutions and native owner.
Every source predicate remains in the complete source check. A required equality
can restrict a lifting level to its real roots when its leading coefficient is one nonzero
rational monomial and every coefficient coordinate has such a source nonzero fact.
A rational constant leading coefficient needs no additional fact. A coefficient
sum or an unproved nonzero coordinate supplies no permission to omit a zero fiber.

At that level, projection retains all input coefficients, all principal
subresultants of the selected equation and its derivative, and all principal
subresultants between that equation and each other polynomial's needed reducta.
Other derivatives and pairs of other polynomials are unnecessary on the equation's
root sections. Coefficient invariance fixes the other polynomials' specialized
degrees. The selected equation has fixed degree on every admitted base cell;
its derivative subresultants preserve its root branches, and the pair
subresultants preserve their intersections with the other polynomials. Their
signs are therefore invariant on each selected root section. This argument
retains repeated roots and shared zero factors without a square-free assumption.

Lifting isolates every real root of the selected equation and visits no sectors
at that level. A specialized degree below the selected degree means the leading
monomial vanished, contradicting this region's source nonzero premises; that
fiber has no admissible root frame. A required equation without real roots also
has an empty frame. Regions without a justified leading coefficient retain full
ordinary projection and their zero fibers. Graph and root sections both retain
all original coordinate slots and the final complete source check.

When both full input polynomials are required zeroes, their zeroth principal
subresultant is a necessary lower equation. This consequence is collected only
for the full inputs, never for arbitrary reducta. A zero resultant supplies no
sufficiency claim about common roots. A nonzero rational constant resultant
refutes the region. Lower equations can enable further graph or root sections;
a graph that forces a source-proved nonzero coordinate to zero also refutes the
region. These consequences share the existing arithmetic owner and leave every
original equation in the final source check.

A nullable counter observes selected required-root levels, including levels
without a projection operation. It counts neither roots nor accepted proofs or
models, and aggregates across regions and invocation checks with saturation.
These observations reuse the existing selection without another operation,
poll or native charge.

#### Ordinary projection levels

A top family containing only rational univariate polynomials has no lower-coordinate
boundaries. It carries the lower families unchanged and isolates its roots during
lifting, including when other declared coordinates are absent from the family.
Its constant feasibility is decided by those cells without a projection matrix.

For each ordinary eliminated top-variable family, the projection contains all coefficient
polynomials and every principal subresultant coefficient of each needed nonconstant reductum
and its derivative, and every principal subresultant coefficient between reducta
from distinct input polynomials. Equal-degree pairs use the same exact kernel
operation. Zero and rational constant projections add no boundaries. Projection
polynomials are normalized by nonzero rational scaling and deduplicated; this
normalization concerns zero loci and does not provide signed source-atom truth.

Coefficient signs preserve degree and identically-zero specializations over a base
cell. Subresultant vanishing preserves distinct-root counts and common-root
structure. Together they preserve delineability, including repeated roots, shared
roots and vanishing leading coefficients. The reductum list stops at its first
nonzero rational leading coefficient. That coefficient cannot vanish under any base
specialization, so lower degrees cannot occur on that fiber. Variable leading
coefficients retain the degree-loss reducta; the construction assumes no nonzero
variable coefficient or discriminant.

### Lifting and truth

Traversal uses an iterative stack in ascending projection-level order. Each projection
polynomial specializes at the exact earlier coordinates. Identically-zero
specializations add no roots; nonzero constants also add none. Kernel isolation
supplies every distinct real root, and exact comparison sorts and deduplicates the
union. Each root is a section sample; kernel rational samples lie strictly inside
the intervening and unbounded sectors. Empty families still contribute a whole-line
sector, so every declared dimension receives a coordinate.

At a prefix, region literals whose numeric variables are all assigned have definite
truth. A violated literal prunes the base cell because its polynomial belongs to
that region's sign-invariant family. Higher literals remain unresolved. No prefix
supplies a satisfying result. An omitted source atom need not have invariant sign;
the region's Boolean proof establishes source truth independently of it whenever
all selected literals hold.

At a complete region sample, exact algebraic evaluation checks every original atom.
The region supplies its Boolean leaf assignments; Boolean IDs outside the proof
receive false. The complete assignment is checked against the original normalized
formula before capture. It retains every declared numeric coordinate, including
empty-family dimensions and fixed values.

Ordinary levels exhaust all Collins sections and sectors; required-root levels
exhaust all real roots under their source premises. Together they refute the
region's signed numeric conjunction, including admitted degree loss and
nullification fibers. A source
UNSAT requires every Boolean region to be covered and numerically refuted; any
unfinished region withholds that verdict. Both SAT and UNSAT retain the final
containing stop check. Root indices in returned
`AlgebraicReal` values identify their rational defining polynomial's distinct roots,
not the position in a specialized lifting family.
