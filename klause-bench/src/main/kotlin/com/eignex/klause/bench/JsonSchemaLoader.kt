package com.eignex.klause.bench

import com.eignex.klause.ast.SchemaEntry
import com.eignex.klause.compile.Compiler
import com.eignex.klause.solver.Problem
import com.eignex.skema.SchemaDef
import com.eignex.skema.schemaJsonConfig
import kotlinx.serialization.json.Json

/** Loads bundled JSON `SchemaDef<SchemaEntry>` instances from
 *  `klause-bench/src/main/resources/schema/`, compiling each via [Compiler]. */
object JsonSchemaLoader {

    private val json: Json = Json { schemaJsonConfig(); ignoreUnknownKeys = true }

    private val bundled: List<Bundled> = listOf(
        Bundled("campaign", expectedSat = true),
    )

    fun loadBundled(): List<Portfolio.Entry> = bundled.map { meta ->
        val text = readResource("/schema/${meta.name}.json")
        Portfolio.Entry(meta.name, parseToProblem(text), meta.expectedSat)
    }

    fun loadFromPath(path: String, name: String, expectedSat: Boolean = true): Portfolio.Entry {
        val text = java.io.File(path).readText()
        return Portfolio.Entry(name, parseToProblem(text), expectedSat)
    }

    private fun parseToProblem(text: String): Problem {
        val def = json.decodeFromString(SchemaDef.serializer(SchemaEntry.serializer()), text)
        return Compiler().compile(def).problem
    }

    private fun readResource(path: String): String =
        JsonSchemaLoader::class.java.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Bundled JSON schema resource not found: $path")

    private data class Bundled(val name: String, val expectedSat: Boolean)
}
