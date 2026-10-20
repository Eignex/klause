package com.eignex.klause.bench.metric

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordComparisonTest {
    @ParameterizedTest
    @MethodSource("exactPairs")
    fun `exact objectives should decide quality in either direction`(lower: String, higher: String, maximize: Boolean) {
        val a = record(exact = if (maximize) higher else lower, maximize = maximize)
        val b = record(exact = if (maximize) lower else higher, maximize = maximize)

        val outcome = RecordComparison.compare(a, b, complete = true)

        assertEquals(Triple(1, 1.0, 0.0), Triple(outcome.comparison, outcome.scoreA, outcome.scoreB))
    }

    @Test
    fun `an exact objective equal to a legacy objective should tie`() {
        val legacy = record(exact = null, objective = 1.25)
        val exact = record(exact = "5/4", objective = 1.25)

        val outcome = RecordComparison.compare(legacy, exact, complete = true)

        assertEquals(Triple(0, 0.5, 0.5), Triple(outcome.comparison, outcome.scoreA, outcome.scoreB))
    }

    @Test
    fun `a witness against a proven refutation should be flagged unsound`() {
        val sat = buildJsonObject { put("feasible", true); put("kind", "satisfy") }
        val unsat = buildJsonObject { put("feasible", false); put("proven", true); put("kind", "satisfy") }

        assertTrue(RecordComparison.compare(sat, unsat, complete = true).unsound)
    }

    private fun record(exact: String?, maximize: Boolean = false, objective: Double = 1.0): JsonObject =
        buildJsonObject {
            put("feasible", true)
            put("proven", false)
            put("kind", "optimize")
            put("objective", objective)
            put("exactObjective", JsonPrimitive(exact))
            put("maximize", maximize)
            put("elapsedMs", 10)
        }

    companion object {
        @JvmStatic
        fun exactPairs(): List<Arguments> = listOf(
            "9007199254740992" to "9007199254740993",
            "9223372036854775808" to "9223372036854775809",
            "1/3" to "1000000000000000001/3000000000000000000",
        ).flatMap { (lower, higher) -> listOf(false, true).map { Arguments.of(lower, higher, it) } }
    }
}
