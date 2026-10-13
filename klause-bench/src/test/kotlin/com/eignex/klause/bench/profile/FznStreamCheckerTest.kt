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
