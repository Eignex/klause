package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.indices
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactRowForm
import com.eignex.klause.lp.exactForm
import com.eignex.klause.solver.search.SearchModel
import com.eignex.klause.util.ArithmeticExecution
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.PollStride
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.minOf
import com.eignex.util.numbers.minus

internal class PreparedExactLiraComponentFactory(
    private val openComponent: (((ExactLiraAssignment, SearchModel) -> Unit)?, IntArray) -> ExactLiraSearchComponent,
) {
    fun open(
        modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
    ): ExactLiraSearchComponent = openComponent(modelContribution, intArrayOf())

    fun open(
        finiteIntegerVariables: IntArray,
        modelContribution: ((ExactLiraAssignment, SearchModel) -> Unit)? = null,
    ): ExactLiraSearchComponent = openComponent(modelContribution, finiteIntegerVariables.copyOf())
}

internal fun prepareExactLiraSource(
    fragment: Problem,
    stop: ExecutionControl,
): PreparedExactLiraComponentFactory? = ExactLiraSearchComponent.prepareSource(fragment, stop)

internal class PreparedExactLiraSource(
    val model: Problem,
    val exactForms: List<List<ExactRowForm>>,
    val preparedRows: List<PreparedSourceRow>,
    val arithmeticRows: List<LinearRow>,
    val arithmeticVariables: Set<Int>,
    val validated: Boolean,
)

internal fun legacyExactLiraSource(model: Problem): PreparedExactLiraSource {
    val exactForms = model.factors.map { factor -> factor.linearRows.map { it.exactForm(model.numRealVars) } }
    val preparedRows = model.factors.flatMapIndexed { factorIndex, factor ->
        factor.linearRows.mapIndexed { index, row ->
            PreparedSourceRow(
                RowAddress(factorIndex, index),
                row,
                exactForms[factorIndex][index],
                row.booleanVariables().toIntArray(),
                factor.linearForm is LinearForm.Disjunction,
            )
        }
    }
    val arithmeticRows = model.factors.flatMap { it.linearRows }.filter { row ->
        (0 until row.size).any { !Term.isBool(row.ref(it)) }
    }
    val arithmeticVariables = arithmeticRows.flatMap { it.booleanVariables() }.toSet()
    return PreparedExactLiraSource(
        model, exactForms, preparedRows, arithmeticRows, arithmeticVariables, validated = false,
    )
}

internal fun sharedExactLiraSource(model: Problem, stop: ExecutionControl): PreparedExactLiraSource? {
    val execution = ArithmeticExecution(stop) { throw LinearPreparationInterrupted() }
    return try {
        execution.operation { sharedExactLiraSourceWithin(model, stop, execution) }
    } catch (_: LinearPreparationInterrupted) {
        null
    }
}

private class LinearPreparationInterrupted : RuntimeException()

private fun sharedExactLiraSourceWithin(
    model: Problem,
    stop: ExecutionControl,
    execution: ArithmeticExecution): PreparedExactLiraSource? {
    fun charge(units: Long) {
        var remaining = units
        while (remaining > 0L) {
            val count = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            execution.advance(count) {}
            remaining -= count
        }
    }
    charge(1)
    if (model.numIntVars == 0 && model.numRealVars == 0) return null
    val exactForms = ArrayList<List<ExactRowForm>>()
    val preparedRows = ArrayList<PreparedSourceRow>()
    val arithmeticRows = ArrayList<LinearRow>()
    val arithmeticVariables = LinkedHashSet<Int>()
    val rows = PollStride(calls = 64)
    for ((factorIndex, factor) in model.factors.withIndex()) {
        charge(1)
        val form = factor.linearForm
        if (form !is LinearForm.Conjunction && form !is LinearForm.Disjunction) return null
        val forms = ArrayList<ExactRowForm>()
        for ((rowIndex, row) in factor.linearRows.withIndex()) {
            charge(1)
            when (val constants = row.constants) {
                is IntegralConstants -> Unit
                is RealConstants -> {
                    if (!constants.bound.isFinite()) return null
                    for (index in constants.intCoefficients.indices) {
                        charge(1)
                        if ( !constants.intCoefficients.at(index).isFinite()) return null
                    }
                    for (index in constants.realCoefficients.indices) {
                        charge(1)
                        if ( !constants.realCoefficients.at(index).isFinite()) return null
                    }
                }
            }
            val booleans = LinkedHashSet<Int>()
            if (row.activator != LinearRow.ALWAYS) booleans += row.activator
            var arithmetic = false
            for (index in 0 until row.size) {
                charge(1)
                val reference = row.ref(index)
                if (Term.isBool(reference)) {
                    booleans += Lit.variable(Term.lit(reference))
                } else {
                    arithmetic = true
                }
            }
            // Exact rational conversion and its owned term buffers are one atomic row operation.
            charge(4L * row.size + 4L)
            val exact = row.exactForm(model.numRealVars)
            if (rows.due() && stop()) return null
            forms += exact
            preparedRows += PreparedSourceRow(
                RowAddress(factorIndex, rowIndex), row, exact, booleans.toIntArray(), form is LinearForm.Disjunction,
            )
            if (arithmetic) {
                arithmeticRows += row
                for (variable in booleans) {
                    charge(1)
                    arithmeticVariables += variable
                }
            }
        }
        charge(forms.size.toLong() + 1L)
        exactForms += forms.toList()
    }
    val copies = exactForms.size.toLong() + preparedRows.size + arithmeticRows.size + arithmeticVariables.size + 1L
    charge(copies)
    val source = PreparedExactLiraSource(
        model, exactForms.toList(), preparedRows.toList(), arithmeticRows.toList(), arithmeticVariables.toSet(),
        validated = true,
    )
    return source.takeUnless { stop() }
}
