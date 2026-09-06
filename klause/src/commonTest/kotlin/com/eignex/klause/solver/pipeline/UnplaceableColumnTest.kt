package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

        assertIs<SourceProblemRoute.Refuted>(model.pipelineRoute())
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

        val route = model.pipelineRoute()

        assertIs<SourceProblemRoute.OpenTheory>(route, "the bounded column leaves nothing unplaceable")
    }
}
