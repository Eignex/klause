package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.pipeline.ProblemPipeline
import com.eignex.klause.solver.pipeline.componentPlan
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UnplaceableColumnTest {

    private fun withAllDifferentOverOpenColumn(): ProblemSpec {
        val openHi = Bits(2).also { it.set(1) }
        return ProblemSpec(
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
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(5, 5), null, null),
            factors = arrayOf(AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 6)),
        )

        assertNull(model.componentPlan().unplaceable)
    }
}
