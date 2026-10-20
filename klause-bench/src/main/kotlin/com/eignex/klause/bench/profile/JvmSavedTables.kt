package com.eignex.klause.bench.profile

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.util.JsonRecyclerPools
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.StringReader

internal class JvmSavedTables(raw: ByteArray) {
    private val text: String
    private val keys: List<List<String>>
    private val strings: List<String>
    private val valueOffset: Long

    init {
        require(raw.size <= JvmSavedReports.MAX_ROW_BYTES) { "Saved table exceeds record bound" }
        text = raw.decodeToString(throwOnInvalidSequence = true)
        val envelope = savedTableTokens { readEnvelope(text) }
        keys = envelope.keys
        strings = envelope.strings
        valueOffset = envelope.offset
    }

    fun project(fields: Set<String>, objectArrays: Map<String, Set<String>> = emptyMap()): JsonObject {
        require(objectArrays.keys.all { it in fields })
        return savedTableTokens {
            StringReader(text).use { source ->
                require(source.skip(valueOffset) == valueOffset)
                FACTORY.createParser(source).use { parser ->
                    require(parser.nextToken() == JsonToken.START_ARRAY) { "Saved root projection is not an object" }
                    decode(parser, SavedProjection(fields = fields, arrays = objectArrays)).jsonObject
                }
            }
        }
    }

    @Suppress("LongMethod")
    private fun decode(parser: JsonParser, root: SavedProjection): JsonElement {
        val stack = ArrayDeque<SavedTableFrame>()
        var projection = root
        var token = parser.currentToken()
        while (true) {
            var result: JsonElement?
            if (token == JsonToken.START_ARRAY) {
                val tag = integer(parser)
                if (stack.isEmpty() && projection === root) require(tag < 0) { "Saved root is not an object" }
                if (tag == 1L) {
                    val index = integer(parser)
                    require(index >= 0 && index < strings.size) { "Saved string reference is outside its table" }
                    require(parser.nextToken() == JsonToken.END_ARRAY) { "Saved string reference arity differs" }
                    result = if (projection.capture) JsonPrimitive(strings[index.toInt()]) else null
                } else {
                    require(tag <= 0) { "Unknown saved value tag" }
                    val names = if (tag == 0L) null else keys[objectIndex(tag)]
                    val frame = SavedTableFrame(names, projection)
                    val next = parser.nextToken()
                    if (next != JsonToken.END_ARRAY) {
                        stack.addLast(frame)
                        require(stack.size <= 4096) { "Saved table nesting exceeds bounds" }
                        projection = frame.childProjection()
                        token = next
                        continue
                    }
                    result = frame.finish()
                }
            } else {
                result = scalar(parser, token, projection.capture)
            }
            while (true) {
                if (stack.isEmpty()) return requireNotNull(result)
                val frame = stack.last()
                frame.accept(result)
                val next = parser.nextToken()
                if (next == JsonToken.END_ARRAY) {
                    result = frame.finish()
                    stack.removeLast()
                } else {
                    projection = frame.childProjection()
                    token = next
                    break
                }
            }
        }
    }

    private fun objectIndex(tag: Long): Int {
        val index = -(tag + 1)
        require(tag < 0 && index >= 0 && index < keys.size) { "Saved object key reference is outside its table" }
        return index.toInt()
    }

    private companion object {
        val FACTORY: JsonFactory = JsonFactoryBuilder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(4096)
                    .maxStringLength(JvmSavedReports.MAX_ROW_BYTES.toInt())
                    .maxNumberLength(JvmSavedReports.MAX_ROW_BYTES.toInt())
                    .maxNameLength(JvmSavedReports.MAX_ROW_BYTES.toInt())
                    .build(),
            )
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES)
            .disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
            .recyclerPool(JsonRecyclerPools.nonRecyclingPool())
            .build()

        fun readEnvelope(text: String): SavedTableEnvelope = FACTORY.createParser(text).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT) { "Saved envelope is not an object" }
            val seen = HashSet<String>()
            var keys: List<List<String>>? = null
            var strings: List<String>? = null
            var offset: Long? = null
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME)
                val name = requireNotNull(parser.currentName())
                require(seen.add(name)) { "Repeated saved envelope field" }
                parser.nextToken()
                when (name) {
                    "encoding" -> require(
                        parser.currentToken() == JsonToken.VALUE_STRING && parser.text == "key-and-string-tables-v1",
                    ) { "Saved table encoding differs" }
                    "objectKeys" -> keys = readKeys(parser)
                    "strings" -> strings = readStrings(parser)
                    "value" -> {
                        offset = parser.currentTokenLocation().charOffset
                        parser.skipChildren()
                    }
                    else -> throw IllegalArgumentException("Unknown saved envelope field")
                }
            }
            require(seen == setOf("encoding", "objectKeys", "strings", "value") && parser.nextToken() == null) {
                "Complete saved envelope field or document census differs"
            }
            SavedTableEnvelope(requireNotNull(keys), requireNotNull(strings), requireNotNull(offset))
        }

        fun readKeys(parser: JsonParser): List<List<String>> {
            require(parser.currentToken() == JsonToken.START_ARRAY)
            val result = ArrayList<List<String>>()
            while (parser.nextToken() != JsonToken.END_ARRAY) result.add(readStrings(parser))
            return result
        }

        fun readStrings(parser: JsonParser): List<String> {
            require(parser.currentToken() == JsonToken.START_ARRAY)
            val result = ArrayList<String>()
            val seen = HashSet<String>()
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                require(parser.currentToken() == JsonToken.VALUE_STRING) { "Saved dictionary value is not a string" }
                val value = parser.text
                require(seen.add(value)) { "Saved dictionary contains duplicates" }
                result.add(value)
            }
            return result
        }

        fun integer(parser: JsonParser): Long {
            require(parser.nextToken() == JsonToken.VALUE_NUMBER_INT) { "Saved tag or index is not an integer" }
            return parser.longValue
        }

        fun scalar(parser: JsonParser, token: JsonToken?, capture: Boolean): JsonElement? = when (token) {
            JsonToken.VALUE_NULL -> if (capture) JsonNull else null
            JsonToken.VALUE_TRUE -> if (capture) JsonPrimitive(true) else null
            JsonToken.VALUE_FALSE -> if (capture) JsonPrimitive(false) else null
            JsonToken.VALUE_NUMBER_INT -> if (capture) JvmSavedJson.parse(parser.text) else null
            JsonToken.VALUE_NUMBER_FLOAT -> {
                require(parser.doubleValue.isFinite()) { "Saved numeric value is not finite" }
                if (capture) JvmSavedJson.parse(parser.text) else null
            }
            else -> throw IllegalArgumentException("Untagged or missing saved value")
        }
    }
}

private class SavedTableEnvelope(val keys: List<List<String>>, val strings: List<String>, val offset: Long)

private class SavedProjection(
    val fields: Set<String>? = null,
    val arrays: Map<String, Set<String>> = emptyMap(),
    val elementFields: Set<String>? = null,
    val capture: Boolean = true,
) {
    companion object {
        val FULL = SavedProjection()
        val SKIP = SavedProjection(capture = false)
    }
}

private class SavedTableFrame(private val names: List<String>?, private val projection: SavedProjection) {
    private val array = if (projection.capture && names == null) ArrayList<JsonElement>() else null
    private val fields = if (projection.capture && names != null) LinkedHashMap<String, JsonElement>() else null
    private var position = 0

    fun childProjection(): SavedProjection {
        if (names != null) require(position < names.size) { "Saved object has excess values" }
        if (!projection.capture) return SavedProjection.SKIP
        if (names == null) return projection.elementFields?.let { SavedProjection(fields = it) } ?: SavedProjection.FULL
        val name = names[position]
        if (projection.fields != null && name !in projection.fields) return SavedProjection.SKIP
        return projection.arrays[name]?.let { SavedProjection(elementFields = it) } ?: SavedProjection.FULL
    }

    fun accept(value: JsonElement?) {
        if (childProjection().capture) {
            val captured = requireNotNull(value)
            if (names == null) {
                requireNotNull(array).add(captured)
            } else {
                requireNotNull(fields)[names[position]] = captured
            }
        }
        position++
    }

    fun finish(): JsonElement? {
        require(names == null || position == names.size) { "Saved object arity differs" }
        if (!projection.capture) return null
        return if (names == null) JsonArray(requireNotNull(array)) else JsonObject(requireNotNull(fields))
    }
}

private inline fun <T> savedTableTokens(block: () -> T): T = try {
    block()
} catch (failure: JacksonException) {
    throw IllegalArgumentException("Invalid saved table JSON", failure)
}
