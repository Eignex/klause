package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PolynomialWitnessVerifierTest {
    @Test
    fun `private native witness transfers to its containing invocation`() {
        val source = Problem(numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        val plan = source.preparePolynomialExecutionPlan()
        val model = assertNotNull(plan.polynomialModel)
        val containing = EngineInvocation(plan, ModelIdentity.of(source))
        val params = PolynomialStrategyParams(assumptions = Assumptions(ints = mapOf(0 to 1L)))
        val producer = EngineInvocation(plan, ModelIdentity.of(source), sourceAssumptions = params.assumptions)
        val point = model.service.invocation().use { native ->
            assertIs<SolveResult.Sat>(PolynomialWorkerAdapter(model, native, params, producer, Cancellation.Never)
                .solve(FiniteEngine.BACKTRACK)).assignment
        }

        val checked = polynomialWitnessVerifier(containing, Cancellation.Never).verify(Candidate(point, null))

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `native witness from another prepared invocation is rejected`() {
        val source = Problem(numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        val plan = source.preparePolynomialExecutionPlan()
        val model = assertNotNull(plan.polynomialModel)
        val producer = EngineInvocation(plan, ModelIdentity.of(source))
        val point = model.service.invocation().use { native ->
            assertIs<SolveResult.Sat>(PolynomialWorkerAdapter(model, native, PolynomialStrategyParams(),
                producer, Cancellation.Never).solve(FiniteEngine.BACKTRACK)).assignment
        }
        val consumer = EngineInvocation(source.prepareExecutionPlan(), ModelIdentity.of(source))

        val checked = polynomialWitnessVerifier(consumer, Cancellation.Never).verify(Candidate(point, null))

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `native witness violating consumer pins is rejected`() {
        val source = Problem(numBoolVars = 0, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf(RealProduct(0, 0, 0, 1.0, 1.0)),
            numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(1.0))
        val plan = source.preparePolynomialExecutionPlan()
        val model = assertNotNull(plan.polynomialModel)
        val producer = EngineInvocation(plan, ModelIdentity.of(source))
        val point = model.service.invocation().use { native ->
            assertIs<SolveResult.Sat>(PolynomialWorkerAdapter(model, native, PolynomialStrategyParams(),
                producer, Cancellation.Never).solve(FiniteEngine.BACKTRACK)).assignment
        }
        val consumer = EngineInvocation(plan, ModelIdentity.of(source),
            sourceAssumptions = Assumptions(ints = mapOf(0 to 0L)))

        val checked = polynomialWitnessVerifier(consumer, Cancellation.Never).verify(Candidate(point, null))

        assertIs<Verification.Rejected>(checked)
    }
}
