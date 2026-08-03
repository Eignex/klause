package com.eignex.klause.formats.json

import com.eignex.klause.compile.CompiledSchema
import com.eignex.klause.compile.Compiler
import com.eignex.klause.formats.FormatException
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.solver.Problem
import com.eignex.skema.SchemaDef
import com.eignex.skema.schemaJsonConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Raised when a schema JSON document is malformed or uses an unknown field. */
class JsonFormatException(msg: String, cause: Throwable? = null) : FormatException("JSON", msg, cause)

/** Parse and compile schema JSON into solver structures. */
object JsonSchema {

    private val json: Json = Json {
        schemaJsonConfig()
    }

    /** Parse and compile, returning only the [Problem]. */
    fun parseProblem(text: String): Problem = parseCompiled(text).problem

    /** Parse and compile, returning the full [CompiledSchema]. */
    fun parseCompiled(text: String): CompiledSchema = Compiler().compile(decode(text))

    private fun decode(text: String): SchemaDef<SchemaEntry> = try {
        json.decodeFromString(SchemaDef.serializer(SchemaEntry.serializer()), text)
    } catch (e: SerializationException) {
        throw JsonFormatException(e.message ?: "malformed document", e)
    }
}
