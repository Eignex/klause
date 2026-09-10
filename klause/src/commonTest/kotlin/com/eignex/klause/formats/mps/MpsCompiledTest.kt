package com.eignex.klause.formats.mps

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsCompiledTest {

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
    fun `a fractional row lowers onto the least common denominator of its decimals`() {
        val row = lower(twoFinite, MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(0.5, 0.125), null, 1.0))

        val constants = assertNotNull(row.integerConstants)
        assertEquals(listOf(500L, 125L), row.vars.indices.map { constants.coeff(it) })
        assertEquals(1000L, constants.bound)
    }

    @Test
    fun `a row touching a continuous column lowers onto the denominator of its decimals`() {
        val vars = listOf(
            MpsVar("x", integer = false, lower = 0.0, upper = 100.0),
            MpsVar("y", integer = true, lower = 0.0, upper = 10.0),
        )
        val row = lower(vars, MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(0.9, 1.0), 54.0, 54.0))

        val constants = assertNotNull(row.realConstants)
        assertEquals(9.0, constants.realCoefficients.at(0), "the real term is restated in whole numbers")
        assertEquals(10.0, constants.intCoefficients.at(0), "the integer term scales with it")
        assertEquals(540.0, constants.bound, "and so does the bound")
    }

    @Test
    fun `a coefficient finer than a millionth keeps its term in the row`() {
        val row = lower(twoFinite, MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(1e-7, 1.0), null, 1.0))

        val constants = assertNotNull(row.integerConstants)
        assertEquals(listOf(1L, 10_000_000L), row.vars.indices.map { constants.coeff(it) })
    }

    @Test
    fun `an underflowing bounded objective term carries its maximum error`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("cost", intArrayOf(0, 1), doubleArrayOf(1e15, 0.25), 0.0),
            listOf(
                MpsVar("x", integer = true, lower = 0.0, upper = 1.0),
                MpsVar("y", integer = true, lower = -2.0, upper = 3.0),
            ),
            emptyList(),
        ).toProblem()

        assertEquals(0.75, compiled.objectiveErrorBound)
    }

    @Test
    fun `an underflowing objective term on an unbounded column is rejected`() {
        val error = assertFailsWith<MpsLoweringException> {
            MpsModel(
                "m",
                ObjectiveSense.MINIMIZE,
                MpsObjective("cost", intArrayOf(0, 1), doubleArrayOf(1e15, 0.25), 0.0),
                listOf(
                    MpsVar("x", integer = true, lower = 0.0, upper = 1.0),
                    MpsVar("y", integer = true, lower = 0.0, upper = null),
                ),
                emptyList(),
            ).toProblem()
        }

        assertTrue("unbounded column 'y'" in error.message.orEmpty())
    }

    @Test
    fun `an underflowing integer constraint is tightened to its inner upper side`() {
        val row = lower(
            twoFinite,
            MpsConstraint("c", intArrayOf(0, 1), doubleArrayOf(1e15, 0.25), null, 1e15),
        )

        val constants = assertNotNull(row.integerConstants)
        assertEquals(listOf(1_000_000_000_000_000L, 0L), row.vars.indices.map { constants.coeff(it) })
        assertEquals(999_999_999_999_997L, constants.bound)
    }

    @Test
    fun `an underflowing indicated integer constraint is qualified`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(
                MpsVar("guard", integer = true, lower = 0.0, upper = 1.0),
                MpsVar("x", integer = true, lower = 0.0, upper = 1.0),
                MpsVar("y", integer = true, lower = 0.0, upper = 1.0),
            ),
            listOf(
                MpsConstraint(
                    "c",
                    intArrayOf(1, 2),
                    doubleArrayOf(1e15, 0.25),
                    null,
                    1e15,
                    MpsIndicator(column = 0, whenOne = true),
                ),
            ),
        ).toProblem()

        assertTrue(compiled.hasInnerConstraintApproximation)
    }

    @Test
    fun `a fractional upper bound on an integer column tightens to the last value it admits`() {
        assertEquals(2L, boundsOf(MpsVar("x", integer = true, lower = 0.0, upper = 2.7)).second)
    }

    @Test
    fun `a fractional lower bound on an integer column tightens to the first value it admits`() {
        assertEquals(3L, boundsOf(MpsVar("x", integer = true, lower = 2.3, upper = 9.0)).first)
    }

    @Test
    fun `parsed exact LP retains wide values and distinct double projections`() {
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

        assertEquals(
            "9007199254740992",
            exact.entries(0).single().number.value.toString(),
        )
        assertEquals(
            "9007199254740993",
            exact.entries(1).single().number.value.toString(),
        )
        assertEquals(
            exact.entries(0).single().number.value.toDouble(),
            exact.entries(1).single().number.value.toDouble(),
        )
        assertFalse(exact.column(0).bounds.fixed)
        assertEquals("1/18014398509481984", exact.column(0).bounds.upper?.number?.value.toString())
        assertEquals("9999999999999999999", exact.column(1).bounds.upper?.number?.value.toString())
        assertNull(exact.toLegacy())
    }

    @Test
    fun `exact MPS infinity classification uses source values`() {
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
    fun `double only exact LP retains signed zero and declines projection`() {
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
    fun `maximized exact LP keeps shifted source objective units`() {
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
    fun `integral parsed exact LP keeps the compatible legacy route`() {
        val text = "ROWS\n N COST\n L C1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 2 C1 1\n" +
            " M1 'MARKER' 'INTEND'\nRHS\n RHS C1 4\nBOUNDS\n UP BND X 3\nENDATA"

        val legacy = assertNotNull(assertNotNull(Mps.parse(text).toProblem().exactLpModel).toLegacy())

        assertEquals(1L, legacy.csc.colVal.single())
        assertEquals(4L, legacy.rhs.single())
        assertEquals(3L, legacy.upper[0])
        assertEquals(2L, legacy.cost[0])
    }

    @Test
    fun `compiled exact LP owns mutable source arrays`() {
        val objectiveIndices = intArrayOf(0)
        val objectiveCoefficients = doubleArrayOf(3.0)
        val rowIndices = intArrayOf(0)
        val rowCoefficients = doubleArrayOf(2.0)
        val compiled = MpsModel(
            "owned",
            ObjectiveSense.MINIMIZE,
            MpsObjective("cost", objectiveIndices, objectiveCoefficients, 1.0),
            listOf(MpsVar("x", integer = false, lower = null, upper = null)),
            listOf(MpsConstraint("c", rowIndices, rowCoefficients, lower = null, upper = 5.0)),
        ).toProblem()

        objectiveIndices[0] = 10
        objectiveCoefficients[0] = 30.0
        rowIndices[0] = 10
        rowCoefficients[0] = 20.0
        val exact = assertNotNull(compiled.exactLpModel)

        assertEquals(BigFraction.ofLong(3L), exact.objective.cost(0).value)
        assertEquals(BigFraction.ofLong(2L), exact.entries(0).single().number.value)
    }

    @Test
    fun `parsed array edits take current IEEE authority`() {
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
    fun `structural edits do not reuse positional parsed authority`() {
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
    fun `compiled parsed snapshot is independent of later edits`() {
        val parsed = Mps.parse(
            "ROWS\n N COST\n L C1\nCOLUMNS\n X COST 3 C1 2\nRHS\n RHS C1 5\nENDATA",
        )
        val compiled = parsed.toProblem()

        parsed.objective.coeffs[0] = 7.0
        parsed.constraints[0].coeffs[0] = 9.0
        val exact = assertNotNull(compiled.exactLpModel)

        assertEquals(BigFraction.ofLong(3L), exact.objective.cost(0).value)
        assertEquals(BigFraction.ofLong(2L), exact.entries(0).single().number.value)
    }

    @Test
    fun `programmatic infinite sides retain open bound conventions`() {
        val source = MpsModel(
            "open",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(
                MpsVar("x", integer = false, lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY),
                MpsVar("y", integer = false, lower = Double.NEGATIVE_INFINITY, upper = 4.0),
                MpsVar("z", integer = false, lower = 2.0, upper = Double.POSITIVE_INFINITY),
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
                MpsConstraint("lower-row", intArrayOf(2), doubleArrayOf(1.0), 3.0, Double.POSITIVE_INFINITY),
            ),
        )

        val compiled = source.copy(name = "open-copy").toProblem()
        val exact = assertNotNull(compiled.exactLpModel)

        assertEquals(Double.NEGATIVE_INFINITY, compiled.model.realLower[0])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[0])
        assertEquals(Double.NEGATIVE_INFINITY, compiled.model.realLower[1])
        assertEquals(4.0, compiled.model.realUpper[1])
        assertEquals(2.0, compiled.model.realLower[2])
        assertEquals(Double.POSITIVE_INFINITY, compiled.model.realUpper[2])
        assertNull(exact.column(0).bounds.lower)
        assertNull(exact.column(0).bounds.upper)
        assertNull(exact.column(1).bounds.lower)
        assertEquals(BigFraction.ofLong(4L), assertNotNull(exact.column(1).bounds.upper).number.value)
        assertEquals(BigFraction.ofLong(2L), assertNotNull(exact.column(2).bounds.lower).number.value)
        assertNull(exact.column(2).bounds.upper)
        assertEquals(2, exact.m)
    }

    @Test
    fun `programmatic NaN inputs remain rejected`() {
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
    fun `parsed and compiled copies retain exact authority`() {
        val text = "ROWS\n N COST\nCOLUMNS\n X COST 9007199254740993\nENDATA"
        val parsed = Mps.parse(text)

        val parsedCopy = parsed.copy(name = "copy")
        val compiledCopy = parsed.toProblem().copy()

        assertEquals(
            "9007199254740993",
            parsedCopy.toProblem().exactLpModel?.objective?.cost(0)?.value.toString(),
        )
        assertEquals(
            "9007199254740993",
            compiledCopy.exactLpModel?.objective?.cost(0)?.value.toString(),
        )
    }

    @Test
    fun `compiled value equality retains nullable Double semantics`() {
        val source = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA").toProblem()
        val positiveZero = source.copy(objectiveErrorBound = 0.0)
        val negativeZero = source.copy(objectiveErrorBound = -0.0)
        val firstNaN = source.copy(objectiveErrorBound = Double.NaN)
        val secondNaN = source.copy(objectiveErrorBound = Double.NaN)

        assertNotEquals(positiveZero, negativeZero)
        assertEquals(firstNaN, secondNaN)
        assertEquals(firstNaN.hashCode(), secondNaN.hashCode())
    }

    @Test
    fun `numeric model copy edits take IEEE authority`() {
        val parsed = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 9007199254740993\nENDATA")
        val edited = parsed.copy(
            objective = parsed.objective.copy(coeffs = parsed.objective.coeffs.copyOf()),
        )

        val number = assertNotNull(edited.toProblem().exactLpModel).objective.cost(0)

        assertEquals(9007199254740992.0.toRawBits(), number.ieeeBits)
    }

    @Test
    fun `conditional exact rows retain equality bound provenance`() {
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
