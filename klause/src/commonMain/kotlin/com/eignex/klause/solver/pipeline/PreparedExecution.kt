package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective

internal sealed interface PreparedExecution {
    class FiniteLinear(val model: Problem, val ownership: ComponentPlan) : PreparedExecution {
        init {
            ownership.requireSource(model)
        }
    }

    class Polynomial(val completion: SharedPolynomialModel) : PreparedExecution
    class NonlinearInteger(val source: NonlinearIntegerProblem) : PreparedExecution
    class Transcendental(val source: TranscendentalProblem) : PreparedExecution
    class Declined(
        val model: Problem,
        val reason: PreparedPlanDecline,
        val finiteProjection: FiniteLinear? = null,
    ) : PreparedExecution {
        init {
            require(finiteProjection == null || finiteProjection.model === model)
        }
    }
}

internal val PreparedExecution.model: Problem? get() = when (this) {
    is PreparedExecution.FiniteLinear -> model
    is PreparedExecution.Polynomial -> completion.source
    is PreparedExecution.Declined -> model
    is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental -> null
}

internal val PreparedExecution.componentPlan: ComponentPlan? get() = when (this) {
    is PreparedExecution.FiniteLinear -> ownership
    is PreparedExecution.Declined -> finiteProjection?.ownership
    is PreparedExecution.Polynomial, is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental -> null
}

internal val PreparedExecution.finiteLinear: PreparedExecution.FiniteLinear? get() = when (this) {
    is PreparedExecution.FiniteLinear -> this
    is PreparedExecution.Declined -> finiteProjection
    is PreparedExecution.Polynomial, is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental -> null
}

internal val PreparedExecution.kind: PreparedExecutionKind get() = when (this) {
    is PreparedExecution.FiniteLinear -> PreparedExecutionKind.FINITE_LINEAR
    is PreparedExecution.Polynomial -> PreparedExecutionKind.POLYNOMIAL
    is PreparedExecution.NonlinearInteger -> PreparedExecutionKind.NONLINEAR_INTEGER
    is PreparedExecution.Transcendental -> PreparedExecutionKind.TRANSCENDENTAL
    is PreparedExecution.Declined -> PreparedExecutionKind.DECLINED
}

internal val PreparedExecution.decline: PreparedPlanDecline? get() = when (this) {
    is PreparedExecution.FiniteLinear -> PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT.takeIf {
        ownership.theoryPipeline == ProblemPipeline.UNSUPPORTED_OPEN
    }
    is PreparedExecution.Declined -> reason
    is PreparedExecution.Polynomial, is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental -> null
}

internal class PreparedSourceStructure(
    val originalSource: Any,
    val execution: PreparedExecution,
    components: List<ExecutionComponentDescriptor>,
    val sourceBoolCount: Int,
    val sourceIntCount: Int,
    val sourceRealCount: Int,
    integerCoordinates: List<Int>,
    realCoordinates: List<Int>,
    operations: Set<ExecutionOperation>,
    val factorProjections: PreparedFactorProjections?,
) {
    val components = components.toList()
    val integerCoordinates = integerCoordinates.toList()
    val realCoordinates = realCoordinates.toList()
    val operations = operations.toSet()
    val finiteFactorProjections: PreparedFactorProjections? = when (execution) {
        is PreparedExecution.Polynomial -> factorProjections?.projected(
            execution.completion.finite, execution.completion.finiteFactorIndices.toIntArray(),
        )
        else -> factorProjections
    }

    init {
        require(this.integerCoordinates.size == sourceIntCount && this.realCoordinates.size == sourceRealCount)
        execution.model?.let { factorProjections?.requireSource(it) }
        when (execution) {
            is PreparedExecution.NonlinearInteger -> require(originalSource === execution.source)
            is PreparedExecution.Transcendental -> require(originalSource === execution.source)
            else -> Unit
        }
    }
}

internal class PreparedOperationBinding(
    val objective: LinearObjective? = null,
    val maximize: Boolean = false,
    val quadraticObjective: QuadraticObjectiveSpec? = null,
    val quadraticSense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    val decline: PreparedPlanDecline? = null,
) {
    fun supportedBy(source: PreparedSourceStructure): Boolean {
        val execution = source.execution
        if (quadraticObjective != null) {
            return (execution is PreparedExecution.Polynomial || execution is PreparedExecution.NonlinearInteger) &&
                quadraticObjective.terms.all { term -> listOfNotNull(term.first, term.second).all { variable ->
                    when (variable) {
                        is QuadraticVariable.Bool -> variable.id.index in 0 until source.sourceBoolCount
                        is QuadraticVariable.Int -> variable.id.index in 0 until source.sourceIntCount
                        is QuadraticVariable.Real -> variable.id.index in 0 until source.sourceRealCount
                    }
                } }
        }
        val linear = objective ?: return false
        if (linear.realCoefficientValues.any { !it.isFinite() } ||
            linear.realCoefficientValues.size > source.sourceRealCount ||
            linear.intCoefficientValues.size > source.sourceIntCount ||
            linear.boolWeightValues.size > source.sourceBoolCount
        ) return false
        return execution !is PreparedExecution.NonlinearInteger && execution !is PreparedExecution.Transcendental
    }
}

internal fun legacyPreparedExecution(
    originalSource: Any,
    model: Problem?,
    kind: PreparedExecutionKind,
    componentPlan: ComponentPlan?,
    polynomialModel: SharedPolynomialModel?,
    decline: PreparedPlanDecline?,
): PreparedExecution = when (kind) {
    PreparedExecutionKind.FINITE_LINEAR -> PreparedExecution.FiniteLinear(
        requireNotNull(model), requireNotNull(componentPlan),
    )
    PreparedExecutionKind.POLYNOMIAL -> PreparedExecution.Polynomial(requireNotNull(polynomialModel))
    PreparedExecutionKind.NONLINEAR_INTEGER -> PreparedExecution.NonlinearInteger(
        originalSource as NonlinearIntegerProblem,
    )
    PreparedExecutionKind.TRANSCENDENTAL -> PreparedExecution.Transcendental(originalSource as TranscendentalProblem)
    PreparedExecutionKind.DECLINED -> if (decline == PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE &&
        polynomialModel != null
    ) PreparedExecution.Polynomial(polynomialModel) else PreparedExecution.Declined(
        requireNotNull(model), requireNotNull(decline),
        componentPlan?.let { PreparedExecution.FiniteLinear(requireNotNull(model), it) },
    )
}
