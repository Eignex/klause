package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.LpParams
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpAssemblyCancelled
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpPrimalHandoffTest {
    private val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) }, listOf(
        Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
    ))
    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
    private val selected = FactorProjectionProviders.Default.prepare(problem)

    @Test
    fun `a foreign engine should not consume a root definition`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(RootDomains(problem))
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("handoff-owner"),
            projections = selected, reusePumpPreparation = true).use { owner ->
            LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("handoff-foreign"),
                projections = selected, reusePumpPreparation = true).use { foreign ->
                assertNotNull(LpPrimalHandoff.open(owner, Cancellation.Never)).use { handoff ->
                    handoff.capture(owner, root, Cancellation.Never)
                    val before = foreign.totalSolveWork()

                    assertNull(handoff.prepareFor(foreign, Cancellation.Never))

                    assertEquals(before, foreign.totalSolveWork())
                    assertTrue(handoff.hasDefinitionFor(owner))
                    assertNotNull(handoff.prepareFor(owner, Cancellation.Never))
                }
            }
        }
    }

    @Test
    fun `a closed root scope should withhold its definition`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(RootDomains(problem))
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("closed-handoff"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            val handoff = assertNotNull(LpPrimalHandoff.open(engine, Cancellation.Never))
            handoff.capture(engine, root, Cancellation.Never)

            handoff.close()

            assertFalse(handoff.hasDefinitionFor(engine))
            assertNull(handoff.prepareFor(engine, Cancellation.Never))
            assertEquals(0L, handoff.setupWorkFor(engine))
        }
    }

    @Test
    fun `a root definition should be offered to one pump scope`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(RootDomains(problem))
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("single-handoff"),
            projections = selected, reusePumpPreparation = true).use { engine ->
            assertNotNull(LpPrimalHandoff.open(engine, Cancellation.Never)).use { handoff ->
                handoff.capture(engine, root, Cancellation.Never)
                assertNotNull(handoff.prepareFor(engine, Cancellation.Never))
                val before = engine.totalSolveWork()

                assertNull(handoff.prepareFor(engine, Cancellation.Never))

                assertEquals(before, engine.totalSolveWork())
                assertFalse(handoff.hasDefinitionFor(engine))
            }
        }
    }

    @Test
    fun `a cancelled capture should withhold its root definition`() {
        val root = CpToLpRelaxation(problem, objective, projections = selected, reuseSourceTemplates = false)
            .build(RootDomains(problem))
        var armed = false
        var stopped = false
        var charged = 0L
        LpEngine(problem, objective, LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink("cancelled-handoff"),
            projections = selected, reusePumpPreparation = true, containingWork = {
                if (armed) {
                    charged += it
                    stopped = true
                }
            }).use { engine ->
            assertNotNull(LpPrimalHandoff.open(engine, Cancellation.Never)).use { handoff ->
                armed = true

                assertFailsWith<LpAssemblyCancelled> {
                    handoff.capture(engine, root, Cancellation { stopped })
                }

                assertEquals(1L, charged)
                assertFalse(handoff.hasDefinitionFor(engine))
            }
        }
    }
}
