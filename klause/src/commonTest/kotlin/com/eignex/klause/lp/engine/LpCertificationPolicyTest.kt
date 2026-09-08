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
    fun `forced decline reaches rational fallback and stays indeterminate`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val policy = RecordingCertificationPolicy(accept = false)

        val result = solveAndCertify(
            builder.build(Sense.MINIMIZE),
            context = LpSolveContext(certificationPolicy = policy),
        )

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertEquals(
            listOf(LpCertifier.EXACT_FARKAS, LpCertifier.RATIONAL),
            policy.attempts.map { it.first },
        )
        assertTrue(policy.attempts.all { it.second })
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
    fun `forced decline reaches component objective certification`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 3L, cost = 1L)
        val y = builder.addVar(0L, 3L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val policy = RecordingCertificationPolicy(accept = false)

        val result = solveAndCertify(
            builder.build(Sense.MINIMIZE),
            context = LpSolveContext(certificationPolicy = policy),
        )

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertTrue(policy.attempts.count { it.first == LpCertifier.INTEGER } >= 2)
    }

    @Test
    fun `forced decline reaches component basis certification`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 3.0)
        val y = builder.addRealVar(0.0, 3.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        builder.addRealRow(intArrayOf(y), doubleArrayOf(1.0), Relation.GE, 2.0)
        val policy = RecordingCertificationPolicy(accept = false)

        val result = solveAndCertify(
            builder.build(Sense.MINIMIZE),
            context = LpSolveContext(certificationPolicy = policy),
        )

        assertEquals(LpVerdict.INDETERMINATE, result.verdict)
        assertTrue(policy.attempts.count { it.first == LpCertifier.EXACT_BASIS } >= 2)
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
        assertEquals(LpVerdict.OPTIMAL, accepted.verdict)
        assertEquals(2L, accepted.exactLowerBound)
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
