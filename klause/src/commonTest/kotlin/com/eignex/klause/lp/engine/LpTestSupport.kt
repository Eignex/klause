package com.eignex.klause.lp.engine

/**
 * Test oracle for the LP relaxation: solve with the float [RevisedSimplex] and read its objective, the
 * FULL optimum (it already folds in [LpModel.objConstant]). Float-accurate — tests compare it with a
 * tolerance. Returns NaN when the relaxation is infeasible / does not solve.
 */
internal fun exactLpOptimum(model: LpModel): Double = RevisedSimplex(model).solve()?.objective ?: Double.NaN

internal enum class FloatLpStatus { OPTIMAL, INDETERMINATE }

/** An LP solve result for the oracle tests: verdict, the FULL optimum ([objectiveValue], folding in
 *  [LpModel.objConstant]), and per-structural-variable [primal]. */
internal class LpSolution(
    val status: FloatLpStatus,
    val objectiveValue: Double,
    private val primalValues: DoubleArray,
) {
    fun primal(col: Int): Double = primalValues[col]
}

/**
 * Solve [model] with the float [RevisedSimplex] and adapt the result to [LpSolution].
 *
 * A null from the engine is [FloatLpStatus.INDETERMINATE], not proof of infeasibility: it covers
 * non-convergence and a singular basis, and even a dual-unbounded termination is only a *candidate*
 * infeasibility until an exact Farkas ray certifies it. Reading it as a refutation is the inference
 * `solveAndCertify` exists to refuse.
 */
internal fun solveLp(model: LpModel): LpSolution {
    val r = RevisedSimplex(model).solve()
        ?: return LpSolution(FloatLpStatus.INDETERMINATE, Double.NaN, DoubleArray(model.n))
    return LpSolution(FloatLpStatus.OPTIMAL, r.objective, r.primal)
}
