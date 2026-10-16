package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.preparePolynomialExecutionPlan
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AllDifferentEncoderTest {
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
                RealProduct(0, 0, 0)), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
            val shared = assertNotNull(source.preparePolynomialExecutionPlan().polynomialModel)

            val result = shared.service.invocation().use { it.check(PolynomialCondition()) }

            if (expectedSat) assertIs<QfNraResult.Satisfiable>(result) else assertIs<QfNraResult.Unsatisfiable>(result)
            assertEquals(0, shared.encodingAttributions.first().sourceFactorIndex)
        }
    }
}
