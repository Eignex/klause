package com.eignex.klause.lp.engine

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.ResumableMinimize

internal object WorkingModelCoverage {
    @JvmStatic
    fun main(args: Array<String>) {
        `objective replacement retracts cutoff facts and reevaluates a carried incumbent`()
        `mixed objective replacement recertifies real coordinates from the original region`()
        println("Working model integer and mixed-real lifecycle assertions passed")
    }

    fun `objective replacement retracts cutoff facts and reevaluates a carried incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val params = BacktrackParams(lpPlan = LpPlan(bounding = true), randomSeed = 17L)
        val solver = BacktrackSolver(problem)
        var search = ResumableMinimize(solver, LinearObjective(intCoefficients = longArrayOf(1L)), params)
        try {
            val first = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(0L, first.sample.ints[0])
            search = search.replacingObjective(LinearObjective(intCoefficients = longArrayOf(-1L), constant = 7L), params)
            val offered = ArrayList<Double>()
            val second = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, 1000L, 256L) {
                offered += it.objective
            })
            assertEquals(3L, second.sample.ints[0])
            assertEquals(4.0, second.objective)
            assertEquals(7.0, offered.first())
            search = search.replacingObjective(LinearObjective(intCoefficients = longArrayOf(1L)), params)
            val third = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(0L, third.sample.ints[0])
        } finally {
            search.close()
        }
    }

    fun `mixed objective replacement recertifies real coordinates from the original region`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(Linear(
                intVars = intArrayOf(0), intCoeffs = doubleArrayOf(1.0),
                realVars = intArrayOf(0), realCoeffs = doubleArrayOf(1.0), op = LinearOp.EQ, bound = 1.5,
            )), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
        ).bake()
        val params = BacktrackParams(lpPlan = LpPlan(bounding = true), randomSeed = 17L)
        val first = ResumableMinimize(BacktrackSolver(problem), LinearObjective(realCoefficients = doubleArrayOf(1.0)), params)
        val initial = assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
        assertEquals(0.5, initial.sample.reals[0])
        initial.sample.reals[0] = -100.0
        first.replacingObjective(LinearObjective(realCoefficients = doubleArrayOf(-1.0)), params).use { second ->
            val offered = ArrayList<Double>()
            val result = assertIs<MinimizeResult.Optimal>(second.runSlice(Cancellation.Never, 1000L, 256L) {
                offered += it.sample.ints[0] + it.sample.reals[0]
            })
            assertTrue(offered.all { it == 1.5 })
            assertEquals(1.5, result.sample.reals[0])
            assertEquals(-1.5, result.objective)
            assertEquals(0L, result.sample.ints[0])
        }
    }
}
