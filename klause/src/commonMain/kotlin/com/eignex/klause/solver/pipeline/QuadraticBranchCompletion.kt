package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.quadratic.QuadraticControl
import com.eignex.klause.arithmetic.quadratic.QuadraticInterrupted
import com.eignex.klause.arithmetic.quadratic.QuadraticStop
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.QuadraticEngineDerivation
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.objective.evaluateExact
import com.eignex.klause.solver.objective.polynomialFor
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.theory.qfnra.PolynomialBooleanCondition
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.PolynomialObjectiveResult
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.polynomialPointFixings
import com.eignex.klause.theory.qfnra.solvePolynomialObjectiveImage
import com.eignex.klause.theory.qfnra.tryConvexPolynomialOptimum
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf

internal sealed interface QuadraticBranchResult {
    data class Optimal(
        val incumbent: QuadraticIncumbent, val limit: AlgebraicReal, val proof: SourceDerivation,
    ) : QuadraticBranchResult
    data class Limit(
        val incumbent: QuadraticIncumbent, val limit: AlgebraicReal, val proof: SourceDerivation,
    ) : QuadraticBranchResult
    data class Unbounded(val incumbent: QuadraticIncumbent, val proof: SourceDerivation) : QuadraticBranchResult
    data class Infeasible(val proof: SourceDerivation) : QuadraticBranchResult
    data object Unknown : QuadraticBranchResult
}

internal class QuadraticBranchCompletion(
    private val request: PolynomialTheoryRequest,
    private val model: SharedPolynomialModel,
    private val objective: QuadraticObjectiveSpec,
    private val sense: ObjectiveSense,
    private val params: PolynomialTheoryParams,
    private val native: QuadraticNativeScope,
) {
    private val limits = PolynomialLimits(params.maximumDenseCoefficients, params.maximumMatrixCells)
    private var operation: Cancellation = Cancellation.Never
    private fun token(parent: Cancellation): Cancellation = object : Cancellation {
        override fun isCancelled(): Boolean = parent() || operation()
        override fun workMeter(): WorkMeter? = parent.workMeter()
        override fun deadline() = parent.deadline()?.let { outer ->
            operation.deadline()?.let { inner -> minOf(outer, inner) } ?: outer
        } ?: operation.deadline()
    }
    private val polynomial = token(native.polynomial)
    private val numerical = token(native.numerical)
    private val arithmetic = PolynomialArithmetic(polynomial, limits)
    val algebraic = AlgebraicArithmetic(arithmetic)
    private var fixedPoints: Map<Int, BigFraction>? = null

    fun pointFixings(step: () -> Unit = arithmetic::step): Map<Int, BigFraction> =
        fixedPoints ?: polynomialPointFixings(model.service.formula, step).also { fixedPoints = it }

    fun conditionedPointFixings(
        bools: Map<Int, Boolean>, step: () -> Unit = arithmetic::step,
    ): Map<Int, BigFraction> = if (bools.isEmpty()) pointFixings(step) else polynomialPointFixings(
        PolynomialBooleanCondition.apply(model.service.formula, bools, step), step,
    )

    fun check(
        finite: Sample,
        proposed: PolynomialCondition,
        cancellation: Cancellation = Cancellation.Never,
    ): QuadraticBranchResult {
        operation = cancellation
        return try {
            arithmetic.step()
            val lower = proposed.lower.toMutableMap()
            val upper = proposed.upper.toMutableMap()
            for ((id, value) in conditionedPointFixings(proposed.bools)) {
                arithmetic.step()
                lower[id] = lower[id]?.let { if (it > value) it else value } ?: value
                upper[id] = upper[id]?.let { if (it < value) it else value } ?: value
            }
            val condition = PolynomialCondition(proposed.bools, lower, upper)
            if (lower.any { (id, value) ->
                    arithmetic.step()
                    upper[id]?.let { value > it } == true
                }
            ) {
                arithmetic.step()
                return QuadraticBranchResult.Infeasible(QuadraticEngineDerivation.Condition(condition))
            }
            val expression = objective.polynomialFor(finite).let {
                if (sense == ObjectiveSense.MINIMIZE) it else PolynomialExpression.Product(listOf(
                    PolynomialExpression.Constant.of(bigIntOf(-1)), it,
                ))
            }
            val formula = condition.apply(
                model.service.formula, model.service.boolCount, model.service.realCount, arithmetic::step,
            )
            val convex = try {
                tryConvexPolynomialOptimum(
                    formula, expression, model.service.boolCount, model.service.realCount, condition.bools,
                    arithmetic, QuadraticControl(numerical, maximumMatrixCells = params.maximumMatrixCells),
                )
            } catch (stop: QuadraticInterrupted) {
                if (stop.reason != QuadraticStop.MATRIX_LIMIT) throw stop
                null
            }
            if (convex != null) {
                val point = capture(convex.assignment, finite)
                arithmetic.step()
                QuadraticBranchResult.Optimal(point, point.value, QuadraticEngineDerivation.Both(
                    QuadraticEngineDerivation.Condition(condition), QuadraticEngineDerivation.Convex(convex),
                ))
            } else {
                val result = solvePolynomialObjectiveImage(
                    model.service.formula, expression, model.service.boolCount, model.service.realCount,
                    QfNraParams(polynomial, polynomialLimits = limits), condition,
                )
                native.observe(result.stats)
                when (result) {
                    is PolynomialObjectiveResult.Optimum -> QuadraticBranchResult.Optimal(
                        capture(result.assignment, finite), sourceValue(result.value),
                        QuadraticEngineDerivation.Both(QuadraticEngineDerivation.Condition(condition),
                            QuadraticEngineDerivation.Image(result.evidence)),
                    )
                    is PolynomialObjectiveResult.Infimum -> QuadraticBranchResult.Limit(
                        capture(result.incumbent, finite), sourceValue(result.value),
                        QuadraticEngineDerivation.Both(QuadraticEngineDerivation.Condition(condition),
                            QuadraticEngineDerivation.Image(result.evidence)),
                    )
                    is PolynomialObjectiveResult.Unbounded -> QuadraticBranchResult.Unbounded(
                        capture(result.incumbent, finite),
                        QuadraticEngineDerivation.Both(QuadraticEngineDerivation.Condition(condition),
                            QuadraticEngineDerivation.Image(result.evidence)),
                    )
                    is PolynomialObjectiveResult.Infeasible -> QuadraticBranchResult.Infeasible(
                        QuadraticEngineDerivation.Condition(condition),
                    )
                    is PolynomialObjectiveResult.Unknown -> {
                        result.resource?.let(native::decline)
                        QuadraticBranchResult.Unknown
                    }
                }
            }
        } catch (_: PolynomialInterrupted) {
            QuadraticBranchResult.Unknown
        } catch (stop: PolynomialResourceLimit) {
            native.decline(stop.resource)
            QuadraticBranchResult.Unknown
        } catch (_: QuadraticInterrupted) {
            QuadraticBranchResult.Unknown
        } finally {
            operation = Cancellation.Never
        }
    }

    private fun sourceValue(value: AlgebraicReal): AlgebraicReal =
        if (sense == ObjectiveSense.MINIMIZE) value else algebraic.negate(value)

    private fun capture(assignment: QfNraAssignment, finite: Sample): QuadraticIncumbent {
        val full = model.capture(assignment, finite.bools, finite.ints)
        val sample = if (request.sharedModel != null) full else Sample(
            BooleanArray(request.problem.sourceBoolCount) { full.boolValue(it) }, full.ints,
            DoubleArray(request.problem.sourceRealCount) { full.approximateRealValue(it) },
            algebraicReals = requireNotNull(full.algebraicReals).take(request.problem.sourceRealCount),
        )
        val value = objective.evaluateExact(sample, algebraic)
        arithmetic.step()
        return QuadraticIncumbent(sample, value)
    }
}
