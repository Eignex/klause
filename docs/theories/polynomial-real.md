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
the captured completion data from that plan. Open integer declarations carry explicit
preparation declines; they are not boxed. Source ownership is selected without the
objective, then the original objective and direction are bound to the same plan.
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
counts. Each `PolynomialTheoryInvocation` owns a cumulative native work allowance
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

Before projection, bounded witness probing checks complete exact points and can
repair one coordinate at a time using roots of an equality specialized at all other
coordinates. Repaired points can guide later repairs even when the full check fails.
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
the same probe owner. The mapping is consumed by one coupled entry and cleared on
exit or interruption; another query's conditions and fixings prepare fresh payloads.
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
sweeps. A complete recheck of the selected rows precedes bounded exact shifts and
point construction. Exponents admit numerator or denominator magnitudes up to the
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
Equalities under negation or disjunction are proposal guides, never asserted facts.
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

Complete Collins projection followed by exact lifting partitions the real space
into sign-invariant cells when probing supplies no witness. Refutation requires
exhaustion of the relevant cells and Boolean alternatives; unsuccessful repairs
supply no refutation authority. Both witness paths retain the final stop check.

### Projection families

[PolynomialProjection](../../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialProjection.kt)
constructs a complete Collins projection family for rational multivariate
polynomials. It reads the [exact polynomial kernel](../arithmetic/polynomial.md)
and owns selection of projection operations and declared variable order.
The theory does not materialize finite CP domains or depend on the linear engines.

The returned family owns its level buffers. Input list mutation and mutation of a
returned list do not change captured families. Immutable kernel polynomials may be
shared. One caller-supplied arithmetic context spans construction; interruption and
kernel resource stops propagate without publishing a partial family.

Variable IDs define ascending lifting order. Construction eliminates variables in
descending order and stores one polynomial family per declared ID, containing only
polynomials whose main variable is that ID. Lower-variable polynomials carry through
elimination. An absent dimension retains an empty family. The univariate family is
the last projection level.

For each eliminated top-variable family, the projection contains all coefficient
polynomials, every principal subresultant coefficient of each nonconstant reductum
and its derivative, and every principal subresultant coefficient between reducta
from distinct input polynomials. Equal-degree pairs use the same exact kernel
operation. Zero and rational constant projections add no boundaries. Projection
polynomials are normalized by nonzero rational scaling and deduplicated; this
normalization concerns zero loci and does not provide signed source-atom truth.

Coefficient signs preserve degree and identically-zero specializations over a base
cell. Subresultant vanishing preserves distinct-root counts and common-root
structure. Together they preserve delineability, including repeated roots, shared
roots and vanishing leading coefficients. The complete projection includes all
reducta rather than relying on a nonvanishing discriminant or leading coefficient.

### Lifting and truth

Traversal uses an iterative stack in ascending variable order. Each projection
polynomial specializes at the exact earlier coordinates. Identically-zero
specializations add no roots; nonzero constants also add none. Kernel isolation
supplies every distinct real root, and exact comparison sorts and deduplicates the
union. Each root is a section sample; kernel rational samples lie strictly inside
the intervening and unbounded sectors. Empty families still contribute a whole-line
sector, so every declared dimension receives a coordinate.

At a prefix, only original atoms whose variables are all assigned have definite
truth. Higher atoms and every Boolean variable remain unknown. A three-valued false
formula prunes that base cell because those lower signs are invariant throughout
the cell. No prefix supplies a satisfying result. At a complete sample, exact
algebraic evaluation decides every atom and iterative Boolean search preserves
reference correlation. Unused Boolean IDs receive false. The complete assignment
is checked against the normalized source formula before capture.

Full Collins projection and exhaustive section/sector traversal provide a
sign-invariant decomposition, including degree loss and nullification. A satisfying
result requires a complete exact witness; an unsatisfiable result requires exhaustion
of every unpruned cell and its Boolean alternatives. Root indices in returned
`AlgebraicReal` values identify their rational defining polynomial's distinct roots,
not the position in a specialized lifting family.
