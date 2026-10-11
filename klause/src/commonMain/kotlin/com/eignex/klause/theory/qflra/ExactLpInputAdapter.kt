package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpPremises
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.util.BIG_ONE

// Project the exact theory source declaration without crossing through Double.
internal fun QfLraRelaxation.toExactLpModel(): ExactLpModel = exactLpModel(sourceColumns, sourceRows)

internal fun exactLpModel(sourceColumns: List<ExactLpSourceColumn>, sourceRows: List<ExactLpSourceRow>): ExactLpModel {
    val zero = ExactLpNumber.of(0L)
    val columns = sourceColumns.map { column ->
        ExactLpColumn(
            ExactLpBounds(
                column.lower?.toExactLpNumber()?.let(::ExactLpSide),
                column.upper?.toExactLpNumber()?.let(::ExactLpSide),
            ),
            column.origin.toExactLpNumber(),
            column.integral,
            column.tag,
        )
    }.toMutableList()
    val matrix = List(sourceColumns.size) { ArrayList<ExactLpEntry>() }
    val rhs = ArrayList<ExactLpNumber>(sourceRows.size)
    val rows = ArrayList<ExactLpRow>(sourceRows.size)
    for ((rowIndex, sourceRow) in sourceRows.withIndex()) {
        val inequality = sourceRow.inequality
        var bound = inequality.rhs
        for (entry in inequality.columns.indices) {
            val column = inequality.columns[entry]
            val coefficient = inequality.coefficients[entry]
            bound -= coefficient * sourceColumns[column].origin.value
            if (!coefficient.isZero) {
                matrix[column].add(ExactLpEntry(rowIndex, ExactLpNumber.of(coefficient)))
            }
        }
        val premises = sourceRow.premiseLiterals.takeIf { it.isNotEmpty() }
            ?.let { ExactLpPremises(emptyList(), it) }
        rows.add(
            ExactLpRow(
                global = premises == null,
                strict = inequality.strict,
                premises = premises,
            ),
        )
        rhs.add(ExactLpNumber.of(bound))
        val integralSlack = inequality.rhs.den == BIG_ONE &&
            inequality.columns.indices.all { entry ->
                sourceColumns[inequality.columns[entry]].integral &&
                    inequality.coefficients[entry].den == BIG_ONE
            }
        columns.add(ExactLpColumn(ExactLpBounds(lower = ExactLpSide(zero)), integral = integralSlack))
    }
    return ExactLpModel(
        matrix,
        rhs,
        columns,
        rows,
        ExactLpObjective(List(columns.size) { zero }),
    )
}

private fun ExactLpSourceNumber.toExactLpNumber(): ExactLpNumber = ieeeBits?.let {
    ExactLpNumber.ofIeee(Double.fromBits(it))
} ?: ExactLpNumber.of(value)
