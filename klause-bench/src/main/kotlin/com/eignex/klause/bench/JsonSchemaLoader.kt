package com.eignex.klause.bench

import com.eignex.klause.formats.json.JsonSchema

/** Loads bundled JSON `SchemaDef<SchemaEntry>` instances from
 *  `klause-bench/src/main/resources/schema/`, compiling each via [JsonSchema]. */
object JsonSchemaLoader {

    private val bundled: List<Bundled> = listOf(
        Bundled("campaign", expectedSat = true),
    )

    fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        val text = readResource("/schema/${meta.name}.json")
        Portfolio.Entry(meta.name, JsonSchema.parseProblem(text), meta.expectedSat)
    }

    fun loadFromPath(path: String, name: String, expectedSat: Boolean = true): Portfolio.Entry {
        val text = java.io.File(path).readText()
        return Portfolio.Entry(name, JsonSchema.parseProblem(text), expectedSat)
    }

    private fun readResource(path: String): String =
        JsonSchemaLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Bundled JSON schema resource not found: $path")

    private data class Bundled(val name: String, val expectedSat: Boolean)
}
