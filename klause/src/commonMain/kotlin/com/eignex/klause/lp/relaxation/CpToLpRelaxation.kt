package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.circuit.Subcircuit
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.lp.Contribution
import com.eignex.klause.lp.LpBuilder
import com.eignex.klause.lp.LpModel
import com.eignex.klause.lp.LpRowPremises
import com.eignex.klause.lp.Relation
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.Sense
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.cut.CircuitArcModel
import com.eignex.klause.lp.cut.CircuitSeparator
import com.eignex.klause.lp.cut.Cut
import com.eignex.klause.lp.subExact
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
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
    val colReq: Array<IntArray?> = arrayOfNulls(model.n),
    /** Per structural column, the upper bound an auxiliary column takes when present (see [colReq]);
     *  unused for CP-var columns. */
    val colPresentUpper: LongArray = LongArray(model.n),
    /** Ids of the factors that emitted at least one [Contribution.HULL] row, in factor order — the
     *  candidates the root pruner (`LpEngine.pruneIneffectiveHulls`) probes for individual removal. */
    val hullFactorIds: IntArray = EmptyIntArray,
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
                    if (!session.intDomain(req[k]).contains(req[k + 1].toLong())) {
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

    /** Per-hull dense-tableau caps. Internal so [com.eignex.klause.backtrack.lp.LpAutoConfig]'s
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

    /** Build the relaxation, optionally appending separator-produced [extraCuts] as extra rows. */
    fun build(session: PropagationSession, extraCuts: List<Cut> = emptyList()): LpRelaxation =
        Assembler(session).assemble(extraCuts)

    private fun intCost(i: Int): Long = objective?.intCoefficients?.getOrElse(i) { 0L } ?: 0L

    private fun boolCost(b: Int): Long = objective?.boolWeights?.getOrElse(b) { 0L } ?: 0L

    /** Per-build mutable state: the builder, the column maps, and the row emitters. Implements
     *  [RelaxationBuilder] so a factor's [com.eignex.klause.lp.Linearizer] can emit into it. */
    private inner class Assembler(private val session: PropagationSession) : RelaxationBuilder {
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

        /** Whether the factor currently being linearized may contribute HULL rows: its convex-hull
         *  family flag is on and we are not in objective-cone mode (where the column-heavy hulls are
         *  forced off). Set per factor before [Factor.asLinearizer]; consulted by the row emitters so a
         *  disabled family contributes only its CORE rows. CORE rows ignore it. */
        private var currentHullEnabled = true

        /** The factor whose Linearizer is currently emitting, for attributing HULL rows to it. */
        private var currentFactorId = -1

        /** Factors that emitted at least one HULL row, in factor order (the root pruner's candidates). */
        private val hullFactorIds = LinkedHashSet<Int>()

        /** The plan flag gating [factor]'s HULL rows (its convex-hull family), false in cone mode.
         *  A factor with no HULL contribution is unaffected — the flag only suppresses HULL rows. */
        private fun hullEnabledFor(factor: Factor): Boolean = !objectiveCone && when (factor) {
            is Element -> elementHull
            is Table -> tableHull
            is NValue -> nValueHull
            is Regular -> regularHull
            is Mdd -> mddHull
            is GlobalCardinality -> gccCountHull
            is ArrayMinMax -> linMaxTightFace
            is Product -> productMcCormick
            else -> true
        }

        /** Auxiliary LP column with no backing CP variable (tag/colVarId = -1) — e.g. a circuit arc.
         *  [presence] names the `(intVar, value)` memberships that must all hold for the column to be
         *  present (upper [hi]); when given, the column can be re-bound on the persistent path. */
        override fun auxColumn(lo: Long, hi: Long, presence: IntArray?): Int {
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
                val live = session.intDomain(succ[i])
                val outCols = IntArrayList()
                val chanCols = IntArrayList()
                val chanCoef = IntArrayList()
                problem.intDomains[succ[i]].forEach { j ->
                    if ((!selfLoops && j == i.toLong()) || j < 0 || j >= n) return@forEach
                    val jn = j.toInt() // validated node index in [0, n)
                    val present = live.contains(j)
                    // The arc is present exactly while head j stays in succ[i]'s live domain — the single
                    // membership that lets the persistent relaxation re-bind this column (#43).
                    val col = auxColumn(0L, if (present) 1L else 0L, presence = intArrayOf(succ[i], jn))
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

        /** Column for integer variable `i`, created on first use with its live domain bounds. */
        override fun intColumn(i: Int): Int {
            var c = intCol[i]
            if (c == -1) {
                val dom = session.intDomain(i)
                c = builder.addVar(dom.min, dom.max, intCost(i), tag = i)
                intCol[i] = c
                colVarId.add(i)
                colIsBool.add(0)
                colReq.add(null)
                colPresentUpper.add(0L)
            }
            return c
        }

        /** Column for Boolean variable `b`; bounds collapse to a point if it is pinned this node. */
        override fun boolColumn(b: Int): Int {
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

        fun assemble(extraCuts: List<Cut>): LpRelaxation {
            // Materialize objective-only variables first so the relaxed objective is complete.
            objective?.let { obj ->
                for (i in obj.intCoefficients.indices) if (obj.intCoefficients[i] != 0L) intColumn(i)
                for (b in obj.boolWeights.indices) if (obj.boolWeights[b] != 0L) boolColumn(b)
            }
            // Cone mode is the minimal linear+Boolean objective-cone probe: the column-heavy hull /
            // circuit / cut / cumulative features are all forced off (see [objectiveCone]). The
            // per-factor convex hulls are emitted by each factor's Linearizer in the main loop below
            // (gated by [currentHullEnabled]); only the non-per-factor relaxations live here — circuit
            // arcs feed the subtour separator, and the cumulative rows span a scheduling view.
            if (!objectiveCone) {
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
            for ((factorId, factor) in problem.factors.withIndex()) {
                // #571: in cone mode emit only factors connected to the objective; this also drops
                // every big-M ReifiedLinear row (they never extend the cone — see [coneTouches]).
                if (coneL != null && !coneTouches(factor, coneL.first, coneL.second)) continue
                currentFactorId = factorId
                currentHullEnabled = factorId !in suppressedHullFactors && hullEnabledFor(factor)
                // Each factor's own Linearizer emits its rows; factors with no linear relaxation
                // (hard globals, cut-only or scheduling-view factors) return NoLinearizer and contribute
                // nothing here — they are handled by the separators and the blocks above.
                factor.asLinearizer().linearize(this, factorId)
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
            )
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
                if (f !is Linear || f.op != LinearOp.LE) continue
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
                            val w = auxColumn(0L, 1L, presence = intArrayOf(xk, xi))
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
                val spec = rel.rowSpec(plan, session)
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
            coeffs: IntArray,
            bound: Long,
            contribution: Contribution,
        ) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            addIntRow(intVars, coeffs, auxCol = -1, auxCoeff = 0L, rel = rel, rhs = bound)
        }

        override fun boolRow(
            literals: IntArray,
            weights: IntArray?,
            op: LinearOp,
            bound: Long,
            contribution: Contribution,
        ) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            addBoolRow(literals, weights, rel, bound)
        }

        override fun hullEnabled(): Boolean = currentHullEnabled

        override fun liveDomain(intVar: Int): IntDomain = session.intDomain(intVar)

        override fun declaredDomain(intVar: Int): IntDomain = problem.intDomains[intVar]

        override fun row(columns: IntArray, coeffs: LongArray, op: LinearOp, rhs: Long, contribution: Contribution) {
            if (skipRow(contribution)) return
            val rel = relationOf(op) ?: return
            builder.addRow(columns, coeffs, rel, rhs)
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
                val dom = session.intDomain(v)
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
