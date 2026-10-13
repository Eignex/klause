# Nonlinear integer arithmetic

`theory.qfnia` searches `ir.NonlinearIntegerProblem`, a neutral polynomial source
snapshot with separate original integer, real and Boolean identities. Integer
coordinates embed into the polynomial coordinate space exactly. The snapshot
retains the source expression DAG; products and fixed powers are not expanded by
the input format. Source integer arithmetic remains arbitrary precision.
Typed SMT fallback exposes this snapshot through `SmtLibInput.NonlinearInteger`.
Its `pipelineRequest()` retains the shared prepared source plan and uses the common
exact verdict and witness surface. Original declaration mappings remain in that
plan; arithmetic-owned coordinates do not become finite projection columns. Bounded
representable factors compose through the shared polynomial model rather than a
separate constraint API.

## Solving and composition

Search checks the whole real relaxation first. A completed real refutation also
refutes the integer source. An integral relaxation point is a candidate requiring
an original-source check. Other points supply rounded heuristic seeds only.
Candidate traversal enumerates integer tuples around zero and source constants,
then widens its candidate set. These are search choices, never source bounds or
finite CP domains. Its candidate allowance can stop an incomplete traversal.

Mandatory conjunctive unary source bounds are intersected at arbitrary precision,
including strict integer floor/ceiling semantics. Boolean alternatives and guarded
bounds do not narrow the source. This arithmetic state remains theory-owned; it
creates no finite CP domains. Empty proved source/condition intersections can
refute their actual scope. Candidate proposals stay within these proved sides and
caller windows, and out-of-window candidates are rejected before completion.

Each candidate fixes every original integer coordinate to an exact rational
integer and asks the existing [polynomial service](polynomial-real.md#conditioned-theory-service)
to complete real coordinates and correlated Booleans. Failure refutes only that
candidate. A real-relaxation representation decline permits fixed integer candidates under
the same work and allocation controls. It supplies no proof. Wide exponents use
compact squaring DAGs; values such as zero and signed units can remain exactly
checkable without materializing the unconditioned polynomial degree.
Numeric conditional auxiliaries remain private to the formula and are
completed under their guarded source definitions.

The heuristic strategies start with an integer candidate, then release one
coordinate for local repair or two for a block repair. Conditioned real completion
supplies a rounded integer proposal. Failed neighborhoods select source-constant
or widening integer moves; repeated candidates trigger an escape move. Every
proposal is checked with all integers fixed. Mixed execution alternates candidate
traversal, single-coordinate repairs and block repairs. All strategies retain the
same formula. Heuristic failure and finite candidate exhaustion produce UNKNOWN.
Only search can publish a whole-source real-relaxation refutation.

The public pipeline uses the [shared exact neighborhood hook](../solver/local-search.md)
for LS and adaptive ALNS. Centers retain arbitrary-width exact integers. Boolean
pins and integer windows map to original source coordinates, while real coordinates
remain completion-owned. Source equalities supply initial proposals, including wide
values; guarded equalities do not become source bounds. Each callback offers up to
sixteen integer candidate attempts under its exact windows. When multiple integer
coordinates are free it uses coordinate repairs; fixed/single-coordinate windows
use exact relaxed completion with checked integer candidates. A failed real
relaxation covers only that neighborhood; integer candidate exhaustion is undecided.

Mixed execution supplies retained whole-source traversal, LS and adaptive ALNS
factories to the [common invocation](../solver/portfolio.md). Materialized workers
keep private cursors and cumulative native reservations, with explicit parallel
lanes and stable seeds. Candidate and completed repair checks are atomic with
respect to segment limits. A segment pause preserves state; containing native
allowance exhaustion stops optional work. Neighborhood refutations remain local.
Default whole-source candidates are 1,024, heuristic moves 256 and ALNS iterations
50. Every definitive result receives the containing scope's final stop check.

## Results and proof authority

### Quadratic objectives

`OpenTheoryPipeline.minimize` accepts the same nonlinear integer request with an
exact source `QuadraticObjectiveSpec`. Declaration-ordered integer and real output
identities map into the existing numeric source coordinates; conditional numeric
auxiliaries remain completion-owned. Every incumbent checks source integrality,
the original formula and the exact source objective before publication.

Quadratic complete traversal uses `solver.search.SearchSession` with a theory
component. Exact real optimization supplies node lower bounds in canonical
minimization direction. Fractional source integer coordinates split into
complementary arbitrary-precision predicates `i <= k` and `i >= k + 1`. The shared
session owns assertions, retraction and the frame stack. An integral attained real
minimum closes its integer node. A fixed-integer continuous completion reaching
the same real infimum can close an unattained node. Other integral samples select
exhaustive integer splits; they do not close an unresolved node. This search may
remain incomplete on unbounded trees.

Bounds prune only against checked feasible incumbents. Objective pruning belongs
to the shared node policy and supplies no source conflict or learned infeasibility
clause. Complete exhaustion combines closed branch limits and pruning evidence
before publishing an optimum, unattained limit or refutation. A stopped or
undecided branch withholds these claims while retaining its incumbent and any
separately checked root relaxation bound.

A real relaxation's unboundedness does not establish integer unboundedness.
A fixed-integer continuous unbounded completion is sufficient. Affine source
constraints also admit checked rational quadratic recession rays whose integer
direction coordinates are scaled to exact integers. An integral source base and
nonnegative integer ray parameters then give unlimited source improvement.
Unproved recession proposals supply no global claim.

LS and ALNS optimize exact source neighborhoods through the same shared traversal
and real completion. Initial integer proposals use unrestricted real completion;
later real windows retain exact incumbent isolating intervals. Boolean pins and
single-coordinate, block and whole-tuple moves preserve typed source identities
and wide integer centers. Adaptive operator weights reward incumbent improvement.
Neighborhood proof does not become a whole-source proof. MIXED offers a bounded
adaptive phase before complete traversal. Every branch and repair shares cumulative
numerical/polynomial work and the containing decision/move allowance. Optional real
representation decline allows a fixed-integer completion attempt; an unresolved
whole-source decline remains explicit.

Witness publication requires integral original integer coordinates and exact
completion of the entire formula. A separate iterative evaluator checks the
original expression DAG using exact rational/algebraic arithmetic, including both
sides of every relation and the retained Boolean correlation. An arithmetic stop
or representation decline during this check withholds the witness.
Rounding selects proposals; it cannot replace an exact witness coordinate.

## State and resources

`NonlinearIntegerCursor` retains source bounds, tuple enumeration, visited candidates,
proposal RNG, widening radius and the polynomial invocation through candidate slices.
Segment cancellation yields before another candidate; a conditioned arithmetic call
and its source witness check are atomic with respect to that segment. Containing
cancellation continues to poll inside arithmetic. Closing releases the invocation
and candidate buffers; captured witnesses keep their owned exact coordinates.

One cancellation and work scope spans hint collection, candidate generation,
condition assembly, all real completion queries and the final source check.
Native work reaches the caller's meter once. Candidate allowances, cancellation,
polynomial work limits and representation declines remain explicit incomplete
causes. No finite-box exhaustion claims UNSAT, and no terminating decision
guarantee is made for nonlinear integer arithmetic. Published telemetry separates
candidates, heuristic moves, block repairs, completion queries and native
polynomial work. The mechanism reads no finite-domain state or linear theory
internals.

Conditioned calls retain original Boolean pins and exact numeric windows. Candidate
fixings intersect these bounds; they cannot replace or widen them. A real
refutation from a conditioned call covers only that call's scope. Fresh calls
capture independent conditions and retain the source snapshot unchanged.

## Implementation

`theory.qfnia` owns integer proposals, exact bounds and source checking.
`solver.pipeline.NonlinearIntegerTheoryRequest` adapts them to the shared
strategy and verdict surface; frontends render that surface.
`QuadraticIntegerComponent` owns trailed integer predicates and delegates bound
classification to the quadratic pipeline policy. The generic numerical kernel
remains independent of source factors, traversal and theory state.
