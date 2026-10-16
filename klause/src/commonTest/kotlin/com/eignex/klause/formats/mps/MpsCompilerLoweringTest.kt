package com.eignex.klause.formats.mps

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MpsCompilerLoweringTest {
    private fun lower(vars: List<MpsVar>, row: MpsConstraint): Linear {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            vars,
            listOf(row),
        ).toProblem()
        return compiled.model.factors.single() as Linear
    }

    private fun boundsOf(v: MpsVar): Pair<Long, Long> {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(v),
            emptyList(),
        ).toProblem()
        return compiled.model.intBounds.lower(0) to compiled.model.intBounds.upper(0)
    }

    private val twoFinite = listOf(
        MpsVar("x", integer = true, lower = 0.0, upper = 10.0),
        MpsVar("y", integer = true, lower = 0.0, upper = 10.0),
    )

    @Test
    fun `a fractional row should lower onto the least common denominator of its decimals`() {
        val row = lower(twoFinite, MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(0.5, 0.125), null, 1.0))

        val constants = assertNotNull(row.integerConstants)
        assertEquals(listOf(500L, 125L), row.vars.indices.map { constants.coeff(it) })
        assertEquals(1000L, constants.bound)
    }

    @Test
    fun `a row touching a continuous column should lower onto the denominator of its decimals`() {
        val compiled = Mps.parse(
            "ROWS\n E C\nCOLUMNS\n X C 0.9\n M0 'MARKER' 'INTORG'\n Y C 1\n" +
                " M1 'MARKER' 'INTEND'\nRHS\n R C 54\nBOUNDS\n UP B X 100\n UI B Y 10\nENDATA",
        ).toProblem()
        val row = compiled.model.factors.single() as Linear

        val constants = assertNotNull(row.realConstants)
        assertEquals(9.0, constants.realCoefficients.at(0))
        assertEquals(10.0, constants.intCoefficients.at(0))
        assertEquals(540.0, constants.bound)
    }

    @Test
    fun `an integer row too wide for one scale should keep every term`() {
        val row = lower(
            twoFinite,
            MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(1e15, 0.25), null, 1e15),
        )

        val constants = assertNotNull(row.integerConstants)
        assertEquals(listOf(4_000_000_000_000_000L, 1L), row.vars.indices.map { constants.coeff(it) })
        assertEquals(4_000_000_000_000_000L, constants.bound)
    }

    @Test
    fun `an integer row with more decimals than a double carries should lower onto its exact decimals`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M1 'MARKER' 'INTORG'\n X R 1\n Y R 0.12345678901234567\n" +
                " M2 'MARKER' 'INTEND'\nRHS\n RHS R 1\nBOUNDS\n UP BND X 1\n UP BND Y 1\nENDATA",
        ).toProblem()

        val row = compiled.model.factors.single() as Linear
        val constants = assertNotNull(row.integerConstants)
        val coefficients = row.vars.indices.map { constants.coeff(it) }
        assertEquals(listOf(100_000_000_000_000_000L, 12_345_678_901_234_567L), coefficients)
        assertEquals(100_000_000_000_000_000L, constants.bound)
        assertTrue(compiled.sourceExact, compiled.sourceDifference)
    }

    @Test
    fun `an integer row too wide for long coefficients should lower onto exact wide ones`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n M1 'MARKER' 'INTORG'\n X R 1e18\n Y R 0.001\n M2 'MARKER' 'INTEND'\n" +
                "RHS\n RHS R 1e18\nBOUNDS\n UP BND X 1\n UP BND Y 1\nENDATA",
        ).toProblem()

        val constants = assertNotNull((compiled.model.factors.single() as Linear).wideConstants)
        val coefficients = (0 until constants.coefficients.size).map { constants.coefficients.at(it).toString() }
        assertEquals(listOf("1000000000000000000000", "1"), coefficients)
        assertEquals("1000000000000000000000", constants.bound.toString())
        assertTrue(compiled.sourceExact, compiled.sourceDifference)
    }

    @Test
    fun `a fractional upper bound on an integer column should tighten to the last value it admits`() {
        assertEquals(2L, boundsOf(MpsVar("x", integer = true, lower = 0.0, upper = 2.7)).second)
    }

    @Test
    fun `a fractional lower bound on an integer column should tighten to the first value it admits`() {
        assertEquals(3L, boundsOf(MpsVar("x", integer = true, lower = 2.3, upper = 9.0)).first)
    }

    @Test
    fun `source rows should retain wide values with indistinguishable double projections`() {
        val text = "ROWS\n N COST\n E C1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
            " X COST 1 C1 9007199254740992\n Y COST 2 C1 9007199254740993\n M1 'MARKER' 'INTEND'\n" +
            "RHS\n RHS C1 9999999999999999999\nBOUNDS\n LO B X 1\n" +
            " UP B X 1.000000000000000055511151231257827021181583404541015625\n" +
            " UP B Y 9999999999999999999\nENDATA"
        val source = Mps.parse(text)

        val compiled = source.toExactSourceProblem()

        val rows = compiled.model.factors.flatMap { it.linearRows }
        val row = rows.single { it.size == 2 }
        val constants = row.constants as IntegralConstants
        val coefficients = (0 until row.size).map {
            BigFraction.of(constants.exactCoeff(it), BIG_ONE)
        }
        assertEquals(listOf("9007199254740992", "9007199254740993"), coefficients.map { it.toString() })
        assertEquals(coefficients[0].toDouble(), coefficients[1].toDouble())
        assertEquals("9999999999999999999", constants.exactBound.toString())
        val bounds = source.sourceNumbers().variableBounds
        assertEquals(BigFraction.of(BIG_ONE, parseBigInt("18014398509481984")),
            checkNotNull(bounds[0].second).fraction - checkNotNull(bounds[0].first).fraction)
        assertEquals("9999999999999999999", bounds[1].second?.fraction.toString())
        assertTrue(compiled.model.intBounds.isOpenUpper(1))
        val wideUpper = rows.single {
            it.size == 1 && it.ref(0) == Term.ofIntVar(1) && it.relation == LinearOp.LE &&
                (it.constants as IntegralConstants).exactCoeff(0) == BIG_ONE
        }
        assertEquals("9999999999999999999", (wideUpper.constants as IntegralConstants).exactBound.toString())
    }

    @Test
    fun `exact MPS infinity classification should use source values`() {
        val source = Mps.parse(
            "ROWS\n L C1\nCOLUMNS\n X C1 1\n Y C1 0\nRHS\n RHS C1 99999999999999999999\n" +
                "BOUNDS\n UP B X 99999999999999999999\n UP B Y 100000000000000000000\nENDATA",
        )
        val edge = BigFraction.of(parseBigInt("99999999999999999999"), BIG_ONE)
        val compiled = source.toExactSourceProblem()

        compiled.sourceWitness(listOf(edge, edge + BigFraction.ofLong(2)))

        val upperRows = compiled.model.factors.flatMap { it.linearRows }
            .filter { it.relation == LinearOp.LE && (it.constants as IntegralConstants).exactBound == edge.num }
        assertEquals(2, upperRows.size)
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(edge + BigFraction.ONE, BigFraction.ZERO))
        }
    }

    @Test
    fun `programmatic signed zero should retain its bound and objective literals`() {
        val source = MpsModel(
            "binary",
            ObjectiveSense.MINIMIZE,
            MpsObjective("cost", intArrayOf(0), doubleArrayOf(1.0), -0.0),
            listOf(MpsVar("x", integer = false, lower = null, upper = -0.0)),
            emptyList(),
        )

        val compiled = source.toProblem()

        assertEquals((-0.0).toRawBits(),
            source.sourceNumbers().variableBounds.single().second?.double?.toRawBits())
        assertEquals((-0.0).toRawBits(), source.sourceNumbers().objectiveConstant.double.toRawBits())
        assertEquals((-0.0).toRawBits(), compiled.model.realUpper.single().toRawBits())
        assertEquals(BigFraction.ZERO, compiled.sourceWitness(listOf(BigFraction.ZERO)).objective)
        assertFalse(compiled.withinTolerance(LongArray(0), doubleArrayOf(1.0)))
    }

    @Test
    fun `maximization should retain original coordinates and objective units`() {
        val source = Mps.parse(
            "OBJSENSE\n MAX\nROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 3\n" +
                " M1 'MARKER' 'INTEND'\nRHS\n RHS COST 7\nBOUNDS\n LO B X 2\n UP B X 8\nENDATA",
        )

        val compiled = source.toProblem()
        val witness = compiled.sourceWitness(listOf(BigFraction.ofLong(4)))

        assertTrue(compiled.maximize)
        assertEquals(2L, compiled.model.intBounds.lower(0))
        assertEquals(8L, compiled.model.intBounds.upper(0))
        val objective = assertNotNull(compiled.objective)
        assertEquals(3L, objective.intCoefficients.single())
        assertEquals(-7L, objective.constant)
        assertEquals(BigFraction.ofLong(5), witness.objective)
    }

    @Test
    fun `integral source rows bounds and objectives should remain in neutral IR`() {
        val source = Mps.parse(
            "ROWS\n N COST\n L C1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 2 C1 1\n" +
                " M1 'MARKER' 'INTEND'\nRHS\n RHS C1 4\nBOUNDS\n UP B X 3\nENDATA",
        )

        val compiled = source.toProblem()

        val row = compiled.model.factors.single().linearRows.single()
        val constants = row.constants as IntegralConstants
        assertEquals("1", constants.exactCoeff(0).toString())
        assertEquals("4", constants.exactBound.toString())
        assertEquals(3L, compiled.model.intBounds.upper(0))
        assertEquals(2L, assertNotNull(compiled.objective).intCoefficients.single())
        assertTrue(compiled.sourceExact)
    }

    @Test
    fun `parsed array edits should take current IEEE authority`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n L C1\nCOLUMNS\n X COST 3 C1 2\nRHS\n RHS C1 5\nENDATA",
        )
        parsed.objective.coeffs[0] = 7.0
        parsed.constraints[0].coeffs[0] = 9.0

        val compiled = parsed.copy(name = "edited").toProblem()

        assertEquals(7.0, assertNotNull(compiled.objective).realCoefficients.single())
        val row = compiled.model.factors.single() as Linear
        assertEquals(9.0, assertNotNull(row.realConstants).realCoefficients.at(0))
        assertEquals(BigFraction.ofLong(7) * BigFraction.ofLong(2).reciprocal(),
            compiled.sourceWitness(listOf(BigFraction.ofLong(2).reciprocal())).objective)
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ofLong(3).reciprocal() * BigFraction.ofLong(2)))
        }
    }

    @Test
    fun `structural edits should not reuse positional parsed authority`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n L C1\n L C2\nCOLUMNS\n" +
                " X COST 9007199254740993 C1 1\n Y C2 1\n" +
                "RHS\n RHS C1 9007199254740993 C2 3\n" +
                "BOUNDS\n UP B X 9007199254740993\nENDATA",
        )
        parsed.objective.indices[0] = 1
        val source = parsed.copy(
            variables = parsed.variables.reversed(), constraints = parsed.constraints.reversed(),
        )

        val compiled = source.toExactSourceProblem()

        val numbers = source.sourceNumbers()
        assertEquals("9007199254740993", numbers.variableBounds[1].second?.fraction.toString())
        assertEquals(BigFraction.ofLong(3), numbers.constraintBounds[0].second?.fraction)
        assertEquals("9007199254740993", numbers.constraintBounds[1].second?.fraction.toString())
        assertEquals("9007199254740993",
            compiled.sourceWitness(listOf(BigFraction.ZERO, BigFraction.ONE)).objective.toString())
    }

    @Test
    fun `programmatic infinite sides should retain open bound conventions`() {
        val source = MpsModel(
            "open",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(
                MpsVar("x", integer = false, lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY),
                MpsVar("y", integer = false, lower = 2.0, upper = Double.POSITIVE_INFINITY),
            ),
            listOf(
                MpsConstraint("open-row", intArrayOf(0), doubleArrayOf(1.0),
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY),
                MpsConstraint("upper-row", intArrayOf(1), doubleArrayOf(1.0), Double.NEGATIVE_INFINITY, 7.0),
            ),
        )

        val compiled = source.toProblem()

        assertEquals(Double.NEGATIVE_INFINITY, compiled.model.realLower[0])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[0])
        assertEquals(2.0, compiled.model.realLower[1])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[1])
        assertEquals(1, compiled.model.factors.size)
        compiled.sourceWitness(listOf(BigFraction.ofLong(-100), BigFraction.ofLong(2)))
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ZERO, BigFraction.ofLong(8)))
        }
    }

    @Test
    fun `programmatic NaN inputs should be rejected`() {
        val objective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0)
        val invalid = listOf(
            MpsModel(
                "bound",
                ObjectiveSense.MINIMIZE,
                objective,
                listOf(MpsVar("x", integer = false, lower = Double.NaN, upper = null)),
                emptyList(),
            ),
            MpsModel(
                "row",
                ObjectiveSense.MINIMIZE,
                objective,
                listOf(MpsVar("x", integer = false, lower = null, upper = null)),
                listOf(MpsConstraint("c", intArrayOf(0), doubleArrayOf(Double.NaN), null, 0.0)),
            ),
        )

        for (model in invalid) assertFailsWith<IllegalArgumentException> { model.toProblem() }
    }

    @Test
    fun `conditional source rows should retain exact integer triggers`() {
        val source = Mps.parse(
            "ROWS\n L C0\n L C1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                " B C0 0 C1 0\n X C0 1 C1 1\n M1 'MARKER' 'INTEND'\nRHS\n RHS C0 3 C1 3\n" +
                "BOUNDS\n UP B B 2\n UP B X 5\nINDICATORS\n IF C0 B 0\n IF C1 B 1\nENDATA",
        )

        val compiled = source.toProblem()

        val guarded = compiled.model.factors.filterIsInstance<LinearIndicator>()
        assertEquals(listOf(false, true), guarded.map { it.whenOne })
        for (factor in guarded) {
            assertEquals(0, factor.indicator)
            assertEquals(Term.ofIntVar(1), factor.row.ref(0))
            assertEquals("1", factor.row.constants.exactCoeff(0).toString())
            assertEquals("3", factor.row.constants.exactBound.toString())
            assertEquals(LinearOp.LE, factor.row.relation)
        }
        compiled.sourceWitness(listOf(BigFraction.ofLong(2), BigFraction.ofLong(5)))
        for (trigger in listOf(0L, 1L)) {
            assertFailsWith<MpsLoweringException> {
                compiled.sourceWitness(listOf(BigFraction.ofLong(trigger), BigFraction.ofLong(4)))
            }
        }
    }
}
