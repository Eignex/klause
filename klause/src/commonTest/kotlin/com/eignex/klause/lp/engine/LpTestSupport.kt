package com.eignex.klause.lp.engine

internal fun exactLpOptimum(model: LpModel): Double = RevisedSimplex(model).solve()?.objective ?: Double.NaN

internal enum class FloatLpStatus { OPTIMAL, INDETERMINATE }

internal class LpSolution(
    val status: FloatLpStatus,
    val objectiveValue: Double,
    private val primalValues: DoubleArray,
) {
    fun primal(col: Int): Double = primalValues[col]
}

// A null float solve is not an infeasibility proof; only an exact Farkas ray certifies that.
internal fun solveLp(model: LpModel): LpSolution {
    val r = RevisedSimplex(model).solve()
        ?: return LpSolution(FloatLpStatus.INDETERMINATE, Double.NaN, DoubleArray(model.n))
    return LpSolution(FloatLpStatus.OPTIMAL, r.objective, r.primal)
}
