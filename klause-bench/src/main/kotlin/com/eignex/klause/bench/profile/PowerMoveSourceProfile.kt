package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.ExactFlatZincValidation
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Compare finite power move grading with independent exact original FlatZinc predicates. */
object PowerMoveSourceProfile {
    /** Accept the incremental move profile's source matrix and retain every original-source check. */
    @JvmStatic
    fun main(args: Array<String>) {
        IncrementalMoveProfile.main(args)
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val sources = Json.parseToJsonElement(requireNotNull(options["sources"])).jsonArray
        val named = sources.associateBy { it.jsonObject.getValue("name").jsonPrimitive.content }
        require(named.size == sources.size) { "source names must be unique" }
        val programs = named.mapValues { (_, source) ->
            val fields = source.jsonObject
            require(fields.getValue("format").jsonPrimitive.content == "flatzinc")
            parseFlatZinc(fields.getValue("text").jsonPrimitive.content)
        }
        val out = File(requireNotNull(System.getenv("OUT")))
        val moves = Json.parseToJsonElement(out.resolve("moves.json").readText()).jsonArray
        var valid = true
        val checks = buildJsonArray {
            for (entry in moves) {
                val move = entry.jsonObject
                val name = move.getValue("name").jsonPrimitive.content
                val program = programs.getValue(name)
                val ints = move.getValue("ints").jsonArray
                val bools = move.getValue("bools").jsonArray
                val coordinates = buildString {
                    for ((variable, id) in program.intVarsByName) append("$variable = ${ints[id]};\n")
                    for ((variable, id) in program.boolVarsByName) append("$variable = ${bools[id]};\n")
                }
                val source = named.getValue(name).jsonObject.getValue("text").jsonPrimitive.content
                val checked = ExactFlatZincValidation.inspect(source, coordinates, null)
                val sourceValid = checked.status == "valid"
                val complete = checked.status == "valid" || checked.status == "invalid"
                val agrees = complete && sourceValid == (move.getValue("fresh").jsonPrimitive.long == 0L)
                valid = valid && agrees
                add(buildJsonObject {
                    put("name", name)
                    put("index", move.getValue("index").jsonPrimitive.int)
                    put("sourceSha256", move.getValue("sourceSha256"))
                    put("coordinates", coordinates)
                    put("status", checked.status)
                    put("reason", checked.reason)
                    put("scope", checked.scope)
                    put("gradingAgrees", agrees)
                })
            }
        }
        out.resolve("source-checks.json").writeText(checks.toString())
        check(valid) { "power grading and complete original-source checks disagree; see source-checks.json" }
        println("Checked ${checks.size} power moves against original FlatZinc predicates")
    }
}
