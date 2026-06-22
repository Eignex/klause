package com.eignex.klause.solver.lp.relaxation

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.ArrayMinMax
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.arithmetic.Product
import com.eignex.klause.solver.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.solver.factor.arithmetic.ReifiedLinear
import com.eignex.klause.solver.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.factor.circuit.Circuit
import com.eignex.klause.solver.factor.circuit.Subcircuit
import com.eignex.klause.solver.factor.global.GlobalCardinality
import com.eignex.klause.solver.factor.global.NValue
import com.eignex.klause.solver.factor.table.Element
import com.eignex.klause.solver.factor.table.Mdd
import com.eignex.klause.solver.factor.table.Regular
import com.eignex.klause.solver.factor.table.Table
import com.eignex.klause.solver.lp.LpBuilder
import com.eignex.klause.solver.lp.LpModel
import com.eignex.klause.solver.lp.LpRowPremises
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.Sense
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.cut.CircuitArcModel
import com.eignex.klause.solver.lp.cut.CircuitSeparator
import com.eignex.klause.solver.lp.cut.Cut
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.subExact
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntIntMap

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
    val colReq: Array<IntArray?> = arrayOfNulls(model.n),
    /** Per structural column, the upper bound an auxiliary column takes when present (see [colReq]);
     *  unused for CP-var columns. */
    val colPresentUpper: LongArray = LongArray(model.n),
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
                    if (!session.intDomain(req[k]).contains(req[k + 1])) {
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
                lo[j] = d.min.toLong()
                hi[j] = d.max.toLong()
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
    /** When true, build the arc-indicator relaxation of each [Circuit] / [Subcircuit] (degree +
     *  channelling rows over one `y_ij ∈ [0,1]` column per candidate arc). For Circuit it also records
     *  a [CircuitArcModel] feeding [CircuitSeparator]'s subtour-elimination cuts; Subcircuit gets the
     *  hull only (its cutset structure differs, #431). Adds O(arcs) columns, so it is gated. */
    private val circuitArcs: Boolean = false,
    /** When true, relax each [Product] `result = a·b` with its **McCormick envelope** (four bound-derived
     *  inequalities). For `a = b` (a square) the envelope degenerates to the secant/tangent relaxation.
     *  Adds four rows per product over the existing columns, so it is gated; off by default. */
    private val productMcCormick: Boolean = false,
    /** When true, add Boolean RLT rows (#D4): multiply each small 0/1 knapsack row by its binaries and
     *  linearize the products with the McCormick envelope. Adds product columns + rows (capped), so it
     *  is gated; off by default. Sound — the relaxation excludes no integer-feasible point. */
    private val booleanRlt: Boolean = false,
    /** When true, add the **tight face** of each [ArrayMinMax] (#C3, Anderson big-M form) on top of the
     *  always-emitted envelope: one-hot selectors `z_i` (`Σ z_i = 1`) and per-operand rows forcing
     *  `result = xs[i]` when `z_i = 1`, so the extremum is bounded from the tight side too (`result ≤
     *  max` / `result ≥ min`), not just the envelope side. Adds O(|xs|) columns, so it is gated. */
    private val linMaxTightFace: Boolean = false,
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
                it is ReifiedLinear || it is ReifiedPseudoBoolean || it is ReifiedCardinality
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

    /** Whether [f] (a cone-relevant, non-big-M factor) shares any variable with the current cone. */
    private fun coneTouches(f: Factor, intIn: BooleanArray, boolIn: BooleanArray): Boolean = when (f) {
        is Linear -> f.vars.any { intIn[it] }
        is ArrayMinMax -> intIn[f.result] || f.xs.any { intIn[it] }
        is Cardinality -> f.literals.any { boolIn[Lit.variable(it)] }
        is Clause -> f.literals.any { boolIn[Lit.variable(it)] }
        is PseudoBoolean -> f.literals.any { boolIn[Lit.variable(it)] }
        else -> false // ReifiedLinear (dropped) and hard globals do not extend the cone
    }

    /** Add every variable of [f] to the cone; returns true when anything was newly added. */
    private fun coneMark(f: Factor, intIn: BooleanArray, boolIn: BooleanArray): Boolean {
        var changed = false
        fun addInt(v: Int) {
            if (!intIn[v]) {
                intIn[v] = true
                changed = true
            }
        }
        fun addBool(lit: Int) {
            val b = Lit.variable(lit)
            if (!boolIn[b]) {
                boolIn[b] = true
                changed = true
            }
        }
        when (f) {
            is Linear -> for (v in f.vars) addInt(v)

            is ArrayMinMax -> {
                addInt(f.result)
                for (v in f.xs) addInt(v)
            }

            is Cardinality -> for (l in f.literals) addBool(l)

            is Clause -> for (l in f.literals) addBool(l)

            is PseudoBoolean -> for (l in f.literals) addBool(l)

            else -> Unit
        }
        return changed
    }

    /** Per-hull dense-tableau caps. Internal so [com.eignex.klause.solver.backtrack.lp.LpAutoConfig]'s
     *  size guard (#484) estimates each enabled hull against the *same* thresholds the builders skip
     *  at — one source of truth, no drift. */
    internal companion object {
        /** Above this candidate-arc count the circuit arc model is skipped — a defensive bound on
         *  the dense-tableau cost. Gating on arc count (LP columns) rather than node count lets
         *  large but sparse routing graphs through (#431); #429 may bench this threshold. */
        const val MAX_CIRCUIT_ARCS: Int = 1024

        /** Above this array length the O(len)-column Element selector model is skipped. */
        const val MAX_ELEM: Int = 256

        /** Boolean RLT (#D4) skips knapsack rows wider than this (each adds O(width) product columns). */
        const val MAX_RLT_ROW: Int = 8

        /** Cap on the total Boolean-RLT product columns added, bounding the dense-tableau cost. */
        const val MAX_RLT_COLUMNS: Int = 256

        /** Above this tuple count the O(numTuples)-column Table hull is skipped. */
        const val MAX_TUPLES: Int = 1024

        /** Above this horizon (latest deadline − earliest start) the time-indexed model is skipped. */
        const val MAX_TI_HORIZON: Int = 512

        /** Above this many `x_{i,t}` columns one time-indexed Cumulative is skipped (the O(n·H) blow-up). */
        const val MAX_TI_COLS: Int = 4096

        /** Above this total selector count (Σ over `xs` of the declared-domain size) the NValue
         *  one-hot value hull is skipped. */
        const val MAX_NVALUE_CELLS: Int = 1024

        /** Above this many arc columns one Regular DFA flow hull is skipped (the O(len·states·alphabet)
         *  blow-up). */
        const val MAX_REGULAR_ARCS: Int = 4096

        /** Above this many arc columns one Mdd flow hull is skipped (the transition-record blow-up). */
        const val MAX_MDD_ARCS: Int = 4096

        /** Above this total selector count (Σ over `xs` of the declared-domain size) the count-variable
         *  GlobalCardinality one-hot hull is skipped. */
        const val MAX_GCC_CELLS: Int = 1024
    }

    /** Build the relaxation, optionally appending separator-produced [extraCuts] as extra rows. */
    fun build(session: PropagationSession, extraCuts: List<Cut> = emptyList()): LpRelaxation =
        Assembler(session).assemble(extraCuts)

    private fun intCost(i: Int): Long = objective?.intCoefficients?.getOrElse(i) { 0L } ?: 0L

    private fun boolCost(b: Int): Long = objective?.boolWeights?.getOrElse(b) { 0L } ?: 0L

    /** Per-build mutable state: the builder, the column maps, and the row emitters. */
    private inner class Assembler(private val session: PropagationSession) {
        private val builder = LpBuilder()
        private val intCol = IntArray(problem.numIntVars) { -1 }
        private val boolCol = IntArray(problem.numBoolVars) { -1 }
        private val colVarId = IntArrayList()
        private val colIsBool = IntArrayList() // 0 = int, 1 = bool; densified at the end

        // Per-column live-bound rule for the persistent relaxation (#39/#43). For an auxiliary column,
        // colReq[c] holds its presence requirement as flat (intVar, value) membership pairs and
        // colPresentUpper[c] the upper bound when present — so a node can re-bind the column (upper = 0
        // when any required value left the live domain, else the present-upper) instead of rebuilding.
        // null/0 for CP-var columns (re-bound from the variable's own domain) and for un-ruled aux
        // columns (which keep the relaxation off the persistent path).
        private val colReq = ArrayList<IntArray?>()
        private val colPresentUpper = LongArrayList()

        /** Arc-indicator models recorded by `buildCircuitArcs` for the subtour-elimination separator. */
        private val circuitModels = ArrayList<CircuitArcModel>()

        /** Auxiliary LP column with no backing CP variable (tag/colVarId = -1) — e.g. a circuit arc.
         *  [presence] names the `(intVar, value)` memberships that must all hold for the column to be
         *  present (upper [hi]); when given, the column can be re-bound on the persistent path. */
        private fun auxColumn(lo: Long, hi: Long, presence: IntArray? = null): Int {
            val c = builder.addVar(lo, hi, cost = 0L, tag = -1)
            colVarId.add(-1)
            colIsBool.add(0)
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
         * Arc-indicator relaxation of one [Subcircuit] over `succ[0..n)`. As `buildCircuitArcs` but the
         * self-loop arc `y_ii` (= "node i is excluded") is a candidate, so the degree + channel rows
         * describe the **permutation** polytope (each node has exactly one in- and out-arc, fixed points
         * allowed). **No subtour-elimination model is registered**: the Hamiltonian SEC is *unsound* for
         * a subcircuit (an all-excluded subset legitimately has no leaving arc).
         */
        private fun buildSubcircuitArcs(factor: Subcircuit) = buildArcModel(factor.succ, selfLoops = true, sec = false)

        /**
         * Shared degree + channel arc model for [Circuit] / [Subcircuit]: a `y_ij ∈ [0,1]` column per
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
                problem.intDomains[succ[i]].forEach { j -> if ((selfLoops || j != i) && j in 0 until n) arcCount++ }
            }
            if (arcCount == 0 || arcCount > MAX_CIRCUIT_ARCS) return
            val tails = IntArrayList()
            val heads = IntArrayList()
            val cols = IntArrayList()
            val inColsByHead = Array(n) { IntArrayList() }
            // Out-degree and channelling rows, building the (sparse) arc columns on the way.
            for (i in 0 until n) {
                val live = session.intDomain(succ[i])
                val outCols = IntArrayList()
                val chanCols = IntArrayList()
                val chanCoef = IntArrayList()
                problem.intDomains[succ[i]].forEach { j ->
                    if ((!selfLoops && j == i) || j < 0 || j >= n) return@forEach
                    val present = live.contains(j)
                    // The arc is present exactly while head j stays in succ[i]'s live domain — the single
                    // membership that lets the persistent relaxation re-bind this column (#43).
                    val col = auxColumn(0L, if (present) 1L else 0L, presence = intArrayOf(succ[i], j))
                    outCols.add(col)
                    chanCols.add(col)
                    chanCoef.add(j)
                    tails.add(i)
                    heads.add(j)
                    cols.add(col)
                    inColsByHead[j].add(col)
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

        /** Column for integer variable `i`, created on first use with its live domain bounds. */
        private fun intColumn(i: Int): Int {
            var c = intCol[i]
            if (c == -1) {
                val dom = session.intDomain(i)
                c = builder.addVar(dom.min.toLong(), dom.max.toLong(), intCost(i), tag = i)
                intCol[i] = c
                colVarId.add(i)
                colIsBool.add(0)
                colReq.add(null)
                colPresentUpper.add(0L)
            }
            return c
        }

        /** Column for Boolean variable `b`; bounds collapse to a point if it is pinned this node. */
        private fun boolColumn(b: Int): Int {
            var c = boolCol[b]
            if (c == -1) {
                val pinned = session.boolValue(b)
                val lo = if (pinned == true) 1L else 0L
                val hi = if (pinned == false) 0L else 1L
                c = builder.addVar(lo, hi, boolCost(b), tag = b)
                boolCol[b] = c
                colVarId.add(b)
                colIsBool.add(1)
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
            coeffs: IntArray,
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
                vals[k] = coeffs[k].toLong()
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
        private fun addBoolRow(literals: IntArray, weights: IntArray?, rel: Relation, rhs: Long) {
            val cols = IntArray(literals.size)
            val vals = LongArray(literals.size)
            var constant = 0L
            for (k in literals.indices) {
                val lit = literals[k]
                val w = (weights?.get(k) ?: 1).toLong()
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

        /**
         * Indicator rows for `auxBoolVar ↔ (L op bound)` via big-M, where `L = Σ coeffs·vars`. The
         * big-Ms are the tightest possible from the live range `[lMin, lMax]` of `L`, and the
         * `¬(L op bound)` side uses integrality (`¬(L ≤ bound) ⇔ L ≥ bound + 1`) so the rows are as
         * strong as a single indicator allows.
         *
         * For `EQ` only the `aux = 1 ⇒ L = bound` direction is emitted, and for `NE` only the
         * `aux = 0 ⇒ L = bound` direction: the complementary side is the disjunction `L ≠ bound`,
         * whose convex hull is the whole interval, so it yields no valid LP cut and is dropped.
         *
         * A live big-M bakes branch-tightened bounds into the row's constants, so the row only holds
         * inside the node's box. Each row is therefore marked global exactly when its M equals the
         * M the *declared* range `[lMinD, lMaxD]` would give — then the relaxed face spans the whole
         * declared box and the row holds at every solution (see [LpModel.rowGlobal]). A non-global
         * row records the live bounds its M rests on as [LpRowPremises] — the lMax-side rows cite
         * each variable's M-relevant live bound (`≤ max` for positive coefficients, `≥ min` for
         * negative; mirrored for lMin-side rows) — so a certificate that leans on the row can cite
         * those atoms instead of being withheld (see the LP explanation).
         */
        private fun reifiedRows(rl: ReifiedLinear) {
            var lMin = 0L
            var lMax = 0L
            var lMinD = 0L
            var lMaxD = 0L
            for (k in rl.vars.indices) {
                val c = rl.coeffs[k].toLong()
                val dom = session.intDomain(rl.vars[k])
                val dec = problem.intDomains[rl.vars[k]]
                if (c >= 0L) {
                    lMin = addExact(lMin, mulExact(c, dom.min.toLong()))
                    lMax = addExact(lMax, mulExact(c, dom.max.toLong()))
                    lMinD = addExact(lMinD, mulExact(c, dec.min.toLong()))
                    lMaxD = addExact(lMaxD, mulExact(c, dec.max.toLong()))
                } else {
                    lMin = addExact(lMin, mulExact(c, dom.max.toLong()))
                    lMax = addExact(lMax, mulExact(c, dom.min.toLong()))
                    lMinD = addExact(lMinD, mulExact(c, dec.max.toLong()))
                    lMaxD = addExact(lMaxD, mulExact(c, dec.min.toLong()))
                }
            }
            val a = boolColumn(rl.auxBoolVar)
            val bound = rl.bound.toLong()
            val boundUp = addExact(bound, 1L) // L ≥ bound + 1 is the integer negation of L ≤ bound
            val boundDown = subExact(bound, 1L)

            // The live bounds the lMax side (maxSide = true) or lMin side of the M rests on; only
            // bounds tighter than declared are cited (the rest hold everywhere).
            fun sidePremises(maxSide: Boolean): LpRowPremises {
                val pv = IntArrayList()
                val pt = IntArrayList()
                val pu = ArrayList<Boolean>()
                for (k in rl.vars.indices) {
                    val c = rl.coeffs[k]
                    if (c == 0) continue
                    val v = rl.vars[k]
                    val dom = session.intDomain(v)
                    val dec = problem.intDomains[v]
                    if ((c >= 0) == maxSide) {
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
                return LpRowPremises(pv.toIntArray(), BooleanArray(pu.size) { pu[it] }, pt.toIntArray())
            }

            fun row(auxCoeff: Long, rel: Relation, rhs: Long, global: Boolean, maxSide: Boolean) = addIntRow(
                rl.vars,
                rl.coeffs,
                a,
                auxCoeff,
                rel,
                rhs,
                global,
                premises = if (global) null else sidePremises(maxSide),
            )

            when (rl.op) {
                LinearOp.LE -> {
                    val m1 = maxOf(0L, subExact(lMax, bound)) // aux=1 ⇒ L ≤ bound
                    row(m1, Relation.LE, addExact(bound, m1), m1 == maxOf(0L, subExact(lMaxD, bound)), maxSide = true)
                    val m2 = maxOf(0L, subExact(boundUp, lMin)) // aux=0 ⇒ L ≥ bound+1
                    row(m2, Relation.GE, boundUp, m2 == maxOf(0L, subExact(boundUp, lMinD)), maxSide = false)
                }

                LinearOp.GE -> {
                    val m1 = maxOf(0L, subExact(bound, lMin)) // aux=1 ⇒ L ≥ bound
                    row(
                        -m1,
                        Relation.GE,
                        subExact(bound, m1),
                        m1 == maxOf(0L, subExact(bound, lMinD)),
                        maxSide = false,
                    )
                    val m2 = maxOf(0L, subExact(lMax, boundDown)) // aux=0 ⇒ L ≤ bound-1
                    row(
                        -m2,
                        Relation.LE,
                        boundDown,
                        m2 == maxOf(0L, subExact(lMaxD, boundDown)),
                        maxSide = true,
                    )
                }

                LinearOp.EQ -> {
                    val mHi = maxOf(0L, subExact(lMax, bound)) // aux=1 ⇒ L ≤ bound
                    row(
                        mHi,
                        Relation.LE,
                        addExact(bound, mHi),
                        mHi == maxOf(0L, subExact(lMaxD, bound)),
                        maxSide = true,
                    )
                    val mLo = maxOf(0L, subExact(bound, lMin)) // aux=1 ⇒ L ≥ bound
                    row(
                        -mLo,
                        Relation.GE,
                        subExact(bound, mLo),
                        mLo == maxOf(0L, subExact(bound, lMinD)),
                        maxSide = false,
                    )
                }

                LinearOp.NE -> {
                    val mHi = maxOf(0L, subExact(lMax, bound)) // aux=0 ⇒ L ≤ bound
                    row(-mHi, Relation.LE, bound, mHi == maxOf(0L, subExact(lMaxD, bound)), maxSide = true)
                    val mLo = maxOf(0L, subExact(bound, lMin)) // aux=0 ⇒ L ≥ bound
                    row(mLo, Relation.GE, bound, mLo == maxOf(0L, subExact(bound, lMinD)), maxSide = false)
                }
            }
        }

        /** The Boolean fan-in of a reified weighted sum folded over its columns: `Σ coeffs·x_col +
         *  constant`, with the declared `[lMin, lMax]` range of the `Σ coeffs·x_col` part over `x ∈ [0,1]`.
         *  A negative literal `w·(1 − x)` folds to coefficient `−w` and `+w` into the constant. */
        private inner class BoolSum(
            val cols: IntArray,
            val coeffs: LongArray,
            val constant: Long,
            val lMin: Long,
            val lMax: Long,
        )

        private fun boolSum(literals: IntArray, weights: IntArray?): BoolSum {
            val coeffByCol = LinkedHashMap<Int, Long>()
            var constant = 0L
            for (k in literals.indices) {
                val lit = literals[k]
                val w = (weights?.get(k) ?: 1).toLong()
                val col = boolColumn(Lit.variable(lit))
                val c = if (Lit.isPositive(lit)) w else -w
                if (!Lit.isPositive(lit)) constant = addExact(constant, w)
                coeffByCol[col] = addExact(coeffByCol.getOrElse(col) { 0L }, c)
            }
            val cols = IntArray(coeffByCol.size)
            val coeffs = LongArray(coeffByCol.size)
            var lMin = 0L
            var lMax = 0L
            var i = 0
            for ((col, c) in coeffByCol) {
                cols[i] = col
                coeffs[i] = c
                i++
                if (c >= 0L) lMax = addExact(lMax, c) else lMin = addExact(lMin, c)
            }
            return BoolSum(cols, coeffs, constant, lMin, lMax)
        }

        /** Emit `Σ sum.coeffs·x + auxCoeff·x_aux  rel  rhs`. The big-M rests on the *declared* `[0,1]`
         *  literal ranges, so the row holds at every solution — globally valid (and cacheable), unlike
         *  the live-big-M [reifiedRows]; no [LpRowPremises] are needed. */
        private fun boolReifiedRow(sum: BoolSum, auxCol: Int, auxCoeff: Long, rel: Relation, rhs: Long) {
            val cols = sum.cols.copyOf(sum.cols.size + 1)
            val vals = sum.coeffs.copyOf(sum.coeffs.size + 1)
            cols[sum.cols.size] = auxCol
            vals[sum.coeffs.size] = auxCoeff
            builder.addRow(cols, vals, rel, rhs)
        }

        /**
         * Indicator rows for `auxBoolVar ↔ (Σ weights·lit ⟨op⟩ bound)` over Boolean literals — the
         * pseudo-Boolean analogue of [reifiedRows]. The big-M comes from the declared `[0,1]` ranges
         * (so the rows are global), and for `EQ` only the `aux = 1 ⇒ L = bound` direction is emitted
         * (its complement is a disjunction with no single LP cut), mirroring [reifiedRows].
         */
        private fun reifiedPbRows(rpb: ReifiedPseudoBoolean) {
            val sum = boolSum(rpb.literals, rpb.weights)
            val a = boolColumn(rpb.auxBoolVar)
            val bound = subExact(rpb.bound.toLong(), sum.constant)
            when (rpb.op) {
                PbOp.LE -> {
                    val m1 = maxOf(0L, subExact(sum.lMax, bound)) // aux=1 ⇒ L ≤ bound
                    boolReifiedRow(sum, a, m1, Relation.LE, addExact(bound, m1))
                    val m2 = maxOf(0L, subExact(addExact(bound, 1L), sum.lMin)) // aux=0 ⇒ L ≥ bound+1
                    boolReifiedRow(sum, a, m2, Relation.GE, addExact(bound, 1L))
                }

                PbOp.GE -> {
                    val m1 = maxOf(0L, subExact(bound, sum.lMin)) // aux=1 ⇒ L ≥ bound
                    boolReifiedRow(sum, a, -m1, Relation.GE, subExact(bound, m1))
                    val m2 = maxOf(0L, subExact(sum.lMax, subExact(bound, 1L))) // aux=0 ⇒ L ≤ bound-1
                    boolReifiedRow(sum, a, -m2, Relation.LE, subExact(bound, 1L))
                }

                PbOp.EQ -> {
                    val mHi = maxOf(0L, subExact(sum.lMax, bound)) // aux=1 ⇒ L ≤ bound
                    boolReifiedRow(sum, a, mHi, Relation.LE, addExact(bound, mHi))
                    val mLo = maxOf(0L, subExact(bound, sum.lMin)) // aux=1 ⇒ L ≥ bound
                    boolReifiedRow(sum, a, -mLo, Relation.GE, subExact(bound, mLo))
                }
            }
        }

        /**
         * Indicator rows for `auxBoolVar ↔ (min ≤ #true literals ≤ max)`. Only the
         * `aux = 1 ⇒ (count ≥ min ∧ count ≤ max)` direction yields LP cuts (the `aux = 0` side is the
         * disjunction `count < min ∨ count > max`, whose hull is the whole interval), so two rows are
         * emitted, big-M'd from the declared `[0,1]` ranges (globally valid).
         */
        private fun reifiedCardRows(rc: ReifiedCardinality) {
            val sum = boolSum(rc.literals, weights = null)
            val a = boolColumn(rc.auxBoolVar)
            val lo = subExact(rc.min.toLong(), sum.constant)
            val hi = subExact(rc.max.toLong(), sum.constant)
            val mHi = maxOf(0L, subExact(sum.lMax, hi)) // aux=1 ⇒ count ≤ max
            boolReifiedRow(sum, a, mHi, Relation.LE, addExact(hi, mHi))
            val mLo = maxOf(0L, subExact(lo, sum.lMin)) // aux=1 ⇒ count ≥ min
            boolReifiedRow(sum, a, -mLo, Relation.GE, subExact(lo, mLo))
        }

        fun assemble(extraCuts: List<Cut>): LpRelaxation {
            // Materialize objective-only variables first so the relaxed objective is complete.
            objective?.let { obj ->
                for (i in obj.intCoefficients.indices) if (obj.intCoefficients[i] != 0L) intColumn(i)
                for (b in obj.boolWeights.indices) if (obj.boolWeights[b] != 0L) boolColumn(b)
            }
            // Cone mode is the minimal linear+Boolean objective-cone probe: the column-heavy hull /
            // circuit / cut / cumulative features are all forced off (see [objectiveCone]).
            if (!objectiveCone) {
                if (elementHull) {
                    for (factor in problem.factors) if (factor is Element) buildElementHull(factor)
                }
                if (tableHull) {
                    for (factor in problem.factors) if (factor is Table) buildTableHull(factor)
                }
                if (nValueHull) {
                    for (factor in problem.factors) if (factor is NValue) buildNValueHull(factor)
                }
                if (regularHull) {
                    for (factor in problem.factors) if (factor is Regular) buildRegularHull(factor)
                }
                if (mddHull) {
                    for (factor in problem.factors) if (factor is Mdd) buildMddHull(factor)
                }
                if (gccCountHull) {
                    for (factor in problem.factors) if (factor is GlobalCardinality) buildGccCountHull(factor)
                }
                if (circuitArcs) {
                    for (factor in problem.factors) {
                        if (factor is Circuit) buildCircuitArcs(factor)
                        if (factor is Subcircuit) buildSubcircuitArcs(factor)
                    }
                }
                cumulativeRelaxation?.let { cumulativeRows(it) }
                if (cumulativeTimeIndexed) {
                    for (view in schedulingViews(problem)) buildCumulativeTimeIndexed(view)
                }
            }

            val coneL = cone
            for (factor in problem.factors) {
                // #571: in cone mode emit only factors connected to the objective; this also drops
                // every big-M ReifiedLinear row (they never extend the cone — see [coneTouches]).
                if (coneL != null && !coneTouches(factor, coneL.first, coneL.second)) continue
                when (factor) {
                    is Linear -> linearRow(factor.op, factor.vars, factor.coeffs, factor.bound.toLong())

                    is ReifiedLinear -> reifiedRows(factor)

                    is ReifiedPseudoBoolean -> reifiedPbRows(factor)

                    is ReifiedCardinality -> reifiedCardRows(factor)

                    is Cardinality -> {
                        addBoolRow(factor.literals, null, Relation.GE, factor.min.toLong())
                        addBoolRow(factor.literals, null, Relation.LE, factor.max.toLong())
                    }

                    is Clause -> addBoolRow(factor.literals, null, Relation.GE, 1L)

                    // result = max(xs) / min(xs) — the extremum is the envelope of its operands:
                    // result ≥ xs[i] for max, result ≤ xs[i] for min. One row per operand, sound
                    // (the opposite face, result tight to some operand, is not a single LP cut).
                    is ArrayMinMax -> {
                        val rel = if (factor.max) Relation.GE else Relation.LE
                        for (x in factor.xs) {
                            addIntRow(
                                intArrayOf(factor.result),
                                intArrayOf(1),
                                auxCol = intColumn(x),
                                auxCoeff = -1L,
                                rel = rel,
                                rhs = 0L,
                            )
                        }
                        if (linMaxTightFace) buildLinMaxTightFace(factor)
                    }

                    is PseudoBoolean -> {
                        val rel = when (factor.op) {
                            PbOp.LE -> Relation.LE
                            PbOp.GE -> Relation.GE
                            PbOp.EQ -> Relation.EQ
                        }
                        addBoolRow(factor.literals, factor.weights, rel, factor.bound.toLong())
                    }

                    is Product -> if (productMcCormick) buildProductMcCormick(factor)

                    else -> Unit // hard globals and unrecognized factors: handled elsewhere or skipped
                }
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
            )
        }

        /**
         * Convex-hull linearization of one [Table] `(xs) ∈ tuples`: a selector column `y_t ∈ [0,1]`
         * per allowed tuple, with `Σ_t y_t = 1` and a per-column channel `xs[j] = Σ_t tuple_t[j]·y_t`.
         * The projection onto `xs` is exactly the convex hull of the allowed tuples — the strongest
         * linear relaxation of the table. A tuple's column exists when every entry is in the declared
         * domain of its variable (layout stable across nodes), and is pinned to 0 when any entry has
         * left the live domain. Tables with more than [MAX_TUPLES] rows are skipped.
         */
        private fun buildTableHull(factor: Table) {
            val numTuples = factor.numTuples
            if (numTuples > MAX_TUPLES) return
            val arity = factor.arity
            val declared = Array(arity) { c -> problem.intDomains[factor.xs[c]] }
            val live = Array(arity) { c -> session.intDomain(factor.xs[c]) }
            val selCols = IntArrayList()
            val rows = IntArrayList()
            for (t in 0 until numTuples) {
                var declaredFeasible = true
                var liveFeasible = true
                for (col in 0 until arity) {
                    val v = factor.tuples[t * arity + col]
                    if (v !in declared[col]) {
                        declaredFeasible = false
                        break
                    }
                    if (v !in live[col]) liveFeasible = false
                }
                if (!declaredFeasible) continue
                // The tuple's selector is present while every entry stays in its column's live domain —
                // the membership conjunction that lets the persistent relaxation re-bind this column.
                val presence = IntArray(arity * 2)
                for (col in 0 until arity) {
                    presence[col * 2] = factor.xs[col]
                    presence[col * 2 + 1] = factor.tuples[t * arity + col]
                }
                selCols.add(auxColumn(0L, if (liveFeasible) 1L else 0L, presence = presence))
                rows.add(t)
            }
            val k = selCols.size
            if (k == 0) return // no tuple feasible under the declared domains — leave it to propagation
            builder.addRow(selCols.toIntArray(), LongArray(k) { 1L }, Relation.EQ, 1L)
            // xs[col] − Σ_t tuple_t[col]·y_t = 0 for each column.
            for (col in 0 until arity) {
                val cols = IntArray(k + 1)
                val vals = LongArray(k + 1)
                for (s in 0 until k) {
                    cols[s] = selCols[s]
                    vals[s] = -factor.tuples[rows[s] * arity + col].toLong()
                }
                cols[k] = intColumn(factor.xs[col])
                vals[k] = 1L
                builder.addRow(cols, vals, Relation.EQ, 0L)
            }
        }

        /**
         * One-hot value model for one [NValue] `n = |distinct(xs)|` (and the AtMost / AtLeast
         * variants): a per-value "used" column `y_v ∈ [0,1]`, a one-hot selector `z_iv ∈ [0,1]` per
         * variable/value with `Σ_v z_iv = 1` and the channel `Σ_v v·z_iv = xs(i)`, and `y_v ≥ z_iv` so
         * a value taken by any variable forces its indicator up. The distinct count `Σ_v y_v` relates
         * to `n` by the mode: `Eq → n = Σ y_v`, `AtMost (n ≥ distinct) → n ≥ Σ y_v`,
         * `AtLeast (n ≤ distinct) → n ≤ Σ y_v`. Each relation holds at every integer solution (set
         * `y_v = 1` iff value v is used), so the relaxation is sound; minimising `n` then reads a real
         * lower bound off `Σ y_v`. Gated by [MAX_NVALUE_CELLS]; optional-presence NValue is skipped.
         */
        private fun buildNValueHull(factor: NValue) {
            if (factor.presents.isNotEmpty()) return // count is over present vars only — defer
            val xs = factor.xs
            var cells = 0L
            for (x in xs) cells += problem.intDomains[x].size.toLong()
            if (cells == 0L || cells > MAX_NVALUE_CELLS) return
            val yCols = IntArrayList()
            val yByValue = MutableIntIntMap()
            fun yOf(v: Int): Int {
                val existing = yByValue.getOrDefault(v, -1) // columns are non-negative, so -1 marks absent
                if (existing >= 0) return existing
                // The "used" indicator is free in [0,1] regardless of the live domains — an empty
                // requirement keeps it present so the relaxation stays persistent (#43).
                val col = auxColumn(0L, 1L, presence = IntArray(0))
                yCols.add(col)
                yByValue.put(v, col)
                return col
            }
            for (x in xs) {
                val declared = problem.intDomains[x]
                val live = session.intDomain(x)
                val sel = IntArrayList()
                val selVal = IntArrayList()
                declared.forEach { v ->
                    // The selector z_xv is present while value v stays in x's live domain.
                    val z = auxColumn(0L, if (live.contains(v)) 1L else 0L, presence = intArrayOf(x, v))
                    sel.add(z)
                    selVal.add(v)
                    builder.addRow(intArrayOf(z, yOf(v)), longArrayOf(1L, -1L), Relation.LE, 0L) // y_v ≥ z
                }
                val k = sel.size
                if (k == 0) return // a variable with no declared values — leave it to propagation
                builder.addRow(sel.toIntArray(), LongArray(k) { 1L }, Relation.EQ, 1L) // Σ_v z = 1
                // Σ_v v·z − xs(i) = 0.
                val cCols = IntArray(k + 1)
                val cVals = LongArray(k + 1)
                for (s in 0 until k) {
                    cCols[s] = sel[s]
                    cVals[s] = selVal[s].toLong()
                }
                cCols[k] = intColumn(x)
                cVals[k] = -1L
                builder.addRow(cCols, cVals, Relation.EQ, 0L)
            }
            if (yCols.isEmpty()) return
            // (Σ_v y_v) − n  {EQ | LE | GE}  0, per the mode (see KDoc).
            val rel = when (factor.mode) {
                NValue.Mode.Eq -> Relation.EQ
                NValue.Mode.AtMost -> Relation.LE
                NValue.Mode.AtLeast -> Relation.GE
            }
            val m = yCols.size
            val cols = IntArray(m + 1)
            val vals = LongArray(m + 1)
            for (idx in 0 until m) {
                cols[idx] = yCols[idx]
                vals[idx] = 1L
            }
            cols[m] = intColumn(factor.n)
            vals[m] = -1L
            builder.addRow(cols, vals, rel, 0L)
        }

        /**
         * One-hot selector model for one count-variable [GlobalCardinality] `counts(k) = #{i : xs(i) =
         * cover(k)}`: a one-hot selector `z_iv ∈ [0,1]` per variable/value over `xs[i]`'s declared
         * domain with `Σ_v z_iv = 1` and the channel `Σ_v v·z_iv = xs(i)`, and per cover value the
         * exact count linkage `Σ_i z_{i,cover(k)} = counts(k)`. Each row holds at every integer solution
         * (set `z_iv = 1` iff `xs(i) = v`), so the relaxation is sound and a count variable in the
         * objective reads a true LP bound. Gated by [MAX_GCC_CELLS]; the constant-bound form (no count
         * variable) and the optional-presence form (count over present vars only) are skipped — neither
         * has a count variable this hull would bound.
         */
        private fun buildGccCountHull(factor: GlobalCardinality) {
            if (factor.presents.isNotEmpty()) return // count is over present vars only — defer
            val countVars = factor.countVars ?: return // constant-bound form has no count var to bound
            val xs = factor.xs
            var cells = 0L
            for (x in xs) cells += problem.intDomains[x].size.toLong()
            if (cells == 0L || cells > MAX_GCC_CELLS) return
            // Selector columns contributing to each cover value's count, accumulated across all xs.
            val selByCover = HashMap<Int, IntArrayList>()
            for (v in factor.cover) selByCover[v] = IntArrayList()
            for (x in xs) {
                val declared = problem.intDomains[x]
                val live = session.intDomain(x)
                val sel = IntArrayList()
                val selVal = IntArrayList()
                declared.forEach { v ->
                    // The selector z_xv is present while value v stays in x's live domain.
                    val z = auxColumn(0L, if (live.contains(v)) 1L else 0L, presence = intArrayOf(x, v))
                    sel.add(z)
                    selVal.add(v)
                    selByCover[v]?.add(z) // only cover values carry a count row
                }
                val k = sel.size
                if (k == 0) return // a variable with no declared values — leave it to propagation
                builder.addRow(sel.toIntArray(), LongArray(k) { 1L }, Relation.EQ, 1L) // Σ_v z = 1
                // Σ_v v·z − xs(i) = 0.
                val cCols = IntArray(k + 1)
                val cVals = LongArray(k + 1)
                for (s in 0 until k) {
                    cCols[s] = sel[s]
                    cVals[s] = selVal[s].toLong()
                }
                cCols[k] = intColumn(x)
                cVals[k] = -1L
                builder.addRow(cCols, cVals, Relation.EQ, 0L)
            }
            // Σ_i z_{i,cover(k)} − counts(k) = 0 per cover value (a cover value in no domain forces 0).
            for (k in factor.cover.indices) {
                val sel = selByCover[factor.cover[k]] ?: continue
                val cols = IntArray(sel.size + 1)
                val vals = LongArray(sel.size + 1)
                for (i in 0 until sel.size) {
                    cols[i] = sel[i]
                    vals[i] = 1L
                }
                cols[sel.size] = intColumn(countVars[k])
                vals[sel.size] = -1L
                builder.addRow(cols, vals, Relation.EQ, 0L)
            }
        }

        /**
         * Layer-expanded DFA flow hull of one [Regular] `regular(seq, Q, S, δ, q0, F)` — the exact convex
         * hull of the automaton's accepting strings. An arc variable `y ∈ [0,1]` per reachable
         * `(position t, state q, symbol s)` whose transition `δ(q, s)` is live (pinned to 0 when symbol
         * `s` left the live domain of `seq[t]`); states 1-based, `δ = 0` is the dead/reject sink. Rows:
         * a source row `Σ y out of (0, q0) = 1`, flow conservation `Σ out(t,q) − Σ in(t,q) = 0` at every
         * interior `(t, q)`, an acceptance row `Σ y into accepting states at the last layer = 1`, and a
         * channel `Σ_s s·y_{t,·,s} = seq[t]` per position. The flow polytope is integral, so the LP is
         * the true convex hull and its optimum a tight bound. Forward reachability from `q0` over the
         * *declared* domains keeps the layout stable and bounds the arc count ([MAX_REGULAR_ARCS]); above
         * the cap, or when no accepting path survives the declared domains, the factor is skipped (only
         * loosens). 1-based symbol values are taken to *be* the `seq` values (per [Regular]).
         */
        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
        private fun buildRegularHull(factor: Regular) {
            val seq = factor.seq
            val len = seq.size
            val s = factor.alphabetSize
            val trans = factor.transitions
            fun delta(state: Int, sym: Int): Int = trans[(state - 1) * s + (sym - 1)] // 1-based; 0 = dead
            val accepting = factor.accepting
            // States are 1-based ids in `1..numStates`, so the per-layer state→arc-columns maps are
            // dense arrays indexed straight by the state id rather than boxed `HashMap<Int, _>` (#678).
            val ns = factor.numStates

            // Forward-reachable states per layer over the declared domains; bail if a layer empties.
            val reach = Array(len + 1) { IntHashSet() }
            reach[0].add(factor.q0)
            var arcCount = 0L
            for (t in 0 until len) {
                val dom = problem.intDomains[seq[t]]
                reach[t].forEach { state ->
                    dom.forEach { sym ->
                        if (sym in 1..s) {
                            val nxt = delta(state, sym)
                            if (nxt != 0) {
                                reach[t + 1].add(nxt)
                                arcCount++
                            }
                        }
                    }
                }
                if (reach[t + 1].isEmpty()) return // no accepting path under declared domains — leave to propagation
            }
            if (arcCount == 0L || arcCount > MAX_REGULAR_ARCS) return

            val outCols = Array(len) { arrayOfNulls<IntArrayList>(ns + 1) }
            val inCols = Array(len + 1) { arrayOfNulls<IntArrayList>(ns + 1) }
            val chanCols = Array(len) { IntArrayList() }
            val chanSym = Array(len) { IntArrayList() }
            val acceptCols = IntArrayList()
            for (t in 0 until len) {
                val declared = problem.intDomains[seq[t]]
                val live = session.intDomain(seq[t])
                reach[t].forEach { state ->
                    declared.forEach { sym ->
                        if (sym !in 1..s) return@forEach
                        val nxt = delta(state, sym)
                        if (nxt == 0) return@forEach
                        // The arc is present while symbol sym stays in seq[t]'s live domain.
                        val col = auxColumn(0L, if (live.contains(sym)) 1L else 0L, presence = intArrayOf(seq[t], sym))
                        (outCols[t][state] ?: IntArrayList().also { outCols[t][state] = it }).add(col)
                        (inCols[t + 1][nxt] ?: IntArrayList().also { inCols[t + 1][nxt] = it }).add(col)
                        chanCols[t].add(col)
                        chanSym[t].add(sym)
                        if (t == len - 1 && nxt in accepting) acceptCols.add(col)
                    }
                }
            }
            // Source: one unit leaves (0, q0).
            val src = outCols[0][factor.q0] ?: return
            if (src.isEmpty()) return
            builder.addRow(src.toIntArray(), LongArray(src.size) { 1L }, Relation.EQ, 1L)
            // Flow conservation at every interior node: Σ out − Σ in = 0.
            for (t in 1 until len) {
                reach[t].forEach { state ->
                    val cols = IntArrayList()
                    val vals = LongArrayList()
                    outCols[t][state]?.let {
                        for (k in 0 until it.size) {
                            cols.add(it[k])
                            vals.add(1L)
                        }
                    }
                    inCols[t][state]?.let {
                        for (k in 0 until it.size) {
                            cols.add(it[k])
                            vals.add(-1L)
                        }
                    }
                    if (!cols.isEmpty()) builder.addRow(cols.toIntArray(), vals.toLongArray(), Relation.EQ, 0L)
                }
            }
            // Acceptance: one unit enters an accepting state at the last layer.
            if (acceptCols.isEmpty()) return // no accepting transition reachable — leave to propagation
            builder.addRow(acceptCols.toIntArray(), LongArray(acceptCols.size) { 1L }, Relation.EQ, 1L)
            // Channel: Σ_s s·y = seq[t] at each position.
            for (t in 0 until len) {
                val k = chanCols[t].size
                if (k == 0) return
                val cols = IntArray(k + 1)
                val vals = LongArray(k + 1)
                for (i in 0 until k) {
                    cols[i] = chanCols[t][i]
                    vals[i] = chanSym[t][i].toLong()
                }
                cols[k] = intColumn(seq[t])
                vals[k] = -1L
                builder.addRow(cols, vals, Relation.EQ, 0L)
            }
        }

        /**
         * Layered flow hull of one [Mdd] — the exact convex hull of the diagram's accepting paths. An
         * arc variable `y ∈ [0,1]` per forward-reachable transition record `(src, value, dst[, weight])`
         * at each layer (pinned to 0 when `value` left the live domain of `seq[layer]`). Rows: a source
         * row (one unit leaves `(0, initial)`), flow conservation at every interior `(layer, state)`, an
         * acceptance row (one unit enters an accepting state at the final layer), a value channel
         * `Σ value·y = seq[layer]` per layer, and — for a cost-MDD (stride 4) — a cost channel
         * `Σ weight·y = cost`, an **exact lower bound on the cost variable**. The flow polytope is
         * integral, so the LP optimum is exact. Forward reachability over the declared domains keeps the
         * layout stable and bounds the arc count ([MAX_MDD_ARCS]); above the cap, or when no accepting
         * path survives, the factor is skipped (only loosens).
         */
        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
        private fun buildMddHull(factor: Mdd) {
            val seq = factor.seq
            val n = seq.size
            val stride = factor.recordStride
            val trans = factor.transitions
            val starts = factor.layerStarts
            // Forward-reachable states per layer over the declared domains; bail if a layer empties.
            val reach = Array(n + 1) { IntHashSet() }
            reach[0].add(factor.initial)
            var arcCount = 0L
            for (layer in 0 until n) {
                val dom = problem.intDomains[seq[layer]]
                var p = starts[layer]
                val end = starts[layer + 1]
                while (p < end) {
                    if (trans[p] in reach[layer] && trans[p + 1] in dom) {
                        reach[layer + 1].add(trans[p + 2])
                        arcCount++
                    }
                    p += stride
                }
                if (reach[layer + 1].isEmpty()) return // no accepting path under declared domains
            }
            if (arcCount == 0L || arcCount > MAX_MDD_ARCS) return

            // States are layer-local dense ids in `[0, numStatesPerLayer(layer))`, so the per-layer
            // state→arc-columns maps are arrays indexed straight by the state id (#678).
            val nspl = factor.numStatesPerLayer
            val outCols = Array(n) { arrayOfNulls<IntArrayList>(nspl[it]) }
            val inCols = Array(n + 1) { arrayOfNulls<IntArrayList>(nspl[it]) }
            val chanCols = Array(n) { IntArrayList() }
            val chanVal = Array(n) { IntArrayList() }
            val accepting = IntHashSet(factor.accepting.size).apply { for (a in factor.accepting) add(a) }
            val acceptCols = IntArrayList()
            val costArcs = IntArrayList()
            val costWeight = IntArrayList()
            for (layer in 0 until n) {
                val declared = problem.intDomains[seq[layer]]
                val live = session.intDomain(seq[layer])
                var p = starts[layer]
                val end = starts[layer + 1]
                while (p < end) {
                    val src = trans[p]
                    val value = trans[p + 1]
                    val dst = trans[p + 2]
                    if (src in reach[layer] && value in declared) {
                        // The arc is present while its value stays in seq[layer]'s live domain.
                        val col = auxColumn(
                            0L,
                            if (live.contains(value)) 1L else 0L,
                            presence = intArrayOf(seq[layer], value),
                        )
                        (outCols[layer][src] ?: IntArrayList().also { outCols[layer][src] = it }).add(col)
                        (inCols[layer + 1][dst] ?: IntArrayList().also { inCols[layer + 1][dst] = it }).add(col)
                        chanCols[layer].add(col)
                        chanVal[layer].add(value)
                        if (layer == n - 1 && dst in accepting) acceptCols.add(col)
                        if (stride == 4) {
                            costArcs.add(col)
                            costWeight.add(trans[p + 3])
                        }
                    }
                    p += stride
                }
            }
            val src = outCols[0][factor.initial] ?: return
            if (src.isEmpty()) return
            builder.addRow(src.toIntArray(), LongArray(src.size) { 1L }, Relation.EQ, 1L)
            for (layer in 1 until n) {
                reach[layer].forEach { state ->
                    val cols = IntArrayList()
                    val vals = LongArrayList()
                    outCols[layer][state]?.let {
                        for (k in 0 until it.size) {
                            cols.add(it[k])
                            vals.add(1L)
                        }
                    }
                    inCols[layer][state]?.let {
                        for (k in 0 until it.size) {
                            cols.add(it[k])
                            vals.add(-1L)
                        }
                    }
                    if (!cols.isEmpty()) builder.addRow(cols.toIntArray(), vals.toLongArray(), Relation.EQ, 0L)
                }
            }
            if (acceptCols.isEmpty()) return
            builder.addRow(acceptCols.toIntArray(), LongArray(acceptCols.size) { 1L }, Relation.EQ, 1L)
            for (layer in 0 until n) {
                val k = chanCols[layer].size
                if (k == 0) return
                val cols = IntArray(k + 1)
                val vals = LongArray(k + 1)
                for (i in 0 until k) {
                    cols[i] = chanCols[layer][i]
                    vals[i] = chanVal[layer][i].toLong()
                }
                cols[k] = intColumn(seq[layer])
                vals[k] = -1L
                builder.addRow(cols, vals, Relation.EQ, 0L)
            }
            // Cost channel: Σ weight·y − cost = 0, an exact lower bound on the cost var.
            if (factor.cost >= 0 && !costArcs.isEmpty()) {
                val k = costArcs.size
                val cols = IntArray(k + 1)
                val vals = LongArray(k + 1)
                for (i in 0 until k) {
                    cols[i] = costArcs[i]
                    vals[i] = costWeight[i].toLong()
                }
                cols[k] = intColumn(factor.cost)
                vals[k] = -1L
                builder.addRow(cols, vals, Relation.EQ, 0L)
            }
        }

        /**
         * One-hot selector linearization of one [Element] `result = arr[idx − indexOffset]` over a
         * *constant* array — the exact convex hull. A selector column `y_p ∈ [0,1]` for each position
         * `p` whose index value `p + indexOffset` is in `idx`'s declared domain (layout stable across
         * nodes; pinned to 0 when that value left the live domain). Rows: `Σ_p y_p = 1`, index channel
         * `Σ_p (p + off)·y_p = idx`, and result channel `Σ_p arr[p]·y_p = result`. Arrays longer than
         * [MAX_ELEM] are skipped (the added columns would dominate). A *variable* array routes to
         * [buildVarElementHull] (the result channel is then bilinear and needs a big-M form).
         */
        private fun buildElementHull(factor: Element) {
            if (factor.arrIsVars) {
                buildVarElementHull(factor)
                return
            }
            val len = factor.arr.size
            if (len > MAX_ELEM) return
            val off = factor.indexOffset
            val declared = problem.intDomains[factor.idx]
            val live = session.intDomain(factor.idx)
            val selCols = IntArrayList()
            val positions = IntArrayList()
            for (p in 0 until len) {
                val idxVal = p + off
                if (idxVal !in declared) continue
                // The selector y_p is present while index value p+off stays in idx's live domain.
                selCols.add(auxColumn(0L, if (idxVal in live) 1L else 0L, presence = intArrayOf(factor.idx, idxVal)))
                positions.add(p)
            }
            val k = selCols.size
            if (k == 0) return
            builder.addRow(selCols.toIntArray(), LongArray(k) { 1L }, Relation.EQ, 1L)
            // Σ_p (p + off)·y_p − idx = 0.
            val idxCols = IntArray(k + 1)
            val idxVals = LongArray(k + 1)
            for (t in 0 until k) {
                idxCols[t] = selCols[t]
                idxVals[t] = (positions[t] + off).toLong()
            }
            idxCols[k] = intColumn(factor.idx)
            idxVals[k] = -1L
            builder.addRow(idxCols, idxVals, Relation.EQ, 0L)
            // Σ_p arr[p]·y_p − result = 0: the exact convex hull of the constant table.
            val resCols = IntArray(k + 1)
            val resVals = LongArray(k + 1)
            for (t in 0 until k) {
                resCols[t] = selCols[t]
                resVals[t] = factor.arr[positions[t]].toLong()
            }
            resCols[k] = intColumn(factor.result)
            resVals[k] = -1L
            builder.addRow(resCols, resVals, Relation.EQ, 0L)
        }

        /**
         * McCormick envelope of one [Product] `result = a·b` (#C4) — the four bound-derived inequalities
         * `(a−aL)(b−bL) ≥ 0`, `(a−aH)(b−bH) ≥ 0`, `(aH−a)(b−bL) ≥ 0`, `(a−aL)(bH−b) ≥ 0`, each expanded
         * to a linear row in `result, a, b`. They are valid at every point with `aL ≤ a ≤ aH`,
         * `bL ≤ b ≤ bH` and `result = a·b`, so the relaxation never cuts a feasible point. Bounds are the
         * **declared** domains (global). For `a = b` (a square) the `a` and `b` coefficients coalesce, so
         * the envelope becomes the secant/tangent relaxation of `result = a²`.
         */
        private fun buildProductMcCormick(factor: Product) {
            val aDom = problem.intDomains[factor.a]
            val bDom = problem.intDomains[factor.b]
            val aL = aDom.min.toLong()
            val aH = aDom.max.toLong()
            val bL = bDom.min.toLong()
            val bH = bDom.max.toLong()
            val aCol = intColumn(factor.a)
            val bCol = intColumn(factor.b)
            val resCol = intColumn(factor.result)
            // Each row is `result + ca·a + cb·b  rel  rhs`; coefficients coalesce when a and b coincide.
            mcCormickRow(resCol, aCol, bCol, -bL, -aL, Relation.GE, -(aL * bL)) // (a−aL)(b−bL) ≥ 0
            mcCormickRow(resCol, aCol, bCol, -bH, -aH, Relation.GE, -(aH * bH)) // (a−aH)(b−bH) ≥ 0
            mcCormickRow(resCol, aCol, bCol, -bL, -aH, Relation.LE, -(aH * bL)) // (aH−a)(b−bL) ≥ 0
            mcCormickRow(resCol, aCol, bCol, -bH, -aL, Relation.LE, -(aL * bH)) // (a−aL)(bH−b) ≥ 0
        }

        /**
         * Boolean Reformulation-Linearization-Technique cuts (#D4, CP-SAT's `_RLT`). For a 0/1 knapsack
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
                if (f !is Linear || f.op != LinearOp.LE) continue
                if (f.vars.size < 2 || f.vars.size > MAX_RLT_ROW) continue
                if (f.bound < 0 || f.coeffs.any { it <= 0 }) continue
                if (f.vars.any { problem.intDomains[it].min != 0 || problem.intDomains[it].max != 1 }) continue
                val b = f.bound.toLong()
                for (iIdx in f.vars.indices) {
                    if (rltColumns >= MAX_RLT_COLUMNS) break
                    val xi = f.vars[iIdx]
                    val xiCol = intColumn(xi)
                    val rltRow = HashMap<Int, Long>() // coalesces the wᵢᵢ = xᵢ term with the −b·xᵢ term
                    for (kIdx in f.vars.indices) {
                        val xk = f.vars[kIdx]
                        val a = f.coeffs[kIdx].toLong()
                        val wCol = if (kIdx == iIdx) {
                            xiCol // wᵢᵢ = xᵢ²= xᵢ
                        } else {
                            val xkCol = intColumn(xk)
                            val w = auxColumn(0L, 1L, presence = intArrayOf(xk, xi))
                            rltColumns++
                            builder.addRow(intArrayOf(w, xkCol), longArrayOf(1L, -1L), Relation.LE, 0L) // w ≤ xₖ
                            builder.addRow(intArrayOf(w, xiCol), longArrayOf(1L, -1L), Relation.LE, 0L) // w ≤ xᵢ
                            builder.addRow(intArrayOf(w, xkCol, xiCol), longArrayOf(1L, -1L, -1L), Relation.GE, -1L)
                            w
                        }
                        rltRow[wCol] = (rltRow[wCol] ?: 0L) + a
                    }
                    rltRow[xiCol] = (rltRow[xiCol] ?: 0L) - b // Σ aₖwₖᵢ ≤ b·xᵢ
                    val cols = rltRow.keys.toIntArray()
                    builder.addRow(cols, LongArray(cols.size) { rltRow.getValue(cols[it]) }, Relation.LE, 0L)
                }
            }
        }

        /** Emit `result + ca·a + cb·b rel rhs`, summing coefficients over shared columns (so a square
         *  `a·a` collapses the two operand terms onto one column). */
        private fun mcCormickRow(resCol: Int, aCol: Int, bCol: Int, ca: Long, cb: Long, rel: Relation, rhs: Long) {
            val coeff = HashMap<Int, Long>()
            coeff[resCol] = (coeff[resCol] ?: 0L) + 1L
            coeff[aCol] = (coeff[aCol] ?: 0L) + ca
            coeff[bCol] = (coeff[bCol] ?: 0L) + cb
            val cols = coeff.keys.toIntArray()
            val vals = LongArray(cols.size) { coeff.getValue(cols[it]) }
            builder.addRow(cols, vals, rel, rhs)
        }

        /**
         * Anderson tight face of `result = max(xs)` / `min(xs)` (#C3). The always-emitted envelope gives
         * `result ≥ xs[i]` (max) / `≤` (min); this adds the *tight* side: one-hot selectors `z_i` with
         * `Σ z_i = 1` and, per operand, a big-M row that forces `result = xs[i]` when `z_i = 1` and is
         * slack otherwise — so the LP also bounds `result ≤ max` / `result ≥ min`. `M_i = max(rHi, xHi) −
         * min(rLo, xLo)` from the **declared** domains bounds `|result − xs[i]|` globally, so the rows
         * hold at every integer solution (sound — never cuts a feasible point; verified by enumeration).
         */
        private fun buildLinMaxTightFace(factor: ArrayMinMax) {
            val n = factor.xs.size
            if (n == 0) return
            val sel = IntArray(n) { auxColumn(0L, 1L) } // free binaries z_i ∈ [0,1]
            builder.addRow(sel, LongArray(n) { 1L }, Relation.EQ, 1L) // Σ z_i = 1
            val resCol = intColumn(factor.result)
            val rDom = problem.intDomains[factor.result]
            for (i in 0 until n) {
                val x = factor.xs[i]
                val xDom = problem.intDomains[x]
                val m = maxOf(rDom.max, xDom.max).toLong() - minOf(rDom.min, xDom.min).toLong()
                if (m < 0L) continue
                val xCol = intColumn(x)
                val z = sel[i]
                if (factor.max) {
                    // result ≤ xs[i] + M(1 − z_i)  ⇒  result − xs[i] + M·z_i ≤ M.
                    builder.addRow(intArrayOf(resCol, xCol, z), longArrayOf(1L, -1L, m), Relation.LE, m)
                } else {
                    // result ≥ xs[i] − M(1 − z_i)  ⇒  xs[i] − result + M·z_i ≤ M.
                    builder.addRow(intArrayOf(xCol, resCol, z), longArrayOf(1L, -1L, m), Relation.LE, m)
                }
            }
        }

        /**
         * Big-M linearization of one [Element] `result = arr[idx − indexOffset]` over a **variable**
         * array (#C5). The same one-hot selectors `y_p` and index channel `Σ_p (p + off)·y_p = idx` as
         * the constant case, but the result channel is bilinear (`arr[p]` is a variable), so it is
         * relaxed with two big-M rows per position that force `result = arr[p]` when `y_p = 1` and are
         * slack when `y_p = 0`:
         *   `result − arr[p] + M_p·y_p ≤ M_p`   and   `arr[p] − result + M_p·y_p ≤ M_p`,
         * with `M_p = max(rHi, aHi) − min(rLo, aLo)` from the **declared** domains — a global bound on
         * `|result − arr[p]|`, so the rows hold at every integer solution (sound: never cuts a feasible
         * point; verified by brute enumeration in the tests). Arrays longer than [MAX_ELEM] are skipped.
         */
        private fun buildVarElementHull(factor: Element) {
            val len = factor.arr.size
            if (len > MAX_ELEM) return
            val off = factor.indexOffset
            val declared = problem.intDomains[factor.idx]
            val live = session.intDomain(factor.idx)
            val selCols = IntArrayList()
            val positions = IntArrayList()
            for (p in 0 until len) {
                val idxVal = p + off
                if (idxVal !in declared) continue
                selCols.add(auxColumn(0L, if (idxVal in live) 1L else 0L, presence = intArrayOf(factor.idx, idxVal)))
                positions.add(p)
            }
            val k = selCols.size
            if (k == 0) return
            builder.addRow(selCols.toIntArray(), LongArray(k) { 1L }, Relation.EQ, 1L)
            // Index channel Σ_p (p + off)·y_p − idx = 0.
            val idxCols = IntArray(k + 1)
            val idxVals = LongArray(k + 1)
            for (t in 0 until k) {
                idxCols[t] = selCols[t]
                idxVals[t] = (positions[t] + off).toLong()
            }
            idxCols[k] = intColumn(factor.idx)
            idxVals[k] = -1L
            builder.addRow(idxCols, idxVals, Relation.EQ, 0L)
            // Per position: two big-M rows tying result to arr[p] when its selector is on.
            val resCol = intColumn(factor.result)
            val rDom = problem.intDomains[factor.result]
            for (t in 0 until k) {
                val arrVar = factor.arr[positions[t]]
                val aDom = problem.intDomains[arrVar]
                val m = maxOf(rDom.max, aDom.max).toLong() - minOf(rDom.min, aDom.min).toLong()
                if (m < 0L) continue // empty domain — leave that position unconstrained (sound)
                val arrCol = intColumn(arrVar)
                val y = selCols[t]
                // result − arr[p] + M·y_p ≤ M  ⇒  result ≤ arr[p] when y_p = 1, slack otherwise.
                builder.addRow(intArrayOf(resCol, arrCol, y), longArrayOf(1L, -1L, m), Relation.LE, m)
                // arr[p] − result + M·y_p ≤ M  ⇒  arr[p] ≤ result when y_p = 1, slack otherwise.
                builder.addRow(intArrayOf(arrCol, resCol, y), longArrayOf(1L, -1L, m), Relation.LE, m)
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
                val spec = rel.rowSpec(plan, session)
                addIntRow(
                    intArrayOf(plan.makespanVar),
                    intArrayOf(plan.capacity),
                    auxCol = -1,
                    auxCoeff = 0L,
                    rel = Relation.GE,
                    rhs = spec.rhs,
                    global = spec.global,
                    premises = spec.premises,
                )
            }
        }

        /**
         * Time-indexed `x_{i,t}` relaxation of one scheduling [view] over the bounded horizon
         * `[T0, T1)` (#453). For each task a binary `x_{i,t} ∈ [0,1]` per declared-feasible start `t`
         * (pinned to 0 when `t` left the live start domain — layout stable across nodes for warm
         * starts), with `Σ_t x_{i,t} = 1` (starts once), the start channel `Σ_t t·x_{i,t} = startᵢ`
         * (ties to the integer column), and per-time-point resource rows
         * `Σ_i Σ_{t: t≤tt<t+durᵢ} resᵢ·x_{i,t} ≤ capacity`. Every integer schedule satisfies all three,
         * so the rows are globally valid; the resource ceiling uses the declared **max** capacity and
         * the **min** demand, so it is a sound relaxation. Columns are O(n·H) — hard-gated on
         * [MAX_TI_HORIZON] and [MAX_TI_COLS]; above either the model is skipped (only loosens).
         *
         * ## No separate makespan row (#472)
         * There is deliberately no disaggregated makespan row here. The makespan links through the
         * start channel and the model's own `M ≥ startᵢ + durᵢ` `Linear`s, so the LP makespan is
         * `Σ_t t·x_{i,t} + durᵢ` — the *expected* completion under a fractional `x`. The disaggregated
         * `M ≥ (t+durᵢ)·x_{i,t}` rows the issue weighed (and the completion-indicator step variant)
         * cannot tighten that: any makespan lower bound linear in one task's `x` is dominated by the
         * expected-completion value, which `M ≥ startᵢ + durᵢ` already attains exactly (verified — the
         * disaggregated rows raised the bound on 0 of 2623 structured instances). The only makespan
         * lever is the *cross-task* resource coupling above, which already lifts the bound past the
         * energetic windowed row (#430) on multi-capacity profiles. So the model is not redundant with
         * #430 for makespan, but the disaggregated strengthening would be; #472 closed as such.
         */
        private fun buildCumulativeTimeIndexed(view: SchedulingView) {
            val n = view.starts.size
            val est = IntArray(n) { problem.intDomains[view.starts[it]].min }
            val lst = IntArray(n) { problem.intDomains[view.starts[it]].max }
            var t0 = Int.MAX_VALUE
            var t1 = Int.MIN_VALUE
            var cols = 0L
            for (i in 0 until n) {
                if (lst[i] < est[i]) return // empty declared start domain — leave to propagation
                if (est[i] < t0) t0 = est[i]
                val end = lst[i] + view.durations[i]
                if (end > t1) t1 = end
                cols += (lst[i] - est[i] + 1).toLong()
            }
            val horizon = t1 - t0
            if (horizon <= 0 || horizon > MAX_TI_HORIZON || cols > MAX_TI_COLS) return

            // Per-task start-time columns, indexed by (t - est_i); assignment + start channel rows.
            val taskCols = Array(n) { IntArray(lst[it] - est[it] + 1) }
            for (i in 0 until n) {
                val live = session.intDomain(view.starts[i])
                val assignCols = IntArray(taskCols[i].size)
                val chanCols = IntArray(taskCols[i].size + 1)
                val chanVals = LongArray(taskCols[i].size + 1)
                for (k in taskCols[i].indices) {
                    val t = est[i] + k
                    val col = auxColumn(0L, if (live.contains(t)) 1L else 0L)
                    taskCols[i][k] = col
                    assignCols[k] = col
                    chanCols[k] = col
                    chanVals[k] = t.toLong()
                }
                builder.addRow(assignCols, LongArray(assignCols.size) { 1L }, Relation.EQ, 1L)
                chanCols[taskCols[i].size] = intColumn(view.starts[i])
                chanVals[taskCols[i].size] = -1L
                builder.addRow(chanCols, chanVals, Relation.EQ, 0L) // Σ t·x − startᵢ = 0
            }

            // Per-time-point resource rows: Σ_i Σ_{t ≤ tt < t+durᵢ} resᵢ·x_{i,t} ≤ capacity.
            val rowCols = IntArrayList()
            val rowVals = LongArrayList()
            for (tt in t0 until t1) {
                rowCols.clear()
                rowVals.clear()
                for (i in 0 until n) {
                    val d = view.durations[i]
                    val r = view.resources[i]
                    if (d <= 0 || r <= 0) continue
                    val lo = maxOf(est[i], tt - d + 1)
                    val hi = minOf(lst[i], tt)
                    for (t in lo..hi) {
                        rowCols.add(taskCols[i][t - est[i]])
                        rowVals.add(r.toLong())
                    }
                }
                if (!rowCols.isEmpty()) {
                    builder.addRow(rowCols.toIntArray(), rowVals.toLongArray(), Relation.LE, view.capacity.toLong())
                }
            }
        }

        private fun linearRow(op: LinearOp, vars: IntArray, coeffs: IntArray, bound: Long) {
            val rel = when (op) {
                LinearOp.LE -> Relation.LE
                LinearOp.GE -> Relation.GE
                LinearOp.EQ -> Relation.EQ
                LinearOp.NE -> return // not LP-relaxable
            }
            addIntRow(vars, coeffs, auxCol = -1, auxCoeff = 0L, rel = rel, rhs = bound)
        }
    }
}
