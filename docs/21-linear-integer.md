# 21. Linear integer arithmetic

Linear integer constraints combine integer coordinates with constant coefficients
and Boolean structure. The theory takes the [linear real component](20-linear-real.md)
and adds integrality: the rational relaxation is checked exactly, a fractional
point causes integer branching, and an exact lattice reduction keeps unbounded
problems finite where the source supports it. Open source bounds stay open instead
of being replaced by a finite box chosen for the solver's convenience.

The [mixed theory](22-linear-mixed.md) in the next chapter reuses everything here
for models that also contain real coordinates. Both follow the
[shared theory contract](18-theories.md).

## Scope and boundaries

The admitted factor surface consists of exactly representable linear conjunctions
or disjunctions and their Boolean structure. Nonconstant products and other
nonlinear integer factors do not become linear theory constraints; they belong to
[nonlinear integer arithmetic](23-nonlinear-integer.md). Finite globals remain in
their admitted finite projection, and an open integer used by a CP-only factor
requires a valid owner or the route declines.

An entirely admitted difference fragment can use [difference logic](19-difference.md)
instead. Finite declarations keep their finite domain state and choices under
either pipeline policy. A bounded linear factor can contribute both propagation
and an encoder to joint reasoning; the theory waits for authoritative finite
assignments before publishing a completed point. SMT admission is defined by
[SMT-LIB semantics](09-smtlib.md).

## Solving and composition

Assertion, partial checks and conflict explanations work as in the
[linear real component](20-linear-real.md#solving-and-composition). Integer
coordinates enter the LP as columns with their exact source bounds, including
values beyond `Long`. What changes is the complete check: once every Boolean is
assigned, the component decides the remaining integer alternatives in a fixed
order.

1. **Finite integers.** A finite integer that is not yet fixed is decided first.
   When a completed point exists, its value at that integer is tried as an
   equality, followed by the lower and upper remainders. The finite owner keeps its
   bounds and holes on every alternative.
2. **Disjunctions and disequalities.** A source disjunction is decided through its
   registered disjunct atoms. An integer disequality `t != b` splits into
   `t <= b - 1` and `t >= b + 1`. The split shares names with source integer
   branching, because an integral activity has no value strictly between
   consecutive integers.
3. **Reduction.** With every Boolean and disjunction chosen, the component applies
   the bounded phase of Bromberger's double-bounded reduction to the selected rows,
   described in the next section. If the reduced system is infeasible, the leaf is
   refuted; if it is bounded, the component branches inside it.
4. **Plain branching.** When the reduction declines or is interrupted, a
   fractional open integer `x = v` splits at exact floor and ceiling thresholds
   `x <= floor(v)` and `x >= floor(v) + 1`.

The theory adds no cutting planes: integrality is enforced only by these
exhaustive splits and by the reduction. Every split is registered as a named
source-bound atom, so conflict learning can cite it like any Boolean.

### The double-bounded reduction

Branch and bound alone need not terminate on an unbounded integer problem: the
relaxation can keep producing fractional points further out along an unbounded
direction. The reduction (M. Bromberger, "A Reduction from Unbounded Linear Mixed
Arithmetic Problems into Bounded Problems", IJCAR 2018) separates the part of the
system that keeps the search finite from the part that does not.

1. **Split.** Each selected row `a x <= b` is double-bounded when its activity
   `a x` also has a finite lower bound. A paired opposite row over the same
   direction supplies one directly. Otherwise a homogeneous cone LP over the same
   rows asks whether the activity can decrease without limit: if it can, the row
   goes to the unbounded set; if the cone proves it cannot, an exact LP minimum
   over the rows supplies the lower bound, and an infeasible LP refutes the leaf.
2. **Transform.** The double-bounded rows are rewritten into mixed echelon form
   with a Hermite integer tail. Rational column operations isolate real pivots and
   never mix an integer source column into a real transformed column; the remaining
   integer block is reduced by unimodular column operations in
   [`lp.lattice`](26-lp-architecture.md), which preserve the integer lattice.
3. **Bound.** Forward substitution derives a range for every transformed
   coordinate. Integer pivots use inward-rounded division, so an empty integer
   interval is an exact refutation rather than a relaxation artefact. In the
   transformed coordinates every nonzero integer column is finite.
4. **Search.** The component branches only on those bounded transformed integer
   columns. A fractional transformed coordinate `v` is split as
   `sum(c_i * x_i) <= floor(v)` against `sum(c_i * x_i) >= floor(v) + 1`, a
   lattice direction expressed over source integers and registered through the
   shared atom registry.
5. **Extend.** Once the bounded search finds a point, the columns the
   transformation left free are completed against the unbounded rows. Each of
   those rows is tightened by half the absolute sum of its free integer
   coefficients, an LP finds a rational point of the tightened system, and the
   integer coordinates are rounded to the nearest integer; the tightening
   guarantees that the rounded point still satisfies the original rows. The point
   is mapped back to source coordinates and checked against every selected row.

The reduction result is cached per Boolean and disjunction leaf across integer
branch-and-bound nodes, because the source bounds and Boolean choices select it,
not the reduced branches searched inside it; branches reached in a different order
select the same entry. It is admitted only for small leaves: at most 512 Booleans,
128 factors, 128 rows and 512 terms, with at most 128 active branches and 4,096-bit
coefficients and bounds. Larger leaves use plain branching. When the whole leaf is
not admitted, the equality rows alone may still be reduced, and an infeasible
equality subsystem refutes the leaf. Shared search owns decision levels,
retraction and conflict learning; the component owns row assertions, branches and
numerical checks.

Integer bounds published by search are batched into the LP after active source
rows and before disequality processing. Each bound keeps its own source premise, and
the first contradictory prefix can produce a conflict.

With null `openHintFlips`, the linear completion owner can reuse a completed partial
point for primitive Boolean preferences as described for
[linear real arithmetic](20-linear-real.md#polarity-guidance). Fractional
coordinates guide branch order only; complete integrality, source checks and
exhaustive integer branches keep their authority.

[Open portfolios](16-portfolio.md) can pair this proof-producing route with
source-checked local-search workers. Current strategy admission limits are coverage
gaps under the [introduction](01-introduction.md#shared-model-breadth).

## Results and proof authority

`ExactLiraAssignment` carries arbitrary-precision integer coordinates, including
values beyond `Long`. Publication checks every selected source row and integrality.
A rational relaxation point or a failed witness check establishes neither
feasibility nor infeasibility. A leaf is refuted only by a certified LP conflict, an
infeasible reduction or exhaustion of its integer branches.

### Objectives

An integer objective with no Boolean or continuous cost uses the integral descent.
Each round asks the theory route to decide the model plus one bound row stating
that the objective is at most the incumbent value minus one. A witness tightens the
row, and a refutation proves the incumbent optimal. The opening round has no
incumbent and no bound row, so a refutation there proves infeasibility. The descent therefore needs no optimizing
simplex: the objective enters as a constraint the route already reasons about.
Preparation runs once on the model without the bound row, because each round only
adds a tighter row, and a row only removes solutions. A portfolio can run the
descent in slices and feed it an external bound reached by another worker, in
which case a refutation proves only that nothing beats that worker's value.

Stepping by one reaches an optimum in as many rounds as there are objective values
between the first witness and the optimum, and never reaches one that does not
exist. Every witness is therefore also asked for an improving ray within its own
branch: a direction preserving source integrality along which the objective
strictly decreases proves the objective unbounded.

Other admitted linear objectives, including Boolean costs, use exact
strict-improvement rounds through `RationalTheoryDescent`. Exact objective units
survive each round. Rational descent supplies no recession certificate and need not
terminate on an unbounded objective. Decidability of linear integer arithmetic does
not remove invocation limits or establish a verdict for an interrupted traversal.

## State and resources

Source rows and forms are prepared read-only. The invocation owns asserted scopes,
integer branches, reduction caches and LP state. Integer publications invalidate the
retained reduction without rebuilding a whole-source bound snapshot per callback.
Retraction restores the shared facts and component scope, and the LP keeps its
scoped bound assertions; scoped premises are removed before evidence can be reused.

Source installation builds derived disequality directions and their premises in
callback-local maps, then keeps one node snapshot. Published maps are not edited,
so ancestor scopes keep their directions through retraction, and an interrupted
assertion keeps its attempted prefix.

[TheoryParams](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/TheoryParams.kt)
separates theory checks, open work, decisions and cancellation. Native LP work keeps
the containing operation token and the
[LP certification](27-lp-certification.md) resource contract. The complete opening
binds its LP statistics observer before shared initialization; fresh openings own
separate sinks, and resuming an opening keeps its sink. Reduction LP work is
reported separately as `smtSourceLp*`.

## Implementation

[ExactLinearFragment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines linear factor admission.
[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
implements integer and mixed checks, the branch order and the reduction cache. The
reduction builds on the exact mixed echelon/Hermite and double-bounded split
helpers in `lp` and `simplex.exact`.
[OpenTheoryMinimizer](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/OpenTheoryMinimize.kt)
implements the integral descent. The pipeline names the
integer-containing route `EXACT_LIRA`; pure integer sources gain no real variables
from that name.

---

Previous: [20. Linear real arithmetic](20-linear-real.md) · [Contents](README.md) · Next: [22. Linear mixed arithmetic](22-linear-mixed.md)
