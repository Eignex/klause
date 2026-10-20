# 25. Transcendental real arithmetic

The last theory in this part checks constraints over exp, natural log, sin and cos,
with Boolean, integer and continuous coordinates. Real arithmetic with these
functions is not decidable in general, so the theory provides sound but incomplete
satisfaction. It builds directly on the [polynomial real theory](24-polynomial-real.md):
each function application is weakened into exact polynomial constraints that the
polynomial theory can refute, and candidate points are repaired and checked against
the original functions.

It follows the [shared theory contract](18-theories.md). It does not decide
unrestricted transcendental arithmetic or supply an optimization procedure.

## Scope and boundaries

`TranscendentalExpression` and `TranscendentalFormula` are neutral immutable source
nodes. Exact rational constants, arithmetic operations and declaration identities
are kept. Exp is the real exponential; log is its real inverse on strictly positive
arguments. Sin and cos use radians, exact pi and their real periodic semantics.
Integer declarations keep exact integrality, including arbitrary-width values;
continuous coordinates never become finite CP domains.

Every arithmetic atom must have defined terms. Invalid log arguments stay invalid
under negation, conjunction and disjunction, so Boolean formula truth cannot turn an
undefined term into a witness. Numeric and Boolean conditionals evaluate the
selected branch. Distinctness keeps every original operand and requires its domain
even when another Boolean alternative is true. No delta or tolerance mode alters
these semantics.

SMT-LIB admission captures lexical function terms in this neutral model and renders
closed exact values. Function objectives are captured and return
UNKNOWN/Unsupported rather than entering a satisfaction or polynomial-objective
route. [SMT-LIB semantics](09-smtlib.md) defines the extension syntax.

## Solving and composition

### Polynomial relaxation

The service weakens the original formula into an exact polynomial formula. Each
function application and each use of pi becomes a fresh real auxiliary, constrained
by its polynomial domain, its range and applicable domain-bound affine relaxations
from [certified enclosures](28-exact-arithmetic.md#certified-transcendental-enclosures).
Rational enclosures propagate through sums, products and powers to bound the
arguments. Conditionals introduce a guarded auxiliary for each branch. A comparison
between two applications of the same monotone function, such as `exp(a) < exp(b)`,
reduces to a comparison of the arguments, with positivity enforced for log.

A completed exact refutation of the relaxation refutes its source scope; incomplete
relaxation work does not.

### Proposals and repair

Relaxation points and rational proposals undergo symbolic direct and inverse repairs.
For a conjunctive equality whose one side is a variable, or a function applied to a
variable, the repair assigns that variable the exact value of the other side, through
the inverse function where needed: log for exp, exp for log, and special values for
sin and cos. An exact completion of the polynomial rows can also propose algebraic
coordinates before function repair. These proposals carry no refutation authority;
each point still requires the complete original-source check, which verifies the
function atoms, integrality and every invocation condition.
[Exact real coordinates](28-exact-arithmetic.md#exact-analytic-real-coordinates) keep
symbolic exp/log/sin/cos expressions with rational and algebraic leaves.

### Strategies

The same original model and conditioned completion service serve complete-style
traversal, LS, ALNS and MIXED. Traversal uses shared search frames and exhaustive
complementary numeric predicates, keeping strict endpoints and retraction. LS and
ALNS consume `ExactNeighborhoodCompletion` and the shared exact-neighborhood move
owner; their rational centers and windows are candidate restrictions, and their
completion failures have neighborhood authority only.

`TranscendentalTheoryRequest` implements the shared source-bound
`ExactTheorySatisfactionRequest`, so frontends consume the dispatcher without
selecting a concrete theory. MIXED supplies retained complete, LS and ALNS recipes to
`PortfolioInvocation`; explicit cores materialize private replicas with shared lanes,
slot seeds, quotas, admission and cleanup. Single-arm execution at one core and FIXED
keep their standalone behavior. Checked witnesses can be published by any strategy;
heuristic failure cannot publish whole-source UNSAT.

## Results and proof authority

Exact SAT requires a complete original-source witness. Exact UNSAT requires a
whole-source relaxation proof, or exhaustion of exhaustive branches with every leaf
refuted. Any uncertified leaf prevents an exhaustive verdict. Finite depth, heuristic
window exhaustion and unavailable equality certification keep UNKNOWN. An invented
finite box cannot refute an unrestricted source.

Exact special identities are supported; arbitrary function-root equalities can stay
uncertified. Floating residuals, a zero heuristic score and algebraic approximations
never establish a transcendental equality. No local improvement establishes a global
optimum. In-memory exact CAD relaxation checks are not an exported proof.

## State and resources

Each pipeline call captures its source conditions and creates fresh mutable theory
and strategy state. A shared invocation plan owns the source coordinate mapping and
satisfaction-only capabilities. Every materialized slot owns its service and its
retained traversal or neighborhood; its native service opens on first use, and no
native workspace crosses workers. A complete worker without Boolean decisions checks
its source root before allocating traversal storage. Checked publication envelopes
keep their actual invocation, so a point checked under another call's strict real
restrictions cannot be admitted by identity alone.

One cumulative native meter, reported as the `transcendental` work scope, covers
enclosure, algebraic, relaxation and source-check work; queries, repairs, branches
and retractions do not renew it. The complete family reserves three quarters of a
finite allowance and LS/ALNS split the remainder; single-family invocations divide
the whole allowance among their slots, and unused reservations are not transferred.
Slots also divide decision, instruction and move caps. Each charge reaches the
containing caller meter once.

Complete slices pause at traversal checkpoints, keeping the pending alternative,
component conditions and cumulative charges. Native queries are atomic with respect
to slices and can overrun a segment. Neighborhood slices keep centers, random state,
radius and weights. Cancellation, timeout, work allowances, bit limits and polynomial
allocation limits cannot produce partial certificates; a checked witness stays valid
after later interruption.

The route returns UNKNOWN/Unsupported for deduction scopes, extra CP components,
finite tolerance acceptance and active finite event, seeding, scoring or
incumbent-exchange hooks. Boolean and integer backend pins belong to the selected
single strategy, while MIXED requires pins in the common invocation scope. Every
supplied node allowance must reference the same shared budget; competing ledgers
decline.

## Implementation

`TranscendentalTheoryService` owns conditioned weakening, proposal checks and
publication eligibility. `TranscendentalRelaxationModel` builds the polynomial
relaxation, `TranscendentalRepair` the symbolic repairs and
`TranscendentalSourceCheck` the original-source check.
`TranscendentalSearchComponent` consumes shared frames, and the pipeline request and
neighborhood adapter keep source scope across strategies. The numerical mechanisms
depend only on other arithmetic kernels and neutral controls, never on factors,
search, propagation or `lp.engine`.

---

Previous: [24. Polynomial real arithmetic](24-polynomial-real.md) · [Contents](README.md) · Next: [26. LP architecture](26-lp-architecture.md)
