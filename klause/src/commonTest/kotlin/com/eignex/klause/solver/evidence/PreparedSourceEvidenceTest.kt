package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PreparedSourceEvidenceTest {
    private val revision = SourceRevision("original-plan", "klause-ir-exact-v1")

    @Test
    fun `prepared capture reads original declarations rather than narrowed execution bounds`() {
        val original = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        val narrowed = Problem(0, 1, arrayOf(IntDomain(3, 9)), emptyArray())
        val plan = narrowed.prepareExecutionPlan(original, null, false, false)
        val capture = assertNotNull(plan.sourceResultCapture(revision))
        val sample = Sample(booleanArrayOf(), longArrayOf(1))

        val result = capture.solve(SolveResult.Sat(sample), "fixture-engine")

        assertEquals(SourceEvidenceCheck.Checked,
            assertNotNull(result.evidence).verify(SourceClaim.Witness(sample), capture.context))
    }

    @Test
    fun `prepared capture retains invocation pins and source objective direction`() {
        val original = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        val plan = original.prepareExecutionPlan(LinearObjective(intCoefficients = longArrayOf(1)), maximize = true)
        val capture = assertNotNull(plan.sourceResultCapture(revision, Assumptions(ints = mapOf(0 to 3L)), "x"))

        assertEquals(ObjectiveSense.MAXIMIZE, assertNotNull(capture.context.scope.objective).sense)
        assertEquals(Assumptions(ints = mapOf(0 to 3L)).sourceCondition(), capture.context.scope.condition)
    }

    @Test
    fun `another source language cannot be relabelled as exact original IR`() {
        val original = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        val plan = original.prepareExecutionPlan()

        assertNull(plan.sourceResultCapture(SourceRevision("decimal-source", "mps-exact-decimal")))
    }
}
