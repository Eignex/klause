package com.eignex.klause.lp.bound

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableLongIntMap
import kotlin.math.ceil

/**
 * Subgradient Lagrangian bound for a constraint-coupling decomposition over AllDifferent globals
 * (#23, generalised to multiple blocks in #572). For
 * `minimize Σ cᵢ·xᵢ s.t. AllDifferent(V₁) ∧ … ∧ AllDifferent(V_k) ∧ (linear linking constraints)`,
 * dualize the linking constraints with multipliers λ and keep the AllDifferents as **independent
 * subproblems** — each an exact min-cost assignment ([MinCostAssignment]). For any valid-sign λ,
 * `L(λ) = min_x [obj + Σ_r λ_r(a_r·x − b_r)]` over the decoupled `AllDifferent(V_j)` is a lower bound
 * on the optimum (every original-feasible point makes the dualized terms ≤ 0), so maximizing it over
 * λ tightens the bound. The decomposition is what makes this stronger than the monolithic LP: each
 * block is solved as an exact combinatorial assignment, capturing the all-different structure the LP
 * relaxes loosely, while a linking constraint that *couples* two blocks is priced into both blocks'
 * costs through λ rather than dropped. Constraints not over the chosen blocks, and all other factors,
 * are dropped — a relaxation only loosens the bound, never makes it unsound.
 *
 * ## Blocks
 * The chosen blocks are pairwise variable-disjoint AllDifferents (each of size `2..`[MAX_VARS], up to
 * [MAX_TOTAL_VARS] variables total), so the residual problem after dualizing the linking constraints
 * separates exactly into one assignment per block. The single-block case (#23) is just `k = 1`.
 *
 * ## Exactness
 * Multipliers are kept as integers `p_r` over a fixed denominator [Q] (`λ_r = p_r / Q`). The adjusted
 * objective coefficient of variable `i` is then the integer `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri`, so each block
 * is solved in exact [Long] arithmetic and `L(λ) = (Σ_blocks M_block − Σ_r p_r·b_r + Q·rest) / Q` is an
 * exact rational. The subgradient *step* uses floating math to pick the next λ, but that only chooses
 * which λ to try — every evaluated `L(λ)` is exact, so the reported bound is always valid. Overflow
 * (large coefficients) makes the node's bound unavailable, never wrong.
 */
internal class LagrangianBound(problem: Problem, objective: LinearObjective?) : LagrangianDualBound {
    /** Variables of all chosen AllDifferent blocks, concatenated; empty when none is eligible. */
    private val vars: IntArray
    private val inV: BooleanArray

    /** Block boundaries: block `j` spans `vars[blockStart[j] until blockStart[j + 1]]`. */
    private val blockStart: IntArray

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
        val blocks = if (objective == null) emptyList() else chooseBlocks(problem)
        if (blocks.isEmpty()) {
            vars = EmptyIntArray
            inV = BooleanArray(numInt)
            blockStart = intArrayOf(0)
            linkVars = emptyArray()
            linkCoeffs = emptyArray()
            linkRhs = EmptyLongArray
            linkSign = EmptyIntArray
            intCoef = EmptyLongArray
            boolWeight = EmptyLongArray
            objConstant = 0L
            applicable = false
        } else {
            val flat = IntArrayList()
            val starts = IntArrayList()
            starts.add(0)
            for (b in blocks) {
                for (v in b) flat.add(v)
                starts.add(flat.size)
            }
            vars = flat.toIntArray()
            blockStart = starts.toIntArray()
            inV = BooleanArray(numInt)
            for (v in vars) inV[v] = true
            val lv = ArrayList<IntArray>()
            val lc = ArrayList<LongArray>()
            val lr = LongArrayList()
            val ls = IntArrayList()
            for (f in problem.factors) {
                if (f !is Linear || !f.isIntegerCore) continue
                val sign = when (f.op) {
                    LinearOp.LE -> 1
                    LinearOp.GE -> -1
                    LinearOp.EQ -> 0
                    LinearOp.NE -> continue // not a linear relaxation
                }
                if (f.vars.any { !inV[it] }) continue // only constraints over the chosen blocks are dualized
                lv.add(f.vars.copyOf())
                lc.add(f.coeffs.copyOf())
                lr.add(f.bound)
                ls.add(sign)
            }
            linkVars = lv.toTypedArray()
            linkCoeffs = lc.toTypedArray()
            linkRhs = LongArray(lr.size) { lr[it] }
            linkSign = IntArray(ls.size) { ls[it] }
            val obj = objective ?: error("blocks chosen only when objective is non-null")
            intCoef = LongArray(numInt) { obj.intCoefficients.getOrElse(it) { 0L } }
            boolWeight = LongArray(problem.numBoolVars) { obj.boolWeights.getOrElse(it) { 0L } }
            objConstant = obj.constant
            applicable = true
        }
    }

    /** Greedily pick pairwise variable-disjoint eligible AllDifferents under the total-variable cap. */
    private fun chooseBlocks(problem: Problem): List<IntArray> {
        val chosen = ArrayList<IntArray>()
        val used = IntHashSet()
        var total = 0
        for (f in problem.factors) {
            if (f !is AllDifferent || f.vars.size !in 2..MAX_VARS) continue
            // Only a *true* all-distinct admits the weighted-assignment bound: the excepted values of an
            // `alldifferent_except` may repeat (so "fewer distinct values than variables" is not
            // infeasible, and the assignment lower bound over-counts), and a conditional/optional
            // AllDifferent (`presents`) need not hold at all. Treating either as hard is unsound.
            if (f.exceptSet.isNotEmpty() || f.presents.isNotEmpty()) continue
            if (total + f.vars.size > MAX_TOTAL_VARS) continue
            if (f.vars.any { it in used }) continue // keep blocks disjoint so the subproblems decouple
            chosen.add(f.vars.copyOf())
            for (v in f.vars) used.add(v)
            total += f.vars.size
        }
        return chosen
    }

    /** Number of dualized linking constraints; the multiplier vector has this length. */
    override val multiplierCount: Int get() = linkVars.size

    private val numBlocks: Int get() = blockStart.size - 1

    /** A subproblem evaluation at fixed multipliers: combined assignment cost and the value each
     *  block variable took (indexed over [vars]); [feasible] is false when any block has no assignment. */
    private class Eval(val feasible: Boolean, val cost: Long, val values: LongArray)

    /**
     * Compute the Lagrangian bound at the current node. [incumbent] is the best objective to beat
     * (`+∞` if none — then the subgradient is skipped and only the base bound / infeasibility is
     * reported). [startMultipliers] warm-starts λ from a parent node. Returns null when the bound is
     * unavailable here (no eligible global, value set too large, or arithmetic overflow).
     */
    override fun computeBound(
        session: PropagationSession,
        incumbent: Double,
        startMultipliers: LongArray,
        iterations: Int,
    ): LagrangianResult? {
        if (!applicable) return null

        // Per-block value set = union of the live domains of the block's variables; bail if any block
        // is too large to assign over, prune if any has fewer distinct values than variables.
        val blockValueIndex = Array(numBlocks) { MutableLongIntMap() }
        val blockValueList = Array(numBlocks) { LongArrayList() }
        for (j in 0 until numBlocks) {
            val index = blockValueIndex[j]
            val list = blockValueList[j]
            for (pos in blockStart[j] until blockStart[j + 1]) {
                // Abort before walking a domain too large to assign over — notably a wide (>2^31-span)
                // domain, whose `sizeLong` saturates past the cap, so it is never enumerated. Sound: the
                // Lagrangian bound is simply skipped (null), never a wrong bound.
                if (session.intDomain(vars[pos]).sizeLong > MAX_VALUES) return null
                session.intDomain(vars[pos]).forEach { value ->
                    if (!index.containsKey(value)) {
                        index.put(value, list.size)
                        list.add(value)
                    }
                }
            }
            val size = blockStart[j + 1] - blockStart[j]
            if (list.size < size) {
                // Fewer distinct values than variables ⇒ this AllDifferent is infeasible ⇒ node infeasible.
                return LagrangianResult(
                    prune = true,
                    boundNumerator = 0L,
                    denominator = Q,
                    multipliers = startMultipliers,
                )
            }
            if (list.size > MAX_VALUES) return null // too large to assign over here
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
                val eval = evaluate(session, blockValueIndex, blockValueList, p)
                if (!eval.feasible) return LagrangianResult(true, 0L, Q, p) // infeasible ⇒ node infeasible
                // numerator = Σ_blocks M_block − Σ_r p_r·b_r + Q·rest, with L = numerator / Q.
                var num = eval.cost
                for (r in 0 until multiplierCount) num = subExact(num, mulExact(p[r], linkRhs[r]))
                num = addExact(num, mulExact(Q, rest))
                if (num > bestNum) bestNum = num
                if (ceilDivLocal(num, Q) >= incumbentCeil(incumbent)) return LagrangianResult(true, num, Q, p)
                if (!incumbent.isFinite() || multiplierCount == 0) return@repeat
                if (!subgradientStep(eval.values, p, num, incumbent, prevDir)) return@repeat
            }
        } catch (_: LpOverflowException) {
            if (bestNum == Long.MIN_VALUE) return null
        }
        return if (bestNum == Long.MIN_VALUE) null else LagrangianResult(false, bestNum, Q, p)
    }

    /**
     * Solve every block's assignment for adjusted coefficients `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri` and combine
     * them: the total cost is the sum over blocks, the assigned value of each block variable is read
     * back (as an actual value) for the subgradient. Infeasible as soon as any one block is.
     */
    private fun evaluate(
        session: PropagationSession,
        blockValueIndex: Array<MutableLongIntMap>,
        blockValueList: Array<LongArrayList>,
        p: LongArray,
    ): Eval {
        val values = LongArray(vars.size)
        var totalCost = 0L
        for (j in 0 until numBlocks) {
            val lo = blockStart[j]
            val size = blockStart[j + 1] - lo
            val assign = MinCostAssignment(size, blockValueList[j].size)
            for (idx in 0 until size) {
                val varId = vars[lo + idx]
                var w = mulExact(Q, intCoef[varId])
                for (r in 0 until multiplierCount) {
                    val a = coeffOf(r, varId)
                    if (a != 0L) w = addExact(w, mulExact(p[r], a))
                }
                session.intDomain(varId).forEach { value ->
                    assign.addOption(idx, blockValueIndex[j].getOrDefault(value, -1), mulExact(w, value))
                }
            }
            val res = assign.solve()
            if (!res.feasible) return Eval(feasible = false, cost = 0L, values = values)
            totalCost = addExact(totalCost, res.cost)
            for (idx in 0 until size) values[lo + idx] = blockValueList[j][res.assignedValue[idx]]
        }
        return Eval(feasible = true, cost = totalCost, values = values)
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
        values: LongArray,
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
                if (a != 0L) gr += a * values[i]
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
            total = addExact(total, mulExact(c, if (c >= 0L) dom.min else dom.max))
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

        /** Cap on the total variables across all chosen blocks, bounding the per-node assignment work. */
        const val MAX_TOTAL_VARS: Int = 256
    }
}

/** Ceiling of `a / b` for `b > 0`. */
private fun ceilDivLocal(a: Long, b: Long): Long {
    val q = a / b
    val r = a % b
    return if (r > 0L) q + 1 else q
}
