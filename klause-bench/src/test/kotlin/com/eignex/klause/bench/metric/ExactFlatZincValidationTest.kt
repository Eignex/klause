package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactFlatZincValidationTest {
    @Test
    fun `original extrema reject a result beyond every operand`() {
        for ((name, result) in listOf("int_min" to "1", "int_max" to "2")) {
            val source = "var 0..3: x; var 0..3: y; var 0..3: r; " +
                "constraint $name(x, y, r); solve satisfy;"

            assertEquals("valid", ExactFlatZincValidation.inspect(source, "x = 1;\ny = 2;\nr = $result;", null).status)
            assertEquals("invalid", ExactFlatZincValidation.inspect(source, "x = 1;\ny = 2;\nr = 3;", null).status)
        }
    }

    @Test
    fun `original variable element checks its index and selected value`() {
        val source = "var 0..3: i; var 0..9: x; var 0..9: y; var 0..9: r; " +
            "constraint array_var_int_element(i, [x, y, x], r); solve satisfy;"
        val values = "x = 4;\ny = 9;\nr = 9;\n"

        assertEquals("valid", ExactFlatZincValidation.inspect(source, values + "i = 2;", null).status)
        for (index in listOf(0, 1, 3)) {
            assertEquals("invalid", ExactFlatZincValidation.inspect(source, values + "i = $index;", null).status)
        }
    }

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
    fun `fixed power predicates check original exact coordinates and reject corruption`() {
        for ((predicate, input, exponent, output) in listOf(
            listOf("int_pow", "-2", "63", "-9223372036854775808"),
            listOf("klause_int_pow", "0", "0", "1"),
            listOf("int_pow_fixed", "-1", "2147483647", "-1"),
            listOf("float_pow", "1/3", "4.0", "1/81"),
        )) {
            val source = "var float: x; var float: y; constraint $predicate(x, $exponent, y); solve satisfy;"
            val valid = ExactFlatZincValidation.inspect(source, "x = $input;\ny = $output;", null)
            val corrupted = ExactFlatZincValidation.inspect(source, "x = $input;\ny = 0;", null)

            assertEquals("valid", valid.status, valid.reason)
            assertEquals("invalid", corrupted.status, corrupted.reason)
        }
    }

    @Test
    fun `power checker cannot accept saturation as exact arithmetic`() {
        val source = "var int: x; var int: y; constraint int_pow(x, 2, y); solve satisfy;"

        val result = ExactFlatZincValidation.inspect(source, "x = 3037000500;\ny = 9223372036854775807;", null)

        assertEquals("invalid", result.status, result.reason)
    }

    @Test
    fun `absolute value checks exact integer signs and magnitudes`() {
        val source = "var int: x; var int: y; constraint int_abs(x,y); solve satisfy;"
        for ((input, output) in listOf("-3" to "3", "0" to "0", "3" to "3",
            "-9223372036854775808" to "9223372036854775808")) {
            val result = ExactFlatZincValidation.inspect(source, "x = $input;\ny = $output;", null)

            assertEquals("valid", result.status, result.reason)
        }
    }

    @Test
    fun `absolute value rejects relaxed and wrapped integer points`() {
        val source = "var int: x; var int: y; constraint int_abs(x,y); solve satisfy;"
        for ((input, output) in listOf("0" to "3", "-3" to "4", "3" to "-3",
            "-9223372036854775808" to "-9223372036854775808")) {
            val result = ExactFlatZincValidation.inspect(source, "x = $input;\ny = $output;", null)

            assertEquals("invalid", result.status, result.reason)
        }
    }

    @Test
    fun `real absolute value checks exact rational coordinates`() {
        val source = "var float: x; var float: y; constraint float_abs(x,y); solve satisfy;"
        for ((input, output, status) in listOf(Triple("-1/3", "1/3", "valid"),
            Triple("1/3", "1/3", "valid"), Triple("0", "1/3", "invalid"))) {
            val result = ExactFlatZincValidation.inspect(source, "x = $input;\ny = $output;", null)

            assertEquals(status, result.status, result.reason)
        }
    }

    @Test
    fun `absolute value aliases require a nonnegative coordinate`() {
        val source = "var float: x; constraint float_abs(x,x); solve satisfy;"
        for ((value, status) in listOf("-1/3" to "invalid", "0" to "valid", "1/3" to "valid")) {
            val result = ExactFlatZincValidation.inspect(source, "x = $value;", null)

            assertEquals(status, result.status, result.reason)
        }
    }

    @Test
    fun `mixed finite float choices are checked from the original constant array`() {
        val source = "var 1..1: i; var 0.0..1.0: exact; var 0.0..1.0: grid; " +
            "constraint array_float_element(i, [0.125], exact); constraint float_le(grid, 0.5); solve satisfy;"
        val valid = "i = 1;\nexact = 1/8;\ngrid = 1/2;"

        assertEquals("valid", ExactFlatZincValidation.inspect(source, valid, null).status)
        val invalid = listOf(valid.replace("exact = 1/8", "exact = 1/4"), valid.replace("grid = 1/2", "grid = 3/4"))
        for (coordinates in invalid) {
            assertEquals("invalid", ExactFlatZincValidation.inspect(source, coordinates, null).status)
        }
    }

    @Test
    fun `rounded cylinder bound cannot accept an exact binary64 product`() {
        val source = "var 50..200: n; var 50.0..200.0: raw; var 0.5..2.0: scaled; " +
            "constraint int2float(n, raw); constraint float_lin_eq([0.01, -1.0], [raw, scaled], 0.0); solve satisfy;"
        val exact = "n = 200; raw = 200; scaled = 144115188075855875/72057594037927936;".replace("; ", ";\n")
        val rounded = "n = 200; raw = 200; scaled = 2;".replace("; ", ";\n")
        val interior = "n = 199; raw = 199; scaled = 1147156897083812765/576460752303423488;".replace("; ", ";\n")

        assertEquals("invalid", ExactFlatZincValidation.inspect(source, exact, null).status)
        assertEquals("invalid", ExactFlatZincValidation.inspect(source, rounded, null).status)
        val valid = ExactFlatZincValidation.inspect(source, interior, null)
        assertEquals("valid", valid.status, valid.reason)
        assertEquals("flatzinc-binary64", valid.scope)
    }

    @Test
    fun `mixed components are checked together against original predicates`() {
        val source = "var 0.0..1.0: exact; var 0.0..1.0: grid; " +
            "constraint float_lin_eq([3.0], [exact], 1.0); constraint float_le(grid, 0.5); solve satisfy;"
        val coordinates = "exact = 1/3;\ngrid = 1/2;"

        assertEquals("valid", ExactFlatZincValidation.inspect(source, coordinates, null).status)
        val invalidGrid = coordinates.replace("grid = 1/2", "grid = 3/4")
        val invalidExact = coordinates.replace("exact = 1/3", "exact = 0.3333333333333333")
        assertEquals("invalid", ExactFlatZincValidation.inspect(source, invalidGrid, null).status)
        assertEquals("invalid", ExactFlatZincValidation.inspect(source, invalidExact, null).status)
    }

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
