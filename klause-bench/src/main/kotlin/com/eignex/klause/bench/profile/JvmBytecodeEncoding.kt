package com.eignex.klause.bench.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal object JvmBytecodeEncoding {
    const val ENCODING = "bytecode-columns-v1"
    val columns = linkedMapOf(
        "constantPool" to listOf("index", "tag", "width", "references", "bootstrapIndex", "methodHandleKind",
            "symbolicText"),
        "instructions" to listOf("bci", "opcode", "byteLength", "constantPoolIndex", "localIndex", "branchTargets",
            "switchPaddingBytes", "subroutineReturnTargetUnresolved"),
        "branchTargets" to listOf("kind", "bci", "caseKey"),
        "exceptionHandlers" to listOf("startBci", "endBciExclusive", "handlerBci", "catchType"),
        "lineNumberTables" to listOf("startPc", "lineNumber", "instructionBoundary"),
    )
    private val json = Json { encodeDefaults = true }

    fun encode(metadata: JvmBytecodeMetadata): JsonObject {
        val original = json.encodeToJsonElement(JvmBytecodeMetadata.serializer(), metadata).jsonObject
        val encoded = transform(original, true).jsonObject
        check(transform(encoded, false).toString() == original.toString()) { "Bytecode columns do not round trip" }
        return encoded
    }

    internal fun expand(metadata: JsonObject): JsonObject = transform(metadata, false).jsonObject

    private fun transform(value: JsonElement, encode: Boolean): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { transform(it, encode) })
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            val names = columns[key]
            if (names == null) transform(child, encode) else {
                fun row(input: JsonElement): JsonElement = if (encode) {
                    val fields = transform(input, true).jsonObject
                    require(fields.keys.toList() == names) { "Unexpected bytecode record columns" }
                    JsonArray(names.map(fields::getValue))
                } else {
                    val fields = input.jsonArray
                    require(fields.size == names.size) { "Unexpected bytecode record width" }
                    transform(JsonObject(names.mapIndexed { index, name -> name to fields[index] }.toMap()), false)
                }
                JsonArray(child.jsonArray.map { input ->
                    if (key == "lineNumberTables") JsonArray(input.jsonArray.map(::row)) else row(input)
                })
            }
        })
        else -> value
    }
}
