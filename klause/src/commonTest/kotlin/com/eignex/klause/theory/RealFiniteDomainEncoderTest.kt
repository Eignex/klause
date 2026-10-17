package com.eignex.klause.theory

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.baked
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.preparePolynomialExecutionPlan
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealFiniteDomainEncoderTest {
    @Test
    fun `selected linear completion should enforce a remapped sparse domain`() {
        val domain = RealFiniteDomain(1, doubleArrayOf(-2.5, 0.125, 7.0))
            .remap(VarRemap(intArrayOf(), intArrayOf(), intArrayOf(1, 0)))
        for ((value, valid) in listOf(0.125 to true, 0.25 to false)) {
            val source = Problem(0, 0, emptyArray(), arrayOf(domain,
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, value)),
                numRealVars = 1, realLower = doubleArrayOf(-3.0), realUpper = doubleArrayOf(8.0))

            val result = BacktrackSolver.source(source).solve()

            if (valid) assertIs<OpenTheoryResult.Sat>(result) else assertIs<OpenTheoryResult.Unsat>(result)
            assertEquals(0, source.numBoolVars)
            assertEquals(1, source.numRealVars)
        }
    }

    @Test
    fun `polynomial membership should retain disconnected coordinates across Boolean queries`() {
        val source = Problem(1, 0, emptyArray(), arrayOf(
            RealFiniteDomain(0, doubleArrayOf(-1.0, 2.0)),
            RealFiniteDomain(1, doubleArrayOf(0.125)),
            ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 2.0),
        ), numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(2.0, 1.0))
        val model = assertNotNull(source.preparePolynomialExecutionPlan().polynomialModel)
        val arithmetic = AlgebraicArithmetic()

        model.service.invocation().use { owner ->
            for (truth in listOf(false, true, false)) {
                val result = assertIs<QfNraResult.Satisfiable>(owner.check(PolynomialCondition(bools = mapOf(0 to truth))))

                assertEquals(0, arithmetic.compare(result.assignment.realValue(0),
                    arithmetic.rational(BigFraction.ofLong(if (truth) 2 else -1))))
                assertEquals(0, arithmetic.compare(result.assignment.realValue(1),
                    arithmetic.rational(BigFraction.ONE / BigFraction.ofLong(8))))
            }
        }
        assertEquals(2, source.numRealVars)
        assertEquals(1, source.numBoolVars)
    }

    @Test
    fun `cancelled membership installation should not poison a later selected cover`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf(-2.5, 0.125, 7.0))),
            numRealVars = 1, realLower = doubleArrayOf(-3.0), realUpper = doubleArrayOf(8.0))
        val selected = FactorProjectionProviders.Default.prepare(source)
        var work = 0L
        val stopped = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 2L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val interrupted = selected.linearTheoryModel(EncodingTarget.LINEAR, cancellation = stopped)
        val fresh = selected.linearTheoryModel(EncodingTarget.LINEAR)

        assertNull(interrupted)
        assertNotNull(fresh)
        assertTrue(work >= 2L)
        assertEquals(0, source.numBoolVars)
        assertEquals(1, source.numRealVars)
    }

    @Test
    fun `the finite facade should complete every disconnected real membership`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(
            RealFiniteDomain(0, doubleArrayOf(0.125)),
            RealFiniteDomain(1, doubleArrayOf(-2.5, 7.0)),
        ), numRealVars = 2, realLower = doubleArrayOf(0.0, -3.0), realUpper = doubleArrayOf(1.0, 8.0))

        val result = assertIs<SolveResult.Sat>(BacktrackSolver(source.bake()).solve())

        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, result.assignment))
        assertEquals(BigFraction.ONE / BigFraction.ofLong(8), assertNotNull(result.assignment.exactReals)[0])
        assertEquals(2, result.assignment.numRealVars)
        assertEquals(0, source.numIntVars)
    }

    @Test
    fun `an empty source membership should refute finite preparation`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealFiniteDomain(0, doubleArrayOf())),
            numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))

        val result = source.baked

        assertIs<PropagationResult.Unsat>(result)
    }
}
