package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
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
    fun `continuous majority should count real coordinates against all discrete coordinates`() {
        for (
        (counts, expected) in listOf(
            Triple(0, 1, 1) to false,
            Triple(0, 1, 2) to true,
            Triple(2, 1, 2) to false,
        )
        ) {
            val (bools, ints, reals) = counts
            val model = Problem(
                bools,
                ints,
                Array(ints) { IntDomain(0, 1) },
                emptyArray(),
                numRealVars = reals,
                realLower = DoubleArray(reals),
                realUpper = DoubleArray(reals) { 1.0 },
            ).bake()

            val majority = model.continuousMajority

            assertEquals(expected, majority)
        }
    }

    @Test
    fun `integer domain bounds should identify binary pools`() {
        for ((upper, binary) in listOf(1L to true, 5L to false)) {
            val model = linearModel(upper)

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE)

            assertEquals(binary, facts.binaryIntegers)
        }
    }

    @Test
    fun `an lp ceiling of off should leave nothing to relax`() {
        val model = linearModel()
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig(LpEmphasis.OFF))

        assertFalse(facts.offers(ArmNeed.Relaxation(LpEmphasis.DEFAULT)))
    }

    @Test
    fun `exact integer equivalents should retain the integer linear model fact`() {
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val half = BigFraction.of(bigIntOf(1), bigIntOf(2))
        val rows = listOf(
            ExactLinear.of(refs, List(2) { BigFraction.ONE }, LinearOp.GE, BigFraction.ofLong(3)),
            ExactLinear.of(refs, List(2) { half }, LinearOp.GE, BigFraction.of(bigIntOf(3), bigIntOf(2))),
            ExactLinear.of(refs, List(2) { BigFraction.ONE }, LinearOp.GE, BigFraction.ofLong(2), strict = true),
            ExactLinear.of(refs, List(2) { BigFraction.ONE }, LinearOp.LE, BigFraction.ofLong(4), strict = true),
        )
        val variants = rows.map { arrayOf<Factor>(it) } + listOf(arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3), rows.first(),
        ))
        for (factors in variants) {
            val model = Problem(0, 2, Array(2) { IntDomain(0, 5) }, factors).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE)

            assertTrue(facts.integerLinear)
        }
    }

    @Test
    fun `nonordinary factors should decline the integer linear model fact`() {
        val refs = intArrayOf(Term.ofIntVar(0))
        val wide = BigFraction.of(parseBigInt("9223372036854775808"), BIG_ONE)
        val rows = listOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE, BigFraction.ONE),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.LE,
                BigFraction.ONE, strict = true),
            ExactLinear.of(refs, listOf(wide), LinearOp.LE, BigFraction.ONE),
            ExactLinear.of(refs, listOf(BigFraction.ONE), LinearOp.LE, wide),
            ExactLinear.of(refs, listOf(BigFraction.ONE), LinearOp.LE, BigFraction.ONE, activator = 0),
            LinearIndicator(1, true, ExactLinear.of(refs, listOf(BigFraction.ONE), LinearOp.LE, BigFraction.ONE)),
            Product(0, 1, 0),
        )
        for (row in rows) {
            val factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3), row)
            val model = Problem(1, 2, Array(2) { IntDomain(0, 5) }, factors,
                numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(5.0)).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE)

            assertFalse(facts.integerLinear)
        }
    }

    @Test
    fun `a continuous objective should omit the exact integer companion`() {
        val row = ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE),
            LinearOp.LE, BigFraction.ofLong(3))
        val model = Problem(0, 1, arrayOf(IntDomain(0, 5)), arrayOf<Factor>(row),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE, objective)

        val composed = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP), facts)

        assertFalse(composed.any { it.label == "lp-aggressive" })
    }

    @Test
    fun `unused real declarations should preserve the integer linear model fact`() {
        val rows = listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)), List(2) { BigFraction.ONE },
                LinearOp.GE, BigFraction.ofLong(3)),
        )
        for (row in rows) {
            val model = Problem(0, 2, Array(2) { IntDomain(0, 5) }, arrayOf<Factor>(row),
                numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0)).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE)

            assertTrue(facts.integerLinear)
        }
    }

    @Test
    fun `disabled LP should omit the integer linear companion`() {
        val model = linearModel()
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.OFF)

        val composed = PortfolioComposition.compose(
            PortfolioScenario.sequential(Kind.COP).copy(lpCeiling = LpConfig.OFF),
            facts,
        )

        assertFalse(composed.any { it.label == "lp-aggressive" })
    }
}
