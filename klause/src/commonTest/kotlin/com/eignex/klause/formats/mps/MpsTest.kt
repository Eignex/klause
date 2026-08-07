package com.eignex.klause.formats.mps

import com.eignex.klause.formats.ObjectiveSense
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
    fun `resolves a value-less bounds type that carries a redundant trailing value`() {
        // Writers emit lines like `BV BOUND1 C_000047 1.0` where the value-less type still carries a
        // value. The column is the named one, never the stray trailing number.
        val cases = listOf(
            Triple("BV BND X1 1.0", 0.0, 1.0),
            Triple("FR BND X1 0.0", null, null),
            Triple("MI BND X1 0.0", null, null),
            Triple("PL BND X1 1e30", 0.0, null),
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
    fun `rejects an unknown row type`() {
        val text = "ROWS\n X BADROW\nENDATA"
        assertFailsWith<MpsFormatException> { Mps.parse(text) }
    }
}
