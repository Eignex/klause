package com.eignex.klause.solver.lp

/**
 * Test oracle for the LP relaxation: solve with the float [RevisedSimplex], then read the exact
 * basis-certified optimum off [ExactBasisCertifier]. The certified objective is the FULL optimum (it
 * already folds in [LpModel.objConstant]).
 */
internal fun exactLpOptimum(model: LpModel): Double {
    val r = RevisedSimplex(model).solve() ?: return Double.NaN
    val cert = ExactBasisCertifier.certify(model, r.basis) ?: return Double.NaN
    return cert.objective.num.toDouble() / cert.objective.den.toDouble()
}

/** An LP solve result for the oracle tests: status, the FULL certified optimum ([objectiveValue],
 *  folding in [LpModel.objConstant]), and per-structural-variable [primal]. */
internal class LpSolution(val status: LpStatus, val objectiveValue: Double, private val primalValues: DoubleArray) {
    fun primal(col: Int): Double = primalValues[col]
}

/** Solve [model] with the [RevisedSimplex] and adapt the result to [LpSolution]. */
internal fun solveLp(model: LpModel): LpSolution {
    val r = RevisedSimplex(model).solve()
        ?: return LpSolution(LpStatus.INFEASIBLE, Double.NaN, DoubleArray(model.n))
    val cert = ExactBasisCertifier.certify(model, r.basis)
    val obj = if (cert != null) cert.objective.num.toDouble() / cert.objective.den.toDouble() else Double.NaN
    return LpSolution(LpStatus.OPTIMAL, obj, r.primal)
}
