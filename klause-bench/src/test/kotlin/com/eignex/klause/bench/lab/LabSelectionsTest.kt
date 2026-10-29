package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LabSelectionsTest {
    @Test
    fun `independent catalog selections retain their order and selectors`() {
        val settings = LabSettings(mapOf("selections" to """[{"suite":"a","max":"2"},{"set":"frozen"}]"""))

        assertEquals(listOf(mapOf("suite" to "a", "max" to "2"), mapOf("set" to "frozen")), settings.selections)
    }

    @Test
    fun `input only planner has no solver platform requirements and cannot execute`() {
        val settings = LabSettings(mapOf("input-only" to "true"))
        assertEquals(emptySet(), settings.resources().requiredTags)
        val case = BenchCase("input", settings.caseParameters("default", 1), settings.resources())
        val output = Files.createTempDirectory("reference-plan")
        try {
            assertFailsWith<IllegalStateException> { ReferenceLabSuite.run(case, BenchContext(output)) }
            assertFailsWith<IllegalArgumentException> { KlauseLabSuite.run(case, BenchContext(output)) }
        } finally {
            output.toFile().deleteRecursively()
        }
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
                val name = recipe.getValue("name").jsonPrimitive.content
                val sweep = name.startsWith("status-sweep")
                if (sweep) {
                    assertEquals("1", recipe.getValue("parallel").jsonPrimitive.content)
                    val tags = recipe.getValue("tags").toString()
                    assertEquals("[\"platform:linux\",\"arch:x86_64\"]", tags)
                    assertEquals("sdk-linux-v1", parameters["comparison-series"])
                    assertEquals("1", parameters["compiler-seed"])
                    assertEquals("1", parameters["cpus"])
                } else if (name.startsWith("klause-reference")) {
                    assertEquals("[\"local\"]", recipe.getValue("tags").toString())
                }
                val reference = recipe["base"]?.jsonObject?.get("backend")?.jsonPrimitive?.content == "reference"
                val manifest = if (reference) "reference" else if (sweep) "bench-linux" else "bench"
                assertEquals("klause-bench/lab/$manifest.json", bench.getValue("manifest").jsonPrimitive.content)
                LabSettings(parameters)
            }
        }
    }
}
