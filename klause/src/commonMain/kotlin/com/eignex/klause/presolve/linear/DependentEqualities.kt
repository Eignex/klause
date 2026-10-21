package com.eignex.klause.presolve.linear

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.lp.lattice.SparseIntRow
import com.eignex.klause.lp.lattice.bareissEchelon
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.bigIntOf

/**
 * Drop redundant single-equality factors using declared integer equalities, and refute a system of
 * equalities that has no rational solution at all.
 *
 * Duplicate and proportional rows are bound fusion's and subsumption's business, and a row that
 * substitution empties is affine elimination's; what neither sees is a row that is the sum of two or
 * more others, as an assignment's last row-sum is of the column sums and the remaining row sums. Exact
 * fraction-free elimination over the equality block finds those as the rows that reduce to `0 = 0`.
 * Each is a rational combination of rows that stay, so every solution of the kept rows satisfies it and
 * dropping the lot leaves the solution set as it was.
 *
 * Reads only the rows, never a domain, so it runs before any finite projection exists. A row whose
 * constants leave 64 bits is not read: it stays, which can only leave a dependent row in place.
 */
internal object DependentEqualities {

    fun dropImplied(problem: Problem, cancellation: Cancellation): SourceDelta {
        val droppableFactorOf = ArrayList<Int>()
        val rows = ArrayList<SparseIntRow>()
        val rhs = ArrayList<BigInt>()
        problem.factors.forEachIndexed { i, factor ->
            if (cancellation()) return SourceDelta()
            val declared = factor.impliedLinearRows
            val droppable = factor.linearForm is LinearForm.Conjunction && declared.size == 1
            for (row in declared) {
                if (cancellation()) return SourceDelta()
                if (row.relation != LinearOp.EQ || !row.isLongUnconditional || !row.isIntegerOnly) continue
                val constants = row.constants as IntegerConstants
                val sparse = sparseRow(row, constants::coeff) ?: continue
                droppableFactorOf.add(if (droppable) i else -1)
                rows.add(sparse)
                rhs.add(bigIntOf(constants.bound))
            }
        }
        // One equality cannot be implied by the others, and an empty system has nothing to imply.
        if (rows.size < 2) return SourceDelta()
        val echelon = bareissEchelon(rows, problem.numIntVars, rhs.toTypedArray(), cancellation)
        if (echelon.inconsistent) return SourceDelta(infeasible = true)
        val dependent = echelon.dependentRows
        if (dependent.isEmpty()) return SourceDelta()
        return SourceDelta(droppedIndices = dependent.map { droppableFactorOf[it] }.filter { it >= 0 }.toIntArray())
    }

    /**
     * [row]'s terms as a row sorted by column with repeated columns merged, or null when they cancel or
     * merging them leaves 64 bits — such a row is simply not read, which only ever keeps a row in place.
     */
    private fun sparseRow(row: LinearRow, coeff: (Int) -> Long): SparseIntRow? {
        val merged = HashMap<Int, Long>(row.size)
        try {
            for (k in 0 until row.size) {
                val v = Term.intVar(row.ref(k))
                merged[v] = addExact(merged[v] ?: 0L, coeff(k))
            }
        } catch (_: CheckedLongOverflowException) {
            return null
        }
        val columns = merged.keys.filter { merged.getValue(it) != 0L }.sorted()
        if (columns.isEmpty()) return null
        return SparseIntRow(
            columns.toIntArray(),
            Array(columns.size) { bigIntOf(merged.getValue(columns[it])) },
        )
    }
}
