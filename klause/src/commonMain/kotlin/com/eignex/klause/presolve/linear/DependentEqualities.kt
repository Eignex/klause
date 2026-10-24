package com.eignex.klause.presolve.linear

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.lp.lattice.EchelonLimits
import com.eignex.klause.lp.lattice.LATTICE_WORK_WEIGHT
import com.eignex.klause.lp.lattice.SparseIntRow
import com.eignex.klause.lp.lattice.bareissEchelon
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.div
import com.eignex.klause.util.isZero
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

/**
 * Exact sparse elimination over unconditional source equalities. Tagged coordinates keep integer and
 * real namespaces disjoint. Only a whole single-equality factor can be dropped; implied rows from
 * larger factors remain premises. Binary partition equations retain finite search structure.
 */
internal object DependentEqualities {
    private val limits = EchelonLimits(maxRowEntries = 4096, maxTotalEntries = 262144, maxBits = 4096)

    fun dropImplied(problem: Problem, cancellation: Cancellation): SourceDelta {
        if (problem.semantics.effective.feasibilityTolerance?.isExact == false) return SourceDelta()
        val columns = LinkedHashMap<Int, Int>()
        val droppableFactorOf = ArrayList<Int>()
        val rows = ArrayList<SparseIntRow>()
        val rhs = ArrayList<BigInt>()
        val constantDrops = ArrayList<Int>()
        var entries = 0L
        problem.factors.forEachIndexed { i, factor ->
            if (cancellation()) return SourceDelta()
            cancellation.charge(LATTICE_WORK_WEIGHT)
            val declared = factor.impliedLinearRows
            val droppable = factor.linearForm is LinearForm.Conjunction && declared.size == 1
            for (row in declared) {
                if (cancellation()) return SourceDelta()
                if (row.relation != LinearOp.EQ || row.activator != LinearRow.ALWAYS || row.strict) continue
                val exact = exactRow(row, cancellation) ?: continue
                if (cancellation()) return SourceDelta()
                if (exact.first.isEmpty()) {
                    if (!exact.second.isZero()) return SourceDelta(infeasible = true)
                    if (droppable) constantDrops.add(i)
                    continue
                }
                entries += exact.first.size
                if (entries > limits.maxTotalEntries) return SourceDelta()
                val removeFactor = droppable && !isBinaryPartition(exact.first, exact.second, problem)
                val indexed = exact.first.entries.map { (ref, value) ->
                    columns.getOrPut(ref) { columns.size } to value
                }.sortedBy { it.first }
                droppableFactorOf.add(if (removeFactor) i else -1)
                rows.add(SparseIntRow(indexed.map { it.first }.toIntArray(), indexed.map { it.second }.toTypedArray()))
                rhs.add(exact.second)
            }
        }
        if (cancellation()) return SourceDelta()
        if (rows.size < 2) return SourceDelta(droppedIndices = constantDrops.toIntArray())
        val echelon = bareissEchelon(rows, columns.size, rhs.toTypedArray(), cancellation, limits)
        if (cancellation()) return SourceDelta()
        if (echelon.inconsistent) return SourceDelta(infeasible = true)
        val dropped = constantDrops + echelon.dependentRows.map { droppableFactorOf[it] }.filter { it >= 0 }
        return SourceDelta(droppedIndices = dropped.toIntArray())
    }

    private fun isBinaryPartition(terms: Map<Int, BigInt>, bound: BigInt, problem: Problem): Boolean {
        if (bound.isZero()) return false
        val bounds = problem.intBounds
        for ((ref, coefficient) in terms) {
            if (!Term.isInt(ref) || coefficient != bound) return false
            val v = Term.intVar(ref)
            if (!bounds.hasLower(v) || !bounds.hasUpper(v) || bounds.lower(v) < 0L || bounds.upper(v) > 1L) {
                return false
            }
        }
        return true
    }

    private fun exactRow(row: LinearRow, cancellation: Cancellation): Pair<Map<Int, BigInt>, BigInt>? {
        if (row.size > limits.maxRowEntries) return null
        val constants = row.constants
        val realValues = if (constants is RealConstants) {
            val values = ArrayList<BigFraction>(row.size + 1)
            values.add(BigFraction.ofDouble(constants.bound) ?: return null)
            for (k in 0 until row.size) {
                cancellation.charge(LATTICE_WORK_WEIGHT)
                if (cancellation()) return null
                val value = if (k < constants.intCoefficients.size) constants.intCoefficients.at(k)
                else constants.realCoefficients.at(k - constants.intCoefficients.size)
                values.add(BigFraction.ofDouble(value) ?: return null)
            }
            values
        } else null
        // Binary64 denominators are powers of two, so the largest clears the entire row.
        val denominator = realValues?.maxBy { it.den.magnitudeBitLength() }?.den ?: BIG_ONE
        val bound = if (constants is IntegralConstants) constants.exactBound
        else realValues!![0].num * (denominator / realValues[0].den)
        if (bound.magnitudeBitLength() > limits.maxBits) return null
        val merged = HashMap<Int, BigInt>(row.size)
        for (k in 0 until row.size) {
            cancellation.charge(LATTICE_WORK_WEIGHT)
            if (cancellation()) return null
            val ref = row.ref(k)
            if (!Term.isInt(ref) && !Term.isReal(ref)) return null
            val coefficient = if (constants is IntegralConstants) constants.exactCoeff(k)
            else realValues!![k + 1].num * (denominator / realValues[k + 1].den)
            if (coefficient.magnitudeBitLength() > limits.maxBits) return null
            val sum = (merged[ref] ?: BIG_ZERO) + coefficient
            if (sum.magnitudeBitLength() > limits.maxBits) return null
            if (sum.isZero()) merged.remove(ref) else merged[ref] = sum
        }
        return merged to bound
    }
}
