package com.eignex.klause.formats.mps

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `a parsed exact LP should retain wide values and distinct double projections`() {
        val text = """
            ROWS
             N COST
             E C1
            COLUMNS
                MARK0 'MARKER' 'INTORG'
                X COST 1 C1 9007199254740992
                Y COST 2 C1 9007199254740993
                MARK1 'MARKER' 'INTEND'
            RHS
                RHS C1 9999999999999999999
            BOUNDS
             LO BND X 1
             UP BND X 1.000000000000000055511151231257827021181583404541015625
             UP BND Y 9999999999999999999
            ENDATA
        """.trimIndent()

        val exact = assertNotNull(Mps.parse(text).toProblem().exactLpModel)

        val x = exact.entries(0).single().number.value
        val y = exact.entries(1).single().number.value
        assertEquals("9007199254740992", x.toString())
        assertEquals("9007199254740993", y.toString())
        assertEquals(x.toDouble(), y.toDouble())
        assertFalse(exact.column(0).bounds.fixed)
        assertEquals("1/18014398509481984", exact.column(0).bounds.upper?.number?.value.toString())
        assertEquals("9999999999999999999", exact.column(1).bounds.upper?.number?.value.toString())
        assertNull(exact.toLegacy())
    }

    @Test
    fun `exact MPS infinity classification should use source values`() {
        val text = """
            ROWS
             L C1
            COLUMNS
                X C1 1
                Y C1 0
            RHS
                RHS C1 99999999999999999999
            BOUNDS
             UP BND X 99999999999999999999
             UP BND Y 100000000000000000000
            ENDATA
        """.trimIndent()

        val exact = assertNotNull(Mps.parse(text).toProblem().exactLpModel)

        assertEquals("99999999999999999999", exact.column(0).bounds.upper?.number?.value.toString())
        assertEquals("99999999999999999999", exact.rhs(0).value.toString())
        assertNull(exact.column(1).bounds.upper)
    }

    @Test
    fun `a double only exact LP should retain signed zero and decline projection`() {
        val source = MpsModel(
            "binary",
            ObjectiveSense.MINIMIZE,
            MpsObjective("cost", intArrayOf(0), doubleArrayOf(1.0), -0.0),
            listOf(MpsVar("x", integer = false, lower = null, upper = -0.0)),
            emptyList(),
        )

        val exact = assertNotNull(source.toProblem().exactLpModel)

        assertEquals((-0.0).toRawBits(), exact.column(0).bounds.upper?.number?.ieeeBits)
        assertEquals((-0.0).toRawBits(), exact.objective.constant.ieeeBits)
        assertNull(exact.toLegacy())
    }

    @Test
    fun `a maximized exact LP should keep shifted source objective units`() {
        val text = """
            OBJSENSE
             MAX
            ROWS
             N COST
            COLUMNS
                MARK0 'MARKER' 'INTORG'
                X COST 3
                MARK1 'MARKER' 'INTEND'
            RHS
                RHS COST 7
            BOUNDS
             LO BND X 2
             UP BND X 8
            ENDATA
        """.trimIndent()

        val exact = assertNotNull(Mps.parse(text).toProblem().exactLpModel)

        assertEquals(Sense.MAXIMIZE, exact.objective.sense)
        assertEquals(BigFraction.ofLong(-3L), exact.objective.cost(0).value)
        assertEquals(BigFraction.ONE, exact.objective.constant.value)
        assertEquals(BigFraction.ofLong(5L), exact.objective.sourceValue(listOf(BigFraction.ofLong(2L))))
    }

    @Test
    fun `an integral parsed exact LP should keep the compatible legacy route`() {
        val text = "ROWS\n N COST\n L C1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 2 C1 1\n" +
            " M1 'MARKER' 'INTEND'\nRHS\n RHS C1 4\nBOUNDS\n UP BND X 3\nENDATA"

        val legacy = assertNotNull(assertNotNull(Mps.parse(text).toProblem().exactLpModel).toLegacy())

        assertEquals(1L, legacy.csc.colVal.single())
        assertEquals(4L, legacy.rhs.single())
        assertEquals(3L, legacy.upper[0])
        assertEquals(2L, legacy.cost[0])
    }

    @Test
    fun `parsed array edits should take current IEEE authority`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n L C1\nCOLUMNS\n X COST 3 C1 2\nRHS\n RHS C1 5\nENDATA",
        )
        parsed.objective.coeffs[0] = 7.0
        parsed.constraints[0].coeffs[0] = 9.0

        val compiled = parsed.copy(name = "edited").toProblem()
        val exact = assertNotNull(compiled.exactLpModel)

        assertEquals(7.0.toRawBits(), exact.objective.cost(0).ieeeBits)
        assertEquals(9.0.toRawBits(), exact.entries(0).single().number.ieeeBits)
        assertEquals(7.0, assertNotNull(compiled.objective).realCoefficients.single())
        val legacyRow = compiled.model.factors.single() as Linear
        assertEquals(9.0, assertNotNull(legacyRow.realConstants).realCoefficients.at(0))
    }

    @Test
    fun `structural edits should not reuse positional parsed authority`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n L C1\n L C2\nCOLUMNS\n" +
                " X COST 9007199254740993 C1 1\n Y C2 1\n" +
                "RHS\n RHS C1 9007199254740993 C2 3\n" +
                "BOUNDS\n UP BND X 9007199254740993\nENDATA",
        )
        parsed.objective.indices[0] = 1

        val exact = parsed.copy(
            variables = parsed.variables.reversed(),
            constraints = parsed.constraints.reversed(),
        ).toExactLpModel()

        assertEquals("9007199254740993", exact.objective.cost(1).value.toString())
        assertEquals("9007199254740993", assertNotNull(exact.column(1).bounds.upper).number.value.toString())
        assertEquals(BigFraction.ofLong(3L), exact.rhs(0).value)
        assertEquals("9007199254740993", exact.rhs(1).value.toString())
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
                MpsConstraint(
                    "open-row",
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    Double.NEGATIVE_INFINITY,
                    Double.POSITIVE_INFINITY,
                ),
                MpsConstraint("upper-row", intArrayOf(1), doubleArrayOf(1.0), Double.NEGATIVE_INFINITY, 7.0),
            ),
        )

        val compiled = source.toProblem()
        val exact = assertNotNull(compiled.exactLpModel)

        assertEquals(Double.NEGATIVE_INFINITY, compiled.model.realLower[0])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[0])
        assertEquals(2.0, compiled.model.realLower[1])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[1])
        assertNull(exact.column(0).bounds.lower)
        assertNull(exact.column(0).bounds.upper)
        assertEquals(BigFraction.ofLong(2L), assertNotNull(exact.column(1).bounds.lower).number.value)
        assertNull(exact.column(1).bounds.upper)
        assertEquals(1, exact.m)
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
    fun `conditional exact rows should retain equality bound provenance`() {
        val source = """
            ROWS
             L C0
             L C1
            COLUMNS
                M0 'MARKER' 'INTORG'
                B C0 0 C1 0
                X C0 1 C1 1
                M1 'MARKER' 'INTEND'
            RHS
                RHS C0 3 C1 3
            BOUNDS
             UP BND B 2
             UP BND X 5
            INDICATORS
             IF C0 B 0
             IF C1 B 1
            ENDATA
        """.trimIndent()

        val exact = assertNotNull(Mps.parse(source).toProblem().exactLpModel)
        val legacy = assertNotNull(exact.toLegacy())

        assertTrue((0 until exact.m).all { !exact.row(it).global && exact.row(it).premises != null })
        assertTrue(legacy.rowGlobal.none { it })
        for ((row, trigger) in listOf(0L, 1L).withIndex()) {
            val premise = assertNotNull(legacy.rowPremises[row])
            assertEquals(listOf(0, 0), premise.vars.toList())
            assertEquals(listOf(false, true), premise.isUpper.toList())
            assertEquals(listOf(trigger, trigger), premise.thresholds.toList())
        }
    }
}
