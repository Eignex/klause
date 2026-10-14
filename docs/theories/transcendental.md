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

`TranscendentalTheoryRequest` implements the shared source-bound
`ExactTheorySatisfactionRequest` callback. Frontends can consume that dispatcher
without selecting a concrete theory. Its native work scope is `transcendental`;
the total includes enclosure and source checks as well as polynomial relaxation.
It is distinct from a count of polynomial steps alone.

Relaxation points and rational proposals undergo symbolic direct/inverse repairs.
An exact completion of the retained polynomial rows can also propose algebraic
coordinates before function repair. That proposal projection supplies no refutation
authority; its points still require the complete original-source check.
Each final witness passes original function checks, exact integer integrality and
every invocation condition. [Exact real coordinates](../arithmetic/real.md) retain
symbolic exp/log/sin/cos expressions with existing rational/algebraic leaves.

LS and ALNS consume `ExactNeighborhoodCompletion` and the shared exact-neighborhood
move owner. Their rational centers and windows are candidate restrictions. Their
completion failures have neighborhood authority only. MIXED supplies retained complete,
LS and ALNS recipes to `PortfolioInvocation`. Explicit cores materialize private
replicas and use its shared lanes, slot seeds, quotas, admission and cleanup.
Single-arm execution at one core and FIXED keep their standalone behavior; FIXED
does not replicate. Checked witnesses can be published by any strategy.
Heuristic failure cannot publish whole-source UNSAT.

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
and strategy state. A shared invocation plan owns its source coordinate mapping and
satisfaction-only capabilities. Every materialized slot owns its service and retained
traversal or neighborhood; its native service opens on first use, and closing an
unused worker prevents later opening. A complete worker without Boolean decisions
checks its actual source root before allocating traversal storage. Only a completed
native witness or refutation settles that root; an unresolved query enters shared
traversal with its private outcome and retained charges. No native workspace crosses workers. Checked publication
envelopes retain that actual invocation and immutable symbolic coordinates. A fresh
invocation plan prevents a point checked under another call's strict real restrictions
from being admitted by identity alone.

One cumulative native meter covers enclosure, algebraic,
relaxation and source-check work. Queries, repairs, branches and retractions do not
renew it. Parallel aggregation serializes charges to the containing caller meter; a
single lane charges directly without a synchronization lock.
The complete family reserves three quarters of a finite native allowance, and LS/ALNS
split the remainder. Complete-only and heuristic-only invocations divide the whole
allowance among their materialized slots; unused reservations are not transferred.
Slots also divide complete decision/instruction caps and heuristic move/instruction
caps. Explicit parallel lanes retain persistent solve-wide node quotas.
Traversal uses the smaller CP decision/instruction allowance. Native work is
reported separately from strategy nodes and moves, and
each arithmetic charge reaches the containing caller meter once.

Complete slices pause at traversal checkpoints, retaining the pending alternative,
component conditions and cumulative arithmetic charges. Complete native queries are
atomic with respect to slices; the counted segment includes native work and decisions,
and a query can overrun the segment. Neighborhood slices retain centers, random state,
radius and weights; an interrupted neighborhood check remains undecided.
Whole-invocation and backend cancellation reach active native completion through the
shared search context or neighborhood token. Closing releases retained traversal and
neighborhood state; completed immutable samples remain readable. Cancellation, timeout, work allowance, bit limits and polynomial allocation limits
cannot produce partial certificates. Required representation declines remain
visible. Zero native work prevents entry. A checked witness remains valid after
subsequent interruption. Published immutable values retain no invocation owner.

The source route returns UNKNOWN/Unsupported for deduction scopes, extra CP
components, finite tolerance acceptance and active finite event/seeding/scoring or
incumbent-exchange hooks. Boolean and integer backend pins belong to the selected
single strategy; common pins take precedence under the assumption merge contract.
MIXED requires pins in the common invocation scope and explicitly declines nested
backend pins. Rational/strict real bounds belong to the call scope. Every supplied
node allowance must reference the same shared budget; competing ledgers decline. The
source snapshot remains unchanged and can serve independent later invocations.

SMT-LIB admission captures lexical function source terms in this neutral model and
renders closed exact values. Function objectives remain captured and return
UNKNOWN/Unsupported rather than entering a satisfaction or polynomial-objective
route. [SMT-LIB semantics](../formats/smtlib.md) defines the extension syntax.

## Implementation

`TranscendentalTheoryService` owns conditioned weakening, proposal checks and
publication eligibility. `TranscendentalSearchComponent` consumes shared frames.
The pipeline request and neighborhood adapter retain source scope across strategies.
Numerical mechanisms depend only on other arithmetic kernels and neutral controls;
they do not depend on factors, search, propagation or `lp.engine`.
