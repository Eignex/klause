package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.asFraction
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.simplex.exact.BigRationalConflict
import com.eignex.klause.simplex.exact.ExactRationalFeasibilityModel
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.SearchExplanation

internal class QfLraSystem(private val model: Problem) {
    fun build(booleanValue: (Int) -> Boolean?): QfLraRelaxation {
        val rows = ArrayList<ExactRationalInequality>()
        for (integer in 0 until model.numIntVars) {
            val column = model.numRealVars + integer
            model.intBounds.lowerAsBigInteger(integer)?.let { rows += exactColumnLower(column, it.asFraction()) }
            model.intBounds.upperAsBigInteger(integer)?.let { rows += exactColumnUpper(column, it.asFraction()) }
        }
        for (real in 0 until model.numRealVars) {
            model.realLower[real].takeIf(Double::isFinite)?.let { rows += exactColumnLower(real, it.asFraction()) }
            model.realUpper[real].takeIf(Double::isFinite)?.let { rows += exactColumnUpper(real, it.asFraction()) }
        }
        val premises = MutableList(rows.size) { intArrayOf() }
        for (factor in model.factors) {
            // A private choice is not a premise expressible by a source Boolean clause.
            if (factor.linearForm is LinearForm.Disjunction) continue
            for (row in factor.linearRows) {
                val variables = row.booleanVariables()
                if (variables.any { booleanValue(it) == null }) continue
                val truth = row.activator == LinearRow.ALWAYS || booleanValue(row.activator) == true
                val comparison = row.exactComparison(model.numRealVars, truth) { booleanValue(it) == true }
                if (comparison.op == LinearOp.NE) continue
                val start = rows.size
                comparison.rowsInto(rows)
                val literals = variables.map { Lit.make(it, positive = booleanValue(it) != true) }.toIntArray()
                repeat(rows.size - start) { premises.add(literals) }
            }
        }
        val columns = model.numRealVars + model.numIntVars
        return QfLraRelaxation(
            ExactRationalFeasibilityModel(2 * columns, rows.map { it.overFreeColumns(columns) }),
            premises,
        )
    }
}

internal class QfLraRelaxation(val model: ExactRationalFeasibilityModel, private val rowPremises: List<IntArray>) {
    fun explanation(conflict: BigRationalConflict?): SearchExplanation? {
        if (conflict == null) return null
        // Split columns have only their intrinsic nonnegative lower bound; every source bound is a row.
        if (conflict.bounds.any { it.column < model.n && it.upper }) return null
        return SearchExplanation(
            conflict.rows.flatMap { rowPremises[it].asIterable() }.distinct().sorted().toIntArray(),
        )
    }
}

private fun LinearRow.booleanVariables(): Set<Int> = buildSet {
    if (activator != LinearRow.ALWAYS) add(activator)
    for (k in 0 until this@booleanVariables.size) {
        if (Term.isBool(ref(k))) add(Lit.variable(Term.lit(ref(k))))
    }
}
