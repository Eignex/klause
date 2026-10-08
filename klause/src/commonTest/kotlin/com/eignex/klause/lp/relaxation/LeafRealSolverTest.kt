package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.EngineConstruction
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.RecordingLpEngineFactory
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LeafRealSolverTest {
    @Test
    fun `sibling integer pins preserve fresh exact real optima with one numerical owner`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(-2, 3)),
            arrayOf(Linear(longArrayOf(1), intArrayOf(0), doubleArrayOf(2.0), intArrayOf(0), LinearOp.GE, 1L)),
            numRealVars = 1, realLower = doubleArrayOf(-5.0), realUpper = doubleArrayOf(5.0),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(2), realCoefficients = doubleArrayOf(1.0),
            constant = 7L)
        val factory = RecordingLpEngineFactory()
        LeafRealSolver(problem, objective, context = LpSolveContext(factory)).use { owner ->
            for (value in listOf(0L, 2L, -2L, 3L, 0L)) {
                val sample = Sample(booleanArrayOf(), longArrayOf(value))
                val actual = owner.solve(sample)
                val fresh = leafRealFeasibility(problem, objective, sample)

                assertEquals(LpVerdict.ATTAINED_OPTIMUM, actual.verdict)
                assertEquals(fresh.verdict, actual.verdict)
                assertEquals(fresh.exactReals, actual.exactReals)
                assertEquals(BigFraction.ofLong(1L - value) * BigFraction.ofLong(2L).reciprocal(),
                    assertNotNull(actual.exactReals).single())
            }
        }
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        assertEquals(0, factory.calls.count { it.kind == EngineConstruction.GENERAL })
    }

    @Test
    fun `changed leaf product rows retain fresh feasible and infeasible verdicts`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(1, 3)),
            arrayOf(RealProduct(0, 0, 1, 0.0, 5.0),
                Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.EQ, 6L)),
            numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(5.0, 8.0),
        )
        LeafRealSolver(problem, null).use { owner ->
            for (value in listOf(1L, 3L, 2L, 1L)) {
                val sample = Sample(booleanArrayOf(), longArrayOf(value))
                val actual = owner.solve(sample)
                val fresh = leafRealFeasibility(problem, null, sample)

                assertEquals(fresh.verdict, actual.verdict)
                assertEquals(if (value == 1L) LpVerdict.INFEASIBLE else LpVerdict.ATTAINED_OPTIMUM, actual.verdict)
                if (value != 1L) assertEquals(fresh.exactReals, actual.exactReals)
                else assertEquals(fresh.refutingFactors.toSet(), actual.refutingFactors.toSet())
            }
        }
    }

    @Test
    fun `reified strict siblings cannot reuse a boundary witness`() {
        val problem = Problem(
            1, 0, emptyArray(),
            arrayOf(ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                LinearOp.LE, 0.0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        LeafRealSolver(problem, objective).use { owner ->
            for (pin in listOf(true, false, true, false)) {
                val sample = Sample(booleanArrayOf(pin), longArrayOf())
                val actual = owner.solve(sample)
                val fresh = leafRealFeasibility(problem, objective, sample)

                assertEquals(fresh.verdict, actual.verdict)
                val value = assertNotNull(actual.exactReals).single()
                if (pin) assertEquals(BigFraction.ZERO, value) else assertTrue(value > BigFraction.ZERO)
            }
        }
    }

    @Test
    fun `a cancelled sibling leaves the preceding source state available for another solve`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(longArrayOf(1), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.EQ, 2L)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0),
        )
        LeafRealSolver(problem, null).use { owner ->
            assertEquals(
                listOf(BigFraction.ofLong(2L)),
                owner.solve(Sample(booleanArrayOf(), longArrayOf(0))).exactReals,
            )
            assertEquals(LpVerdict.INDETERMINATE,
                owner.solve(Sample(booleanArrayOf(), longArrayOf(2)), Cancellation { true }).verdict)
            owner.releaseSolvers()
            assertEquals(listOf(BigFraction.ONE), owner.solve(Sample(booleanArrayOf(), longArrayOf(1))).exactReals)
        }
    }
}
