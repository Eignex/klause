package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

internal class JvmSavedTables(envelope: JsonObject) {
    private val keys: List<List<String>>
    private val strings: List<JsonPrimitive>
    private val root: JsonElement

    init {
        require(envelope.keys == setOf("encoding", "objectKeys", "strings", "value"))
        require(envelope["encoding"] == JsonPrimitive("canonical-json-key-and-string-tables-v1"))
        keys = envelope.getValue("objectKeys").jsonArray.map { row ->
            row.jsonArray.map { it.savedString() }.also { require(it.distinct().size == it.size) }
        }
        strings = envelope.getValue("strings").jsonArray.map { JsonPrimitive(it.savedString()) }
        require(strings.distinct().size == strings.size)
        root = envelope.getValue("value")
        validate(root)
    }

    fun project(fields: Set<String>): JsonObject = projectObject(root, fields)

    fun objectArray(name: String, fields: Set<String>): JsonArray {
        val value = objectValues(root).single { it.first == name }.second
        require(value is JsonArray && savedInteger(value.first()) == 0L)
        return JsonArray(value.drop(1).map { projectObject(it, fields) })
    }

    private fun projectObject(value: JsonElement, fields: Set<String>): JsonObject =
        JsonObject(objectValues(value).filter { it.first in fields }.associate { (name, child) ->
            name to expand(child)
        })

    private fun objectValues(value: JsonElement): List<Pair<String, JsonElement>> {
        require(value is JsonArray && savedInteger(value.first()) < 0)
        return keys[objectIndex(savedInteger(value.first()))].mapIndexed { index, name -> name to value[index + 1] }
    }

    private fun objectIndex(tag: Long): Int {
        val index = -(tag + 1)
        require(tag < 0 && index >= 0 && index < keys.size)
        return index.toInt()
    }

    private fun validate(value: JsonElement) {
        val stack = ArrayDeque<ValidationFrame>()
        fun enter(child: JsonElement) {
            when (child) {
                is JsonArray -> {
                    require(child.isNotEmpty()) { "Missing saved value tag" }
                    val tag = savedInteger(child.first())
                    if (tag == 1L) {
                        require(child.size == 2)
                        require(savedInteger(child[1]) >= 0 && savedInteger(child[1]) < strings.size)
                    } else {
                        require(tag == 0L || (tag < 0 && child.size == keys[objectIndex(tag)].size + 1))
                        stack.addLast(ValidationFrame(child))
                        require(stack.size <= 4096) { "Saved table nesting exceeds bounds" }
                    }
                }

                is JsonPrimitive -> require(
                    child == JsonNull || (!child.isString && (
                        child.content in setOf("true", "false") || INTEGER.matches(child.content) ||
                            child.content.toDoubleOrNull()?.isFinite() == true
                        )),
                ) { "Untagged or unsupported saved value" }

                else -> throw IllegalArgumentException("Untagged saved object")
            }
        }
        enter(value)
        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (frame.position == frame.value.size) stack.removeLast() else enter(frame.value[frame.position++])
        }
    }

    private fun expand(value: JsonElement): JsonElement {
        val stack = ArrayDeque<ExpansionFrame>()
        var pending = value
        while (true) {
            var result: JsonElement
            if (pending is JsonArray) {
                val tag = savedInteger(pending.first())
                if (tag == 1L) {
                    result = strings[savedInteger(pending[1]).toInt()]
                } else {
                    val frame = ExpansionFrame(pending, tag)
                    if (pending.size > 1) {
                        stack.addLast(frame)
                        pending = pending[frame.position++]
                        continue
                    }
                    result = frame.result()
                }
            } else {
                result = pending
            }
            while (true) {
                if (stack.isEmpty()) return result
                val frame = stack.last()
                frame.children.add(result)
                if (frame.position < frame.value.size) {
                    pending = frame.value[frame.position++]
                    break
                }
                stack.removeLast()
                result = frame.result()
            }
        }
    }

    private class ValidationFrame(val value: JsonArray, var position: Int = 1)

    private inner class ExpansionFrame(val value: JsonArray, val tag: Long, var position: Int = 1) {
        val children = ArrayList<JsonElement>()

        fun result(): JsonElement =
            if (tag == 0L) JsonArray(children) else JsonObject(keys[objectIndex(tag)].zip(children).toMap())
    }

    private companion object {
        val INTEGER = Regex("-?(0|[1-9][0-9]*)")
    }
}
