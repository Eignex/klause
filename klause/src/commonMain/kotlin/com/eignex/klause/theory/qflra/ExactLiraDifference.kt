package com.eignex.klause.theory.qflra

import com.eignex.klause.arithmetic.difference.DifferenceGraph
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomPremise
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.explainAtoms
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.toLong

internal class ExactLiraDifference(private val model: Problem, forms: List<List<ExactRowForm>>) {
    private val graph = DifferenceGraph(model.numIntVars + 1)
    private val guards = ArrayList<Int>()
    private var checkedActive: BooleanArray? = null
    private val zero = model.numIntVars
    private val room = bigIntOf(Long.MAX_VALUE / (8L * (model.numIntVars.toLong() + 2L)))

    init {
        if (model.numRealVars == 0) {
            for (integer in 0 until model.numIntVars) {
                model.intBounds.upperAsBigInteger(integer)?.let { append(zero, integer, it, ALWAYS) }
                model.intBounds.lowerAsBigInteger(integer)?.let { append(integer, zero, it.negate(), ALWAYS) }
            }
            model.factors.forEachIndexed { factorIndex, factor ->
                if (factor.linearForm !is LinearForm.Disjunction) {
                    factor.linearRows.forEachIndexed { rowIndex, row ->
                        if ((0 until row.size).none { Term.isBool(row.ref(it)) }) {
                            append(row, forms[factorIndex][rowIndex], true)
                            if (row.activator != LinearRow.ALWAYS) append(row, forms[factorIndex][rowIndex], false)
                        }
                    }
                }
            }
        }
    }

    fun propagate(context: SearchContext, stop: Cancellation): ComponentResult {
        if (stop()) return ComponentResult.Indeterminate
        if (guards.isEmpty()) return ComponentResult.Consistent
        val active = BooleanArray(guards.size) { index ->
            val guard = guards[index]
            guard == ALWAYS || context.boolValue(Lit.variable(guard)) == Lit.isPositive(guard)
        }
        if (checkedActive?.contentEquals(active) == true) {
            return if (stop()) ComponentResult.Indeterminate else ComponentResult.Consistent
        }
        var abandoned = false
        val cycle = graph.negativeCycle(active) { stop().also { abandoned = abandoned || it } }
        if (abandoned || stop()) return ComponentResult.Indeterminate
        if (cycle == null) {
            checkedActive = active
            return ComponentResult.Consistent
        }
        val premises = cycle.map { guards[it] }.filter { it != ALWAYS }.distinct().map {
            SearchAtomPremise.Asserted(SearchDecision.Bool(it))
        }
        return ComponentResult.Conflict(context.explainAtoms(SearchAtomPremise.All(premises)))
    }

    private fun append(row: LinearRow, form: ExactRowForm, truth: Boolean) {
        val comparison = form.comparison(truth) { false }
        if (comparison.op == LinearOp.NE) return
        val inequalities = ArrayList<ExactRationalInequality>(2)
        comparison.rowsInto(inequalities)
        val guard = if (row.activator == LinearRow.ALWAYS) ALWAYS else Lit.make(row.activator, truth)
        for (inequality in inequalities) {
            val columns = inequality.columns
            val coefficients = inequality.coefficients
            if (columns.isEmpty() || columns.size > 2) continue
            if (columns.size == 2 && coefficients[0] != coefficients[1].negated()) continue
            val positive = coefficients[0].signum() > 0
            val first = columns[0]
            val second = columns.getOrNull(1) ?: zero
            val target = if (positive) first else second
            val source = if (positive) second else first
            val magnitude = if (positive) coefficients[0] else coefficients[0].negated()
            if (magnitude.isZero) continue
            val bound = inequality.rhs * magnitude.reciprocal()
            var rounded = bound.negated().ceilInteger().negate()
            if (inequality.strict && bound.den == BIG_ONE) rounded -= BIG_ONE
            append(source, target, rounded, guard)
        }
    }

    private fun append(source: Int, target: Int, bound: BigInt, guard: Int) {
        // This graph is redundant; omitting a wide row weakens it without changing the source theory.
        if (bound < room.negate() || bound > room) return
        graph.addEdge(source, target, bound.toLong())
        guards += guard
    }

    private companion object {
        const val ALWAYS = -1
    }
}
