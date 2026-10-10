package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RevisedSimplexObjectiveWarmStartTest {
    @Test
    fun `unbounded objective candidates reuse feasible factors after objective adoption`() {
        val builder = LpBuilder()
        val x = builder.addOpenAboveVar(0, cost = -1)
        val y = builder.addOpenAboveVar(0)
        builder.addRow(intArrayOf(x, y), longArrayOf(1, -1), Relation.EQ, 1)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { solver ->
            assertNull(solver.solvePrimal())
            assertEquals(LpFloatTermination.UNBOUNDED_CANDIDATE, solver.lastTermination)
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(one, zero, zero))))
            assertTrue(solver.adopt(trail.state, Cancellation.Never))

            val result = assertNotNull(solver.resolveBounds())

            assertEquals(1.0, result.objective)
            assertEquals(1, solver.lastMetrics.objectiveWarmHits)
            assertEquals(0, result.refactorizations)
            assertEquals(BigFraction.ONE,
                certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result).lowerBound)
        }
    }

    @Test
    fun `bounding an unbounded objective repairs its primal basis before reporting an optimum`() {
        val builder = LpBuilder()
        val x = builder.addOpenAboveVar(0, cost = -1)
        val y = builder.addOpenAboveVar(0)
        builder.addRow(intArrayOf(x, y), longArrayOf(1, -1), Relation.EQ, 1)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { solver ->
            assertNull(solver.solvePrimal())
            assertTrue(trail.assertBound(x, true, ExactLpSide(ExactLpNumber.of(3L)), 0L))
            assertTrue(solver.adopt(trail.state, Cancellation.Never))

            val result = assertNotNull(solver.resolveBounds())

            assertEquals(-3.0, result.objective)
            assertEquals(3.0, result.primal[x])
            assertEquals(2.0, result.primal[y])
            assertEquals(BigFraction.ofLong(-3L),
                certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result).lowerBound)
        }
    }

    @Test
    fun `mixed seats retain exact bounds and costs through repeated objective changes`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        val exact = ExactLpModel(
            List(4) { listOf(ExactLpEntry(0, one)) },
            listOf(ExactLpNumber.of(10L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(three))),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(-1L)))),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(three), ExactLpSide(three))),
                ExactLpColumn(ExactLpBounds()),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, ExactLpNumber.of(-1L), zero, one, zero)),
        )
        val state = LpExactState(exact)
        val trail = LpBoundTrail(state)
        val solver = RevisedSimplex(
            assertNotNull(state.toWorkingModel()),
        )
        repeat(4) { index ->
            val cost = if (index % 2 == 0) one else ExactLpNumber.of(-1L)
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(cost, ExactLpNumber.of(-1L), zero, one, zero))))
            assertTrue(solver.adopt(trail.state, Cancellation.Never))

            val result = assertNotNull(solver.resolveBounds())

            assertEquals(if (index % 2 == 0) 4.0 else 1.0, result.objective, 1e-9)
            assertEquals(-1.0, result.primal[1])
            assertEquals(0.0, result.primal[2])
            assertEquals(3.0, result.primal[3])
            assertEquals(if (index % 2 == 0) 0.0 else 3.0, result.primal[0])
            assertSame(trail.state, result.exactState)
            assertEquals(
                BigFraction.ofLong(if (index % 2 == 0) 4L else 1L),
                certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result).lowerBound,
            )
        }
        solver.close()
    }

    private val zero = ExactLpNumber.of(0L)
    private val one = ExactLpNumber.of(1L)
    private val four = ExactLpNumber.of(4L)

    private fun state(cost: ExactLpNumber = one): LpExactState = LpExactState(
        ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(four))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(cost, zero)),
        ),
    )

    @Test
    fun `objective replacement reuses a feasible basis through the primal pass`() {
        val source = state()
        LpScopedSolver(source).use { owner ->
            assertEquals(BigFraction.ZERO, assertNotNull(owner.solve()).lowerBound)

            assertTrue(owner.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero)), source))
            val replaced = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(-1L), replaced.lowerBound)
            assertEquals(listOf(BigFraction.ONE), replaced.exactPrimal)
            assertEquals(1, owner.lastMetrics.objectiveWarmAttempts)
            assertEquals(1, owner.lastMetrics.objectiveWarmHits)
            assertEquals(0, owner.lastMetrics.objectiveWarmRepairs)
            assertEquals(0, owner.lastMetrics.primalRefactorizations)
        }
    }

    @Test
    fun `a truncated infeasible hint runs phase one before the new objective`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 4L, cost = -1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        val model = builder.build(Sense.MINIMIZE)
        val slack = model.slackCol(0)
        val statuses = Array(model.numVars) { VarStatus.AT_LOWER }
        statuses[slack] = VarStatus.BASIC

        val result = assertNotNull(RevisedSimplex(model).solve(Basis(intArrayOf(slack), statuses)))

        assertEquals(-4.0, result.objective, 1e-9)
        assertTrue(result.pivots > 0)
    }

    @Test
    fun `simultaneous objective and bound changes use bounded primal repair`() {
        val source = state()
        LpScopedSolver(source).use { owner ->
            assertNotNull(owner.solve())
            assertTrue(owner.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero))))
            assertTrue(owner.assertBound(0, false, ExactLpSide(ExactLpNumber.ofIeee(0.5)), 0L))

            val result = assertNotNull(owner.solve())

            assertEquals(BigFraction.ofLong(-1L), result.lowerBound)
            assertEquals(1, owner.lastMetrics.objectiveWarmAttempts)
            assertEquals(1, owner.lastMetrics.objectiveWarmRepairs)
        }
    }

    @Test
    fun `primal repair honors cancellation and work limits`() {
        val builder = LpBuilder()
        repeat(16) {
            val column = builder.addVar(0L, 2L, cost = -1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.LE, 1L)
        }
        val model = builder.build(Sense.MINIMIZE)

        val cancelled = RevisedSimplex(model, cancellation = Cancellation { true }).solvePrimal()
        val exhausted = RevisedSimplex(model, workLimit = 1L).solvePrimal()

        assertNull(cancelled)
        assertNull(exhausted)
    }
}
