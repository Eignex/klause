package com.eignex.klause.lp.engine

import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpDualizationBasisTest {
    @Test
    fun `free structural and equality logical recover a source basis`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))), listOf(ExactLpNumber.of(2L)),
            listOf(ExactLpColumn(ExactLpBounds(), integral = false), ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false)),
            listOf(ExactLpRow()), ExactLpObjective(listOf(one, zero)),
        ))
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true, minRowColumnRatio = 1))

        val basis = assertNotNull(attempt.solve(source))
        val solver = newPersistentLpSolver(assertNotNull(source.toWorkingModel()))
        val result = solver.use { assertNotNull(it.solve(basis)) }

        assertEquals(listOf(0), basis.basicVars.toList())
        assertEquals(VarStatus.FIXED, basis.status[1])
        assertEquals(listOf(2.0), result.primal.toList())
        assertTrue(result.warmStarted)
    }

    @Test
    fun `duplicate dual headings cannot publish a source basis`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        val y = builder.addVar(0L, 8L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 1L), Relation.GE, 3L)
        builder.addRow(intArrayOf(x, y), longArrayOf(1L, 2L), Relation.GE, 4L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val transform = LpDualization.create(source, LpDualizationOptions(), LpDualizationMeter(10000L, 4096, Cancellation.Never))
        val bad = Basis(intArrayOf(0, 0), Array(transform.model.numVars) { VarStatus.BASIC })

        val result = transform.sourceBasis(bad, ExactLpWitness(listOf(BigFraction.ZERO, BigFraction.ofLong(3L)), BigFraction.ZERO), LpDualizationMeter(10000L, 4096, Cancellation.Never))

        assertNull(result)
    }

    @Test
    fun `admitted source basis remains usable after a cut append`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        repeat(10) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true))
        val basis = assertNotNull(attempt.solve(source))

        LpScopedSolver(source).use { owner ->
            assertEquals(BigFraction.ofLong(6L), owner.solve(basis)?.lowerBound)
            assertTrue(owner.append(LpScopedRow(
                id = 100L,
                coefficients = listOf(0 to ExactLpNumber.of(1L)),
                rhs = ExactLpNumber.of(4L),
                logical = ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(0L)))),
            ), scoped = false))
            val after = assertNotNull(owner.solve())
            assertEquals(BigFraction.ofLong(8L), after.lowerBound)
            assertEquals(listOf(BigFraction.ofLong(4L)), after.exactPrimal)
        }
    }

    @Test
    fun `nested working dualization preserves the parent source and objective`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 8L, cost = 2L)
        repeat(10) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        LpScopedSolver(source).use { owner ->
            assertEquals(BigFraction.ofLong(6L), owner.solve()?.lowerBound)
            val working = LpWorkingModel.overrides(source, ExactLpObjective(List(source.model.numVars) { ExactLpNumber.of(if (it == 0) -1L else 0L) }))
            owner.withWorkingModel(working) { scope ->
                val attempt = LpRootDualizationAttempt(LpDualizationOptions(enabled = true))
                val basis = assertNotNull(attempt.solve(scope.state))
                assertEquals(BigFraction.ofLong(-8L), scope.solve(basis)?.lowerBound)
                assertNull(certifyDualizedSource(assertNotNull(source.toWorkingModel()), attempt))
            }
            assertSame(source, owner.state)
            assertEquals(BigFraction.ofLong(6L), owner.solve()?.lowerBound)
        }
    }
    @Test
    fun `a singular mapped basis is repaired by the original source engine`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(ExactLpModel(
            listOf(emptyList()), listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one))), ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero)),
        ))
        val transform = LpDualization.create(source, LpDualizationOptions(), LpDualizationMeter(10000L, 4096, Cancellation.Never))
        val statuses = Array(transform.model.numVars) { VarStatus.AT_LOWER }
        statuses[2] = VarStatus.BASIC
        val candidate = assertNotNull(transform.sourceBasis(Basis(intArrayOf(2), statuses), ExactLpWitness(listOf(BigFraction.ZERO), BigFraction.ZERO), LpDualizationMeter(10000L, 4096, Cancellation.Never)))

        val result = newPersistentLpSolver(assertNotNull(source.toWorkingModel())).use { assertNotNull(it.solve(candidate)) }

        assertEquals(listOf(0), candidate.basicVars.toList())
        assertEquals(listOf(0.0), result.primal.toList())
        assertEquals(listOf(1), result.basis.basicVars.toList())
    }

}
