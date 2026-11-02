# 16. Nonlinear arithmetic

Nonlinear services meet at exact polynomial real completion. Nonlinear integer
search fixes integer candidates before completing reals; the polynomial theory
uses cylindrical algebraic decomposition (CAD); the transcendental theory weakens
functions into polynomial constraints and checks proposals against the originals.

## Nonlinear integer arithmetic

Nonlinear integer satisfaction is undecidable in general. This sound incomplete
service owns integer proposals and integrality, delegating exact real completion to
the [polynomial theory](#polynomial-real-arithmetic).

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

Optional root admission adds the complete integer roots of mandatory unary
equalities. It expands each conjunctive source equality whose syntax names one
original integer coordinate into an exact univariate polynomial, isolates its real
roots with the shared Sturm kernel, and bisects each isolating interval on the
integer grid by exact signs of the square-free polynomial, so wide roots cost
logarithmically many evaluations. Repeated roots count once, and equalities on one coordinate
intersect their sets. An empty set, or one disjoint from the caller's integer
window, refutes that scope. A nonempty set narrows the window to its extreme roots,
replaces the coordinate's traversal values, snaps repairs to the nearest root
(upper on ties) and skips tuples outside it before completion; witnesses still need
full completion and source checking. Constant differences, representation limits
and the admission allowance decline an equation without a proof. Guarded, negated,
multivariate and real equalities are not admitted.

Optional residue refutation admits equalities reached only through source
conjunctions, with shared original integer coordinates across every atom. It
evaluates the typed expression DAG over rings modulo 2, 3, 4, 5 and 8 without
expanding products or powers. Positive exact denominators at each circuit node
clear rational coefficients through integer edge scales; no ring division or
field inference is used. Before shared-tuple enumeration, each node receives a
bounded overapproximating residue image in every selected ring. Sums and products
combine independent operand images, conservatively losing alias correlations;
powers map each base residue by modular squaring. A completed disjoint scaled
equality image refutes the source. Overlapping images supply no fact and retain
shared-tuple enumeration for cross-atom correlations. Exhausting a ring without a
shared support also refutes the source. Surviving support supplies no integer
witness or proof.
An equality with real or auxiliary support declines the check. Unary-only systems
remain with root admission.

NIA-owned Boolean reduction also checks each equality atom independently over all
original integer coordinates, ignoring Boolean guards and numeric windows. A
completed impossible atom becomes false under conjunction, disjunction and
negation. Supported atoms remain unchanged; modular support never proves equality,
and disequalities and order predicates are not modularized. Only complete Boolean
coverage by impossible alternatives establishes source refutation. The reduced
formula is private to integer completion and never enters the pure-real route or
real-valid source-cell producer. Conditions still apply to every completion, and
every witness passes the original source evaluator.

Each candidate fixes every original integer coordinate and asks the
[conditioned polynomial service](#conditioned-theory-service)
to complete real coordinates and correlated Booleans. Failure refutes only that
candidate. Wide exponents use compact squaring DAGs, so values such as zero and
signed units stay checkable without expanding the polynomial degree.

An optional cell consumer filters unvisited, in-window tuples before their fixed
completion. It admits only checked mandatory predicates of the cursor's identical
unconditioned source formula, on original integer coordinates. Query and regional
cells decline. Exact algebraic ceiling and floor convert each admitted cell to its
integer hull, preserving open and closed endpoints at arbitrary precision. A hull
representation decline removes that cell's authority. These exclusions skip
completion calls; exhausting them still supplies no whole-source refutation.

An independently enabled proposal policy alternates ordinary attempts with the
nearest tuple outside those hulls. For each original integer coordinate it crosses
covering hulls monotonically in both directions, intersects the caller's exact
integer window and chooses the closer surviving endpoint, preferring the upper
endpoint on ties. Empty complements, unchanged tuples and visited proposals retain
ordinary traversal. Selection never rejects a released real repair window.

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

Only a completed real-relaxation refutation, an empty intersection of proved
bounds, an empty admitted integer root set or a completed empty residue system
refutes the whole source. Each applies
within the caller's Boolean and numeric assumptions. No finite-box exhaustion,
including exhaustion of nonempty root sets, claims UNSAT.

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

Resumable traversal forwards its containing deadline and work meter to the shared
session. Clause maintenance and arithmetic completion debit that same cumulative
allowance across slices.

A real relaxation's unboundedness does not establish integer unboundedness. A
fixed-integer unbounded completion is sufficient, as is, with affine constraints, a
checked quadratic recession ray scaled to integer direction coordinates from an
integral base. A neighborhood proof never becomes a whole-source proof.

### State and resources

Residue checks run once per cursor entry under the containing arithmetic allowance.
They use a private 65,536-step ceiling, at most 16,384 modeled storage cells, and
4,096-bit coefficients and cleared denominators; configured polynomial coefficient
and matrix ceilings further limit storage. Formula admission, DAG preparation,
denominator work, image storage and set operations, and shared tuple evaluation
all charge the containing meter. Atom checks, repeated-node caching, proof storage
and Boolean reconstruction share that same private allowance. An unfinished
reduction discards its replacements; completed replacements remain cursor-local.
Private ceilings decline without proof; containing cancellation or exhaustion stops
the cursor. Completed checks remain inside that cursor and use no numeric window
or Boolean pin as a residue premise, so no conditional refutation can escape to a
wider invocation. Initialization is atomic with respect to candidate slices.

`NonlinearIntegerCursor` keeps source bounds, tuple enumeration, visited candidates,
proposal randomness, widening radius and the polynomial invocation across slices. A
conditioned arithmetic call and its source check are atomic with respect to
segments, while cancellation still polls inside arithmetic. One cancellation and
work scope spans candidate generation, all completion queries and the final source
check. Conditioned calls keep original Boolean pins and numeric windows; candidate
fixings never widen them.

The optional producer and integer consumer are independently controlled and disabled
by default. Each cursor retains at most eight source cells. Admission, integer hull
conversion and membership use its containing arithmetic allowance; cached hulls and
support-hit counts survive slices and retire on close. Integer hulls never refute
partial real repair windows. Invocation observations sum private cursor admissions,
declines, newly excluded tuples, repeated support hits and completion calls; they
do not authorize cross-cursor proof reuse.

Cell-complement steering is disabled by default and requires the producer and
consumer. Selection scans and transient proposal coordinates debit the containing
work allowance. Each selected or fallback tuple consumes one ordinary candidate
attempt, including duplicate, out-of-window and excluded attempts; caps and slice
boundaries are unchanged. Counters distinguish selection calls, changed unvisited
proposals, distinct tuples reaching completion and duplicate attempts.

Root admission is disabled by default and independent of the cell controls. An
invocation admits its source once, spending at most 65,536 work units per equation
and 262,144 overall from the containing allowance; later cursors reuse the completed
sets for one step per rooted coordinate. A containing stop leaves admission
incomplete for the next cursor, while an exhausted admission allowance declines
only the remaining equations. Counters report admitted and declined equations,
reuses, refuted cursor scopes and skipped tuples.

### Implementation

`theory.qfnia` owns integer proposals, exact bounds and source checking.
`solver.pipeline.NonlinearIntegerTheoryRequest` adapts them to the shared strategy
and verdict surface. `QuadraticIntegerComponent` owns trailed integer predicates and
delegates bound classification to the quadratic pipeline policy.

## Polynomial real arithmetic

The polynomial theory decides rational-coefficient real formulas by CAD and completes
reals beside finite search. Nonlinear integer and transcendental services reuse its
conditioned checks. The [polynomial kernel](18-exact-arithmetic.md#exact-polynomial-and-algebraic-arithmetic)
owns arithmetic; this service owns formula decisions and projection policy.

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

`SharedPolynomialModel` installs selected polynomial encoders, source bounds and
private definitions under the [prepared ownership plan](02-model.md#engine-projections-and-factor-capabilities).
Only finite integers used by encoded equations receive numeric slots; their domains
and globals remain finite-owned. Clauses keep Boolean disjunctions without numeric
selectors. Surviving open integers select the
[nonlinear integer adapter](#nonlinear-integer-arithmetic).

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

`solveQfNra` normalizes exact polynomials and Boolean structure. Scalar-equivalent
predicates share truth and complements use opposite truth, with exact negative-scale
order reversal and distinct strict/weak zero boundaries. Rewrites preserve singular
fibres, source Boolean identities and numeric predicate order, fold duplicate or
complementary children and constants, and compact unreachable nodes.

Root reduction substitutes Booleans defined by mandatory numeric equivalences, then
mandatory total graph equalities into every atom. Opposite weak bounds can establish
an equality; optional guards and variable-dependent leading coefficients cannot
justify global elimination. Remaining definitions and condition pins stay enforced.
Each completed rewrite commits an immutable formula snapshot with its own persistent
reverse-fibre chain. Representation decline can retain a completed prefix; interrupted
rewrites publish no snapshot.

Reduction and search retain separate cursors under one allowance, advancing the one
with less charged work. Search starts at the first committed rewrite or finished
unchanged formula and survives intermediate commits. A finished different formula,
or a representation decline with a newer snapshot, can replace its cursor. Only the
latest snapshot and one search cursor survive; discarded work remains charged and
regional refutations never transfer across snapshots. A containing stop ends both;
optional probing keeps one cumulative allowance across retries.

The decision procedure has four stages:

1. **Bounded probe.** Necessary sign/magnitude conditions can refute conjunctive
   atoms; rational and algebraic repairs propose points. A proposal reconstructs
   eliminated coordinates and checks the original normalized formula. This optional
   small-formula prefix has a 65,536-unit slice.
2. **Boolean regions.** `PolynomialBooleanRegions` enumerates source implicants
   over Boolean leaves and signed numeric predicates. Numeric assignment/retraction
   runs exact sign propagation before further decisions. Completed feasible fixpoints
   extend with appended predicates and rebuild after changed premises; unchanged
   conjunctions reuse them. A contradiction learns a private exclusion over all
   assigned numeric premises, valid under any Boolean prefix in that enumerator.
   Feasible overapproximations and unfinished checks learn nothing. Exclusions stay
   within the query, condition and snapshot. Incremental gate support follows affected
   nodes and parents; region capture keeps the first decisive child in source order
   or all identity children. Separate visited-region exclusions prevent repetition.
   Neither exclusion kind enters shared learning.
3. **Reduction and projection.** Ordered remainders simplify predicates modulo
   retained zero-equality premises. Linear equalities are substituted and linear-only
   inequality coordinates eliminated. Affine endpoint comparisons can add
   fewer-variable consequences while retaining original bounds and strictness;
   variable coefficients are never divided out. Exact convex quadratic certificates
   can refute conjunctions against affine premises; their anchors supply no witness.
   `PolynomialProjection` then builds Collins families preserving delineability,
   repeated roots and vanished leading coefficients. A required equality with a proved
   nonzero leading coefficient restricts its level to its roots.
4. **Lifting.** Specialized roots form sections and rational points form intervening
   sectors. Exact partial substitution can prune impossible sign requirements when
   remaining monomials have even powers and one coefficient sign; unresolved cases
   stay open. Complete samples use certified coordinate enclosures before scalar
   images, reconstruct eliminated coordinates and check the original formula.

Projection and lifting alternate over increasing families in source order. Every
sample checks all regional predicates, including unprojected ones. A rejection
independent of varying projected coordinates advances the family without enumerating
its remaining Cartesian product. Partial-family rejection cannot refute a region:
exhaustion requires complete CAD or an exact equality contradiction. Representation
decline advances to the complete family under the same remaining allowance. Region,
projection and lifting counters record actual attempts across snapshot replacements.
Only region coverage, projection and lifting are required for completeness.

An internal optional univariate refinement precedes root reduction and partial-family
lifting. Mandatory predicates seed a bounded coordinate candidate; a violated unary
predicate isolates all of its roots before certifying the containing section or
sector as forbidden. The next candidate lies outside retained cells and within the
query window. A seed checks the entire normalized formula; regional repairs alter
only unfixed coordinates absent from the selected projection family. Reconstruction
and full source checking retain witness authority, while complete CAD remains the
fallback. Optional failure supplies no refutation.

Checked cells retain exact algebraic endpoints, openness and their numeric predicate.
Source support requires a mandatory predicate of the unconditioned immutable source.
Source and query intervals retain their captured coordinate meaning. An eliminated
CAD slot holds a placeholder until reconstruction; reuse needs a checked coordinate
map or an explicit surviving-predicate match before reading that slot.
Other cells retain the query condition and snapshot; regional cells additionally keep
signed source premises and an opaque affine-reduction owner. Such cells cannot cross
owners without checked source reconstruction. Integer point coverage does not imply
coverage of a real repair window. The internal option is disabled by default.
Its query-wide allowance is 16,384 charged units across preparation, candidate checks,
retries and snapshot replacements, with eight retained cells, degree sixteen,
64 terms per predicate and 4,096-bit input coefficients. Regional support and seeds
admit at most 64 premises or coordinates. A private decline preserves CAD; an enclosing
stop remains terminal. Invocation observers receive completed checked cells only.

An internal regional feedback option consumes checked cells before repeating a
unary predicate evaluation. A hit requires the active predicate and source identity;
query facts also require their condition and snapshot, and regional facts require
their signed premises and opaque reduction owner. The pool keeps at most eight
references, drops facts after premise retraction and clears on snapshot replacement.
Eligible immutable producer facts can rebuild it under the current scope. Admission,
lookup and eviction poll and charge the caller; endpoint comparisons use its exact
arithmetic. A hit already eligible for the producer's candidate selector establishes
repeated membership without a second comparison. These numeric filters supply no
Boolean clause and preserve the producer's candidate traversal, full witness checks
and complete CAD fallback. Numeric atom checking can also consult retained cells
after candidate production declines. This read-only path has a separate cumulative
8,192-unit allowance charged to the same query, retaining the producer's scope or a
fresh regional owner that admits only eligible Source/query facts. It preserves atom
order and independent-family bookkeeping; private resource or allowance declines run
the original atom check, while an enclosing stop remains terminal. Both paths share
the bounded view. Reuse diagnostics retain at most two scalar snapshots of degree
sixteen with 4,096-bit coefficients and bounds, without optional image provenance.
Larger points are marked incomplete. The option is disabled by default.

An internal optional coupled-section refutation runs before a completed Boolean region
is issued. For each assigned multivariate predicate, the enumerator selects by
dependency support the first assigned univariate equation of each of its coordinates.
When every coordinate is covered, the equations' real roots are isolated exactly and the
signed predicate is evaluated at every root combination, bisecting the isolating
intervals before any exact algebraic evaluation. Failing at all of them, or a
covering equation without real roots, refutes that support: the enumerator learns an
exclusion over exactly those signed premises, valid under every Boolean prefix within
its query, condition and snapshot. Retraction keeps the remaining Boolean alternatives,
and the exclusion never enters shared learning. After individual checks, connected
covered predicates are checked as a conjunction over one streamed product of root
sets. Each common combination must fail at least one predicate before a joint
exclusion is learned; individual witnesses need not agree.

If direct predicates expose no refutation, a private support view eliminates total
equational graphs with nonzero rational pivots. Matching rational multiples of weak
inequalities can supply an equality only when their signed intersection is exactly
zero; the support retains every participating inequality. Each rewritten predicate retains
its original signed premise and all definitions used by its substitutions. Selection,
rewriting, root isolation and checking share the same containing allowance. The
view neither mutates the query snapshot nor reconstructs source coordinates; its
exclusion refers to the active encoded premises, including assigned definitions.
Activation and frontend attribution remain with the prepared pipeline. Variable
coefficients cannot supply total substitutions, including on nullified fibres.
Incomplete coverage, an already attempted support, a representation decline or the
exhausted query-wide allowance of 16,384 units learns nothing and keeps the complete
path; an enclosing stop remains terminal. Predicates admit the checked-cell term, degree
and coefficient limits and at most 64 premises. The option is disabled by default.

Optional query failure capture retains bounded exact observations of rejected probe
points and CAD prefixes, their signed premises, numeric reconstruction fibres and
condition/snapshot identity. Projection observations retain the active operands.
Root, search and partial-sign cursors restore their own bounded operation/context
records when resumed. Partial-sign observations retain full signed premises within
the capture limits, including the first completed sign conflict.
Capture polls and charges the containing allowance; incomplete copies and bounded
repetition comparisons remain explicitly incomplete. These diagnostic records
carry no exclusion or source-refutation authority.

#### Objective-image traversal

The image service adds an objective coordinate `t = f(source)` below all source
coordinates in the CAD order, so the objective line decomposes into sections and
sectors with invariant source feasibility. Those cells justify the
[objective certificates](#objective-certificates) above.

## Transcendental real arithmetic

The transcendental service gives sound incomplete satisfaction for exp/log/sin/cos
constraints. Polynomial relaxations can refute a scope; symbolic repairs and original
function checks can establish witnesses. It supplies no optimization procedure.

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
