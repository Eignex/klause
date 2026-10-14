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
import kotlin.test.assertTrue

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
    fun `continuous majority counts real coordinates against all discrete coordinates`() {
        for ((counts, expected) in listOf(Triple(0, 1, 1) to false, Triple(0, 1, 2) to true,
            Triple(2, 1, 2) to false)
        ) {
            val (bools, ints, reals) = counts
            val model = Problem(
                bools, ints, Array(ints) { IntDomain(0, 1) }, emptyArray(),
                numRealVars = reals, realLower = DoubleArray(reals), realUpper = DoubleArray(reals) { 1.0 },
            ).bake()

            val majority = model.continuousMajority

            assertEquals(expected, majority)
        }
    }

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

    @Test
    fun `unused real declarations preserve the integer linear model fact`() {
        val model = Problem(
            0, 2, arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
        ).bake()

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE)

        assertTrue(facts.integerLinear)
    }

    @Test
    fun `disabled LP omits the integer linear companion`() {
        val model = linearModel()
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.OFF)

        val composed = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.COP).copy(lpCeiling = LpConfig.OFF), facts,
        )

        assertFalse(composed.any { it.label == "lp-aggressive" })
    }
}
