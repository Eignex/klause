package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.BasisArithmeticException
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.koblas.SparseMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class RevisedSimplexPricingTest {
    @Test
    fun `column pricing should match stored entry arithmetic including empty and zero columns`() {
        val builder = LpBuilder()
        repeat(3) { builder.addRealVar(0.0, 10.0) }
        builder.addRealRow(intArrayOf(0, 1), doubleArrayOf(2.0, 1.0), Relation.LE, 10.0)
        builder.addRealRow(intArrayOf(0), doubleArrayOf(-4.0), Relation.LE, 10.0)
        val model = builder.build(Sense.MINIMIZE)
        val view = assertNotNull(model.doubleView)
        view.colVal[view.colPtr[1]] = 0.0
        val duals = listOf(
            doubleArrayOf(0.25, -0.5),
            doubleArrayOf(-8.0, 0.125),
            doubleArrayOf(Double.POSITIVE_INFINITY, 1.0),
            doubleArrayOf(1.0, Double.NaN),
        )

        RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false)).use { solver ->
            for (y in duals) {
                for (j in 0 until model.n) {
                    var expected = 0.0
                    model.forEachInColumnD(j) { row, value -> expected += value * y[row] }
                    assertEquals(expected.toBits(), solver.columnDot(y, j).toBits(), "column $j")
                }
                for (i in 0 until model.m) {
                    assertEquals(y[i].toBits(), solver.columnDot(y, model.n + i).toBits())
                }
            }
        }
    }

    @Test
    fun `pricing should own its coefficient snapshot independently of the input model`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addRow(intArrayOf(0), longArrayOf(2L), Relation.LE, 10L)
        }.build(Sense.MINIMIZE)
        RevisedSimplex(model, scalingOptions = LpScalingOptions(enabled = false)).use { solver ->
            model.csc.colVal.fill(99L)

            assertEquals(6.0, solver.columnDot(doubleArrayOf(3.0), 0))
        }
    }

    @Test
    fun `pricing should read current owned coefficients without retaining a column copy`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L)
            addRow(intArrayOf(0), longArrayOf(2L), Relation.LE, 10L)
        }.build(Sense.MINIMIZE)
        lateinit var matrix: SparseMatrix
        RevisedSimplex(
            assertNotNull(LpExactState(assertNotNull(model.authoritativeModel())).toWorkingModel()),
            scalingOptions = LpScalingOptions(enabled = false),
            basisSolverFactory = { columns -> KotlinBasisSolver(columns).also { matrix = columns } },
        ).use { solver ->
            assertNotNull(solver.prepareLogicals(Cancellation.Never))
            assertEquals(6.0, solver.columnDot(doubleArrayOf(3.0), 0))

            matrix.values[0] = -4.0

            assertEquals(-12.0, solver.columnDot(doubleArrayOf(3.0), 0))
        }
    }

    @Test
    fun `pricing should follow the replacement matrix after an unscaled retry`() {
        val model = LpBuilder().apply {
            addRealVar(0.0, 10.0, cost = 1.0)
            addRealVar(0.0, 10.0, cost = 2.0)
            addRealRow(intArrayOf(0, 1), doubleArrayOf(1.0, 1024.0), Relation.GE, 3072.0)
        }.build(Sense.MINIMIZE)
        val owners = mutableListOf<SparseMatrix>()
        RevisedSimplex(model, basisSolverFactory = { matrix ->
            owners.add(matrix)
            val delegate = KotlinBasisSolver(matrix)
            if (owners.size == 1) {
                object : BasisSolver by delegate {
                    override fun ftran(x: IndexedVector, expectedDensity: Double) {
                        delegate.ftran(x, expectedDensity)
                        throw BasisArithmeticException("injected scaled failure")
                    }
                }
            } else {
                delegate
            }
        }).use { solver ->
            val duals = doubleArrayOf(0.5)
            assertTrue(solver.scalingMetrics.applied)
            for (column in 0 until model.n) assertTrue(solver.columnDot(duals, column).isFinite())
            val result = assertNotNull(solver.solve())
            assertEquals(2, owners.size)
            assertNotSame(owners[0], owners[1])
            assertFalse(solver.scalingMetrics.applied)
            assertEquals(1, solver.scalingMetrics.fallbacks)
            owners[0].values.fill(Double.NaN)

            assertEquals(-0.5, solver.columnDot(duals, 0))
            assertEquals(-512.0, solver.columnDot(duals, 1))
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, certifyLpResult(model, solver, result).verdict)
        }
    }

    @Test
    fun `objective adoption should preserve column pricing across a certified basis change`() {
        val model = LpBuilder().apply {
            addVar(0L, 10L, cost = 1L)
            addVar(0L, 10L, cost = 1L)
            addRow(intArrayOf(0, 1), longArrayOf(1L, 2L), Relation.GE, 4L)
        }.build(Sense.MINIMIZE)
        val trail = LpBoundTrail(assertNotNull(model.authoritativeModel()))
        RevisedSimplex(assertNotNull(trail.state.toWorkingModel())).use { solver ->
            val first = assertNotNull(solver.solve())
            val before = solver.columnDot(doubleArrayOf(0.5), 1)
            assertEquals(BigFraction.ofLong(2L), certifyLpResult(model, solver, first).lowerBound)
            assertTrue(
                trail.replaceObjective(ExactLpObjective(listOf(
                    ExactLpNumber.of(1L), ExactLpNumber.of(3L), ExactLpNumber.of(0L),
                ))),
            )

            assertTrue(solver.adopt(trail.state, Cancellation.Never))
            val second = assertNotNull(solver.resolveBounds())
            val certified = certifyLpResult(assertNotNull(trail.state.toWorkingModel()), solver, second)

            assertFalse(first.basis.basicVars.contentEquals(second.basis.basicVars))
            assertEquals(before, solver.columnDot(doubleArrayOf(0.5), 1))
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
            assertEquals(BigFraction.ofLong(4L), certified.lowerBound)
        }
    }
}
