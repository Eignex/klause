package com.eignex.klause.lp.engine

/** An LP solve result for the oracle tests: verdict, the FULL optimum ([objectiveValue], folding in
 *  [LpModel.objConstant]), and per-structural-variable [primal]. */
internal class LpSolution(val status: LpVerdict, val objectiveValue: Double, private val primalValues: DoubleArray) {
    fun primal(col: Int): Double = primalValues[col]
}

/**
 * Solve [model] with the float [RevisedSimplex] and adapt the result to [LpSolution].
 *
 * A null from the engine is [LpVerdict.INDETERMINATE], not [LpVerdict.INFEASIBLE]: it also covers
 * non-convergence and a singular basis, and even a dual-unbounded termination is only a *candidate*
 * infeasibility until an exact Farkas ray certifies it. Reading it as a refutation is the inference
 * `solveAndCertify` exists to refuse.
 */
internal fun solveLp(model: LpModel): LpSolution {
    val r = RevisedSimplex(model).solve()
        ?: return LpSolution(LpVerdict.INDETERMINATE, Double.NaN, DoubleArray(model.n))
    return LpSolution(LpVerdict.OPTIMAL, r.objective, r.primal)
}
