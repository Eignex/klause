package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArmNeedsTest {
    @Test
    fun `integer only coordinates should exclude Boolean and empty integer projections`() {
        for ((booleans, integers) in listOf(0 to 2, 1 to 2, 1 to 0)) {
            val model = Problem(booleans, integers, Array(integers) { IntDomain(0, 1) }, emptyArray()).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.OFF)

            assertEquals(booleans == 0 && integers > 0, facts.integerColumnsOnly)
        }
    }

    @Test
    fun `curated eligibility should identify the missing objective without changing explicit recipes`() {
        val facts = ProblemFacts.assumed(Kind.CSP)
        val required = setOf(ArmNeed.Objective)

        val eligibility = facts.eligibility("objective-guided", required)

        assertFalse(eligibility.admitted)
        assertEquals(listOf("missing:Objective"), eligibility.declines)
        assertEquals(required, eligibility.requirements.needs)
        assertEquals("objective-guided", BacktrackCatalog.byLabel("objective-guided").label)
    }

    @Test
    fun `small ground tables should offer support ordering`() {
        val model = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), longArrayOf(0, 0, 1, 1))),
        ).bake()

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, false), LpConfig.OFF)

        assertTrue(facts.offers(ArmNeed.ExtensionalSupports))
    }

    @Test
    fun `interval tables should decline automatic support ordering`() {
        val model = Problem(
            0, 2, Array(2) { IntDomain(0, 64) },
            arrayOf<Factor>(Table(intArrayOf(0, 1), longArrayOf(0, 0), longArrayOf(64, 64))),
        ).bake()

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, false), LpConfig.OFF)

        assertFalse(facts.offers(ArmNeed.ExtensionalSupports))
    }

    @Test
    fun `wide table scopes or domains should decline automatic support ordering`() {
        for ((arity, values) in listOf(33 to 2, 2 to 33)) {
            val model = Problem(
                0, arity, Array(arity) { IntDomain(0, (values - 1).toLong()) },
                arrayOf<Factor>(Table(IntArray(arity) { it }, LongArray(arity * values) { (it / arity).toLong() })),
            ).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, false), LpConfig.OFF)

            assertFalse(facts.offers(ArmNeed.ExtensionalSupports))
        }
    }

    @Test
    fun `finite preparation should preserve an integer linear proof recipe`() {
        val side = 4
        val rows = (0 until side).map { row ->
            Linear(LongArray(side) { 1L }, IntArray(side) { row * side + it }, LinearOp.EQ, 2L)
        } + (0 until side).map { column ->
            Linear(LongArray(side) { 1L }, IntArray(side) { it * side + column }, LinearOp.EQ, 2L)
        }
        val source = Problem(0, side * side, Array(side * side) { IntDomain(0, 1) }, rows)
        val objective = LinearObjective(intCoefficients = LongArray(side * side) {
            val distance = it / side - it % side
            (distance * distance).toLong()
        })
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(source, FiniteEngine.MIXED, objective))
        val model = prepared.executableProblem()
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AGGRESSIVE, prepared.objective)

        val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP), facts)

        assertTrue(
            arms.any { it.label == "lp-aggressive" },
            "integerLinear=${facts.integerLinear}, origin=${prepared.objective?.binaryIntegerSource != null}, " +
                "arms=${arms.map { it.label }}, factors=${model.factors.map { it::class.simpleName }}",
        )
    }

    @Test
    fun `binary cost channels should retain the linear companion beside difference propagation`() {
        val source = Problem(
            0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)),
            listOf(
                Linear(longArrayOf(2, 3, 1), intArrayOf(0, 1, 2), LinearOp.GE, 4),
                Linear(longArrayOf(1, -1), intArrayOf(1, 2), LinearOp.LE, 0),
            ),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(3, 5))
        val config = PresolveConfig(overrides = PresolvePass.entries.associateWith {
            it == PresolvePass.SUBSTITUTE_BINARY_COLUMNS || it == PresolvePass.POST_DIFFERENCE_SYSTEM
        })
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(source, FiniteEngine.MIXED, objective, config))
        val model = prepared.executableProblem()
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AGGRESSIVE, prepared.objective)

        val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP), facts)

        assertTrue(model.factors.any { it is DifferenceSystem })
        assertTrue(arms.any { it.label == "lp-aggressive" })
        assertTrue(arms.any { it.label == "core-guided" })
    }

    @Test
    fun `Boolean source costs should not change integer linear recipe admission`() {
        val model = Problem(
            2, 1, arrayOf(IntDomain(0, 1)),
            listOf(Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), 1, 2)),
        ).bake()
        val objective = LinearObjective(longArrayOf(1, 2))

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AGGRESSIVE, objective)

        assertFalse(facts.integerLinear)
    }

    @Test
    fun `binary objective views should retain the packing LP recipe`() {
        val source = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(2, 3, 4), intArrayOf(0, 1, 2), LinearOp.LE, 5)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-4, -5, -6))
        val config = PresolveConfig(overrides = PresolvePass.entries.associateWith {
            it == PresolvePass.SUBSTITUTE_BINARY_COLUMNS
        })
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(source, FiniteEngine.MIXED, objective, config))
        val model = prepared.executableProblem()
        val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AGGRESSIVE, prepared.objective)

        val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP), facts)

        assertTrue(facts.binaryPackingObjective)
        assertTrue(arms.any { it.label == "lp-default" })
    }

    @Test
    fun `changed Boolean view costs should discard the source packing recipe`() {
        val source = Problem(
            0, 3, Array(3) { IntDomain(0, 1) },
            listOf(Linear(longArrayOf(2, 3, 4), intArrayOf(0, 1, 2), LinearOp.LE, 5)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-4, -5, -6))
        val config = PresolveConfig(overrides = PresolvePass.entries.associateWith {
            it == PresolvePass.SUBSTITUTE_BINARY_COLUMNS
        })
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(source, FiniteEngine.MIXED, objective, config))
        val model = prepared.executableProblem()
        val originalView = checkNotNull(prepared.objective)
        val changed = originalView.copy(boolWeights = LongArray(model.numBoolVars) { 1L })

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AGGRESSIVE, changed)

        assertFalse(facts.binaryPackingObjective)
    }

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
    fun `integer linear recipes should recognize ordinary and exact row views`() {
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val rows = listOf(
            Linear(longArrayOf(1, -2), intArrayOf(0, 1), LinearOp.LE, -1),
            ExactLinear.of(
                refs, listOf(BigFraction.ONE, BigFraction.ofLong(-2)), LinearOp.LE, BigFraction.ofLong(-1),
            ),
            ExactLinear.of(
                refs, listOf(BigFraction.ofLong(2).reciprocal(), BigFraction.ofLong(-1)),
                LinearOp.LE, BigFraction.ofLong(-2).reciprocal(),
            ),
        )
        for ((row, symmetry) in rows.flatMap { row -> listOf(row to false, row to true) }) {
            val factors = if (symmetry) {
                listOf(row, SymmetryHandling(listOf(intArrayOf(0, 1, 3, 2) to intArrayOf())))
            } else listOf(row)
            val model = Problem(0, 4, Array(4) { IntDomain(0, 1) }, factors).bake()
            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO)

            val arms = PortfolioComposition.compose(PortfolioScenario.sequential(Kind.COP), facts)

            assertTrue(arms.any { it.label == "lp-aggressive" })
        }
    }

    @Test
    fun `symmetry alone should not admit an integer linear recipe`() {
        val model = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(SymmetryHandling(listOf(intArrayOf(1, 0) to intArrayOf()))),
        ).bake()

        val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AGGRESSIVE)

        assertFalse(facts.integerLinear)
    }

    @Test
    fun `integer linear recipes should exclude conditional real and wide rows`() {
        val refs = intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1))
        val wide = BigFraction.of(parseBigInt("18446744073709551617"), BIG_ONE)
        val rows = listOf(
            ExactLinear.of(
                refs, listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.LE, BigFraction.ONE, activator = 0,
            ),
            ExactLinear.of(
                intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.LE, BigFraction.ONE,
            ),
            ExactLinear.of(refs, listOf(wide, BigFraction.ONE), LinearOp.LE, BigFraction.ONE),
        )
        for (row in rows) {
            val model = Problem(
                1, 2, Array(2) { IntDomain(0, 1) }, listOf(row),
                numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0),
            ).bake()

            val facts = ProblemFacts.of(model, ProblemProfile.of(model, true), LpConfig.AUTO)

            assertFalse(facts.integerLinear)
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
