package com.eignex.klause.bench.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OpbStreamCheckerTest {
    @ParameterizedTest
    @ValueSource(strings = ["9223372036854775808", "18446744073709551616", "999999999999999999999999999999"])
    fun `wide weighted source literals should be checked without machine word rounding`(weight: String) {
        val source = "$weight x1 1 ~x2 >= $weight ;"

        val valid = OpbStreamChecker.check(source, "s SATISFIABLE\nv x1 -x2\n", false, 1).single()
        val invalid = OpbStreamChecker.check(source, "s SATISFIABLE\nv -x1 -x2\n", false, 1).single()

        assertEquals("valid", valid.status)
        assertEquals("invalid", invalid.status)
    }

    @Test
    fun `repeated and complementary source terms should contribute their exact values`() {
        val source = "18446744073709551616 x1 -18446744073709551616 x1 3 ~x1 = 3 ;"

        val valid = OpbStreamChecker.check(source, "v -x1\n", false, 1).single()
        val invalid = OpbStreamChecker.check(source, "v x1\n", false, 1).single()

        assertEquals("valid", valid.status)
        assertEquals("invalid", invalid.status)
    }

    @Test
    fun `source products and soft penalties should retain original objective units`() {
        val source = "min: -2 x1 ~x2 ; soft: 5 ; [3] 18446744073709551616 x2 >= 1 ;"

        val valid = OpbStreamChecker.check(source, "o 1\ns SATISFIABLE\nv x1 -x2\n", false, 1).single()
        val wrongCost = OpbStreamChecker.check(source, "o 3\ns SATISFIABLE\nv x1 -x2\n", false, 1).single()

        assertEquals("valid", valid.status)
        assertEquals("invalid", wrongCost.status)
    }

    @Test
    fun `a failed soft top should reject a publication with a matching cost`() {
        val source = "soft: 3 ; [3] 1 x1 >= 1 ;"

        val result = OpbStreamChecker.check(source, "o 3\nv -x1\n", false, 1).single()

        assertEquals("invalid", result.status)
    }

    @Test
    fun `unused declared coordinates should still be required in a complete model`() {
        val source = "* #variable= 2 #constraint= 1\n1 x1 >= 1 ;"

        val result = OpbStreamChecker.check(source, "v x1\n", false, 1).single()

        assertEquals("invalid", result.status)
    }

    @Test
    fun `a capture without a model should retain its missing publication failure`() {
        assertFailsWith<IllegalArgumentException> {
            OpbStreamChecker.check("1 x1 >= 1 ;", "s UNKNOWN\n", false, 1)
        }
    }
}
