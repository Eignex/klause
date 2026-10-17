package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.ir.LinearSourceIndicator
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionOptions
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FiniteSolveRequest
import com.eignex.klause.solver.pipeline.LinearSourceConclusion
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceExecutionResult
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.SourceExecutionCallbacks
import com.eignex.klause.solver.pipeline.SourceExecutionRequest
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.TheoryParams
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.executionKind
import com.eignex.klause.solver.validation.LinearSourceWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.milliseconds

/** Capture direct-library original-unit linear publications for independent source checking. */
object LinearSourceCapture {
    /** Read base64 JSON cases, retaining supplied original bytes and every checked publication. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Supply a base64 JSON array of linear source captures" }
        require(args[0].length <= 128 * 1024) { "Inline capture descriptor is oversized" }
        val bytes = Base64.getDecoder().decode(args.single())
        val inputs = Json.decodeFromString<List<LinearCaptureInput>>(bytes.decodeToString())
        require(inputs.isNotEmpty())
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("input.json").writeBytes(bytes)
        val revision = requireNotNull(Reports.readGitSha())
        Reports.writeJson(out.resolve("installed-build.json").path, InstalledBuild.reference)
        out.resolve("runtime.json").writeText(
            buildJsonObject {
            put("revision", revision)
            put("inputSha256", sha256(bytes))
            put("javaVersion", System.getProperty("java.version"))
            put("javaVmVersion", System.getProperty("java.vm.version"))
            put("kotlinVersion", KotlinVersion.CURRENT.toString())
            put("processors", Runtime.getRuntime().availableProcessors())
            put("javaOptions", ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" "))
            put(
                "classpath",
                buildJsonArray {
                System.getProperty("java.class.path").split(File.pathSeparator).map(::File)
                    .filter(File::isFile).forEach { file ->
                        add(
                            buildJsonObject {
                            put("name", file.name)
                            put("sha256", sha256(file.readBytes()))
                        }
                        )
                    }
            }
            )
        }.toString()
        )
        val records = ArrayList<JsonObject>()
        val measurements = ArrayList<JsonObject>()
        for ((index, input) in inputs.withIndex()) {
            val original = Base64.getDecoder().decode(input.source64)
            require(original.size <= 64 * 1024 && sha256(original) == input.sourceSha256)
            out.resolve("sources").apply { mkdirs() }.resolve("${input.sourceSha256}.txt").writeBytes(original)
            val source = input.source()
            val stop = Cancellation.after(input.timeoutMs.milliseconds)
            val started = System.nanoTime()
            val operation = if (source.objective == null) ExecutionOperation.SATISFY else ExecutionOperation.OPTIMIZE
            val engine = FiniteEngine.valueOf(input.engine)
            val invocation = SourceSetup.prepare(
                SourceSetupRequest(
                SourceAttachment(source, ProblemSettings()),
                operation,
                engine,
                maximize = input.maximize,
                cancellation = stop,
                boundCancellation = if (input.openBoundProof) Cancellation.Never else Cancellation { true },
            )
            )
            val points = ArrayList<LinearSourceWitness>()
            val result = SourceSetup.execute(
                SourceExecutionRequest(invocation, finiteRequest = { shape ->
                    FiniteSolveRequest(
                        shape, engine, PresolveConfig.NONE, false, false, stop,
                        presolveBudget = null, cores = input.cores,
                        executionOptions = ExecutionOptions.parse(emptyList()),
                        randomSeed = input.seed, defaultArms = 1, lpConfig = LpConfig.AUTO,
                        zeroObjectivePricing = LpZeroObjectivePricing.MIN_BOUND_SUPPORT,
                        nodeBudget = input.nodeLimit?.let(::NodeBudget),
                        solveBudgetMillis = input.timeoutMs, allSolutions = false, solutionCap = null,
                        deadlineExceeded = { stop() }, onEvent = null, onPortfolioEvent = null,
                    )
                }, theory = TheoryParams(openWorkLimit = input.work, cancellation = stop), cores = input.cores),
                SourceExecutionCallbacks(onLinearSourceIncumbent = points::add),
            )
            val outcome = result.linearSource
            val linear = (result as? SourceExecutionResult.Numerical)?.result
                ?: ((result as? SourceExecutionResult.Linear)?.result as? OpenTheoryExecution.Optimize)
                    ?.result?.linearResult
            val stats = linear?.stats ?: ((result as? SourceExecutionResult.Finite)?.result?.outcome
                as? FiniteSolveOutcome.Completed)?.stats
            for (point in points) records += input.record(point, false, records.size, revision)
            val conclusion = outcome?.conclusion ?: LinearSourceConclusion.UNKNOWN
            records += input.record(
                outcome?.incumbent,
                conclusion in setOf(LinearSourceConclusion.OPTIMAL, LinearSourceConclusion.UNSATISFIABLE),
                records.size,
                revision,
                conclusion == LinearSourceConclusion.UNSATISFIABLE,
            )
            measurements += buildJsonObject {
                put("index", index)
                put("name", input.name)
                put("engine", input.engine)
                put("cores", input.cores)
                put("seed", input.seed)
                put("work", input.work)
                put("nodeLimit", input.nodeLimit)
                put("openBoundProof", input.openBoundProof)
                put("sourceIntegerCount", invocation.preparedPlan?.sourceIntCount)
                put("sourceRealCount", invocation.preparedPlan?.sourceRealCount)
                put("workingIntegerCount", invocation.preparedPlan?.model?.numIntVars)
                put("workingRealCount", invocation.preparedPlan?.model?.numRealVars)
                put("scheduledArms", buildJsonArray {
                    for (arm in stats?.portfolio?.arms.orEmpty()) add(buildJsonObject {
                        put("label", arm.label)
                        put("segments", arm.segments)
                        put("work", arm.work)
                        put("faults", arm.faults)
                        put("failures", arm.failures)
                    })
                })
                put("timeoutMs", input.timeoutMs)
                put("sourceSha256", input.sourceSha256)
                put("executionKind", SourceSetup.executionKind(invocation).name)
                put("nativeResult", result::class.simpleName)
                put("nativeReason", linear?.reason?.toString() ?: (result as? SourceExecutionResult.Declined)
                    ?.reason?.toString())
                put("nativeScope", linear?.scope?.toString())
                put("nativeBound", linear?.bound?.claim?.toString())
                put("openWork", stats?.openTheory?.openWork)
                put("lpStandaloneWork", stats?.lp?.standaloneWorkOps?.sum)
                put("nodes", stats?.search?.nodes?.sum)
                put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0)
                put("publications", points.size)
                put("conclusion", conclusion.name)
                put("nativeConclusion", outcome?.nativeConclusion?.name)
                put("nominalSourceExact", outcome?.nominalSourceExact)
                put("nominalConstraintsExact", outcome?.nominalConstraintsExact)
                put("claimsCoverTolerance", outcome?.claimsCoverTolerance)
                put("nativeClaimsUnrestricted", outcome?.nativeClaimsUnrestricted)
                put("acceptedObjectiveBound", outcome?.acceptedObjectiveBound?.toString())
                put("objectiveScale", outcome?.objectiveScale?.toString())
                put("nativeProofCaptured", false)
            }
            out.resolve("cases.json").writeText(JsonArray(records).toString())
            out.resolve("measurements.json").writeText(JsonArray(measurements).toString())
        }
    }
}

@Serializable
private data class LinearCaptureInput(
    val name: String,
    val source64: String,
    val sourceSha256: String,
    val variables: List<LinearCaptureVariable>,
    val rows: List<LinearCaptureRow>,
    val objective: List<LinearCaptureTerm>? = null,
    val constant: String = "0",
    val maximize: Boolean = false,
    val absolute: String = "0",
    val relative: String = "0",
    val engine: String = "FIXED",
    val cores: Int = 1,
    val seed: Long = 17,
    val work: Long = Long.MAX_VALUE,
    val nodeLimit: Long? = null,
    val openBoundProof: Boolean = true,
    val timeoutMs: Long = 5000,
) {
    fun source(): LinearSourceProblem {
        require(cores > 0 && work >= 0 && timeoutMs > 0 && (nodeLimit == null || nodeLimit >= 0))
        fun tolerance(text: String): SemanticRational {
            return text.fraction()
        }
        return LinearSourceProblem(
            variables.map { LinearSourceVariable(it.name, it.integer, it.lower?.fraction(), it.upper?.fraction()) },
            rows.map { row ->
                LinearSourceRow(
                    row.terms.map { LinearSourceTerm(it.column, it.coefficient.fraction()) },
                    row.lower?.fraction(),
                    row.upper?.fraction(),
                    row.indicator?.let { LinearSourceIndicator(it, row.whenOne) },
                )
            },
            objective?.let { terms ->
                LinearSourceObjective(
                    terms.map {
                        LinearSourceTerm(
                            it.column,
                            it.coefficient.fraction(),
                        )
                    },
                    constant.fraction(),
                    if (maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE,
                )
            },
            SourceSemantics(
                SourceFormat.MPS,
                NumericAuthority.DECIMAL_LITERALS,
                feasibilityTolerance = SourceTolerance(tolerance(absolute), tolerance(relative)),
            ).resolve(),
        )
    }

    fun record(
        point: LinearSourceWitness?,
        proven: Boolean,
        index: Int,
        revision: String,
        infeasible: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("index", index)
        put("status", "DONE")
        val exact = absolute.fraction() == SemanticRational.ZERO && relative.fraction() == SemanticRational.ZERO
        put("arm", engine + if (exact) "-exact" else "")
        put("problem", buildJsonObject { put("suite", "mps-linear-source-v1"); put("problem", name) })
        put("record", buildJsonObject {
            put("problem", name)
            put("gitSha", revision)
            put("kind", if (objective == null) "satisfy" else "optimize")
            put(
                "feasible",
                when {
                infeasible -> false;
                point != null -> true;
                else -> null
            }
            )
            put("proven", proven)
            put("maximize", maximize)
            put("objective", point?.objective?.toDouble())
            put("exactObjective", point?.objective?.toString())
            put("sourceHashes", buildJsonObject { put("model", sourceSha256) })
            put(
                "finalWitness",
                point?.let { witness ->
                val values = witness.values
                "v " + variables.indices.joinToString(" ") { "${variables[it].name}=${values[it]}" }
            }
            )
            put(
                "stats",
                buildJsonObject {
                put("sourcePolicy", "captured-linear-source")
                if (proven && point?.exact == false) put("optimizationStatus", "tolerance-optimum")
            }
            )
        }
        )
    }
}

@Serializable
private data class LinearCaptureVariable(
    val name: String,
    val integer: Boolean,
    val lower: String? = null,
    val upper: String? = null,
)

@Serializable
private data class LinearCaptureTerm(val column: Int, val coefficient: String)

@Serializable
private data class LinearCaptureRow(
    val terms: List<LinearCaptureTerm>,
    val lower: String? = null,
    val upper: String? = null,
    val indicator: Int? = null,
    val whenOne: Boolean = true,
)

private fun String.fraction(): SemanticRational {
    val parts = split('/')
    require(parts.size in 1..2 && parts.all { it.matches(Regex("-?[0-9]+")) }) { "Expected an exact fraction" }
    return SemanticRational.of(parseBigInt(parts[0]), if (parts.size == 1) BIG_ONE else parseBigInt(parts[1]))
}
