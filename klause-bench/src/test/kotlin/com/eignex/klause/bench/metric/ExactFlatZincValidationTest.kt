package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactFlatZincValidationTest {
    private val voltage = requireNotNull(javaClass.getResource("/flatzinc/voltage_divider.fzn")).readText()
    private val coordinates = """
        X_INTRODUCED_9_ = true;
        X_INTRODUCED_11_ = true;
        X_INTRODUCED_10_ = false;
        X_INTRODUCED_15_ = true;
        X_INTRODUCED_14_ = false;
        X_INTRODUCED_13_ = false;
        X_INTRODUCED_12_ = false;
        X_INTRODUCED_16_ = false;
        R2 = 9;
        __float_8 = 3602879701896397/36028797018963968;
        X_INTRODUCED_0_[5] = 3602879701896397/36028797018963968;
        V = 9;
        X_INTRODUCED_3_ = 1783425452438716425/504403158265495552;
        I = 356685090487743285/504403158265495552;
        X_INTRODUCED_2_ = 306244774661193727/504403158265495552;
        VD = 2756202971950743543/504403158265495552;
        R1 = 5;
    """.trimIndent()

    @Test
    fun `original voltage divider predicates and bounds accept exact coordinates`() {
        val result = ExactFlatZincValidation.inspect(voltage, coordinates, null)

        assertEquals("valid", result.status, result.reason)
        assertEquals("flatzinc-binary64", result.scope)
        assertTrue("6 bounds and 15 predicates" in result.reason)
    }

    @Test
    fun `voltage divider rejects a corrupted source coordinate`() {
        val corrupted = coordinates.replace("VD = 2756202971950743543/504403158265495552;", "VD = 11/2;")

        val result = ExactFlatZincValidation.inspect(voltage, corrupted, null)

        assertEquals("invalid", result.status, result.reason)
    }

    @Test
    fun `voltage divider rejects a corrupted coordinate even inside its bounds`() {
        val model = "var 0.0..1.0: x; constraint float_lin_eq([3.0], [x], 1.0); solve satisfy;"
        for (candidate in listOf("x = 0.3333333333333333;", "x = 1/2;")) {
            assertEquals("invalid", ExactFlatZincValidation.inspect(model, candidate, null).status)
        }
    }

    @Test
    fun `source binary64 bounds are checked without witness rounding`() {
        val model = "var 0.1..0.3: x; solve satisfy;"

        val result = ExactFlatZincValidation.inspect(model, "x = 30000000000000001/100000000000000000;", null)

        assertEquals("invalid", result.status)
    }

    @Test
    fun `missing coordinates and unsupported predicates remain unknown`() {
        val missing = ExactFlatZincValidation.inspect("var float: x; solve satisfy;", "y = 1/3;", null)
        val unsupported = ExactFlatZincValidation.inspect(
            "var float: x; constraint unsupported(x); solve satisfy;",
            "x = 1/3;",
            null,
        )

        assertEquals("unknown", missing.status)
        assertEquals("unknown", unsupported.status)
    }

    @Test
    fun `exact source objective rejects values that share a Double`() {
        val model = "var int: x; solve minimize x;"

        val result = ExactFlatZincValidation.inspect(model, "x = 9007199254740993;", "9007199254740992")

        assertEquals("invalid", result.status)
    }
}
