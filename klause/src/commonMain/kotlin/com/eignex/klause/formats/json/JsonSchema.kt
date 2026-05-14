package com.eignex.klause.formats.json

import com.eignex.klause.ast.SchemaEntry
import com.eignex.klause.compile.CompiledProblem
import com.eignex.klause.compile.Compiler
import com.eignex.klause.solver.Problem
import com.eignex.skema.SchemaDef
import com.eignex.skema.schemaJsonConfig
import kotlinx.serialization.json.Json

/**
 * Parses a klause [SchemaDef] from its JSON representation and compiles it into a
 * solver-ready [Problem]. The JSON shape is the Skema configuration format —
 * `SchemaDef<SchemaEntry>` — that the [Compiler] consumes. See `schema/campaign.json` in
 * `klause-bench` for an example.
 */
object JsonSchema {

    private val json: Json = Json {
        schemaJsonConfig()
        ignoreUnknownKeys = true
    }

    /** Parse + compile in one step; returns only the [Problem]. */
    fun parseProblem(text: String): Problem = parseCompiled(text).problem

    /** Parse + compile, returning the full [CompiledProblem] so callers can decode samples. */
    fun parseCompiled(text: String): CompiledProblem {
        val def = json.decodeFromString(
            SchemaDef.serializer(SchemaEntry.serializer()),
            text,
        )
        return Compiler().compile(def)
    }
}
