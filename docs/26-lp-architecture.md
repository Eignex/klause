# 26. LP architecture

Part V describes the numerical mechanisms that search and the theories of Part IV
call on. The linear programming engine comes first because it has the most
consumers: finite CP/COP relaxations, mixed-integer models and the open linear theories of
chapters [20](20-linear-real.md)–[22](22-linear-mixed.md) all run on one
production floating revised simplex with exact certification. Shared search owns
integer, comparison and disequality branches; LP feasibility alone does not
establish integrality.

The flow is to assemble a source-aware linear model, solve its floating working
representation, certify the requested result, then let the consumer reconstruct
or apply it. Bound changes and retraction update that same numerical scope. This
chapter follows that flow: engine boundaries, exact authority and consumer
integration come before private basis mechanics. The [next chapter](27-lp-certification.md)
defines the proof packages a certified result carries, and
[chapter 29](29-numerical-dependencies.md) the external kernel interfaces.

## Boundaries

```text
CP / COP, mixed-integer models, open theories, shared search
                         |
           model adapters, LpPropagator, effort policy
                         |
         exact model, bound trail, float solve, certification
                         |
       simplex.basis, simplex.exact, lp.lattice, util
```

[RevisedSimplex](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/RevisedSimplex.kt)
is the floating implementation and
[ProductionLpEngineFactory](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpEngineFactory.kt)
the default factory; `ComponentLpSolver` composes it for component-wise OBBT.
`lp.engine` depends only on `util`, `simplex.exact`, `lp.lattice` and
`simplex.basis`; model assembly, factors, propagation, presolve and search stay
outside it, and consumers use interfaces and factories. See
[system boundaries](02-architecture.md).

## Exact source row projections

Native exact linear rows keep their rational source meaning independently of
finite filtering and numerical emission. Compact coefficients use the integer LP
builder; wider coefficients use an exact positive row normalization whose rational
entries remain certificate authority beside the floating projection. A
normalization that cannot project to finite nonzero floating values declines.

Exact-linear preparation keeps templates for every source row and original column.
Unconditional rows are installed up front; guarded, Boolean-dependent and
disjunctive rows materialize when asserted, and a row enabled by an integer
indicator records both bound premises and retires when the pin retracts. Bounds
retract with the search scope while definitions stay available to siblings. These
definitions supply no witness authority: publication checks the original rows.

## Numerical authority

The exact matrix, costs, objective sense/scale/constant, bounds, strictness,
integrality, origins, source premises, ordered basis and nonbasic statuses determine
the problem. Floating factors, values and reduced costs are revision-keyed working
state; pricing weights are heuristic history.

The model uses `[A | I] x = b` with bounded structural and logical columns.
`ExactLpModel` supports arbitrary bounds and origins; re-centering changes bounds,
RHS and objective constant together. Compact Long inputs keep authority beyond
`2^53`, wider data uses rationals, and a Double input denotes its exact IEEE value.
Projection to Double must not infer fixedness from equal rounded endpoints. The
engine supports missing bound sides and FREE statuses. MPS decimal authority is
separate; see [MPS semantics](08-mps.md).

## Consumers, explanations and cuts

Several consumers share the engine, each owning its own search state. The engine
answers bound, fixing and conflict queries; the consumer decides when to ask, how
to apply the answer and how to retract it.

### Bounding and explanations

[LpPropagator](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
maps source decisions to bound assertions, retracts through the trail and supplies
certified bounds, fixings and Farkas conflicts under an effort governor. Farkas
explanations cite the selected bound witnesses and all contributing row premises;
every learned literal is false at the conflict and implied by the cited antecedents.
One integer or disjunction leaf cannot refute its sibling branches.

For mixed models, node bounding uses the
[mixed deduction operations](27-lp-certification.md#mixed-deduction-operations),
keeps the strongest checked objective bound and applies only integral consequences
with complete explanations. For a bound `L`, reduced cost `d(j)` and cutoff `U`, a
fixing allows movement at most `(U - L) / abs(d(j))` from the minimizing endpoint.
Objective changes retract dependent bounds, cuts and clauses, or restore an
objective-independent region.

### Cuts

Cuts aggregate exact source rows and apply their rounding rule's bound and
integrality checks. Local cuts keep their substitutions and premises and are
revalidated after pop or remap. Selection considers efficacy, parallelism,
orthogonality, age and row caps; separators check actual column integrality, so
continuous columns decline. Root harvesting caps retained cuts at the original row
count, and root and node separation keep their round, depth, pool and work
allowances. The [portfolio](16.3-portfolio-recipes.md#lp-arm-admission) decides when
an aggressive LP companion runs.

### Root preparation

Finite satisfaction and optimization share root hull pruning and cut harvesting;
objective shaving and incumbent-relative fixings are optimization work. Root
preparation runs once, under a common time and work allowance, before shared
initialization publishes its bounds; a cancelled phase contributes only completed
deductions. Root rounding and pumping propose discrete assignments for mixed
models, but a relaxation point supplies a target, never witness authority.

### Leaf completion

Mixed witness recovery can submit a connected real residual to `SourceLp` after
fixing integer guesses, within its `SourceLpBudget`. Only exact primal coordinates
are consumed, and a failed guess supplies no conflict or bound. Real array selection
emits index-range rows on every build, and a fixed index adds its equality with both
index premises. In every case the original source rows decide publication.

## Bound trail and warm state

[LpBoundTrail](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpBoundTrail.kt)
intersects assertions exactly and keeps a source premise for each accepted side.
Batches publish witnesses only for a committed prefix; crossed bounds cite both
sides, and pop restores bounds and deactivates scoped rows.

A nonbasic bound change updates the basic values by `-B^-1 A(j) delta(x(j))`; pop
reseats nonbasics and recomputes `x(B) = B^-1 (b - N x(N))`. Compatible edits and
pops retain factors; missing sides require status and dual-feasibility repair, and
objective edits invalidate dependent proofs.
[LpScopedSolver](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpScopedSolver.kt)
and [CpLpAdapter](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/CpLpAdapter.kt)
hold bound trails, active-row identity and verification caches.

Rows have stable identities. Deactivation removes slack bounds, strictness and
integrality, and is rejected while surviving assertions need the row; global
source-valid cuts can survive pop. Appends and compaction prepare a fresh
fixed-dimension owner with warm headings and publish it before retiring the donor;
live factors are never resized. A projection that cannot import exact authority
declines without a partial layout: node bounding withholds deductions and leaf
completion returns indeterminate.

## Floating simplex and basis factors

[SimplexWorkspace](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexWorkspace.kt)
owns fixed-dimension scratch for one engine, with distinct indexed carriers for
entering spikes, pivotal rows, RHS and dual solves so a basis backend can reuse the
form it produced. [SimplexBasisLifecycle](../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/SimplexBasisLifecycle.kt)
owns headings, statuses and the lazy basis backend, and invalidates them on
retirement even when close fails. `SimplexPricingState` owns Devex weights and
`SimplexObservations` per-solve counters; `RevisedSimplex` coordinates allowance
charging, recovery and publication.

Dual leaving selection uses verified Devex, reselecting when a maintained weight
falls below `0.25 * norm(rho)^2`. Entering selection uses a scaled Harris window,
a ministep floor and a breakpoint walk. Zero-objective solves choose between
`MIN_BOUND_SUPPORT` (default) and `LARGEST_PIVOT`. Refactorization is triggered by
estimated work and fill, sampled residuals or a hard update cap.

Power-of-two scaling guards lost nonzeros, overflow and underflow, and results map
back to unscaled authority. Safeguards include small-pivot checks, refactor and
recompute, logical repair, unscaled fallback and bounded Bland recovery on primal
stalls. A dual-infeasible cold slack basis can try bounded dual phase I on a working
copy; an artificial box never authorizes a source proof, ray or bound.

[simplex.basis](../klause/src/commonMain/kotlin/com/eignex/klause/simplex/basis)
owns sparse and small dense elimination, DFS reach, Forrest-Tomlin updates, logical
repair, indexed forward/transpose solves and rational factors. Each owner has fixed
dimension, both permutations and three-valued update results. Dense partial-pivot
LU is selected only for dimensions 2–6 with at least 50% density in the selected
basis; it produces the same CSC factors as sparse LU, and a numerical decline
retries sparse. Rational factors reuse orderings but never approximate numeric
factors, and only complete exact rank determination establishes singularity. Koblas
supplies the containers and kernels; see
[integration contracts](29-numerical-dependencies.md).

## Selected factor relaxation providers

`RelaxationProjectionProvider` selects emitters from immutable factor data; a
descriptor declares whether it supplies a sound relaxation, and `NONE` emits no
rows. A custom emitter must preserve every source solution and is rebuilt against
live domains, which disables retained layouts. A sound relaxation supplies bounds
and refutations, not feasibility: real leaf completion keeps the original built-in
source rows, and omitting a row never removes its constraint from the witness check.
Circuit arcs, Boolean RLT rows and scheduling families use only factors that keep
their built-in descriptors.

---

Previous: [25. Transcendental real arithmetic](25-transcendental.md) · [Contents](README.md) · Next: [27. LP certification](27-lp-certification.md)
