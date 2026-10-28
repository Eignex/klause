package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchProtocol
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LabSelectionsTest {
    @Test
    fun `independent catalog selections retain their order and selectors`() {
        val settings = LabSettings(mapOf("selections" to """[{"suite":"a","max":"2"},{"set":"frozen"}]"""))

        assertEquals(listOf(mapOf("suite" to "a", "max" to "2"), mapOf("set" to "frozen")), settings.selections)
    }

    @ParameterizedTest
    @ValueSource(strings = ["[]", "[{}]", "[{\"out\":\"escape\"}]", "not-json"])
    fun `empty malformed and nonselector groups fail validation`(encoded: String) {
        assertFailsWith<IllegalArgumentException> { LabSettings(mapOf("selections" to encoded)) }
    }

    @Test
    fun `committed recipes use the repository manifest and supported request controls`() {
        Files.list(Path.of("lab/recipes")).use { files ->
            files.filter { it.toString().endsWith(".json") }.forEach { file ->
                val recipe = BenchProtocol.json.parseToJsonElement(Files.readString(file)).jsonObject
                val bench = recipe.getValue("bench").jsonObject
                val parameters = bench.getValue("request").jsonObject.getValue("parameters").jsonObject
                    .mapValues { it.value.jsonPrimitive.content }

                assertEquals("Eignex/klause", recipe.getValue("repository").jsonPrimitive.content)
                assertEquals("klause-bench/lab/bench.json", bench.getValue("manifest").jsonPrimitive.content)
                LabSettings(parameters)
            }
        }
    }
}
