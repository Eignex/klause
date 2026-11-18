package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpProjectionMeter
import com.eignex.klause.lp.engine.LpRowPremises
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal class ExactRelaxationRows {
    private class Row(val columns: IntArray, val coefficients: List<BigFraction>, val rhs: BigFraction)
    private val rows = LinkedHashMap<Int, Row>()
    private val slacks = LinkedHashMap<Int, LpRowSlack>()

    fun carrySlack(row: Int, slack: LpRowSlack) {
        slacks[row] = slack
    }

    fun add(
        builder: LpBuilder,
        columns: IntArray,
        coefficients: List<BigFraction>,
        relation: Relation,
        rhs: BigFraction,
        premises: LpRowPremises?,
    ) {
        val negate = relation == Relation.GE
        rows[builder.rowCount] = Row(columns.copyOf(),
            if (negate) coefficients.map { it.negated() } else coefficients.toList(),
            if (negate) rhs.negated() else rhs)
        builder.addRealRow(columns, coefficients.map { it.toDouble() }.toDoubleArray(), relation, rhs.toDouble(),
            validity = premises)
    }

    fun project(model: LpModel, cancellation: ExecutionControl): LpModel? {
        if (rows.isEmpty() && slacks.isEmpty()) return model
        val original = model.authoritativeModel() ?: return null
        val metadata = List(model.m) { index ->
            val row = original.row(index)
            slacks[index]?.let { row.copy(slack = it) } ?: row
        }
        if (rows.isEmpty()) {
            return LpExactState(original.copy(rows = metadata))
                .toWorkingModel(LpProjectionMeter(cancellation = cancellation))
        }
        val matrix = List(model.n) { column ->
            original.entries(column).filter { it.row !in rows }.toMutableList()
        }
        val rhs = MutableList(model.m) { original.rhs(it) }
        for ((index, row) in rows) {
            if (cancellation()) return null
            var shifted = row.rhs
            val summed = LinkedHashMap<Int, BigFraction>()
            for (entry in row.columns.indices) {
                val column = row.columns[entry]
                val coefficient = row.coefficients[entry]
                summed[column] = (summed[column] ?: BigFraction.ZERO) + coefficient
                shifted -= coefficient * original.column(column).origin.value
            }
            for ((column, coefficient) in summed) {
                if (!coefficient.isZero) matrix[column].add(ExactLpEntry(index, ExactLpNumber.of(coefficient)))
            }
            rhs[index] = ExactLpNumber.of(shifted)
        }
        val exact = ExactLpModel(
            matrix.map { column -> column.sortedBy { it.row } }, rhs,
            List(model.numVars) { column ->
                val source = original.column(column)
                if (column - model.n in rows) source.copy(integral = false) else source
            },
            metadata, original.objective,
        )
        return LpExactState(exact).toWorkingModel(LpProjectionMeter(cancellation = cancellation))
    }
}
