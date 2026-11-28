package com.eignex.klause.bound

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.MinCostAssignment
import com.eignex.klause.util.constructionIntArray
import com.eignex.klause.util.constructionLongArray
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableLongIntMap
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact
import com.eignex.util.numbers.mulExact
import com.eignex.util.numbers.subExact
import kotlin.math.ceil

// Dualizes the linear rows linking pairwise variable-disjoint all-different blocks and solves each block as an
// exact min-cost assignment, capturing the all-different structure the LP relaxes loosely. Other factors are
// dropped, which only loosens the bound. Multipliers are integers over the denominator Q, so every evaluated
// `L(λ)` is exact Long arithmetic and only the step uses floating point; overflow makes the bound unavailable.
internal class LagrangianBound(
    private val problem: Problem,
    private val objective: LinearObjective?,
    deferred: Boolean = false,
) : LagrangianDualBound {
    private lateinit var vars: IntArray
    private lateinit var inV: BooleanArray

    private lateinit var blockStart: IntArray

    private lateinit var linkVars: Array<IntArray>
    private lateinit var linkCoeffs: Array<LongArray>
    private lateinit var linkRhs: LongArray
    private lateinit var linkSign: IntArray // +1: λ≥0 (LE), -1: λ≤0 (GE), 0: free (EQ)

    private lateinit var intCoef: LongArray
    private lateinit var boolWeight: LongArray
    private var objConstant: Long = 0L

    var applicable: Boolean = false
        private set

    init {
        if (!deferred) preparation().forEach { }
    }

    internal fun preparation(): Iterator<Unit> = sequence {
        var visits = 0
        val numInt = problem.numIntVars
        var blocks: List<IntArray> = emptyList()
        if (objective != null) for (next in chooseBlocks(problem)) {
            if (next != null) blocks = next
            yield(Unit)
        }
        if (blocks.isEmpty()) {
            vars = emptyIntArray()
            inV = BooleanArray(numInt)
            blockStart = intArrayOf(0)
            linkVars = emptyArray()
            linkCoeffs = emptyArray()
            linkRhs = emptyLongArray()
            linkSign = emptyIntArray()
            intCoef = emptyLongArray()
            boolWeight = emptyLongArray()
            objConstant = 0L
            applicable = false
        } else {
            val flat = IntArrayList()
            val starts = IntArrayList()
            starts.add(0)
            for (b in blocks) {
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
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
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                if (f !is Linear) continue
                val row = f.integerConstants ?: continue
                val sign = when (f.op) {
                    LinearOp.LE -> 1
                    LinearOp.GE -> -1
                    LinearOp.EQ -> 0
                    LinearOp.NE -> continue
                }
                var contained = true
                for (v in f.vars) {
                    if (!inV[v]) {
                        contained = false
                        break
                    }
                    if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                }
                if (!contained) continue
                lv.add(f.vars.copyOf())
                lc.add(row.coeffs)
                lr.add(row.bound)
                ls.add(sign)
            }
            linkVars = lv.toTypedArray()
            linkCoeffs = lc.toTypedArray()
            linkRhs = constructionLongArray(lr.size) { lr[it] }
            linkSign = constructionIntArray(ls.size) { ls[it] }
            val obj = objective ?: error("blocks chosen only when objective is non-null")
            intCoef = constructionLongArray(numInt) { obj.intCoefficientValues.getOrElse(it) { 0L } }
            boolWeight = constructionLongArray(problem.numBoolVars) { obj.boolWeightValues.getOrElse(it) { 0L } }
            objConstant = obj.constant
            applicable = true
        }
    }.iterator()

    private fun chooseBlocks(problem: Problem): Sequence<List<IntArray>?> = sequence {
        var visits = 0
        val chosen = ArrayList<IntArray>()
        val used = IntHashSet()
        var total = 0
        for (f in problem.factors) {
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(null)
            if (f !is AllDifferent || f.vars.size !in 2..MAX_VARS) continue
            // Excepted values may repeat and optional positions need not hold, so treating either as hard is unsound.
            if (f.exceptSet.isNotEmpty() || f.presents.isNotEmpty()) continue
            if (total + f.vars.size > MAX_TOTAL_VARS) continue
            if (f.vars.any { it in used }) continue
            chosen.add(f.vars.copyOf())
            for (v in f.vars) used.add(v)
            total += f.vars.size
        }
        yield(chosen)
    }

    override val multiplierCount: Int get() = linkVars.size

    private val numBlocks: Int get() = blockStart.size - 1

    private class Eval(val feasible: Boolean, val cost: Long, val values: LongArray)

    override fun computeBound(
        session: PropagationSession,
        incumbent: Double,
        startMultipliers: LongArray,
        iterations: Int,
    ): LagrangianResult? {
        if (!applicable) return null

        val blockValueIndex = Array(numBlocks) { MutableLongIntMap() }
        val blockValueList = Array(numBlocks) { LongArrayList() }
        for (j in 0 until numBlocks) {
            val index = blockValueIndex[j]
            val list = blockValueList[j]
            for (pos in blockStart[j] until blockStart[j + 1]) {
                if (session.intDomain(vars[pos]).spanOrNull(MAX_VALUES.toLong()) == null) return null
                session.intDomain(vars[pos]).values.forEach { value ->
                    if (!index.containsKey(value)) {
                        index.put(value, list.size)
                        list.add(value)
                    }
                }
            }
            val size = blockStart[j + 1] - blockStart[j]
            if (list.size < size) {
                return LagrangianResult(
                    prune = true,
                    boundNumerator = 0L,
                    denominator = Q,
                    multipliers = startMultipliers,
                )
            }
            if (list.size > MAX_VALUES) return null
        }

        val p = LongArray(multiplierCount) { startMultipliers.getOrElse(it) { 0L } }
        var bestNum = Long.MIN_VALUE
        val prevDir = DoubleArray(multiplierCount)

        try {
            val rest = trivialRest(session)
            val steps = if (incumbent.isFinite()) iterations else 1
            repeat(steps) {
                val eval = evaluate(session, blockValueIndex, blockValueList, p)
                if (!eval.feasible) return LagrangianResult(true, 0L, Q, p)
                // L = (Σ_blocks M_block − Σ_r p_r·b_r + Q·rest) / Q.
                var num = eval.cost
                for (r in 0 until multiplierCount) num = subExact(num, mulExact(p[r], linkRhs[r]))
                num = addExact(num, mulExact(Q, rest))
                if (num > bestNum) bestNum = num
                if (ceilDivLocal(num, Q) >= incumbentCeil(incumbent)) return LagrangianResult(true, num, Q, p)
                if (!incumbent.isFinite() || multiplierCount == 0) return@repeat
                if (!subgradientStep(eval.values, p, num, incumbent, prevDir)) return@repeat
            }
        } catch (_: CheckedLongOverflowException) {
            if (bestNum == Long.MIN_VALUE) return null
        }
        return if (bestNum == Long.MIN_VALUE) null else LagrangianResult(false, bestNum, Q, p)
    }

    // Adjusted coefficients `Wᵢ = Q·cᵢ + Σ_r p_r·a_ri`; infeasible as soon as any block is.
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
                session.intDomain(varId).values.forEach { value ->
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

    private fun subgradientStep(
        values: LongArray,
        p: LongArray,
        num: Long,
        incumbent: Double,
        prevDir: DoubleArray,
    ): Boolean {
        val g = LongArray(multiplierCount)
        for (r in 0 until multiplierCount) {
            var gr = -linkRhs[r]
            for (i in vars.indices) {
                val a = coeffOf(r, vars[i])
                if (a != 0L) gr += a * values[i]
            }
            g[r] = gr
        }
        return p.deflectedSubgradientStep(g, num, incumbent, prevDir, Q, linkSign)
    }

    private fun coeffOf(r: Int, varId: Int): Long {
        val vs = linkVars[r]
        for (k in vs.indices) if (vs[k] == varId) return linkCoeffs[r][k]
        return 0L
    }

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

                    else -> 0L
                },
            )
        }
        for (i in intCoef.indices) {
            if (inV[i]) continue
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
        // A power of two keeps the scaling exact and small.
        const val Q: Long = 128L
        const val MAX_VARS: Int = 64
        const val MAX_VALUES: Int = 512

        const val MAX_TOTAL_VARS: Int = 256
    }
}

private fun ceilDivLocal(a: Long, b: Long): Long {
    val q = a / b
    val r = a % b
    return if (r > 0L) q + 1 else q
}
