package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResource
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.quadratic.QuadraticControl
import com.eignex.klause.arithmetic.quadratic.QuadraticInterrupted
import com.eignex.klause.arithmetic.quadratic.QuadraticStop
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialObjectiveResult
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.solvePolynomialObjectiveImage
import com.eignex.klause.theory.qfnra.tryConvexPolynomialOptimum
import com.eignex.klause.theory.qfnra.polynomialPointFixings
import com.eignex.klause.theory.qfnra.PolynomialIntegerRecession
import com.eignex.klause.theory.qfnra.tryPolynomialIntegerRecession
import com.eignex.klause.util.Cancellation

internal sealed interface QuadraticRelaxationResult {
    data class Minimum(
        val assignment: QfNraAssignment,
        val value: AlgebraicReal,
        val attained: Boolean,
        val proof: Any,
    ) : QuadraticRelaxationResult
    data class Unbounded(val assignment: QfNraAssignment, val proof: Any) : QuadraticRelaxationResult
    data class Infeasible(val condition: PolynomialCondition) : QuadraticRelaxationResult
    data class Unknown(val resource: PolynomialResource? = null) : QuadraticRelaxationResult
}

internal class QuadraticSourceRelaxation(
    private val formula: PolynomialRealFormula,
    private val objective: PolynomialExpression,
    private val boolCount: Int,
    private val numericCount: Int,
    private val params: PolynomialTheoryParams,
    private val native: QuadraticNativeScope,
) {
    private val limits = PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells)
    private var operation: Cancellation = Cancellation.Never
    private fun token(parent: Cancellation): Cancellation = object : Cancellation {
        override fun isCancelled(): Boolean = parent() || operation()
        override fun workMeter() = parent.workMeter()
        override fun deadline() = parent.deadline()?.let { outer ->
            operation.deadline()?.let { inner -> minOf(outer, inner) } ?: outer
        } ?: operation.deadline()
    }
    private val polynomial = token(native.polynomial)
    private val numerical = token(native.numerical)
    val arithmetic = PolynomialArithmetic(polynomial, limits)
    val algebraic = AlgebraicArithmetic(arithmetic)
    private var fixedPoints: Map<Int, BigFraction>? = null

    fun integerRecession(
        condition: PolynomialCondition,
        assignment: QfNraAssignment,
        integers: List<Int>,
        cancellation: Cancellation,
    ): PolynomialIntegerRecession? {
        operation = cancellation
        return try {
            val conditioned = condition.apply(formula, boolCount, numericCount, arithmetic::step)
            tryPolynomialIntegerRecession(
                conditioned, objective, assignment, integers, arithmetic,
                QuadraticControl(numerical, maximumMatrixCells = params.maximumMatrixCells),
            )
        } catch (_: QuadraticInterrupted) {
            null
        } catch (_: PolynomialResourceLimit) {
            null
        } finally {
            operation = Cancellation.Never
        }
    }

    fun check(
        proposed: PolynomialCondition,
        cancellation: Cancellation,
        mandatory: Boolean = true,
    ): QuadraticRelaxationResult {
        operation = cancellation
        return try {
            arithmetic.step()
            val points = fixedPoints ?: polynomialPointFixings(formula, arithmetic::step).also { fixedPoints = it }
            val lower = proposed.lower.toMutableMap()
            val upper = proposed.upper.toMutableMap()
            for ((id, value) in points) {
                arithmetic.step()
                lower[id] = lower[id]?.let { if (it > value) it else value } ?: value
                upper[id] = upper[id]?.let { if (it < value) it else value } ?: value
            }
            val condition = PolynomialCondition(proposed.bools, lower, upper)
            if (lower.any { (id, value) -> arithmetic.step(); upper[id]?.let { value > it } == true }) {
                return QuadraticRelaxationResult.Infeasible(condition)
            }
            val conditioned = condition.apply(formula, boolCount, numericCount, arithmetic::step)
            val convex = try {
                tryConvexPolynomialOptimum(
                    conditioned, objective, boolCount, numericCount, condition.bools, arithmetic,
                    QuadraticControl(numerical, maximumMatrixCells = params.maximumMatrixCells),
                )
            } catch (stop: QuadraticInterrupted) {
                if (stop.reason != QuadraticStop.MATRIX_LIMIT) throw stop
                null
            }
            if (convex != null) {
                QuadraticRelaxationResult.Minimum(
                    convex.assignment, algebraic.rational(convex.certificate.bound.value), true, convex,
                )
            } else {
                val result = solvePolynomialObjectiveImage(
                    formula, objective, boolCount, numericCount,
                    QfNraParams(polynomial, polynomialLimits = limits), condition,
                )
                native.observe(result.stats)
                when (result) {
                    is PolynomialObjectiveResult.Optimum -> QuadraticRelaxationResult.Minimum(
                        result.assignment, result.value, true, result.evidence,
                    )
                    is PolynomialObjectiveResult.Infimum -> QuadraticRelaxationResult.Minimum(
                        result.incumbent, result.value, false, result.evidence,
                    )
                    is PolynomialObjectiveResult.Unbounded -> QuadraticRelaxationResult.Unbounded(
                        result.incumbent, result.evidence,
                    )
                    is PolynomialObjectiveResult.Infeasible -> QuadraticRelaxationResult.Infeasible(condition)
                    is PolynomialObjectiveResult.Unknown -> {
                        if (mandatory) result.resource?.let(native::decline)
                        QuadraticRelaxationResult.Unknown(result.resource)
                    }
                }
            }
        } catch (_: PolynomialInterrupted) {
            QuadraticRelaxationResult.Unknown()
        } catch (stop: PolynomialResourceLimit) {
            if (mandatory) native.decline(stop.resource)
            QuadraticRelaxationResult.Unknown(stop.resource)
        } catch (_: QuadraticInterrupted) {
            QuadraticRelaxationResult.Unknown()
        } finally {
            operation = Cancellation.Never
        }
    }
}
