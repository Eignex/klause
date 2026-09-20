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

    @Test
    fun `objective adoption follows zero nonzero zero revisions`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val minusOne = ExactLpNumber.of(-1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, minusOne))),
            listOf(ExactLpNumber.of(-3L)),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(source)
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { solver ->
            assertEquals(BigFraction.ZERO, solveCurrent(solver, trail))

            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(minusOne, zero))))
            assertEquals(BigFraction.ofLong(-10L), solveCurrent(solver, trail))

            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(zero, zero))))
            assertEquals(BigFraction.ZERO, solveCurrent(solver, trail))
        }
    }

    private fun solveCurrent(solver: RevisedSimplex, trail: LpBoundTrail): BigFraction {
        assertTrue(solver.adopt(trail.state, Cancellation.Never))
        val result = assertNotNull(solver.resolveBounds())
        val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, result)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
        return assertNotNull(certified.lowerBound)
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
    fun `alternating objectives retain certified source results`() {
        val source = state()
        LpScopedSolver(source).use { owner ->
            assertNotNull(owner.solve())
            repeat(4) { iteration ->
                val cost = if (iteration % 2 == 0) ExactLpNumber.of(-1L) else one
                assertTrue(owner.replaceObjective(ExactLpObjective(listOf(cost, zero)), source))
                val result = assertNotNull(owner.solve())
                assertEquals(
                    if (cost.value.signum() < 0) BigFraction.ofLong(-1L) else BigFraction.ZERO,
                    result.lowerBound,
                )
                assertEquals(1, owner.lastMetrics.objectiveWarmHits, "objective swap $iteration")
            }
        }
    }

    @Test
    fun `warm objective sequence avoids cold factorization cost`() {
        val columns = 32
        val negativeOne = ExactLpNumber.of(-1L)
        val matrix = List(columns) { column -> listOf(ExactLpEntry(column, negativeOne)) }
        val sourceModel = ExactLpModel(
            matrix,
            List(columns) { negativeOne },
            List(columns) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))) } +
                List(columns) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false) },
            List(columns) { ExactLpRow() },
            ExactLpObjective(List(columns) { one } + List(columns) { zero }),
        )
        val source = LpExactState(sourceModel)
        val replacement = ExactLpObjective(
            List(columns) { ExactLpNumber.of(2L) } + List(columns) { zero },
        )
        val coldTrail = LpBoundTrail(source)
        assertTrue(coldTrail.replaceObjective(replacement))
        val coldModel = assertNotNull(coldTrail.state.toWorkingModel())
        val measurements = List(3) {
            val coldSolver = RevisedSimplex(coldModel)
            val cold = assertNotNull(coldSolver.solve())
            LpScopedSolver(source).use { owner ->
                assertNotNull(owner.solve())
                assertTrue(owner.replaceObjective(replacement, source))
                val warm = assertNotNull(owner.solve())
                assertEquals(cold.objective, assertNotNull(warm.float).objective, 1e-9)
                assertEquals(0, warm.float.refactorizations)
                owner.lastMetrics.workOps to coldSolver.lastWorkOps
            }
        }

        assertTrue(measurements.all { it.first < it.second }, "$measurements")
    }

    @Test
    fun `a stale objective hint is admitted through primal feasibility and reduced costs`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 4L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        val original = builder.build(Sense.MINIMIZE)
        val hint = assertNotNull(RevisedSimplex(original).solve()).basis
        val replacement = original.withRowObjective(intArrayOf(x), longArrayOf(-1L))

        val warm = assertNotNull(RevisedSimplex(replacement).solve(hint))
        val cold = assertNotNull(RevisedSimplex(replacement).solve())

        assertEquals(-4.0, warm.objective, 1e-9)
        assertEquals(cold.objective, warm.objective, 1e-9)
        assertTrue(warm.warmStarted)
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
