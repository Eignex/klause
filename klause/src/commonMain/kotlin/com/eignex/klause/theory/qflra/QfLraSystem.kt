package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.ExactRationalFeasibilityModel
import com.eignex.klause.simplex.exact.ExactRationalInequality

internal class QfLraSystem(private val model: Problem) {
    fun build(bools: BooleanArray): ExactRationalFeasibilityModel {
        val rows = ArrayList<ExactRationalInequality>()
        for (real in 0 until model.numRealVars) {
            model.realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
            model.realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
        }
        for (factor in model.factors) {
            for (row in factor.linearRows) {
                val truth = row.activator == LinearRow.ALWAYS || bools[row.activator]
                row.exactComparison(model.numRealVars, truth) { bools[it] }.rowsInto(rows)
            }
        }
        return ExactRationalFeasibilityModel(2 * model.numRealVars, rows.map { it.overFreeColumns(model.numRealVars) })
    }
}
