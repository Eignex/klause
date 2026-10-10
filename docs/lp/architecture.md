# LP architecture

Klause uses one production floating revised simplex with exact certification. The
engine serves finite CP/COP relaxations, mixed-integer models and open linear
arithmetic. Shared search owns integer, comparison and disequality branches;
LP feasibility alone does not establish integrality.

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

[RevisedSimplex](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/RevisedSimplex.kt)
is the floating implementation.
[ProductionLpEngineFactory](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpEngineFactory.kt)
is the default factory. `UnscaledLpEngineFactory` constructs the same engine with
scaling disabled. `ComponentLpSolver` composes it for component-wise OBBT.

Among Klause packages, `lp.engine` depends only on `util`, `simplex.exact`,
`lp.lattice` and `simplex.basis`. Model assembly, factors, propagation, presolve
and search stay outside it. `lp.lattice` depends only on `util`.
Consumers use interfaces and factories. See [system boundaries](../architecture.md).

## Numerical authority

The exact matrix, costs, objective sense/scale/constant, bounds, absent sides,
strictness, integrality, origins, source premises, ordered basis and nonbasic
statuses determine the problem. Floating factors, values and reduced costs are
revision-keyed working state; pricing weights are heuristic history.

The model uses `[A | I] x = b` with bounded structural and logical columns. Terms
named by atoms have defining rows and columns. `LpBuilder` shifts finite lower
bounds to zero; `ExactLpModel` also supports explicit arbitrary bounds and origins.
Re-centering changes bounds, RHS and objective constant together.

Compact Long inputs retain authority beyond `2^53`; wider data uses rational
authority. Double-only input denotes its exact finite IEEE value. Projection to
Double must preserve the source maps and must not infer fixedness from equal
rounded endpoints. MPS decimal authority and tolerance correspondence are separate
from the engine's exact model; see [MPS semantics](../formats.md#mps).

The engine supports missing bound sides and native FREE statuses. The CP adapter
still splits lower-unbounded real variables as `x = x+ - x-` and adds a row for
a finite upper side. A nonbasic status must refer to an existing exact bound, or
zero for a free column.

## Bound trail and warm state

[LpBoundTrail](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpBoundTrail.kt)
intersects assertions exactly. It retains effective witnesses and weaker active
assertions. Crossed bounds cite both sides; pop restores bounds and deactivates
scoped rows.

A nonbasic bound change updates the basic contribution by `-B^-1 A(j) delta(x(j))`.
A basic-bound edit changes feasibility rather than its value, except when source
coordinates shift. Pop seats nonbasics at restored status bounds and recomputes
`x(B) = B^-1 (b - N x(N))` from an aggregated RHS. Compatible bound edits and pops
retain factors; missing sides require status and dual-feasibility repair.
Objective edits use primal warmth from a feasible basis and invalidate dependent
proofs and continuation state.

[LpScopedSolver](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/engine/LpScopedSolver.kt)
and [CpLpAdapter](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/CpLpAdapter.kt)
retain bound trails, source/working authority, active-row identity and verification
caches. `PersistentLpSolver` supports exact-state adoption and bound resolution.
Failed adoption withholds requested-state results until an explicit successful
replacement; donor results cannot escape as current proofs.

Rows have stable identities. Deactivation removes effective slack bounds,
strictness and integrality, and is rejected when surviving logical assertions or
nonzero logical costs require the row. Global source-valid cuts can survive pop.
Physical removal seats the slack, remaps headings and rebuilds.

Appends and compaction prepare a fresh fixed-dimension owner with discrete warm
headings. Publish the replacement before retiring the donor; failed preparation
preserves it. Live factors are never resized. Rank repair consumes the complete
ordered column/unit-row result, rebuilds inverse headings and invalidates values,
duals and pricing. Failed repair falls back to logical columns. Mutable working
arrays cannot become shared authoritative caches or outlive their owner.

Eligible finite source models use the scoped `LpBounding.solveNode` path. An exact
import decline can retain a bounded fresh fallback with warm hints, metrics,
limits, cancellation and cleanup. A later scoped projection decline does not
enter that fallback. Fresh fallback does not retain factors between calls.
Factor retention alone is not a complete-consumer speed guarantee.

## Floating simplex and basis factors

Dual leaving selection uses verified Devex: correct and reselect a maintained
weight below `0.25 * norm(rho)^2`. Entering selection uses a scaled Harris window,
a ministep floor and an early-exit breakpoint walk. Nonzero objectives use Harris
selection; zero-objective solves expose `MIN_BOUND_SUPPORT` (default) and
`LARGEST_PIVOT`. Harris constrains candidates rather than adding a third pricing
policy. Transformed-support sampling has a cost of its own.

Refactor decisions include estimated work/fill, sampled residuals and a hard
update cap. Bound-update forward solves are charged too. Pricing weights reset
at refactorization. Routine fill/work triggers count as `UPDATE_LIMIT`; bad-residual
recovery counts as `NUMERICAL_RECOVERY`. A suspicious near-tolerance infeasibility
candidate gets a fresh-factor check before numerical acceptance.

Power-of-two scaling guards lost nonzeros, overflow and underflow; candidate
primals, duals, slacks and rays map back to unscaled authority. Safeguards include
small-pivot checks, refactor/recompute, logical repair, unscaled fallback and
primal stall Bland. Floating Bland is a bounded recovery heuristic.
Phase-I and working state are restored on every exit. A dual-infeasible cold
slack basis can try bounded dual phase I on a working copy. Artificial seats
must be released or widened before fallback; the artificial box cannot authorize
a source proof, ray or truncated bound.

[simplex.basis](../../klause/src/commonMain/kotlin/com/eignex/klause/simplex/basis/)
owns sparse and small dense elimination, DFS reach/density switching, Forrest-Tomlin updates,
logical repair, indexed forward/transpose solves and rational factors. Each
owner has fixed dimension, named columns, both permutations and three-valued
update results. Operation-owned buffers control scratch lifetime.
`KotlinBasisSolver.refactorize` selects dense partial-row-pivot LU only for dimensions
2–6 with at least 50% nonzero selected entries and more nonzeros than rows. The selected basis, rather than the
whole source matrix or stored zeros, determines density. Koblas GER performs Schur
updates; finite arithmetic and nonzero-underflow guards precede factor publication.
Dense elimination materializes the same owned CSC factors and permutations used by
sparse solves and Forrest-Tomlin updates. A numerical decline retries sparse LU and
charges both attempts. Logical repair retains sparse construction and its existing
cancellation control. `denseDimensionLimit = 0` is the internal sparse comparison
control; the `BasisSolver` factory remains the extension seam. Exact factors never
reuse floating numeric factors. The measured selection envelope is documented in
[dense basis evidence](../evidence/dense-basis.md).

Koblas supplies containers and compatible numerical kernels; see
[integration contracts](koblas.md).

Rational ordering reuse is enabled with an explicit off control and exact
fallback. Hints own permutations tied to the matrix and ordered basis; they
never reuse approximate numeric factors as exact factors. Unavailable updated-owner
hints decline before expensive identity traversal. Exact fill is recomputed,
zero pivots reorder exactly, and only complete exact rank determination establishes
singularity.

## Consumers, explanations and cuts

[LpPropagator](../../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
maps source decisions to bound assertions, retracts through the trail and supplies
certified bounds, fixings and Farkas conflicts under an effort governor. Open
adapters share normalized terms and justified root-fixed substitutions. They
retain defining rows needed by any active bound, objective or reconstruction map.
Derived bound batches retain a separate witness premise for each applied side and
refresh the numerical owner once. A declined batch publishes no bound changes;
a conflicting batch retains the applied prefix and its explanation premises.

Farkas explanations cite selected bound witnesses and all contributing scoped
row premises. Every learned literal is false at the conflict, and the cited
antecedents alone must imply it. One integer/disjunction leaf cannot refute its
sibling branches. Resolve branch proofs or retain a chronological unexplained
conflict. Current source explanations recursively expand derived premises.

Cuts aggregate exact source rows and apply their rounding rule's bound and
integrality checks. Local cuts retain substitutions, rounding bounds and
expanded premises. Guards are revalidated after pop or remap; unsupported maps
withhold the proof. Cut policy considers efficacy, parallelism, orthogonality,
age and row caps. An integer-specialized separator does not imply general
mixed-integer coverage.

For unrounded Lagrangian bound `L`, reduced cost `d(j)` and cutoff `U`, a fixing
allows movement at most `(U - L) / abs(d(j))` from the minimizing endpoint.
Use that certificate's costs and scale, round integer movement on the source
lattice, and retain cutoff and row/bound premises.

Objective changes retract dependent bounds, cuts and clauses. If dependent
learning cannot be retracted selectively, restore an objective-independent
region or prepare a fresh root. Portfolio sharing requires matching objectives,
cutoffs and assumptions; source publication validation precedes admission.

Problem-level presolve/inprocessing and LP root harvesting are reused. There
is no general problem presolve inside the tree or elimination of shared variable
IDs after portfolio forks. Owner-local exact factors, bounded refinement reuse,
counter-proofs and component OBBT remain scoped mechanisms. Triangular crash
initialization belongs to the optional LP tree-search route. Production primal
pricing recomputes scores. Open theory adapters propagate exact source-row
intervals with source premises; this stays outside the numerical engine. There
is no conflict-weakening framework.

[Certification](certification.md) defines accepted proof packages and budgets.
[Capture and replay](replay.md) describes diagnostics without equating a kernel
replay to a complete source solve.
