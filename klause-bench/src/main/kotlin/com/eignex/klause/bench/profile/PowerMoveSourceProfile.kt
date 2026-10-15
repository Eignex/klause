package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.ExactFlatZincValidation
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.SolveEvidence
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincCompatibility
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
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
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        if ("suite" in options) {
            captureCompilation(options)
            return
        }
        IncrementalMoveProfile.main(args)
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
    private fun captureCompilation(options: Map<String, String>) {
        require(options.keys == setOf("suite", "problem"))
        val ref = Catalog.uncapped(options.getValue("suite")).problems.single {
            it.name == options.getValue("problem")
        }
        val out = File(requireNotNull(System.getenv("OUT")))
        val original = CorpusFetcher.resolve(ref.source)
        original.copyTo(out.resolve("source.mzn"), overwrite = true)
        ref.data?.let { CorpusFetcher.resolve(it).copyTo(out.resolve("source.dzn"), overwrite = true) }
        val hashes = SolveEvidence.sourceHashes(ref)
        val compiled = MiniZincRunner().compileFzn(ref, fresh = true)
        compiled.copyTo(out.resolve("compiled.fzn"), overwrite = true)
        val program = parseFlatZinc(compiled.readText())
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        Reports.writeJson(out.resolve("compiler.json").path, MiniZincCompatibility.compiler)
        val factors = program.problem.factors
        out.resolve("retention.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("compilerExit", 0)
            put("solverInvocations", 0)
            put("sourceHashes", Reports.json.encodeToJsonElement(hashes))
            put("factors", buildJsonArray {
                for (factor in factors) add(buildJsonObject {
                    put("type", factor::class.simpleName)
                    if (factor is Power) {
                        put("base", factor.base.toString())
                        put("exponent", factor.exponent)
                        put("result", factor.result.toString())
                    }
                })
            })
        }.toString())
        check(hashes == SolveEvidence.sourceHashes(ref)) { "source changed during compilation" }
        check(factors.any { it is Power }) { "compiled source retains no native Power relation" }
        println("Captured original MiniZinc, compiled FlatZinc and native power relations")
    }

}
