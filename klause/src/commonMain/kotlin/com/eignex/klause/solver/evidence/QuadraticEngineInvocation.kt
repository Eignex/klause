package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.pipeline.PreparedSourcePlan

internal class QuadraticEngineInvocation(
    val source: QuadraticEngineSource,
    val objective: QuadraticObjectiveSpec,
    val sense: ObjectiveSense,
    val condition: SourceCondition,
)

internal fun PreparedSourcePlan.quadraticEngineInvocation(
    objective: QuadraticObjectiveSpec,
    sense: ObjectiveSense,
    assumptions: Assumptions,
): QuadraticEngineInvocation? {
    if (!assumptions.deductions.isEmpty || assumptions.bools.keys.any { it !in 0 until sourceBoolCount } ||
        assumptions.ints.keys.any { it !in 0 until sourceIntCount }
    ) return null
    val source = when (val original = originalSource) {
        is Problem -> QuadraticEngineSource.ExactIr(original)
        is PolynomialRealProblem -> QuadraticEngineSource.Polynomial(original)
        is NonlinearIntegerProblem -> QuadraticEngineSource.NonlinearInteger(original)
        else -> return null
    }
    return QuadraticEngineInvocation(source, objective, sense, assumptions.sourceCondition())
}
