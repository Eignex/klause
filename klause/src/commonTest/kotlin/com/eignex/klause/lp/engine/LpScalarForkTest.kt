package com.eignex.klause.lp.engine

import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpScalarForkTest {
    @Test
    fun `source retirement before copy installation should decline without fresh factorization`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1L, 1L), Relation.GE, 1L) }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            val warm = assertNotNull(parent.retainedBasis())
            val fork = assertNotNull(ProductionLpEngineFactory.newScalarFork(parent, model, model, warm,
                ExecutionControl.Never, LpComponentStaging(100_000L, ExecutionControl.Never), LpPricingOptions()))
            parent.close()
            fork.create().use { child ->

                val prepared = child.prepareBasis(warm, ExecutionControl.Never)

                assertNull(prepared)
                assertEquals(0, child.lastRefactorizations)
            }
        }
    }

    @Test
    fun `a scalar fork keeps its numerical state after source retirement`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1L, 1L), Relation.GE, 1L) }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            val before = parent.lastMetrics
            val trail = LpBoundTrail(state)
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 1L))
            val next = assertNotNull(trail.state.ownerWorkingModel())
            val warm = assertNotNull(assertNotNull(parent.retainedBasis()).extended(state.model, trail.state.model))
            val staging = LpComponentStaging(100_000L, ExecutionControl.Never)
            val fork = assertNotNull(ProductionLpEngineFactory.newScalarFork(parent, model, next, warm,
                ExecutionControl.Never, staging, LpPricingOptions()))
            assertIs<RevisedSimplex>(fork.create()).use { child ->
                assertNotNull(child.prepareBasis(warm, ExecutionControl.Never))
                assertTrue(child.hasImportedFactors)
                assertEquals(0, child.lastRefactorizations)
                assertEquals(before, parent.lastMetrics)
                parent.close()

                val result = certifyLpResult(next, child, child.solve())

                assertEquals(BigFraction.ofLong(3L), result.lowerBound)
            }
        }
    }

    @Test
    fun `retiring a scalar fork keeps the source factors usable`() {
        val builder = LpBuilder()
        repeat(3) { builder.addVar(0L, 5L, cost = 1L) }
        repeat(3) { builder.addRow(intArrayOf(it, (it + 1) % 3), longArrayOf(1L, 1L), Relation.GE, 1L) }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            val original = assertNotNull(parent.solve())
            val trail = LpBoundTrail(state)
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 1L))
            val next = assertNotNull(trail.state.ownerWorkingModel())
            val warm = assertNotNull(assertNotNull(parent.retainedBasis()).extended(state.model, trail.state.model))
            val fork = assertNotNull(ProductionLpEngineFactory.newScalarFork(parent, model, next, warm,
                ExecutionControl.Never, LpComponentStaging(100_000L, ExecutionControl.Never), LpPricingOptions()))
            fork.create().use { child ->
                assertNotNull(child.prepareBasis(warm, ExecutionControl.Never))
                assertNotNull(child.solve())
            }

            val repeated = assertNotNull(parent.resolveBounds())

            assertEquals(original.objective, repeated.objective, 1e-8)
            assertEquals(0, repeated.refactorizations)
        }
    }

    @Test
    fun `stale source identity declines before construction`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(variable), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            val staging = LpComponentStaging(100_000L, ExecutionControl.Never)
            val stale = assertNotNull(LpExactState(state.model).ownerWorkingModel())

            val fork = ProductionLpEngineFactory.newScalarFork(parent, stale, model,
                assertNotNull(parent.retainedBasis()), ExecutionControl.Never, staging, LpPricingOptions())

            assertNull(fork)
            assertEquals(0L, staging.work)
        }
    }

    @Test
    fun `a suspended source declines without disturbing its factors`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(variable), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            val warm = assertNotNull(parent.retainedBasis())
            val staging = LpComponentStaging(100_000L, ExecutionControl.Never)
            parent.openSolve().use {
                assertNull(ProductionLpEngineFactory.newScalarFork(parent, model, model, warm,
                    ExecutionControl.Never, staging, LpPricingOptions()))
            }

            assertEquals(0L, staging.work)
            assertNotNull(parent.resolveBounds())
        }
    }

    @Test
    fun `factor resource declines retain the compatibility work`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(variable), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            val staging = LpComponentStaging(100_000L, ExecutionControl.Never)

            val fork = ProductionLpEngineFactory.newScalarFork(parent, model, model,
                assertNotNull(parent.retainedBasis()), ExecutionControl.Never, staging,
                LpPricingOptions(basisEntryLimit = 0L))

            assertNull(fork)
            assertTrue(staging.work > 0L)
            assertEquals(0L, staging.reservedConstructionWork)
            assertNotNull(parent.resolveBounds())
        }
    }

    @Test
    fun `matrix and coordinate changes decline without replacing the source`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(variable), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        val changed = ExactLpModel(listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(3L)))),
            listOf(state.model.rhs(0)), List(state.model.numVars) { state.model.column(it) },
            listOf(state.model.row(0)), state.model.objective)
        val shifted = state.model.recentered(listOf(ExactLpNumber.of(1L)))
        RevisedSimplex(model).use { parent ->
            val original = assertNotNull(parent.solve())
            val warm = assertNotNull(parent.retainedBasis())

            for (target in listOf(changed, shifted)) {
                val next = assertNotNull(LpExactState(target).ownerWorkingModel())
                val staging = LpComponentStaging(100_000L, ExecutionControl.Never)
                assertNull(ProductionLpEngineFactory.newScalarFork(parent, model, next, warm,
                    ExecutionControl.Never, staging, LpPricingOptions()))
                assertTrue(staging.work > 0L)
                assertEquals(0L, staging.reservedConstructionWork)
            }

            assertEquals(original.objective, assertNotNull(parent.resolveBounds()).objective, 1e-8)
        }
    }

    @Test
    fun `an exhausted workspace grant releases unstarted construction`() {
        val builder = LpBuilder()
        val variable = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(variable), longArrayOf(2L), Relation.GE, 1L)
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            val warm = assertNotNull(parent.retainedBasis())
            val full = LpComponentStaging(0L, ExecutionControl.Never)
            assertNotNull(ProductionLpEngineFactory.newScalarFork(parent, model, model, warm,
                ExecutionControl.Never, full, LpPricingOptions()))
            val exhausted = LpComponentStaging(full.work, ExecutionControl.Never)

            assertFailsWith<LpProjectionStop> {
                ProductionLpEngineFactory.newScalarFork(parent, model, model, warm,
                    ExecutionControl.Never, exhausted, LpPricingOptions())
            }

            assertTrue(exhausted.reservedConstructionWork >= simplexWorkspaceWork(model.m, model.numVars))
            val paid = exhausted.work - exhausted.reservedConstructionWork + exhausted.metrics.preparationOps
            assertTrue(paid > 0L && paid < full.work)
            assertNotNull(parent.resolveBounds())
        }
    }

    @Test
    fun `unsafe scalar refresh should retain its work without allocating a workspace`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1e-300))),
                listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1e300)))),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(1L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            listOf(ExactLpRow()), ExactLpObjective(listOf(zero, zero, zero)),
        )
        val trail = LpBoundTrail(source)
        val model = assertNotNull(trail.state.ownerWorkingModel())
        RevisedSimplex(model).use { parent ->
            assertNotNull(parent.solve())
            assertTrue(parent.scalingMetrics.applied)
            val before = parent.lastMetrics
            val version = parent.scaleVersion
            val warm = assertNotNull(parent.retainedBasis())
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.ofIeee(1e300), zero, zero))))
            val next = assertNotNull(trail.state.ownerWorkingModel())
            val compatibility = LpComponentStaging(100_000L, ExecutionControl.Never)
            assertNull(ProductionLpEngineFactory.newScalarFork(parent, model, next, warm,
                ExecutionControl.Never, compatibility, LpPricingOptions(basisEntryLimit = 0L)))
            val staging = LpComponentStaging(100_000L, ExecutionControl.Never)

            val fork = ProductionLpEngineFactory.newScalarFork(parent, model, next, warm,
                ExecutionControl.Never, staging, LpPricingOptions())

            assertNull(fork)
            assertEquals(compatibility.work + LpScalingView.create(model).refreshWorkBound(next), staging.work)
            assertEquals(0L, staging.reservedConstructionWork)
            assertEquals(0L, staging.metrics.preparationOps)
            assertEquals(before, parent.lastMetrics)
            assertEquals(version, parent.scaleVersion)
        }
    }
}
