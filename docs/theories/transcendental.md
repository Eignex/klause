# Transcendental real arithmetic

The transcendental service checks original exp, natural log, sin and cos constraints
with Boolean, integer and continuous coordinates. It provides sound incomplete
satisfaction execution through the shared pipeline. It does not decide unrestricted
transcendental arithmetic or supply an optimization procedure.

## Source semantics

`TranscendentalExpression` and `TranscendentalFormula` are neutral immutable source
nodes. Exact rational constants, arithmetic operations and declaration identities
are retained. Exp is the real exponential; log is its real inverse on strictly
positive arguments. Sin and cos use radians, exact pi and their real periodic
semantics. Integer declarations retain exact integrality, including arbitrary-width
values; continuous coordinates never become finite CP domains.

Every arithmetic atom must have defined terms. Invalid log arguments remain
invalid under negation, conjunction and disjunction. Boolean formula truth cannot
turn an undefined arithmetic term into a witness. Numeric and Boolean conditionals evaluate
the selected branch. Unsupported domain certification retains unknown separately
from unknown atom truth. No delta or tolerance mode alters these semantics.

## Solving and composition

The same original model and conditioned completion service serve complete-style
traversal, LS, ALNS and MIXED. Traversal uses shared search frames and exhaustive
complementary numeric predicates, retaining strict endpoints and retraction.
The service weakens original functions into exact polynomial domain, range and
applicable domain-bound affine constraints. A completed exact refutation of that
relaxation refutes its source scope. Incomplete relaxation work does not refute it.

Relaxation points and rational proposals undergo symbolic direct/inverse repairs.
Each final witness passes original function checks, exact integer integrality and
every invocation condition. [Exact real coordinates](../arithmetic/real.md) retain
symbolic exp/log/sin/cos expressions with existing rational/algebraic leaves.

LS and ALNS consume `ExactNeighborhoodCompletion` and the shared exact-neighborhood
move owner. Their rational centers and windows are candidate restrictions. Their
completion failures have neighborhood authority only. MIXED reserves one quarter
of remaining native work and wall time for adaptive neighborhoods, then permits
traversal under the containing remainder. Checked witnesses can be published by
any strategy. Heuristic failure cannot publish whole-source UNSAT.

## Results and limits

Exact SAT requires a complete original-source witness. Exact UNSAT requires a
whole-source relaxation proof or exhaustion of exhaustive branches with every
leaf refuted. Any uncertified leaf prevents an exhaustive verdict. Finite depth,
heuristic window exhaustion and unavailable equality certification retain UNKNOWN.
An invented finite box cannot refute an unrestricted source.

Exact special identities are supported; arbitrary function-root equalities can
remain uncertified. Floating residuals, a zero heuristic score and algebraic
approximations never establish a transcendental equality. No local improvement
establishes a global optimum. In-memory exact CAD relaxation checks are not an
exported transcendental proof checker.

## State and resources

Each pipeline call captures its source conditions and creates fresh mutable theory
and strategy state. One cumulative native meter covers enclosure, algebraic,
relaxation and source-check work. Queries, repairs, branches and retractions do not
renew it. Native work is reported separately from strategy nodes and moves, and
each arithmetic charge reaches the containing caller meter once.

Cancellation, timeout, work allowance, bit limits and polynomial allocation limits
cannot produce partial certificates. Required representation declines remain
visible. Zero native work prevents entry. A checked witness remains valid after
subsequent interruption. Published immutable values retain no invocation owner.

The source route rejects unsupported assumption deductions explicitly; Boolean
and integer pins and rational/strict real bounds belong to the call scope. The
source snapshot remains unchanged and can serve independent later invocations.

## Implementation

`TranscendentalTheoryService` owns conditioned weakening, proposal checks and
publication eligibility. `TranscendentalSearchComponent` consumes shared frames.
The pipeline request and neighborhood adapter retain source scope across strategies.
Numerical mechanisms depend only on other arithmetic kernels and neutral controls;
they do not depend on factors, search, propagation or `lp.engine`.
