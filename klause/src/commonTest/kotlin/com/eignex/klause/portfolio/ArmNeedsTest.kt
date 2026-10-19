package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
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
    fun `unused real declarations should preserve the integer linear model fact`() {
        val model = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        ).bake()

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, optimizing = true), LpConfig.AGGRESSIVE)

        assertTrue(facts.integerLinear)
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

    @Test
    fun `binary packing should recognize ordinary and exact integer capacities`() {
        val rows = listOf(
            Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 3),
            ExactLinear.of(
                intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                listOf(BigFraction.ofLong(2), BigFraction.ofLong(3)),
                LinearOp.LE,
                BigFraction.ofLong(3),
            ),
        )
        for (row in rows) {
            val model = Problem(0, 2, Array(2) { IntDomain(0, 1) }, listOf(row)).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(-4, -5))

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO, objective)

            assertTrue(facts.binaryPackingObjective)
        }
    }

    @Test
    fun `binary packing should need binary ranges and an item profit objective`() {
        val profits = LinearObjective(intCoefficients = longArrayOf(-4, -5))
        for ((upper, objective, expected) in listOf(
            Triple(1L, profits, true),
            Triple(5L, profits, false),
            Triple(1L, LinearObjective(intCoefficients = longArrayOf(4, 5)), false),
            Triple(1L, LinearObjective(intCoefficients = longArrayOf(-4, 5)), false),
            Triple(1L, LinearObjective(), false),
            Triple(1L, null, false),
        )) {
            val model = Problem(
                0, 2, Array(2) { IntDomain(0, upper) },
                listOf(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 30)),
            ).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO, objective)

            assertEquals(expected, facts.binaryPackingObjective)
        }
    }

    @Test
    fun `other row forms should not claim a binary packing objective`() {
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val wide = BigFraction.of(parseBigInt("18446744073709551617"), BIG_ONE)
        val cases = listOf<Triple<Factor, Int, Int>>(
            Triple(Linear(longArrayOf(-2, 3), intArrayOf(0, 1), LinearOp.LE, 3), 0, 0),
            Triple(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.EQ, 3), 0, 0),
            Triple(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.NE, 3), 0, 0),
            Triple(Linear(longArrayOf(2), intArrayOf(0), LinearOp.LE, 3), 0, 0),
            Triple(Linear(longArrayOf(2, 3), intArrayOf(0, 0), LinearOp.LE, 3), 0, 0),
            Triple(Linear(longArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, -1), 0, 0),
            Triple(ExactLinear.of(refs, listOf(wide, wide), LinearOp.LE, wide), 0, 0),
            Triple(
                ExactLinear.of(
                    refs, listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.LE,
                    BigFraction.ONE, activator = 0,
                ),
                1, 0,
            ),
            Triple(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.LE, BigFraction.ONE,
                ),
                0, 1,
            ),
        )
        for ((row, bools, reals) in cases) {
            val model = Problem(
                bools, 2, Array(2) { IntDomain(0, 1) }, listOf(row),
                numRealVars = reals,
                realLower = DoubleArray(reals),
                realUpper = DoubleArray(reals) { 1.0 },
            ).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(-4, -5))

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO, objective)

            assertFalse(facts.binaryPackingObjective)
        }
    }

    @Test
    fun `binary equality objectives should recognize ordinary and exact rows`() {
        val rows = listOf(
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
            ExactLinear.of(
                intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.EQ, BigFraction.ONE,
            ),
            ExactLinear.of(
                intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                List(2) { BigFraction.ofLong(2).reciprocal() },
                LinearOp.EQ, BigFraction.ofLong(2).reciprocal(),
            ),
        )
        for (row in rows) {
            val model = Problem(
                0, 3, Array(3) { IntDomain(0, 1) },
                listOf(row, Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0)),
            ).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(2, 3, 4))

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO, objective)

            assertTrue(facts.binaryEqualityObjective)
        }
    }

    @Test
    fun `binary equality objectives should need binary ranges and nonnegative costs`() {
        val costs = LinearObjective(intCoefficients = longArrayOf(4, 5))
        for ((upper, objective, expected) in listOf(
            Triple(1L, costs, true),
            Triple(5L, costs, false),
            Triple(1L, LinearObjective(intCoefficients = longArrayOf(-4, -5)), false),
            Triple(1L, LinearObjective(intCoefficients = longArrayOf(-4, 5)), false),
            Triple(1L, LinearObjective(), false),
            Triple(1L, null, false),
        )) {
            val model = Problem(
                0, 2, Array(2) { IntDomain(0, upper) },
                listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1)),
            ).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO, objective)

            assertEquals(expected, facts.binaryEqualityObjective)
        }
    }

    @Test
    fun `other row forms should not claim a binary equality objective`() {
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val wide = BigFraction.of(parseBigInt("18446744073709551617"), BIG_ONE)
        val cases = listOf<Triple<Factor?, Int, Int>>(
            Triple(null, 0, 0),
            Triple(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1), 0, 0),
            Triple(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1), 0, 0),
            Triple(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 1), 0, 0),
            Triple(ExactLinear.of(refs, listOf(wide, BigFraction.ONE), LinearOp.EQ, BigFraction.ONE), 0, 0),
            Triple(
                ExactLinear.of(
                    refs, listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.EQ,
                    BigFraction.ONE, activator = 0,
                ),
                1, 0,
            ),
            Triple(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                    listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.EQ, BigFraction.ONE,
                ),
                0, 1,
            ),
        )
        for ((row, bools, reals) in cases) {
            val model = Problem(
                bools, 2, Array(2) { IntDomain(0, 1) }, listOfNotNull(row),
                numRealVars = reals,
                realLower = DoubleArray(reals),
                realUpper = DoubleArray(reals) { 1.0 },
            ).bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(4, 5))

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO, objective)

            assertFalse(facts.binaryEqualityObjective)
        }
    }

    @Test
    fun `binary equality eligibility should cover every factor`() {
        val model = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                    List(2) { BigFraction.ONE }, LinearOp.EQ, BigFraction.ONE,
                ),
                ExactLinear.of(
                    intArrayOf(Term.ofIntVar(1), Term.ofIntVar(2)),
                    List(2) { BigFraction.ONE }, LinearOp.GE, BigFraction.ONE,
                ),
            ),
        ).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(2, 3, 4))

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO, objective)

        assertFalse(facts.binaryEqualityObjective)
    }
}
