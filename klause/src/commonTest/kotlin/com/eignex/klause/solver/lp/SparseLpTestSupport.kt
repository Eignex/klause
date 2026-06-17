package com.eignex.klause.solver.lp

/**
 * Test oracle for the sparse LP path (the only LP path, #705): solve with the float [RevisedSimplex],
 * then read the exact basis-certified optimum off [ExactBasisCertifier] — the replacement for the
 * retired dense `DualSimplex` solve in the relaxation/hull oracle tests. The certified objective is
 * the FULL optimum (it already folds in [LpModel.objConstant]), matching the old `LpSolution`'s
 * `objectiveValue + objectiveConstant`.
 */
internal fun exactLpOptimum(model: LpModel): Double {
    val r = RevisedSimplex(model).solve() ?: return Double.NaN
    val cert = ExactBasisCertifier.certify(model, r.basis) ?: return Double.NaN
    return cert.objective.num.toDouble() / cert.objective.den.toDouble()
}

/** A near-drop-in for the retired dense `LpSolution` in the oracle tests: status, the FULL certified
 *  optimum ([objectiveValue], folding in [LpModel.objConstant]), and per-structural-variable [primal]. */
internal class SparseSolution(val status: LpStatus, val objectiveValue: Double, private val primalValues: DoubleArray) {
    fun primal(col: Int): Double = primalValues[col]
}

/** Solve [model] through the sparse pipeline and adapt the result to [SparseSolution]. */
internal fun solveSparse(model: LpModel): SparseSolution {
    val r = RevisedSimplex(
        model,
    ).solve() ?: return SparseSolution(LpStatus.INFEASIBLE, Double.NaN, DoubleArray(model.n))
    val cert = ExactBasisCertifier.certify(model, r.basis)
    val obj = if (cert != null) cert.objective.num.toDouble() / cert.objective.den.toDouble() else Double.NaN
    return SparseSolution(LpStatus.OPTIMAL, obj, r.primal)
}
