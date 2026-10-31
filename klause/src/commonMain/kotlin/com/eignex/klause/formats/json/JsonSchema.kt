package com.eignex.klause.formats.json

import com.eignex.klause.formats.FormatException
import com.eignex.klause.model.ModelJson
import com.eignex.klause.model.SchemaEntry
import com.eignex.skema.SchemaDef
import com.eignex.skema.schemaJsonConfig
import com.eignex.skema.serialization.toJsonSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Raised when a schema or problem JSON document is malformed or uses an unknown field. */
open class JsonFormatException(msg: String, cause: Throwable? = null) : FormatException("JSON", msg, cause)

/** Schema JSON parser for `{"entries": {name: entry}}` documents in the [ModelJson] format. */
object JsonSchema {

    private val json: Json = Json {
        schemaJsonConfig()
    }

    private val serializer: KSerializer<SchemaDef<SchemaEntry>> = SchemaDef.serializer(SchemaEntry.serializer())

    /** Parse [text] into an immutable schema definition; errors name their JSON path, such as `$.entries.x.int`. */
    fun parse(text: String): SchemaDef<SchemaEntry> = try {
        json.decodeFromJsonElement(serializer, json.parseToJsonElement(text))
    } catch (e: SerializationException) {
        throw JsonFormatException(e.message ?: "malformed document", e)
    }

    /** JSON Schema (draft 2020-12) of the documents [parse] reads, derived from the same serializers. */
    fun jsonSchema(): JsonObject = serializer.toJsonSchema(ModelJson.catalog, json)
}
