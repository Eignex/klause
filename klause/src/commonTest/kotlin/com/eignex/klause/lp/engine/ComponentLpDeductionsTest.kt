package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentLpDeductionsTest {
    @Test
    fun `component checking honors the remaining shared work allowance`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))
        owner.use {
            val result = assertNotNull(it.solve())
            assertNotNull(it.nodeDeductions(result.duals))
            val meter = MixedCertificateMeter(Cancellation.Never)
            meter.reserve(8L * 249_998L)

            assertNull(it.nodeDeductions(result.duals, sharedMeter = meter))
        }
    }

    @Test
    fun `assembled deductions preserve every component Lagrangian and fixing alternative`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 5L, cost = 3L)
            val y = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(x, y), longArrayOf(2L, 1L), Relation.GE, 3L)
        }
        builder.addVar(1L, 5L, cost = 2L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(model))
        owner.use {
            val result = assertNotNull(it.solve())
            val wholeAlternatives = ArrayList<LpDeductionCertificate>()
            val whole = assertNotNull(nodeDualCertificate(model, result.duals,
                onAdditionalCertificate = { wholeAlternatives.add(it) }))

            val deductions = assertNotNull(it.nodeDeductions(result.duals, withAlternatives = true))

            val candidates = listOf(deductions.primary) + deductions.alternatives
            assertTrue(deductions.primary.exactObjectiveBound() >= whole.exactObjectiveBound())
            for (candidate in candidates) {
                assertTrue(candidate.belongsTo(model))
                assertEquals(exactLagrangian(model, candidate.exactMultipliers()), candidate.exactObjectiveBound())
                for (column in 0 until model.numVars) {
                    var reduced = model.exactCost(column)
                    model.forEachRationalColumn(column) { row, coefficient ->
                        reduced -= candidate.exactMultiplier(row) * coefficient
                    }
                    assertEquals(reduced, candidate.exactReducedCost(column))
                }
            }
            for (candidate in listOf(whole) + wholeAlternatives) for (column in 0 until model.n) {
                val steps = candidate.fixSteps(column, 10L, 0L) ?: continue
                assertTrue(candidates.any { assembled ->
                    (assembled.fixingColumns()?.contains(column) != false) &&
                        assembled.reducedCostSign(column) == candidate.reducedCostSign(column) &&
                        assembled.fixSteps(column, 10L, 0L)?.let { it <= steps } == true
                })
            }
        }
    }

    @Test
    fun `a bound edit rechecks only its owning deduction and binds the current parent`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val previous = assertNotNull(trail.state.ownerWorkingModel())
        var checks = 0
        val observer = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) = Unit
            override fun observeExactInput(accepted: Boolean) = Unit
            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            override fun observePhase(metrics: LpPhaseMetrics) {
                if (metrics.phase == LpSolvePhase.NODE_DEDUCTION && metrics.outcome == "INTEGER_COMPLETE") checks++
            }
        }
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(previous))
        owner.use {
            val first = assertNotNull(it.solve())
            assertNotNull(it.nodeDeductions(first.duals, observer))
            assertEquals(2, checks)
            checks = 0
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
            assertTrue(it.adopt(trail.state))
            val result = assertNotNull(it.resolveBounds())

            val deductions = assertNotNull(it.nodeDeductions(result.duals, observer))

            assertEquals(1, checks)
            assertEquals(BigFraction.ofLong(4L), deductions.primary.exactObjectiveBound())
            assertTrue(deductions.primary.belongsTo(assertNotNull(trail.state.ownerWorkingModel())))
            assertFalse(deductions.primary.belongsTo(previous))
        }
    }

    @Test
    fun `cancelled deduction reuse withholds authority without discarding compatible checks`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))
        owner.use {
            val result = assertNotNull(it.solve())
            val expected = assertNotNull(it.nodeDeductions(result.duals)).primary.exactObjectiveBound()

            assertNull(it.nodeDeductions(result.duals, cancellation = Cancellation { true }))

            assertEquals(expected, assertNotNull(it.nodeDeductions(result.duals)).primary.exactObjectiveBound())
        }
    }

    @Test
    fun `continuous components retain the whole model deduction route`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addRealVar(0.0, 5.0, cost = 1.0)
            builder.addRealRow(intArrayOf(column), doubleArrayOf(1.0), Relation.GE, 1.0)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(model))
        owner.use {
            val result = assertNotNull(it.solve())

            assertNull(it.nodeDeductions(result.duals))
            assertNotNull(nodeDualCertificate(model, result.duals))
        }
    }
}
