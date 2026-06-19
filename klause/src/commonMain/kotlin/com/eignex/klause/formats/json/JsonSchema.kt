package com.eignex.klause.formats.json

import com.eignex.klause.compile.CompiledProblem
import com.eignex.klause.compile.Compiler
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.solver.Problem
import com.eignex.skema.SchemaDef
import com.eignex.skema.schemaJsonConfig
import kotlinx.serialization.json.Json

/** Parse and compile schema JSON into solver structures. */
object JsonSchema {

    private val json: Json = Json {
        schemaJsonConfig()
        ignoreUnknownKeys = true
    }

    /** Parse and compile, returning only the [Problem]. */
    fun parseProblem(text: String): Problem = parseCompiled(text).problem

    /** Parse and compile, returning the full [CompiledProblem]. */
    fun parseCompiled(text: String): CompiledProblem {
        val def = json.decodeFromString(
            SchemaDef.serializer(SchemaEntry.serializer()),
            text,
        )
        return Compiler().compile(def)
    }
}
