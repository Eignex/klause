package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class CpToLpRelaxationRltToleranceTest(private val relative: Boolean, private val relaxed: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "relative={0} relaxed={1}")
        fun variants(): List<Array<Boolean>> = listOf(false, true).flatMap { relative ->
            listOf(false, true).map { arrayOf(relative, it) }
        }

        @JvmStatic
        @BeforeClass
        fun initializeRelaxation() = LinearSourceRowToleranceTest.initializeRelaxation()
    }

    @Test
    fun `RLT tolerance admission should require original binary declarations`() {
        val policy = if (!relaxed) SourceTolerance() else if (relative) {
            SourceTolerance(relative = SemanticRational.of(BIG_ONE, bigIntOf(4)))
        } else SourceTolerance(SemanticRational.ONE)
        val source = Problem(0, 2, Array(2) { IntDomain(-1, 2) }, listOf(
            Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 3),
        ), semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = policy).resolve())
        val problem = source.withIntDomains(Array(2) { IntDomain(0, 1) })

        val base = CpToLpRelaxation(problem, null, booleanRlt = true).build(RootDomains(problem))

        if (relaxed) assertEquals(2, base.model.n) else assertTrue(base.model.n > 2)
    }

    @Test
    fun `RLT pricing should cover an integer point accepted by source tolerance`() {
        val absolute = if (!relative && relaxed) SemanticRational.ONE else SemanticRational.ZERO
        val rate = if (relative && relaxed) SemanticRational.of(BIG_ONE, bigIntOf(4)) else SemanticRational.ZERO
        val policy = SourceTolerance(absolute, rate)
        val bound = if (relative) 3 else 1000
        val coefficient = if (relative) 2 else 1
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(coefficient, coefficient), intArrayOf(0, 1), LinearOp.LE, bound),
        ), semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = policy).resolve())
        val values = when {
            relative -> if (relaxed) longArrayOf(1, 1) else longArrayOf(1, 0)
            relaxed -> longArrayOf(-1, 0)
            else -> longArrayOf(0, 0)
        }
        val lower = if (relaxed && !relative) -1L else 0L
        assertTrue(values.all { it >= lower && it <= 1L })
        assertTrue(verifyCapturedRealTolerance(problem, Sample(booleanArrayOf(), values),
            policy, ExecutionControl.Never) is Verification.Accepted)
        val objective = LinearObjective(intCoefficients = if (relative) longArrayOf(-1, -1) else longArrayOf(1, 0))
        val base = CpToLpRelaxation(problem, objective, booleanRlt = true).build(RootDomains(problem))
        assertTrue(base.model.n > 2)
        val duals = if (relative) RevisedSimplex(base.model).use { assertNotNull(it.solve()).duals } else {
            val authority = assertNotNull(base.model.authoritativeModel())
            val row = authority.entries(base.intColOf[0]).single {
                it.number.value == BigFraction.ofLong(1L - bound)
            }.row
            DoubleArray(base.model.m).also { it[row] = -1.0 / (bound - 1) }
        }

        val certificate = assertNotNull(FloatDeductionCertificate.of(base.model, duals,
            slack = LpBoundSlack(if (!relative && relaxed) 1.0 else 0.0,
                if (relative && relaxed) 0.25 else 0.0)))

        val value = if (relative) -values.sum().toDouble() else values[0].toDouble()
        assertTrue(certificate.lowerBound <= value, "bound ${certificate.lowerBound} excludes source objective $value")
    }
}
