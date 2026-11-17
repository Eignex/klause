package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.schema.times
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryOptimum
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.PolynomialStrategyParams
import com.eignex.klause.solver.pipeline.PolynomialTheoryParams
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceStrategyAdmissionTest {
    @Test
    fun `open source solve should decline boolean pins without changing the model`() {
        val source = Problem(
            1,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        val strategy = BacktrackSolver.source(source)

        val result = strategy.solve(BacktrackParams(assumptions = Assumptions(bools = mapOf(0 to true))))

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertIs<OpenTheoryResult.Sat>(strategy.solve())
    }

    @Test
    fun `source solve should decline deduction scopes on every route`() {
        val finite = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val open = Problem(
            0,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        val polynomial = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        val assumptions = Assumptions.None.withTightenedMin(0, 1)
        for (source in listOf(finite, open, polynomial)) {
            for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
                val common = PolynomialTheoryParams(strategy = PolynomialStrategyParams(assumptions = assumptions))
                val native = PolynomialTheoryParams(
                    strategy = PolynomialStrategyParams(
                        backtrack = BacktrackParams(assumptions = assumptions),
                        localSearch = LocalSearchParams(assumptions = assumptions),
                    ),
                )
                for (params in listOf(common, native)) {
                    val result = strategy.solve(params)

                    assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
                }
            }
        }
    }

    @Test
    fun `source optimize should decline deduction scopes`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(-3, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 42),
            ),
            openIntLo = booleanArrayOf(true),
            openIntHi = booleanArrayOf(true),
        )
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                assumptions = Assumptions.None.withIntHole(0, 0),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val open = assertIs<OpenTheoryExecution.Optimize>(strategy.optimizeOpen(objective, params)).result
            val polynomial = strategy.optimize(objective, params).result

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryOptimum.Bounded>(open).reason)
            assertEquals(
                TerminationReason.Unsupported,
                assertIs<QuadraticOptimizationResult.Unknown>(polynomial).reason,
            )
        }
    }

    @Test
    fun `source solve should decline tolerance checks without invoking them`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.3),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
        )
        var calls = 0

        val result = BacktrackSolver.source(source).solve(
            BacktrackParams(toleranceCheck = {
                calls++
                true
            }),
        )

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(result).reason)
        assertEquals(0, calls)
    }

    @Test
    fun `source operations should decline component factories without invoking them`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        var calls = 0
        val params = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                backtrack = BacktrackParams(componentFactory = {
                    calls++
                    emptyList()
                }),
            ),
        )
        for (strategy in listOf(BacktrackSolver.source(source), LocalSearchSolver.source(source))) {
            val solve = strategy.solve(params)
            val optimize = strategy.optimize(LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0)), params).result

            assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(solve).reason)
            assertEquals(TerminationReason.Unsupported, assertIs<QuadraticOptimizationResult.Unknown>(optimize).reason)
        }
        assertEquals(0, calls)
    }

    @Test
    fun `polynomial local search should decline event hooks without invoking them`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf<Factor>(ContinuousProduct(0, 0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-1.0, 0.0),
            realUpper = doubleArrayOf(1.0, 0.0),
        )
        var calls = 0
        val params = LocalSearchParams(onEvent = { calls++ })
        val strategy = LocalSearchSolver.source(source)

        val solve = strategy.solve(params)
        val optimize = strategy.optimize(LinearObjective(realCoefficients = doubleArrayOf(0.0, 1.0)), params).result

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(solve).reason)
        assertEquals(TerminationReason.Unsupported, assertIs<QuadraticOptimizationResult.Unknown>(optimize).reason)
        assertEquals(0, calls)
    }
}
