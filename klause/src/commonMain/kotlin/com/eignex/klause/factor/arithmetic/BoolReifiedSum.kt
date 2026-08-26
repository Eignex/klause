package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.addExact

/**
 * The Boolean fan-in of a reified weighted sum folded over its LP columns: `Σ coeffs·x_col + constant`,
 * with the declared `[lMin, lMax]` range of the `Σ coeffs·x_col` part over `x ∈ [0,1]`. A negative
 * literal `w·(1 − x)` folds to coefficient `−w` and `+w` into the constant. Shared by the reified
 * Boolean `linearize` of [ReifiedPseudoBoolean] and [ReifiedCardinality].
 */
internal class BoolReifiedSum private constructor(
    private val cols: IntArray,
    private val coeffs: LongArray,
    val constant: Long,
    val lMin: Long,
    val lMax: Long,
) {
    /**
     * Emit `Σ coeffs·x + auxCoeff·x_aux ⟨op⟩ rhs`. The big-M rests on the declared `[0, 1]` literal
     * ranges, so the row holds at every solution (global) — a feasibility-defining CORE row.
     */
    fun reifiedRow(builder: RelaxationBuilder, auxCol: Int, auxCoeff: Long, op: LinearOp, rhs: Long) {
        val rowCols = cols.copyOf(cols.size + 1)
        val rowVals = coeffs.copyOf(coeffs.size + 1)
        rowCols[cols.size] = auxCol
        rowVals[coeffs.size] = auxCoeff
        builder.row(rowCols, rowVals, op, rhs)
    }

    companion object {
        /** Fold [literals] (with optional [weights]; `null` = unit) over their Boolean columns. */
        fun fold(builder: RelaxationBuilder, literals: IntArray, weights: LongArray?): BoolReifiedSum {
            val coeffByCol = LinkedHashMap<Int, Long>()
            var constant = 0L
            for (k in literals.indices) {
                val lit = literals[k]
                val w = weights?.get(k) ?: 1L
                val col = builder.boolColumn(Lit.variable(lit))
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
            return BoolReifiedSum(cols, coeffs, constant, lMin, lMax)
        }
    }
}
