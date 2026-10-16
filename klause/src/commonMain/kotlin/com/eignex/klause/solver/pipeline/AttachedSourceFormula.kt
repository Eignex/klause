package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.SourceSemanticsOwner
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.util.Cancellation

internal sealed interface AttachedSourceFormula {
    val source: SourceSemanticsOwner

    class Linear(val problem: LinearSourceProblem) : AttachedSourceFormula {
        override val source: SourceSemanticsOwner get() = problem
    }

    class Polynomial(val problem: PolynomialRealProblem) : AttachedSourceFormula {
        override val source: SourceSemanticsOwner get() = problem
    }

    class IntegerPolynomial(val problem: NonlinearIntegerProblem) : AttachedSourceFormula {
        override val source: SourceSemanticsOwner get() = problem
    }

    class Functions(val problem: TranscendentalProblem) : AttachedSourceFormula {
        override val source: SourceSemanticsOwner get() = problem
    }
}

internal sealed interface NativeSourceExecution {
    val preparedPlan: PreparedSourcePlan

    class Numerical(val request: NumericalLinearRequest, override val preparedPlan: PreparedSourcePlan) :
        NativeSourceExecution

    class Polynomial(val request: PolynomialTheoryRequest) : NativeSourceExecution {
        override val preparedPlan: PreparedSourcePlan get() = request.preparedPlan
    }

    class IntegerPolynomial(val request: NonlinearIntegerTheoryRequest) : NativeSourceExecution {
        override val preparedPlan: PreparedSourcePlan get() = request.preparedPlan
    }

    class Functions(val request: TranscendentalTheoryRequest) : NativeSourceExecution {
        override val preparedPlan: PreparedSourcePlan = request.problem.prepareTranscendentalExecutionPlan()
    }
}

internal fun prepareNativeSource(
    request: SourceSetupRequest,
    formula: AttachedSourceFormula,
    stop: Cancellation,
): SourceProblemRoute.NativeFormula {
    if (stop()) return SourceProblemRoute.NativeFormula(null)
    val visits = when (formula) {
        is AttachedSourceFormula.Linear -> error("linear sources prepare their source-unit execution view")
        is AttachedSourceFormula.Polynomial -> formula.problem.totalRealCount.toLong() + formula.problem.totalBoolCount
        is AttachedSourceFormula.IntegerPolynomial -> formula.problem.numericCount.toLong() + formula.problem.boolCount
        is AttachedSourceFormula.Functions -> formula.problem.numericCount.toLong() + formula.problem.boolCount
    }
    stop.charge(visits)
    if (stop()) return SourceProblemRoute.NativeFormula(null)
    val execution = when (formula) {
        is AttachedSourceFormula.Linear -> error("linear sources prepare their source-unit execution view")

        is AttachedSourceFormula.Polynomial -> NativeSourceExecution.Polynomial(
            PolynomialTheoryRequest(
                formula.problem,
                formula.problem.prepareExecutionPlan(request.source.settings, stop).bindObjective(request),
            ),
        )

        is AttachedSourceFormula.IntegerPolynomial -> NativeSourceExecution.IntegerPolynomial(
            NonlinearIntegerTheoryRequest(
                formula.problem,
                formula.problem.prepareExecutionPlan().bindObjective(request),
            ),
        )

        is AttachedSourceFormula.Functions -> NativeSourceExecution.Functions(
            TranscendentalTheoryRequest(formula.problem),
        )
    }
    return SourceProblemRoute.NativeFormula(execution.takeUnless { stop() })
}

private fun PreparedSourcePlan.bindObjective(request: SourceSetupRequest): PreparedSourcePlan = when {
    request.quadraticObjective != null -> withObjective(
        request.quadraticObjective,
        if (request.maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
    )

    request.objective != null -> withObjective(request.objective, request.maximize)

    else -> this
}
