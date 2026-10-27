package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.RationalInterval
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.polynomial.RationalPolynomialArithmetic
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_TWO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus

internal data class NonlinearIntegerRootCounts(
    val admitted: Long = 0,
    val declined: Long = 0,
    val reused: Long = 0,
    val refuted: Long = 0,
    val excluded: Long = 0,
) {
    operator fun plus(other: NonlinearIntegerRootCounts): NonlinearIntegerRootCounts = NonlinearIntegerRootCounts(
        admitted + other.admitted, declined + other.declined, reused + other.reused,
        refuted + other.refuted, excluded + other.excluded,
    )
}

internal class NonlinearIntegerRootAdmission(
    val roots: Map<Int, List<BigInt>>,
    val admitted: Long,
    val declined: Long,
    val fresh: Boolean,
)

// Admitted once per source; every cursor of the owning invocation reuses the completed root sets.
internal class NonlinearIntegerRootStore(val problem: NonlinearIntegerProblem) {
    private var completed: Map<Int, List<BigInt>>? = null

    fun admit(parent: Cancellation, limits: PolynomialLimits, step: () -> Unit): NonlinearIntegerRootAdmission {
        completed?.let { roots ->
            repeat(roots.size) { step() }
            return NonlinearIntegerRootAdmission(roots, 0, 0, false)
        }
        var equationWork = 0L
        var totalWork = 0L
        var parentStopped = false
        val meter = WorkMeter {
            equationWork += it
            totalWork += it
            parent.charge(it)
        }
        // A private allowance declines one expensive equation without spending the containing search.
        val token = object : Cancellation {
            override fun isCancelled(): Boolean {
                if (parent()) parentStopped = true
                return parentStopped || equationWork >= MAX_EQUATION_WORK || totalWork >= MAX_ADMISSION_WORK
            }
            override fun deadline() = parent.deadline()
            override fun workMeter(): WorkMeter = meter
        }
        val arithmetic = PolynomialArithmetic(token, limits)
        val roots = HashMap<Int, List<BigInt>>()
        var admitted = 0L
        var declined = 0L
        for ((variable, equation) in unaryEquations(step)) {
            equationWork = 0
            try {
                val found = integerRoots(equation, variable, arithmetic)
                if (found == null) {
                    declined++
                    continue
                }
                roots[variable] = roots[variable]?.let { old ->
                    old.filter { value ->
                        arithmetic.step()
                        value in found
                    }
                } ?: found
                admitted++
            } catch (stop: PolynomialInterrupted) {
                if (parentStopped) throw stop
                declined++
            } catch (_: PolynomialResourceLimit) {
                declined++
            }
        }
        completed = roots
        return NonlinearIntegerRootAdmission(roots, admitted, declined, true)
    }

    private fun unaryEquations(step: () -> Unit): List<Pair<Int, PolynomialRealFormula.Atom>> {
        val integers = problem.integerCoordinates.toSet()
        val equations = ArrayList<Pair<Int, PolynomialRealFormula.Atom>>()
        val work = ArrayDeque<PolynomialRealFormula>()
        work.addLast(problem.formula)
        while (work.isNotEmpty()) {
            step()
            when (val node = work.removeLast()) {
                is PolynomialRealFormula.And -> work.addAll(node.operands)
                is PolynomialRealFormula.Atom -> if (node.relation == PolynomialRelation.EQ) {
                    unaryVariable(node, step)?.takeIf { it in integers }?.let { equations.add(it to node) }
                }
                else -> Unit
            }
        }
        return equations
    }

    private fun unaryVariable(atom: PolynomialRealFormula.Atom, step: () -> Unit): Int? {
        var variable: Int? = null
        val seen = HashSet<PolynomialExpression>()
        val work = ArrayDeque<PolynomialExpression>()
        work.addLast(atom.left)
        work.addLast(atom.right)
        while (work.isNotEmpty()) {
            step()
            val node = work.removeLast()
            if (!seen.add(node)) continue
            when (node) {
                is PolynomialExpression.Variable -> {
                    if (variable != null && variable != node.id.index) return null
                    variable = node.id.index
                }
                is PolynomialExpression.Sum -> work.addAll(node.operands)
                is PolynomialExpression.Product -> work.addAll(node.operands)
                is PolynomialExpression.Power -> work.addLast(node.base)
                is PolynomialExpression.Constant -> Unit
            }
        }
        return variable
    }

    // Null declines a constant difference, including complete cancellation.
    private fun integerRoots(
        atom: PolynomialRealFormula.Atom,
        variable: Int,
        arithmetic: PolynomialArithmetic,
    ): List<BigInt>? {
        arithmetic.step()
        val univariate = RationalPolynomialArithmetic(arithmetic)
        val expanded = HashMap<PolynomialExpression, RationalPolynomial>()
        val polynomial = univariate.add(
            expand(atom.left, variable, univariate, expanded),
            univariate.scale(expand(atom.right, variable, univariate, expanded), BigFraction.MINUS_ONE),
        )
        if (polynomial.degree <= 0) return null
        val squareFree = univariate.squareFree(polynomial)
        return univariate.isolate(squareFree).mapNotNull { integerRoot(squareFree, it, univariate) }
    }

    // A nonpoint isolating interval holds one simple root strictly inside, so its sign change brackets the
    // root on the integer grid within a number of exact evaluations logarithmic in the interval width.
    private fun integerRoot(
        polynomial: RationalPolynomial,
        interval: RationalInterval,
        univariate: RationalPolynomialArithmetic,
    ): BigInt? {
        if (interval.isPoint) return interval.lower.takeIf { it.den == BIG_ONE }?.num
        val lowerSign = univariate.evaluate(polynomial, interval.lower).signum()
        var low = -interval.lower.negated().ceilInteger() + BIG_ONE
        var high = interval.upper.ceilInteger() - BIG_ONE
        while (low <= high) {
            univariate.arithmetic.step()
            val middle = BigFraction.of(low + high, BIG_TWO).ceilInteger()
            val sign = univariate.evaluate(polynomial, BigFraction.of(middle, BIG_ONE)).signum()
            when {
                sign == 0 -> return middle
                sign == lowerSign -> low = middle + BIG_ONE
                else -> high = middle - BIG_ONE
            }
        }
        return null
    }

    private fun expand(
        root: PolynomialExpression,
        variable: Int,
        univariate: RationalPolynomialArithmetic,
        expanded: HashMap<PolynomialExpression, RationalPolynomial>,
    ): RationalPolynomial {
        val arithmetic = univariate.arithmetic
        val pending = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
        pending.addLast(root to false)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val (node, combine) = pending.removeLast()
            if (node in expanded) continue
            val children = when (node) {
                is PolynomialExpression.Sum -> node.operands
                is PolynomialExpression.Product -> node.operands
                is PolynomialExpression.Power -> listOf(node.base)
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                pending.addLast(node to true)
                for (child in children.asReversed()) pending.addLast(child to false)
                continue
            }
            expanded[node] = when (node) {
                is PolynomialExpression.Constant ->
                    RationalPolynomial(listOf(BigFraction.of(node.numerator, node.denominator)))
                is PolynomialExpression.Variable -> {
                    check(node.id.index == variable) { "unary equation names another coordinate" }
                    RationalPolynomial.X
                }
                is PolynomialExpression.Sum -> children.fold(RationalPolynomial.ZERO) { value, child ->
                    univariate.add(value, expanded.getValue(child))
                }
                is PolynomialExpression.Product -> children.fold(RationalPolynomial.ONE) { value, child ->
                    univariate.multiply(value, expanded.getValue(child))
                }
                is PolynomialExpression.Power -> {
                    var result = RationalPolynomial.ONE
                    var base = expanded.getValue(node.base)
                    var remaining = node.exponent
                    while (remaining != 0) {
                        arithmetic.step()
                        if (remaining and 1 != 0) result = univariate.multiply(result, base)
                        remaining = remaining ushr 1
                        if (remaining != 0) base = univariate.multiply(base, base)
                    }
                    result
                }
            }
        }
        return expanded.getValue(root)
    }
}

private const val MAX_EQUATION_WORK = 65_536L
private const val MAX_ADMISSION_WORK = 262_144L
