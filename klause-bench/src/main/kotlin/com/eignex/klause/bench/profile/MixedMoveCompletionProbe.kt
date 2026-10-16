package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.CostShaping
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

internal object MixedMoveCompletionProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { argument ->
            val pair = argument.split('=', limit = 2)
            require(pair.size == 2) { "Use key=value arguments" }
            pair[0] to pair[1]
        }
        require(options.keys.all {
            it in setOf("input", "text64", "suite", "problem", "sourceSHA256", "initial64", "exact",
                "instructions", "seed", "repeats", "timeout", "lowering", "residualObjective", "lambda")
        })
        require(listOf(options["input"], options["text64"], options["suite"]).count { it != null } == 1)
        require((options["suite"] == null) == (options["problem"] == null))
        require(options["text64"] != null || options["sourceSHA256"] != null) { "Pin original input sourceSHA256" }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        if (Runtime.getRuntime().availableProcessors() != 1) {
            launchWithOneProcessor(args, out)
            return
        }
        val input = options["input"]?.let(::File) ?: options["suite"]?.let { suite ->
            val entry = Catalog.uncapped(suite).problems.single { it.name == options.getValue("problem") }
            require(entry.format == Format.MPS) { "A mixed completion probe requires original MPS" }
            CorpusFetcher.resolve(entry.source)
        }
        val bytes = input?.let { CorpusFiles.open(it).use { source -> source.readBytes() } }
            ?: Base64.getDecoder().decode(options.getValue("text64"))
        val initialBytes = options["initial64"]?.let { Base64.getDecoder().decode(it) }
        out.resolve("input.mps").writeBytes(bytes)
        initialBytes?.let { out.resolve("initial.json").writeBytes(it) }
        val hash = sha256(bytes)
        require(options["sourceSHA256"] == null || options["sourceSHA256"] == hash) { "Original source hash differs" }
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
        val lowering = options["lowering"] ?: "exact-source"
        require(lowering in setOf("cli", "exact-source"))
        val compiled = if ((exact && (lowering == "exact-source" || !hybrid.sourceExact)) ||
            (!hybrid.sourceExact && hybrid.model.numIntVars > 0)
        ) {
            parsed.toExactSourceProblem()
        } else hybrid
        val original = parsed.toExactSourceProblem()
        val values = initialBytes?.let { Json.parseToJsonElement(it.decodeToString()).jsonObject
            .mapValues { entry -> fraction(entry.value.jsonPrimitive.content) } }
        require(values == null || values.keys == compiled.columns.map { it.name }.toSet()) { "Supply every original column once" }
        val checkedInitial = values?.let { supplied -> runCatching {
            original.sourceWitness(compiled.columns.map { supplied.getValue(it.name) })
        }.getOrNull() }
        val ints = LongArray(compiled.model.numIntVars)
        val reals = MutableList(compiled.model.numRealVars) { BigFraction.ZERO }
        if (values != null) for (column in compiled.columns) {
            val value = values.getValue(column.name)
            if (column.real) reals[column.id] = value else {
                require(value.den == BigInteger.ONE) { "A finite coordinate must be integral" }
                ints[column.id] = value.num.longValueExact()
            }
        }
        val initialReals = DoubleArray(reals.size) { reals[it].toDouble() }
        require(values == null || checkedInitial != null || (!exact && compiled.withinTolerance(ints, initialReals))) {
            "Initial point fails its configured original source guarantee"
        }
        val initialCost = if (values == null) null else checkedInitial?.objective
            ?: requireNotNull(compiled.sourceObjective(ints, initialReals))
        val objectiveSpec = requireNotNull(compiled.objective)
        val mappedReals = compiled.columns.filter { it.real }.map { it.id }.toSet()
        val auxiliaries = reals.indices.filter { it !in mappedReals }
        if (auxiliaries.isNotEmpty()) {
            require(auxiliaries.size == 1 && compiled.objectiveScale == 1L && objectiveSpec.constant == 0L &&
                objectiveSpec.intCoefficients.all { it == 0L } && objectiveSpec.boolWeights.all { it == 0L } &&
                objectiveSpec.realCoefficients.indices.all {
                    objectiveSpec.realCoefficients[it] == if (it == auxiliaries.single()) 1.0 else 0.0
                }) { "Unmapped coordinates must be the source objective definition" }
            if (initialCost != null) reals[auxiliaries.single()] = initialCost
        }
        val initial = if (values == null) null else Sample(BooleanArray(compiled.model.numBoolVars), ints,
            DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals)
        val objective = objectiveSpec.toLinearObjective().let {
            if (compiled.maximize) it.negated() else it
        }
        val residualObjective = options["residualObjective"] ?: "source"
        require(residualObjective in setOf("source", "feasibility"))
        val lambda = options["lambda"]?.toDouble()?.also { require(it.isFinite() && it >= 0.0) }
        val cases = mutableListOf<JsonObject>()
        val runs = mutableListOf<JsonObject>()
        out.resolve("settings.json").writeText(buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSHA256", hash)
            put("initialSHA256", initialBytes?.let(::sha256))
            put("initialObjective", initialCost?.toString())
            put("initialSourceGuarantee", when {
                values == null -> "absent"
                checkedInitial != null -> "exact source feasible"
                else -> "source tolerance"
            })
            put("exact", exact)
            put("lowering", lowering)
            put("sourceExact", compiled.sourceExact)
            put("residualObjective", residualObjective)
            put("lambda", lambda)
            put("instructions", instructions)
            put("seed", seed)
            put("repeats", repeats)
            put("timeoutMs", timeout)
            put("processors", Runtime.getRuntime().availableProcessors())
            put("runtimeArguments", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("objectiveAuxiliaries", auxiliaries.size)
            put("role", "numerical LS completion diagnostic; no proof or whole-CLI timing")
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
                constructor.newInstance(compiled.model, objective.takeIf { residualObjective == "source" })
            } else {
                val context = tolerant.parameterTypes.last()
                val companion = context.getField("Companion").get(null)
                val production = companion.javaClass.getMethod("getProduction").invoke(companion)
                constructor.newInstance(compiled.model, objective.takeIf { residualObjective == "source" }, callback, production)
            }
            val attempts = mutableListOf<JsonObject>()
            var lpVerdict: String? = null
            var lpStats: LpStats? = null
            var floatPoint: Sample? = null
            val observe: (Any, LpStats) -> Unit = { verdict, stats ->
                lpVerdict = verdict.toString()
                lpStats = stats
            }
            adapter.methods.singleOrNull { it.name.startsWith("setObserveLp") }?.invoke(completion, observe)
            val observeFloat: (Sample) -> Unit = { floatPoint = it }
            adapter.methods.singleOrNull { it.name.startsWith("setObserveFloat") }?.invoke(completion, observeFloat)
            val observed = CandidateCompletion { candidate, cancellation ->
                lpVerdict = null
                lpStats = null
                floatPoint = null
                val started = System.nanoTime()
                val result = (completion as CandidateCompletion).complete(candidate, cancellation)
                val elapsed = System.nanoTime() - started
                val finite = buildJsonObject {
                    put("booleans", JsonArray(candidate.bools.map(::JsonPrimitive)))
                    put("integers", JsonArray(candidate.ints.map(::JsonPrimitive)))
                }
                attempts += buildJsonObject {
                    put("elapsedNs", elapsed)
                    put("finiteSHA256", sha256(finite.toString().toByteArray()))
                    if (attempts.size < 16) put("finite", finite)
                    put("lpVerdict", lpVerdict)
                    put("lpStats", lpStats?.toString())
                    floatPoint?.let { point ->
                        val sourceValues = compiled.columns.map { column ->
                            if (column.real) requireNotNull(BigFraction.ofDouble(point.approximateRealValue(column.id)))
                            else BigFraction.ofLong(point.intValue(column.id))
                        }
                        val checked = runCatching { original.sourceWitness(sourceValues) }.getOrNull()
                        put("floatExactlySourceFeasible", checked != null)
                        if (attempts.size < 16) {
                            put("floatWitness", "v " + compiled.columns.zip(sourceValues).joinToString(" ") {
                                (column, value) -> "${column.name}=$value"
                            })
                            put("floatSourceObjective", checked?.objective?.toString())
                        }
                    }
                    put("verdict", result::class.simpleName)
                    put("work", result.work)
                }
                result
            }
            val solver = LocalSearchSolver(compiled.model.bake(), completion = observed,
                greedyRepairOnRestart = initial == null)
            val begun = System.nanoTime()
            val results = solver.improvements(objective, LocalSearchParams(
                maxInstructions = instructions, randomSeed = seed, initialAssignment = initial,
                costShaping = lambda?.let { CostShaping.Linear(it) } ?: CostShaping.FeasibilityFirst,
                normalizeWeightsByClass = lambda != null,
                cancellation = Cancellation.after(timeout.milliseconds),
            )).toList()
            for ((publication, result) in results.withIndex()) {
                val point = (result as? MinimizeResult.WithSample)?.sample ?: continue
                val sourceValues = compiled.columns.map { column ->
                    if (column.real) point.exactReals?.get(column.id)
                        ?: requireNotNull(BigFraction.ofDouble(point.approximateRealValue(column.id)))
                    else BigFraction.ofLong(point.intValue(column.id))
                }
                val checked = runCatching { original.sourceWitness(sourceValues) }.getOrNull()
                check(checked != null || (!exact && compiled.withinTolerance(point.ints, point.reals))) {
                    "Published point fails its configured original source guarantee"
                }
                val sourceCost = checked?.objective ?: requireNotNull(compiled.sourceObjective(point.ints, point.reals))
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
                    put("sourceGuarantee", if (checked != null) "exact source feasible" else "source tolerance")
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
