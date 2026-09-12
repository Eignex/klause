package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpPropagatorEpochTest {
    @Test
    fun `conditional initial bounds cannot become empty premise proofs after reset`() {
        val builder = LpBuilder()
        builder.addVar(0, 0)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.GE, 1)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(Any(), source))
            val session = SearchSession(listOf(lp))
            session.initialize()
            val declared = assertNotNull(lp.solve())
            assertNotNull(lp.explainConflict(declared.conflictSupport, session))
            assertTrue(lp.replaceEpoch(Any(), source, null, Cancellation.Never, { true }) {})
            val first = assertNotNull(lp.solve())
            assertNotNull(first.conflictSupport)
            assertNull(lp.explainConflict(first.conflictSupport, session))
            assertTrue(lp.resetRoot())
            val second = assertNotNull(lp.solve())
            assertNotNull(second.conflictSupport)
            assertNull(lp.explainConflict(second.conflictSupport, session))
            lp.reset()
            assertTrue(lp.install(Any(), source))
            val freshSession = SearchSession(listOf(lp))
            freshSession.initialize()
            val reinstalled = assertNotNull(lp.solve())
            assertNotNull(reinstalled.conflictSupport)
            assertNull(lp.explainConflict(reinstalled.conflictSupport, freshSession))
        }
    }

    @Test
    fun `failed preparation and publication validation preserve the original owner`() {
        var constructions = 0
        var closes = 0
        var failPreparation = false
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
                constructions++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun prepareLogicals(token: Cancellation): Basis? =
                        if (failPreparation) null else delegate.prepareLogicals(token)
                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            }
        }
        val builder = LpBuilder()
        builder.addVar(0, 4, cost = 1)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.GE, 1)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        LpPropagator(object : LpSearchPolicy {}, solveContext = LpSolveContext(factory)).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertNotNull(lp.solve())
            val old = lp.state
            failPreparation = true
            assertFalse(lp.replaceEpoch(Any(), source, null, Cancellation.Never, { true }) {})
            assertSame(old, lp.state)
            failPreparation = false
            assertFailsWith<IllegalStateException> {
                lp.replaceEpoch(Any(), source, null, Cancellation.Never, { error("publication rejected") }) {}
            }
            assertSame(old, lp.state)
            assertNotNull(lp.solve())
            assertEquals(constructions - 1, closes)
        }
        assertEquals(constructions, closes)
    }

    @Test
    fun `retired owner cleanup preserves a publication failure`() {
        var constructions = 0
        val cleanupFailure = IllegalStateException("retired owner cleanup failed")
        val publicationFailure = IllegalStateException("publication failed")
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
                val retired = constructions++ == 0
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun close() {
                        delegate.close()
                        if (retired) throw cleanupFailure
                    }
                }
            }
        }
        val builder = LpBuilder()
        builder.addVar(0, 4, cost = 1)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.GE, 1)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        LpPropagator(object : LpSearchPolicy {}, solveContext = LpSolveContext(factory)).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertNotNull(lp.solve())
            val thrown = assertFailsWith<IllegalStateException> {
                lp.replaceEpoch(Any(), source, null, Cancellation.Never, { true }) { throw publicationFailure }
            }
            assertSame(publicationFailure, thrown)
            assertEquals(listOf(cleanupFailure), thrown.suppressedExceptions)
            assertNotNull(lp.solve())
        }
    }

    @Test
    fun `cancellation immediately before publication closes the candidate`() {
        val builder = LpBuilder()
        builder.addVar(0, 4, cost = 1)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.GE, 1)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertNotNull(lp.solve())
            val old = lp.state
            var cancelled = false
            var published = false
            assertFalse(
                lp.replaceEpoch(Any(), source, null, Cancellation { cancelled }, {
                    cancelled = true
                    true
                }) { published = true },
            )
            assertFalse(published)
            assertSame(old, lp.state)
            assertNotNull(lp.solve())
        }
    }

    @Test
    fun `published owners outlive the epoch preparation deadline`() {
        val builder = LpBuilder()
        builder.addVar(0, 4, cost = 1)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.GE, 1)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            var expired = false
            assertTrue(lp.replaceEpoch(Any(), source, null, Cancellation { expired }, { true }) {})
            expired = true
            assertNotNull(lp.solve())
        }
    }

    @Test
    fun `singular source status hints use the existing basis recovery`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 4, cost = 1) }
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 1), Relation.GE, 1)
        builder.addRow(intArrayOf(0, 1), longArrayOf(2, 2), Relation.LE, 6)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).trailModel())
        val warm = Basis(
            intArrayOf(0, 1),
            arrayOf(VarStatus.BASIC, VarStatus.BASIC, VarStatus.AT_LOWER, VarStatus.AT_LOWER),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.replaceEpoch(Any(), source, warm, Cancellation.Never, { true }) {})
            val result = assertNotNull(lp.solve())
            assertNotNull(result.exactPrimal)
            assertTrue(lp.lastEpochMetrics.singularRefactorizations > 0)
        }
    }
}
