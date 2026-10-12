package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraReason
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.theory.qfnra.QfNraStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus
import kotlin.random.Random

internal enum class NonlinearIntegerStrategy { SEARCH, LOCAL_SEARCH, ALNS, MIXED }

internal data class NonlinearIntegerParams(
    val arithmetic: QfNraParams = QfNraParams(),
    val maximumCandidates: Long = 1024,
    val strategy: NonlinearIntegerStrategy = NonlinearIntegerStrategy.MIXED,
    val seed: Long = 1,
) {
    init { require(maximumCandidates >= 0) }
}

internal data class NonlinearIntegerStats(
    val candidates: Long,
    val moves: Long,
    val repairs: Long,
    val checks: Long,
    val polynomial: QfNraStats,
)

internal data class NonlinearIntegerExecution(val result: QfNraResult, val stats: NonlinearIntegerStats)

internal fun solveNonlinearIntegers(
    problem: NonlinearIntegerProblem,
    params: NonlinearIntegerParams = NonlinearIntegerParams(),
    condition: PolynomialCondition = PolynomialCondition(),
): NonlinearIntegerExecution = NonlinearIntegerSearch(problem, params, condition).solve()

private class NonlinearIntegerSearch(
    private val problem: NonlinearIntegerProblem,
    private val params: NonlinearIntegerParams,
    private var condition: PolynomialCondition,
) {
    private val work = NonlinearIntegerWork(params.arithmetic)
    private val arithmetic = PolynomialArithmetic(work, params.arithmetic.polynomialLimits)
    private val invocation = PolynomialTheoryService(problem.formula, problem.boolCount, problem.numericCount)
        .invocation(params.arithmetic.copy(cancellation = work, maximumWork = null))
    private val random = Random(params.seed)
    private val coordinates = problem.integerCoordinates
    private var current = List(coordinates.size) { BIG_ZERO }
    private var candidates = 0L
    private var moves = 0L
    private var repairs = 0L
    private val visited = HashSet<List<BigInt>>()
    private var hints = emptyList<BigInt>()
    private var cursor = IntArray(coordinates.size)
    private var radius = BIG_ONE
    private var enumeration = emptyList<BigInt>()
    private var representationDecline: QfNraResult.Unknown? = null

    fun solve(): NonlinearIntegerExecution {
        val result = try {
            invocation.use {
                arithmetic.step()
                if (params.maximumCandidates == 0L) return@use unknown()
                condition = problem.integerBounds(condition, arithmetic)
                if (condition.lower.any { (id, value) -> condition.upper[id]?.let { it < value } == true }) {
                    return@use QfNraResult.Unsatisfiable(polynomialStats())
                }
                current = current.mapIndexed(::bounded)
                hints = (sourceIntegerHints(problem.formula, arithmetic) +
                    (condition.lower.values + condition.upper.values).map { it.ceilInteger() }).distinct()
                enumeration = (listOf(BIG_ZERO, BIG_ONE, -BIG_ONE) + hints).distinct()
                val fixedIntegers = coordinates.all {
                    condition.lower[it] != null && condition.lower[it] == condition.upper[it]
                }
                if (params.strategy == NonlinearIntegerStrategy.SEARCH ||
                    params.strategy == NonlinearIntegerStrategy.MIXED || fixedIntegers
                ) {
                    when (val relaxed = completion()) {
                        is QfNraResult.Unsatisfiable -> return@use relaxed
                        is QfNraResult.Unknown -> {
                            if (relaxed.reason != QfNraReason.ResourceLimit || work()) return@use relaxed
                            representationDecline = relaxed
                        }
                        is QfNraResult.Satisfiable -> {
                            current = coordinates.map { integerNear(relaxed.assignment.realValue(it), arithmetic) }
                            enumeration = (current + enumeration).distinct()
                            if (integral(relaxed.assignment) && checked(relaxed.assignment)) return@use relaxed
                        }
                    }
                }
                while (candidates < params.maximumCandidates) {
                    arithmetic.step()
                    val strategy = when (params.strategy) {
                        NonlinearIntegerStrategy.MIXED -> when (candidates % 3) {
                            0L -> NonlinearIntegerStrategy.SEARCH
                            1L -> NonlinearIntegerStrategy.LOCAL_SEARCH
                            else -> NonlinearIntegerStrategy.ALNS
                        }
                        else -> params.strategy
                    }
                    if (strategy == NonlinearIntegerStrategy.SEARCH) current = nextEnumeration()
                    else if (candidates > 0) repair(strategy)
                    val result = checkCandidate()
                    if (result is QfNraResult.Satisfiable || result is QfNraResult.Unknown) return@use result
                }
                unknown()
            }
        } catch (_: PolynomialInterrupted) {
            unknown()
        } catch (stop: PolynomialResourceLimit) {
            QfNraResult.Unknown(QfNraReason.ResourceLimit, polynomialStats(), stop.resource)
        }
        return NonlinearIntegerExecution(
            withStats(result), NonlinearIntegerStats(candidates, moves, repairs, invocation.queries, polynomialStats()),
        )
    }

    private fun checkCandidate(): QfNraResult? {
        arithmetic.step()
        candidates++
        if (!visited.add(current.toList())) return null
        if (current.indices.any { index ->
                val value = BigFraction.of(current[index], BIG_ONE)
                val id = coordinates[index]
                condition.lower[id]?.let { value < it } == true || condition.upper[id]?.let { value > it } == true
            }
        ) return null
        val fixed = coordinates.indices.associate { coordinates[it] to BigFraction.of(current[it], BIG_ONE) }
        return when (val result = completion(fixed)) {
            is QfNraResult.Satisfiable -> {
                check(integral(result.assignment)) { "conditioned nonlinear integer witness is fractional" }
                if (checked(result.assignment)) result else error("nonlinear source witness check failed")
            }
            is QfNraResult.Unknown -> if (result.reason == QfNraReason.ResourceLimit && !work()) {
                representationDecline = result
                null
            } else result
            is QfNraResult.Unsatisfiable -> null
        }
    }

    private fun repair(strategy: NonlinearIntegerStrategy) {
        arithmetic.step()
        val first = ((candidates - 1) % coordinates.size).toInt()
        val released = if (strategy == NonlinearIntegerStrategy.ALNS) {
            repairs++
            setOf(first, (first + 1) % coordinates.size)
        } else setOf(first)
        val fixed = coordinates.indices.filter { it !in released }
            .associate { coordinates[it] to BigFraction.of(current[it], BIG_ONE) }
        when (val guide = completion(fixed)) {
            is QfNraResult.Satisfiable -> {
                current = current.mapIndexed { index, value ->
                    if (index in released) {
                        integerNear(guide.assignment.realValue(coordinates[index]), arithmetic)
                    } else value
                }
            }
            is QfNraResult.Unsatisfiable -> {
                // Candidate windows guide moves; they do not constrain the source's integer domains.
                current = current.mapIndexed { index, value ->
                    if (index in released) hints.getOrNull(random.nextInt(hints.size.coerceAtLeast(1)))
                        ?: (value + if (random.nextBoolean()) radius else -radius)
                    else value
                }
            }
            is QfNraResult.Unknown -> {
                if (work()) throw PolynomialInterrupted()
                if (guide.reason == QfNraReason.ResourceLimit) representationDecline = guide
                current = current.mapIndexed { index, value ->
                    if (index in released) hints.getOrNull(((candidates - 1) % hints.size.coerceAtLeast(1)).toInt())
                        ?: (value + radius)
                    else value
                }
            }
        }
        current = current.mapIndexed(::bounded)
        moves++
        if (visited.contains(current)) {
            current = current.mapIndexed { index, value -> if (index == first) value + radius else value }
            current = current.mapIndexed(::bounded)
            radius += BIG_ONE
        }
    }

    private fun bounded(index: Int, value: BigInt): BigInt {
        val id = coordinates[index]
        val lower = condition.lower[id]?.ceilInteger()
        val upper = condition.upper[id]?.let { -it.negated().ceilInteger() }
        return when {
            lower != null && value < lower -> lower
            upper != null && value > upper -> upper
            else -> value
        }
    }

    private fun nextEnumeration(): List<BigInt> {
        arithmetic.step()
        val value = cursor.map { enumeration[it] }
        var index = cursor.lastIndex
        while (index >= 0) {
            cursor[index]++
            if (cursor[index] < enumeration.size) break
            cursor[index] = 0
            index--
        }
        if (index < 0) {
            radius += BIG_ONE
            enumeration = (enumeration + listOf(radius, -radius)).distinct()
        }
        return value
    }

    private fun completion(fixed: Map<Int, BigFraction> = emptyMap()): QfNraResult {
        fun intersect(bounds: Map<Int, BigFraction>, lower: Boolean): Map<Int, BigFraction> {
            val merged = bounds.toMutableMap()
            for ((id, value) in fixed) {
                arithmetic.step()
                val bound = merged[id]
                merged[id] = if (bound == null || if (lower) value > bound else value < bound) value else bound
            }
            return merged
        }
        return invocation.check(PolynomialCondition(
            condition.bools, intersect(condition.lower, true), intersect(condition.upper, false),
        ))
    }

    private fun integral(assignment: QfNraAssignment): Boolean = coordinates.all {
        arithmetic.step()
        assignment.realValue(it).rational?.den == BIG_ONE
    }

    private fun checked(assignment: QfNraAssignment): Boolean =
        checkNonlinearSource(problem.formula, assignment, arithmetic)
    private fun unknown(): QfNraResult.Unknown =
        representationDecline?.takeIf { !work() }?.copy(stats = polynomialStats()) ?: QfNraResult.Unknown(
        if (work.limitReached || !params.arithmetic.cancellation()) QfNraReason.WorkLimit else QfNraReason.Interrupted,
        polynomialStats(),
    )
    private fun polynomialStats(): QfNraStats = invocation.stats.copy(workSpent = work.spent)
    private fun withStats(result: QfNraResult): QfNraResult = when (result) {
        is QfNraResult.Satisfiable -> result.copy(stats = polynomialStats())
        is QfNraResult.Unsatisfiable -> result.copy(stats = polynomialStats())
        is QfNraResult.Unknown -> result.copy(
            stats = polynomialStats(),
            reason = if (work.limitReached) QfNraReason.WorkLimit else result.reason,
        )
    }
}

private class NonlinearIntegerWork(private val params: QfNraParams) : Cancellation, WorkMeter {
    var spent: Long = 0
        private set
    val limitReached: Boolean get() = params.maximumWork?.let { spent >= it } == true
    override fun isCancelled(): Boolean = limitReached || params.cancellation()
    override fun deadline() = params.cancellation.deadline()
    override fun workMeter(): WorkMeter = this
    override fun charge(units: Long) {
        require(units >= 0)
        spent += minOf(units, Long.MAX_VALUE - spent)
        params.cancellation.charge(units)
    }
}

private fun integerNear(value: AlgebraicReal, arithmetic: PolynomialArithmetic): BigInt {
    value.rational?.let { return it.ceilInteger() }
    val algebraic = AlgebraicArithmetic(arithmetic)
    var lower = value.lowerBound.ceilInteger()
    var upper = value.upperBound.ceilInteger()
    while (lower < upper) {
        arithmetic.step()
        val middle = -BigFraction.of(-(lower + upper), BIG_TWO).ceilInteger()
        if (algebraic.compare(value, algebraic.rational(BigFraction.of(middle, BIG_ONE))) <= 0) upper = middle
        else lower = middle + BIG_ONE
    }
    return lower
}

private fun sourceIntegerHints(formula: PolynomialRealFormula, arithmetic: PolynomialArithmetic): List<BigInt> {
    val hints = linkedSetOf<BigInt>()
    val formulas = ArrayDeque<PolynomialRealFormula>()
    val expressions = ArrayDeque<PolynomialExpression>()
    val seenFormulas = HashSet<PolynomialRealFormula>()
    val seenExpressions = HashSet<PolynomialExpression>()
    formulas.addLast(formula)
    while (formulas.isNotEmpty()) {
        arithmetic.step()
        val node = formulas.removeLast()
        if (!seenFormulas.add(node)) continue
        when (node) {
            is PolynomialRealFormula.Atom -> {
                expressions.addLast(node.left)
                expressions.addLast(node.right)
            }
            is PolynomialRealFormula.And -> formulas.addAll(node.operands)
            is PolynomialRealFormula.Or -> formulas.addAll(node.operands)
            is PolynomialRealFormula.Not -> formulas.addLast(node.operand)
            else -> Unit
        }
    }
    while (expressions.isNotEmpty()) {
        arithmetic.step()
        val node = expressions.removeLast()
        if (!seenExpressions.add(node)) continue
        when (node) {
            is PolynomialExpression.Constant -> {
                val value = BigFraction.of(node.numerator, node.denominator).ceilInteger()
                hints.add(value)
                hints.add(value - BIG_ONE)
                hints.add(value + BIG_ONE)
                hints.add(-value)
            }
            is PolynomialExpression.Sum -> expressions.addAll(node.operands)
            is PolynomialExpression.Product -> expressions.addAll(node.operands)
            is PolynomialExpression.Power -> expressions.addLast(node.base)
            is PolynomialExpression.Variable -> Unit
        }
    }
    return hints.toList()
}
