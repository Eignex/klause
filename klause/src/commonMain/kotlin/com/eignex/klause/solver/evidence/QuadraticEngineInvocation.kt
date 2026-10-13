package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.pipeline.PreparedSourcePlan

internal class QuadraticEngineInvocation(
    val source: QuadraticEngineSource,
    val objective: QuadraticObjectiveSpec,
    val sense: ObjectiveSense,
    val condition: SourceCondition,
    val affineObjective: LinearObjective? = null,
) {
    fun matches(other: QuadraticEngineInvocation): Boolean = sameSourceObjective(other) && condition == other.condition

    fun sameSourceObjective(other: QuadraticEngineInvocation): Boolean = when (val original = source) {
        is QuadraticEngineSource.ExactIr ->
            (other.source as? QuadraticEngineSource.ExactIr)?.problem === original.problem
        is QuadraticEngineSource.Polynomial ->
            (other.source as? QuadraticEngineSource.Polynomial)?.problem === original.problem
        is QuadraticEngineSource.NonlinearInteger ->
            (other.source as? QuadraticEngineSource.NonlinearInteger)?.problem === original.problem
    } && objective === other.objective && affineObjective === other.affineObjective && sense == other.sense

    fun matchesModel(model: ModelIdentity): Boolean = when (val original = source) {
        is QuadraticEngineSource.ExactIr -> model.matches(ModelIdentity.of(original.problem, objective))
        is QuadraticEngineSource.Polynomial -> model.matches(ModelIdentity.of(original.problem, objective))
        is QuadraticEngineSource.NonlinearInteger -> model.matches(ModelIdentity.of(original.problem, objective))
    }
}

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
    val affine = this.objective?.takeIf {
        it.realCoefficientValues.all { coefficient -> coefficient.isFinite() } &&
            sense == if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
    }?.takeIf { objective == it.toQuadraticObjectiveSpec() }
    return QuadraticEngineInvocation(source, objective, sense, assumptions.sourceCondition(), affine)
}
