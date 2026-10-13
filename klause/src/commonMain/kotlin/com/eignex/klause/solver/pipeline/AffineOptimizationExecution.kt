package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec

/**
 * Optimize an affine source objective with exact rational or algebraic results.
 * The quadratic source executor preserves attainment, strict limits and objective direction.
 * [minimize] retains the finite-objective API; this entry point also accepts continuous costs.
 */
fun OpenTheoryPipeline.optimize(
    request: PolynomialTheoryRequest,
    objective: LinearObjective,
    params: PolynomialTheoryParams = PolynomialTheoryParams(),
    engine: FiniteEngine = FiniteEngine.MIXED,
    sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
): QuadraticOptimizationExecution = optimize(request, objective, params, engine, sense, 1)

/** Optimize an affine source objective with explicit parallel lanes. */
fun OpenTheoryPipeline.optimize(
    request: PolynomialTheoryRequest,
    objective: LinearObjective,
    params: PolynomialTheoryParams,
    engine: FiniteEngine,
    sense: ObjectiveSense,
    cores: Int,
): QuadraticOptimizationExecution = minimize(
    request.affineOperation(objective, sense), objective.toQuadraticObjectiveSpec(), params, engine, sense, cores,
)
