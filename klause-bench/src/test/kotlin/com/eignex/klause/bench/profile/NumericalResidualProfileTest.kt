package com.eignex.klause.bench.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

class NumericalResidualProfileTest {
    @ParameterizedTest
    @ValueSource(strings = ["NaN", "Infinity", "-Infinity"])
    fun `nonfinite statistics should serialize as strings`(value: String) {
        val number = value.toDouble()
        val statistics = mapOf("double" to number, "float" to number.toFloat(),
            "array" to doubleArrayOf(number))

        val encoded = NumericalResidualProfile.json(statistics).toString()

        val expected = Json.parseToJsonElement("""{"double":"$value","float":"$value","array":["$value"]}""")
        assertEquals(expected, Json.parseToJsonElement(encoded))
    }

    @Test
    fun `finite statistics should preserve numeric values`() {
        val statistics = doubleArrayOf(-1.5, 0.0, 2.0)

        val encoded = NumericalResidualProfile.json(statistics).toString()

        assertEquals(JsonArray(statistics.map(::JsonPrimitive)), Json.parseToJsonElement(encoded))
    }
}
