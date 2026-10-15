package com.eignex.klause.formats.mps

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsCompilerSourceTest {
    @Test
    fun `the source witness entry limit should admit a complete checked source`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 1\nBOUNDS\n LO B X 0\nENDATA",
        ).toExactSourceProblem()

        val witness = compiled.sourceWitnessOrNull(LongArray(0), listOf(BigFraction.ONE), maxEntries = 4)

        assertEquals(BigFraction.ONE, assertNotNull(witness).objective)
    }

    @Test
    fun `the source witness entry limit should decline an oversized source`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 1\nBOUNDS\n LO B X 0\nENDATA",
        ).toExactSourceProblem()

        val witness = compiled.sourceWitnessOrNull(LongArray(0), listOf(BigFraction.ONE), maxEntries = 3)

        assertNull(witness)
    }

    @Test
    fun `a bounded source witness check should reject a violated source row`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 1\nBOUNDS\n LO B X 0\nENDATA",
        ).toExactSourceProblem()

        val witness = compiled.sourceWitnessOrNull(LongArray(0), listOf(BigFraction.ZERO), maxEntries = 4)

        assertNull(witness)
    }

    @Test
    fun `source coordinates should retain integer values beyond Long`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n" +
                " M1 'MARKER' 'INTEND'\nBOUNDS\n FR B I\nENDATA",
        ).toProblem()
        val value = BigFraction.of(parseBigInt("10000000000000000000"), BIG_ONE)

        val witness = compiled.sourceWitness(listOf(value))

        assertEquals("10000000000000000000", witness.objective.toString())
        assertEquals(listOf(value), witness.values)
    }

    @Test
    fun `source coordinates should require exact integer integrality`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n I COST 1\n" +
                " M1 'MARKER' 'INTEND'\nBOUNDS\n FR B I\nENDATA",
        ).toProblem()

        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.ofLong(2L).reciprocal()))
        }
    }

    @Test
    fun `source coordinates should cover every declared column`() {
        val compiled = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA").toProblem()

        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(emptyList())
        }
    }

    @Test
    fun `a free continuous integer definition should preserve source values and objective`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -3\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 1 DEF 1\nRHS\n RHS DEF 2\n" +
                "BOUNDS\n BV B X\n FR B Z\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(longArrayOf(1L, 5L), null)

        assertEquals(0, compiled.model.numRealVars)
        assertEquals(2L, compiled.model.intBounds.lower(1))
        assertEquals(5L, compiled.model.intBounds.upper(1))
        assertTrue(compiled.sourceExact)
        assertEquals("5", witness.objective.toString())
        assertEquals(listOf("1", "5"), witness.values.map { it.toString() })
    }

    @Test
    fun `a fractional continuous definition should retain its continuous values`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -1\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 1 DEF 2\nBOUNDS\n BV B X\n FR B Z\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(longArrayOf(1L), listOf(BigFraction.ofLong(2L).reciprocal()))

        assertEquals(1, compiled.columns.count { it.real })
        assertEquals("1/2", witness.objective.toString())
    }

    @Test
    fun `a guarded continuous definition should not restrict an inactive column`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -3\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 1 DEF 1\nBOUNDS\n BV B X\n FR B Z\n" +
                "INDICATORS\n IF DEF X 1\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(longArrayOf(0L), listOf(BigFraction.ofLong(2L).reciprocal()))

        assertEquals(1, compiled.columns.count { it.real })
        assertEquals("1/2", witness.objective.toString())
    }

    @Test
    fun `definition integrality should use the original decimal coefficients`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -1\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 1 DEF 1.0000000000000001\n" +
                "BOUNDS\n BV B X\n FR B Z\nENDATA",
        ).toProblem()

        assertEquals(1, compiled.columns.count { it.real })
    }

    @Test
    fun `an integer definition should use exact source rows beyond binary64 integer precision`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -10000000000000001\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 1 DEF 1\nBOUNDS\n BV B X\n FR B Z\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(longArrayOf(1L, 10_000_000_000_000_001L), null)

        assertEquals(0, compiled.model.numRealVars)
        assertTrue(compiled.sourceExact)
        assertEquals("10000000000000001", witness.objective.toString())
    }

    @Test
    fun `an integer definition should retain continuous source decimal precision`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -2.0000000000000002\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 1 DEF 1.0000000000000001\nBOUNDS\n BV B X\n FR B Z\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(longArrayOf(1L), listOf(BigFraction.ofLong(2L)))

        assertEquals(1, compiled.columns.count { it.real })
        assertEquals("2", witness.objective.toString())
    }

    @Test
    fun `an integer definition should retain continuous source objective precision`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -3\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 10000000000000001 DEF 1\nBOUNDS\n BV B X\n FR B Z\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(longArrayOf(1L), listOf(BigFraction.ofLong(3L)))

        assertEquals(1, compiled.columns.count { it.real })
        assertEquals("30000000000000003", witness.objective.toString())
    }

    @Test
    fun `an infinity marker should not define a continuous column`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E DEF\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X DEF -1e30\n" +
                " M1 'MARKER' 'INTEND'\n Z COST 1 DEF 1e30\nRHS\n RHS DEF 1e30\n" +
                "BOUNDS\n BV B X\n FR B Z\nENDATA",
        ).toProblem()

        assertEquals(1, compiled.columns.count { it.real })
    }

    @Test
    fun `the source check should reject a marker integer above its implicit binary bound`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 1\n" +
                " M1 'MARKER' 'INTEND'\nENDATA",
        ).toProblem()

        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(longArrayOf(2L), null)
        }
    }

    @Test
    fun `the source check should accept an exact rational continuous optimum`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 3\nRHS\n RHS R 1\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(LongArray(0), listOf(BigFraction.ofLong(3L).reciprocal()))

        assertTrue(compiled.sourceExact)
        assertTrue(compiled.copy().sourceExact)
        assertFalse(compiled.copy(objectiveScale = 2L).sourceExact)
        assertEquals("1/3", witness.values.single().toString())
        assertEquals("1/3", witness.objective.toString())
    }

    @Test
    fun `the source check should reject a point beyond tolerance of a rounded original row`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 3.0000000000000001\nRHS\n RHS R 1\nENDATA",
        ).toProblem()

        assertFalse(compiled.sourceExact)
        assertEquals("row 'R' coefficient", compiled.sourceDifference)
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(LongArray(0), listOf(BigFraction.ofLong(2L).reciprocal()))
        }
    }

    @Test
    fun `the source check should keep the exact objective when a decimal cost rounds`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n E R\nCOLUMNS\n X COST 0.10000000000000000001 R 3\nRHS\n RHS R 1\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(LongArray(0), listOf(BigFraction.ofLong(3L).reciprocal()))

        assertFalse(compiled.sourceExact)
        assertEquals("objective coefficient 'X'", compiled.sourceDifference)
        assertEquals("10000000000000000001/300000000000000000000", witness.objective.toString())
    }

    @Test
    fun `the source check should reject an integer lower bound beyond long range`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(MpsVar("X", integer = true, lower = -1e19, upper = 0.0)),
            emptyList(),
        ).toProblem()

        assertTrue(compiled.sourceExact)
        assertNull(compiled.sourceDifference)
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.of(parseBigInt("-10000000000000000001"), BIG_ONE)))
        }
    }

    @Test
    fun `the source check should reject an integer upper bound beyond long range`() {
        val compiled = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("", IntArray(0), DoubleArray(0), 0.0),
            listOf(MpsVar("X", integer = true, lower = 0.0, upper = 1e19)),
            emptyList(),
        ).toProblem()

        assertTrue(compiled.sourceExact)
        assertNull(compiled.sourceDifference)
        assertFailsWith<MpsLoweringException> {
            compiled.sourceWitness(listOf(BigFraction.of(parseBigInt("10000000000000000001"), BIG_ONE)))
        }
    }

    @Test
    fun `a mixed objective should retain exact decimal source units`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\nCOLUMNS\n MARK0 'MARKER' 'INTORG'\n X COST 1\n" +
                " MARK1 'MARKER' 'INTEND'\n Y COST 0.333333333333333\n" +
                "BOUNDS\n UP BND X 10000\n UP BND Y 1\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(longArrayOf(1), listOf(BigFraction.ONE))

        assertEquals(1L, compiled.objectiveScale)
        assertTrue(compiled.sourceExact)
        assertEquals("1333333333333333/1000000000000000", witness.objective.toString())
    }

    @Test
    fun `the source objective should keep maximize sense and the negated objective RHS`() {
        val compiled = Mps.parse(
            "OBJSENSE\n MAX\nROWS\n N COST\n E R\nCOLUMNS\n X COST 0.5 R 1\n" +
                "RHS\n RHS R 2 COST 3\nENDATA",
        ).toProblem()

        val witness = compiled.sourceWitness(LongArray(0), listOf(BigFraction.ofLong(2L)))

        assertTrue(compiled.maximize)
        assertTrue(compiled.sourceExact)
        assertEquals("-2", witness.objective.toString())
    }

    @Test
    fun `the tolerance check should admit a row violation only below the MPS tolerance`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 1\nBOUNDS\n UP BND X 10\nENDATA",
        ).toProblem()

        for ((value, admitted) in listOf(1.0 - 1e-9 to true, 1.0 - 1e-3 to false, 1.0 to true)) {
            assertEquals(admitted, compiled.withinTolerance(LongArray(0), doubleArrayOf(value)), "$value")
        }
    }

    @Test
    fun `the tolerance check should judge a row whose binary64 terms overflow by its exact activity`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n X COST 1 R 1e308\n Y COST 1 R -1e308\nRHS\n RHS R 0\nENDATA",
        ).toProblem()

        val points = listOf(
            doubleArrayOf(10.0, 9.0) to false,
            doubleArrayOf(10.0, 10.0) to true,
            doubleArrayOf(9.0, 10.0) to true,
        )
        for ((point, admitted) in points) {
            assertEquals(admitted, compiled.withinTolerance(LongArray(0), point), point.toList().toString())
        }
    }

    @Test
    fun `the tolerance check should read a coefficient past binary64 range at its exact value`() {
        // The two entries sum to 2e308, which no binary64 holds.
        val compiled = Mps.parse(
            "ROWS\n N COST\n L R\nCOLUMNS\n X COST 1 R 1e308\n X R 1e308\nRHS\n RHS R 1\nENDATA",
        ).toProblem()

        for ((value, admitted) in listOf(0.0 to true, 1.0 to false)) {
            assertEquals(admitted, compiled.withinTolerance(LongArray(0), doubleArrayOf(value)), "$value")
        }
    }

    @Test
    fun `the source objective of a float point should be exact in its doubles`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n Y COST 1e5 R 1\n X COST 99999.9999999701976776123046875 R 1\n" +
                "RHS\n RHS R 1\nBOUNDS\n FR BND Y\nENDATA",
        ).toProblem()
        val y = -999_999_999_999.0
        val x = 1e12

        val objective = compiled.sourceObjective(LongArray(0), doubleArrayOf(y, x))

        val expected = BigFraction.ofLong(100_000L) * BigFraction.ofLong(-999_999_999_999L) +
            BigFraction.ofDouble(99999.9999999701976776123046875)!! * BigFraction.ofLong(1_000_000_000_000L)
        assertEquals(expected, objective)
    }
}
