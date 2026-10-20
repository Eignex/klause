# 21. Linear integer arithmetic

Linear integer constraints combine integer coordinates with constant coefficients
and Boolean structure. The theory takes the [linear real component](20-linear-real.md)
and adds integrality: the rational relaxation is checked exactly, a fractional
point causes integer branching, and an exact lattice reduction keeps unbounded
problems finite where the source supports it. Open source bounds stay open instead
of being replaced by a finite box. The [mixed theory](22-linear-mixed.md) reuses
everything here.

## Scope and boundaries

The admitted surface consists of exactly representable linear conjunctions or
disjunctions and their Boolean structure. Nonconstant products belong to
[nonlinear integer arithmetic](23-nonlinear-integer.md). Finite globals remain in
their finite projection, and an open integer used by a CP-only factor requires a
valid owner or the route declines. An entirely admitted difference fragment can use
[difference logic](19-difference.md) instead. Finite declarations keep their
domain state and choices; the theory waits for authoritative finite assignments
before publishing a completed point.

## Solving and composition

Assertion, partial checks and conflict explanations work as in the
[linear real component](20-linear-real.md#solving-and-composition). Integer
coordinates enter the LP as columns with their exact source bounds, including
values beyond `Long`. Once every Boolean is assigned, the complete check decides
the remaining integer alternatives in a fixed order:

1. **Finite integers.** An unfixed finite integer is decided first, trying its
   value in a completed point as an equality, then the lower and upper remainders.
2. **Disjunctions and disequalities.** A disjunction is decided through its
   disjunct atoms. An integer disequality `t != b` splits into `t <= b - 1` and
   `t >= b + 1`.
3. **Reduction.** The component applies the bounded phase of Bromberger's
   double-bounded reduction to the selected rows. An infeasible reduced system
   refutes the leaf; a bounded one is branched inside.
4. **Plain branching.** When the reduction declines, a fractional open integer
   `x = v` splits at `x <= floor(v)` and `x >= floor(v) + 1`.

The theory adds no cutting planes: integrality is enforced only by these
exhaustive splits and the reduction. Every split is a named source-bound atom, so
conflict learning can cite it like any Boolean.

### The double-bounded reduction

Branch and bound alone need not terminate on an unbounded integer problem: the
relaxation can keep producing fractional points further out along an unbounded
direction. The reduction (M. Bromberger, "A Reduction from Unbounded Linear Mixed
Arithmetic Problems into Bounded Problems", IJCAR 2018) separates the part of the
system that keeps the search finite from the part that does not.

1. **Split.** A selected row `a x <= b` is double-bounded when `a x` also has a
   finite lower bound, supplied by a paired opposite row or by an exact LP minimum
   after a homogeneous cone LP proves the activity cannot decrease without limit.
   Other rows go to the unbounded set.
2. **Transform.** The double-bounded rows are rewritten into mixed echelon form
   with a Hermite integer tail, using unimodular column operations from
   [`lp.lattice`](26-lp-architecture.md) that preserve the integer lattice.
3. **Bound.** Forward substitution derives a range for every transformed
   coordinate. Integer pivots use inward-rounded division, so an empty integer
   interval is an exact refutation.
4. **Search.** The component branches only on bounded transformed integer columns.
   A fractional coordinate `v` splits as `sum(c_i * x_i) <= floor(v)` against
   `sum(c_i * x_i) >= floor(v) + 1`, a lattice direction over source integers.
5. **Extend.** The columns left free are completed against the unbounded rows. Each
   row is tightened by half the absolute sum of its free integer coefficients, so
   rounding an LP point of the tightened system to the nearest integers still
   satisfies the original rows. The point is mapped back and checked against every
   selected row.

The reduction result is cached per Boolean and disjunction leaf across branch nodes.
It is admitted only for small leaves: at most 512 Booleans, 128 factors, 128 rows
and 512 terms, with at most 128 active branches and 4,096-bit coefficients. Larger
leaves use plain branching, though their equality rows alone may still be reduced.

[Open portfolios](16-portfolio.md) can pair this proof-producing route with
source-checked local-search workers.

## Results and proof authority

`ExactLiraAssignment` carries arbitrary-precision integer coordinates. Publication
checks every selected source row and integrality. A rational relaxation point
establishes neither feasibility nor infeasibility. A leaf is refuted only by a
certified LP conflict, an infeasible reduction or exhaustion of its integer
branches.

### Objectives

An integer objective with no Boolean or continuous cost uses the integral descent.
Each round asks the theory route to decide the model plus one bound row stating
that the objective is at most the incumbent minus one. A witness tightens the row;
a refutation proves the incumbent optimal, or infeasibility in the opening round,
which has no bound row. The objective therefore enters as a constraint and needs no
optimizing simplex. A portfolio can feed the descent an external bound from another
worker, in which case a refutation proves only that nothing beats that value.

Every witness is also asked for an improving ray within its branch: a direction
preserving integrality along which the objective strictly decreases proves the
objective unbounded.

Other admitted linear objectives, including Boolean costs, use exact
strict-improvement rounds through `RationalTheoryDescent`, which supplies no
recession certificate and need not terminate on an unbounded objective.

## State and resources

Source rows and forms are prepared read-only. The invocation owns asserted scopes,
integer branches, reduction caches and LP state. Integer publications invalidate the
retained reduction; retraction restores the shared facts and component scope before
evidence can be reused.
[TheoryParams](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/TheoryParams.kt)
separates theory checks, open work, decisions and cancellation; native LP work
follows the [LP certification](27-lp-certification.md) resource contract.

## Implementation

[ExactLinearFragment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines linear factor admission.
[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
implements integer and mixed checks, the branch order and the reduction cache,
building on the echelon/Hermite and double-bounded split helpers in `lp` and
`simplex.exact`.
[OpenTheoryMinimizer](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/OpenTheoryMinimize.kt)
implements the integral descent. The pipeline names the integer-containing route
`EXACT_LIRA`.

---

Previous: [20. Linear real arithmetic](20-linear-real.md) · [Contents](README.md) · Next: [22. Linear mixed arithmetic](22-linear-mixed.md)
