package com.eignex.klause.lp.engine

import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.simplex.basis.BasisSolveQuality
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.IndexedVector
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevisedSimplexPrimalTest {

    @Test
    fun `primal stops should report their invocation caps`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 3L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val model = builder.build(Sense.MINIMIZE)
        for ((work, pivots, expected) in listOf(
            Triple(1L, 100, LpFloatTermination.WORK),
            Triple(0L, 1, LpFloatTermination.PIVOTS),
        )) {
            RevisedSimplex(model, workLimit = work, iterationLimit = pivots).use { solver ->
                assertNull(solver.solvePrimal())

                assertEquals(expected, solver.lastTermination)
            }
        }
    }

    @Test
    fun `native lower upper free and fixed seats should certify their source optimum`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        val model = ExactLpModel(
            List(4) { listOf(ExactLpEntry(0, one)) },
            listOf(ExactLpNumber.of(10L)),
            listOf(
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(2L)))),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(-1L)))),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(ExactLpSide(three), ExactLpSide(three))),
                ExactLpColumn(ExactLpBounds()),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, ExactLpNumber.of(-1L), zero, ExactLpNumber.of(7L), zero)),
        )

        val certified = solveAndCertify(model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
        assertEquals(listOf(2L, -1L, 0L, 3L).map(BigFraction::ofLong), certified.exactPrimal)
        assertEquals(BigFraction.ofLong(24L), certified.lowerBound)
        val statuses = assertNotNull(certified.float).basis.status
        assertEquals(VarStatus.AT_LOWER, statuses[0])
        assertEquals(VarStatus.AT_UPPER, statuses[1])
        assertEquals(VarStatus.FREE, statuses[2])
        assertEquals(VarStatus.FIXED, statuses[3])
        val support = assertNotNull(assertNotNull(certified.bound).support)
        assertEquals(setOf(-1L, -4L, -7L), support.sides.map { it.witness }.toSet())
    }

    @Test
    fun `nonzero logical cost should repair a feasible but dual infeasible initial basis`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val box = ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(box), ExactLpColumn(box)),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, one)),
        )

        val certified = solveAndCertify(model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
        assertEquals(listOf(BigFraction.ONE), certified.exactPrimal)
        assertEquals(BigFraction.ZERO, certified.lowerBound)
    }

    @Test
    fun `distinct exact endpoints sharing a double should not export a fixed status`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val lower = ExactLpNumber.of(9007199254740992L)
        val upper = ExactLpNumber.of(9007199254740993L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(lower),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(lower), ExactLpSide(upper))),
                ExactLpColumn(ExactLpBounds()),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )

        val certified = solveAndCertify(model)

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, certified.verdict)
        assertEquals(listOf(lower.value), certified.exactPrimal)
        assertEquals(lower.value, certified.lowerBound)
        assertEquals(VarStatus.AT_LOWER, assertNotNull(certified.float).basis.status[0])
        assertFalse(model.column(0).bounds.fixed)
        assertEquals(lower.value.toDouble(), upper.value.toDouble())
    }

    @Test
    fun `the bound-flipping ratio test should flip entering variables to their upper bound`() {
        // The loose row never blocks, so both pivots are bound flips.
        val b = LpBuilder()
        b.addVar(0L, 1L, cost = -1L)
        b.addVar(0L, 1L, cost = -1L)
        b.addRow(intArrayOf(0, 1), longArrayOf(1L, 1L), Relation.LE, 5L)
        val model = b.build(Sense.MINIMIZE)
        val result = RevisedSimplex(model).solvePrimal()
        assertTrue(result != null)
        assertEquals(-2.0, result.objective, 1e-9)
    }

    @Test
    fun `generation changing quality restarts should share one recovery budget`() {
        val builder = LpBuilder()
        repeat(40) {
            val variable = builder.addVar(0L, 1L, cost = -1L)
            builder.addRow(intArrayOf(variable), longArrayOf(1L), Relation.LE, 0L)
        }
        RevisedSimplex(
            builder.build(Sense.MINIMIZE),
            basisSolverFactory = { matrix -> PersistentlyBadQualitySolver(KotlinBasisSolver(matrix)) },
        ).use { solver ->
            val result = solver.solvePrimal()

            assertNull(result)
            assertTrue(solver.lastRefactorPolicyMetrics.acceptedUpdates >= 5L)
            assertEquals(5L, solver.lastRefactorPolicyMetrics.residualTriggers)
            assertEquals(5, solver.lastMetrics.numericalRecoveryRefactorizations)
        }
    }
}

private class PersistentlyBadQualitySolver(private val delegate: BasisSolver) : BasisSolver by delegate {
    override fun solveQuality(rhs: DoubleArray, solution: IndexedVector, transpose: Boolean): BasisSolveQuality =
        BasisSolveQuality(1e-4, 1e-4)
}
