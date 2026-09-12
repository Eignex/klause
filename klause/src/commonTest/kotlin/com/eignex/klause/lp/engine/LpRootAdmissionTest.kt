package com.eignex.klause.lp.engine

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEffortProfile
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPropagator
import com.eignex.klause.lp.bounding.LpSearchPolicy
import com.eignex.klause.lp.bounding.solveNode
import com.eignex.klause.lp.bounding.trailModel
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpRootAdmissionTest {
    @Test
    fun `root admission preserves source authority and reserves preparation work`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = LpExactState(
            ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            List(2) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero))) },
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(one, zero)),
        )
        )
        val model = assertNotNull(source.toWorkingModel())
        val pricing = LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT, 19L)
        var created = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                created++
                assertSame(source, model.exactState)
                assertEquals(17, refactorUpdateLimit)
                assertEquals(7, iterationLimit)
                assertEquals(500L, workLimit)
                assertTrue(trackDegeneracy)
                assertEquals(LpPricingOptions(LpZeroObjectivePricing.LARGEST_PIVOT, 19L), pricing)
                return object : PersistentLpSolver {
                    override val infeasibleRay: DoubleArray? = null
                    override val solvedExactState = source
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean {
                        assertSame(source, state)
                        return true
                    }
                    override fun prepareLogicals(token: Cancellation) = Basis(
                        intArrayOf(1),
                        arrayOf(VarStatus.AT_LOWER, VarStatus.BASIC),
                    )
                    override fun solve(warm: Basis?) = FloatLpResult(
                        prepareLogicals(),
                        1.0,
                        doubleArrayOf(-1.0),
                        doubleArrayOf(1.0),
                        exactState = source,
                    )
                    override fun solvePrimal(warm: Basis?) = solve(warm)
                    override fun rebind(next: LpModel, token: Cancellation) = false
                    override fun resolveBounds() = solve(null)
                    override fun resolveGated(enforced: BooleanArray) = null
                }
            }
        }
        val receipt = LpRootAdmission(model, 1001L, 7)
        LpPropagator(
            object : LpSearchPolicy {},
            {
                LpEffortProfile(
                    work = 5000L,
                    iterations = 30,
                    refactorUpdates = 17,
                    trackDegeneracy = true,
                    pricing = pricing,
                )
            },
            LpSolveContext(factory),
        ).use { owner ->
            assertTrue(owner.install(model, source.model, receipt))
            assertSame(source, owner.state)
            val result = assertNotNull(owner.solveFloat())
            assertSame(source, assertNotNull(result.second).exactState)
            assertFalse(owner.install(model, source.model, receipt))
            assertSame(source, owner.state)
        }
        assertEquals(1, created)
    }

    @Test
    fun `a foreign equal valued authority consumes admission and leaves current state intact`() {
        val builder = LpBuilder()
        builder.addVar(0L, 3L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val model = assertNotNull(source.toWorkingModel())
        val foreign = LpExactState(source.model)
        val foreignModel = assertNotNull(foreign.toWorkingModel())
        val receipt = LpRootAdmission(model, 10L, null)
        LpPropagator(object : LpSearchPolicy {}).use { owner ->
            assertTrue(owner.install(foreignModel, foreign.model))
            val prior = owner.state

            assertFalse(owner.install(foreignModel, foreign.model, receipt))
            assertFalse(owner.install(model, source.model, receipt))

            assertSame(prior, owner.state)
        }
    }

    @Test
    fun `zero and exhausted finite limits cannot become unlimited admission`() {
        val builder = LpBuilder()
        builder.addVar(0L, 3L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val model = assertNotNull(source.toWorkingModel())

        assertFailsWith<IllegalArgumentException> { LpRootAdmission(model, 0L, null) }
        assertFailsWith<IllegalArgumentException> { LpRootAdmission(model, 1L, null) }
        assertFailsWith<IllegalArgumentException> { LpRootAdmission(model, null, 0) }
        assertSame(source, LpRootAdmission(model, null, null).claim(model, source.model))
    }

    @Test
    fun `a missing exact model consumes the receipt`() {
        val builder = LpBuilder()
        builder.addVar(0L, 3L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val model = assertNotNull(source.toWorkingModel())
        val receipt = LpRootAdmission(model, 10L, null)

        assertNull(receipt.claim(model, null))
        assertNull(receipt.claim(model, source.model))
    }

    @Test
    fun `per call cancellation preserves installed state and consumes the root receipt`() {
        val builder = LpBuilder()
        builder.addVar(0L, 3L)
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val model = assertNotNull(source.toWorkingModel())
        val receipt = LpRootAdmission(model, 10L, null)
        LpEngine(
            Problem(0, 0, emptyArray(), emptyArray()),
            LinearObjective(),
            LpParams(),
            SolveStatsSink(backend = "admission"),
        ).use { engine ->
            assertTrue(engine.propagator.install(Any(), source.model))
            val previous = engine.propagator.state
            val previousKey = engine.cpAdapter.currentModel

            assertNull(engine.solveNode(model, null, Cancellation { true }, receipt))

            assertSame(previous, engine.propagator.state)
            assertSame(previousKey, engine.cpAdapter.currentModel)
            assertFalse(engine.nodeUsesTrail)
            assertNull(receipt.claim(model, source.model))
        }
    }

    @Test
    fun `failed logical preparation remains charged and cannot be readmitted`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L)
        repeat(4) { builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L) }
        val source = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).trailModel()))
        val model = assertNotNull(source.toWorkingModel())
        val receipt = LpRootAdmission(model, 2L, null)
        LpPropagator(object : LpSearchPolicy {}).use { owner ->
            assertTrue(owner.install(model, source.model, receipt))

            assertNull(owner.solveFloat())

            assertTrue(owner.lastMetrics.workOps > 0L)
            assertFalse(owner.install(model, source.model, receipt))
        }
    }
}
