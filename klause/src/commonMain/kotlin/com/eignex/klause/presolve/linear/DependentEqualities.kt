package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.lattice.SparseIntRow
import com.eignex.klause.lp.lattice.bareissEchelon
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.bigIntOf

/**
 * Drop every integer equality that the other equalities already imply, and refute a system of
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
        val factorOf = ArrayList<Int>()
        val rows = ArrayList<SparseIntRow>()
        val rhs = ArrayList<BigInt>()
        problem.factors.forEachIndexed { i, factor ->
            if (factor !is Linear || factor.op != LinearOp.EQ) return@forEachIndexed
            val constants = factor.integerConstants ?: return@forEachIndexed
            val row = sparseRow(factor, constants::coeff) ?: return@forEachIndexed
            factorOf.add(i)
            rows.add(row)
            rhs.add(bigIntOf(constants.bound))
        }
        // One equality cannot be implied by the others, and an empty system has nothing to imply.
        if (rows.size < 2) return SourceDelta()
        val echelon = bareissEchelon(rows, problem.numIntVars, rhs.toTypedArray(), cancellation)
        if (echelon.inconsistent) return SourceDelta(infeasible = true)
        val dependent = echelon.dependentRows
        if (dependent.isEmpty()) return SourceDelta()
        return SourceDelta(droppedIndices = IntArray(dependent.size) { factorOf[dependent[it]] })
    }

    /**
     * [factor]'s terms as a row sorted by column with repeated columns merged, or null when they cancel or
     * merging them leaves 64 bits — such a row is simply not read, which only ever keeps a row in place.
     */
    private fun sparseRow(factor: Linear, coeff: (Int) -> Long): SparseIntRow? {
        val merged = HashMap<Int, Long>(factor.vars.size)
        try {
            for (k in factor.vars.indices) {
                val v = factor.vars[k]
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
