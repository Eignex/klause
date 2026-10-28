package com.eignex.klause.formats.json

import com.eignex.klause.arithmetic.real.compareExactly
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExactRealJsonTest {
    @Test
    fun `closed exact expressions should survive canonical round trips`() {
        for (text in listOf(
            "\"18446744073709551616/3\"",
            """{"root":{"coefficients":["-2","0","1"],"index":2}}""",
            """{"pi":{}}""",
            """{"exp":"1"}""",
            """{"log":"2"}""",
            """{"sin":{"root":{"coefficients":["-2","0","1"],"index":2}}}""",
            """{"cos":{"exp":"1"}}""",
            """{"add":[{"pi":{}},{"multiply":["2",{"power":[{"log":"2"},3]}]}]}""",
        )) {
            val original = ExactRealJson.decodeFromString(text)
            val encoded = ExactRealJson.encodeToJsonElement(original)
            val decoded = ExactRealJson.decodeFromJsonElement(encoded)
            assertEquals(0, original.compareExactly(decoded), text)
            assertEquals(encoded, ExactRealJson.encodeToJsonElement(decoded), text)
        }
    }

    @Test
    fun `malformed exact values should fail without an approximation fallback`() {
        for (text in listOf(
            "null", "1.4", "\"1/0\"", "\"sqrt(2)\"", "{}", """{"log":"0"}""", """{"log":"-1"}""",
            """{"root":{"coefficients":["-2","0","1"],"index":0}}""",
            """{"root":{"coefficients":["1","0","1"],"index":1}}""",
            """{"root":{"coefficients":["-2","0","1"],"index":3}}""",
            """{"root":{"coefficients":["-2","0","1"],"index":2,"lower":"1"}}""",
            """{"power":[{"pi":{}},-1]}""", """{"exp":"1","sin":"1"}""",
        )) {
            assertFailsWith<IllegalArgumentException>(text) {
                ExactRealJson.decodeFromJsonElement(Json.parseToJsonElement(text))
            }
        }
    }
}
