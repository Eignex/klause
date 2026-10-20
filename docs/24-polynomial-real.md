# 24. Polynomial real arithmetic

Polynomial real constraints multiply real coordinates, such as `x*y >= 1` or
`x^2 + y^2 = 2`. Where the linear theories of chapters 20–22 check rows with an
LP, this theory checks nonlinear real formulas exactly and completes continuous
coordinates beside finite search. The previous chapter already used it as the real
completion service for fixed integer candidates; the
[transcendental theory](25-transcendental.md) in the next chapter relaxes its
functions into it.

Backtracking, local search, ALNS and portfolios can ask this service to check the
same source constraints under their current finite assignments. Real values remain
theory-owned; CP remains Bool+Int only. The theory follows the
[shared theory contract](18-theories.md), and its decision procedure is a
cylindrical algebraic decomposition (CAD) over exact algebraic numbers.

## Scope and boundaries

The complete arithmetic fragment is polynomial real satisfaction with rational
coefficients and Boolean structure, also called QF_NRA. Shared composition supports
finite integer domains and exact quadratic objectives over Boolean, integer and real
source coordinates. Open integer domains belong to
[nonlinear integer arithmetic](23-nonlinear-integer.md), and transcendental
functions to the next chapter. Resource limits can interrupt even this complete
procedure.

[SMT-LIB semantics](09-smtlib.md#polynomial-source-lowering) owns source admission;
the [exact polynomial kernel](28-exact-arithmetic.md#exact-polynomial-and-algebraic-arithmetic)
owns mathematical operations; [search](14-search.md) owns finite traversal.

## Solving and composition

### Shared model composition

`PreparedSourcePlan` owns the shared source decomposition. Its captured
`SharedPolynomialModel` installs required selected polynomial encoders and connected
finite encoders, keeping original factor indices and integer embeddings for every
strategy. Exact linear rows, Boolean reification, real bounds, integer/real products
and `ContinuousProduct` become neutral polynomial formulas through the captured
encoder provider. Binary64 constants denote their exact binary rational values.

Only integers that appear in encoded equations receive numeric slots, as symbolic
real coordinates for conditioned queries; their finite domains and global
constraints stay in CP. AllDifferent, scheduling and other finite factors remain
intact in the finite projection, and linear relaxation rows cannot stand in for a
factor's semantics. A finite filter and a complete encoder can participate together.
A factor scope with open integer declarations selects the
[nonlinear integer adapter](23-nonlinear-integer.md) instead; open integers are
never boxed. Source ownership is selected without the objective, and the original
objective and direction are then bound to the same plan.

Each strategy uses the plan in its own way:

- **Backtracking** checks interval relaxations at partial states and exact
  completions at fixed finite branches.
- **Local search** generates candidates from finite factors, rechecks their pinned
  finite fixpoint, then pins their finite values in a theory query before
  publication. Pure-real sources and fixed finite projections use exact coordinate
  neighborhoods: rational centers derived from isolating intervals guide proposals,
  and published coordinates keep their exact algebraic values.
- **ALNS** uses the same completion in bootstrap, finite CP repair and its inner
  local search.
- **The sequential mixed pool** shares one finite projection and immutable source
  scope across these workers; only the complete worker receives proof admission.

Rejected heuristic candidates never establish a global refutation. The native theory
allowance persists across queries and neighbourhood pins.

### Conditioned theory service

`PolynomialTheoryService` captures one immutable neutral formula and its coordinate
counts. Each `PolynomialTheoryInvocation` owns a cumulative native work allowance
across conditioned checks. A check conjoins Boolean pins and rational lower/upper
bounds with the formula inside that counted scope. Numeric selections keep their
guards and branch expressions; distinctness expands to exact pairwise disequalities
during normalization. Query construction, normalization, CAD, witness capture and
the final verdict poll share cancellation and allocation limits.

`PolynomialSearchComponent` consumes shared Boolean facts and semantic integer
bounds. A query over an unfixed integer interval is a real relaxation: its exact
refutation can reject the finite branch, while its witness cannot accept that
branch. Complete checks require every mapped integer and Boolean to be fixed.
Conflicts without a clause explanation stay chronological and cannot enter learned
clause exchange. Retraction invalidates the current result while the invocation
allowance persists.

### Quadratic strategy execution

The quadratic overload of `OpenTheoryPipeline.minimize` uses the same source
request, finite projection and polynomial formula. Complete CP traversal visits every
finite assignment and completes its continuous objective exactly; branch optima and
unattained limits combine in source objective order, with attainment preferred on
equal limits. An unknown branch withholds global optimality and infeasibility, while
one checked improving unbounded branch proves source unboundedness. Local search and
ALNS propose coordinate releases and expanding exact rational windows, repairing
each neighborhood by finite enumeration with exact continuous completion; MIXED runs
a bounded adaptive phase before complete traversal. Termwise exact box arithmetic
can supply a global bound from declarations and pins before feasibility is known.
`OpenTheoryPipeline.optimize` normalizes affine `LinearObjective` values losslessly
into the same result surface.

### Objective optimization adapters

`solvePolynomialObjectiveImage` optimizes an objective over a conditioned
real/Boolean source using the same polynomial IR, projection and exact witnesses.
Integrality and finite factors remain caller obligations. `tryConvexPolynomialOptimum`
independently admits conjunctions of affine rows with a quadratic objective, uses
the [quadratic certificate kernel](28-exact-arithmetic.md#exact-quadratic-certificates),
then rechecks the original formula and objective. Its candidate selection is bounded
and incomplete; a decline leaves the image service available.

## Results and proof authority

`OpenTheoryPipeline.execute` returns the common SAT/UNSAT/UNKNOWN result with
`PolynomialTheoryStats` and an optional native resource cause. Both verdicts receive
a final stop check, and SAT capture receives another before publication. Timeout,
cancellation and resource exhaustion keep distinct termination reasons. The frontend
passes the request, objective and direction to the pipeline and renders the exact
result; it performs no proof selection itself.

SAT requires a complete exact source assignment that passes the original
normalized formula. UNSAT requires every Boolean region to be covered and
numerically refuted, as described under the decision procedure below. A
representation-limited region withholds global UNSAT even if no witness is found.

### Objective certificates

Objective-image checks visit projected objective cells in ascending order. Every
earlier cell must be refuted before a global claim. The first feasible section
supplies an attained optimum with an exact witness. The first feasible sector with a
finite lower endpoint supplies an unattained infimum. A feasible negative-unbounded
sector establishes unboundedness, and exhausting every cell establishes
infeasibility. Isolated threshold queries supply none of this authority. The
in-memory image evidence is not an exported CAD proof.

### Witness ownership

[ExactNraAssignment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/ExactNraAssignment.kt)
captures Boolean buffers and an immutable list of exact real scalars. Each
coordinate is reconstructed by its rational defining polynomial and distinct-root
index and is never rounded to a rational or binary64 for publication.
`OpenTheoryAssignment.ExactNra` keeps these values beside linear rational witnesses;
the [API contract](30-api.md#exact-assignment-values) defines their public access.

## State and resources

`QfNraParams.maximumWork` is nullable for unlimited work; zero prevents entry. One
counted scope spans normalization, probing, projection, lifting, Boolean search and
the final publication poll, and each charge reaches the caller's meter once. A stop
before publication returns `Unknown` with its reason, never a partial SAT or UNSAT.
Kernel allocation caps (`polynomialLimits`: 65,536 dense coefficients and 262,144
matrix cells by default) return `Unknown(ResourceLimit)` without truncating accepted
polynomials; see [kernel resources](28-exact-arithmetic.md#invocation-resources).

The quadratic execution owner counts certificate and polynomial work separately
under one cumulative cap. MIXED supplies retained worker factories to the
[common invocation](16-portfolio.md), whose families receive persistent native
reservations. A complete worker's root query keeps normalization, probing and
projection preparation between slices; individual rational operations and the final
cell check are atomic. See
[portfolio preparation](16.2-portfolio-scheduling.md#polynomial-root-preparation).

### Invocation ownership

A pipeline request keeps an immutable source snapshot and can be reused; each
execution constructs its own arithmetic context, Boolean search and CAD traversal.
A conditioned service invocation keeps its native allowance across queries, while
each query captures its own pins and bounds with fresh traversal state. A completed
native witness stays private to the invocation and can be reused for a later
Boolean-only condition after a charged scan confirms every pin; numeric bounds
require fresh traversal, and no refutation is reused across conditions. Retraction
discards the current result without refunding work. Published algebraic scalars
outlive the invocation.

## Implementation

[SharedPolynomialModel](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/SharedPolynomialModel.kt)
assembles the source decomposition.
[PolynomialTheoryService](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialTheoryService.kt)
owns exact conditioned checks.
[PolynomialSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialSearchComponent.kt)
consumes shared facts. [QfNraSolver](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/QfNraSolver.kt)
implements the decision procedure. The pipeline uses
`SourceProblemRoute.PolynomialTheory`.

### Decision procedure

`solveQfNra` decides a neutral `PolynomialRealFormula` over declared real and
Boolean IDs. Normalization expands exact rational polynomial expressions, keeps each
atom's original left-minus-right sign and relation, and preserves arbitrary `Not`,
`And` and `Or` structure. The procedure then runs in four stages. Only the last two
are needed for completeness; the first is a bounded shortcut.

**1. Bounded probe.** An optional prefix tries to settle the query cheaply. On the
refutation side it checks necessary conditions on unconditional conjunctive atoms:
sign enclosures from coefficient signs and monomial parity, magnitude bounds from
sums of even monomials, private equality consequences such as cancelled common
monomials and binomial-square enclosures, and a Boolean relaxation that treats each
numeric atom as an independent predicate. On the witness side it proposes exact
points: uniform rational centers, single-coordinate repairs from the roots of a
specialized equality, coupled lines through several coordinates, dyadic points whose
dominant positive term outweighs the negative ones, and sparse joins of equalities
with disjoint supports. Every proposal must pass the complete original-formula check.
The probe considers at most 32 real coordinates, 64 Booleans, 128 atoms and 2,048
terms with coefficients up to 4,096 bits, and spends one private slice of 65,536
native units. A probe decline or exhaustion keeps its charges and leaves complete CAD
available; no probe consequence installs source bounds or exports a proof.

**2. Boolean regions.** [PolynomialBooleanRegions](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialBooleanRegions.kt)
enumerates source implicants: assignments of Boolean leaves and signed numeric
predicates that suffice to make the original formula true. Shared Boolean
propagation runs over the normalized DAG with numeric predicates as opaque leaves;
decisions visit Boolean leaves before numeric ones, false before true. A sign
fixpoint over the initial required predicates can force further predicate truths
before the first decision. A proof scan keeps only the leaf literals that support
the formula's truth, and private exclusion clauses prevent revisiting a region.
These clauses describe traversal coverage only and never enter shared learning.

**3. Regional reduction and projection.** Each region's signed predicates form a
conjunction, which is simplified before projection: proportional rows intersect
their allowed signs, required equalities that are linear in some coordinate are
solved and substituted as total graph sections, coordinates occurring only linearly
in inequalities are eliminated by exact affine pairing of lower and upper bounds,
and necessary sign and magnitude propagation can refute the region outright.
[PolynomialProjection](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialProjection.kt)
then builds one polynomial family per lifting level. Ordinary levels use Collins
projection: coefficients, principal subresultant coefficients of each reductum with
its derivative, and those between reducta of distinct polynomials, which together
preserve delineability including repeated roots and vanishing leading coefficients.
Where a required equality has a provably nonzero leading coefficient, the level is
restricted to that equation's roots and needs only the subresultants involving it.
Elimination order prefers total graph coordinates, then justified required-root
coordinates, then coordinates with smaller degree sums.

**4. Lifting and truth.** Traversal walks the levels in ascending order. Each
projection polynomial is specialized at the exact earlier coordinates, the
[kernel](28-exact-arithmetic.md#algebraic-arithmetic-and-lifting) isolates every
distinct real root, and roots become section samples with rational samples in the
sectors between them. A region literal whose variables are all assigned prunes the
cell when violated. At a complete sample, exact algebraic evaluation checks every
original atom, graph and affine coordinates are reconstructed in reverse order, and
the full assignment passes the original formula before capture. Exhausting all
sections and sectors of every level refutes the region. A source UNSAT requires
every region to be covered and refuted.

Normalization, the probe, region enumeration, reduction, projection, lifting and the
final source check share one native arithmetic and cancellation owner. Region
changes and resumable checkpoints renew no allowance, and no region creates finite
CP domains.

### Objective-image traversal

The image service adds an objective coordinate `t = f(source)` below all source
coordinates in the CAD order. Full Collins projection decomposes the objective line
into sections and sectors with invariant existential source feasibility, and
complete checks decide the fiber at each sample. Algebraic section samples keep their
defining polynomial and isolating interval. The decomposition and completed fiber
checks jointly justify the [objective certificates](#objective-certificates) above.

---

Previous: [23. Nonlinear integer arithmetic](23-nonlinear-integer.md) · [Contents](README.md) · Next: [25. Transcendental real arithmetic](25-transcendental.md)
