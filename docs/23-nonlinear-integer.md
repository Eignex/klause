# 23. Nonlinear integer arithmetic

Nonlinear integer constraints multiply or raise integer coordinates to powers,
alongside real and Boolean coordinates. Unlike the linear fragments of the previous
chapters, this fragment is undecidable in general, so the theory makes no
termination promise. Instead it searches integer candidates and, for each one, asks
the [polynomial real theory](24-polynomial-real.md) of the next chapter to complete
the real coordinates exactly. This theory owns integer proposals and integrality;
the polynomial theory owns exact real completion.

## Scope and boundaries

`theory.qfnia` searches `ir.NonlinearIntegerProblem`, a neutral polynomial source
snapshot with separate original integer, real and Boolean identities. The snapshot
keeps the source expression DAG; products and fixed powers are not expanded, and
integer arithmetic stays arbitrary precision. SMT admission keeps the snapshot
through `SmtLibProblem.source`.

Library setup can also project a factor model into this execution when it contains
open integer declarations and every factor has a complete selected polynomial
encoding. The projection emits declared endpoints and membership holes exactly and
creates no finite CP domains for open integers; unsupported scopes decline.

## Solving and composition

Every strategy starts from the same two facts about the source.

- **The real relaxation.** Search first checks the whole formula with integers
  relaxed to reals. A completed real refutation also refutes the integer source. An
  integral relaxation point is a candidate; other points only seed rounded
  proposals.
- **Proved unary bounds.** Mandatory conjunctive unary bounds are intersected at
  arbitrary precision, including strict integer floor/ceiling semantics. An empty
  intersection refutes its scope. Guarded bounds do not narrow the source.

Each candidate fixes every original integer coordinate and asks the
[conditioned polynomial service](24-polynomial-real.md#conditioned-theory-service)
to complete real coordinates and correlated Booleans. Failure refutes only that
candidate. Wide exponents use compact squaring DAGs, so values such as zero and
signed units stay checkable without expanding the polynomial degree.

### Strategies

Candidate traversal enumerates integer tuples around zero and source constants,
then widens; these are search choices, never source bounds. Heuristic strategies
release one coordinate for local repair or two for a block repair, use rounded
completion proposals, and escape repeated candidates. The public pipeline also uses
the [shared exact neighborhood hook](15-local-search.md) for LS and adaptive ALNS,
with up to sixteen integer candidate attempts per callback. Heuristic failure and
finite candidate exhaustion produce UNKNOWN.

MIXED supplies retained whole-source traversal, LS and ALNS factories to the
[common invocation](16-portfolio.md). Defaults are 1,024 whole-source candidates,
256 heuristic moves and 50 ALNS iterations.

## Results and proof authority

Witness publication requires integral original integer coordinates and exact
completion of the entire formula. A separate evaluator checks the original
expression DAG with exact rational and algebraic arithmetic; a stop or
representation decline withholds the witness. Rounding selects proposals; it never
replaces an exact coordinate.

Only a completed real-relaxation refutation or an empty intersection of proved
bounds refutes the whole source. No finite-box exhaustion claims UNSAT.

### Quadratic objectives

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

## State and resources

`NonlinearIntegerCursor` keeps source bounds, tuple enumeration, visited candidates,
proposal randomness, widening radius and the polynomial invocation across slices. A
conditioned arithmetic call and its source check are atomic with respect to
segments, while cancellation still polls inside arithmetic. One cancellation and
work scope spans candidate generation, all completion queries and the final source
check. Conditioned calls keep original Boolean pins and numeric windows; candidate
fixings never widen them.

## Implementation

`theory.qfnia` owns integer proposals, exact bounds and source checking.
`solver.pipeline.NonlinearIntegerTheoryRequest` adapts them to the shared strategy
and verdict surface. `QuadraticIntegerComponent` owns trailed integer predicates and
delegates bound classification to the quadratic pipeline policy.

---

Previous: [22. Linear mixed arithmetic](22-linear-mixed.md) · [Contents](README.md) · Next: [24. Polynomial real arithmetic](24-polynomial-real.md)
