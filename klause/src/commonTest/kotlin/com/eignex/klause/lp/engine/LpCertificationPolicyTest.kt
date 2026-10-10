package com.eignex.klause.lp.engine

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.openLpInfeasible
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingCertificationPolicy(private val accept: Boolean) : LpCertificationPolicy {
    val attempts = ArrayList<Pair<LpCertifier, Boolean>>()

    override fun accepts(certifier: LpCertifier, successful: Boolean): Boolean {
        attempts += certifier to successful
        return accept && successful
    }
}

class LpCertificationPolicyTest {

    @Test
    fun `forced decline rejects supplied certificates on legacy and retained models`() {
        val source = LpBuilder().apply { addVar(0L, 3L, cost = 1L) }.build(Sense.MINIMIZE)
        val retained = assertNotNull(LpExactState(assertNotNull(source.authoritativeModel())).toWorkingModel())
        for (model in listOf(source, retained)) {
            val certificate = assertNotNull(integerCertify(model, doubleArrayOf()))
            val policy = RecordingCertificationPolicy(accept = false)

            val bound = certifiedTightObjectiveLowerBound(model, doubleArrayOf(), certificate, null, policy)

            assertNull(bound)
            assertEquals(listOf(LpCertifier.SAFE_OBJECTIVE, LpCertifier.INTEGER), policy.attempts.map { it.first })
            assertTrue(policy.attempts.all { it.second })
        }
    }

    @Test
    fun `forced decline bypasses basis point safe and rational recovery`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.5)
        val policy = RecordingCertificationPolicy(accept = false)

        val result = solveAndCertify(
            builder.build(Sense.MINIMIZE),
            context = LpSolveContext(certificationPolicy = policy),
        )
        val safe = result.safeLowerBound

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertNull(safe)
        assertTrue(LpCertifier.INTEGER in policy.attempts.map { it.first })
        assertTrue(LpCertifier.EXACT_BASIS in policy.attempts.map { it.first })
        assertTrue(LpCertifier.EXACT_POINT in policy.attempts.map { it.first })
        assertTrue(LpCertifier.RATIONAL in policy.attempts.map { it.first })
        assertTrue(LpCertifier.SAFE_OBJECTIVE in policy.attempts.map { it.first })
    }

    @Test
    fun `policies are isolated between solve contexts`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val model = builder.build(Sense.MINIMIZE)
        val decline = RecordingCertificationPolicy(accept = false)

        val rejected = solveAndCertify(model, context = LpSolveContext(certificationPolicy = decline))
        val accepted = solveAndCertify(model)
        val rejectedAgain = solveAndCertify(model, context = LpSolveContext(certificationPolicy = decline))

        assertEquals(LpVerdict.INDETERMINATE, rejected.verdict)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, accepted.verdict)
        assertEquals(2L, accepted.integerObjectiveLowerBound)
        assertEquals(LpVerdict.INDETERMINATE, rejectedAgain.verdict)
    }

    @Test
    fun `open refutation forwards the solve context`() {
        val rows = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
        )
        val policy = RecordingCertificationPolicy(accept = false)

        val rejected = openLpInfeasible(
            arrayOf(OpenIntBounds(null, null)),
            rows,
            context = LpSolveContext(certificationPolicy = policy),
        )

        assertTrue(openLpInfeasible(arrayOf(OpenIntBounds(null, null)), rows))
        assertEquals(false, rejected)
        assertTrue(LpCertifier.RATIONAL in policy.attempts.map { it.first })
    }

    @Test
    fun `leaf real feasibility forwards factory and policy`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(row),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        val factory = RecordingLpEngineFactory()
        val result = leafRealFeasibility(
            problem,
            objective = null,
            sample = Sample(booleanArrayOf(), longArrayOf()),
            context = LpSolveContext(factory, RecordingCertificationPolicy(accept = false)),
        )

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.GENERAL })
    }
}
