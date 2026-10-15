package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import java.io.File
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object MixedMoveCompletionProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { argument ->
            val pair = argument.split('=', limit = 2)
            require(pair.size == 2) { "Use key=value arguments" }
            pair[0] to pair[1]
        }
        require(options.keys.all {
            it in setOf("input", "text64", "initial64", "exact", "instructions", "seed", "repeats", "timeout")
        })
        require((options["input"] != null) != (options["text64"] != null))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (Runtime.getRuntime().availableProcessors() != 1) {
            launchWithOneProcessor(args, out)
            return
        }
        val bytes = options["input"]?.let { CorpusFiles.open(File(it)).use { input -> input.readBytes() } }
            ?: Base64.getDecoder().decode(options.getValue("text64"))
        val initialBytes = Base64.getDecoder().decode(options.getValue("initial64"))
        out.resolve("input.mps").writeBytes(bytes)
        out.resolve("initial.json").writeBytes(initialBytes)
        val hash = sha256(bytes)
        out.resolve("sources").apply { mkdirs() }.resolve("$hash.txt").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val exact = options["exact"]?.toBooleanStrict() ?: false
        val instructions = options["instructions"]?.toLong() ?: 20L
        val seed = options["seed"]?.toLong() ?: 3L
        val repeats = options["repeats"]?.toInt() ?: 3
        val timeout = options["timeout"]?.toLong() ?: 3000L
        require(instructions > 0L && repeats in 1..100 && timeout > 0L)
        val parsed = Mps.parse(bytes.decodeToString())
        val hybrid = parsed.toProblem()
        val compiled = if (exact || (!hybrid.sourceExact && hybrid.model.numIntVars > 0)) {
            parsed.toExactSourceProblem()
        } else hybrid
        val original = parsed.toExactSourceProblem()
        val values = Json.parseToJsonElement(initialBytes.decodeToString()).jsonObject
            .mapValues { fraction(it.value.jsonPrimitive.content) }
        require(values.keys == compiled.columns.map { it.name }.toSet()) { "Supply every original column once" }
        val checkedInitial = original.sourceWitness(compiled.columns.map { values.getValue(it.name) })
        val ints = LongArray(compiled.model.numIntVars)
        val reals = MutableList(compiled.model.numRealVars) { BigFraction.ZERO }
        for (column in compiled.columns) {
            val value = values.getValue(column.name)
            if (column.real) reals[column.id] = value else {
                require(value.den == BigInteger.ONE) { "A finite coordinate must be integral" }
                ints[column.id] = value.num.longValueExact()
            }
        }
        val objectiveSpec = requireNotNull(compiled.objective)
        val mappedReals = compiled.columns.filter { it.real }.map { it.id }.toSet()
        val auxiliaries = reals.indices.filter { it !in mappedReals }
        if (auxiliaries.isNotEmpty()) {
            require(auxiliaries.size == 1 && compiled.objectiveScale == 1L && objectiveSpec.constant == 0L &&
                objectiveSpec.intCoefficients.all { it == 0L } && objectiveSpec.boolWeights.all { it == 0L } &&
                objectiveSpec.realCoefficients.indices.all {
                    objectiveSpec.realCoefficients[it] == if (it == auxiliaries.single()) 1.0 else 0.0
                }) { "Unmapped coordinates must be the source objective definition" }
            reals[auxiliaries.single()] = checkedInitial.objective
        }
        val initial = Sample(BooleanArray(compiled.model.numBoolVars), ints,
            DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals)
        val objective = objectiveSpec.toLinearObjective().let {
            if (compiled.maximize) it.negated() else it
        }
        val cases = mutableListOf<JsonObject>()
        val runs = mutableListOf<JsonObject>()
        out.resolve("settings.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSHA256", hash)
            put("initialSHA256", sha256(initialBytes))
            put("initialObjective", checkedInitial.objective.toString())
            put("exact", exact)
            put("instructions", instructions)
            put("seed", seed)
            put("repeats", repeats)
            put("timeoutMs", timeout)
            put("processors", Runtime.getRuntime().availableProcessors())
            put("runtimeArguments", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("objectiveAuxiliaries", auxiliaries.size)
            put("role", "seeded numerical LS incumbent comparison; no proof or whole-CLI timing")
        }.toString())
        repeat(repeats) { round ->
            val adapter = Class.forName("com.eignex.klause.portfolio.LeafRealCompletion")
            val tolerant = adapter.declaredConstructors.singleOrNull {
                it.parameterCount == 4 && it.parameterTypes[2].name == "kotlin.jvm.functions.Function1"
            }
            val constructor = (tolerant ?: adapter.declaredConstructors.single { it.parameterCount == 2 }).apply {
                isAccessible = true
            }
            val callback: ((Sample) -> Boolean)? = if (exact) null else { sample ->
                compiled.withinTolerance(sample.ints, sample.reals)
            }
            val completion = if (tolerant == null) {
                constructor.newInstance(compiled.model, objective)
            } else {
                val context = tolerant.parameterTypes.last()
                val companion = context.getField("Companion").get(null)
                val production = companion.javaClass.getMethod("getProduction").invoke(companion)
                constructor.newInstance(compiled.model, objective, callback, production)
            }
            val attempts = mutableListOf<JsonObject>()
            val observed = CandidateCompletion { candidate, cancellation ->
                val result = (completion as CandidateCompletion).complete(candidate, cancellation)
                attempts += buildJsonObject {
                    put("verdict", result::class.simpleName)
                    put("work", result.work)
                }
                result
            }
            val solver = LocalSearchSolver(compiled.model.bake(), completion = observed, greedyRepairOnRestart = false)
            val begun = System.nanoTime()
            val results = solver.improvements(objective, LocalSearchParams(
                maxInstructions = instructions, randomSeed = seed, initialAssignment = initial,
                cancellation = Cancellation.after(timeout.milliseconds),
            )).toList()
            for ((publication, result) in results.withIndex()) {
                val point = (result as? MinimizeResult.WithSample)?.sample ?: continue
                val sourceValues = compiled.columns.map { column ->
                    if (column.real) point.exactReals?.get(column.id)
                        ?: requireNotNull(BigFraction.ofDouble(point.approximateRealValue(column.id)))
                    else BigFraction.ofLong(point.intValue(column.id))
                }
                val checked = original.sourceWitness(sourceValues)
                val sourceCost = checked.objective
                val witness = "v " + compiled.columns.zip(sourceValues).joinToString(" ") { (column, value) ->
                    "${column.name}=$value"
                }
                cases += buildJsonObject {
                    put("index", cases.size)
                    put("status", "DONE")
                    put("arm", if (exact) "seeded-exact" else "seeded-default")
                    put("problem", buildJsonObject { put("suite", "mps-core"); put("problem", "supplied") })
                    put("record", buildJsonObject {
                        put("problem", "supplied")
                        put("gitSha", Reports.readGitSha())
                        put("sourceHashes", buildJsonObject { put("model", hash) })
                        put("kind", "optimize")
                        put("maximize", compiled.maximize)
                        put("feasible", true)
                        put("proven", false)
                        put("objective", sourceCost.toDouble())
                        put("exactObjective", sourceCost.toString())
                        put("finalWitness", witness)
                        put("stats", buildJsonObject {})
                    })
                    put("round", round)
                    put("publication", publication)
                    put("sourceGuarantee", "exact source feasible")
                }
            }
            val terminal = results.last()
            runs += buildJsonObject {
                put("round", round)
                put("elapsedNs", System.nanoTime() - begun)
                put("terminal", terminal::class.simpleName)
                put("reason", when (terminal) {
                    is MinimizeResult.BestFound -> terminal.reason.name
                    is MinimizeResult.Unknown -> terminal.reason.name
                    else -> null
                })
                put("moves", terminal.stats.ls.moves.sum)
                put("restarts", terminal.stats.search.restarts.sum)
                put("completions", terminal.stats.ls.completions.sum)
                put("refuted", terminal.stats.ls.completionsRefuted.sum)
                put("undecided", terminal.stats.ls.completionsUndecided.sum)
                put("attempts", JsonArray(attempts))
            }
            out.resolve("cases.json").writeText(JsonArray(cases).toString())
            out.resolve("runs.json").writeText(JsonArray(runs).toString())
        }
    }

    private fun launchWithOneProcessor(args: Array<String>, out: File) {
        val jvm = ManagementFactory.getRuntimeMXBean().inputArguments.filterNot {
            it.startsWith("-XX:ActiveProcessorCount=") || it.startsWith("-XX:StartFlightRecording=")
        }
        val command = listOf(File(System.getProperty("java.home"), "bin/java").absolutePath) + jvm + listOf(
            "-XX:ActiveProcessorCount=1",
            "-XX:StartFlightRecording=filename=${out.resolve("mixed-move-probe.jfr").absolutePath},settings=profile",
            "-cp", System.getProperty("java.class.path"), MixedMoveCompletionProbe::class.java.name,
        ) + args
        out.resolve("child-command.json").writeText(JsonArray(command.map(::JsonPrimitive)).toString())
        val child = ProcessBuilder(command).inheritIO().start()
        val exit = child.waitFor()
        out.resolve("child-exit.json").writeText(buildJsonObject { put("exit", exit) }.toString())
        check(exit == 0) { "Mixed move probe JVM failed" }
    }

    private fun fraction(text: String): BigFraction {
        val parts = text.split('/')
        if (parts.size == 2) return BigFraction.of(parseBigInt(parts[0]), parseBigInt(parts[1]))
        require(parts.size == 1)
        val decimal = BigDecimal(text)
        return if (decimal.scale() >= 0) {
            BigFraction.of(decimal.unscaledValue(), BigInteger.TEN.pow(decimal.scale()))
        } else BigFraction.of(decimal.unscaledValue().multiply(BigInteger.TEN.pow(-decimal.scale())), BigInteger.ONE)
    }
}
