package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.MiniZincRunner
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.formats.flatzinc.FlatZincArray
import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZincExecution
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.random.Random

/** Inspect bounded Diffn proposals at a supplied integer MiniZinc witness on a remote profile host. */
object DiffnNeighborhoodProbe {
    /** Load named scalar/array values, grade candidates and retain source/runtime identities under `$OUT`. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "values64", "modelHash", "dataHash", "objective", "draws"))
        val draws = requireNotNull(options["draws"]?.toIntOrNull()).also { require(it in 1..256) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val encoded = requireNotNull(options["values64"])
        require(encoded.length <= 256 * 1024)
        val values = Base64.getDecoder().decode(encoded)
        out.resolve("values.json").writeBytes(values)
        val ref = Catalog.uncapped(requireNotNull(options["suite"])).problems.single {
            it.name == options["problem"]
        }
        val model = CorpusFetcher.resolve(ref.source)
        val data = ref.data?.let(CorpusFetcher::resolve)
        require(hash(model.readBytes()) == options["modelHash"])
        require(data != null && hash(data.readBytes()) == options["dataHash"])
        model.copyTo(out.resolve("source.mzn"))
        data.copyTo(out.resolve("source-data.${data.extension}"))
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        val compiler = ProcessBuilder("minizinc", "--version").redirectErrorStream(true)
            .redirectOutput(out.resolve("minizinc-version.txt")).start()
        if (!compiler.waitFor(10, TimeUnit.SECONDS)) {
            compiler.destroyForcibly()
            error("compiler identity unavailable")
        }
        check(compiler.exitValue() == 0)
        val fzn = MiniZincRunner().compileFzn(ref)
        fzn.copyTo(out.resolve("source.fzn"))
        val execution = parseFlatZincExecution(fzn.readText())
        val program = execution.program
        require(program.problem.numRealVars == 0 && program.floatVarsByName.isEmpty())
        val directive = program.solve
        val objectiveName = when (directive) {
            is SolveDirective.Minimize -> directive.objVar.also { require(directive.kind == SolveDirective.ObjKind.Int) }
            is SolveDirective.Maximize -> directive.objVar.also { require(directive.kind == SolveDirective.ObjKind.Int) }
            else -> error("integer optimization source required")
        }
        val objectiveVar = requireNotNull(program.intVarsByName[objectiveName])
        val state = LocalSearchState(program.problem.bake(), Random(3))
        val pins = requireNotNull(state.model.pinsUnder(Assumptions.None))
        state.assumptions = pins
        state.moveSink.setAssumptions(pins)
        for (v in state.rootDomains.indices) state.assignment.setInt(v, state.rootDomains[v].min)
        pins.forEachBool { v, value -> state.assignment.setBool(v, value) }
        pins.forEachInt { v, value -> state.assignment.setInt(v, value) }
        loadValues(program, state, Json.parseToJsonElement(values.decodeToString()).jsonObject)
        execution.definitionalSweep?.sweep(state.assignment, state.rootDomains, state.problem.factors)
        state.invariants = execution.definitionalSweep?.network(state.problem.numIntVars, state.problem.numBoolVars)
        state.recompute()
        val initialObjective = state.assignment.intValue(objectiveVar)
        require(initialObjective.toString() == options["objective"])
        val beforeInts = LongArray(state.problem.numIntVars) { state.assignment.intValue(it) }
        val beforeBools = BooleanArray(state.problem.numBoolVars) { state.assignment.boolValue(it) }
        val proposals = buildJsonArray {
            if (state.cost == 0L) {
                for ((factorId, factor) in state.problem.factors.withIndex()) {
                    if (factor !is Diffn) continue
                    repeat(draws) { draw ->
                        val sink = state.moveSink
                        sink.clear()
                        sink.proposer = factorId
                        state.factors[factorId].proposeStructuredMoves(state, factorId, sink)
                        for (move in sink.list) {
                            val predicted = state.netDelta(move)
                            state.apply(move)
                            val cost = state.cost
                            val objective = state.assignment.intValue(objectiveVar)
                            add(buildJsonObject {
                                put("factor", factorId)
                                put("draw", draw)
                                put("move", move.toString())
                                put("cost", cost)
                                put("predictedCost", predicted)
                                put("objective", objective.toString())
                                put("objectiveDelta", BigInteger.valueOf(objective).subtract(
                                    BigInteger.valueOf(initialObjective)).toString())
                            })
                            for (v in beforeInts.indices) state.assignment.setInt(v, beforeInts[v])
                            for (v in beforeBools.indices) state.assignment.setBool(v, beforeBools[v])
                            state.recompute()
                            check(state.cost == 0L && predicted == cost)
                        }
                    }
                }
            }
        }
        out.resolve("proposals.json").writeText(buildJsonObject {
            put("suite", options["suite"])
            put("problem", options["problem"])
            put("modelHash", hash(model.readBytes()))
            put("dataHash", hash(data.readBytes()))
            put("fznHash", hash(fzn.readBytes()))
            put("valuesHash", hash(values))
            put("initialCost", state.cost)
            put("status", if (state.cost == 0L) "graded" else "declined-incomplete-or-invalid-compiled-assignment")
            put("initialObjective", initialObjective.toString())
            put("maximize", directive is SolveDirective.Maximize)
            put("factors", state.problem.numFactors)
            put("diffnFactors", state.problem.factors.count { it is Diffn })
            put("proposals", proposals)
        }.toString() + "\n")
        println("initial cost=${state.cost}, proposals=${proposals.size}")
    }

    private fun loadValues(program: FlatZincProgram, state: LocalSearchState, values: Map<String, JsonElement>) {
        val assignedInts = mutableMapOf<Int, Long>()
        val assignedBools = mutableMapOf<Int, Boolean>()
        fun bindInt(v: Int, value: JsonElement) {
            val target = value.jsonPrimitive.long
            require(target in state.rootDomains[v])
            require(state.assumptions.intValueOrNull(v)?.let { it == target } != false)
            val previous = assignedInts.put(v, target)
            require(previous == null || previous == target) { "conflicting integer aliases" }
            state.assignment.setInt(v, target)
        }
        fun bindBool(v: Int, value: JsonElement) {
            val target = value.jsonPrimitive.boolean
            require(state.assumptions.boolValueOrNull(v)?.let { it == target } != false)
            val previous = assignedBools.put(v, target)
            require(previous == null || previous == target) { "conflicting Boolean aliases" }
            state.assignment.setBool(v, target)
        }
        for ((name, value) in values) {
            when {
                name in program.intVarsByName -> bindInt(program.intVarsByName.getValue(name), value)
                name in program.boolVarsByName -> bindBool(program.boolVarsByName.getValue(name), value)
                else -> {
                    val array = program.arraysByName[name] as? FlatZincArray.Vars
                        ?: error("unknown variable or variable array $name")
                    require(value is JsonArray && value.size == array.length)
                    for ((index, v) in array.varIds.withIndex()) {
                        when (array.elementKind) {
                            FlatZincArray.Vars.ElementKind.Int -> bindInt(v, value.jsonArray[index])
                            FlatZincArray.Vars.ElementKind.Bool -> bindBool(v, value.jsonArray[index])
                            else -> error("float arrays are unsupported")
                        }
                    }
                }
            }
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
