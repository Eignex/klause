package com.eignex.klause.formats.json

import com.eignex.klause.formats.FormatException
import com.eignex.klause.model.SchemaEntry
import com.eignex.skema.SchemaDef
import com.eignex.skema.schemaJsonConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Raised when a schema JSON document is malformed or uses an unknown field. */
class JsonFormatException(msg: String, cause: Throwable? = null) : FormatException("JSON", msg, cause)

/** Parses a schema JSON document into its source representation. */
object JsonSchema {

    private val json: Json = Json {
        schemaJsonConfig()
    }

    /** Decodes JSON syntax and schema entries without lowering them into solver IR. */
    fun decode(text: String): SchemaDef<SchemaEntry> = try {
        json.decodeFromString(SchemaDef.serializer(SchemaEntry.serializer()), text)
    } catch (e: SerializationException) {
        throw JsonFormatException(e.message ?: "malformed document", e)
    }
}
