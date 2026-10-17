package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class JvmSourceSyntaxEncoding {
    private val kinds = ArrayList<String>()
    private val kindIndices = HashMap<String, Int>()

    fun encode(row: JsonObject, defineKind: (Int, String) -> Unit): JsonArray {
        require(row.keys.toList() == NODE_COLUMNS)
        val kind = row.getValue("psiKind").jsonPrimitive.content
        val kindIndex = kindIndices[kind] ?: kinds.size.also { index ->
            require(index < MAX_KINDS)
            kindIndices[kind] = index
            kinds.add(kind)
            defineKind(index, kind)
        }
        val range = row.getValue("range").jsonObject
        require(range.keys.toList() == RANGE_COLUMNS)
        val encoded = JsonArray(NODE_COLUMNS.map { key ->
            when (key) {
                "psiKind" -> JsonPrimitive(kindIndex)
                "range" -> JsonArray(RANGE_COLUMNS.map(range::getValue))
                else -> row.getValue(key)
            }
        })
        check(expand(encoded).toString() == row.toString()) { "Source syntax record does not round trip" }
        return encoded
    }

    fun expand(row: JsonArray): JsonObject {
        require(row.size == NODE_COLUMNS.size)
        return JsonObject(NODE_COLUMNS.mapIndexed { index, key ->
            val value: JsonElement = when (key) {
                "psiKind" -> {
                    val kind = row[index].jsonPrimitive.int
                    require(kind in kinds.indices)
                    JsonPrimitive(kinds[kind])
                }
                "range" -> {
                    val range = row[index] as? JsonArray
                    require(range != null && range.size == RANGE_COLUMNS.size)
                    JsonObject(RANGE_COLUMNS.mapIndexed { position, column -> column to range[position] }.toMap())
                }
                else -> row[index]
            }
            key to value
        }.toMap())
    }

    companion object {
        const val ENCODING = "source-syntax-columns-v1"
        private const val MAX_KINDS = 4096
        val NODE_COLUMNS = listOf("index", "parentIndex", "kind", "psiKind", "name", "role", "package", "range",
            "syntaxError", "referencedName", "symbolResolutionProven", "jvmMemberOrGeneratedBodyInferred")
        val RANGE_COLUMNS = listOf("startUtf16", "endUtf16Exclusive", "startByte", "endByteExclusive",
            "firstLine", "lastLine", "sha256")
    }
}
