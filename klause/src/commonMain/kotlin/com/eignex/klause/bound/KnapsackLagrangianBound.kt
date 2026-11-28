package com.eignex.klause.bound

import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.internals.permitsLongPseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.constructionIntArray
import com.eignex.klause.util.constructionLongArray
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact
import kotlin.math.ceil

// Multi-knapsack Lagrangian bound: the largest clean capacity row is solved exactly by 0/1-knapsack DP and the
// other pseudo-Boolean rows are dualized. The exact knapsack keeps integrality the LP relaxes away, so the bound
// can beat the LP. Multipliers are integers over the denominator Q, so every evaluated `L(λ)` is exact Long
// arithmetic and only the step uses floating point; overflow makes the bound unavailable, never wrong.
internal class KnapsackLagrangianBound(
    private val problem: Problem,
    private val objective: LinearObjective?,
    deferred: Boolean = false,
) : LagrangianDualBound {
    private lateinit var varIds: IntArray

    private lateinit var cost: LongArray

    private lateinit var kItems: IntArray
    private lateinit var kWeights: LongArray
    private var kCap: Long = 0L

    private lateinit var rowVars: Array<IntArray>
    private lateinit var rowCoeffs: Array<LongArray>
    private lateinit var rowRhs: LongArray
    private lateinit var rowSign: IntArray // +1: λ≥0 (LE), -1: λ≤0 (GE), 0: free (EQ)

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
            if (factor is PseudoBoolean && permitsLongPseudoBoolean(factor.constants)) pbs.add(factor)
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
        }
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
            varIds = emptyIntArray()
            cost = emptyLongArray()
            kItems = emptyIntArray()
            kWeights = emptyLongArray()
            kCap = 0L
            rowVars = emptyArray()
            rowCoeffs = emptyArray()
            rowRhs = emptyLongArray()
            rowSign = emptyIntArray()
            intCoef = emptyLongArray()
            objConstant = 0L
            applicable = false
        } else {
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
            for (f in others) {
                for (lit in f.literals) {
                    local(Lit.variable(lit))
                    if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                }
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

    override val multiplierCount: Int get() = rowVars.size

    private class Eval(val feasible: Boolean, val num: Long, val x: IntArray)

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
                if (!eval.feasible) return LagrangianResult(true, 0L, Q, p)
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

    // `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri`.
    private fun adjustedCost(i: Int, p: LongArray): Long {
        var w = mulExact(Q, cost[i])
        for (r in 0 until multiplierCount) {
            val a = coeffOf(r, i)
            if (a != 0L) w = addExact(w, mulExact(p[r], a))
        }
        return w
    }

    private fun evaluate(pinned: IntArray, p: LongArray): Eval {
        val x = IntArray(varIds.size)
        val w = LongArray(varIds.size) { adjustedCost(it, p) }
        val inKnap = BooleanArray(varIds.size)
        for (i in kItems) inKnap[i] = true
        var num = 0L
        var usedCap = 0L
        for (i in varIds.indices) {
            when {
                pinned[i] == 1 -> {
                    x[i] = 1
                    num = addExact(num, w[i])
                }

                pinned[i] == 0 -> x[i] = 0

                inKnap[i] -> Unit

                w[i] < 0L -> {
                    x[i] = 1
                    num = addExact(num, w[i])
                }
            }
        }
        for (k in kItems.indices) if (pinned[kItems[k]] == 1) usedCap = addExact(usedCap, kWeights[k])
        if (usedCap > kCap) return Eval(feasible = false, num = 0L, x = x)
        val rem = (kCap - usedCap).toInt()
        val dp = LongArray(rem + 1) // dp(c) = max Σ(−W) with weight ≤ c
        val take = ArrayList<Pair<Int, Int>>()
        for (k in kItems.indices) {
            val i = kItems[k]
            if (pinned[i] != -1 || w[i] >= 0L) continue
            if (kWeights[k] > rem.toLong()) continue
            take.add(i to kWeights[k].toInt())
        }
        val chosenFlag = Array(take.size) { BooleanArray(rem + 1) }
        for (t in take.indices) {
            val (i, wt) = take[t]
            val value = subExact(0L, w[i])
            for (c in rem downTo wt) {
                val cand = addExact(dp[c - wt], value)
                if (cand > dp[c]) {
                    dp[c] = cand
                    chosenFlag[t][c] = true
                }
            }
        }
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

    private fun subgradientStep(
        x: IntArray,
        p: LongArray,
        num: Long,
        incumbent: Double,
        prevDir: DoubleArray,
    ): Boolean {
        val g = LongArray(multiplierCount)
        for (r in 0 until multiplierCount) {
            var gr = -rowRhs[r]
            val vs = rowVars[r]
            val cs = rowCoeffs[r]
            for (k in vs.indices) gr += cs[k] * x[vs[k]]
            g[r] = gr
        }
        return p.deflectedSubgradientStep(g, num, incumbent, prevDir, Q, rowSign)
    }

    private fun coeffOf(r: Int, localVar: Int): Long {
        val vs = rowVars[r]
        var coefficient = 0L
        for (k in vs.indices) if (vs[k] == localVar) coefficient = addExact(coefficient, rowCoeffs[r][k])
        return coefficient
    }

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

private fun ceilDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b > 0L) q + 1 else q
}
