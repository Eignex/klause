package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.toLongExact

internal class FixedSelectorDisjunction(
    val factor: Int,
    val row: Int,
    private val variable: Int,
    private val value: Long,
) {
    fun premise(context: SearchContext): SearchAtomPremise? {
        if (context.intLowerBound(variable) != value || context.intUpperBound(variable) != value) return null
        val lower = context.intLowerBoundPremise(variable)
        val upper = context.intUpperBoundPremise(variable)
        if (lower === SearchAtomPremise.Unavailable || upper === SearchAtomPremise.Unavailable) return null
        return SearchAtomPremise.All(listOf(lower, upper))
    }

    companion object {
        fun prepare(model: Problem, stop: Cancellation): List<FixedSelectorDisjunction>? {
            if (stop()) return null
            stop.charge(4L)
            if (stop()) return null
            val selected = ArrayList<FixedSelectorDisjunction>()
            for ((factor, source) in model.factors.withIndex()) {
                if (stop()) return null
                stop.charge(1L)
                if (stop()) return null
                val form = source.linearForm as? LinearForm.Disjunction ?: continue
                if (form.rows.size != 2) continue
                for ((index, row) in form.rows.withIndex()) {
                    stop.charge(5L)
                    if (stop()) return null
                    val candidate = fromRow(row, factor, index, model.numIntVars, stop)
                    if (stop()) return null
                    if (candidate != null) selected += candidate
                }
            }
            stop.charge(selected.size.toLong() + 1L)
            return if (stop()) null else selected.toList()
        }

        private fun fromRow(
            row: LinearRow,
            factor: Int,
            index: Int,
            integers: Int,
            stop: Cancellation,
        ): FixedSelectorDisjunction? {
            val constants = row.constants as? IntegralConstants ?: return null
            if (row.relation != LinearOp.NE || row.strict || row.activator != LinearRow.ALWAYS ||
                row.size != 1 || !Term.isInt(row.ref(0))
            ) return null
            val variable = Term.intVar(row.ref(0))
            if (variable !in 0 until integers) return null
            val coefficient = constants.exactCoeff(0)
            val bound = constants.exactBound
            stop.charge(3L + coefficient.magnitudeBitLength().toLong() / Long.SIZE_BITS +
                bound.magnitudeBitLength().toLong() / Long.SIZE_BITS)
            if (stop() || coefficient != BIG_ONE || !bound.fitsLong()) return null
            return FixedSelectorDisjunction(factor, 1 - index, variable, bound.toLongExact())
        }
    }
}
