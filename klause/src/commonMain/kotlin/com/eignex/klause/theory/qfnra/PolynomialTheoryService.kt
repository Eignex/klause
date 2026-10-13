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

    override fun equals(other: Any?): Boolean = other is PolynomialCondition &&
        bools == other.bools && lower == other.lower && upper == other.upper

    override fun hashCode(): Int = 31 * (31 * bools.hashCode() + lower.hashCode()) + upper.hashCode()

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
    formula: PolynomialRealFormula,
    val boolCount: Int,
    val realCount: Int,
    fixedReals: Map<Int, BigFraction> = emptyMap(),
) {
    private val fixedReals = fixedReals.toMap()
    val formula: PolynomialRealFormula = if (fixedReals.isEmpty()) formula else PolynomialRealFormula.And(
        listOf(formula) + fixedReals.map { (id, value) ->
            PolynomialRealFormula.Atom(
                PolynomialExpression.Variable(SourceRealId(id)), PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(value.num, value.den),
            )
        },
    )
    fun invocation(params: QfNraParams = QfNraParams()): PolynomialTheoryInvocation =
        PolynomialTheoryInvocation(formula, boolCount, realCount, params, fixedReals)
}

internal class PolynomialTheoryInvocation(
    private val formula: PolynomialRealFormula,
    private val boolCount: Int,
    private val realCount: Int,
    private val params: QfNraParams,
    private val fixedReals: Map<Int, BigFraction>,
) : AutoCloseable {
    private var closed = false
    private var active = false
    private var completedStats = QfNraStats(0, 0, 0)
    private var pending: QfNraQuery? = null
    val stats: QfNraStats get() = pending?.stats?.let { combine(completedStats, it) } ?: completedStats
    var lastUnknown: QfNraResult.Unknown? = null
        private set
    var queries: Long = 0
        private set
    val workExhausted: Boolean get() = params.maximumWork?.let { stats.workSpent >= it } == true

    fun check(
        condition: PolynomialCondition = PolynomialCondition(),
        cancellation: Cancellation = Cancellation.Never,
    ): QfNraResult {
        val query = prepareCheck(condition, cancellation)
        while (true) query.next()?.let { return it }
    }

    fun prepareCheck(
        condition: PolynomialCondition = PolynomialCondition(),
        cancellation: Cancellation = Cancellation.Never,
    ): Iterator<QfNraResult?> {
        check(!closed) { "polynomial invocation is closed" }
        check(!active) { "polynomial invocation has an active query" }
        active = true
        val remaining = params.maximumWork?.let { (it - completedStats.workSpent).coerceAtLeast(0) }
        val query = QfNraQuery(formula, boolCount, realCount,
            params.copy(cancellation = params.cancellation or cancellation, maximumWork = remaining),
            PolynomialCondition(condition.bools, fixedReals + condition.lower, fixedReals + condition.upper))
        pending = query
        queries = sum(queries, 1)
        return object : Iterator<QfNraResult?> {
            private var finished = false
            override fun hasNext(): Boolean = !finished
            @Suppress("TooGenericExceptionCaught") // query failures retain their spent native allowance
            override fun next(): QfNraResult? {
                check(!closed && !finished) { "polynomial query is closed" }
                try {
                    val result = query.next()
                    if (result != null) {
                        completedStats = combine(completedStats, result.stats)
                        lastUnknown = result as? QfNraResult.Unknown
                        finished = true
                        pending = null
                        active = false
                    }
                    return result
                } catch (failure: Throwable) {
                    completedStats = combine(completedStats, query.stats)
                    pending = null
                    active = false
                    finished = true
                    throw failure
                }
            }
        }
    }

    override fun close() {
        pending?.let { completedStats = combine(completedStats, it.stats) }
        pending = null
        active = false
        closed = true
    }

    private fun combine(left: QfNraStats, right: QfNraStats): QfNraStats = QfNraStats(
        sum(left.projectedPolynomials, right.projectedPolynomials),
        sum(left.liftedCells, right.liftedCells), sum(left.workSpent, right.workSpent),
    )

    private fun sum(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
}
