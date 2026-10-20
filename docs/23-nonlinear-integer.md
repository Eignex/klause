# 23. Nonlinear integer arithmetic

Nonlinear integer constraints multiply or raise integer coordinates to powers,
alongside real and Boolean coordinates. Unlike the linear fragments of the previous
chapters, this fragment is undecidable in general, so the theory makes no
termination promise. Instead it searches integer candidates and, for each one, asks
the [polynomial real theory](24-polynomial-real.md) of the next chapter to complete
the real coordinates exactly. A real refutation of the whole relaxation is the one
whole-source proof it can produce.

The chapter therefore reads best together with the next one: this theory owns
integer proposals and integrality, the polynomial theory owns exact real
completion. Both follow the [shared theory contract](18-theories.md).

## Scope and boundaries

`theory.qfnia` searches `ir.NonlinearIntegerProblem`, a neutral polynomial source
snapshot with separate original integer, real and Boolean identities. Integer
coordinates embed into the polynomial coordinate space exactly. The snapshot keeps
the source expression DAG; products and fixed powers are not expanded by the input
format, and source integer arithmetic stays arbitrary precision. SMT admission
keeps the snapshot through `SmtLibProblem.source`, and `pipelineRequest()` uses the
shared prepared source plan and the common exact verdict and witness surface.
Arithmetic-owned coordinates do not become finite projection columns.

Library setup can also project a factor model into this execution when it contains
open integer declarations and every factor has a complete selected polynomial
encoding. The projection emits declared integer endpoints and finite membership
holes exactly, keeps real endpoints as their exact binary rational values, and
creates no finite CP domains for open integers. Whole-scope preflight checks support
and required source pins before installation; only a completed projection is
published. Unproved Boolean pins cannot authorize a conditioned encoding, and
unsupported scopes decline without proof.

## Solving and composition

Every strategy starts from the same two facts about the source.

- **The real relaxation.** Search first checks the whole formula with integers
  relaxed to reals. A completed real refutation also refutes the integer source. An
  integral relaxation point is a candidate that still needs an original-source
  check; other points only seed rounded proposals. A relaxation representation
  decline still permits fixed-integer candidates, but supplies no proof.
- **Proved unary bounds.** Mandatory conjunctive unary bounds are intersected at
  arbitrary precision, including strict integer floor/ceiling semantics. Boolean
  alternatives and guarded bounds do not narrow the source. An empty intersection
  can refute its actual scope. These bounds stay theory-owned and create no finite
  CP domains.

Each candidate fixes every original integer coordinate to an exact integer and asks
the [conditioned polynomial service](24-polynomial-real.md#conditioned-theory-service)
to complete real coordinates and correlated Booleans. Failure refutes only that
candidate. Wide exponents use compact squaring DAGs, so values such as zero and
signed units stay checkable without expanding the polynomial degree. Conditional
numeric results stay private to the source formula, and original-source checking
evaluates the selected branch exactly.

### Strategies

Candidate traversal enumerates integer tuples around zero and source constants,
then widens its candidate set. These are search choices, never source bounds.
Candidates stay inside the proved bounds and caller windows.

Heuristic strategies start from an integer candidate and release one coordinate for
local repair or two for a block repair; conditioned real completion supplies a
rounded integer proposal. Failed neighborhoods choose source-constant or widening
integer moves, and repeated candidates trigger an escape move. Every proposal is
checked with all integers fixed. Heuristic failure and finite candidate exhaustion
produce UNKNOWN.

The public pipeline uses the [shared exact neighborhood hook](15-local-search.md)
for LS and adaptive ALNS. Centers keep arbitrary-width exact integers, Boolean pins
and integer windows map to original source coordinates, and real coordinates stay
completion-owned. Each callback offers up to sixteen integer candidate attempts. A
failed real relaxation covers only that neighborhood.

MIXED supplies retained whole-source traversal, LS and ALNS factories to the
[common invocation](16-portfolio.md). Parallel single-strategy requests materialize
private replicas of their admitted arm; FIXED keeps one worker. Defaults are 1,024
whole-source candidates, 256 heuristic moves and 50 ALNS iterations.

## Results and proof authority

Witness publication requires integral original integer coordinates and exact
completion of the entire formula. A separate iterative evaluator checks the original
expression DAG with exact rational and algebraic arithmetic, including both sides of
every relation and the Boolean correlation. An arithmetic stop or representation
decline during this check withholds the witness. Rounding selects proposals; it
never replaces an exact witness coordinate.

For satisfaction, only search can publish a whole-source refutation, from a
completed real-relaxation refutation or an empty intersection of proved bounds. No
finite-box exhaustion claims UNSAT.

### Quadratic objectives

`OpenTheoryPipeline.minimize` accepts the same request with an exact source
`QuadraticObjectiveSpec`. Every incumbent checks source integrality, the original
formula and the exact objective before publication.

Complete traversal uses `solver.search.SearchSession` with a theory component. Exact
real optimization supplies node lower bounds. Fractional integer coordinates split
into complementary arbitrary-precision predicates `i <= k` and `i >= k + 1`. An
integral attained real minimum closes its node; a fixed-integer completion reaching
the same real infimum can close an unattained node. Other integral samples only
select splits. Bounds prune only against checked incumbents, and pruning supplies no
source conflict or learned clause. Complete exhaustion combines closed branch limits
and pruning evidence before publishing an optimum, unattained limit or refutation;
a stopped branch withholds these claims. The search may stay incomplete on unbounded
trees.

A real relaxation's unboundedness does not establish integer unboundedness. A
fixed-integer continuous unbounded completion is sufficient. With affine source
constraints, a checked rational quadratic recession ray scaled to exact integer
direction coordinates, from an integral base, also proves unlimited improvement.

LS and ALNS optimize exact source neighborhoods through the same traversal and real
completion; adaptive operator weights reward incumbent improvement. A neighborhood
proof never becomes a whole-source proof.

## State and resources

`NonlinearIntegerCursor` keeps source bounds, tuple enumeration, visited candidates,
proposal randomness, widening radius and the polynomial invocation through candidate
slices. A segment pause yields before another candidate; a conditioned arithmetic
call and its source witness check are atomic with respect to segments, while
containing cancellation still polls inside arithmetic. Closing releases the
invocation and candidate buffers; captured witnesses own their exact coordinates.

One cancellation and work scope spans hint collection, candidate generation,
condition assembly, all real completion queries and the final source check. Native
work reaches the caller's meter once. Candidate allowances, cancellation, polynomial
work limits and representation declines remain explicit incomplete causes.
Telemetry separates candidates, heuristic moves, block repairs, completion queries
and native polynomial work.

Conditioned calls keep original Boolean pins and exact numeric windows. Candidate
fixings intersect these bounds and never widen them. A refutation from a conditioned
call covers only that call's scope.

## Implementation

`theory.qfnia` owns integer proposals, exact bounds and source checking.
`solver.pipeline.NonlinearIntegerTheoryRequest` adapts them to the shared strategy
and verdict surface. `QuadraticIntegerComponent` owns trailed integer predicates and
delegates bound classification to the quadratic pipeline policy. The mechanism reads
no finite-domain state or linear theory internals.

---

Previous: [22. Linear mixed arithmetic](22-linear-mixed.md) · [Contents](README.md) · Next: [24. Polynomial real arithmetic](24-polynomial-real.md)
