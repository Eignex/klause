package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.ComponentLpSolver
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.integerDualLowerBoundCeil
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.lp.engine.newLpSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.LpStatsSink
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpComponentsTest {

    @Test
    fun `a separable model should stitch to the monolithic optimum`() {
        // Two independent blocks plus an isolated costed column.
        val b = LpBuilder()
        val x0 = b.addVar(0L, 10L, cost = 1L)
        val x1 = b.addVar(0L, 10L, cost = -1L)
        val y0 = b.addVar(0L, 10L, cost = 2L)
        b.addVar(0L, 4L, cost = -3L) // isolated: rides to its upper bound
        b.addRow(intArrayOf(x0, x1), longArrayOf(1L, 1L), Relation.GE, 6L)
        b.addRow(intArrayOf(y0), longArrayOf(1L), Relation.GE, 3L)
        val model = b.build(Sense.MINIMIZE)

        val split = newLpSolver(model).solvePrimal(null)
        val mono = newLpSolver(model, componentSplit = false).solvePrimal(null)
        assertNotNull(split)
        assertNotNull(mono)
        assertEquals(mono.objective, split.objective, 1e-9)
        for (j in 0 until model.n) assertEquals(mono.primal[j], split.primal[j], 1e-9, "primal[$j]")
    }

    @Test
    fun `component solve reports component route work`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 1L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 4L)
        b.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 5L)
        val sink = LpStatsSink()

        val result = solveAndCertify(b.build(Sense.MINIMIZE), observer = sink.certificationObserver())

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(1.0, sink.snapshot().componentPasses.sum)
    }

    @Test
    fun `random separable models should stitch to the monolithic optimum`() {
        val rng = Random(7)
        repeat(40) {
            val b = LpBuilder()
            val blocks = 2 + rng.nextInt(3)
            repeat(blocks) {
                val v0 = b.addVar(0L, 1L + rng.nextInt(9), cost = rng.nextLong(-4L, 5L))
                val v1 = b.addVar(0L, 1L + rng.nextInt(9), cost = rng.nextLong(-4L, 5L))
                b.addRow(
                    intArrayOf(v0, v1),
                    longArrayOf(1L + rng.nextInt(3).toLong(), 1L),
                    Relation.GE,
                    rng.nextLong(0L, 6L),
                )
                if (rng.nextBoolean()) b.addRow(intArrayOf(v0), longArrayOf(1L), Relation.LE, 1L + rng.nextLong(8L))
            }
            val model = b.build(Sense.MINIMIZE)
            val split = newLpSolver(model).solvePrimal(null)
            val mono = newLpSolver(model, componentSplit = false).solvePrimal(null)
            if (mono == null) {
                assertNull(split, "split must fail exactly where monolithic fails")
            } else {
                assertNotNull(split)
                assertEquals(mono.objective, split.objective, 1e-6)
            }
        }
    }

    @Test
    fun `an infeasible block should certify the whole model infeasible`() {
        val b = LpBuilder()
        val ok = b.addVar(0L, 10L)
        val bad = b.addVar(0L, 10L)
        b.addRow(intArrayOf(ok), longArrayOf(1L), Relation.LE, 9L)
        b.addRow(intArrayOf(bad), longArrayOf(1L), Relation.GE, 5L)
        b.addRow(intArrayOf(bad), longArrayOf(1L), Relation.LE, 2L)
        val model = b.build(Sense.MINIMIZE)
        val solver = newLpSolver(model)
        assertIs<ComponentLpSolver>(solver)
        assertNull(solver.solve(null))
        val ray = solver.infeasibleRay
        assertNotNull(ray, "the infeasible block's ray scatters to the full model")
        assertEquals(model.m, ray.size)
        assertNotNull(integerFarkasRay(model, ray), "the scattered ray certifies on the full model")
    }

    @Test
    fun `stitched duals should certify the objective bound on the full model`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 3L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 4L)
        b.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 5L)
        val model = b.build(Sense.MINIMIZE)
        val solver = newLpSolver(model)
        assertIs<ComponentLpSolver>(solver)
        val result = assertNotNull(solver.solve(null))
        assertTrue(abs(result.objective - 22.0) < 1e-9)
        val ceil = integerDualLowerBoundCeil(model, result.duals)
        assertEquals(22L, ceil, "the concatenated dual vector is a valid full-model certificate")
    }

    @Test
    fun `a single-component model should keep the monolithic engine`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L)
        val y = b.addVar(0L, 10L)
        b.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.LE, 7L)
        val model = b.build(Sense.MINIMIZE)
        assertIs<RevisedSimplex>(newLpSolver(model))
    }

    @Test
    fun `an isolated probe-clamped column should ride to the probe exactly like the engine`() {
        // A free-upper isolated column carries the probe stand-in bound, so both paths ride it there
        // and the probe-clamp flag lets downstream bound extraction reject the frontier value.
        val b = LpBuilder()
        val x = b.addVar(0L, 10L)
        val y = b.addVar(0L, 10L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.LE, 9L)
        b.addRow(intArrayOf(y), longArrayOf(1L), Relation.LE, 9L)
        val free = b.addFreeVar(0L, null, cost = -1L)
        val model = b.build(Sense.MINIMIZE)
        val split = newLpSolver(model).solvePrimal(null)
        val mono = newLpSolver(model, componentSplit = false).solvePrimal(null)
        assertNotNull(split)
        assertNotNull(mono)
        assertEquals(mono.primal[free], split.primal[free], 1e-3)
        assertEquals(mono.objective, split.objective, 1e-3)
    }

    @Test
    fun `a solve should report how many components it decomposed into`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 3L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 4L)
        b.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 5L)
        val model = b.build(Sense.MINIMIZE)

        val split = assertNotNull(newLpSolver(model).solve(null))
        val mono = assertNotNull(newLpSolver(model, componentSplit = false).solve(null))

        assertEquals(2, split.blocks)
        assertEquals(1, mono.blocks, "a monolithic solve reports one block")
    }

    @Test
    fun `component bases certify a continuous model beyond the monolithic row cap`() {
        val builder = LpBuilder()
        repeat(50) {
            val column = builder.addRealVar(0.0, 1.0)
            builder.addRealRow(intArrayOf(column), doubleArrayOf(1.0), Relation.LE, 1.0)
        }
        val model = builder.build(Sense.MINIMIZE)
        val solver = assertIs<ComponentLpSolver>(newLpSolver(model))
        val result = assertNotNull(solver.solve())

        assertEquals(null, exactBasisFeasible(model, result.basis))
        assertNotNull(solver.exactWitness())
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, solveAndCertify(model).verdict)
    }

    @Test
    fun `component certificates sum before rounding the objective`() {
        val builder = LpBuilder()
        val first = builder.addVar(0L, 1L, cost = 1L)
        val second = builder.addVar(0L, 1L, cost = 1L)
        builder.addRow(intArrayOf(first), longArrayOf(2L), Relation.GE, 1L)
        builder.addRow(intArrayOf(second), longArrayOf(2L), Relation.GE, 1L)
        val model = builder.build(Sense.MINIMIZE)
        val solver = assertIs<ComponentLpSolver>(newLpSolver(model))

        assertNotNull(solver.solve())
        assertEquals(BigFraction.ONE, solver.exactBound()?.value)
    }

    @Test
    fun `an interrupted component cannot promote a mixed proof to an attained optimum`() {
        val model = LpBuilder().apply {
            repeat(2) {
                val x = addVar(0L, 3L, cost = 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            }
        }.build(Sense.MINIMIZE)
        var index = 0
        val solver = assertNotNull(
            componentLpSolverOrNull(
                model,
                com.eignex.klause.util.Cancellation.Never,
                { part, token ->
                    if (index++ == 0) {
                        ProductionLpEngineFactory.newGeneralSolver(part, token)
                    } else {
                        object : LpSolver {
                            override val infeasibleRay: DoubleArray? = null
                            override fun solve(warm: Basis?) = FloatLpResult(
                                Basis(intArrayOf(1), arrayOf(VarStatus.AT_UPPER, VarStatus.BASIC)),
                                3.0,
                                doubleArrayOf(0.0),
                                doubleArrayOf(3.0),
                                optimal = false,
                            )
                            override fun solvePrimal(warm: Basis?) = solve(warm)
                        }
                    }
                },
            ),
        )

        solver.use {
            val hint = assertNotNull(it.solve())
            assertEquals(false, hint.optimal)
            val result = certifyLpResult(model, it, hint)
            assertEquals(LpVerdict.FEASIBLE, result.verdict)
            assertEquals(listOf(BigFraction.ONE, BigFraction.ofLong(3L)), result.exactPrimal)
            assertEquals(BigFraction.ofLong(4L), result.witness?.objective)
            assertEquals(BigFraction.ONE, result.lowerBound)
        }
    }

    @Test
    fun `component bounds decline objectives with omitted slack costs`() {
        val model = LpBuilder().apply {
            repeat(2) {
                val x = addVar(0L, 3L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            }
        }.build(Sense.MINIMIZE)
        for (column in model.n until model.numVars) model.cost[column] = -1L

        assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            assertNotNull(solver.solve())
            assertNull(solver.exactBound())
            val point = assertNotNull(checkedLpWitness(model, List(2) { BigFraction.ofLong(3L) }))
            assertEquals(BigFraction.ofLong(-4L), point.objective)
        }
    }

    @Test
    fun `a component bound loses authority when the shared objective changes`() {
        val model = LpBuilder().apply {
            repeat(2) {
                val x = addVar(0L, 3L, cost = 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            }
        }.build(Sense.MINIMIZE)

        assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            assertNotNull(solver.solve())
            assertEquals(BigFraction.ofLong(2L), solver.exactBound()?.value)
            model.cost[0] = -1L
            assertEquals(null, solver.exactBound())
        }
        assertEquals(BigFraction.ofLong(-2L), solveAndCertify(model).lowerBound)
    }
}
