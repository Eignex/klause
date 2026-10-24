package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.checkMpsWitness
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object ContinuousCompletionProfile {
    private const val ENGINE = "com.eignex.klause.lp.engine."
    private const val LIMIT = 16

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single().startsWith("request64="))
        val bytes = Base64.getDecoder().decode(args.single().substringAfter('='))
        require(bytes.size <= 48 * 1024)
        val requests = Reports.json.parseToJsonElement(bytes.decodeToString()).jsonArray
        require(requests.size in 1..8)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        out.resolve("request.json").writeBytes(bytes)
        requests.forEachIndexed { index, request ->
            capture(request.jsonObject, out.resolve("$index").apply { mkdirs() })
        }
    }

    private fun capture(input: JsonObject, out: File) {
        require(input.keys == setOf("suite", "problem", "sha256", "exact"))
        val reference = Catalog.uncapped(input.getValue("suite").jsonPrimitive.content).problems
            .single { it.name == input.getValue("problem").jsonPrimitive.content }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use {
            it.readNBytes(16 * 1024 * 1024 + 1)
        }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == input.getValue("sha256").jsonPrimitive.content)
        out.resolve("source.mps").writeBytes(bytes)
        val exact = input.getValue("exact").jsonPrimitive.content.toBooleanStrict()
        val overrides = if (exact) SemanticOverrides(feasibilityTolerance = SourceTolerance()) else SemanticOverrides()
        val parsed = Mps.parse(bytes.decodeToString())
        val source = requireNotNull(parsed.toLinearSourceProblem(overrides))
        val compiled = parsed.toProblem(semanticOverrides = overrides)
        val problem = compiled.model.bake()
        val objective = requireNotNull(compiled.objective).toLinearObjective().let {
            if (compiled.maximize) it.negated() else it
        }
        val accepts: (Sample) -> Boolean = { sample -> compiled.withinTolerance(sample.ints, sample.reals) }
        val acceptanceType = Class.forName("com.eignex.klause.solver.validation.FiniteSourceAcceptance")
        val acceptance = acceptanceType.constructors.single().newInstance(accepts, !exact)
        val type = Class.forName("com.eignex.klause.portfolio.LeafRealCompletion")
        val completion = type.constructors.single { it.parameterCount == 7 }
            .newInstance(problem, objective, acceptance, null, false, 8, null) as CandidateCompletion
        val probe = CaptureProbe(completion, out)
        var stats: JsonElement = JsonNull
        val observe: (Any, LpStats) -> Unit = { verdict, value ->
            stats = buildJsonObject {
                put("verdict", verdict.toString())
                put("standaloneWork", value.standaloneWorkOps.sum)
                put("componentWork", value.componentWorkOps.sum)
                put("standalonePasses", value.standalonePasses.sum)
                put("componentPasses", value.componentPasses.sum)
                put("standalonePivots", value.standalonePivots.sum)
                put("componentPivots", value.componentPivots.sum)
            }
        }
        type.methods.single { it.name == "setObserveLp" }.invoke(completion, observe)
        var count = 0
        val containing = Cancellation.after(10.seconds)
        val stop = containing or Cancellation { count >= LIMIT }
        out.resolve("candidates.jsonl").bufferedWriter().use { output ->
            val recording = CandidateCompletion { candidate, cancellation ->
                probe.begin(count)
                stats = JsonNull
                val started = System.nanoTime()
                val result = completion.complete(candidate, cancellation)
                val elapsed = System.nanoTime() - started
                val completionCancelled = cancellation()
                val witness = (result as? Completion.Witness)?.sample
                val checkStop = Cancellation.after(1.seconds)
                val values = witness?.let { sample ->
                    compiled.columns.map { column ->
                        if (column.real) {
                            sample.exactReals?.get(column.id)
                                ?: BigFraction.ofDouble(sample.approximateRealValue(column.id))
                        } else {
                            BigFraction.ofLong(sample.intValue(column.id))
                        }
                    }.takeIf { coordinates -> coordinates.all { it != null } }?.filterNotNull()
                }
                val checked = values?.let { source.checkMpsWitness(it, checkStop) }
                val report = buildJsonObject {
                    put("index", count++)
                    put("pins", sample(candidate))
                    put("completion", result::class.simpleName)
                    put("work", result.work)
                    put("cancelled", completionCancelled)
                    put("observedElapsedNs", elapsed)
                    put("lp", stats)
                    put("residuals", JsonArray(probe.residuals))
                    put("residualsOmitted", probe.omitted)
                    put("witness", witness?.let(::sample) ?: JsonNull)
                    put("sourceCheck", when {
                        witness == null -> "not-a-witness"
                        checkStop() -> "declined"
                        checked == null -> "rejected"
                        else -> "accepted"
                    })
                    put("sourceExact", checked?.exact?.let(::JsonPrimitive) ?: JsonNull)
                }
                output.appendLine(report.toString())
                output.flush()
                result
            }
            val search = LocalSearchSolver(problem, completion = recording)
            search.session().use { session ->
                val result = session.minimize(objective, LocalSearchParams(
                    randomSeed = 3L, cancellation = stop,
                ))
                out.resolve("summary.json").writeText(buildJsonObject {
                    put("revision", Reports.readGitSha())
                    put("input", input)
                    put("scope", "direct-ls-cold-completion-sequence")
                    put("instrumentation", "candidate and exact residual capture; timing is observed only")
                    put("sourceVerdictClaim", false)
                    put("seed", 3)
                    put("candidateLimit", LIMIT)
                    put("containingTimeoutSeconds", 10)
                    put("instructionLimit", JsonNull)
                    put("candidates", count)
                    put("cancelled", containing())
                    put("result", result::class.simpleName)
                    put("sourceSemantics", source.semantics.toString())
                    put("processors", Runtime.getRuntime().availableProcessors())
                }.toString())
            }
        }
    }

    private fun sample(value: Sample): JsonElement = buildJsonObject {
        put("bools", JsonArray(value.bools.map(::JsonPrimitive)))
        put("ints", JsonArray(value.ints.map { JsonPrimitive(it.toString()) }))
        put("reals", JsonArray(value.reals.map { JsonPrimitive(it.toString()) }))
        put("exactReals", value.exactReals?.let { JsonArray(it.map { v -> JsonPrimitive(v.toString()) }) } ?: JsonNull)
    }

    @Suppress("SpreadOperator")
    private class CaptureProbe(completion: CandidateCompletion, private val out: File) {
        val residuals = ArrayList<JsonElement>()
        var omitted = 0
            private set
        private var candidate = 0
        init {
            val context = field(completion, "context")
            val factory = requireNotNull(call(context, "getEngineFactory"))
            val factoryType = Class.forName(ENGINE + "LpEngineFactory")
            val wrapped = Proxy.newProxyInstance(
                factoryType.classLoader, arrayOf(factoryType),
            ) { _, method, arguments ->
                val values = arguments.orEmpty()
                if (method.name in setOf("newGeneralSolver", "newPersistentSolver")) {
                    val state = values.firstOrNull()?.let { call(it, "getExactState") }
                    if (state != null) save(state)
                }
                try {
                    method.invoke(factory, *values)
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
            val copy = context.javaClass.methods.single { it.name == "copy" && it.parameterCount == 4 }.invoke(
                context, wrapped, call(context, "getCertificationPolicy"),
                call(context, "getOnRefinementBasisVerification"), call(context, "getSparsePointRecovery"),
            )
            completion.javaClass.getDeclaredField("context").apply { isAccessible = true }.set(completion, copy)
        }

        fun begin(index: Int) {
            candidate = index
            residuals.clear()
            omitted = 0
        }

        private fun save(state: Any) {
            if (residuals.size >= 64) {
                omitted++
                return
            }
            val kind = Class.forName(ENGINE + "LpReplaySolverKind").enumConstants.single { it.toString() == "GENERAL" }
            val settings = Class.forName(ENGINE + "LpReplaySettings").constructors.single { it.parameterCount == 9 }
                .newInstance("continuous-completion", 0L, kind, false, 0, 0, 0L, 64, false)
            val solve = Class.forName(ENGINE + "LpExactReplayEvent\$Solve").getConstructor().newInstance()
            val companion = Class.forName(ENGINE + "LpExactCapture").getField("Companion").get(null)
            val captured = requireNotNull(call(companion, "capture", state, settings, listOf(solve), Int.MAX_VALUE))
            val bytes = call(captured, "encode") as ByteArray
            require(bytes.size <= 16 * 1024 * 1024)
            val name = "candidate-$candidate-residual-${residuals.size}.klexact"
            out.resolve(name).writeBytes(bytes)
            residuals += buildJsonObject { put("path", name); put("sha256", sha256(bytes)) }
        }
    }

    private fun field(owner: Any, name: String): Any = requireNotNull(
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner),
    )

    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        owner.javaClass.methods.single { it.name == name && it.parameterCount == args.size }.invoke(owner, *args)
}
