package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreparedSourceHintsTest {
    @Test
    fun `empty source hints should retain unhinted arithmetic maintenance and objective gradients`() {
        for (maximize in listOf(false, true)) {
            val domains = arrayOf(IntDomain(-3, 3), IntDomain(0, 3), IntDomain(0, 9), IntDomain(0, 81),
                IntDomain(0, 27), IntDomain(0, 2))
            val source = Problem(0, domains.size, domains, arrayOf(Abs(0, 1), Product(1, 1, 2), Power(2, 2, 3),
                DivMod(3, bigIntOf(3), 4, 5)))
            val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1), constant = 7)
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source),
                ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, objective, maximize))
            val shape = invocation.finiteShape()
            val preparation = FinitePipeline.prepare(FinitePipelineRequest(shape.problem, FiniteEngine.LOCAL_SEARCH,
                shape.linearObjective, PresolveConfig.NONE, preparedPlan = shape.preparedPlan))
            assertTrue(invocation.source.definitionHints.isEmpty())

            val view = assertNotNull(invocation.heuristicHints(preparation.problem, preparation.objective,
                preparation.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
                assertNotNull(preparation.executionPlan).capabilities))
            val assignment = Assignment(0, domains.size)
            assignment.setInt(0, -3)
            assertNotNull(view.sweep).sweep(assignment, domains)

            assertEquals(81L, assignment.intValue(3))
            assertEquals(27L, assignment.intValue(4))
            assertEquals(0L, assignment.intValue(5))
            assertEquals(if (maximize) 80.0 else -80.0,
                assertNotNull(view.objective).deltaIfApplied(assignment, Move.IntSet(0, 1)))
            assertNull(shape.localSearchObjective)
            assertNull(shape.definitionalSweep)
        }
    }
}
