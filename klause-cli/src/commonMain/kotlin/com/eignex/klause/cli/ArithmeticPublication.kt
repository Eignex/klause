package com.eignex.klause.cli

import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.QuadraticOptimizationResult

internal class ArithmeticPublication(private val output: OutputProtocol) {
    private var lastRendered: String? = null
    private var lastObjective: SourceValue? = null

    var solutions: Long = 0
        private set

    fun publish(rendered: String, objective: SourceValue) {
        if (rendered == lastRendered && sameObjective(objective, lastObjective)) return
        output.onArithmeticIncumbent(rendered, objective)
        lastRendered = rendered
        lastObjective = objective
        solutions++
    }

    fun quadratic(rendered: String?, result: QuadraticOptimizationResult) {
        val point = (result as? QuadraticOptimizationResult.WithIncumbent)?.incumbent
        if (rendered != null && point != null) publish(rendered, SourceValue.Algebraic(point.value))
        output.onQuadraticResult(null, result)
    }
}

internal fun arithmeticObjectiveText(objective: SourceValue): String = when (objective) {
    is SourceValue.Rational -> objective.value.toString()
    is SourceValue.Algebraic -> objective.value.rational?.toString()
        ?: "root(${objective.value.rootIndex};[${objective.value.definingCoefficients.joinToString(",")}])"
    is SourceValue.Extension -> error("arithmetic objective requires its source renderer")
}

private fun sameObjective(first: SourceValue, second: SourceValue?): Boolean {
    fun rational(value: SourceValue?) = when (value) {
        is SourceValue.Rational -> value.value
        is SourceValue.Algebraic -> value.value.rational
        else -> null
    }
    val firstRational = rational(first)
    if (firstRational != null) return firstRational == rational(second)
    if (first is SourceValue.Algebraic && second is SourceValue.Algebraic) {
        return first.value.rootIndex == second.value.rootIndex &&
            first.value.definingCoefficients == second.value.definingCoefficients
    }
    return first === second
}
