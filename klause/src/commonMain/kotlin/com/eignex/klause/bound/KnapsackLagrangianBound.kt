package com.eignex.klause.bound

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.constructionIntArray
import com.eignex.klause.util.constructionLongArray
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact
import kotlin.math.ceil

/**
 * Subgradient Lagrangian bound for a 0/1 **multi-knapsack** decomposition over a
 * non-assignment exact subproblem. For `minimize Σ cᵢ·xᵢ` over Boolean variables subject to several
 * `PseudoBoolean` capacity rows `Σ aᵢ·xᵢ ≤ Cap`, **one** capacity row is kept as the subproblem and
 * solved **exactly** by 0/1-knapsack dynamic programming, while the remaining `PseudoBoolean` rows are
 * **dualized** with multipliers λ. For any valid-sign λ,
 * `L(λ) = min_x [obj + Σ_r λ_r(a_r·x − b_r)]` over the single kept knapsack is a lower bound on the
 * optimum (every feasible point makes the dualized terms ≤ 0), so maximizing it over λ tightens the
 * bound. The exact knapsack captures integrality the monolithic LP relaxes away, so at good λ the
 * bound can exceed the LP bound — the point of decomposition over plain relaxation.
 *
 * ## Exactness
 * Multipliers are integers `p_r` over a fixed denominator [Q] (`λ_r = p_r / Q`). The adjusted cost of
 * variable `i` is the integer `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri`, so the subproblem (free variables in closed
 * form, kept-knapsack variables by DP) is solved in exact [Long] arithmetic and the bound numerator
 * `subMin − Σ_r p_r·b_r + Q·rest` is exact. The subgradient *step* uses floating math only to pick the
 * next λ; every evaluated `L(λ)` is exact, so the reported bound is always valid. Overflow makes the
 * node's bound unavailable, never wrong.
 */
internal class KnapsackLagrangianBound(
    private val problem: Problem,
    private val objective: LinearObjective?,
    deferred: Boolean = false,
) : LagrangianDualBound {
    /** Boolean variable id per local index (the union of the knapsack/link/objective variables). */
    private lateinit var varIds: IntArray

    /** Objective coefficient per local index. */
    private lateinit var cost: LongArray

    /** Kept-knapsack: local item indices, positive weights, and the capacity. */
    private lateinit var kItems: IntArray
    private lateinit var kWeights: LongArray
    private var kCap: Long = 0L

    /** Dualized linking rows (other PseudoBooleans), as `Σ coeff·x_local ⟨sign⟩ rhs` over local indices. */
    private lateinit var rowVars: Array<IntArray>
    private lateinit var rowCoeffs: Array<LongArray>
    private lateinit var rowRhs: LongArray
    private lateinit var rowSign: IntArray // +1: λ≥0 (LE), -1: λ≤0 (GE), 0: free (EQ)

    /** Objective contribution of the integer variables (this bound only reasons over the bools). */
    private lateinit var intCoef: LongArray
    private var objConstant: Long = 0L

    var applicable: Boolean = false
        private set

    init {
        if (!deferred) preparation().forEach { }
    }

    internal fun preparation(): Iterator<Unit> = sequence {
        var visits = 0
        val pbs = ArrayList<PseudoBoolean>()
        if (objective != null) for (factor in problem.factors) {
            if (factor is PseudoBoolean) pbs.add(factor)
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
        }
        // The subproblem knapsack must be a clean capacity row: all positive literals, positive weights,
        // a DP-bounded capacity. The cleanest, largest such row is kept; the rest are dualized.
        var chosen: PseudoBoolean? = null
        for (factor in pbs) {
            var clean = factor.op == PbOp.LE && factor.bound >= 0
            for (k in factor.literals.indices) {
                clean = clean && Lit.isPositive(factor.literals[k]) && factor.weights[k] > 0L
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
            if (clean && (chosen == null || factor.literals.size > chosen.literals.size)) chosen = factor
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
        }
        val subProblem = chosen
        if (objective == null || subProblem == null) {
            varIds = EmptyIntArray
            cost = EmptyLongArray
            kItems = EmptyIntArray
            kWeights = EmptyLongArray
            kCap = 0L
            rowVars = emptyArray()
            rowCoeffs = emptyArray()
            rowRhs = EmptyLongArray
            rowSign = EmptyIntArray
            intCoef = EmptyLongArray
            objConstant = 0L
            applicable = false
        } else {
            // Local index space: every bool var that appears in the kept knapsack, a linking row, or the
            // objective. A variable absent from all three contributes nothing and is left out.
            val index = MutableIntIntMap(problem.numBoolVars)
            yield(Unit)
            val ids = IntArrayList()
            fun local(v: Int): Int {
                if (index.containsKey(v)) return index.getOrDefault(v, -1)
                ids.add(v)
                val idx = ids.size - 1
                index.put(v, idx)
                return idx
            }
            for (k in subProblem.literals.indices) {
                local(Lit.variable(subProblem.literals[k]))
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
            val others = ArrayList<PseudoBoolean>()
            for (factor in pbs) {
                if (factor !== subProblem) others.add(factor)
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
            for (f in others) for (lit in f.literals) {
                local(Lit.variable(lit))
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
            for (b in objective.boolWeightValues.indices) {
                if (objective.boolWeightValues[b] != 0L) local(b)
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
            varIds = ids.toIntArray()

            cost = constructionLongArray(varIds.size) { objective.boolWeightValues.getOrElse(varIds[it]) { 0L } }

            val kIdx = IntArrayList()
            val kW = LongArrayList()
            for (k in subProblem.literals.indices) {
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                kIdx.add(index.getOrDefault(Lit.variable(subProblem.literals[k]), -1))
                kW.add(subProblem.weights[k])
            }
            kItems = kIdx.toIntArray()
            kWeights = constructionLongArray(kW.size) { kW[it] }
            kCap = subProblem.bound

            val rv = ArrayList<IntArray>()
            val rc = ArrayList<LongArray>()
            val rr = LongArrayList()
            val rs = IntArrayList()
            for (f in others) {
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                val sign = when (f.op) {
                    PbOp.LE -> 1
                    PbOp.GE -> -1
                    PbOp.EQ -> 0
                }
                val cols = IntArray(f.literals.size)
                yield(Unit)
                val vals = LongArray(f.literals.size)
                yield(Unit)
                var rhs = f.bound
                for (k in f.literals.indices) {
                    if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                    val lit = f.literals[k]
                    cols[k] = index.getOrDefault(Lit.variable(lit), -1)
                    val w = f.weights[k]
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
            rowRhs = constructionLongArray(rr.size) { rr[it] }
            rowSign = constructionIntArray(rs.size) { rs[it] }

            intCoef = constructionLongArray(problem.numIntVars) { objective.intCoefficientValues.getOrElse(it) { 0L } }
            objConstant = objective.constant
            applicable = kCap in 0..MAX_CAP && kItems.size <= MAX_ITEMS
        }
    }.iterator()

    /** Number of dualized linking constraints; the multiplier vector has this length. */
    override val multiplierCount: Int get() = rowVars.size

    /** A subproblem evaluation at fixed multipliers: feasibility, scaled cost, and the chosen x. */
    private class Eval(val feasible: Boolean, val num: Long, val x: IntArray)

    /**
     * Compute the Lagrangian bound at the current node. [incumbent] is the best objective to beat
     * (`+∞` skips the subgradient and reports only the base bound). [startMultipliers] warm-starts λ.
     * Returns null when the bound is unavailable (not applicable, or arithmetic overflow).
     */
    override fun computeBound(
        session: PropagationSession,
        incumbent: Double,
        startMultipliers: LongArray,
        iterations: Int,
    ): LagrangianResult? {
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
                if (!eval.feasible) return LagrangianResult(true, 0L, Q, p) // forced items exceed capacity ⇒ infeasible
                var num = addExact(eval.num, mulExact(Q, rest))
                for (r in 0 until multiplierCount) num = subExact(num, mulExact(p[r], rowRhs[r]))
                if (num > bestNum) bestNum = num
                if (ceilDiv(num, Q) >= incumbentCeil(incumbent)) return LagrangianResult(true, num, Q, p)
                if (!incumbent.isFinite() || multiplierCount == 0) return@repeat
                if (!subgradientStep(eval.x, p, num, incumbent, prevDir)) return@repeat
            }
        } catch (_: CheckedLongOverflowException) {
            if (bestNum == Long.MIN_VALUE) return null
        }
        return if (bestNum == Long.MIN_VALUE) null else LagrangianResult(false, bestNum, Q, p)
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
            total = addExact(total, mulExact(c, if (c >= 0L) dom.min else dom.max))
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
