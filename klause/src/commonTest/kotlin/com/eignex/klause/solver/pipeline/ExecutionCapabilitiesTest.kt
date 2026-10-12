package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExecutionCapabilitiesTest {
    @Test
    fun `finite counting selects complete cells independently of sampling backend`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(AllDifferent(intArrayOf(0, 1), 0, 3)))
        val plan = source.prepareExecutionPlan(preferFinite = true)

        val admission = plan.capabilities.admission(FiniteEngine.LOCAL_SEARCH, ExecutionOperation.EXACT_COUNT)
        val count = BacktrackSolver(source.bake()).exactCount().last()

        assertEquals(6L, count.lower)
        assertEquals(6L, count.upper)
        assertTrue(admission.runnable)
        assertEquals(listOf(ExecutionStrategy.COMPLETE), admission.arms)
        assertEquals(setOf(ExecutionClaimAuthority.PROJECTED_COUNT_BOUND), admission.claimAuthorities)
    }

    @Test
    fun `native source completion does not invent a counting surface`() {
        val source = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 2))",
        )).problem
        val plan = source.prepareExecutionPlan()

        val admission = plan.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.EXACT_COUNT)

        assertFalse(admission.runnable)
        assertEquals(ExecutionDeclineCause.OPERATION_SURFACE, admission.declines.single().cause)
        assertEquals(emptySet(), admission.claimAuthorities)
    }

    @Test
    fun `unsupported open polynomial ownership remains distinct from exhausted arithmetic work`() {
        val open = Bits(1).also { it.set(0) }
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(ContinuousProduct(0, 0, 0)), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        val supported = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (assert (= (* x x) 2))",
        )).problem.pipelineRequest()

        val unsupported = source.prepareExecutionPlan().capabilities.admission(FiniteEngine.BACKTRACK)
        val exhausted = OpenTheoryPipeline.execute(supported, PolynomialTheoryParams(maximumWork = 0),
            FiniteEngine.BACKTRACK).result

        assertFalse(unsupported.runnable)
        assertTrue(unsupported.declines.any { it.preparation == PreparedPlanDecline.OPEN_POLYNOMIAL_INTEGER })
        assertTrue(supported.preparedPlan.capabilities.admission(FiniteEngine.BACKTRACK).runnable)
        assertEquals(TerminationReason.BudgetExhausted, assertIs<OpenTheoryResult.Unknown>(exhausted).reason)
    }

    @Test
    fun `operation binding retains immutable original source ownership and direction`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val plan = source.prepareExecutionPlan(preferFinite = true)
        val objective = LinearObjective(intCoefficients = longArrayOf(1))

        val operation = plan.withObjective(objective, maximize = true)
        val complete = operation.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE)
        val heuristic = operation.capabilities.admission(FiniteEngine.LOCAL_SEARCH, ExecutionOperation.OPTIMIZE)

        assertSame(source, operation.originalSource)
        assertSame(plan.componentPlan, operation.componentPlan)
        assertSame(objective, operation.objective)
        assertTrue(operation.maximize)
        assertTrue(complete.runnable)
        assertEquals(ProcedureCompleteness.EXHAUSTIVE, complete.completeness)
        assertEquals(setOf(ExecutionClaimAuthority.WITNESS), heuristic.claimAuthorities)
    }

    @Test
    fun `an unsupported polynomial objective does not make source satisfaction unsupported`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 0)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val plan = source.prepareExecutionPlan(LinearObjective(realCoefficients = doubleArrayOf(1.0)))

        val satisfaction = plan.capabilities.admission(FiniteEngine.BACKTRACK)
        val optimization = plan.capabilities.admission(FiniteEngine.BACKTRACK, ExecutionOperation.OPTIMIZE)

        assertTrue(satisfaction.runnable)
        assertFalse(optimization.runnable)
        assertEquals(ExecutionDeclineCause.OBJECTIVE, optimization.declines.single().cause)
    }
    @Test
    fun `polynomial invocation pins do not change prepared source admission or later witnesses`() {
        val source = assertIs<SmtLibInput.Polynomial>(SmtLibAdmission.parse(
            "(declare-const x Real) (declare-const b Bool) (assert (= (* x x) 2)) (assert b)",
        )).problem
        val request = source.pipelineRequest()
        val scoped = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            assumptions = Assumptions.None.withBool(0, false)))

        val rejected = OpenTheoryPipeline.execute(request, scoped, FiniteEngine.BACKTRACK).result
        val fresh = OpenTheoryPipeline.execute(request, engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(rejected)
        assertTrue(assertIs<OpenTheoryResult.Sat>(fresh).assignment.boolValue(0))
        assertSame(source, request.preparedPlan.originalSource)
        assertTrue(request.preparedPlan.capabilities.admission(FiniteEngine.BACKTRACK).runnable)
    }

}
