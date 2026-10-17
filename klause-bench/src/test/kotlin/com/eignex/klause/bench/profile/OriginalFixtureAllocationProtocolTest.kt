package com.eignex.klause.bench.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OriginalFixtureAllocationProtocolTest {
    private val output = listOf("CLASS\tmatcher", "CLASS\trebuild", "FIXTURE_CLASS\toriginal",
        "FIXTURE\tmatching", "JAR\toriginal", "VM\tpinned", "PID\t123", "CPU\t0") +
        (0 until 21).map { "MEASURE\t$it\t10\t20" } + "COMPLETED\tmatching"

    @Test
    fun `every original round survives alongside the separately marked late window`() {
        val rows = OriginalFixtureAllocationProtocol.measurements(output.joinToString("\n"), "matching")

        assertEquals((0 until 21).toList(), rows.map { it.round })
        assertFalse(rows[10].inPredeclaredLateWindow)
        assertTrue(rows[11].inPredeclaredLateWindow)
        assertEquals(10L, rows[0].elapsedNanos)
        assertEquals(20L, rows[0].threadAllocatedBytes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "reordered", "negative-duration", "negative-allocation",
        "unfinished", "unexpected", "wrong-completed", "unsupported-counter", "extra-field"])
    fun `partial malformed and repeated original measurements cannot report completion`(variant: String) {
        val lines = output.toMutableList()
        when (variant) {
            "missing" -> lines.removeAt(8)
            "duplicate" -> lines[9] = lines[8]
            "reordered" -> {
                lines[8] = output[9]
                lines[9] = output[8]
            }
            "negative-duration" -> lines[8] = "MEASURE\t0\t-1\t20"
            "negative-allocation" -> lines[8] = "MEASURE\t0\t10\t-1"
            "unfinished" -> lines.removeAt(lines.lastIndex)
            "unexpected" -> lines.add("UNKNOWN\tretained")
            "wrong-completed" -> lines[lines.lastIndex] = "COMPLETED\texcept-zero"
            "unsupported-counter" -> lines[8] = "MEASURE\t0\t10\tunavailable"
            "extra-field" -> lines[8] = "MEASURE\t0\t10\t20\textra"
        }

        assertFailsWith<IllegalArgumentException> {
            OriginalFixtureAllocationProtocol.measurements(lines.joinToString("\n"), "matching")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "renamed", "edited", "duplicate"])
    fun `missing renamed and edited fixture source cannot authorize measurement`(variant: String) {
        val fixture = OriginalFixtureAllocationProtocol.fixtures.first()
        val body = "    fun `${fixture.method}`() {\n        error(\"changed\")\n    }"
        val source = when (variant) {
            "missing" -> ""
            "renamed" -> body.replace("should be implied", "are implied")
            "duplicate" -> "$body\n$body"
            else -> body
        }

        assertFailsWith<IllegalArgumentException> {
            OriginalFixtureAllocationProtocol.sourceBody(source, fixture)
        }
    }
}
