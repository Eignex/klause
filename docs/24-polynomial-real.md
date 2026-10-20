# 24. Polynomial real arithmetic

Polynomial real constraints multiply real coordinates, such as `x*y >= 1` or
`x^2 + y^2 = 2`. Where the linear theories of chapters 20–22 check rows with an
LP, this theory checks nonlinear real formulas exactly and completes continuous
coordinates beside finite search. The previous chapter already used it as the real
completion service for fixed integer candidates; the
[transcendental theory](25-transcendental.md) in the next chapter relaxes its
functions into it. Its decision procedure is a cylindrical algebraic decomposition
(CAD) over exact algebraic numbers. Real values remain theory-owned; CP remains
Bool+Int only.

## Scope and boundaries

The complete fragment is polynomial real satisfaction with rational coefficients
and Boolean structure (QF_NRA). Shared composition supports finite integer domains
and exact quadratic objectives over Boolean, integer and real coordinates. Open
integer domains belong to [nonlinear integer arithmetic](23-nonlinear-integer.md).
[SMT-LIB semantics](09-smtlib.md#polynomial-source-lowering) owns source admission
and the [exact polynomial kernel](28-exact-arithmetic.md#exact-polynomial-and-algebraic-arithmetic)
owns mathematical operations. Resource limits can interrupt even this complete
procedure.

## Solving and composition

### Shared model composition

`PreparedSourcePlan` owns the shared source decomposition. Its
`SharedPolynomialModel` installs selected polynomial encoders and connected finite
encoders: exact linear rows, Boolean reification, real bounds, integer/real
products and `ContinuousProduct` become neutral polynomial formulas. Only integers
that appear in encoded equations receive numeric slots; their finite domains and
global constraints stay in CP, and relaxation rows cannot stand in for a factor's
semantics. A scope with open integer declarations selects the
[nonlinear integer adapter](23-nonlinear-integer.md) instead; open integers are
never boxed.

Each strategy uses the plan in its own way:

- **Backtracking** checks interval relaxations at partial states and exact
  completions at fixed finite branches.
- **Local search** pins finite candidates in a theory query before publication.
  Pure-real sources use exact coordinate neighborhoods around rational centers.
- **ALNS** uses the same completion in bootstrap, finite repair and its inner local
  search.
- **The mixed pool** shares one finite projection across these workers; only the
  complete worker receives proof admission.

Rejected heuristic candidates never establish a global refutation.

### Conditioned theory service

`PolynomialTheoryService` captures one immutable neutral formula. Each
`PolynomialTheoryInvocation` owns a cumulative native work allowance across
conditioned checks; a check conjoins Boolean pins and rational bounds with the
formula. `PolynomialSearchComponent` consumes shared Boolean facts and integer
bounds. A query over an unfixed integer interval is a real relaxation: its
refutation can reject the finite branch, but its witness cannot accept it. Conflicts
without a clause explanation stay chronological.

### Quadratic strategy execution

The quadratic overload of `OpenTheoryPipeline.minimize` uses the same source
request, finite projection and polynomial formula. Complete CP traversal visits
every finite assignment and completes its continuous objective exactly; branch
optima and unattained limits combine in objective order. An unknown branch
withholds global claims, while one checked unbounded branch proves unboundedness.
LS and ALNS repair neighborhoods by finite enumeration with exact continuous
completion. `solvePolynomialObjectiveImage` optimizes over a conditioned source with
the same IR, and `tryConvexPolynomialOptimum` admits affine rows with a quadratic
objective through the
[quadratic certificate kernel](28-exact-arithmetic.md#exact-quadratic-certificates).

## Results and proof authority

`OpenTheoryPipeline.execute` returns the common SAT/UNSAT/UNKNOWN result with
`PolynomialTheoryStats`. SAT requires a complete exact assignment that passes the
original normalized formula. UNSAT requires every Boolean region to be covered and
refuted; a representation-limited region withholds it. Timeout, cancellation and
resource exhaustion keep distinct termination reasons.

### Objective certificates

Objective-image checks visit projected objective cells in ascending order, and every
earlier cell must be refuted before a global claim. The first feasible section
supplies an attained optimum with an exact witness; the first feasible sector with a
finite lower endpoint supplies an unattained infimum; a feasible negative-unbounded
sector establishes unboundedness; exhausting every cell establishes infeasibility.
The evidence is in-memory, not an exported CAD proof.

### Witness ownership

[ExactNraAssignment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/ExactNraAssignment.kt)
captures Boolean buffers and exact real scalars, each represented by its rational
defining polynomial and root index and never rounded for publication. The
[API contract](30-api.md#exact-assignment-values) defines their public access.

## State and resources

`QfNraParams.maximumWork` is nullable for unlimited work; zero prevents entry. One
counted scope spans normalization, probing, projection, lifting, Boolean search and
the final poll. A stop returns `Unknown` with its reason, never a partial verdict.
Kernel allocation caps (65,536 dense coefficients and 262,144 matrix cells by
default) return `Unknown(ResourceLimit)`; see
[kernel resources](28-exact-arithmetic.md#invocation-resources). A complete
portfolio worker keeps its root preparation between slices; see
[portfolio preparation](16.2-portfolio-scheduling.md#polynomial-root-preparation).

### Invocation ownership

A pipeline request keeps an immutable source snapshot and can be reused; each
execution constructs its own arithmetic context, Boolean search and CAD traversal.
A conditioned invocation keeps its allowance across queries. A completed witness
can be reused for a later Boolean-only condition after a charged scan confirms
every pin; no refutation is reused across conditions. Published algebraic scalars
outlive the invocation.

## Implementation

[SharedPolynomialModel](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/SharedPolynomialModel.kt)
assembles the source decomposition.
[PolynomialTheoryService](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialTheoryService.kt)
owns conditioned checks, and
[QfNraSolver](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/QfNraSolver.kt)
implements the decision procedure. The pipeline uses
`SourceProblemRoute.PolynomialTheory`.

### Decision procedure

`solveQfNra` decides a neutral `PolynomialRealFormula`. Normalization expands exact
rational polynomial expressions and keeps each atom's sign, relation and Boolean
structure. The procedure then runs in four stages; only the last two are needed for
completeness.

**1. Bounded probe.** An optional prefix tries to settle the query cheaply. It
checks necessary conditions on conjunctive atoms (sign enclosures from coefficient
signs and monomial parity, magnitude bounds, cancelled common monomials) and
proposes exact points (rational centers, single-coordinate repairs from roots of a
specialized equality, coupled lines, dyadic points). Every proposal must pass the
complete original-formula check. The probe is limited to small formulas and one
65,536-unit slice; a decline leaves complete CAD available.

**2. Boolean regions.** [PolynomialBooleanRegions](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialBooleanRegions.kt)
enumerates source implicants: assignments of Boolean leaves and signed numeric
predicates that make the formula true. Propagation treats numeric predicates as
opaque leaves, a proof scan keeps only supporting literals, and private exclusion
clauses prevent revisiting a region without entering shared learning.

**3. Regional reduction and projection.** Each region's signed predicates form a
conjunction. Linear required equalities are solved and substituted, coordinates
occurring only linearly in inequalities are eliminated by affine pairing, and sign
propagation can refute the region outright.
[PolynomialProjection](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialProjection.kt)
then builds one polynomial family per level using Collins projection, which
preserves delineability including repeated roots and vanishing leading
coefficients. A required equality with a provably nonzero leading coefficient
restricts its level to that equation's roots.

**4. Lifting and truth.** Traversal walks the levels in order. Each projection
polynomial is specialized at the earlier coordinates, the
[kernel](28-exact-arithmetic.md#algebraic-arithmetic-and-lifting) isolates its real
roots, and roots become section samples with rational samples in the sectors
between them. A violated region literal prunes the cell. At a complete sample,
eliminated coordinates are reconstructed and the full assignment passes the
original formula before capture. Exhausting every cell refutes the region.

### Objective-image traversal

The image service adds an objective coordinate `t = f(source)` below all source
coordinates in the CAD order, so the objective line decomposes into sections and
sectors with invariant source feasibility. Those cells justify the
[objective certificates](#objective-certificates) above.

---

Previous: [23. Nonlinear integer arithmetic](23-nonlinear-integer.md) · [Contents](README.md) · Next: [25. Transcendental real arithmetic](25-transcendental.md)
