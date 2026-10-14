package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class UnplaceableColumnTest {

    private fun withAllDifferentOverOpenColumn(): Problem {
        val openHi = Bits(2).also { it.set(1) }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 0), null, openHi),
            factors = arrayOf(AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6)),
        )
    }

    @Test
    fun `a refused model names the column and the constraint that demanded it`() {
        val plan = withAllDifferentOverOpenColumn().componentPlan()

        assertEquals(ProblemPipeline.UNSUPPORTED_OPEN, plan.theoryPipeline)
        val unplaceable = assertNotNull(plan.unplaceable, "a refusal without a cause is not actionable")
        assertEquals(1, unplaceable.column)
        assertEquals("AllDifferent", unplaceable.factorKind)
    }

    @Test
    fun `a routable model names nothing`() {
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 5), null, null),
            factors = arrayOf(AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6)),
        )

        assertNull(model.componentPlan().unplaceable)
    }

    @Test
    fun `a refused source route carries its unplaceable column`() {
        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(withAllDifferentOverOpenColumn().pipelineRoute())

        assertEquals(1, assertNotNull(route.unplaceable).column)
    }

    @Test
    fun `interrupted preparation after bounding retains the source and objective`() {
        val model = withAllDifferentOverOpenColumn()
        for ((objective, maximize) in listOf(
            null to false,
            LinearObjective(intCoefficients = longArrayOf(1, 0), constant = 7) to true,
        )) {
            var stopped = false

            val route = assertIs<SourceProblemRoute.UnsupportedOpen>(model.pipelineRoute(
                objective = objective,
                maximize = maximize,
                boundCancellation = Cancellation { stopped },
                onLpStats = { stopped = true },
            ))

            assertTrue(stopped)
            assertNull(route.unplaceable)
            assertEquals(PreparedExecutionKind.DECLINED, route.preparedPlan.kind)
            assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
            assertNull(route.preparedPlan.componentPlan)
            assertSame(model, route.preparedPlan.originalSource)
            assertSame(route.problem, route.preparedPlan.model)
            assertSame(objective, route.preparedPlan.objective)
            assertEquals(maximize, route.preparedPlan.maximize)
            assertNotNull(route.preparedPlan.factorProjections).requireSource(route.problem)
        }
    }

    @Test
    fun `a stop after unchanged bounds cannot reuse runnable preparation`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(
            Linear(intVars = intArrayOf(), intCoeffs = doubleArrayOf(), realVars = intArrayOf(0),
                realCoeffs = doubleArrayOf(1.0), op = LinearOp.EQ, bound = 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        for (once in listOf(false, true)) for ((objective, maximize) in listOf(
            null to false,
            LinearObjective(realCoefficients = doubleArrayOf(1.0), constant = 7) to true,
        )) {
            var stopped = false
            var stopObserved = false

            val route = assertIs<SourceProblemRoute.UnsupportedOpen>(model.pipelineRoute(
                objective = objective,
                maximize = maximize,
                routePureRealToTheory = true,
                boundCancellation = Cancellation {
                    val answer = stopped
                    if (answer) stopObserved = true
                    if (once) stopped = false
                    answer
                },
                onLpStats = { stopped = true },
            ))

            assertTrue(stopObserved)
            assertEquals(PreparedExecutionKind.DECLINED, route.preparedPlan.kind)
            assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
            assertNull(route.preparedPlan.componentPlan)
            assertSame(model, route.problem)
            assertSame(model, route.preparedPlan.originalSource)
            assertSame(model, route.preparedPlan.model)
            assertSame(objective, route.preparedPlan.objective)
            assertEquals(maximize, route.preparedPlan.maximize)
            assertNotNull(route.preparedPlan.factorProjections).requireSource(model)
        }
    }

    @Test
    fun `a refuted model is decided rather than declined for the column it could not place`() {
        // AllDifferent leaves column 1 unplaceable while it is open, and the two rows cross over its
        // range. Deciding the model is the stronger answer, so the refusal must not win the race.
        val openHi = Bits(2).also { it.set(1) }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 0), null, openHi),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6),
                Linear(longArrayOf(1), intArrayOf(1), LinearOp.LE, 3),
                Linear(longArrayOf(-1), intArrayOf(1), LinearOp.LE, -5),
            ),
        )

        for (stopAfterBounds in listOf(false, true)) {
            var stopped = false

            val route = model.pipelineRoute(
                boundCancellation = Cancellation { stopped },
                onLpStats = { stopped = stopAfterBounds },
            )

            assertIs<SourceProblemRoute.Refuted>(route)
            assertEquals(stopAfterBounds, stopped)
        }
    }

    @Test
    fun `a column a row bounds is placed even though another column stays open`() {
        // Column 1 is the one AllDifferent demands be finite, and the row states its upper side. Column 2
        // is open with nothing to bound it, so the closure reaches only part of the model.
        val openHi = Bits(3).also {
            it.set(1)
            it.set(2)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0, 0),
                longArrayOf(5, 0, 0),
                null,
                openHi,
            ),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6),
                Linear(longArrayOf(1), intArrayOf(1), LinearOp.LE, 5),
            ),
        )

        val objective = LinearObjective(intCoefficients = longArrayOf(0, 1, 0), constant = 7)

        val route = model.pipelineRoute(objective = objective, maximize = true)

        val planned = assertIs<SourceProblemRoute.OpenTheory>(route, "the bounded column leaves nothing unplaceable")
        kotlin.test.assertSame(model, planned.preparedPlan.originalSource)
        val bounded = assertNotNull(planned.preparedPlan.model)
        val ownership = assertNotNull(planned.preparedPlan.componentPlan)
        assertTrue(bounded !== model)
        assertEquals(5L, bounded.intBounds.upper(1))
        assertEquals(false, model.intBounds.hasUpper(1))
        assertEquals(IntVariableOwner.CP, ownership.intOwner(1))
        assertEquals(IntVariableOwner.THEORY, ownership.intOwner(2))
        ownership.requireSource(bounded)
        assertNotNull(planned.preparedPlan.factorProjections).requireSource(bounded)
        assertSame(objective, planned.preparedPlan.objective)
        assertTrue(planned.preparedPlan.maximize)
    }

    @Test
    fun `interrupted preparation retains a completed partial bound closure`() {
        val openHi = Bits(3).also {
            it.set(1)
            it.set(2)
        }
        val model = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(5, 0, 0), null, openHi),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6),
                Linear(longArrayOf(1), intArrayOf(1), LinearOp.LE, 5),
            ),
        )
        var stopped = false

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(model.pipelineRoute(
            boundCancellation = Cancellation { stopped },
            onLpStats = { stopped = true },
        ))

        assertTrue(stopped)
        assertEquals(PreparedExecutionKind.DECLINED, route.preparedPlan.kind)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
        assertNull(route.preparedPlan.componentPlan)
        assertSame(model, route.preparedPlan.originalSource)
        assertSame(route.problem, route.preparedPlan.model)
        assertEquals(5L, route.problem.intBounds.upper(1))
        assertEquals(false, model.intBounds.hasUpper(1))
        assertEquals(false, route.problem.intBounds.hasUpper(2))
    }
}
