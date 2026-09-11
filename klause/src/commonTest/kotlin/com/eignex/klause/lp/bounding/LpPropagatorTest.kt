package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPropagatorTest {
    @Test
    fun `scoped row pop restores exact feasibility while retaining the original output`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(one)))),
            emptyList(),
            ExactLpObjective(listOf(one)),
        )
        LpPropagator(object : LpSearchPolicy {}).use { lp ->
            assertTrue(lp.install(Any(), source))
            val initial = assertNotNull(lp.solve())
            assertEquals(BigFraction.ZERO, initial.witness?.objective)
            assertTrue(lp.atLevel(1))
            assertTrue(
                lp.append(
                    LpScopedRow(
                0,
                listOf(0 to one),
                one,
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
                scoped = true
                )
            )
            assertEquals(BigFraction.ONE, assertNotNull(lp.solve()).witness?.objective)

            lp.retract(0)

            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).witness?.objective)
            assertEquals(BigFraction.ZERO, initial.witness?.objective)
            assertEquals(0, assertNotNull(lp.state).rows.activeCount)
            assertEquals(ComponentCheck.Indeterminate, lp.check(SearchSession(emptyList())))
        }
    }

    @Test
    fun `cancelled rollback restores bounds and a declined adoption invalidates authority`() {
        var cancelled = false
        var reject = false
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
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !reject && delegate.adopt(state, token)
                }
            }
        }
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)), ExactLpSide(ExactLpNumber.of(5L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )
        LpPropagator(
            object : LpSearchPolicy {},
            solveContext = LpSolveContext(factory),
            cancellation = Cancellation { cancelled },
        ).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertNotNull(lp.solve())
            assertTrue(lp.atLevel(1))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L))))
            cancelled = true
            lp.retract(0)
            assertEquals(BigFraction.ZERO, lp.state?.model?.column(0)?.bounds?.lower?.number?.value)
            cancelled = false
            assertTrue(lp.atLevel(1))
            assertTrue(lp.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L))))
            reject = true
            lp.retract(0)
            assertNull(lp.state)
            assertNull(lp.solve())
            reject = false
            assertTrue(lp.install(Any(), source))
            assertEquals(BigFraction.ZERO, assertNotNull(lp.solve()).witness?.objective)
        }
    }

    @Test
    fun `row budget decline does not become a feasible neutral check`() {
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(0L))),
        )
        LpPropagator(object : LpSearchPolicy {}, effort = { LpEffortProfile(maxRows = 0) }).use { lp ->
            assertTrue(lp.install(Any(), source))
            assertFalse(
                lp.append(
                    LpScopedRow(
                0,
                listOf(0 to ExactLpNumber.of(1L)),
                ExactLpNumber.of(0L),
                ExactLpColumn(ExactLpBounds()),
            ),
                scoped = true
                )
            )
            assertEquals(ComponentCheck.Indeterminate, lp.check(SearchSession(emptyList())))
        }
    }
}
