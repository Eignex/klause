package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexCyclingTest {
    private val model = LpBuilder().apply {
        for (cost in longArrayOf(-10, 57, 9, 24)) addVar(0L, 1000L, cost = cost)
        addRealRow(intArrayOf(0, 1, 2, 3), doubleArrayOf(0.5, -5.5, -2.5, 9.0), Relation.LE, 0.0)
        addRealRow(intArrayOf(0, 1, 2, 3), doubleArrayOf(0.5, -1.5, -0.5, 1.0), Relation.LE, 0.0)
        addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 1L)
    }.build(Sense.MINIMIZE)

    @Test
    fun `stall recovery reaches the source optimum from cold and objective warm starts`() {
        val exact = assertNotNull(model.authoritativeModel())
        for (warm in listOf(false, true)) {
            val initial = if (warm) {
                exact.copy(objective = ExactLpObjective(List(exact.numVars) { ExactLpNumber.of(0L) }))
            } else {
                exact
            }
            val trail = LpBoundTrail(LpExactState(initial))
            RevisedSimplex(
                assertNotNull(trail.state.toWorkingModel()),
                iterationLimit = 96,
                scalingOptions = LpScalingOptions(enabled = false),
            ).use { solver ->
                if (warm) {
                    assertNotNull(solver.solve())
                    assertTrue(trail.replaceObjective(exact.objective))
                    assertTrue(solver.adopt(trail.state, Cancellation.Never))
                }

                val result = assertNotNull(if (warm) solver.resolveBounds() else solver.solvePrimal())
                val certified = verifyExactBasis(assertNotNull(trail.state.toWorkingModel()), result.basis)

                assertEquals(1, solver.lastNumericalMetrics.primalBlandEntries)
                assertEquals(if (warm) 1 else 0, solver.lastMetrics.objectiveWarmHits)
                assertTrue(certified.complementary)
                assertEquals(listOf(1L, 0L, 1L, 0L).map(BigFraction::ofLong), certified.witness?.primal)
                // The second row's slack gives c*x = -x0 + 30*x1 + 42*x3 + 18*s1 >= -1.
                assertEquals(BigFraction.MINUS_ONE, certified.bound?.value)
            }
        }
    }

    @Test
    fun `cyclic primal pivots cannot replenish the iteration allowance`() {
        for (limit in listOf(6, 64)) {
            RevisedSimplex(
                model,
                iterationLimit = limit,
                scalingOptions = LpScalingOptions(enabled = false),
            ).use { solver ->
                val result = solver.solvePrimal()

                assertNull(result)
                assertEquals(limit, solver.lastPivots)
                assertEquals(1, solver.lastNumericalMetrics.capExits)
            }
        }
    }

    @Test
    fun `cyclic primal refactors share the work allowance`() {
        RevisedSimplex(
            model,
            workLimit = 1000L,
            scalingOptions = LpScalingOptions(enabled = false),
        ).use { solver ->
            val result = solver.solvePrimal()

            assertNull(result)
            assertTrue(solver.lastRefactorizations > 1)
            assertTrue(solver.lastWorkOps >= 1000L)
            assertEquals(1, solver.lastNumericalMetrics.capExits)
        }
    }

    @Test
    fun `cancellation during a primal cycle withholds the exact state`() {
        val working = assertNotNull(LpExactState(assertNotNull(model.authoritativeModel())).toWorkingModel())
        lateinit var solver: RevisedSimplex
        solver = RevisedSimplex(
            working,
            cancellation = Cancellation { solver.lastPivots >= 6 },
            scalingOptions = LpScalingOptions(enabled = false),
        )
        solver.use {
            val result = solver.solvePrimal()

            assertNull(result)
            assertTrue(solver.lastPivots >= 6)
            assertNull(solver.solvedExactState)
            assertNull(solver.infeasibleRay)
        }
    }
}
