package com.eignex.klause.lp.bound

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.lp.LpOverflowException
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.mulExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import kotlin.math.ceil

/**
 * Subgradient Lagrangian bound for a 0/1 **multi-knapsack** decomposition (#572 / #632 — a
 * non-assignment exact subproblem). For `minimize Σ cᵢ·xᵢ` over Boolean variables subject to several
 * `PseudoBoolean` capacity rows `Σ aᵢ·xᵢ ≤ Cap`, **one** capacity row is kept as the subproblem and
 * solved **exactly** by 0/1-knapsack dynamic programming, while the remaining `PseudoBoolean` rows are
 * **dualized** with multipliers λ. For any valid-sign λ,
 * `L(λ) = min_x [obj + Σ_r λ_r(a_r·x − b_r)]` over the single kept knapsack is a lower bound on the
 * optimum (every feasible point makes the dualized terms ≤ 0), so maximizing it over λ tightens the
 * bound. The exact knapsack captures integrality the monolithic LP relaxes away, so at good λ the
 * bound can exceed the LP bound — the point of decomposition over plain relaxation.
 *
 * ## Why a knapsack (and not the scheduling subproblem #632 also lists)
 * The 0/1 knapsack DP is exact, pseudo-polynomial, and unambiguously sound; the single-machine
 * scheduling subproblem #632 also names overlaps klause's edge-finding propagation and was deferred
 * for that reason (see [CumulativeEnergeticBound]). This is the self-contained, clearly-sound slice.
 *
 * ## Exactness
 * Multipliers are integers `p_r` over a fixed denominator [Q] (`λ_r = p_r / Q`). The adjusted cost of
 * variable `i` is the integer `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri`, so the subproblem (free variables in closed
 * form, kept-knapsack variables by DP) is solved in exact [Long] arithmetic and the bound numerator
 * `subMin − Σ_r p_r·b_r + Q·rest` is exact. The subgradient *step* uses floating math only to pick the
 * next λ; every evaluated `L(λ)` is exact, so the reported bound is always valid. Overflow makes the
 * node's bound unavailable, never wrong.
 */
internal class KnapsackLagrangianBound(problem: Problem, objective: LinearObjective?) {
    /** Boolean variable id per local index (the union of the knapsack/link/objective variables). */
    private val varIds: IntArray

    /** Objective coefficient per local index. */
    private val cost: LongArray

    /** Kept-knapsack: local item indices, positive weights, and the capacity. */
    private val kItems: IntArray
    private val kWeights: LongArray
    private val kCap: Long

    /** Dualized linking rows (other PseudoBooleans), as `Σ coeff·x_local ⟨sign⟩ rhs` over local indices. */
    private val rowVars: Array<IntArray>
    private val rowCoeffs: Array<LongArray>
    private val rowRhs: LongArray
    private val rowSign: IntArray // +1: λ≥0 (LE), -1: λ≤0 (GE), 0: free (EQ)

    /** Objective contribution of the integer variables (this bound only reasons over the bools). */
    private val intCoef: LongArray
    private val objConstant: Long

    val applicable: Boolean

    init {
        val pbs = if (objective == null) emptyList() else problem.factors.filterIsInstance<PseudoBoolean>()
        // The subproblem knapsack must be a clean capacity row: all positive literals, positive weights,
        // a DP-bounded capacity. The cleanest, largest such row is kept; the rest are dualized.
        val subProblem = pbs.filter { cleanCapacity(it) }
            .maxByOrNull { it.literals.size }
        if (objective == null || subProblem == null) {
            varIds = IntArray(0)
            cost = LongArray(0)
            kItems = IntArray(0)
            kWeights = LongArray(0)
            kCap = 0L
            rowVars = emptyArray()
            rowCoeffs = emptyArray()
            rowRhs = LongArray(0)
            rowSign = IntArray(0)
            intCoef = LongArray(0)
            objConstant = 0L
            applicable = false
        } else {
            // Local index space: every bool var that appears in the kept knapsack, a linking row, or the
            // objective. A variable absent from all three contributes nothing and is left out.
            val index = HashMap<Int, Int>()
            val ids = IntArrayList()
            fun local(v: Int): Int = index.getOrPut(v) {
                ids.add(v)
                ids.size - 1
            }
            for (k in subProblem.literals.indices) local(Lit.variable(subProblem.literals[k]))
            val others = pbs.filter { it !== subProblem }
            for (f in others) for (lit in f.literals) local(Lit.variable(lit))
            for (b in objective.boolWeights.indices) if (objective.boolWeights[b] != 0L) local(b)
            varIds = ids.toIntArray()
            index.clear()
            for (i in varIds.indices) index[varIds[i]] = i

            cost = LongArray(varIds.size) { objective.boolWeights.getOrElse(varIds[it]) { 0L } }

            val kIdx = IntArrayList()
            val kW = LongArrayList()
            for (k in subProblem.literals.indices) {
                kIdx.add(index.getValue(Lit.variable(subProblem.literals[k])))
                kW.add(subProblem.weights[k].toLong())
            }
            kItems = kIdx.toIntArray()
            kWeights = LongArray(kW.size) { kW[it] }
            kCap = subProblem.bound.toLong()

            val rv = ArrayList<IntArray>()
            val rc = ArrayList<LongArray>()
            val rr = LongArrayList()
            val rs = IntArrayList()
            for (f in others) {
                val sign = when (f.op) {
                    PbOp.LE -> 1
                    PbOp.GE -> -1
                    PbOp.EQ -> 0
                }
                val cols = IntArray(f.literals.size)
                val vals = LongArray(f.literals.size)
                var rhs = f.bound.toLong()
                for (k in f.literals.indices) {
                    val lit = f.literals[k]
                    cols[k] = index.getValue(Lit.variable(lit))
                    val w = f.weights[k].toLong()
                    if (Lit.isPositive(lit)) {
                        vals[k] = w
                    } else {
                        // w·(1 − x) = w − w·x: coefficient −w, the constant w moves to the rhs.
                        vals[k] = -w
                        rhs -= w
                    }
                }
                rv.add(cols)
                rc.add(vals)
                rr.add(rhs)
                rs.add(sign)
            }
            rowVars = rv.toTypedArray()
            rowCoeffs = rc.toTypedArray()
            rowRhs = LongArray(rr.size) { rr[it] }
            rowSign = IntArray(rs.size) { rs[it] }

            intCoef = LongArray(problem.numIntVars) { objective.intCoefficients.getOrElse(it) { 0L } }
            objConstant = objective.constant
            applicable = kCap in 0..MAX_CAP && kItems.size <= MAX_ITEMS
        }
    }

    /** A PseudoBoolean usable as the kept subproblem: `Σ wᵢ·xᵢ ≤ bound`, all positive literals/weights. */
    private fun cleanCapacity(f: PseudoBoolean): Boolean =
        f.op == PbOp.LE && f.bound >= 0 && f.literals.all { Lit.isPositive(it) } && f.weights.all { it > 0 }

    /** Number of dualized linking constraints; the multiplier vector has this length. */
    val multiplierCount: Int get() = rowVars.size

    /** Outcome of a node bound: prune plus the best bound found and the multipliers to carry on. */
    class Result(val prune: Boolean, val boundNumerator: Long, val denominator: Long, val multipliers: LongArray)

    /** A subproblem evaluation at fixed multipliers: feasibility, scaled cost, and the chosen x. */
    private class Eval(val feasible: Boolean, val num: Long, val x: IntArray)

    /**
     * Compute the Lagrangian bound at the current node. [incumbent] is the best objective to beat
     * (`+∞` skips the subgradient and reports only the base bound). [startMultipliers] warm-starts λ.
     * Returns null when the bound is unavailable (not applicable, or arithmetic overflow).
     */
    fun computeBound(
        session: PropagationSession,
        incumbent: Double,
        startMultipliers: LongArray,
        iterations: Int,
    ): Result? {
        if (!applicable) return null
        val pinned = IntArray(varIds.size) { i ->
            when (session.boolValue(varIds[i])) {
                true -> 1
                false -> 0
                null -> -1
            }
        }
        val p = LongArray(multiplierCount) { startMultipliers.getOrElse(it) { 0L } }
        var bestNum = Long.MIN_VALUE
        val prevDir = DoubleArray(multiplierCount)
        try {
            val rest = trivialRest(session)
            val steps = if (incumbent.isFinite()) iterations else 1
            repeat(steps) {
                val eval = evaluate(pinned, p)
                if (!eval.feasible) return Result(true, 0L, Q, p) // forced items exceed capacity ⇒ infeasible
                var num = addExact(eval.num, mulExact(Q, rest))
                for (r in 0 until multiplierCount) num = subExact(num, mulExact(p[r], rowRhs[r]))
                if (num > bestNum) bestNum = num
                if (ceilDiv(num, Q) >= incumbentCeil(incumbent)) return Result(true, num, Q, p)
                if (!incumbent.isFinite() || multiplierCount == 0) return@repeat
                if (!subgradientStep(eval.x, p, num, incumbent, prevDir)) return@repeat
            }
        } catch (_: LpOverflowException) {
            if (bestNum == Long.MIN_VALUE) return null
        }
        return if (bestNum == Long.MIN_VALUE) null else Result(false, bestNum, Q, p)
    }

    /** Adjusted cost `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri` of local variable [i] under multipliers [p]. */
    private fun adjustedCost(i: Int, p: LongArray): Long {
        var w = mulExact(Q, cost[i])
        for (r in 0 until multiplierCount) {
            val a = coeffOf(r, i)
            if (a != 0L) w = addExact(w, mulExact(p[r], a))
        }
        return w
    }

    /**
     * Minimize `Σ Wᵢ·xᵢ` over the box (free vars in closed form) and the kept knapsack (exact 0/1 DP),
     * honouring pins. Free non-knapsack vars take 1 iff `Wᵢ < 0`; the knapsack picks, within remaining
     * capacity, the negative-`W` items that most reduce the objective.
     */
    private fun evaluate(pinned: IntArray, p: LongArray): Eval {
        val x = IntArray(varIds.size)
        val w = LongArray(varIds.size) { adjustedCost(it, p) }
        val inKnap = BooleanArray(varIds.size)
        for (i in kItems) inKnap[i] = true
        var num = 0L
        var usedCap = 0L
        // Pinned and free non-knapsack variables first.
        for (i in varIds.indices) {
            when {
                pinned[i] == 1 -> {
                    x[i] = 1
                    num = addExact(num, w[i])
                }

                pinned[i] == 0 -> x[i] = 0

                inKnap[i] -> Unit

                // decided by the DP below
                w[i] < 0L -> {
                    x[i] = 1
                    num = addExact(num, w[i])
                }
            }
        }
        // Capacity consumed by pinned-true knapsack items.
        for (k in kItems.indices) if (pinned[kItems[k]] == 1) usedCap = addExact(usedCap, kWeights[k])
        if (usedCap > kCap) return Eval(feasible = false, num = 0L, x = x)
        // Exact 0/1 knapsack over the still-free knapsack items: maximize Σ(−Wᵢ) within (kCap − usedCap).
        val rem = (kCap - usedCap).toInt()
        val dp = LongArray(rem + 1) // dp[c] = max Σ(−W) achievable with weight ≤ c
        val take = ArrayList<Pair<Int, Int>>() // (item local idx, weight) for free, beneficial items
        for (k in kItems.indices) {
            val i = kItems[k]
            if (pinned[i] != -1 || w[i] >= 0L) continue // pinned handled; W≥0 never helps a min
            take.add(i to kWeights[k].toInt())
        }
        val chosenFlag = Array(take.size) { BooleanArray(rem + 1) }
        for (t in take.indices) {
            val (i, wt) = take[t]
            val value = -w[i]
            for (c in rem downTo wt) {
                val cand = dp[c - wt] + value
                if (cand > dp[c]) {
                    dp[c] = cand
                    chosenFlag[t][c] = true
                }
            }
        }
        // Reconstruct the selection at full capacity and fold it into x / num.
        var c = rem
        var best = 0L
        var bestC = rem
        for (cc in 0..rem) {
            if (dp[cc] > best) {
                best = dp[cc]
                bestC = cc
            }
        }
        c = bestC
        for (t in take.indices.reversed()) {
            val (i, wt) = take[t]
            if (c >= wt && chosenFlag[t][c]) {
                x[i] = 1
                num = addExact(num, w[i])
                c -= wt
            }
        }
        return Eval(feasible = true, num = num, x = x)
    }

    /** One deflected (conjugate) subgradient ascent step; false if the subgradient is zero. Mirrors the
     *  Camerini–Fratta–Maffioli stabilization used by [LagrangianBound]. */
    private fun subgradientStep(
        x: IntArray,
        p: LongArray,
        num: Long,
        incumbent: Double,
        prevDir: DoubleArray,
    ): Boolean {
        val g = LongArray(multiplierCount)
        var gNorm2 = 0.0
        for (r in 0 until multiplierCount) {
            var gr = -rowRhs[r]
            val vs = rowVars[r]
            val cs = rowCoeffs[r]
            for (k in vs.indices) gr += cs[k] * x[vs[k]]
            g[r] = gr
            gNorm2 += gr.toDouble() * gr.toDouble()
        }
        if (gNorm2 == 0.0) return false
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
        val lValue = num.toDouble() / Q.toDouble()
        val t = (incumbent - lValue) / dNorm2
        for (r in 0 until multiplierCount) {
            val step = (Q.toDouble() * t * d[r]).toLong()
            var pr = p[r] + step
            when (rowSign[r]) {
                1 -> if (pr < 0L) pr = 0L
                -1 -> if (pr > 0L) pr = 0L
            }
            p[r] = pr
        }
        return true
    }

    private fun coeffOf(r: Int, localVar: Int): Long {
        val vs = rowVars[r]
        for (k in vs.indices) if (vs[k] == localVar) return rowCoeffs[r][k]
        return 0L
    }

    /** Objective contribution of the integer variables at their bound-optimal value (the bools are
     *  handled exactly by the subproblem). Checked arithmetic — called inside the overflow guard. */
    private fun trivialRest(session: PropagationSession): Long {
        var total = objConstant
        for (i in intCoef.indices) {
            val c = intCoef[i]
            if (c == 0L) continue
            val dom = session.intDomain(i)
            total = addExact(total, mulExact(c, if (c >= 0L) dom.min.toLong() else dom.max.toLong()))
        }
        return total
    }

    private fun incumbentCeil(incumbent: Double): Long =
        if (incumbent.isFinite()) ceil(incumbent).toLong() else Long.MAX_VALUE

    private companion object {
        const val Q: Long = 128L
        const val MAX_ITEMS: Int = 256
        const val MAX_CAP: Long = 200_000L
    }
}

/** Ceiling of `a / b` for `b > 0`. */
private fun ceilDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b > 0L) q + 1 else q
}
