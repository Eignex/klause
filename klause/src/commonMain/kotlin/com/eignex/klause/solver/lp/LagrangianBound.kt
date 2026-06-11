package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import kotlin.math.ceil

/**
 * Subgradient Lagrangian bound for a structured AllDifferent global. For
 * `minimize Σ cᵢ·xᵢ s.t. AllDifferent(V) ∧ (linear linking constraints over V)`, dualize the linking
 * constraints with multipliers λ and keep AllDifferent as the subproblem — which is an exact
 * min-cost assignment ([MinCostAssignment]). For any valid-sign λ,
 * `L(λ) = min_x [obj + Σ_r λ_r(a_r·x − b_r)]` over AllDifferent(V) is a lower bound on the optimum
 * (every original-feasible point makes the dualized terms ≤ 0), so maximizing it over λ tightens the
 * bound. Constraints not over V, and all other factors, are dropped — a relaxation only loosens the
 * bound, never makes it unsound.
 *
 * ## Exactness
 * Multipliers are kept as integers `p_r` over a fixed denominator [Q] (`λ_r = p_r / Q`). The adjusted
 * objective coefficient of variable `i` is then the integer `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri`, so the
 * assignment is solved in exact [Long] arithmetic and `L(λ) = (M − Σ_r p_r·b_r + Q·rest) / Q` is an
 * exact rational. The subgradient *step* uses floating math to pick the next λ, but that only chooses
 * which λ to try — every evaluated `L(λ)` is exact, so the reported bound is always valid. Overflow
 * (large coefficients) makes the node's bound unavailable, never wrong.
 */
internal class LagrangianBound(problem: Problem, objective: LinearObjective?) {
    /** Variables of the chosen AllDifferent, or empty when no eligible global exists. */
    private val vars: IntArray
    private val inV: BooleanArray

    /** Linking constraints (Linear factors entirely over [vars]); dualized with one multiplier each. */
    private val linkVars: Array<IntArray>
    private val linkCoeffs: Array<LongArray>
    private val linkRhs: LongArray
    private val linkSign: IntArray // +1: λ≥0 (LE), -1: λ≤0 (GE), 0: free (EQ)

    private val intCoef: LongArray
    private val boolWeight: LongArray
    private val objConstant: Long

    val applicable: Boolean

    init {
        val numInt = problem.numIntVars
        val chosen = if (objective == null) {
            null
        } else {
            problem.factors.filterIsInstance<AllDifferent>().firstOrNull { it.vars.size in 2..MAX_VARS }
        }
        if (chosen == null) {
            vars = IntArray(0)
            inV = BooleanArray(numInt)
            linkVars = emptyArray()
            linkCoeffs = emptyArray()
            linkRhs = LongArray(0)
            linkSign = IntArray(0)
            intCoef = LongArray(0)
            boolWeight = LongArray(0)
            objConstant = 0L
            applicable = false
        } else {
            vars = chosen.vars.copyOf()
            inV = BooleanArray(numInt)
            for (v in vars) inV[v] = true
            val lv = ArrayList<IntArray>()
            val lc = ArrayList<LongArray>()
            val lr = LongArrayList()
            val ls = IntArrayList()
            for (f in problem.factors) {
                if (f !is Linear) continue
                val sign = when (f.op) {
                    LinearOp.LE -> 1
                    LinearOp.GE -> -1
                    LinearOp.EQ -> 0
                    LinearOp.NE -> continue // not a linear relaxation
                }
                if (f.vars.any { !inV[it] }) continue // only constraints entirely over V are dualized
                lv.add(f.vars.copyOf())
                lc.add(LongArray(f.coeffs.size) { f.coeffs[it].toLong() })
                lr.add(f.bound.toLong())
                ls.add(sign)
            }
            linkVars = lv.toTypedArray()
            linkCoeffs = lc.toTypedArray()
            linkRhs = LongArray(lr.size) { lr[it] }
            linkSign = IntArray(ls.size) { ls[it] }
            val obj = objective ?: error("AllDifferent chosen only when objective is non-null")
            intCoef = LongArray(numInt) { obj.intCoefficients.getOrElse(it) { 0L } }
            boolWeight = LongArray(problem.numBoolVars) { obj.boolWeights.getOrElse(it) { 0L } }
            objConstant = obj.constant
            applicable = true
        }
    }

    /** Number of dualized linking constraints; the multiplier vector has this length. */
    val multiplierCount: Int get() = linkVars.size

    /** Outcome of a node bound: prune (subtree cannot beat the incumbent / is infeasible) plus the
     *  best bound found and the multipliers to carry to child nodes. */
    class Result(val prune: Boolean, val boundNumerator: Long, val denominator: Long, val multipliers: LongArray)

    /**
     * Compute the Lagrangian bound at the current node. [incumbent] is the best objective to beat
     * (`+∞` if none — then the subgradient is skipped and only the base bound / infeasibility is
     * reported). [startMultipliers] warm-starts λ from a parent node. Returns null when the bound is
     * unavailable here (no eligible global, value set too large, or arithmetic overflow).
     */
    fun computeBound(
        session: PropagationSession,
        incumbent: Double,
        startMultipliers: LongArray,
        iterations: Int,
    ): Result? {
        if (!applicable) return null

        // Value set = union of the live domains of V; bail if it is too large to assign over.
        val valueIndex = HashMap<Int, Int>()
        val valueList = IntArrayList()
        for (v in vars) {
            val dom = session.intDomain(v)
            dom.forEach { value ->
                if (value !in valueIndex) {
                    valueIndex[value] = valueList.size
                    valueList.add(value)
                }
            }
        }
        if (valueList.size > MAX_VALUES || valueList.size < vars.size) {
            // Too large to assign, or fewer distinct values than variables (AllDifferent infeasible).
            return if (valueList.size < vars.size) {
                Result(prune = true, boundNumerator = 0L, denominator = Q, multipliers = startMultipliers)
            } else {
                null
            }
        }

        val p = LongArray(multiplierCount) { startMultipliers.getOrElse(it) { 0L } }
        var bestNum = Long.MIN_VALUE
        // Previous ascent direction, for deflected (conjugate) subgradient stabilization.
        val prevDir = DoubleArray(multiplierCount)

        try {
            // Inside the try: a checked-arithmetic overflow here means "bound unavailable", the
            // same sound skip as everywhere else — never a silently wrapped (wrong) bound.
            val rest = trivialRest(session)
            val steps = if (incumbent.isFinite()) iterations else 1
            repeat(steps) {
                val assignment = solveAssignment(session, valueIndex, valueList, p)
                if (!assignment.feasible) return Result(true, 0L, Q, p) // infeasible ⇒ node infeasible
                // numerator = M − Σ p_r·b_r + Q·rest, with L = numerator / Q.
                var num = assignment.cost
                for (r in 0 until multiplierCount) num = subExact(num, mulExact(p[r], linkRhs[r]))
                num = addExact(num, mulExact(Q, rest))
                if (num > bestNum) bestNum = num
                if (ceilDivLocal(num, Q) >= incumbentCeil(incumbent)) {
                    return Result(true, num, Q, p)
                }
                if (!incumbent.isFinite() || multiplierCount == 0) return@repeat
                if (!subgradientStep(assignment, valueList, p, num, incumbent, prevDir)) return@repeat
            }
        } catch (_: LpOverflowException) {
            if (bestNum == Long.MIN_VALUE) return null
        }
        return if (bestNum == Long.MIN_VALUE) null else Result(false, bestNum, Q, p)
    }

    /** Solve the assignment for adjusted coefficients `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri`. */
    private fun solveAssignment(
        session: PropagationSession,
        valueIndex: Map<Int, Int>,
        valueList: IntArrayList,
        p: LongArray,
    ): MinCostAssignment.Result {
        val assign = MinCostAssignment(vars.size, valueList.size)
        for (i in vars.indices) {
            val varId = vars[i]
            var w = mulExact(Q, intCoef[varId])
            for (r in 0 until multiplierCount) {
                val a = coeffOf(r, varId)
                if (a != 0L) w = addExact(w, mulExact(p[r], a))
            }
            session.intDomain(varId).forEach { value ->
                assign.addOption(i, valueIndex.getValue(value), mulExact(w, value.toLong()))
            }
        }
        return assign.solve()
    }

    /**
     * One deflected (conjugate) subgradient ascent step on the multipliers; false if the subgradient
     * is zero. The lightweight bundle-style stabilization (Camerini–Fratta–Maffioli with γ = 1):
     * the step direction is `d = g + β·prevDir` with `β = max(0, −(g·prevDir)/‖prevDir‖²)`, which by
     * Cauchy–Schwarz keeps `d·g ≥ 0` (a valid ascent direction) while damping the zigzag that slows
     * plain subgradient. A full proximal-bundle master is deferred — it needs a free-variable QP/LP
     * solver klause does not have, and the bound is exact for any λ regardless of how λ is chosen.
     */
    private fun subgradientStep(
        assignment: MinCostAssignment.Result,
        valueList: IntArrayList,
        p: LongArray,
        num: Long,
        incumbent: Double,
        prevDir: DoubleArray,
    ): Boolean {
        val g = LongArray(multiplierCount)
        var gNorm2 = 0.0
        for (r in 0 until multiplierCount) {
            var gr = -linkRhs[r]
            for (i in vars.indices) {
                val a = coeffOf(r, vars[i])
                if (a != 0L) gr += a * valueList[assignment.assignedValue[i]]
            }
            g[r] = gr
            gNorm2 += (gr.toDouble() * gr.toDouble())
        }
        if (gNorm2 == 0.0) return false // multipliers optimal for this subproblem
        // Deflection: combine with the previous direction to suppress zigzagging.
        var gDotPrev = 0.0
        var prevNorm2 = 0.0
        for (r in 0 until multiplierCount) {
            gDotPrev += g[r].toDouble() * prevDir[r]
            prevNorm2 += prevDir[r] * prevDir[r]
        }
        val beta = if (prevNorm2 > 0.0 && gDotPrev < 0.0) -gDotPrev / prevNorm2 else 0.0
        var dNorm2 = 0.0
        val d = DoubleArray(multiplierCount)
        for (r in 0 until multiplierCount) {
            d[r] = g[r].toDouble() + beta * prevDir[r]
            dNorm2 += d[r] * d[r]
            prevDir[r] = d[r]
        }
        if (dNorm2 == 0.0) return false
        // Polyak along the deflected direction: t = (UB − L) / ‖d‖²; p_r += round(Q·t·d_r), projected.
        val lValue = num.toDouble() / Q.toDouble()
        val t = (incumbent - lValue) / dNorm2
        for (r in 0 until multiplierCount) {
            val step = (Q.toDouble() * t * d[r]).toLong()
            var pr = p[r] + step
            when (linkSign[r]) {
                1 -> if (pr < 0L) pr = 0L
                -1 -> if (pr > 0L) pr = 0L
            }
            p[r] = pr
        }
        return true
    }

    /** Coefficient of [varId] in linking constraint [r], or 0. */
    private fun coeffOf(r: Int, varId: Int): Long {
        val vs = linkVars[r]
        for (k in vs.indices) if (vs[k] == varId) return linkCoeffs[r][k]
        return 0L
    }

    /** Objective contribution of everything outside the assignment: non-V ints, bools, constant.
     *  Checked arithmetic throughout — called inside [computeBound]'s overflow guard. */
    private fun trivialRest(session: PropagationSession): Long {
        var total = objConstant
        for (b in boolWeight.indices) {
            val w = boolWeight[b]
            if (w == 0L) continue
            val pinned = session.boolValue(b)
            total = addExact(
                total,
                when {
                    pinned == true -> w

                    pinned == false -> 0L

                    w < 0L -> w

                    // free: cheapest is true when the weight is negative
                    else -> 0L
                },
            )
        }
        for (i in intCoef.indices) {
            if (inV[i]) continue // handled exactly by the assignment
            val c = intCoef[i]
            if (c == 0L) continue
            val dom = session.intDomain(i)
            total = addExact(total, mulExact(c, if (c >= 0L) dom.min.toLong() else dom.max.toLong()))
        }
        return total
    }

    /** Smallest objective that still beats [incumbent]; `Long.MIN_VALUE` headroom when none. */
    private fun incumbentCeil(incumbent: Double): Long =
        if (incumbent.isFinite()) ceil(incumbent).toLong() else Long.MAX_VALUE

    private companion object {
        /** Fixed multiplier denominator: λ = p / Q. A power of two keeps the scaling exact and small. */
        const val Q: Long = 128L
        const val MAX_VARS: Int = 64
        const val MAX_VALUES: Int = 512
    }
}

/** Ceiling of `a / b` for `b > 0`. */
private fun ceilDivLocal(a: Long, b: Long): Long {
    val q = a / b
    val r = a % b
    return if (r > 0L) q + 1 else q
}
