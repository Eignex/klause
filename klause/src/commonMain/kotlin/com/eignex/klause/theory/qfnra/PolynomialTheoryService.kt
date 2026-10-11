package com.eignex.klause.theory.qfnra

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal class PolynomialCondition(
    bools: Map<Int, Boolean> = emptyMap(),
    lower: Map<Int, BigFraction> = emptyMap(),
    upper: Map<Int, BigFraction> = emptyMap(),
) {
    val bools = bools.toMap()
    val lower = lower.toMap()
    val upper = upper.toMap()

    fun apply(formula: PolynomialRealFormula, boolCount: Int, realCount: Int, step: () -> Unit): PolynomialRealFormula {
        val terms = ArrayList<PolynomialRealFormula>()
        terms += formula
        for ((id, value) in bools) {
            step()
            require(id in 0 until boolCount) { "conditioned Boolean id out of range" }
            val variable = PolynomialRealFormula.BooleanVariable(SourceBoolId(id))
            terms += if (value) variable else PolynomialRealFormula.Not(variable)
        }
        fun bounds(values: Map<Int, BigFraction>, relation: PolynomialRelation) {
            for ((id, value) in values) {
                step()
                require(id in 0 until realCount) { "conditioned real id out of range" }
                terms += PolynomialRealFormula.Atom(
                    PolynomialExpression.Variable(SourceRealId(id)), relation,
                    PolynomialExpression.Constant.of(value.num, value.den),
                )
            }
        }
        bounds(lower, PolynomialRelation.GE)
        bounds(upper, PolynomialRelation.LE)
        step()
        return PolynomialRealFormula.And(terms)
    }
}

internal class PolynomialTheoryService(
    private val formula: PolynomialRealFormula,
    val boolCount: Int,
    val realCount: Int,
) {
    fun invocation(params: QfNraParams = QfNraParams()): PolynomialTheoryInvocation =
        PolynomialTheoryInvocation(formula, boolCount, realCount, params)
}

internal class PolynomialTheoryInvocation(
    private val formula: PolynomialRealFormula,
    private val boolCount: Int,
    private val realCount: Int,
    private val params: QfNraParams,
) : AutoCloseable {
    private var closed = false
    private var active = false
    var stats = QfNraStats(0, 0, 0)
        private set
    var queries: Long = 0
        private set

    fun check(
        condition: PolynomialCondition = PolynomialCondition(),
        cancellation: Cancellation = Cancellation.Never,
    ): QfNraResult {
        check(!closed) { "polynomial invocation is closed" }
        check(!active) { "polynomial invocation has an active query" }
        active = true
        try {
            val remaining = params.maximumWork?.let { (it - stats.workSpent).coerceAtLeast(0) }
            val result = solveQfNra(
                formula, boolCount, realCount,
                params.copy(cancellation = params.cancellation or cancellation, maximumWork = remaining),
                condition,
            )
            stats = QfNraStats(
                sum(stats.projectedPolynomials, result.stats.projectedPolynomials),
                sum(stats.liftedCells, result.stats.liftedCells),
                sum(stats.workSpent, result.stats.workSpent),
            )
            queries = sum(queries, 1)
            return result
        } finally {
            active = false
        }
    }

    override fun close() {
        check(!active) { "polynomial invocation has an active query" }
        closed = true
    }

    private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
}
