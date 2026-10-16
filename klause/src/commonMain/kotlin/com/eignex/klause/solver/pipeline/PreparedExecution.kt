package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective

internal sealed interface PreparedExecution {
    sealed interface Factor : PreparedExecution {
        val model: Problem
    }

    class FiniteLinear(override val model: Problem, val ownership: ComponentPlan) : Factor {
        init {
            ownership.requireSource(model)
        }
    }

    class Polynomial(val completion: SharedPolynomialModel) : Factor {
        override val model: Problem get() = completion.source
    }
    class NonlinearInteger(
        val source: NonlinearIntegerProblem,
        val projection: FactorPolynomialProjection? = null,
    ) : PreparedExecution
    class Transcendental(val source: TranscendentalProblem) : PreparedExecution
    class Numerical(val source: LinearSourceProblem, override val model: Problem) : Factor
    class Declined(val model: Problem, val reason: PreparedPlanDecline, val retained: Factor? = null) :
        PreparedExecution {
        init {
            require(retained == null || retained.model === model)
        }
    }
}

internal val PreparedExecution.model: Problem? get() = when (this) {
    is PreparedExecution.FiniteLinear -> model
    is PreparedExecution.Polynomial -> completion.source
    is PreparedExecution.Declined -> model
    is PreparedExecution.Numerical -> model
    is PreparedExecution.NonlinearInteger -> projection?.model
    is PreparedExecution.Transcendental -> null
}

internal val PreparedExecution.componentPlan: ComponentPlan? get() = finiteLinear?.ownership

internal val PreparedExecution.finiteLinear: PreparedExecution.FiniteLinear? get() = when (this) {
    is PreparedExecution.FiniteLinear -> this
    is PreparedExecution.Declined -> retained as? PreparedExecution.FiniteLinear
    is PreparedExecution.Polynomial, is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental -> null
    is PreparedExecution.Numerical -> null
}

internal val PreparedExecution.polynomial: PreparedExecution.Polynomial? get() = when (this) {
    is PreparedExecution.Polynomial -> this

    is PreparedExecution.Declined -> retained as? PreparedExecution.Polynomial

    is PreparedExecution.FiniteLinear, is PreparedExecution.NonlinearInteger,
    is PreparedExecution.Transcendental,
    -> null

    is PreparedExecution.Numerical -> null
}

internal val PreparedExecution.kind: PreparedExecutionKind get() = when (this) {
    is PreparedExecution.FiniteLinear -> PreparedExecutionKind.FINITE_LINEAR
    is PreparedExecution.Polynomial -> PreparedExecutionKind.POLYNOMIAL
    is PreparedExecution.NonlinearInteger -> PreparedExecutionKind.NONLINEAR_INTEGER
    is PreparedExecution.Transcendental -> PreparedExecutionKind.TRANSCENDENTAL
    is PreparedExecution.Numerical -> PreparedExecutionKind.NUMERICAL
    is PreparedExecution.Declined -> PreparedExecutionKind.DECLINED
}

internal val PreparedExecution.decline: PreparedPlanDecline? get() = when (this) {
    is PreparedExecution.FiniteLinear -> PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT.takeIf {
        ownership.theoryPipeline == ProblemPipeline.UNSUPPORTED_OPEN
    }

    is PreparedExecution.Declined -> reason

    is PreparedExecution.Polynomial, is PreparedExecution.NonlinearInteger, is PreparedExecution.Transcendental -> null

    is PreparedExecution.Numerical -> null
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
    val sourceModel: Problem? = originalSource as? Problem ?: execution.model,
) {
    val components = components.toList()
    val integerCoordinates = integerCoordinates.toList()
    val realCoordinates = realCoordinates.toList()
    val operations = operations.toSet()
    val finiteFactorProjections: PreparedFactorProjections? = execution.polynomial?.let { polynomial ->
        factorProjections?.projected(
            polynomial.completion.finite,
            polynomial.completion.finiteFactorIndices.toIntArray(),
        )
    } ?: factorProjections

    init {
        require(this.integerCoordinates.size == sourceIntCount && this.realCoordinates.size == sourceRealCount)
        execution.model?.let { factorProjections?.requireSource(it) }
        when (execution) {
            is PreparedExecution.NonlinearInteger -> {
                val projection = execution.projection
                if (projection == null) require(originalSource === execution.source)
                else {
                    require(originalSource === projection.original && execution.source === projection.formula)
                    require(factorProjections === projection.projections)
                    require(sourceBoolCount == projection.model.numBoolVars &&
                        sourceIntCount == projection.model.numIntVars && sourceRealCount == projection.model.numRealVars)
                    require(this.integerCoordinates == execution.source.integerCoordinates &&
                        this.realCoordinates == execution.source.realCoordinates)
                }
            }
            is PreparedExecution.Transcendental -> require(originalSource === execution.source)
            is PreparedExecution.Numerical -> require(originalSource === execution.source)
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
    val workingLinearObjective: LinearObjective? = null,
) {
    fun supportedBy(source: PreparedSourceStructure): Boolean {
        val execution = source.execution
        if ((source.originalSource as? LinearSourceProblem)?.objective != null) {
            val working = workingLinearObjective ?: return false
            val model = execution.model ?: return false
            return working.boolWeightValues.size <= model.numBoolVars &&
                working.intCoefficientValues.size <= model.numIntVars &&
                working.realCoefficientValues.size <= model.numRealVars &&
                working.realCoefficientValues.all { it.isFinite() }
        }
        if (quadraticObjective != null) {
            return (execution.polynomial != null || execution is PreparedExecution.NonlinearInteger) &&
                quadraticObjective.terms.all { term ->
                    listOfNotNull(term.first, term.second).all { variable ->
                        when (variable) {
                            is QuadraticVariable.Bool -> variable.id.index in 0 until source.sourceBoolCount
                            is QuadraticVariable.Int -> variable.id.index in 0 until source.sourceIntCount
                            is QuadraticVariable.Real -> variable.id.index in 0 until source.sourceRealCount
                        }
                    }
                }
        }
        val linear = objective ?: return false
        if (linear.realCoefficientValues.any { !it.isFinite() } ||
            linear.realCoefficientValues.size > source.sourceRealCount ||
            linear.intCoefficientValues.size > source.sourceIntCount ||
            linear.boolWeightValues.size > source.sourceBoolCount
        ) {
            return false
        }
        return execution !is PreparedExecution.Transcendental
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
        requireNotNull(model),
        requireNotNull(componentPlan),
    )

    PreparedExecutionKind.POLYNOMIAL -> PreparedExecution.Polynomial(requireNotNull(polynomialModel))

    PreparedExecutionKind.NONLINEAR_INTEGER -> PreparedExecution.NonlinearInteger(
        originalSource as NonlinearIntegerProblem,
    )

    PreparedExecutionKind.TRANSCENDENTAL -> PreparedExecution.Transcendental(originalSource as TranscendentalProblem)
    PreparedExecutionKind.NUMERICAL -> PreparedExecution.Numerical(
        originalSource as LinearSourceProblem, requireNotNull(model),
    )
    PreparedExecutionKind.DECLINED -> if (decline == PreparedPlanDecline.CONTINUOUS_LINEAR_OBJECTIVE &&
        polynomialModel != null
    ) {
        PreparedExecution.Polynomial(polynomialModel)
    } else {
        PreparedExecution.Declined(
        requireNotNull(model),
        requireNotNull(decline),
        polynomialModel?.let { PreparedExecution.Polynomial(it) }
            ?: componentPlan?.let { PreparedExecution.FiniteLinear(requireNotNull(model), it) },
    )
    }
}
