# 25. Transcendental real arithmetic

The last theory in this part checks constraints over exp, natural log, sin and cos,
with Boolean, integer and continuous coordinates. Real arithmetic with these
functions is not decidable in general, so the theory provides sound but incomplete
satisfaction. It builds directly on the [polynomial real theory](24-polynomial-real.md):
each function application is weakened into exact polynomial constraints that the
polynomial theory can refute, and candidate points are repaired and checked against
the original functions. It supplies no optimization procedure.

## Scope and boundaries

`TranscendentalExpression` and `TranscendentalFormula` are neutral immutable source
nodes. Exp is the real exponential; log is its real inverse on strictly positive
arguments; sin and cos use radians and exact pi. Integer declarations keep exact
integrality, and continuous coordinates never become finite CP domains.

Every arithmetic atom must have defined terms: invalid log arguments stay invalid
under negation and disjunction, so Boolean truth cannot turn an undefined term into
a witness. No delta or tolerance mode alters these semantics. Function objectives
return UNKNOWN/Unsupported. [SMT-LIB semantics](09-smtlib.md) defines the syntax.

## Solving and composition

### Polynomial relaxation

The service weakens the original formula into an exact polynomial formula. Each
function application and each use of pi becomes a fresh real auxiliary, constrained
by its domain, range and domain-bound affine relaxations from
[certified enclosures](28-exact-arithmetic.md#certified-transcendental-enclosures).
Rational enclosures propagate through sums, products and powers to bound the
arguments. A comparison between two applications of the same monotone function,
such as `exp(a) < exp(b)`, reduces to a comparison of the arguments. A completed
exact refutation of the relaxation refutes its source scope.

### Proposals and repair

Relaxation points and rational proposals undergo symbolic repairs. For a
conjunctive equality with a variable, or a function of a variable, on one side, the
repair assigns that variable the exact value of the other side, through the inverse
function where needed. These proposals carry no refutation authority; each point
still requires the complete original-source check of function atoms, integrality and
invocation conditions.
[Exact real coordinates](28-exact-arithmetic.md#exact-analytic-real-coordinates)
keep symbolic exp/log/sin/cos expressions.

### Strategies

The same model and completion service serve complete-style traversal, LS, ALNS and
MIXED. Traversal uses shared search frames and exhaustive complementary numeric
predicates. LS and ALNS use the shared exact-neighborhood move owner; their windows
are candidate restrictions, and their failures have neighborhood authority only.
`TranscendentalTheoryRequest` implements `ExactTheorySatisfactionRequest`, so
frontends consume the dispatcher without selecting a concrete theory.

## Results and proof authority

Exact SAT requires a complete original-source witness. Exact UNSAT requires a
whole-source relaxation proof, or exhaustion of exhaustive branches with every leaf
refuted. Finite depth, heuristic window exhaustion and unavailable equality
certification keep UNKNOWN. Exact special identities are supported; arbitrary
function-root equalities can stay uncertified. Floating residuals and algebraic
approximations never establish a transcendental equality.

## State and resources

Each pipeline call creates fresh mutable theory and strategy state; no native
workspace crosses workers. One cumulative native meter, reported as the
`transcendental` work scope, covers enclosure, algebraic, relaxation and
source-check work. The complete family reserves three quarters of a finite
allowance and LS/ALNS split the remainder. Complete slices pause at traversal
checkpoints; native queries are atomic and can overrun a segment. Interruption
cannot produce partial certificates.

The route returns UNKNOWN/Unsupported for deduction scopes, extra CP components,
finite tolerance acceptance and active finite event or incumbent-exchange hooks.

## Implementation

`TranscendentalTheoryService` owns conditioned weakening, proposal checks and
publication eligibility. `TranscendentalRelaxationModel` builds the polynomial
relaxation, `TranscendentalRepair` the symbolic repairs and
`TranscendentalSourceCheck` the original-source check.
`TranscendentalSearchComponent` consumes shared frames. The mechanisms depend only on
other arithmetic kernels, never on factors, search, propagation or `lp.engine`.

---

Previous: [24. Polynomial real arithmetic](24-polynomial-real.md) · [Contents](README.md) · Next: [26. LP architecture](26-lp-architecture.md)
