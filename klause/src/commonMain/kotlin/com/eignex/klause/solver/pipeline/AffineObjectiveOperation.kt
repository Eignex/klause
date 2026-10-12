package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.objective.LinearObjective

internal fun PolynomialTheoryRequest.affineOperation(
    objective: LinearObjective,
    sense: ObjectiveSense,
): PolynomialTheoryRequest = PolynomialTheoryRequest(
    problem, preparedPlan.withObjective(objective, sense == ObjectiveSense.MAXIMIZE),
)
