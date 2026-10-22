# 15. Linear arithmetic

Linear constraints combine coordinates with constant coefficients and Boolean
structure. They generalize the difference rows of
[difference logic](14-theories.md#difference-logic) to arbitrary rational coefficients.
This chapter describes one linear component and the three theories built on it:
linear real arithmetic checks the active system exactly, linear integer arithmetic
adds integrality, and linear mixed arithmetic couples integer and real columns and
recovers a complete point from what the LP returns.

## Linear real arithmetic

Exact rational checks decide the active linear system, and shared search explores
the Boolean alternatives. Strict inequalities require an interior point, not a
point on the closure's boundary.

### Scope and boundaries

The pure-real route requires real variables, no source integer variables, and
factors wholly represented by admitted exact linear forms. Source bounds,
reification, disjunction and strictness remain authoritative. Nonconstant real
products belong to [polynomial real arithmetic](16-nonlinear-arithmetic.md#polynomial-real-arithmetic).
[Difference logic](14-theories.md#real-graph-admission-and-checks) handles an
admitted pure-real subfragment; a graph decline does not reject a supported
linear source.

### Solving and composition

The linear component sits on two lifecycles at once: the
[search session](08-search.md) owns Boolean decisions and retraction, and the
[LP engine](17-linear-programming.md) owns numerical state and scoped bound
assertions. The component turns assigned source rows into LP bounds, and LP
verdicts into conflicts, branches and witnesses.

#### Rows as bounds

The component follows the slack-variable design of Dutertre and de Moura: every
normalized multi-column term gets one slack column defined by an equality row, so
every asserted atom becomes a bound on a single column, and asserting or retracting
an atom only moves a bound.

A term is normalized before lookup: zero coefficients are dropped and the first
remaining coefficient is scaled to one, flipping the bound direction for a negative
scale. `2x + 4y <= 6` and `-x - 2y >= -3` therefore share one slack column. Terms
first seen under an activator, branch or disjunct get their definition row appended
on first use, and siblings reuse it. At the root, fixed columns are substituted as
constants, keeping the premises of both fixing bounds.

#### A check

A check proceeds in four steps:

1. **Assert.** The component collects declared bounds and every row whose Boolean
   activator is assigned; the row's truth selects the row or its exact complement.
   Each asserted bound carries the literals that justify it. Disjunction rows stay
   out until search selects a disjunct.
2. **Check.** An admitted difference fragment runs the graph check first. Otherwise
   the LP solves the asserted system with exact certification. Partial Boolean
   states are checked once at least one arithmetic row is determined.
3. **Explain.** An infeasible system produces a conflict from the certified
   infeasibility support: each cited row or bound contributes its premise literals.
   If a cited row or bound has changed in the live LP state, no conflict is
   reported rather than one with stale premises.
4. **Complete.** Once every Boolean is assigned, the exact candidate point is
   checked against every selected source row before it becomes a witness.

Retraction pops the LP scope together with the Boolean decisions that justified its
rows and discards candidates, so a witness never outlives its premises.

When an optimal float point has no exact primal package, a complete pure-real
state can use [witness recovery](#witness-recovery) with an empty integer tail.
The recovered point must pass every selected exact source row before publication.
Paired source equations can also reduce an inequality to a false constant, proving
the selected real rows infeasible. A pure-real state with no additional
theory branches can explain that contradiction using the Boolean premises of the
rows used in the equality derivation and false inequality. Pivot and substitution
support retain original row identities within the repair-entry and search
explanation limits. Cancellation withholds the conflict.
An exact residual LP refutation follows the same path: its certified row and root
slack-bound support selects the retained parallel inequalities, and substitution
support maps those inequalities back through every paired source equation used.
Partial states can use the same recovery and refutation after a float optimum lacks
an exact point, including the float-prefix and equality-repair attempts before a
component LP call. Rows with unresolved Boolean inputs are excluded, and every
Boolean premise in the retained support must be assigned. The same restricted theory scope gates
that conflict. A recovered partial point that passes every selected original row
can guide unresolved guards through the existing point-reuse lifecycle. Complete
source agreement still gates publication of a witness.
An indexed point checks a single changed guard through its unique source-row index.
Batches of changed guards and shared activators scan all pending source rows.
Wide pure-real candidates can partition into independent blocks connected by selected rows.
Each block uses the bounded repair path, and their summed squared dimensions must fit
the same elimination allowance. A block's certified infeasibility support maps back to its
original selected rows and their Boolean premises. Every selected original row checks an
assembled candidate point.

#### Disjunctions and disequalities

A source disjunction is registered with search as one atom per disjunct; a disjunct
that is a single comparison is named by its exact bound, so the same predicate
reached another way shares the atom. The component holds the disjunction itself
rather than handing search a clause, because a learned clause could be forgotten by
a database reduction. It propagates like a clause: when all but one disjunct has
failed, the last is implied.

A disequality `t != b` is satisfied by either strict side. If the current bounds
already exclude one side, the component asserts the other; otherwise, at a complete
Boolean state, it registers the split `t < b` against `t > b`.

#### Strict inequalities

The floating simplex solves the closure, where strict and non-strict bounds
coincide. Certification decides strictness with a margin auxiliary: it maximizes a
slack `t` added to every strict row and accepts only an exact witness with positive
`t`, or refutes only with an exact bound `t <= 0`.
[LP certification](17-linear-programming.md#refinement-and-strict-inequalities)
defines that check.

#### Coordinate agreement

Composed arithmetic preparation excludes source Boolean clauses owned by the
shared clause component. Encoded rows are excluded only when their original
factor identity matches that shared ownership; standalone inputs retain clauses.

When the component shares a model with other components, it publishes its selected
rows as exact source-bound atoms and can check a candidate assembled by others
against the rows selected by the current Boolean state. A missing coordinate
declines rather than failing the candidate.

#### Polarity guidance

While Booleans remain open, a completed partial point from the LP or the graph can
guide which polarity search tries first for a primitive comparison. Every row
sharing an activator must agree, or that activator receives no preference. These
guesses only order exhaustive Boolean splits. The `openHintFlips` allowance
controls them: null enables direct preferences, a positive value also enables
compound clause preferences, and zero disables steering.
When default pure-real guidance exceeds the eager row envelope, a bounded index
can refer to immutable prepared rows and evaluate one primitive comparison on demand.
Shared activators and disjunctions receive no indexed preference. The index,
selected heads and retained rational point share the same storage envelope, and
retraction, restart or a changed arithmetic assertion discards the point.
For default eager or indexed rows containing only real coordinates, a retained
certified point can serve compatible Boolean prefixes. Every newly resolved
comparison is checked exactly before its LP assertion is deferred. Retirement replays deferred rows;
incompatible comparisons, theory updates, retraction and restart retire the point.
A complete Boolean state still runs the full source-point validator before model
acceptance. Eager guidance retains its existing preference array and owned numerical
point; indexed guidance reuses its bounded snapshot. Both keep the same storage
envelope.
When that envelope has room for another real vector, a recovered point can remain
as an untrusted numerical guess across retraction and restart. After a float optimum
without an exact point, every currently selected original row must accept the guess
under the current operation allowance before it can supply fresh guidance. Releasing
the hint workspace discards the guess. When the ordinary float-based equality repair
fails, the bounded repair can also reconstruct the retained free coordinates against
the current source equations before component LP completion. Every selected original
row checks that reconstructed candidate.

### Results and proof authority

`ExactLraAssignment` captures Boolean values and rational real coordinates.
Completed source checks admit witnesses. Exact conflicts, bounds and rays follow
[LP certification](17-linear-programming.md#lp-certification); a floating status alone is
insufficient. Strict infeasibility requires a completed strict-feasibility check.

Open real objectives with complete affine source capture use
`RationalTheoryDescent`. Attained witnesses, strict approach paths and feasible
improving rays determine its result; `OpenTheoryOptimum.linearResult` separates the
incumbent value from an unattained limit. Incomplete optimization withholds global
claims while preserving checked incumbents.

### State and resources

Immutable row addresses, forms and Boolean dependencies can be shared by fresh
components over one canonical fragment. `prepareExactLiraSource` returns an opaque
factory only after complete, uncancelled preparation; each opening binds fresh
callbacks, cancellation and mutable arithmetic owners. Scoped assertions, graph
potentials, LP bases and candidates remain invocation-owned. Optional hint storage
admits at most 65,536 entries and 4,096-bit normalized rationals.

### Implementation

[QfLraSystem](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLraSystem.kt)
builds the asserted source system, slack columns and premise literals.
[ExactLinearFragment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines `supportsExactLra`.
[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
shares the linear checking machinery with the integer and mixed theories.
[LpPropagator](../klause/src/commonMain/kotlin/com/eignex/klause/lp/bounding/LpPropagator.kt)
owns scoped bounds, their premises and conflict explanation. The pipeline names
this route `EXACT_LRA`.

## Linear integer arithmetic

Linear integer constraints combine integer coordinates with constant coefficients
and Boolean structure. The theory takes the [linear real component](#linear-real-arithmetic)
and adds integrality: the rational relaxation is checked exactly, a fractional
point causes integer branching, and an exact lattice reduction keeps unbounded
problems finite where the source supports it. Open source bounds stay open instead
of being replaced by a finite box. The [mixed theory](#linear-mixed-arithmetic) reuses
everything here.

### Scope and boundaries

The admitted surface consists of exactly representable linear conjunctions or
disjunctions and their Boolean structure. Nonconstant products belong to
[nonlinear integer arithmetic](16-nonlinear-arithmetic.md). Finite globals remain in
their finite projection, and an open integer used by a CP-only factor requires a
valid owner or the route declines. An entirely admitted difference fragment can use
[difference logic](14-theories.md#difference-logic) instead. Finite declarations keep their
domain state and choices; the theory waits for authoritative finite assignments
before publishing a completed point.

### Solving and composition

Assertion, partial checks and conflict explanations work as in the
[linear real component](#solving-and-composition). Integer
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

#### The double-bounded reduction

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
   [`lp.lattice`](17-linear-programming.md) that preserve the integer lattice.
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

[Open portfolios](12-portfolio.md) can pair this proof-producing route with
source-checked local-search workers.

### Results and proof authority

`ExactLiraAssignment` carries arbitrary-precision integer coordinates. Publication
checks every selected source row and integrality. A rational relaxation point
establishes neither feasibility nor infeasibility. A leaf is refuted only by a
certified LP conflict, an infeasible reduction or exhaustion of its integer
branches.

#### Objectives

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

### State and resources

Source rows and forms are prepared read-only. The invocation owns asserted scopes,
integer branches, reduction caches and LP state. Integer publications invalidate the
retained reduction; retraction restores the shared facts and component scope before
evidence can be reused.
[TheoryParams](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/TheoryParams.kt)
separates theory checks, open work, decisions and cancellation; native LP work
follows the [LP certification](17-linear-programming.md#lp-certification) resource contract.

### Implementation

[ExactLinearFragment](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/ExactLinearFragment.kt)
defines linear factor admission.
[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
implements integer and mixed checks, the branch order and the reduction cache,
building on the echelon/Hermite and double-bounded split helpers in `lp` and
`simplex.exact`.
[OpenTheoryMinimizer](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/OpenTheoryMinimize.kt)
implements the integral descent. The pipeline names the integer-containing route
`EXACT_LIRA`.

## Linear mixed arithmetic

Mixed linear constraints couple integer and real coordinates in one source model.
This theory combines the two above: the
[linear real](#linear-real-arithmetic) relaxation checks real feasibility, and the
[linear integer](#linear-integer-arithmetic) branch order enforces integrality on the
integer columns. A feasible continuous point is not enough to accept a mixed model,
and the main addition here is how a complete integral and real point is recovered
from what the LP returns. This chapter closes the linear part of the book.

### Scope and boundaries

Every theory-owned factor must have an admitted complete selected linear encoding.
Integer and real source namespaces stay distinct through lowering, reductions and
witness reconstruction. Open integer ranges remain theory-owned; finite
declarations keep their domain state and choices, including holes.

A finite integer × real product becomes linear once its integer operand is fixed.
That leaf-completion case does not admit an unfixed bilinear factor; shared
nonlinear products use [polynomial completion](16-nonlinear-arithmetic.md#polynomial-real-arithmetic).

### Solving and composition

Shared Boolean choices select active rows, and the component runs the
[integer branch order](#solving-and-composition-1): finite
integers, then disjunctions and disequalities, then the reduction or plain
fractional splits. Three mixed-specific mechanisms sit around that order.

#### One LP for both kinds of column

Integer and real source columns live in the same LP model; integer columns are
marked integral and enter with their exact source bounds, so one relaxation solve
checks the coupled system. The
[double-bounded reduction](#the-double-bounded-reduction) never
mixes an integer source column into a real transformed column, so integrality
survives the transformation and only integer coordinates are branched.

#### Finite and open integers together

A mixed model often has bounded integers owned by finite search beside open ones
owned by the theory. A finite integer counts as decided only when finite search has
fixed it; until then the complete check is indeterminate and the first unfixed
finite integer is the next branch. The theory never narrows a finite domain itself:
it proposes alternatives, and finite propagation enforces bounds, holes and
unrelated finite factors on each.

A completed continuous point guides these splits: an integral coordinate selects a
finite equality first, then its lower and upper remainders. With the default hint
allowance, a complete worker can also prefer a disjunct or disequality side using
an exact point or the prefix of an optimal float point. These preferences only
order exhaustive choices; a float optimum supplies no witness, bound or
refutation.

#### Witness recovery

The LP can finish with a float optimum but no exact primal package. Once every
Boolean and disjunction is chosen and every finite integer is fixed, the component
tries to turn that float point into an exact witness. The same recovery serves
pure-real models with an empty integer tail:

1. Round the integer coordinates of the float point as private guesses and
   substitute them into all selected source rows and branch bounds.
2. Check the exact IEEE real prefix, then rationally reconstructed reals, against
   those rows.
3. If neither passes, repair the real prefix through paired opposite source
   equations using bounded sparse elimination.
4. Complete the free real coordinates per connected component, grouped by shared
   rows: by the difference checker where the rows form a difference graph,
   otherwise by an exact point from the [source LP owner](17-linear-programming.md).
   Before the LP call, parallel residual inequalities are
   divided by the positive magnitude of their first nonzero coefficient and
   merged to the strongest bound, retaining strictness at equal endpoints.
   Opposite bounds with an empty interval certify a conflict from their two
   original rows before an LP call. Other opposite directions remain separate.
   Completion keeps the existing LP limits
   and is attempted only up to 65,536 row entries; every original residual row
   checks the assembled point.

The full rows, strictness, integrality and source reconstruction still gate
acceptance. A failed guess supplies no conflict or bound; the component returns to
ordinary branching.
An equality residual contradiction without guessed integers can certify selected
pure-real rows; a contradiction after guessing integer coordinates cannot refute
the source model.

Complete workers and open local search can share checked incumbents under the same
source and objective through [portfolios](12-portfolio.md). Candidate integer
windows guide heuristic search; they do not bound the source for a refutation.

### Results and proof authority

`ExactLiraAssignment` keeps arbitrary-precision integers and exact rational reals.
Acceptance checks integrality, declared bounds, source rows, strictness and Boolean
structure. Mixed unboundedness requires an integer-feasible point and an
integrality-preserving improving ray.

Complete affine source capture uses the retained linear authority invocation for
integer, Boolean and real costs; `OpenTheoryOptimum.linearResult` separates the
incumbent from checked bounds and limiting values. The
[integral descent](#objectives) rejects any objective with a
continuous term: a real objective has no next value below the incumbent and need
not attain its infimum. Unsupported or interrupted checks withhold global
optimization claims.

### State and resources

The invocation owns LP assertions, reduction caches, integer branches and candidate
assignments. The shared preparation factory keeps only immutable row forms, source
addresses and Boolean dependencies. A source rewrite or objective change needs
matching reconstruction and proof scope; a retained basis alone cannot transfer
result authority. Every recovery stage charges the current operation allowance.

### Implementation

[ExactLiraSearchComponent](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/QfLiraSolver.kt)
implements exact mixed checks and complementary integer decisions.
[MixedPointCompletion](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/MixedPointCompletion.kt),
[MixedEqualityRepair](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/MixedEqualityRepair.kt)
and [MixedRealCompletion](../klause/src/commonMain/kotlin/com/eignex/klause/theory/qflra/MixedRealCompletion.kt)
implement witness recovery.
[ComponentPlan](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/ComponentPlan.kt)
owns finite/theory decomposition; the open route is `EXACT_LIRA`.

---

Previous: [14. Theory services and difference logic](14-theories.md) · [Contents](README.md) · Next: [16. Nonlinear arithmetic](16-nonlinear-arithmetic.md)
