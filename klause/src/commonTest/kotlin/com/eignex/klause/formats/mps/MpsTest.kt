package com.eignex.klause.formats.mps

import com.eignex.klause.formats.mps.MpsIndicator
import com.eignex.klause.formats.mps.MpsVar
import com.eignex.klause.ir.ObjectiveSense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpsTest {

    @Test
    fun `parses rows columns rhs and bounds into a model`() {
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
        // X1 sits inside the INTORG/INTEND marker with an explicit upper bound; X2 is free continuous.
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
    fun `sections after ENDATA are not read as the model`() {
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
    fun `a lazy constraint is posted as an ordinary constraint`() {
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

        // A solver may add it lazily, but it constrains the model either way: dropping it would admit
        // points the model forbids.
        val l1 = model.constraints.single { it.name == "L1" }
        assertEquals(2.0 to null, l1.lower to l1.upper)
        assertEquals(listOf(1.0), l1.coeffs.toList())
    }

    @Test
    fun `a user cut is skipped because it cuts no solution`() {
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
    fun `a trailing dollar comment is not read as data`() {
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
    fun `parsed numeric fields retain decimal and scientific authority`() {
        val text = "ROWS\n N COST\n E C1\nCOLUMNS\n X COST 0.1 C1 9.007199254740993D15\n" +
            "RHS\n RHS C1 1.208925819614629174706176E24\nENDATA"

        val source = Mps.parse(text).sourceNumbers()

        assertEquals("1/10", source.objectiveCoefficients.single().fraction.toString())
        assertEquals("9007199254740993", source.constraintCoefficients.single().single().fraction.toString())
        assertEquals("1208925819614629174706176", source.constraintBounds.single().first?.fraction.toString())
    }

    @Test
    fun `unsupported exact decimal scales decline cleanly`() {
        val tokens = listOf("1e-2147483648", "0.1e-9223372036854775808")

        for (token in tokens) {
            assertFailsWith<MpsFormatException>(token) {
                Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST $token\nENDATA")
            }
        }
    }

    @Test
    fun `unchanged copies retain exact sums with overflowing projections`() {
        val parsed = Mps.parse("ROWS\n N COST\nCOLUMNS\n X COST 1e308\n X COST 1e308\nENDATA")

        val exact = parsed.copy(name = "copy").toExactLpModel()

        assertEquals("2" + "0".repeat(308), exact.objective.cost(0).value.toString())
    }

    @Test
    fun `rejects a value bound with no value`() {
        val ex = assertFailsWith<MpsFormatException> {
            Mps.parse("ROWS\n N COST\nCOLUMNS\n X1 COST 1\nBOUNDS\n UP X1\nENDATA")
        }
        assertTrue("needs a column and a value" in ex.message.orEmpty(), ex.message.orEmpty())
    }

    @Test
    fun `defaults a variable to the zero to positive-infinity range`() {
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
    fun `defaults a marker integer without a bounds entry to binary`() {
        val cases = listOf("", "BOUNDS\n", "BOUNDS\n UP BND Y 5\n")
        for (bounds in cases) {
            val text = "ROWS\n N COST\nCOLUMNS\n M0 'MARKER' 'INTORG'\n X COST 1\n" +
                " M1 'MARKER' 'INTEND'\n Y COST 1\n${bounds}ENDATA"

            val variable = Mps.parse(text).variables.first()

            assertEquals(MpsVar("X", integer = true, lower = 0.0, upper = 1.0), variable, bounds)
        }
    }

    @Test
    fun `explicit bounds replace a marker integer binary default`() {
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
    fun `resolves each bounds type`() {
        // (bound line, expected lower, expected upper); null bound = infinity.
        val cases = listOf(
            Triple("UP BND X1 5.0", 0.0, 5.0),
            Triple("LO BND X1 -3.0", -3.0, null),
            Triple("FX BND X1 2.0", 2.0, 2.0),
            Triple("FR BND X1", null, null),
            Triple("MI BND X1", null, null),
            Triple("BV BND X1", 0.0, 1.0),
        )
        for ((line, lo, hi) in cases) {
            val text = "ROWS\n N COST\nCOLUMNS\n X1 COST 1.0\nBOUNDS\n $line\nENDATA"
            val v = Mps.parse(text).variables.single()
            assertEquals(lo, v.lower, "lower for '$line'")
            assertEquals(hi, v.upper, "upper for '$line'")
        }
    }

    @Test
    fun `resolves ranges into two-sided bounds`() {
        // A range R turns a one-sided row into an interval per the MPS sign rules.
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
    fun `reads objective sense and constant`() {
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
    fun `parses an INDICATORS entry gating a row on a binary column`() {
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
    fun `rejects an INDICATORS entry naming an unknown row or column`() {
        val head = "ROWS\n N COST\n L C1\nCOLUMNS\n X1 COST 1.0 C1 1.0\n B1 COST 0.0\nBOUNDS\n BV BND B1\n"
        for (line in listOf("IF NOSUCHROW B1 1", "IF C1 NOSUCHCOL 1")) {
            assertFailsWith<MpsFormatException>(line) { Mps.parse(head + "INDICATORS\n $line\nENDATA") }
        }
    }

    @Test
    fun `rejects an INDICATORS entry whose trigger value is neither zero nor one`() {
        val text = "ROWS\n N COST\n L C1\nCOLUMNS\n X1 COST 1.0 C1 1.0\n B1 COST 0.0\n" +
            "BOUNDS\n BV BND B1\nINDICATORS\n IF C1 B1 2\nENDATA"
        assertFailsWith<MpsFormatException> { Mps.parse(text) }
    }

    @Test
    fun `rejects an unknown row type`() {
        val text = "ROWS\n X BADROW\nENDATA"
        assertFailsWith<MpsFormatException> { Mps.parse(text) }
    }

    @Test
    fun `rejects duplicate row names`() {
        assertFailsWith<MpsFormatException> { Mps.parse("ROWS\n L C1\n G C1\nENDATA") }
    }

    @Test
    fun `rejects non-finite numeric fields`() {
        assertFailsWith<MpsFormatException> { Mps.parse("ROWS\n L C1\nCOLUMNS\n X1 C1 NaN\nENDATA") }
    }
}
