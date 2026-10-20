package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.ExactColumnValues
import com.eignex.klause.lp.ExactComparison
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal class PartialDifferenceRelaxation private constructor(
    private val columns: Int,
    private val rows: List<ExactRationalInequality>,
    private val comparisons: List<ExactComparison>,
) {
    fun check(stop: Cancellation): Result {
        if (stop()) return Result.Interrupted
        val graph = RealDifferenceSystem.prepareRows(columns, rows, stop)
            ?: return if (stop()) Result.Interrupted else Result.Declined
        return when (val result = graph.check(intArrayOf(), stop, stop::charge)) {
            RealDifferenceSystem.Result.Interrupted -> Result.Interrupted
            is RealDifferenceSystem.Result.Infeasible -> Result.Declined
            is RealDifferenceSystem.Result.Feasible -> {
                stop.charge(columns.toLong() + 1L)
                if (stop()) return Result.Interrupted
                val point = result.point
                val values = object : ExactColumnValues {
                    override fun at(column: Int): BigFraction = point[column]
                }
                for (comparison in comparisons) {
                    stop.charge(comparison.terms.size.toLong() + 1L)
                    if (stop()) return Result.Interrupted
                    if (!comparison.holdsAt(values)) return if (stop()) Result.Interrupted else Result.Declined
                }
                if (stop()) Result.Interrupted else Result.Feasible(point)
            }
        }
    }

    sealed interface Result {
        class Feasible(val point: List<BigFraction>) : Result
        data object Declined : Result
        data object Interrupted : Result
    }

    companion object {
        fun prepare(
            columns: Int,
            comparisons: List<ExactComparison>,
            stop: Cancellation,
        ): PartialDifferenceRelaxation? {
            if (columns !in 1..10000 || comparisons.size > 100000 || stop()) return null
            val rows = ArrayList<ExactRationalInequality>()
            for (comparison in comparisons) {
                stop.charge(comparison.terms.size.toLong() + 1L)
                if (stop() || comparison.terms.size > 2 ||
                    comparison.terms.keys.any { it !in 0 until columns }
                ) return null
                val coefficients = comparison.terms.values
                if (coefficients.any { it.isZero } ||
                    (coefficients.size == 2 && coefficients.first() != coefficients.last().negated())
                ) return null
                // A graph point must satisfy each disequality without choosing a private side.
                if (comparison.op == LinearOp.NE) continue
                val added = if (comparison.op == LinearOp.EQ) 2 else 1
                if (rows.size > 100000 - added) return null
                comparison.rowsInto(rows)
            }
            stop.charge(comparisons.size.toLong() + 1L)
            if (stop()) return null
            return PartialDifferenceRelaxation(columns, rows, comparisons.toList())
        }
    }
}
