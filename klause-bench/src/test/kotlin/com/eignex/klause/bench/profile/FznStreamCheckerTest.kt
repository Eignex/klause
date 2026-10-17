package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FznStreamCheckerTest {
    private val declarations = "var 0..1: i; var float: x; var float: y; " +
        "constraint float_times(x, x, y); constraint float_le(y, 4.0); "

    @ParameterizedTest
    @ValueSource(strings = ["-1/3", "0", "1/3"])
    fun `satisfaction publications should check exact absolute value without a cost`(input: String) {
        val source = "var float: x; var float: y; constraint float_abs(x,y); solve satisfy;"
        val magnitude = if (input == "0") "0" else "1/3"
        val output = "x = $input;\ny = $magnitude;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("valid", result.status)
    }

    @Test
    fun `satisfaction publications should reject a point allowed only by the absolute value relaxation`() {
        val source = "var float: x; var float: y; constraint float_abs(x,y); solve satisfy;"
        val output = "x = 0;\ny = 1/3;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("invalid", result.status)
    }

    @ParameterizedTest
    @ValueSource(strings = ["% objective=0\n", "_objective = 0;\n", "% klause-exact: _objective = 0;\n"])
    fun `satisfaction publications should not supply an optimization cost`(metadata: String) {
        val source = "var float: x; var float: y; constraint float_abs(x,y); solve satisfy;"
        val output = metadata + "x = 0;\ny = 0;\n----------\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1)
        }
    }

    @Test
    fun `finite objective fields should be checked against the original source cost`() {
        val source = "var 0..2: i; solve minimize i;"
        val output = "i = 2;\n_objective = 2;\n----------\ni = 1;\n_objective = 1;\n----------\n"

        val results = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 2)

        assertEquals(listOf("valid", "valid"), results.map { it.status })
    }

    @Test
    fun `an incorrect finite objective field should not earn a checked witness`() {
        val source = "var 0..2: i; solve minimize i;"
        val output = "i = 2;\n_objective = 1;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("invalid", result.status)
    }

    @ParameterizedTest
    @ValueSource(strings = ["2", "2.0"])
    fun `equivalent comment and finite objective fields should retain source acceptance`(helper: String) {
        val source = "var 0..2: i; solve minimize i;"
        val output = "% objective=2/1\ni = 2;\n_objective = $helper;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("valid", result.status)
    }

    @Test
    fun `conflicting comment and finite objective fields should be rejected`() {
        val source = "var 0..2: i; solve minimize i;"
        val output = "% objective=2\ni = 2;\n_objective = 1;\n----------\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["minimize", "maximize"])
    fun `exact rational objective annotations should retain their rounded display`(sense: String) {
        val source = "var float: x; solve $sense x;"
        val output = "x = 0.6142857142857143;\n_objective = 0.6142857142857143;\n" +
            "% klause-exact: x = 43/70;\n% klause-exact: _objective = 43/70;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("valid", result.status)
    }

    @ParameterizedTest
    @ValueSource(strings = ["_objective = 0.5;\n", "% objective=1/2\n_objective = 0.6142857142857143;\n"])
    fun `cost metadata conflicting with its exact objective should be rejected`(metadata: String) {
        val source = "var float: x; solve minimize x;"
        val output = "x = 0.6142857142857143;\n" + metadata +
            "% klause-exact: x = 43/70;\n% klause-exact: _objective = 43/70;\n----------\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1)
        }
    }

    @Test
    fun `an exact objective differing from its source value should remain invalid`() {
        val source = "var float: x; solve minimize x;"
        val output = "x = 0.6142857142857143;\n_objective = 0.5;\n" +
            "% klause-exact: x = 43/70;\n% klause-exact: _objective = 1/2;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("invalid", result.status)
    }

    @Test
    fun `duplicate exact objective annotations should be rejected`() {
        val source = "var float: x; solve minimize x;"
        val output = "x = 0.5;\n_objective = 0.5;\n% klause-exact: x = 1/2;\n" +
            "% klause-exact: _objective = 1/2;\n% klause-exact: _objective = 1/2;\n----------\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["minimize", "maximize"])
    fun `each improving publication should be checked against original rows and objective`(sense: String) {
        val source = declarations + "solve $sense i;"
        val points = if (sense == "minimize") listOf(1, 0) else listOf(0, 1)
        val output = points.joinToString("") { i ->
            "% objective=$i\ni = $i;\nx = 2;\ny = 4;\n----------\n"
        }

        val results = FznStreamChecker.check(source, output, interrupted = true, minimumWitnesses = 2)

        assertEquals(listOf("valid", "valid"), results.map { it.status })
        assertEquals(listOf("flatzinc-binary64", "flatzinc-binary64"), results.map { it.scope })
    }

    @Test
    fun `an incorrect objective should remain rejected beside a valid publication`() {
        val source = declarations + "solve maximize i;"
        val output = "% objective=0\ni = 0;\nx = 2;\ny = 4;\n----------\n" +
            "% objective=-1\ni = 1;\nx = 2;\ny = 4;\n----------\n"

        val results = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 2)

        assertEquals(listOf("valid", "invalid"), results.map { it.status })
    }

    @Test
    fun `a source-invalid point should not earn a checked witness`() {
        val source = declarations + "solve maximize i;"
        val output = "% objective=1\ni = 1;\nx = 2;\ny = 3;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("invalid", result.status)
    }

    @Test
    fun `a duplicate final publication should be rejected`() {
        val source = declarations + "solve maximize i;"
        val point = "% objective=1\ni = 1;\nx = 2;\ny = 4;\n----------\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, point + point, interrupted = false, minimumWitnesses = 2)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["==========", "=====UNBOUNDED=====", "=====UNSATISFIABLE=====", "=====UNKNOWN=====",
        "% optimizationStatus=unattained-infimum", "% optimizationStatus=unattained-supremum"])
    fun `an interrupted publication should not carry a terminal marker`(marker: String) {
        val source = declarations + "solve maximize i;"
        val output = "% objective=1\ni = 1;\nx = 2;\ny = 4;\n----------\n$marker\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, output, interrupted = true, minimumWitnesses = 1)
        }
    }
}
