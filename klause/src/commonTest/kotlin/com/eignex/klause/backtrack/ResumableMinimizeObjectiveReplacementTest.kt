package com.eignex.klause.backtrack

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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResumableMinimizeObjectiveReplacementTest {
    @Test
    fun `objective replacement should retract cutoff facts and reevaluate a carried incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val params = BacktrackParams(lpPlan = LpPlan(bounding = true), randomSeed = 17L)
        val solver = BacktrackSolver(problem)
        var search = ResumableMinimize(solver, LinearObjective(intCoefficients = longArrayOf(1L)), params)
        try {
            val first = assertIs<MinimizeResult.Optimal>(search.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(0L, first.sample.ints[0])
            search = search.replacingObjective(
                LinearObjective(intCoefficients = longArrayOf(-1L), constant = 7L),
                params,
            )
            val offered = ArrayList<Double>()
            val second = assertIs<MinimizeResult.Optimal>(
                search.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.objective
                },
            )
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

    @Test
    fun `mixed objective replacement should recertify real coordinates from the original region`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = arrayOf<Factor>(
                Linear(
                    intVars = intArrayOf(0),
                    intCoeffs = doubleArrayOf(1.0),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.EQ,
                    bound = 1.5,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        ).bake()
        val params = BacktrackParams(lpPlan = LpPlan(bounding = true), randomSeed = 17L)
        val first = ResumableMinimize(
            BacktrackSolver(problem),
            LinearObjective(realCoefficients = doubleArrayOf(1.0)),
            params,
        )
        val initial = assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
        assertEquals(1L, initial.sample.ints[0])
        assertEquals(0.5, initial.sample.reals[0])
        first.replacingObjective(LinearObjective(realCoefficients = doubleArrayOf(-1.0)), params).use { second ->
            val offered = ArrayList<Double>()
            val result = assertIs<MinimizeResult.Optimal>(
                second.runSlice(Cancellation.Never, 1000L, 256L) {
                    offered += it.sample.ints[0] + it.sample.reals[0]
                },
            )
            assertTrue(offered.all { it == 1.5 })
            assertEquals(1.5, result.sample.reals[0])
            assertEquals(-1.5, result.objective)
            assertEquals(0L, result.sample.ints[0])
        }
    }

    @Test
    fun `a carried incumbent should satisfy replacement assumptions and deductions`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        for (assumptions in listOf(Assumptions.None.withInt(0, 3L), Assumptions.None.withTightenedMin(0, 2L))) {
            val params = BacktrackParams(randomSeed = 0L)
            val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
            assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
            first.replacingObjective(objective, params.copy(assumptions = assumptions)).use { second ->
                val offered = ArrayList<Long>()
                val result = assertIs<MinimizeResult.Optimal>(
                    second.runSlice(Cancellation.Never, 1000L, 256L) {
                        offered += it.sample.ints[0]
                    },
                )
                assertTrue(offered.all { it >= 2L })
                assertEquals(if (assumptions.numInts > 0) 3L else 2L, result.sample.ints[0])
                second.replacingObjective(objective, params).use { third ->
                    val restored = assertIs<MinimizeResult.Optimal>(third.runSlice(Cancellation.Never, 1000L, 256L) {})
                    assertEquals(0L, restored.sample.ints[0])
                }
            }
        }
    }

    @Test
    fun `contradictory replacement deductions should discard a carried incumbent`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
        assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
        first.replacingObjective(
            objective,
            params.copy(assumptions = Assumptions.None.withTightenedMin(0, 4)),
        ).use { second ->
            var offers = 0
            assertIs<MinimizeResult.Infeasible>(second.runSlice(Cancellation.Never, 1000L, 256L) { offers++ })
            assertEquals(0, offers)
        }
    }

    @Test
    fun `replacement should be able to remove an earlier boolean root assumption`() {
        val problem = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val first = ResumableMinimize(BacktrackSolver(problem), objective, params)
        first.replacingObjective(
            objective,
            params.copy(assumptions = Assumptions.None.withBool(0, true)),
        ).use { second ->
            val restricted = assertIs<MinimizeResult.Optimal>(second.runSlice(Cancellation.Never, 1000L, 256L) {})
            assertEquals(true, restricted.sample.bools[0])
            second.replacingObjective(objective, params).use { third ->
                val restored = assertIs<MinimizeResult.Optimal>(third.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(false, restored.sample.bools[0])
            }
        }
    }

    @Test
    fun `a cancelled replacement should leave the original search usable`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        for (initiallyCancelled in listOf(true, false)) {
            var cancelled = initiallyCancelled
            val exchange = onSearchStart { cancelled = true }
            ResumableMinimize(BacktrackSolver(problem), objective, params).use { first ->
                assertFailsWith<CancellationException> {
                    first.replacingObjective(
                        objective,
                        params.copy(cancellation = Cancellation { cancelled }, clauseExchange = exchange),
                    )
                }
                val result = assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
                assertEquals(0L, result.sample.ints[0])
                first.replacingObjective(objective, params).use { replacement ->
                    assertIs<MinimizeResult.Optimal>(replacement.runSlice(Cancellation.Never, 1000L, 256L) {})
                }
            }
        }
    }

    @Test
    fun `a throwing publication token should leave the original search usable`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0L, 3L)), emptyArray()).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1L))
        val params = BacktrackParams(randomSeed = 0L)
        val failure = IllegalStateException("publication token")
        var initialized = false
        val exchange = onSearchStart { initialized = true }
        val token = Cancellation {
            if (initialized) throw failure
            false
        }
        ResumableMinimize(BacktrackSolver(problem), objective, params).use { first ->
            val thrown = assertFailsWith<IllegalStateException> {
                first.replacingObjective(objective, params.copy(cancellation = token, clauseExchange = exchange))
            }
            assertSame(failure, thrown)
            assertIs<MinimizeResult.Optimal>(first.runSlice(Cancellation.Never, 1000L, 256L) {})
            first.replacingObjective(objective, params).use { replacement ->
                assertIs<MinimizeResult.Optimal>(replacement.runSlice(Cancellation.Never, 1000L, 256L) {})
            }
        }
    }
}
