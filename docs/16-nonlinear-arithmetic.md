# 16. Nonlinear arithmetic

Nonlinear constraints multiply coordinates, raise them to powers or apply
transcendental functions. These fragments are undecidable in general or need far
heavier machinery than an LP, and all three theories in this chapter meet in one
place: exact polynomial real completion. Nonlinear integer arithmetic searches
integer candidates and asks the polynomial real theory to complete the reals;
polynomial real arithmetic decides polynomial formulas exactly with a cylindrical
algebraic decomposition; transcendental arithmetic relaxes exp, log, sin and cos
into polynomial constraints and repairs candidates against the original functions.

## Nonlinear integer arithmetic

Nonlinear integer constraints multiply or raise integer coordinates to powers,
alongside real and Boolean coordinates. Unlike the linear fragments, this fragment
is undecidable in general, so the theory makes no termination promise. It searches
integer candidates and, for each one, asks the
[polynomial real theory](#polynomial-real-arithmetic) to complete the real
coordinates exactly. This theory owns integer proposals and integrality; the
polynomial theory owns exact real completion.

### Scope and boundaries

`theory.qfnia` searches `ir.NonlinearIntegerProblem`, a neutral polynomial source
snapshot with separate original integer, real and Boolean identities. The snapshot
keeps the source expression DAG; products and fixed powers are not expanded, and
integer arithmetic stays arbitrary precision. SMT admission keeps the snapshot
through `SmtLibProblem.source`.

Library setup can also project a factor model into this execution when it contains
open integer declarations and every factor has a complete selected polynomial
encoding. The projection emits declared endpoints and membership holes exactly and
creates no finite CP domains for open integers; unsupported scopes decline.

### Solving and composition

Every strategy starts from the same two facts about the source.

- **The real relaxation.** Search first checks the whole formula with integers
  relaxed to reals. A completed real refutation also refutes the integer source. An
  integral relaxation point is a candidate; other points only seed rounded
  proposals.
- **Proved unary bounds.** Mandatory conjunctive unary bounds are intersected at
  arbitrary precision, including strict integer floor/ceiling semantics. An empty
  intersection refutes its scope. Guarded bounds do not narrow the source.

Each candidate fixes every original integer coordinate and asks the
[conditioned polynomial service](#conditioned-theory-service)
to complete real coordinates and correlated Booleans. Failure refutes only that
candidate. Wide exponents use compact squaring DAGs, so values such as zero and
signed units stay checkable without expanding the polynomial degree.

#### Strategies

Candidate traversal enumerates integer tuples around zero and source constants,
then widens; these are search choices, never source bounds. Heuristic strategies
release one coordinate for local repair or two for a block repair, use rounded
completion proposals, and escape repeated candidates. The public pipeline also uses
the [shared exact neighborhood hook](10-local-search.md) for LS and adaptive ALNS,
with up to sixteen integer candidate attempts per callback. Heuristic failure and
finite candidate exhaustion produce UNKNOWN.

MIXED supplies retained whole-source traversal, LS and ALNS factories to the
[common invocation](12-portfolio.md). Defaults are 1,024 whole-source candidates,
256 heuristic moves and 50 ALNS iterations.

### Results and proof authority

Witness publication requires integral original integer coordinates and exact
completion of the entire formula. A separate evaluator checks the original
expression DAG with exact rational and algebraic arithmetic; a stop or
representation decline withholds the witness. Rounding selects proposals; it never
replaces an exact coordinate.

Only a completed real-relaxation refutation or an empty intersection of proved
bounds refutes the whole source. No finite-box exhaustion claims UNSAT.

#### Quadratic objectives

`OpenTheoryPipeline.minimize` accepts the same request with an exact source
`QuadraticObjectiveSpec`. Every incumbent checks integrality, the original formula
and the exact objective before publication.

Complete traversal uses `SearchSession` with a theory component. Exact real
optimization supplies node lower bounds, and fractional integer coordinates split
into `i <= k` and `i >= k + 1`. An integral attained real minimum closes its node.
Bounds prune only against checked incumbents, and pruning supplies no learned
clause. Exhaustion publishes an optimum, unattained limit or refutation; a stopped
branch withholds these claims.

A real relaxation's unboundedness does not establish integer unboundedness. A
fixed-integer unbounded completion is sufficient, as is, with affine constraints, a
checked quadratic recession ray scaled to integer direction coordinates from an
integral base. A neighborhood proof never becomes a whole-source proof.

### State and resources

`NonlinearIntegerCursor` keeps source bounds, tuple enumeration, visited candidates,
proposal randomness, widening radius and the polynomial invocation across slices. A
conditioned arithmetic call and its source check are atomic with respect to
segments, while cancellation still polls inside arithmetic. One cancellation and
work scope spans candidate generation, all completion queries and the final source
check. Conditioned calls keep original Boolean pins and numeric windows; candidate
fixings never widen them.

### Implementation

`theory.qfnia` owns integer proposals, exact bounds and source checking.
`solver.pipeline.NonlinearIntegerTheoryRequest` adapts them to the shared strategy
and verdict surface. `QuadraticIntegerComponent` owns trailed integer predicates and
delegates bound classification to the quadratic pipeline policy.

## Polynomial real arithmetic

Polynomial real constraints multiply real coordinates, such as `x*y >= 1` or
`x^2 + y^2 = 2`. Where the [linear theories](15-linear-arithmetic.md) check rows
with an LP, this theory checks nonlinear real formulas exactly and completes
continuous coordinates beside finite search. Nonlinear integer arithmetic uses it as
the real completion service for fixed integer candidates, and the
[transcendental theory](#transcendental-real-arithmetic) relaxes its functions into
it. Its decision procedure is a cylindrical algebraic decomposition
(CAD) over exact algebraic numbers. Real values remain theory-owned; CP remains
Bool+Int only.

### Scope and boundaries

The complete fragment is polynomial real satisfaction with rational coefficients
and Boolean structure (QF_NRA). Shared composition supports finite integer domains
and exact quadratic objectives over Boolean, integer and real coordinates. Open
integer domains belong to [nonlinear integer arithmetic](#nonlinear-integer-arithmetic).
[SMT-LIB semantics](05-arithmetic-formats.md#polynomial-source-lowering) owns source admission
and the [exact polynomial kernel](18-exact-arithmetic.md#exact-polynomial-and-algebraic-arithmetic)
owns mathematical operations. Resource limits can interrupt even this complete
procedure.

### Solving and composition

#### Shared model composition

`PreparedSourcePlan` owns the shared source decomposition. Its
`SharedPolynomialModel` installs selected polynomial encoders and connected finite
encoders: exact linear rows, Boolean reification, real bounds, integer/real
products and `ContinuousProduct` become neutral polynomial formulas. Only integers
that appear in encoded equations receive numeric slots; their finite domains and
global constraints stay in CP, and relaxation rows cannot stand in for a factor's
semantics. A scope with open integer declarations selects the
[nonlinear integer adapter](#) instead; open integers are
never boxed.

Selected clauses retain their source Boolean disjunctions. Their literals enter
Boolean region search directly, without real selector coordinates or numeric
comparison predicates.

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

#### Conditioned theory service

`PolynomialTheoryService` captures one immutable neutral formula. Each
`PolynomialTheoryInvocation` owns a cumulative native work allowance across
conditioned checks; a check conjoins Boolean pins and rational bounds with the
formula. `PolynomialSearchComponent` consumes shared Boolean facts and integer
bounds. A query over an unfixed integer interval is a real relaxation: its
refutation can reject the finite branch, but its witness cannot accept it. Conflicts
without a clause explanation stay chronological.

#### Quadratic strategy execution

The quadratic overload of `OpenTheoryPipeline.minimize` uses the same source
request, finite projection and polynomial formula. Complete CP traversal visits
every finite assignment and completes its continuous objective exactly; branch
optima and unattained limits combine in objective order. An unknown branch
withholds global claims, while one checked unbounded branch proves unboundedness.
LS and ALNS repair neighborhoods by finite enumeration with exact continuous
completion. `solvePolynomialObjectiveImage` optimizes over a conditioned source with
the same IR, and `tryConvexPolynomialOptimum` admits affine rows with a quadratic
objective through the
[quadratic certificate kernel](18-exact-arithmetic.md#exact-quadratic-certificates).

### Results and proof authority

`OpenTheoryPipeline.execute` returns the common SAT/UNSAT/UNKNOWN result with
`PolynomialTheoryStats`. SAT requires a complete exact assignment that passes the
original normalized formula. UNSAT requires every Boolean region to be covered and
refuted; a representation-limited region withholds it. Timeout, cancellation and
resource exhaustion keep distinct termination reasons.

#### Objective certificates

Objective-image checks visit projected objective cells in ascending order, and every
earlier cell must be refuted before a global claim. The first feasible section
supplies an attained optimum with an exact witness; the first feasible sector with a
finite lower endpoint supplies an unattained infimum; a feasible negative-unbounded
sector establishes unboundedness; exhausting every cell establishes infeasibility.
The evidence is in-memory, not an exported CAD proof.

#### Witness ownership

[ExactNraAssignment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/ExactNraAssignment.kt)
captures Boolean buffers and exact real scalars, each represented by its rational
defining polynomial and root index and never rounded for publication. The
[API contract](19-api.md#exact-assignment-values) defines their public access.

### State and resources

`QfNraParams.maximumWork` is nullable for unlimited work; zero prevents entry. One
counted scope spans normalization, probing, projection, lifting, Boolean search and
the final poll. A stop returns `Unknown` with its reason, never a partial verdict.
Kernel allocation caps (65,536 dense coefficients and 262,144 matrix cells by
default) return `Unknown(ResourceLimit)`; see
[kernel resources](18-exact-arithmetic.md#invocation-resources). A complete
portfolio worker keeps its root preparation between slices; see
[portfolio preparation](12-portfolio.md#polynomial-root-preparation).

#### Invocation ownership

A pipeline request keeps an immutable source snapshot and can be reused; each
execution constructs its own arithmetic context, Boolean search and CAD traversal.
A conditioned invocation keeps its allowance across queries. A completed witness
can be reused for a later Boolean-only condition after a charged scan confirms
every pin; no refutation is reused across conditions. Published algebraic scalars
outlive the invocation.

### Implementation

[SharedPolynomialModel](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/SharedPolynomialModel.kt)
assembles the source decomposition.
[PolynomialTheoryService](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialTheoryService.kt)
owns conditioned checks, and
[QfNraSolver](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/QfNraSolver.kt)
implements the decision procedure. The pipeline uses
`SourceProblemRoute.PolynomialTheory`.

#### Decision procedure

`solveQfNra` decides a neutral `PolynomialRealFormula`. Normalization expands exact
rational polynomial expressions and keeps each atom's sign, relation and Boolean
structure. Scalar-equivalent numeric predicates share Boolean truth; complementary
relations use opposite truth values. Exact rational scaling accounts for order
reversal under a negative coefficient. Strict and weak comparisons retain their
different zero boundaries, and variable factors retain singular fibres. The
Boolean DAG retains original Boolean identities and the first numeric
representative, including after graph substitution. Constant numeric atoms retain
their exact polynomials; Boolean propagation evaluates their truth.

Before probing or Boolean search, required equivalences defining Boolean variables
by numeric predicates are substituted throughout the formula. Their tautologies
are removed, so a source implicant need not choose truth values for unused
definitions. Numeric predicates retain their input order through this rewrite.
An equivalence in an optional guarded branch cannot define a global
substitution. Remaining constraints, including other definitions of the same
Boolean variable and condition pins, keep their numeric consequences. Reduced
witnesses recover original Boolean values through the original normalized formula
check, even when no real coordinate was eliminated.

Mandatory numeric predicates are
extracted with Boolean propagation. Their exact total graph equalities are
substituted into every atom, including optional branches. Opposite weak bounds may
supply such an equality; an unforced guarded equation or an equation with a
variable-dependent leading coefficient cannot eliminate a coordinate. Sections are
ordered by fill across the complete formula, coefficient size and coordinate. An
independent prefix shares a rewrite: no replacement reads another selected target.
Substitutions retain reverse fibres. Existing arithmetic
representation limits can decline a substitution while preserving its completed
prefix; cancellation and work exhaustion withhold the result. Expansion uses the
caller's remaining work and representation limits.

The procedure then runs in four stages; only the last two are needed for
completeness.

**1. Bounded probe.** An optional prefix tries to settle the query cheaply. It
checks necessary conditions on conjunctive atoms (sign enclosures from coefficient
signs and monomial parity, magnitude bounds, cancelled common monomials) and
proposes exact points (rational centers, single-coordinate repairs from roots of a
specialized equality, coupled lines, dyadic points). Every proposal must pass the
complete formula check. A successful reduced point reconstructs all total graph
coordinates and passes the original normalized formula before publication. The
probe is limited to small formulas and one 65,536-unit slice; a decline leaves
complete CAD available.

**2. Boolean regions.** [PolynomialBooleanRegions](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialBooleanRegions.kt)
enumerates source implicants: assignments of Boolean leaves and signed numeric
predicates that make the formula true. Propagation treats numeric predicates as
opaque leaves, a proof scan keeps only supporting literals, and private exclusion
clauses prevent revisiting a region without entering shared learning.
Each query retains junction child-assignment counts through its Boolean trail.
Counts include repeated child edges and retract with their assignments; a unit
junction identifies its remaining child without a full child scan. Propagation
and retraction charge the containing work scope.

**3. Regional reduction and projection.** Each region's signed predicates form a
conjunction. Ordered polynomial remainders simplify other predicates modulo the
region's required zero equalities, which remain as premises. The conjunction
retains one ordered divisor index for its predicate remainders. Linear required
equalities are solved and substituted, coordinates
occurring only linearly in inequalities are eliminated by affine pairing, and sign
propagation can refute the region outright.
For a coordinate retained by another nonlinear or non-inequality predicate,
constant-coefficient affine lower and upper bounds also imply a comparison between
their endpoints. Consequences with fewer variables than the larger premise are
retained ahead of the original predicates, ordered by variable count. The original
bounds and nonlinear constraints remain required; strictness in either endpoint
makes the consequence strict. No variable-dependent coefficient is divided out.
Convex quadratic predicates are also checked against each affine predicate on the
same coordinates. Exact stationary certificates and the quadratic kernel's
semidefiniteness check can prove a positive lower bound, or a zero lower bound
excluded by a strict premise. These checks only refute the regional conjunction;
their anchors do not become witness proposals, and a declined certificate leaves
the predicates available for CAD.
The reduced conjunction supplies projection predicates; the original regional
predicates supply the witness check for both rewrites and coordinate elimination.
[PolynomialProjection](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qfnra/PolynomialProjection.kt)
then builds one polynomial family per level using Collins projection, which
preserves delineability including repeated roots and vanishing leading
coefficients. A required equality with a provably nonzero leading coefficient
restricts its level to that equation's roots.

Projection and lifting alternate over an increasing family in source polynomial
order. Every lifted sample is checked against all region predicates, including
those whose boundaries have not been projected. Rejected samples from a partial
family cannot exhaust the region: refutation requires the complete family's CAD
or an exact contradiction between required equalities. A representation refusal
on a partial family advances to the complete family under the same caller's
remaining allowance. Projection counters accumulate completed stages.

A rejected predicate over coordinates absent from a partial family's polynomials
has the same truth at every sample of that family: each absent coordinate has one
sector sample, or its fixed value. This rejection advances to the next source
polynomial instead of enumerating the remaining Cartesian product. Predicates
depending on a varying projected coordinate retain cell traversal.

**4. Lifting and truth.** Traversal walks the levels in order. Each projection
polynomial is specialized at the earlier coordinates, the
[kernel](18-exact-arithmetic.md#algebraic-arithmetic-and-lifting) isolates its real
roots, and roots become section samples with rational samples in the sectors
between them. Partial substitution uses exact assigned coordinates to bound a
predicate's sign when every remaining monomial has even powers and coefficients
of one sign. A strict constant sign remains strict after adding terms of that
sign; zero coefficients and boundary completions remain possible. An odd power,
mixed signs or a representation refusal leaves the prefix open. A predicate
whose required truth is impossible for every completion prunes the prefix.
At a complete sample, predicate signs use certified coordinate enclosures before
exact scalar images when those bounds establish the sign. Eliminated coordinates
are reconstructed and the full assignment passes the original formula before
capture. Exhausting every cell of the complete projection refutes the region.

Every root-level cell checks all region predicates. A deeper cell changes one
coordinate of an accepted parent prefix, so it rechecks only predicates reading
that coordinate, in their original order. The index includes every coordinate a
predicate reads, including coordinates below its final level. Retraction returns
to the accepted ancestor prefix before another child is checked.

A coordinate absent from every region predicate and with no boundary or section
at its projection level has one unrestricted sector. Its fixed value or the
canonical sector sample is assigned once, and traversal keeps frames for the
remaining levels.
Predicate readiness follows the current projection level rather than the number
of assigned coordinates. Witnesses retain every original real coordinate,
including those removed from the reduced constraints.

#### Objective-image traversal

The image service adds an objective coordinate `t = f(source)` below all source
coordinates in the CAD order, so the objective line decomposes into sections and
sectors with invariant source feasibility. Those cells justify the
[objective certificates](#objective-certificates) above.

## Transcendental real arithmetic

The transcendental theory checks constraints over exp, natural log, sin and cos,
with Boolean, integer and continuous coordinates. Real arithmetic with these
functions is not decidable in general, so the theory provides sound but incomplete
satisfaction. It builds directly on the [polynomial real theory](#polynomial-real-arithmetic):
each function application is weakened into exact polynomial constraints that the
polynomial theory can refute, and candidate points are repaired and checked against
the original functions. It supplies no optimization procedure.

### Scope and boundaries

`TranscendentalExpression` and `TranscendentalFormula` are neutral immutable source
nodes. Exp is the real exponential; log is its real inverse on strictly positive
arguments; sin and cos use radians and exact pi. Integer declarations keep exact
integrality, and continuous coordinates never become finite CP domains.

Every arithmetic atom must have defined terms: invalid log arguments stay invalid
under negation and disjunction, so Boolean truth cannot turn an undefined term into
a witness. No delta or tolerance mode alters these semantics. Function objectives
return UNKNOWN/Unsupported. [SMT-LIB semantics](05-arithmetic-formats.md#smt-lib-source-semantics) defines the syntax.

### Solving and composition

#### Polynomial relaxation

The service weakens the original formula into an exact polynomial formula. Each
function application and each use of pi becomes a fresh real auxiliary, constrained
by its domain, range and domain-bound affine relaxations from
[certified enclosures](18-exact-arithmetic.md#certified-transcendental-enclosures).
Rational enclosures propagate through sums, products and powers to bound the
arguments. A comparison between two applications of the same monotone function,
such as `exp(a) < exp(b)`, reduces to a comparison of the arguments. A completed
exact refutation of the relaxation refutes its source scope.

#### Proposals and repair

Relaxation points and rational proposals undergo symbolic repairs. For a
conjunctive equality with a variable, or a function of a variable, on one side, the
repair assigns that variable the exact value of the other side, through the inverse
function where needed. These proposals carry no refutation authority; each point
still requires the complete original-source check of function atoms, integrality and
invocation conditions.
[Exact real coordinates](18-exact-arithmetic.md#exact-analytic-real-coordinates)
keep symbolic exp/log/sin/cos expressions.

#### Strategies

The same model and completion service serve complete-style traversal, LS, ALNS and
MIXED. Traversal uses shared search frames and exhaustive complementary numeric
predicates. LS and ALNS use the shared exact-neighborhood move owner; their windows
are candidate restrictions, and their failures have neighborhood authority only.
`TranscendentalTheoryRequest` implements `ExactTheorySatisfactionRequest`, so
frontends consume the dispatcher without selecting a concrete theory.

### Results and proof authority

Exact SAT requires a complete original-source witness. Exact UNSAT requires a
whole-source relaxation proof, or exhaustion of exhaustive branches with every leaf
refuted. Finite depth, heuristic window exhaustion and unavailable equality
certification keep UNKNOWN. Exact special identities are supported; arbitrary
function-root equalities can stay uncertified. Floating residuals and algebraic
approximations never establish a transcendental equality.

### State and resources

Each pipeline call creates fresh mutable theory and strategy state; no native
workspace crosses workers. One cumulative native meter, reported as the
`transcendental` work scope, covers enclosure, algebraic, relaxation and
source-check work. The complete family reserves three quarters of a finite
allowance and LS/ALNS split the remainder. Complete slices pause at traversal
checkpoints; native queries are atomic and can overrun a segment. Interruption
cannot produce partial certificates.

The route returns UNKNOWN/Unsupported for deduction scopes, extra CP components,
finite tolerance acceptance and active finite event or incumbent-exchange hooks.

### Implementation

`TranscendentalTheoryService` owns conditioned weakening, proposal checks and
publication eligibility. `TranscendentalRelaxationModel` builds the polynomial
relaxation, `TranscendentalRepair` the symbolic repairs and
`TranscendentalSourceCheck` the original-source check.
`TranscendentalSearchComponent` consumes shared frames. The mechanisms depend only on
other arithmetic kernels, never on factors, search, propagation or `lp.engine`.

---

Previous: [15. Linear arithmetic](15-linear-arithmetic.md) · [Contents](README.md) · Next: [17. Linear programming](17-linear-programming.md)
