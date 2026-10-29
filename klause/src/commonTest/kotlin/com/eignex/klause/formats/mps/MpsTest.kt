package com.eignex.klause.formats.mps

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.perCharSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsTest {

    @Test
    fun `parsing should read rows columns rhs and bounds into a model`() {
        val text = """
            NAME          TEST
            ROWS
             N  COST
             L  C1
             G  C2
             E  C3
            COLUMNS
                M1        'MARKER'                 'INTORG'
                X1        COST           1.0   C1             1.0
                X1        C2             1.0
                M2        'MARKER'                 'INTEND'
                X2        COST           2.0   C1             1.0
                X2        C3             1.0
            RHS
                RHS       C1             4.0   C2             1.0
                RHS       C3             2.0
            BOUNDS
             UP BND       X1            10.0
             FR BND       X2
            ENDATA
        """.trimIndent()

        val model = Mps.parse(text)

        assertEquals("TEST", model.name)
        assertEquals(ObjectiveSense.MINIMIZE, model.sense)
        assertEquals(listOf("X1", "X2"), model.variables.map { it.name })
        assertEquals(MpsVar("X1", integer = true, lower = 0.0, upper = 10.0), model.variables[0])
        assertEquals(MpsVar("X2", integer = false, lower = null, upper = null), model.variables[1])
        assertEquals(listOf(0, 1), model.objective.indices.toList())
        assertEquals(listOf(1.0, 2.0), model.objective.coeffs.toList())

        val byName = model.constraints.associateBy { it.name }
        assertEquals(null to 4.0, byName.getValue("C1").let { it.lower to it.upper }) // L: <= rhs
        assertEquals(1.0 to null, byName.getValue("C2").let { it.lower to it.upper }) // G: >= rhs
        assertEquals(2.0 to 2.0, byName.getValue("C3").let { it.lower to it.upper }) // E: == rhs
        assertEquals(listOf(0, 1), byName.getValue("C1").indices.toList())
        assertEquals(listOf(1.0, 1.0), byName.getValue("C1").coeffs.toList())
    }

    @Test
    fun `sections after ENDATA should not be read as the model`() {
        val text = """
            NAME          TRAILER
            ROWS
             N  COST
             L  C1
            COLUMNS
                X1        COST           1.0   C1             1.0
            RHS
                RHS       C1             4.0
            ENDATA
            IMPORTANCES
            X1           2
        """.trimIndent()

        val model = Mps.parse(text)

        assertEquals(listOf("X1"), model.variables.map { it.name })
        assertEquals(listOf(4.0), model.constraints.map { it.upper })
    }

    @Test
    fun `a lazy constraint should be posted as an ordinary constraint`() {
        val text = """
            NAME          LAZY
            ROWS
             N  COST
             L  C1
            LAZYCONS
             G  L1
            COLUMNS
                X1        COST           1.0   C1             1.0
                X1        L1             1.0
            RHS
                RHS       C1             4.0   L1             2.0
            ENDATA
        """.trimIndent()

        val model = Mps.parse(text)

        val l1 = model.constraints.single { it.name == "L1" }
        assertEquals(2.0 to null, l1.lower to l1.upper)
        assertEquals(listOf(1.0), l1.coeffs.toList())
    }

    @Test
    fun `a user cut should be skipped`() {
        val text = """
            NAME          CUTS
            ROWS
             N  COST
             L  C1
            USERCUTS
             L  U1
            COLUMNS
                X1        COST           1.0   C1             1.0
            RHS
                RHS       C1             4.0
            ENDATA
        """.trimIndent()

        assertEquals(listOf("C1"), Mps.parse(text).constraints.map { it.name })
    }

    @Test
    fun `a trailing dollar comment should not be read as data`() {
        val text = """
            NAME          COMMENT
            ROWS
             N  COST
             L  C1
            COLUMNS
                X1        COST           1.0   C1             1.0   ${'$'} empty column
            RHS
                RHS       C1             4.0
            ENDATA
        """.trimIndent()

        val c1 = Mps.parse(text).constraints.single()
        assertEquals(listOf(1.0), c1.coeffs.toList())
    }

    @Test
    fun `parsed numeric fields should retain decimal and scientific authority`() {
        val text = "ROWS\n N COST\n E C1\nCOLUMNS\n X COST 0.1 C1 9.007199254740993D15\n" +
            "RHS\n RHS C1 1.208925819614629174706176E24\nENDATA"

        val source = Mps.parse(text).sourceNumbers()

        assertEquals("1/10", source.objectiveCoefficients.single().fraction.toString())
        assertEquals("9007199254740993", source.constraintCoefficients.single().single().fraction.toString())
        assertEquals("1208925819614629174706176", source.constraintBounds.single().first?.fraction.toString())
    }

    @Test
    fun `unsupported exact decimal scales should decline cleanly`() {
        val tokens = listOf("1e-2147483648", "0.1e-9223372036854775808")

        for (token in tokens) {
            assertFailsWith<MpsFormatException>(token) {
                Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST $token\nENDATA")
            }
        }
    }

    @Test
    fun `unchanged copies should retain exact sums with overflowing projections`() {
        val parsed = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1e308\n X COST 1e308\nENDATA")

        val compiled = parsed.copy(name = "copy").toExactSourceProblem()

        val witness = compiled.sourceWitness(listOf(BigFraction.ONE))

        assertEquals("2" + "0".repeat(308), witness.objective.toString())
    }

    @Test
    fun `a value bound with no value should be rejected`() {
        val ex = assertFailsWith<MpsFormatException> {
            Mps.parse("ROWS\n N COST\nCOLUMNS\n X1 COST 1\nBOUNDS\n UP X1\nENDATA")
        }
        assertTrue("needs a column and a value" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `a variable should default to the zero to positive-infinity range`() {
        val text = """
            ROWS
             N  COST
             G  C1
            COLUMNS
                X1        COST           1.0   C1             1.0
            ENDATA
        """.trimIndent()

        val v = Mps.parse(text).variables.single()

        assertEquals(0.0, v.lower)
        assertNull(v.upper) // +infinity
        assertTrue(!v.integer)
    }

    @Test
    fun `a marker integer without a bounds entry should default to binary`() {
        val cases = listOf("", "BOUNDS\n", "BOUNDS\n UP BND Y 5\n")
        for (bounds in cases) {
            val text = "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 1\n" +
                " M1 'MARKER' 'INTEND'\n Y COST 1\n${bounds}ENDATA"

            val variable = Mps.parse(text).variables.first()

            assertEquals(MpsVar("X", integer = true, lower = 0.0, upper = 1.0), variable, bounds)
        }
    }

    @Test
    fun `explicit bounds should replace a marker integer binary default`() {
        val cases = listOf(
            Triple("LO BND X 2", 2.0, null),
            Triple("LI BND X 2", 2.0, null),
            Triple("UP BND X 5", 0.0, 5.0),
            Triple("UI BND X 5", 0.0, 5.0),
            Triple("FX BND X 2", 2.0, 2.0),
            Triple("FR BND X", null, null),
            Triple("MI BND X", null, null),
            Triple("PL BND X", 0.0, null),
            Triple("BV BND X", 0.0, 1.0),
        )
        for ((bound, lower, upper) in cases) {
            val text = "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 1\n" +
                " M1 'MARKER' 'INTEND'\nBOUNDS\n $bound\nENDATA"

            val variable = Mps.parse(text).variables.single()

            assertEquals(MpsVar("X", integer = true, lower = lower, upper = upper), variable, bound)
        }
    }

    @Test
    fun `ranges should resolve into two-sided bounds`() {
        val cases = listOf(
            Triple("L", 6.0, 4.0 to 10.0), // [rhs - |R|, rhs]
            Triple("G", 6.0, 10.0 to 16.0), // [rhs, rhs + |R|]
            Triple("E", 6.0, 10.0 to 16.0), // R >= 0: [rhs, rhs + R]
            Triple("E", -6.0, 4.0 to 10.0), // R < 0: [rhs + R, rhs]
        )
        for ((type, range, expected) in cases) {
            val text = "ROWS\n N COST\n $type C1\nCOLUMNS\n X1 COST 1.0 C1 1.0\n" +
                "RHS\n RHS C1 10.0\nRANGES\n RNG C1 $range\nENDATA"
            val c = Mps.parse(text).constraints.single()
            assertEquals(expected, c.lower to c.upper, "range $type $range")
        }
    }

    @Test
    fun `parsing should read objective sense and constant`() {
        val text = """
            OBJSENSE
             MAX
            ROWS
             N  COST
             G  C1
            COLUMNS
                X1        COST           3.0   C1             1.0
            RHS
                RHS       COST           7.0   C1             1.0
            ENDATA
        """.trimIndent()

        val model = Mps.parse(text)

        assertEquals(ObjectiveSense.MAXIMIZE, model.sense)
        // An RHS against the objective row is the negated objective constant.
        assertEquals(-7.0, model.objective.constant)
    }

    @Test
    fun `an INDICATORS entry should gate a row on a binary column`() {
        val text = """
            ROWS
             N  COST
             L  C1
             G  C2
            COLUMNS
                X1        COST           1.0   C1             1.0
                X1        C2             1.0
                B1        COST           0.0
                B2        COST           0.0
            BOUNDS
             BV BND       B1
             BV BND       B2
            INDICATORS
             IF C1 B1 1
             IF C2 B2 0
            ENDATA
        """.trimIndent()

        val byName = Mps.parse(text).constraints.associateBy { it.name }

        assertEquals(MpsIndicator(column = 1, whenOne = true), byName.getValue("C1").indicator)
        assertEquals(MpsIndicator(column = 2, whenOne = false), byName.getValue("C2").indicator)
    }

    @Test
    fun `an INDICATORS entry naming an unknown row or column should be rejected`() {
        val head = "ROWS\n N COST\n L C1\nCOLUMNS\n X1 COST 1.0 C1 1.0\n B1 COST 0.0\nBOUNDS\n BV BND B1\n"
        for (line in listOf("IF NOSUCHROW B1 1", "IF C1 NOSUCHCOL 1")) {
            assertFailsWith<MpsFormatException>(line) { Mps.parse(head + "INDICATORS\n $line\nENDATA") }
        }
    }

    @Test
    fun `an INDICATORS entry whose trigger value is neither zero nor one should be rejected`() {
        for (value in listOf("2", "0.99999999999999999999", "1e-400")) {
            val text = "ROWS\n N COST\n L C1\nCOLUMNS\n X1 COST 1.0 C1 1.0\n B1 COST 0.0\n" +
                "BOUNDS\n BV BND B1\nINDICATORS\n IF C1 B1 $value\nENDATA"
            assertFailsWith<MpsFormatException>(value) { Mps.parse(text) }
        }
    }

    @Test
    fun `an unknown row type should be rejected`() {
        val text = "ROWS\n X BADROW\nENDATA"
        assertFailsWith<MpsFormatException> { Mps.parse(text) }
    }

    @Test
    fun `duplicate row names should be rejected`() {
        assertFailsWith<MpsFormatException> { Mps.parse("ROWS\n L C1\n G C1\nENDATA") }
    }

    @Test
    fun `non-finite numeric fields should be rejected`() {
        assertFailsWith<MpsFormatException> { Mps.parse("ROWS\n L C1\nCOLUMNS\n X1 C1 NaN\nENDATA") }
    }

    @Test
    fun `parse should not depend on source chunk boundaries`() {
        val text = """
            NAME          t
            ROWS
             N  cost
             L  c1
            COLUMNS
                x         cost           1.0   c1             1.0
                y         cost           1.0   c1             1.0
            RHS
                rhs       c1             3.0
            BOUNDS
             UP BND       x              4.0
             UP BND       y              4.0
            ENDATA
        """.trimIndent() + "\n"
        fun shape(source: CharSource) =
            Mps.parse(source).toProblem().model.let { Triple(it.numBoolVars, it.numIntVars, it.factors.size) }
        assertEquals(shape(StringCharSource(text)), shape(perCharSource(text)))
    }
}
