package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.HullFlags
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.LpModel
import com.eignex.klause.lp.LpRowPremises
import com.eignex.klause.lp.LpVerdict
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.Sense
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.cut.CircuitArcModel
import com.eignex.klause.lp.cut.CircuitSeparator
import com.eignex.klause.lp.cut.Cut
import com.eignex.klause.lp.solveAndCertify
import com.eignex.klause.lp.subExact
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap

/**
 * An LP relaxation of a [Problem] at one search node, plus the metadata mapping each LP column
 * back to the CP variable it stands for. The mapping is what lets reduced-cost fixing turn
 * an LP column reduction into a domain reduction on the right `(kind, varId)`.
 *
 * The LP objective is over the column costs only; the true objective is
 * `lpObjective + objectiveConstant`. Branch-and-bound must add [objectiveConstant] before
 * comparing the LP bound to the incumbent.
 */
internal class LpRelaxation(
    val model: LpModel,
    /** Structural LP column → origin CP variable id. */
    val colVarId: IntArray,
    /** Structural LP column → true if it is a Boolean variable, false if an integer variable. */
    val colIsBool: BooleanArray,
    /** Constant term of the objective, omitted from the LP and re-added to its bound. */
    val objectiveConstant: Long,
    /** Integer variable id → its LP column, or -1 if none. For separators to write cuts. */
    val intColOf: IntArray,
    /** Boolean variable id → its LP column, or -1 if none. */
    val boolColOf: IntArray,
    /** Arc-indicator models of any Circuit factors, for the subtour-elimination separator. */
    val circuitArcs: List<CircuitArcModel> = emptyList(),
    /**
     * Whether this relaxation's layout is node-invariant, so a search node may [rebound] it (re-bind
     * column bounds over the fixed matrix) instead of rebuilding. True when no row's coefficients
     * depend on the live domains and every structural column is re-derivable from the live session —
     * either CP-var-backed (re-bound from its own domain) or an auxiliary column with a [colReq]
     * presence rule (re-bound by pinning). For an eligible relaxation [rebound] reproduces exactly the
     * model a per-node build would emit.
     */
    val persistentEligible: Boolean = false,
    /**
     * Per structural column, an auxiliary column's presence requirement as flat `(intVar, value)`
     * membership pairs — the column is present (upper [colPresentUpper]) only while every named value
     * stays in its variable's live domain. Null for CP-var-backed columns and for auxiliary columns
     * with no rule (which make the relaxation persistent-ineligible). Empty by default.
     */
    val colReq: Array<LongArray?> = arrayOfNulls(model.n),
    /** Per structural column, the upper bound an auxiliary column takes when present (see [colReq]);
     *  unused for CP-var columns. */
    val colPresentUpper: LongArray = LongArray(model.n),
    /** Ids of the factors that emitted at least one [Contribution.HULL] row, in factor order — the
     *  candidates the root pruner (`LpEngine.pruneIneffectiveHulls`) probes for individual removal. */
    val hullFactorIds: IntArray = EmptyIntArray,
    /** Structural LP column → the LP-only continuous (real) variable id it stands for, or -1 for an
     *  integer / Boolean / auxiliary column. Lets the leaf verdict read each continuous column's solved
     *  value back onto its real variable. */
    val colRealId: IntArray = IntArray(model.n) { -1 },
    /** Per structural column, the sign its primal contributes to its real variable's value: a
     *  lower-unbounded real variable is split `x = x⁺ − x⁻` (two columns, both lower-bounded at 0, so
     *  no huge lower-shift ever reaches the rhs doubles), and the negative part carries -1. */
    val colRealSign: IntArray = IntArray(model.n) { 1 },
)

/**
 * The same relaxation re-bound to [session]'s live column bounds, reusing the fixed matrix and column
 * maps (see [LpRelaxation.persistentEligible] and [LpModel.rebind]). A CP-var column takes its live
 * domain (or pin); an auxiliary column with a [LpRelaxation.colReq] rule is pinned to `[0, 0]` once any
 * required value has left its variable's live domain, else `[0, present-upper]`. Only valid on an
 * eligible relaxation, which the caller checks before building the persistent relaxation once.
 */
internal fun LpRelaxation.rebound(session: PropagationSession): LpRelaxation {
    val n = model.n
    val lo = LongArray(n)
    val hi = LongArray(n)
    for (j in 0 until n) {
        val req = colReq[j]
        when {
            req != null -> {
                var present = true
                var k = 0
                while (k < req.size) {
                    if (!session.intDomain(req[k].toInt()).contains(req[k + 1])) {
                        present = false
                        break
                    }
                    k += 2
                }
                lo[j] = 0L
                hi[j] = if (present) colPresentUpper[j] else 0L
            }

            colIsBool[j] -> {
                val pinned = session.boolValue(colVarId[j])
                lo[j] = if (pinned == true) 1L else 0L
                hi[j] = if (pinned == false) 0L else 1L
            }

            else -> {
                val d = session.intDomain(colVarId[j])
                lo[j] = d.min
                hi[j] = d.max
            }
        }
    }
    return LpRelaxation(
        model = model.rebind(lo, hi),
        colVarId = colVarId,
        colIsBool = colIsBool,
        objectiveConstant = objectiveConstant,
        intColOf = intColOf,
        boolColOf = boolColOf,
        circuitArcs = circuitArcs,
        persistentEligible = true,
        colReq = colReq,
        colPresentUpper = colPresentUpper,
    )
}

/**
 * The per-variable bounds the relaxation reads to size its columns: an integer variable's live domain
 * and a Boolean variable's pin (null when free). A [PropagationSession] is one source (its live search
 * state); [RootDomains] is another (the problem's declared domains, read without running the bake
 * fixpoint) so a root relaxation can be built in O(model) rather than O(domain span).
 */
internal interface RelaxationDomains {
    /** The live domain of integer variable [varId]. */
    fun intDomain(varId: Int): IntDomain

    /** The pin of Boolean variable [varId] — `true`/`false` when fixed, null when free. */
    fun boolValue(varId: Int): Boolean?
}

/** [RelaxationDomains] over a [Problem]'s declared domains: every integer variable at its full domain
 *  and every Boolean free. Reads endpoints only, so building a relaxation from it never triggers the
 *  O(domain span) bake fixpoint a [PropagationSession] runs on construction. */
internal class RootDomains(private val problem: Problem) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = problem.intDomains[varId]
    override fun boolValue(varId: Int): Boolean? = null
}

/** [RelaxationDomains] backed by a live [PropagationSession]'s search state. */
private class SessionDomains(private val session: PropagationSession) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = session.intDomain(varId)
    override fun boolValue(varId: Int): Boolean? = session.boolValue(varId)
}

/** [RelaxationDomains] pinning every integer variable to its value in a full-assignment [sample] and
 *  every Boolean to its bit — the leaf view where the residual LP over the continuous columns is all
 *  that remains undecided (see [leafRealFeasibility]). */
private class SampleDomains(private val sample: Sample) : RelaxationDomains {
    override fun intDomain(varId: Int): IntDomain = sample.ints[varId].let { IntDomain(it, it) }
    override fun boolValue(varId: Int): Boolean = sample.bools[varId]
}

/**
 * The certified feasibility verdict of the LP-only continuous relaxation at a full-assignment leaf
 * [sample], with every discrete variable pinned to its assigned value so the LP decides only the
 * continuous columns. [LpVerdict.OPTIMAL] means the reals have a feasible completion — the leaf is a
 * genuine solution; [LpVerdict.INFEASIBLE] (exact Farkas) means none exists — the leaf must be rejected;
 * [LpVerdict.INDETERMINATE] means neither could be certified within the 128-bit budget, so the leaf's
 * status is unknown and the terminal verdict must degrade to `unknown` rather than claim UNSAT/SAT.
 *
 * The discrete constraints already hold at a CP-consistent leaf, so their linear rows are satisfiable by
 * the fixed values and only the LP-only real rows can bind — making this exactly a residual real-LP
 * feasibility test. Called only when the problem declares continuous variables.
 */
internal fun leafRealFeasibility(
    problem: Problem,
    objective: LinearObjective?,
    sample: Sample,
    cancellation: Cancellation = Cancellation.Never,
): LeafRealResult {
    val relaxation = CpToLpRelaxation(problem, objective).build(SampleDomains(sample))
    // Bound the residual-LP solve by the search deadline: on a continuous-heavy model a single leaf LP is a
    // large factorization, so an unbounded solve could outlast the whole budget. A solve cut short returns
    // INDETERMINATE, degrading the leaf to `unknown` — never an unsound SAT/UNSAT.
    val certified = solveAndCertify(relaxation.model, cancellation = cancellation)
    if (certified.verdict != LpVerdict.OPTIMAL) return LeafRealResult(certified.verdict, EmptyDoubleArray)
    val primal = certified.exactPrimal ?: certified.float?.primal
        ?: return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
    // Read each continuous column's solved value back onto its real variable (see [LpRelaxation.colRealId]);
    // a split (lower-unbounded) variable accumulates x⁺ − x⁻ across its two columns.
    val reals = DoubleArray(problem.numRealVars)
    val colRealId = relaxation.colRealId
    for (col in colRealId.indices) {
        val r = colRealId[col]
        if (r >= 0 && col < primal.size) reals[r] += relaxation.colRealSign[col] * primal[col]
    }
    return LeafRealResult(LpVerdict.OPTIMAL, reals)
}

/** The residual-LP verdict at a leaf plus, on [LpVerdict.OPTIMAL], the continuous variables' solved
 *  values (indexed by real var id) that complete the discrete assignment into a full solution. */
internal class LeafRealResult(val verdict: LpVerdict, val reals: DoubleArray)

/**
 * Walks [Problem.factors] and emits an [LpModel] relaxation for the LP-emittable factor types,
 * pulling variable bounds live from the current search node.
 *
 * ## What is encoded
 *  - [Linear]: one row, `LE`/`GE`/`EQ` mapped directly; `NE` is not LP-relaxable and is skipped.
 *  - [Cardinality], [Clause], [PseudoBoolean]: linear rows over the Boolean fan-in. A positive
 *    literal contributes `x_b`, a negative literal `1 − x_b`; the constant folds into the row's
 *    right-hand side.
 *  - [ReifiedLinear]: indicator rows via tight big-M (see `reifiedRows`).
 *  - [ArrayMinMax]: the extremum's envelope — `result ≥ xs[i]` for `max`, `result ≤ xs[i]` for
 *    `min`, one row per operand. Sound; the tight face (result equals some operand) is not a
 *    single LP cut, so only the envelope side is emitted.
 *  - The [LinearObjective] (always minimization): a cost on each variable's column. Every variable
 *    with a nonzero objective coefficient gets a column even if no constraint mentions it, so the
 *    LP objective is the complete relaxed objective and its optimum is a valid bound.
 *
 * ## What is skipped
 *  Hard globals (AllDifferent, Cumulative, Element, Circuit, …) are not encoded here; they are
 *  handled by cut generation or Lagrangian relaxation. Unrecognized factors are
 *  silently skipped — a missing constraint only loosens the relaxation, it never makes the bound
 *  unsound.
 *
 * ## Live bounds
 *  Integer columns take `[min, max]` from [PropagationSession.intDomain]; a Boolean column takes
 *  `[1, 1]` / `[0, 0]` when its variable is already pinned this node, else `[0, 1]`. Pinning a
 *  Boolean column collapses every big-M indicator that mentions it to the exact constraint.
 */
internal class CpToLpRelaxation(
    private val problem: Problem,
    private val objective: LinearObjective?,
    /** When true, linearize each constant-array Element with a one-hot selector model (its exact
     *  convex hull). Adds O(len) columns, so it is gated; variable arrays are skipped. */
    private val elementHull: Boolean = false,
    /** When true, linearize each Table with one selector column per allowed tuple — its exact convex
     *  hull. Adds O(numTuples) columns, so it is gated. */
    private val tableHull: Boolean = false,
    /** When true, emit the energetic makespan lower-bound row for each Cumulative / Disjunctive whose
     *  makespan variable can be verified (see [CumulativeRelaxation]). One row per plan. */
    private val cumulative: Boolean = false,
    /** When true, project each constant-size Diffn onto both axes as a cumulative and emit the same
     *  energetic makespan row (#655) — a sound lower bound on a strip-length / extent variable (its
     *  `t1 = min-est` case is the area bound `Σ wᵢ·hᵢ ≤ W·H`). One row per derived plan; a no-op unless
     *  an axis extent is provably an upper bound on every task end (so it only fires when it helps). */
    private val diffn: Boolean = false,
    /** When true, emit the time-indexed `x_{i,t}` relaxation of each Cumulative / Disjunctive over a
     *  bounded horizon (#453): assignment + start channel + per-time resource rows. Adds O(n·H)
     *  columns, so it is hard-gated on the horizon and total cell count. */
    private val cumulativeTimeIndexed: Boolean = false,
    /** When true, linearize each NValue with a one-hot value model (per-value "used" indicators) so
     *  the distinct-count target gets an LP bound. Adds O(Σ|domain|) columns, so it is gated. */
    private val nValueHull: Boolean = false,
    /** When true, linearize each Regular with the layer-expanded DFA flow hull (one arc var per
     *  reachable `(position, state, symbol)` transition, flow-conservation + channel rows) — the exact
     *  convex hull of the automaton's accepting strings. Adds O(len·states·alphabet) columns, gated. */
    private val regularHull: Boolean = false,
    /** When true, linearize each Mdd with the layered flow hull (one arc var per reachable transition
     *  record, flow-conservation + value channel + optional cost channel) — the exact convex hull of
     *  the diagram's accepting paths, so a cost-MDD's cost var gets an exact lower bound. Gated. */
    private val mddHull: Boolean = false,
    /** When true, linearize each count-variable [GlobalCardinality] with a one-hot selector model so
     *  the count variables get an LP bound: per `xs[i]` a one-hot over its declared domain, and per
     *  cover value `Σ_i z_{i,cover} = counts(k)` — exact, so a count in the objective reads a true
     *  bound. Adds O(Σ|domain|) columns, so it is gated. */
    private val gccCountHull: Boolean = false,
    /**
     * #571: build only the **objective cone** — the rows and variables transitively connected to the
     * objective through the linear/Boolean constraints — and **drop every big-M [ReifiedLinear] row**.
     * The result is a small structural sub-relaxation that always fits the dense-tableau cap (no
     * disjunctive ordering bools), yet is a genuine lower bound: for scheduling it is the
     * critical-path / longest-path bound (precedence + objective, machine-disjunctions removed). Any
     * subset of constraints is a relaxation, so the bound is sound; this is just a cheaper, looser one.
     * When set, the column-heavy hull / circuit / cut / cumulative features are forced off — the cone
     * probe is deliberately the minimal linear+Boolean relaxation.
     */
    private val objectiveCone: Boolean = false,
    /**
     * Build only the **residual real sub-relaxation**: the factors that touch LP-only continuous
     * (real) columns, and nothing else. Propagation already enforces the Boolean/integer structure at
     * every node, so for a pure feasibility prune the clause rows add no strength — dropping them
     * shrinks the basis from the clause count to the real-atom count (the satisfaction path's
     * per-node check; see [com.eignex.klause.lp.bounding.LpPlan.realResidual]).
     */
    private val realResidual: Boolean = false,
    /** When true, build the arc-indicator relaxation of each [Circuit] / [Circuit] (degree +
     *  channelling rows over one `y_ij ∈ [0,1]` column per candidate arc). For Circuit it also records
     *  a [CircuitArcModel] feeding [CircuitSeparator]'s subtour-elimination cuts; Subcircuit gets the
     *  hull only (its cutset structure differs, #431). Adds O(arcs) columns, so it is gated. */
    private val circuitArcs: Boolean = false,
    /** When true, relax each [Product] `result = a·b` with its **McCormick envelope** (four bound-derived
     *  inequalities). For `a = b` (a square) the envelope degenerates to the secant/tangent relaxation.
     *  Adds four rows per product over the existing columns, so it is gated; off by default. */
    private val productMcCormick: Boolean = false,
    /** When true, add Boolean RLT rows: multiply each small 0/1 knapsack row by its binaries and
     *  linearize the products with the McCormick envelope. Adds product columns + rows (capped), so it
     *  is gated; off by default. Sound — the relaxation excludes no integer-feasible point. */
    private val booleanRlt: Boolean = false,
    /** When true, add the **tight face** of each [ArrayMinMax] (Anderson big-M form) on top of the
     *  always-emitted envelope: one-hot selectors `z_i` (`Σ z_i = 1`) and per-operand rows forcing
     *  `result = xs[i]` when `z_i = 1`, so the extremum is bounded from the tight side too (`result ≤
     *  max` / `result ≥ min`), not just the envelope side. Adds O(|xs|) columns, so it is gated. */
    private val linMaxTightFace: Boolean = false,
    /** Factor ids whose [Contribution.HULL] rows are suppressed this build — the root pruner
     *  (`LpEngine.pruneIneffectiveHulls`) fills this with the per-factor hulls it found add no root
     *  strength, so they contribute only their CORE rows (if any). */
    private val suppressedHullFactors: Set<Int> = emptySet(),
) {
    /** Verified makespan plans for the scheduling globals; null when disabled or none applicable. */
    private val cumulativeRelaxation: CumulativeRelaxation? =
        if (cumulative || diffn) {
            CumulativeRelaxation(problem, includeCumulative = cumulative, includeDiffn = diffn)
                .takeIf { it.applicable }
        } else {
            null
        }

    /**
     * Whether this relaxer emits no row whose coefficients depend on the live domains (#39), the
     * row-side half of [LpRelaxation.persistentEligible]: the objective cone (different per-cone
     * structure), the cumulative / diffn energetic rows (live-coefficient rows with no auxiliary
     * column to gate them), and live-M reified rows are excluded. Auxiliary-column hulls (circuit,
     * table, …) stay candidates here — their rows are fixed and the live restriction rides on the
     * column bounds, which the per-column presence rule re-binds; an un-ruled aux column is what keeps
     * a not-yet-wired hull off the persistent path (checked at build). When this holds and every
     * column is re-derivable, a node may [rebound] the once-built relaxation instead of rebuilding it.
     */
    private val structurallyPersistent: Boolean =
        !objectiveCone && !cumulative && !diffn &&
            problem.factors.none {
                it is ReifiedLinear || it is ReifiedPseudoBoolean || it is ReifiedCardinality ||
                    it is ReifiedRealLinear
            }

    /**
     * #571 objective-cone membership, structural (depends only on [Problem.factors] and the
     * objective, never on live domains), so it is computed once and reused across nodes. `first` is
     * per-int-var, `second` per-bool-var: `true` when the variable is transitively connected to the
     * objective through a cone-relevant factor (every LP-emittable type except the dropped big-M
     * [ReifiedLinear]). A factor is emitted in cone mode iff it touches the cone — and by closure a
     * factor that touches the cone has all its variables in the cone. Null when [objectiveCone] is off.
     */
    private val cone: Pair<BooleanArray, BooleanArray>? by lazy {
        if (objectiveCone) computeObjectiveCone() else null
    }

    /** Fixpoint closure from the objective's support over the cone-relevant factors (see [cone]). */
    private fun computeObjectiveCone(): Pair<BooleanArray, BooleanArray> {
        val intIn = BooleanArray(problem.numIntVars)
        val boolIn = BooleanArray(problem.numBoolVars)
        objective?.let { obj ->
            for (i in obj.intCoefficients.indices) if (obj.intCoefficients[i] != 0L) intIn[i] = true
            for (b in obj.boolWeights.indices) if (obj.boolWeights[b] != 0L) boolIn[b] = true
        }
        var changed = true
        while (changed) {
            changed = false
            for (f in problem.factors) {
                if (coneTouches(f, intIn, boolIn)) changed = coneMark(f, intIn, boolIn) || changed
            }
        }
        return intIn to boolIn
    }

    /** Whether [f] extends the cone ([Factor.extendsObjectiveCone]) and shares a variable with it. */
    private fun coneTouches(f: Factor, intIn: BooleanArray, boolIn: BooleanArray): Boolean =
        f.extendsObjectiveCone && (f.intVars.any { intIn[it] } || f.boolVars.any { boolIn[it] })

    /** Add every variable of a cone-extending [f] to the cone; true when anything was newly added. */
    private fun coneMark(f: Factor, intIn: BooleanArray, boolIn: BooleanArray): Boolean {
        if (!f.extendsObjectiveCone) return false
        var changed = false
        for (v in f.intVars) {
            if (!intIn[v]) {
                intIn[v] = true
                changed = true
            }
        }
        for (b in f.boolVars) {
            if (!boolIn[b]) {
                boolIn[b] = true
                changed = true
            }
        }
        return changed
    }

    /** Per-hull dense-tableau caps. Internal so [com.eignex.klause.lp.bounding.LpAutoConfig]'s
     *  size guard (#484) estimates each enabled hull against the *same* thresholds the builders skip
     *  at — one source of truth, no drift. */
    internal companion object {
        /** Above this candidate-arc count the circuit arc model is skipped — a defensive bound on
         *  the dense-tableau cost. Gating on arc count (LP columns) rather than node count lets
         *  large but sparse routing graphs through (#431); #429 may bench this threshold. */
        const val MAX_CIRCUIT_ARCS: Int = 1024

        /** Boolean RLT skips knapsack rows wider than this (each adds O(width) product columns). */
        const val MAX_RLT_ROW: Int = 8

        /** Cap on the total Boolean-RLT product columns added, bounding the dense-tableau cost. */
        const val MAX_RLT_COLUMNS: Int = 256

        /** Above this horizon (latest deadline − earliest start) the time-indexed model is skipped. */
        const val MAX_TI_HORIZON: Int = 512

        /** Above this many `x_{i,t}` columns one time-indexed Cumulative is skipped (the O(n·H) blow-up). */
        const val MAX_TI_COLS: Int = 4096
    }

    /** Build the relaxation from a live [session], optionally appending separator-produced [extraCuts]
     *  as extra rows. */
    fun build(session: PropagationSession, extraCuts: List<Cut> = emptyList()): LpRelaxation =
        build(SessionDomains(session), extraCuts)

    /** Build the relaxation over [domains], optionally appending separator-produced [extraCuts] as extra
     *  rows. With [RootDomains] this builds a root relaxation without running the bake fixpoint. */
    fun build(domains: RelaxationDomains, extraCuts: List<Cut> = emptyList()): LpRelaxation =
        Assembler(domains).assemble(extraCuts)

    private fun intCost(i: Int): Long = objective?.intCoefficients?.getOrElse(i) { 0L } ?: 0L

    private fun boolCost(b: Int): Long = objective?.boolWeights?.getOrElse(b) { 0L } ?: 0L

    private fun realCost(r: Int): Double = objective?.realCoefficients?.getOrElse(r) { 0.0 } ?: 0.0

    /** Per-build mutable state: the builder, the column maps, and the row emitters. Implements
     *  [RelaxationBuilder] so a factor's [com.eignex.klause.solver.Factor.linearize] can emit into it. */
    private inner class Assembler(private val domains: RelaxationDomains) : RelaxationBuilder {
        private val builder = LpBuilder()
        private val intCol = IntArray(problem.numIntVars) { -1 }
        private val boolCol = IntArray(problem.numBoolVars) { -1 }
        private val realCol = IntArray(problem.numRealVars) { -1 }
        private val colVarId = IntArrayList()
        private val colIsBool = IntArrayList() // 0 = int, 1 = bool; densified at the end

        // LP column -> real var id it stands for, or -1 for int/bool/aux columns. Lets the leaf verdict
        // read each LP-only continuous column's value back onto its real variable.
        private val colRealId = IntArrayList()
        private val colRealSign = IntArrayList()

        // Primary column -> negative-part column of a split lower-unbounded real variable.
        private val realNegOf = HashMap<Int, Int>()

        // Per-column live-bound rule for the persistent relaxation (#39/#43). For an auxiliary column,
        // colReq[c] holds its presence requirement as flat (intVar, value) membership pairs and
        // colPresentUpper[c] the upper bound when present — so a node can re-bind the column (upper = 0
        // when any required value left the live domain, else the present-upper) instead of rebuilding.
        // null/0 for CP-var columns (re-bound from the variable's own domain) and for un-ruled aux
        // columns (which keep the relaxation off the persistent path).
        private val colReq = ArrayList<LongArray?>()
        private val colPresentUpper = LongArrayList()

        /** Arc-indicator models recorded by `buildCircuitArcs` for the subtour-elimination separator. */
        private val circuitModels = ArrayList<CircuitArcModel>()

        /** Whether the factor currently being linearized may contribute HULL rows: its convex-hull
         *  family flag is on and we are not in objective-cone mode (where the column-heavy hulls are
         *  forced off). Set per factor before [Factor.linearize]; consulted by the row emitters so a
         *  disabled family contributes only its CORE rows. CORE rows ignore it. */
        private var currentHullEnabled = true

        /** The factor currently emitting, for attributing HULL rows to it. */
        private var currentFactorId = -1

        /** Factors that emitted at least one HULL row, in factor order (the root pruner's candidates). */
        private val hullFactorIds = LinkedHashSet<Int>()

        /** The per-family convex-hull switches for this build, read polymorphically by each hull factor's
         *  [Factor.hullFamilyEnabled]; combined with the cone and per-factor suppression gates when
         *  [currentHullEnabled] is set, so the driver never matches factor types. */
        private val hullFlags = HullFlags(
            element = elementHull,
            table = tableHull,
            nValue = nValueHull,
            regular = regularHull,
            mdd = mddHull,
            gccCount = gccCountHull,
            arrayMinMax = linMaxTightFace,
            product = productMcCormick,
        )

        /** Auxiliary LP column with no backing CP variable (tag/colVarId = -1) — e.g. a circuit arc.
         *  [presence] names the `(intVar, value)` memberships that must all hold for the column to be
         *  present (upper [hi]); when given, the column can be re-bound on the persistent path. */
        override fun auxColumn(lo: Long, hi: Long, presence: LongArray?): Int {
            val c = builder.addVar(lo, hi, cost = 0L, tag = -1)
            colVarId.add(-1)
            colIsBool.add(0)
            colRealId.add(-1)
            colRealSign.add(1)
            colReq.add(presence)
            colPresentUpper.add(hi)
            return c
        }

        /**
         * Arc-indicator relaxation of one [Circuit] over `succ[0..n)`: a column `y_ij ∈ [0,1]` per
         * candidate arc (`j` in the declared domain of `succ[i]`, `j ≠ i` — circuit forbids self
         * loops), pinned to 0 when `j` left the live domain. Rows: out-degree `Σ_j y_ij = 1`,
         * in-degree `Σ_i y_ij = 1`, and channelling `Σ_j j·y_ij = succ[i]` tying arcs to the integer
         * column. Integer solutions are then permutations; [CircuitSeparator] removes the subtours.
         * The column *layout* uses the declared domain so it is identical across nodes (warm-start
         * safe). A circuit whose candidate-arc count exceeds [MAX_CIRCUIT_ARCS] is skipped (the LP
         * column count would dominate); gating on arc count rather than n lets large sparse graphs
         * through (#431). Arcs are recorded sparsely for the [CircuitSeparator] — no O(n²) matrix.
         */
        private fun buildCircuitArcs(factor: Circuit) = buildArcModel(factor.succ, selfLoops = false, sec = true)

        /**
         * Arc-indicator relaxation of one [Circuit] over `succ[0..n)`. As `buildCircuitArcs` but the
         * self-loop arc `y_ii` (= "node i is excluded") is a candidate, so the degree + channel rows
         * describe the **permutation** polytope (each node has exactly one in- and out-arc, fixed points
         * allowed). **No subtour-elimination model is registered**: the Hamiltonian SEC is *unsound* for
         * a subcircuit (an all-excluded subset legitimately has no leaving arc).
         */
        private fun buildSubcircuitArcs(factor: Circuit) = buildArcModel(factor.succ, selfLoops = true, sec = false)

        /**
         * Shared degree + channel arc model for [Circuit] / [Circuit]: a `y_ij ∈ [0,1]` column per
         * candidate arc (pinned to 0 when `j` left the live domain), with out-degree / in-degree
         * `= 1` rows and the channel `Σ_j j·y_ij = succ[i]`. Records a [CircuitArcModel] when [sec].
         */
        private fun buildArcModel(succ: IntArray, selfLoops: Boolean, sec: Boolean) {
            val n = succ.size
            if (n < 2) return
            // Gate on the candidate-arc total — the LP column count — not on n, so large sparse
            // graphs (small per-node successor domains) are not skipped by a blunt node cap.
            var arcCount = 0
            for (i in 0 until n) {
                problem.intDomains[succ[i]].forEach { j ->
                    if ((selfLoops || j != i.toLong()) && j >= 0 &&
                        j < n
                    ) {
                        arcCount++
                    }
                }
            }
            if (arcCount == 0 || arcCount > MAX_CIRCUIT_ARCS) return
            val tails = IntArrayList()
            val heads = IntArrayList()
            val cols = IntArrayList()
            val inColsByHead = Array(n) { IntArrayList() }
            // Out-degree and channelling rows, building the (sparse) arc columns on the way.
            for (i in 0 until n) {
                val live = domains.intDomain(succ[i])
                val outCols = IntArrayList()
                val chanCols = IntArrayList()
                val chanCoef = IntArrayList()
                problem.intDomains[succ[i]].forEach { j ->
                    if ((!selfLoops && j == i.toLong()) || j < 0 || j >= n) return@forEach
                    val jn = j.toInt() // validated node index in [0, n)
                    val present = live.contains(j)
                    // The arc is present exactly while head j stays in succ[i]'s live domain — the single
                    // membership that lets the persistent relaxation re-bind this column (#43).
                    val col = auxColumn(
                        0L,
                        if (present) 1L else 0L,
                        presence = longArrayOf(succ[i].toLong(), jn.toLong()),
                    )
                    outCols.add(col)
                    chanCols.add(col)
                    chanCoef.add(jn)
                    tails.add(i)
                    heads.add(jn)
                    cols.add(col)
                    inColsByHead[jn].add(col)
                }
                if (outCols.isEmpty()) return // degenerate: no candidate successor — leave to propagation
                builder.addRow(outCols.toIntArray(), LongArray(outCols.size) { 1L }, Relation.EQ, 1L)
                // Σ_j j·y_ij − succ[i] = 0.
                val cCols = IntArray(chanCols.size + 1)
                val cVals = LongArray(chanCols.size + 1)
                for (k in 0 until chanCols.size) {
                    cCols[k] = chanCols[k]
                    cVals[k] = chanCoef[k].toLong()
                }
                cCols[chanCols.size] = intColumn(succ[i])
                cVals[chanCols.size] = -1L
                builder.addRow(cCols, cVals, Relation.EQ, 0L)
            }
            // In-degree rows: Σ_i y_ij = 1 for each node j that is some arc's head.
            for (j in 0 until n) {
                val inCols = inColsByHead[j]
                if (!inCols.isEmpty()) {
                    builder.addRow(inCols.toIntArray(), LongArray(inCols.size) { 1L }, Relation.EQ, 1L)
                }
            }
            if (sec) circuitModels.add(CircuitArcModel(n, tails.toIntArray(), heads.toIntArray(), cols.toIntArray()))
        }

        /** Column for integer variable `intVar`, created on first use with its live domain bounds. */
        override fun intColumn(intVar: Int): Int {
            var c = intCol[intVar]
            if (c == -1) {
                val dom = domains.intDomain(intVar)
                c = builder.addVar(dom.min, dom.max, intCost(intVar), tag = intVar)
                intCol[intVar] = c
                colVarId.add(intVar)
                colIsBool.add(0)
                colRealId.add(-1)
                colRealSign.add(1)
                colReq.add(null)
                colPresentUpper.add(0L)
            }
            return c
        }

        /**
         * Column(s) for LP-only continuous variable [realVar], created on first use with its declared
         * real bounds and objective coefficient. It has no backing CP variable (`colVarId = -1`) so the
         * persistent relaxation never re-binds it and reduced-cost fixing never maps it to a domain; its
         * value is read back onto the real variable at the leaf.
         *
         * A finite lower bound becomes the column's shift as usual. A `−∞` lower bound must NOT become a
         * probe-magnitude shift: folding `coeff · 2⁶¹` into the rhs doubles annihilates ordinary
         * right-hand sides (float spacing at that magnitude is 512), which once turned an infeasible
         * system into a self-consistent — and exactly "certifiable" — corrupted one. Instead the
         * variable splits as `x = x⁺ − x⁻` with both parts lower-bounded at 0 (upper sides open through
         * the probe stand-in, which only ever appears as a box bound, never in the rhs); every row and
         * the objective mirror the negative part with negated coefficients, and a finite upper bound is
         * enforced by an explicit `x⁺ − x⁻ ≤ hi` row.
         */
        override fun realColumn(realVar: Int): Int {
            var c = realCol[realVar]
            if (c == -1) {
                val lo = problem.realLower[realVar]
                val hi = problem.realUpper[realVar]
                if (lo.isFinite()) {
                    c = builder.addRealVar(
                        lower = lo,
                        upper = if (hi.isFinite()) hi else null,
                        cost = realCost(realVar),
                    )
                    registerRealCol(realVar, sign = 1)
                } else {
                    c = builder.addRealVar(lower = 0.0, upper = null, cost = realCost(realVar))
                    registerRealCol(realVar, sign = 1)
                    val neg = builder.addRealVar(lower = 0.0, upper = null, cost = -realCost(realVar))
                    registerRealCol(realVar, sign = -1)
                    realNegOf[c] = neg
                    if (hi.isFinite()) {
                        builder.addRealRow(intArrayOf(c, neg), doubleArrayOf(1.0, -1.0), Relation.LE, hi)
                    }
                }
                realCol[realVar] = c
            }
            return c
        }

        private fun registerRealCol(realVar: Int, sign: Int) {
            colVarId.add(-1)
            colIsBool.add(0)
            colRealId.add(realVar)
            colRealSign.add(sign)
            colReq.add(null)
            colPresentUpper.add(0L)
        }

        /** Column for Boolean variable `boolVar`; bounds collapse to a point if it is pinned this node. */
        override fun boolColumn(boolVar: Int): Int {
            var c = boolCol[boolVar]
            if (c == -1) {
                val pinned = domains.boolValue(boolVar)
                val lo = if (pinned == true) 1L else 0L
                val hi = if (pinned == false) 0L else 1L
                c = builder.addVar(lo, hi, boolCost(boolVar), tag = boolVar)
                boolCol[boolVar] = c
                colVarId.add(boolVar)
                colIsBool.add(1)
                colRealId.add(-1)
                colRealSign.add(1)
                colReq.add(null)
                colPresentUpper.add(0L)
            }
            return c
        }

        /** Emit `Σ coeffs[k]·x_{vars[k]} (+ auxCoeff·x_aux) rel rhs`; pass `auxCol = -1` for no aux.
         *  [global] marks whether the row holds at every solution (see [LpModel.rowGlobal]); only
         *  rows whose constants baked in live domain bounds pass `false`. */
        @Suppress("LongParameterList")
        private fun addIntRow(
            vars: IntArray,
            coeffs: LongArray,
            auxCol: Int,
            auxCoeff: Long,
            rel: Relation,
            rhs: Long,
            global: Boolean = true,
            premises: LpRowPremises? = null,
        ) {
            val extra = if (auxCol >= 0) 1 else 0
            val cols = IntArray(vars.size + extra)
            val vals = LongArray(vars.size + extra)
            for (k in vars.indices) {
                cols[k] = intColumn(vars[k])
                vals[k] = coeffs[k]
            }
            if (auxCol >= 0) {
                cols[vars.size] = auxCol
                vals[vars.size] = auxCoeff
            }
            builder.addRow(cols, vals, rel, rhs, global, premises)
        }

        /**
         * Emit `Σ weights[k]·literal[k] rel rhs` over Boolean literals. A positive literal counts
         * `+w·x_b`, a negative literal `+w·(1 − x_b) = w − w·x_b`; the `+w` constants accumulate
         * and move to the right-hand side. [weights] of `null` means unit weights (cardinality).
         */
        private fun addBoolRow(literals: IntArray, weights: LongArray?, rel: Relation, rhs: Long) {
            val cols = IntArray(literals.size)
            val vals = LongArray(literals.size)
            var constant = 0L
            for (k in literals.indices) {
                val lit = literals[k]
                val w = weights?.get(k) ?: 1L
                cols[k] = boolColumn(Lit.variable(lit))
                if (Lit.isPositive(lit)) {
                    vals[k] = w
                } else {
                    vals[k] = -w
                    constant = addExact(constant, w)
                }
            }
            builder.addRow(cols, vals, rel, subExact(rhs, constant))
        }

        fun assemble(extraCuts: List<Cut>): LpRelaxation {
            // Materialize objective-only variables first so the relaxed objective is complete — including
            // LP-only continuous columns (issue #1232), else a real objective term with no constraint on it
            // never reaches the LP and the objective is not minimised over it.
            objective?.let { obj ->
                for (i in obj.intCoefficients.indices) if (obj.intCoefficients[i] != 0L) intColumn(i)
                for (b in obj.boolWeights.indices) if (obj.boolWeights[b] != 0L) boolColumn(b)
                for (r in obj.realCoefficients.indices) if (obj.realCoefficients[r] != 0.0) realColumn(r)
            }
            // Cone mode is the minimal linear+Boolean objective-cone probe: the column-heavy hull /
            // circuit / cut / cumulative features are all forced off (see [objectiveCone]). The
            // per-factor convex hulls are emitted by each factor's linearize in the main loop below
            // (gated by [currentHullEnabled]); only the non-per-factor relaxations live here — circuit
            // arcs feed the subtour separator, and the cumulative rows span a scheduling view.
            if (!objectiveCone) {
                if (circuitArcs) {
                    for (factor in problem.factors) {
                        if (factor is Circuit) {
                            if (factor.subcircuit) buildSubcircuitArcs(factor) else buildCircuitArcs(factor)
                        }
                    }
                }
                cumulativeRelaxation?.let { cumulativeRows(it) }
                if (cumulativeTimeIndexed) {
                    for (view in schedulingViews(problem)) buildCumulativeTimeIndexed(view)
                }
            }

            val coneL = cone
            for ((factorId, factor) in problem.factors.withIndex()) {
                // #571: in cone mode emit only factors connected to the objective; this also drops
                // every big-M ReifiedLinear row (they never extend the cone — see [coneTouches]).
                if (coneL != null && !coneTouches(factor, coneL.first, coneL.second)) continue
                if (realResidual && !touchesReals(factor)) continue
                currentFactorId = factorId
                // Each factor emits its own rows; factors with no linear relaxation (hard globals,
                // cut-only or scheduling-view factors) keep the default no-op [Factor.linearize] and
                // contribute nothing here — they are handled by the separators and the blocks above.
                currentHullEnabled = factorId !in suppressedHullFactors && !objectiveCone &&
                    factor.hullFamilyEnabled(hullFlags)
                factor.linearize(this, factorId)
            }

            if (booleanRlt) buildBooleanRlt()

            // Separator-produced cuts, over already-created columns. A cut referencing an absent
            // column is dropped (defensive — separators should only emit over existing columns).
            for (cut in extraCuts) {
                if (cut.cols.all { it in 0 until builder.varCount }) {
                    builder.addRow(cut.cols, cut.coeffs, cut.rel, cut.rhs, cut.global)
                }
            }

            val model = builder.build(Sense.MINIMIZE)
            val kinds = BooleanArray(colIsBool.size) { colIsBool[it] == 1 }
            val colVarIds = IntArray(colVarId.size) { colVarId[it] }
            val reqs = colReq.toTypedArray()
            val presentUpper = LongArray(colPresentUpper.size) { colPresentUpper[it] }
            // Persistent re-binding needs each column re-derivable from the live session: either
            // CP-var-backed (re-bound from its own domain) or an auxiliary column carrying a presence
            // rule (re-bound by pinning). The global cuts folded in here are fixed rows over existing
            // columns. Empty relaxations are not worth persisting.
            val eligible = structurallyPersistent && model.n > 0 &&
                colVarIds.indices.all { colVarIds[it] >= 0 || reqs[it] != null }
            return LpRelaxation(
                model = model,
                colVarId = colVarIds,
                colIsBool = kinds,
                objectiveConstant = objective?.constant ?: 0L,
                intColOf = intCol.copyOf(),
                boolColOf = boolCol.copyOf(),
                circuitArcs = circuitModels,
                persistentEligible = eligible,
                colReq = reqs,
                colPresentUpper = presentUpper,
                hullFactorIds = hullFactorIds.toIntArray(),
                colRealId = IntArray(colRealId.size) { colRealId[it] },
                colRealSign = IntArray(colRealSign.size) { colRealSign[it] },
            )
        }

        /** Whether [factor] involves an LP-only continuous column — the [realResidual] filter. */
        private fun touchesReals(factor: Factor): Boolean = when (factor) {
            is Linear -> factor.hasReals
            is ReifiedRealLinear -> true
            is RealProduct -> true
            else -> false
        }

        /**
         * Boolean Reformulation-Linearization-Technique cuts. For a 0/1 knapsack
         * row `Σₖ aₖ·xₖ ≤ b` (`aₖ > 0`, every `xₖ ∈ {0,1}`) and a multiplier `xᵢ` from it, multiplying by
         * `xᵢ ≥ 0` gives the valid `Σₖ aₖ·(xₖxᵢ) ≤ b·xᵢ`. Each product `wₖᵢ = xₖ·xᵢ` becomes an auxiliary
         * column with the binary McCormick envelope `wₖᵢ ≤ xₖ`, `wₖᵢ ≤ xᵢ`, `wₖᵢ ≥ xₖ + xᵢ − 1`
         * (`wₖᵢ ≥ 0` by domain); `wᵢᵢ = xᵢ` (idempotent). At any integer solution `wₖᵢ = xₖxᵢ` satisfies
         * every added row, so the relaxation excludes no feasible point — it only tightens the LP. Bounded
         * by [MAX_RLT_ROW] (row width) and [MAX_RLT_COLUMNS] (total product columns).
         */
        private fun buildBooleanRlt() {
            var rltColumns = 0
            for (f in problem.factors) {
                if (rltColumns >= MAX_RLT_COLUMNS) break
                if (f !is Linear || f.hasReals || f.op != LinearOp.LE) continue
                if (f.vars.size < 2 || f.vars.size > MAX_RLT_ROW) continue
                if (f.bound < 0 || f.coeffs.any { it <= 0 }) continue
                if (f.vars.any { problem.intDomains[it].min != 0L || problem.intDomains[it].max != 1L }) continue
                val b = f.bound
                for (iIdx in f.vars.indices) {
                    if (rltColumns >= MAX_RLT_COLUMNS) break
                    val xi = f.vars[iIdx]
                    val xiCol = intColumn(xi)
                    val rltRow = MutableIntLongMap() // coalesces the wᵢᵢ = xᵢ term with the −b·xᵢ term
                    for (kIdx in f.vars.indices) {
                        val xk = f.vars[kIdx]
                        val a = f.coeffs[kIdx]
                        val wCol = if (kIdx == iIdx) {
                            xiCol // wᵢᵢ = xᵢ²= xᵢ
                        } else {
                            val xkCol = intColumn(xk)
                            val w = auxColumn(0L, 1L, presence = longArrayOf(xk.toLong(), xi.toLong()))
                            rltColumns++
                            builder.addRow(intArrayOf(w, xkCol), longArrayOf(1L, -1L), Relation.LE, 0L) // w ≤ xₖ
                            builder.addRow(intArrayOf(w, xiCol), longArrayOf(1L, -1L), Relation.LE, 0L) // w ≤ xᵢ
                            builder.addRow(intArrayOf(w, xkCol, xiCol), longArrayOf(1L, -1L, -1L), Relation.GE, -1L)
                            w
                        }
                        rltRow.addTo(wCol, a)
                    }
                    rltRow.addTo(xiCol, -b) // Σ aₖwₖᵢ ≤ b·xᵢ
                    val colsList = IntArrayList()
                    rltRow.forEach { k, _ -> colsList.add(k) }
                    val cols = colsList.toIntArray()
                    builder.addRow(cols, LongArray(cols.size) { rltRow.getOrDefault(cols[it], 0L) }, Relation.LE, 0L)
                }
            }
        }

        /**
         * Emit the energetic makespan lower-bound row `capacity·M ≥ rhs` for each verified plan (see
         * [CumulativeRelaxation]). The right-hand side is recomputed from the live earliest-starts, so
         * the row tightens as branching raises task starts; it is marked global at the declared bounds
         * and otherwise carries the live starts it leaned on as premises. One row per plan keeps the
         * row count structural (warm-start safe).
         */
        private fun cumulativeRows(rel: CumulativeRelaxation) {
            for (plan in rel.plans) {
                val spec = rel.rowSpec(plan, domains)
                // Single-term row capacity·M ≥ rhs; the capacity coefficient is a (possibly wide) Long.
                builder.addRow(
                    intArrayOf(intColumn(plan.makespanVar)),
                    longArrayOf(plan.capacity),
                    Relation.GE,
                    spec.rhs,
                    spec.global,
                    spec.premises,
                )
            }
        }

        private fun relationOf(op: LinearOp): Relation? = when (op) {
            LinearOp.LE -> Relation.LE
            LinearOp.GE -> Relation.GE
            LinearOp.EQ -> Relation.EQ
            LinearOp.NE -> null // not LP-relaxable
        }

        /** True iff a row with this [contribution] should be skipped — a HULL row whose factor's hull is
         *  off this build. A HULL row that passes is attributed to the current factor, so the root pruner
         *  (`LpEngine.pruneIneffectiveHulls`) sees it as a removal candidate. CORE rows always pass. */
        private fun skipRow(contribution: Contribution): Boolean {
            if (contribution != Contribution.HULL) return false
            if (!currentHullEnabled) return true
            hullFactorIds.add(currentFactorId)
            return false
        }

        override fun linearRow(
            op: LinearOp,
            intVars: IntArray,
            coeffs: LongArray,
            bound: Long,
            contribution: Contribution,
        ) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            addIntRow(intVars, coeffs, auxCol = -1, auxCoeff = 0L, rel = rel, rhs = bound)
        }

        override fun boolRow(
            literals: IntArray,
            weights: LongArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            addBoolRow(literals, weights, rel, bound)
        }

        override fun hullEnabled(): Boolean = currentHullEnabled

        override fun liveDomain(intVar: Int): IntDomain = domains.intDomain(intVar)

        override fun liveBool(boolVar: Int): Boolean? = domains.boolValue(boolVar)

        override fun declaredDomain(intVar: Int): IntDomain = problem.intDomains[intVar]

        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            builder.addRow(columns, coeffs, rel, rhs)
        }

        override fun realRow(
            columns: IntArray,
            coeffs: DoubleArray,
            op: LinearOp,
            rhs: Double,
            strict: Boolean,
            premiseLits: IntArray,
        ) {
            val rel = relationOf(op) ?: return
            if (realNegOf.isEmpty() || columns.none { realNegOf.containsKey(it) }) {
                builder.addRealRow(columns, coeffs, rel, rhs, strict, premiseLits)
                return
            }
            // Mirror each split column's negative part with the negated coefficient.
            val extra = columns.count { realNegOf.containsKey(it) }
            val cols = IntArray(columns.size + extra)
            val vals = DoubleArray(columns.size + extra)
            var w = columns.size
            for (k in columns.indices) {
                cols[k] = columns[k]
                vals[k] = coeffs[k]
                val neg = realNegOf[columns[k]] ?: continue
                cols[w] = neg
                vals[w] = -coeffs[k]
                w++
            }
            builder.addRealRow(cols, vals, rel, rhs, strict, premiseLits)
        }

        override fun bigMRow(
            columns: IntArray,
            coeffs: LongArray,
            op: LinearOp,
            rhs: Long,
            global: Boolean,
            maxSide: Boolean,
        ) {
            val rel = relationOf(op) ?: return
            val premises = if (global) null else derivePremises(columns, coeffs, maxSide)
            builder.addRow(columns, coeffs, rel, rhs, global, premises)
        }

        /** The live bounds a non-global big-M row leans on, as [LpRowPremises]: per linearized integer
         *  column, its tightened live bound on the [maxSide]-relevant side (upper when the coefficient
         *  sign matches [maxSide], else lower). Auxiliary / Boolean indicator columns are skipped. */
        private fun derivePremises(columns: IntArray, coeffs: LongArray, maxSide: Boolean): LpRowPremises {
            val pv = IntArrayList()
            val pu = ArrayList<Boolean>()
            val pt = LongArrayList()
            for (j in columns.indices) {
                val col = columns[j]
                if (colIsBool[col] == 1 || colVarId[col] < 0) continue
                val c = coeffs[j]
                if (c == 0L) continue
                val v = colVarId[col]
                val dom = domains.intDomain(v)
                val dec = problem.intDomains[v]
                if ((c >= 0L) == maxSide) {
                    if (dom.max != dec.max) {
                        pv.add(v)
                        pu.add(true)
                        pt.add(dom.max)
                    }
                } else if (dom.min != dec.min) {
                    pv.add(v)
                    pu.add(false)
                    pt.add(dom.min)
                }
            }
            return LpRowPremises(pv.toIntArray(), BooleanArray(pu.size) { pu[it] }, pt.toLongArray())
        }
    }
}
