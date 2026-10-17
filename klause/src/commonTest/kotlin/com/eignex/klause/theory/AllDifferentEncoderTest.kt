package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.preparePolynomialExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AllDifferentEncoderTest {
    @Test
    fun `cancelled all different installation should publish no enforcing fragment`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2))))
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 1
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val declines = mutableListOf<EncoderDecline>()

        val installed = FactorProjectionProviders.Default.prepare(source).installLinearFactor(0,
            EncodingTarget.LINEAR, cancellation = cancellation, onDecline = declines::add)

        assertNull(installed)
        assertEquals(listOf(EncoderDecline.RESOURCE), declines)
    }

    @Test
    fun `linear all different encoding should retain optional polarity and exempt collisions`() {
        for ((value, present, expectedSat) in listOf(Triple(1L, true, false),
            Triple(1L, false, true), Triple(7L, true, true))) {
            val relation = AllDifferent(intArrayOf(0, 1),
                presents = intArrayOf(Lit.make(0, true), Lit.make(1, false)), exceptSet = longArrayOf(7))
            val source = Problem(2, 2, Array(2) { IntDomain(value, value) }, arrayOf<Factor>(relation,
                Clause(intArrayOf(Lit.make(0, present))), Clause(intArrayOf(Lit.make(1, false)))))
            val installed = assertNotNull(FactorProjectionProviders.Default.prepare(source)
                .linearTheoryModel(EncodingTarget.LINEAR))

            val result = BacktrackSolver.source(installed).solve()

            if (expectedSat) assertIs<OpenTheoryResult.Sat>(result) else assertIs<OpenTheoryResult.Unsat>(result)
        }
    }

    @Test
    fun `polynomial all different encoding should retain optional polarity and exempt collisions`() {
        for ((value, present, expectedSat) in listOf(Triple(1L, true, false),
            Triple(1L, false, true), Triple(7L, true, true))) {
            val relation = AllDifferent(intArrayOf(0, 1),
                presents = intArrayOf(Lit.make(0, true), Lit.make(1, false)), exceptSet = longArrayOf(7))
            val source = Problem(2, 2, Array(2) { IntDomain(value, value) }, arrayOf<Factor>(relation,
                Clause(intArrayOf(Lit.make(0, present))), Clause(intArrayOf(Lit.make(1, false))),
                RealProduct(0, 0, 0, 0.0, 0.0)), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
            val shared = assertNotNull(source.preparePolynomialExecutionPlan().polynomialModel)

            val pins = (0..1).associate { shared.integerColumns.getValue(it) to BigFraction.ofLong(value) }
            val result = shared.service.invocation().use {
                it.check(PolynomialCondition(lower = pins, upper = pins))
            }

            if (expectedSat) assertIs<QfNraResult.Satisfiable>(result) else assertIs<QfNraResult.Unsatisfiable>(result)
            assertEquals(0, shared.encodingAttributions.first().sourceFactorIndex)
        }
    }
}
