package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ProblemProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArmNeedsTest {
    private fun linearModel(upper: Long = 5L) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, upper), IntDomain(0, upper)),
        factors = arrayOf<Factor>(
            Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
        ),
    ).bake()

    @Test
    fun `integer domain bounds identify binary pools`() {
        for ((upper, binary) in listOf(1L to true, 5L to false)) {
            val model = linearModel(upper)

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE)

            assertEquals(binary, facts.binaryIntegers)
        }
    }

    @Test
    fun `an lp ceiling of off leaves nothing to relax`() {
        val model = linearModel()
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig(LpEmphasis.OFF))

        assertFalse(facts.offers(ArmNeed.Relaxation(LpEmphasis.DEFAULT)))
    }
}
