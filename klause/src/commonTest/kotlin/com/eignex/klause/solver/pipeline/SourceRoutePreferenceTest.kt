package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class SourceRoutePreferenceTest {
    @Test
    fun `exact linear preference should select an admitted alternate over the same bounded source`() {
        val source = Problem(0, 2, Array(2) { IntDomain(-1, 1) },
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)))
        val automatic = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
        ))

        val alternate = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            routePreference = SourceRoutePreference.EXACT_LINEAR,
        ))

        assertIs<SourceProblemRoute.Finite>(automatic.route)
        val route = assertIs<SourceProblemRoute.OpenTheory>(alternate.route)
        assertEquals(ProblemPipeline.EXACT_LIRA, route.request.route)
        assertSame(source, route.preparedPlan.originalSource)
        assertSame(source, route.request.model)
        assertEquals(listOf(IntVariableOwner.CP, IntVariableOwner.CP),
            (0..1).map { route.request.componentPlan.intOwner(it) })
    }

    @Test
    fun `exact linear preference should enforce excluded finite values in mixed completion`() {
        val domain = assertNotNull(IntDomain(0, 2).excludeValues(longArrayOf(1)))
        val source = Problem(0, 1, arrayOf(domain), arrayOf(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            routePreference = SourceRoutePreference.EXACT_LINEAR,
        ))
        val route = assertIs<SourceProblemRoute.OpenTheory>(invocation.route)

        val result = assertIs<OpenTheoryExecution.Satisfy>(
            OpenTheoryPipeline.execute(route.request.withPresolve(PresolveConfig.NONE)),
        ).result

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(domain, route.request.model.intDomainOrNull(0))
    }

    @Test
    fun `exact linear preference should retain finite execution for unsupported nonlinear arithmetic`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) }, arrayOf(Product(0, 1, 2)))

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            routePreference = SourceRoutePreference.EXACT_LINEAR,
        ))

        assertSame(source, assertIs<SourceProblemRoute.Finite>(invocation.route).problem)
    }

    @Test
    fun `exact linear preference should withhold execution after preparation cancellation`() {
        val source = Problem(0, 2, Array(2) { IntDomain(-1, 1) },
            arrayOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 1)))

        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source), ExecutionOperation.SATISFY, FiniteEngine.BACKTRACK,
            cancellation = Cancellation { true }, routePreference = SourceRoutePreference.EXACT_LINEAR,
        ))

        val route = assertIs<SourceProblemRoute.UnsupportedOpen>(invocation.route)
        assertEquals(PreparedPlanDecline.RESOURCE, route.preparedPlan.decline)
    }
}
