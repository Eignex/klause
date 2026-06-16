package com.eignex.klause.solver.lp

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

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
)

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
    /** When true, materialize columns for variables of cut-eligible globals (AllDifferent) so a
     *  [CutSeparator] can write cuts over them, even when no other factor references the variable. */
    private val generateCuts: Boolean = false,
    /** When true, build the arc-indicator relaxation of each Circuit (degree + channelling rows) so
     *  [CircuitSeparator] can separate subtour-elimination cuts. Adds O(n²) columns, so it is gated. */
    private val circuitArcs: Boolean = false,
    /** When true, linearize each constant-array Element with a one-hot selector model (its exact
     *  convex hull). Adds O(len) columns, so it is gated; variable arrays are skipped. */
    private val elementHull: Boolean = false,
    /** When true, linearize each Table with one selector column per allowed tuple — its exact convex
     *  hull. Adds O(numTuples) columns, so it is gated. */
    private val tableHull: Boolean = false,
    /** When true, emit the energetic makespan lower-bound row for each Cumulative / Disjunctive whose
     *  makespan variable can be verified (see [CumulativeRelaxation]). One row per plan. */
    private val cumulative: Boolean = false,
    /** When true, emit the time-indexed `x_{i,t}` relaxation of each Cumulative / Disjunctive over a
     *  bounded horizon (#453): assignment + start channel + per-time resource rows. Adds O(n·H)
     *  columns, so it is hard-gated on the horizon and total cell count. */
    private val cumulativeTimeIndexed: Boolean = false,
    /** When true, linearize each NValue with a one-hot value model (per-value "used" indicators) so
     *  the distinct-count target gets an LP bound. Adds O(Σ|domain|) columns, so it is gated. */
    private val nValueHull: Boolean = false,
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
) {
    /** Verified makespan plans for the scheduling globals; null when disabled or none applicable. */
    private val cumulativeRelaxation: CumulativeRelaxation? =
        if (cumulative) CumulativeRelaxation(problem).takeIf { it.applicable } else null

    /**
     * #564 dense-row cache eligibility. The per-node rebuild is dominated by densifying the `m × n`
     * coefficient matrix, not by the factor walk; for the base relaxation every row's coefficients
     * are bound-invariant except the live-big-M reified rows, so the dense rows can be shared across
     * nodes. The gated hull / circuit / time-indexed features build auxiliary columns whose layout or
     * count would have to be re-derived to share safely, so the cache is disabled when any is on and
     * those builds take the (unchanged) full densify. Cumulative *is* cacheable: its row coefficients
     * are constant (capacity on the makespan column) and only the right-hand side varies, which the
     * re-walk recomputes anyway.
     */
    private val cacheable: Boolean =
        !circuitArcs && !elementHull && !tableHull && !nValueHull && !cumulativeTimeIndexed

    /** Shared pre-densified coefficient rows for the bound-invariant base rows (#564). A null entry is
     *  a live-big-M reified base row, re-densified each node. Populated on the first cacheable build;
     *  [cacheVarCount] / [cacheBaseRows] guard against a layout change (then it is rebuilt). */
    private var denseCache: Array<LongArray?>? = null
    private var cacheVarCount: Int = -1
    private var cacheBaseRows: Int = -1

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

    /** Per-hull dense-tableau caps. Internal so [com.eignex.klause.solver.backtrack.LpAutoConfig]'s
     *  size guard (#484) estimates each enabled hull against the *same* thresholds the builders skip
     *  at — one source of truth, no drift. */
    internal companion object {
        /** Above this candidate-arc count the circuit arc model is skipped — a defensive bound on
         *  the dense-tableau cost. Gating on arc count (LP columns) rather than node count lets
         *  large but sparse routing graphs through (#431); #429 may bench this threshold. */
        const val MAX_CIRCUIT_ARCS: Int = 1024

        /** Above this array length the O(len)-column Element selector model is skipped. */
        const val MAX_ELEM: Int = 256

        /** Above this tuple count the O(numTuples)-column Table hull is skipped. */
        const val MAX_TUPLES: Int = 1024

        /** Above this horizon (latest deadline − earliest start) the time-indexed model is skipped. */
        const val MAX_TI_HORIZON: Int = 512

        /** Above this many `x_{i,t}` columns one time-indexed Cumulative is skipped (the O(n·H) blow-up). */
        const val MAX_TI_COLS: Int = 4096

        /** Above this total selector count (Σ over `xs` of the declared-domain size) the NValue
         *  one-hot value hull is skipped. */
        const val MAX_NVALUE_CELLS: Int = 1024
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
        private val circuitModels = ArrayList<CircuitArcModel>()

        /** Base-row indices emitted by live-big-M reified rows (#564); their coefficients vary per
         *  node, so they are never shared from the dense cache. */
        private val reifiedRowIdx = IntArrayList()

        /** Auxiliary LP column with no backing CP variable (tag/colVarId = -1) — e.g. a circuit arc. */
        private fun auxColumn(lo: Long, hi: Long): Int {
            val c = builder.addVar(lo, hi, cost = 0L, tag = -1)
            colVarId.add(-1)
            colIsBool.add(0)
            return c
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
         * those atoms instead of being withheld (see [LpExplanation]).
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
                // Cut generation needs columns for the globals' variables even when nothing else
                // references them, so a separator has something to write the cut over.
                if (generateCuts) {
                    // The all-different family (AllDifferent, SymmetricAllDifferent, both Inverse sides)
                    // feeds the Hall-sum and assignment cuts; closed GlobalCardinality feeds the GCC cut.
                    for (group in allDifferentGroups(problem)) for (v in group) intColumn(v)
                    for (factor in problem.factors) {
                        if (factor is GlobalCardinality && factor.closed && factor.presents.isEmpty()) {
                            for (v in factor.xs) intColumn(v)
                        }
                    }
                }
                if (circuitArcs) {
                    for (factor in problem.factors) if (factor is Circuit) buildCircuitArcs(factor)
                }
                if (elementHull) {
                    for (factor in problem.factors) if (factor is Element) buildElementHull(factor)
                }
                if (tableHull) {
                    for (factor in problem.factors) if (factor is Table) buildTableHull(factor)
                }
                if (nValueHull) {
                    for (factor in problem.factors) if (factor is NValue) buildNValueHull(factor)
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

                    is ReifiedLinear -> {
                        val start = builder.rowCount
                        reifiedRows(factor)
                        for (r in start until builder.rowCount) reifiedRowIdx.add(r)
                    }

                    // Reified Boolean sums use declared-[0,1] big-M, so their rows are global /
                    // bound-invariant (cacheable) — not added to reifiedRowIdx.
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
                    }

                    is PseudoBoolean -> {
                        val rel = when (factor.op) {
                            PbOp.LE -> Relation.LE
                            PbOp.GE -> Relation.GE
                            PbOp.EQ -> Relation.EQ
                        }
                        addBoolRow(factor.literals, factor.weights, rel, factor.bound.toLong())
                    }

                    else -> Unit // hard globals and unrecognized factors: handled elsewhere or skipped
                }
            }

            // The base relaxation is complete; cuts append after it (and are never cached, since they
            // are per-node and locally separated).
            val baseRows = builder.rowCount

            // Separator-produced cuts, over already-created columns. A cut referencing an absent
            // column is dropped (defensive — separators should only emit over existing columns).
            for (cut in extraCuts) {
                if (cut.cols.all { it in 0 until builder.varCount }) {
                    builder.addRow(cut.cols, cut.coeffs, cut.rel, cut.rhs, cut.global)
                }
            }

            // #564: reuse the cached dense base rows when the layout matches; else densify fully and
            // (re)capture the cache. Sharing is sound because the re-walk above produced the live
            // right-hand sides / global flags for every row — only the bound-invariant coefficient
            // arrays are aliased.
            val shared = denseCache?.takeIf {
                cacheable && cacheVarCount == builder.varCount &&
                    cacheBaseRows == baseRows
            }
            val model = builder.buildShared(Sense.MINIMIZE, shared)
            if (shared == null && cacheable) captureDenseCache(model, baseRows)
            val kinds = BooleanArray(colIsBool.size) { colIsBool[it] == 1 }
            return LpRelaxation(
                model = model,
                colVarId = IntArray(colVarId.size) { colVarId[it] },
                colIsBool = kinds,
                objectiveConstant = objective?.constant ?: 0L,
                intColOf = intCol.copyOf(),
                boolColOf = boolCol.copyOf(),
                circuitArcs = circuitModels,
            )
        }

        /** Capture the freshly densified base rows for reuse across nodes (#564). Reified base rows
         *  are left null (their big-M coefficients vary); every other base row's array is aliased —
         *  it is bound-invariant and the simplex treats [LpModel.a] as read-only. */
        private fun captureDenseCache(model: LpModel, baseRows: Int) {
            val reified = BooleanArray(baseRows)
            for (idx in 0 until reifiedRowIdx.size) {
                val r = reifiedRowIdx[idx]
                if (r < baseRows) reified[r] = true
            }
            denseCache = Array(baseRows) { i -> if (reified[i]) null else model.a[i] }
            cacheVarCount = builder.varCount
            cacheBaseRows = baseRows
        }

        /**
         * Arc-indicator relaxation of one [Circuit] over `succ[0..n)`: a column `y_ij ∈ [0,1]` per
         * candidate arc (`j` in the declared domain of `succ[i]`, `j ≠ i` — circuit forbids self
         * loops), pinned to 0 when `j` left the live domain. Rows: out-degree `Σ_j y_ij = 1`,
         * in-degree `Σ_i y_ij = 1`, and channelling `Σ_j j·y_ij = succ[i]` tying arcs to the integer
         * column. Integer solutions are then permutations; [CircuitSeparator] removes the subtours.
         * The column *layout* uses the declared domain so it is identical across nodes (warm-start
         * safe). A circuit whose candidate-arc count exceeds [MAX_CIRCUIT_ARCS] is skipped (the dense
         * LP tableau would dominate); gating on arc count rather than n lets large sparse graphs
         * through (#431). Arcs are recorded sparsely for the [CircuitSeparator] — no O(n²) matrix.
         */
        private fun buildCircuitArcs(factor: Circuit) {
            val succ = factor.succ
            val n = succ.size
            if (n < 2) return
            // Gate on the candidate-arc total — the LP column count — not on n, so large sparse
            // graphs (small per-node successor domains) are not skipped by a blunt node cap.
            var arcCount = 0
            for (i in 0 until n) {
                problem.intDomains[succ[i]].forEach { j -> if (j != i && j in 0 until n) arcCount++ }
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
                    if (j == i || j < 0 || j >= n) return@forEach
                    val present = live.contains(j)
                    val col = auxColumn(0L, if (present) 1L else 0L)
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
            circuitModels.add(CircuitArcModel(n, tails.toIntArray(), heads.toIntArray(), cols.toIntArray()))
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
                selCols.add(auxColumn(0L, if (liveFeasible) 1L else 0L))
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
            val yByValue = HashMap<Int, Int>()
            fun yOf(v: Int): Int = yByValue.getOrPut(v) { auxColumn(0L, 1L).also { yCols.add(it) } }
            for (x in xs) {
                val declared = problem.intDomains[x]
                val live = session.intDomain(x)
                val sel = IntArrayList()
                val selVal = IntArrayList()
                declared.forEach { v ->
                    val z = auxColumn(0L, if (live.contains(v)) 1L else 0L)
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
         * One-hot selector linearization of one [Element] `result = arr[idx − indexOffset]` over a
         * *constant* array — the exact convex hull. A selector column `y_p ∈ [0,1]` for each position
         * `p` whose index value `p + indexOffset` is in `idx`'s declared domain (layout stable across
         * nodes; pinned to 0 when that value left the live domain). Rows: `Σ_p y_p = 1`, index channel
         * `Σ_p (p + off)·y_p = idx`, and result channel `Σ_p arr[p]·y_p = result`. Arrays longer than
         * [MAX_ELEM] are skipped (the added columns would dominate). Variable arrays are not handled
         * here — their channel is bilinear, so a sound big-M form is a separate follow-up.
         */
        private fun buildElementHull(factor: Element) {
            if (factor.arrIsVars) return
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
                selCols.add(auxColumn(0L, if (idxVal in live) 1L else 0L))
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
