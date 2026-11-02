package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexTest {

    @Test
    fun `basis directional costs preserve source units and seated directions`() {
        for (upperSeated in listOf(false, true)) for ((coefficient, objectiveScale) in
            listOf(2L to 1L, 2048L to 1L, 2L to 4L)) {
            val builder = LpBuilder()
            val x = builder.addVar(0, 2)
            val down = builder.addVar(0, 1, cost = if (upperSeated) -3 else 3)
            val up = builder.addVar(0, 1, cost = 5)
            builder.addRow(intArrayOf(x, down, up),
                longArrayOf(coefficient, if (upperSeated) -coefficient else coefficient, -coefficient),
                Relation.EQ, if (upperSeated) -coefficient / 2 else coefficient / 2)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val objective = trail.state.model.objective
            assertTrue(trail.replaceObjective(ExactLpObjective(List(objective.size) { objective.cost(it) },
                scale = ExactLpNumber.of(objectiveScale), externalConstant = ExactLpNumber.of(1L shl 60))))
            val model = assertNotNull(trail.state.ownerWorkingModel())
            RevisedSimplex(model).use { solver ->
                val optimum = assertNotNull(solver.solve())
                assertEquals(0.5, optimum.primal[x], 1e-8)
                var work = 0L

                val costs = assertNotNull(solver.directionalCosts(model, intArrayOf(x), 100_000L,
                    Cancellation.Never) { work += it })

                assertEquals(3.0 / objectiveScale, assertNotNull(costs.single()).down, 1e-7)
                assertEquals(5.0 / objectiveScale, assertNotNull(costs.single()).up, 1e-7)
                assertTrue(work in 1L..100_000L)
            }
        }
    }

    @Test
    fun `unavailable basis estimates spend no analysis allowance`() {
        for (decline in listOf("unfunded", "cancelled", "stale", "closed", "zero", "too-many")) {
            val model = halfIntegralModel()
            RevisedSimplex(model).use { solver ->
                assertNotNull(solver.solve())
                if (decline == "closed") solver.close()
                var work = 0L
                val columns = if (decline == "too-many") intArrayOf(0, 1, 2) else intArrayOf(0)

                val costs = solver.directionalCosts(if (decline == "stale") halfIntegralModel() else model,
                    columns, when (decline) { "unfunded" -> 1L; "zero" -> 0L; else -> 100_000L },
                    Cancellation { decline == "cancelled" }) { work += it }

                assertNull(costs, decline)
                assertEquals(0L, work, decline)
            }
        }
    }

    @Test
    fun `basis estimates preserve the retained optimum and solve metrics`() {
        val model = halfIntegralModel()
        RevisedSimplex(model).use { solver ->
            val optimum = assertNotNull(solver.solve())
            val metrics = solver.lastMetrics
            val basis = assertNotNull(solver.retainedBasis())

            solver.directionalCosts(model, intArrayOf(0), 100_000L, Cancellation.Never) { assertTrue(it > 0L) }

            assertEquals(metrics, solver.lastMetrics)
            assertEquals(basis.basicVars.toList(), solver.retainedBasis()?.basicVars?.toList())
            assertEquals(basis.status.toList(), solver.retainedBasis()?.status?.toList())
            assertEquals(optimum.objective, solver.resolveBounds()?.objective)
        }
    }

    @Test
    fun `zero right hand sides should fit an allowance for factorization and setup`() {
        for (size in listOf(2, 8)) {
            val model = LpBuilder().apply {
                repeat(size) {
                    val variable = addVar(0L, 1L)
                    addRow(intArrayOf(variable), longArrayOf(1L), Relation.EQ, 0L)
                }
            }.build(Sense.MINIMIZE)
            val allowance = 2L * size + 1L

            RevisedSimplex(model, workLimit = allowance).use { solver ->
                val result = assertNotNull(solver.solve())

                assertTrue(result.optimal, "size=$size termination=${solver.lastTermination}")
                assertTrue(result.primal.all { it == 0.0 })
                assertTrue(solver.lastMetrics.workOps in 1L..allowance,
                    "size=$size work=${solver.lastMetrics.workOps} allowance=$allowance")
            }
        }
    }

    @Test
    fun `sliced solves should preserve the numerical path and certified result`() {
        for (original in listOf(coverModel(), infeasibleModel(), halfIntegralModel())) {
            val model = assertNotNull(LpExactState(assertNotNull(original.authoritativeModel())).ownerWorkingModel())
            RevisedSimplex(model).use { cold ->
                val expected = cold.solve()
                val expectedMetrics = cold.lastMetrics
                val proof = certifyLpResult(model, cold, expected)
                RevisedSimplex(model).use { sliced ->
                    sliced.openSolve().use { cursor ->
                        var step = cursor.advance(1L)
                        var pauses = 0
                        while (step is LpSolveStep.Paused && pauses < 64) {
                            pauses++
                            assertNull(sliced.solvedExactState)
                            step = cursor.advance(1L)
                        }
                        val result = assertIs<LpSolveStep.Completed<FloatLpResult?>>(step).value

                        assertTrue(pauses > 0)
                        assertEquals(expected?.objective, result?.objective)
                        assertEquals(expected?.primal?.toList(), result?.primal?.toList())
                        assertEquals(expectedMetrics.pivots, sliced.lastMetrics.pivots)
                        assertEquals(expectedMetrics.workOps, sliced.lastMetrics.workOps)
                        assertEquals(
                            expectedMetrics.initialRefactorizations,
                            sliced.lastMetrics.initialRefactorizations,
                        )
                        assertEquals(
                            expectedMetrics.updateLimitRefactorizations,
                            sliced.lastMetrics.updateLimitRefactorizations,
                        )
                        assertEquals(proof.verdict, certifyLpResult(model, sliced, result).verdict)
                    }
                }
            }
        }
    }

    @Test
    fun `resuming a cursor should preserve the invocation work limit`() {
        val model = halfIntegralModel()
        val limit = RevisedSimplex(model).use { owner ->
            owner.openSolve().use { cursor ->
                assertIs<LpSolveStep.Paused>(cursor.advance(shouldPause = { true }))
                owner.currentWork
            }
        }
        assertTrue(limit > 0L)
        RevisedSimplex(model, workLimit = limit).use { uninterrupted ->
            val expected = uninterrupted.solve()
            RevisedSimplex(model, workLimit = limit).use { sliced ->
                sliced.openSolve().use { cursor ->
                    assertIs<LpSolveStep.Paused>(cursor.advance(shouldPause = { true }))

                    val result = assertIs<LpSolveStep.Completed<FloatLpResult?>>(cursor.advance()).value

                    assertEquals(expected?.objective, result?.objective)
                    assertEquals(expected?.primal?.toList(), result?.primal?.toList())
                    assertEquals(LpFloatTermination.WORK, sliced.lastTermination)
                    assertEquals(uninterrupted.lastMetrics.workOps, sliced.lastMetrics.workOps)
                    assertEquals(uninterrupted.lastMetrics.pivots, sliced.lastMetrics.pivots)
                }
            }
        }
    }

    @Test
    fun `closing an unopened cursor should release the solve exclusion`() {
        RevisedSimplex(halfIntegralModel()).use { solver ->
            val cursor = solver.openSolve()

            cursor.close()
            val result = assertNotNull(solver.solve())

            assertEquals(1.5, result.primal.single(), 1e-9)
        }
    }

    @Test
    fun `cancellation during a pause should publish no numerical result`() {
        var cancelled = false
        RevisedSimplex(halfIntegralModel(), cancellation = Cancellation { cancelled }).use { solver ->
            solver.openSolve().use { cursor ->
                assertIs<LpSolveStep.Paused>(cursor.advance(shouldPause = { true }))

                cancelled = true
                val step = assertIs<LpSolveStep.Completed<FloatLpResult?>>(cursor.advance())

                assertNull(step.value)
                assertEquals(LpFloatTermination.CANCELLED, solver.lastTermination)
                assertNull(solver.solvedExactState)
            }
        }
    }

    @Test
    fun `gated resolve should match a fresh solve of the enforced submodel`() {
        val rng = Random(20260729)
        var checked = 0
        repeat(10) {
            val n = rng.nextInt(3, 7)
            val m = rng.nextInt(4, 10)
            val uppers = LongArray(n) { rng.nextLong(2, 8) }
            val rowCoeffs = Array(m) { LongArray(n) { rng.nextLong(-3, 4) } }
            val rowRhs = LongArray(m) { rng.nextLong(-6, 12) }
            val persistent = RevisedSimplex(zeroCostModel(uppers, rowCoeffs, rowRhs, BooleanArray(m) { true }))
            val enforced = BooleanArray(m)
            repeat(20) {
                for (i in 0 until m) enforced[i] = rng.nextBoolean()
                val gated = persistent.resolveGated(enforced) != null
                val kept = enforced.copyOf()
                val fresh = RevisedSimplex(zeroCostModel(uppers, rowCoeffs, rowRhs, kept)).solve() != null
                checked++
                assertTrue(gated == fresh, "gated=$gated fresh=$fresh for enforcement ${kept.toList()}")
            }
        }
        assertEquals(200, checked)
    }

    @Test
    fun `gomory cuts should separate a fractional optimum without cutting integer points`() {
        val simplex = RevisedSimplex(halfIntegralModel())
        assertEquals(1.5, requireNotNull(simplex.solve()).primal[0], 1e-9)

        val cuts = simplex.gomoryCuts(8)

        assertTrue(cuts.isNotEmpty())
        assertKeepsIntegerPoints(cuts)
    }

    @Test
    fun `mir cuts should separate a fractional optimum without cutting integer points`() {
        val simplex = RevisedSimplex(halfIntegralModel())
        assertEquals(1.5, requireNotNull(simplex.solve()).primal[0], 1e-9)

        val cuts = simplex.mirCuts(8)

        assertTrue(cuts.isNotEmpty())
        assertKeepsIntegerPoints(cuts)
    }

    @Test
    fun `deferred diagnostics should retain scaled source arithmetic validation`() {
        val model = LpBuilder().apply {
            val x = addVar(0L, 2L, cost = -1L)
            addRow(intArrayOf(x), longArrayOf(1_000_000L), Relation.LE, 1_500_000L)
        }.build(Sense.MINIMIZE)

        RevisedSimplex(model).use { solver ->
            solver.deferUnscaledSourceDiagnostics()
            val result = assertNotNull(solver.solve())

            assertEquals(1.5, result.primal.single(), 1e-9)
            assertTrue(solver.scalingMetrics.applied)
            assertEquals(1L, solver.lastMetrics.sourceResidualCalls)
            assertEquals(1L, solver.lastMetrics.sourceResidualScaledCalls)
        }
    }

    @Test
    fun `a persistent reused basis should record a warm attempt before its hit`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 4L, cost = 1L)
        val y = b.addVar(0L, 4L, cost = 2L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        val solver = newPersistentLpSolver(b.build(Sense.MINIMIZE))

        assertNotNull(solver.resolveBounds())
        assertEquals(1, solver.lastMetrics.warmAttempts)
        assertEquals(0, solver.lastMetrics.warmHits)
        assertNotNull(solver.resolveBounds())

        assertEquals(1, solver.lastMetrics.warmAttempts)
        assertEquals(1, solver.lastMetrics.warmHits)
    }

    @Test
    fun `a solve that returns no result should still report the cost it spent`() {
        val simplex = RevisedSimplex(infeasibleModel())

        assertNull(simplex.solve(null))

        assertTrue(simplex.lastPivots > 0)
        assertTrue(simplex.lastRefactorizations > 0)
    }

    @Test
    fun `a primal solve should clear the prior infeasibility artifacts`() {
        val simplex = RevisedSimplex(infeasibleModel())
        assertNull(simplex.solve())
        assertNotNull(simplex.infeasibleRay)

        simplex.solvePrimal()

        assertNull(simplex.infeasibleRay)
        assertNull(simplex.infeasibleBasis)
        assertEquals(-1, simplex.infeasibleRow)
    }

    @Test
    fun `a solve stopped at its pivot budget should still bound the optimum from below`() {
        val full = assertNotNull(RevisedSimplex(coverModel()).solve(null))
        assertTrue(full.pivots >= 2, "fixture must cost more than one pivot to cap meaningfully")

        val capped = assertNotNull(RevisedSimplex(coverModel(), iterationLimit = 1).solve(null))

        assertTrue(capped.pivots < full.pivots)
        assertTrue(capped.objective <= full.objective + 1e-9)
    }

    @Test
    fun `a solve stopped at its pivot budget should not claim an optimum`() {
        val full = assertNotNull(RevisedSimplex(coverModel()).solve(null))

        val capped = assertNotNull(RevisedSimplex(coverModel(), iterationLimit = 1).solve(null))

        assertTrue(full.optimal)
        assertFalse(capped.optimal)
    }

    @Test
    fun `triangular crash should reach the certified cold optimum with fewer pivots`() {
        val builder = LpBuilder()
        repeat(8) {
            val column = builder.addVar(0L, 10L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val objective = builder.addVar(0L, 4L, cost = 1L)
        val model = builder.build(Sense.MINIMIZE)
        val crash = assertNotNull(triangularCrashBasis(model).basis)

        val cold = assertNotNull(RevisedSimplex(model).solve())
        val crashed = assertNotNull(RevisedSimplex(model).solve(crash))

        assertEquals(cold.objective, crashed.objective, 1e-9)
        assertEquals(0.0, crashed.primal[objective], 1e-9)
        assertTrue(crashed.pivots < cold.pivots, "crash ${crashed.pivots}, cold ${cold.pivots}")
        assertTrue(crashed.warmStarted)
    }

    @Test
    fun `singular and malformed hints should fall back to a valid cold basis`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        val y = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(x, y), longArrayOf(2L, 2L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val statuses = Array(model.numVars) { VarStatus.AT_LOWER }
        statuses[x] = VarStatus.BASIC
        statuses[y] = VarStatus.BASIC

        val result = assertNotNull(RevisedSimplex(model).solve(Basis(intArrayOf(x, y), statuses)))

        val cold = assertNotNull(RevisedSimplex(model).solve())
        assertEquals(cold.objective, result.objective, 1e-9)
        assertTrue(result.warmStarted)
    }

    private fun halfIntegralModel(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0, 5, cost = 1)
        b.addRow(mapOf(x to 2L), Relation.LE, 3)
        return b.build(Sense.MAXIMIZE)
    }

    private fun assertKeepsIntegerPoints(cuts: List<Cut>) {
        for (xi in 0..1) {
            for (cut in cuts) {
                val lhs = cut.cols.indices.sumOf { cut.coeffs[it] * xi }
                assertTrue(lhs >= cut.rhs, "x=$xi violates a cut")
            }
        }
    }

    private fun infeasibleModel(): LpModel {
        val b = LpBuilder()
        val x = b.addVar(0L, 5L, cost = 1L)
        val y = b.addVar(0L, 5L, cost = 1L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 8L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.LE, 7L)
        return b.build(Sense.MINIMIZE)
    }

    private fun coverModel(): LpModel {
        val b = LpBuilder()
        val x1 = b.addVar(0L, 10L, cost = 1L)
        val x2 = b.addVar(0L, 10L, cost = 1L)
        val x3 = b.addVar(0L, 10L, cost = 1L)
        val x4 = b.addVar(0L, 10L, cost = 1L)
        b.addRow(intArrayOf(x1, x2), longArrayOf(1L, 1L), Relation.GE, 3L)
        b.addRow(intArrayOf(x2, x3), longArrayOf(1L, 1L), Relation.GE, 4L)
        b.addRow(intArrayOf(x3, x4), longArrayOf(1L, 1L), Relation.GE, 5L)
        b.addRow(intArrayOf(x1, x4), longArrayOf(1L, 1L), Relation.GE, 2L)
        return b.build(Sense.MINIMIZE)
    }

    private fun zeroCostModel(
        uppers: LongArray,
        rowCoeffs: Array<LongArray>,
        rowRhs: LongArray,
        keep: BooleanArray,
    ): LpModel {
        val b = LpBuilder()
        for (u in uppers) b.addVar(0L, u)
        val cols = IntArray(uppers.size) { it }
        for (i in rowCoeffs.indices) {
            if (keep[i]) b.addRow(cols, rowCoeffs[i], Relation.LE, rowRhs[i])
        }
        return b.build(Sense.MINIMIZE)
    }
}
