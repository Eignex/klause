package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Sample
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Compare finite integer move predictions and commits with fresh whole-model grading. */
object IncrementalMoveProfile {
    /** Capture supplied source texts, assignments, moves and repair candidates without running a search. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys.all { it in setOf("sources", "seed") })
        val sources = Json.parseToJsonElement(requireNotNull(options["sources"])).jsonArray
        val seed = options["seed"]?.toLong() ?: 1L
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("sources.json").writeText(sources.toString())
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        out.resolve("runtime.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("java", System.getProperty("java.runtime.version"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("options", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put("seed", seed)
            put("sourcesSha256", hash(sources.toString().toByteArray()))
            put("classpath", buildJsonArray {
                for (path in System.getProperty("java.class.path").split(File.pathSeparator)) {
                    val file = File(path)
                    if (file.isFile) add(buildJsonObject {
                        put("name", file.name)
                        put("sha256", hash(file.readBytes()))
                    })
                }
            })
        }.toString())
        val results = buildJsonArray {
            for (entry in sources) {
                val source = entry.jsonObject
                require(source.keys == setOf("name", "format", "text", "ints", "bools", "moves"))
                val text = source.getValue("text").jsonPrimitive.content
                val problem = when (source.getValue("format").jsonPrimitive.content) {
                    "flatzinc" -> parseFlatZinc(text).problem
                    "smtlib" -> SmtLib.parse(text).model
                    "xcsp3" -> Xcsp3.parse(text).problem
                    else -> error("format must be flatzinc, smtlib or xcsp3")
                }
                require(problem.numRealVars == 0) { "finite integer grading requires no real coordinates" }
                val ints = source.getValue("ints").jsonArray.map { it.jsonPrimitive.long }.toLongArray()
                val bools = source.getValue("bools").jsonArray.map { it.jsonPrimitive.boolean }.toBooleanArray()
                require(ints.size == problem.numIntVars && bools.size == problem.numBoolVars)
                val initial = Sample(bools, ints)
                val state = fresh(problem, initial, seed)
                val moves = source.getValue("moves").jsonArray.map { move ->
                    val fields = move.jsonObject
                    require(fields.keys == setOf("id", "value"))
                    Move.IntSet(fields.getValue("id").jsonPrimitive.int, fields.getValue("value").jsonPrimitive.long)
                }.toMutableList<Move>()
                for (factorId in state.factors.indices) {
                    state.factors[factorId].proposeRepairMoves(state, factorId, state.moveSink)
                }
                val explicit = moves.size
                moves += state.moveSink.list
                for ((index, move) in moves.withIndex()) {
                    val after = fresh(problem, initial, seed)
                    write(after, move)
                    after.recompute()
                    val before = state.cost
                    val predicted = state.netDelta(move)
                    state.apply(move)
                    val committed = state.cost
                    val degreesAgree = state.factorDegree.contentEquals(after.factorDegree)
                    val assignmentsAgree = state.assignment.snapshot() == after.assignment.snapshot()
                    add(buildJsonObject {
                        put("name", source.getValue("name"))
                        put("sourceSha256", hash(text.toByteArray()))
                        put("index", index)
                        put("repair", index >= explicit)
                        put("move", move.toString())
                        put("before", before)
                        put("predicted", predicted)
                        put("committed", committed)
                        put("fresh", after.cost)
                        put("degreesAgree", degreesAgree)
                        put("assignmentsAgree", assignmentsAgree)
                        put("bools", buildJsonArray { after.assignment.snapshot().bools.forEach { add(JsonPrimitive(it)) } })
                        put("ints", buildJsonArray { after.assignment.snapshot().ints.forEach { add(JsonPrimitive(it)) } })
                        put("consistent", predicted == after.cost - before && committed == after.cost &&
                            degreesAgree && assignmentsAgree)
                    })
                    for (id in ints.indices) state.assignment.setInt(id, ints[id])
                    for (id in bools.indices) state.assignment.setBool(id, bools[id])
                    state.recompute()
                }
            }
        }
        out.resolve("moves.json").writeText(results.toString())
        check(results.all { it.jsonObject.getValue("consistent").jsonPrimitive.boolean }) {
            "incremental grading differs from fresh grading; see moves.json"
        }
        println("Checked ${results.size} moves against fresh grading")
    }

    private fun fresh(problem: Problem, sample: Sample, seed: Long): LocalSearchState {
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(seed))
        for (id in 0 until problem.numIntVars) {
            val value = sample.intValue(id)
            require(value in state.rootDomains[id])
            state.assignment.setInt(id, value)
        }
        for (id in 0 until problem.numBoolVars) state.assignment.setBool(id, sample.boolValue(id))
        state.recompute()
        return state
    }

    private fun write(state: LocalSearchState, move: Move) {
        when (move) {
            is Move.IntSet -> {
                require(move.newValue in state.rootDomains[move.varId])
                state.assignment.setInt(move.varId, move.newValue)
            }
            is Move.BoolFlip -> state.assignment.setBool(move.varId, !state.assignment.boolValue(move.varId))
            is Move.Compound -> move.parts.forEach { write(state, it) }
            is Move.RealSet -> error("real moves are outside finite integer grading")
        }
    }

    private fun hash(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
