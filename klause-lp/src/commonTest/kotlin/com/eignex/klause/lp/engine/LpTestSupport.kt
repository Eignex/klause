package com.eignex.klause.lp.engine

/**
 * Test oracle for the LP relaxation: solve with the float [RevisedSimplex] and read its objective, the
 * FULL optimum (it already folds in [LpModel.objConstant]). Float-accurate — tests compare it with a
 * tolerance. Returns NaN when the relaxation is infeasible / does not solve.
 */
internal fun exactLpOptimum(model: LpModel): Double = RevisedSimplex(model).solve()?.objective ?: Double.NaN
