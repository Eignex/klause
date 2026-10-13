package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FznStreamCheckerTest {
    private val declarations = "var 0..1: i; var float: x; var float: y; " +
        "constraint float_times(x, x, y); constraint float_le(y, 4.0); "

    @Test
    fun `finite objective fields are checked against the original source cost`() {
        val source = "var 0..2: i; solve minimize i;"
        val output = "i = 2;\n_objective = 2;\n----------\ni = 1;\n_objective = 1;\n----------\n"

        val results = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 2)

        assertEquals(listOf("valid", "valid"), results.map { it.status })
    }

    @Test
    fun `an incorrect finite objective field cannot earn a checked witness`() {
        val source = "var 0..2: i; solve minimize i;"
        val output = "i = 2;\n_objective = 1;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("invalid", result.status)
    }

    @ParameterizedTest
    @ValueSource(strings = ["2", "2.0"])
    fun `equivalent comment and finite objective fields retain source acceptance`(helper: String) {
        val source = "var 0..2: i; solve minimize i;"
        val output = "% objective=2/1\ni = 2;\n_objective = $helper;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("valid", result.status)
    }

    @Test
    fun `conflicting comment and finite objective field are rejected`() {
        val source = "var 0..2: i; solve minimize i;"
        val output = "% objective=2\ni = 2;\n_objective = 1;\n----------\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["minimize", "maximize"])
    fun `each improving publication is checked against original rows and objective`(sense: String) {
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
    fun `incorrect original objective remains rejected beside a valid publication`() {
        val source = declarations + "solve maximize i;"
        val output = "% objective=0\ni = 0;\nx = 2;\ny = 4;\n----------\n" +
            "% objective=-1\ni = 1;\nx = 2;\ny = 4;\n----------\n"

        val results = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 2)

        assertEquals(listOf("valid", "invalid"), results.map { it.status })
    }

    @Test
    fun `a source-invalid point cannot earn a checked witness`() {
        val source = declarations + "solve maximize i;"
        val output = "% objective=1\ni = 1;\nx = 2;\ny = 3;\n----------\n"

        val result = FznStreamChecker.check(source, output, interrupted = false, minimumWitnesses = 1).single()

        assertEquals("invalid", result.status)
    }

    @Test
    fun `duplicate final publication is rejected`() {
        val source = declarations + "solve maximize i;"
        val point = "% objective=1\ni = 1;\nx = 2;\ny = 4;\n----------\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, point + point, interrupted = false, minimumWitnesses = 2)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["==========", "=====UNBOUNDED=====", "=====UNSATISFIABLE=====", "=====UNKNOWN=====",
        "% optimizationStatus=unattained-infimum", "% optimizationStatus=unattained-supremum"])
    fun `an interrupted publication cannot carry a terminal marker`(marker: String) {
        val source = declarations + "solve maximize i;"
        val output = "% objective=1\ni = 1;\nx = 2;\ny = 4;\n----------\n$marker\n"

        assertFailsWith<IllegalArgumentException> {
            FznStreamChecker.check(source, output, interrupted = true, minimumWitnesses = 1)
        }
    }
}
