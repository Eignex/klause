package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.theory.TheoryParams

/** A complete open-model solve request selected by the orchestration layer. */
class OpenTheoryRequest(
    /** Source model whose complete theory route is selected by this pipeline. */
    val model: ProblemSpec,
    /** Null requests satisfiability; a value requests optimization. */
    val objective: LinearObjective? = null,
    /** Whether [objective] is maximized rather than minimized. */
    val maximize: Boolean = false,
)

/** The common execution result for a complete open-model request. */
sealed interface OpenTheoryExecution {
    /** Satisfiability result for a request without an objective. */
    data class Satisfy(
        /** The satisfiability verdict. */
        val result: OpenTheoryResult,
    ) : OpenTheoryExecution

    /** Optimization result for a request with an objective. */
    data class Optimize(
        /** The optimization verdict. */
        val result: OpenTheoryOptimum,
    ) : OpenTheoryExecution
}

/** Executes an open-model request without exposing individual theory implementations to callers. */
object OpenTheoryPipeline {
    /** Execute [request] through its selected complete theory route. */
    fun execute(request: OpenTheoryRequest, params: TheoryParams = TheoryParams()): OpenTheoryExecution {
        val objective = request.objective
        if (objective == null) {
            val plan = request.model.componentPlan()
            return OpenTheoryExecution.Satisfy(OpenTheoryEngine(request.model, plan.theoryPipeline, plan).solve(params))
        }
        val driven = if (request.maximize) objective.negated() else objective
        return OpenTheoryExecution.Optimize(OpenTheoryMinimizer(request.model, driven).minimize(params))
    }
}

/** The objective whose minimum is this one's maximum. */
private fun LinearObjective.negated(): LinearObjective = LinearObjective(
    boolWeights = LongArray(boolWeights.size) { -boolWeights[it] },
    intCoefficients = LongArray(intCoefficients.size) { -intCoefficients[it] },
    constant = -constant,
    realCoefficients = DoubleArray(realCoefficients.size) { -realCoefficients[it] },
)
