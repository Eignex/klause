package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toLinearSourceProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.LinearTraversal
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MpsSparseRoot {
    private const val LP = "com.eignex.klause.lp."
    private const val ENGINE = LP + "engine."
    private val policy = Class.forName(LP + "SparseSourceLpPolicy").getConstructor().newInstance()

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args.single().startsWith("batch64="))
        val requests = Json.decodeFromString<List<List<String>>>(
            Base64.getDecoder().decode(args.single().substringAfter('=')).decodeToString())
        require(requests.size in 1..16)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        requests.forEachIndexed { index, request -> inspect(request, out.resolve("$index").apply { mkdirs() }) }
    }

    private fun inspect(request: List<String>, out: File) {
        val options = request.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val selected = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(selected.source)).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.mps").writeBytes(bytes)
        val stop = Cancellation.after(30.seconds)
        val source = checkNotNull(Mps.parse(bytes.decodeToString()).toLinearSourceProblem(cancellation = stop))
        val plan = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED,
            maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop,
            linearTraversal = LinearTraversal.NUMERICAL))
        val execution = checkNotNull(NumericalFrontierSnapshot.field(plan.route, "execution"))
        val numerical = checkNotNull(NumericalFrontierSnapshot.field(execution, "request"))
        val context = numerical.javaClass.declaredMethods.single {
            it.name == "authorityContext" && it.parameterCount == 2
        }.apply { isAccessible = true }.invoke(numerical, checkNotNull(call(numerical, "getObjective")), stop)
            as SourceProofContext
        require(context.coordinates.none { it.kind == SourceCoordinate.Kind.Boolean })
        require(context.premises.values.all { it.guards.isEmpty() && it.integerPins.isEmpty() })
        val coordinates = context.coordinates.sortedWith(compareBy({ it.kind.ordinal }, { it.id }))
        val coordinateIndex = coordinates.withIndex().associate { it.value to it.index }
        val rowType = Class.forName("com.eignex.klause.simplex.exact.ExactRationalInequality")
        val rowConstructor = rowType.constructors.single { it.parameterCount == 4 }
        val rows = context.premises.values.map { premise ->
            val row = premise.inequality
            val ids = row.coefficients.keys.map { coordinateIndex.getValue(it) }.sorted().toIntArray()
            rowConstructor.newInstance(ids, ids.map { row.coefficients.getValue(coordinates[it]) }, row.rhs, row.strict)
        }
        val sign = if (context.scope.objective?.sense == ObjectiveSense.MAXIMIZE) BigFraction.MINUS_ONE
            else BigFraction.ONE
        val costs = coordinates.map { (context.objective[it] ?: BigFraction.ZERO) * sign }
        val modelType = Class.forName(LP + "optimization.LinearAuthorityModel")
        val companion = modelType.getField("Companion").get(null)
        val prepare = companion.javaClass.methods.single { it.name == "prepare" }
        val packed = checkNotNull(when (prepare.parameterCount) {
            3 -> prepare.invoke(companion, rows, costs.size, stop)
            4 -> prepare.invoke(companion, rows, costs.size, stop, emptySet<Int>())
            else -> error("unsupported source preparation signature")
        })
        val workingRows = call(packed, "getRows") as List<*>
        val columns = call(packed, "getColumns") as List<*>
        val sparseType = Class.forName(LP + "SparseSourceLp")
        val limits = checkNotNull(call(policy, "getPreparation"))
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val started = System.nanoTime()
        val allocated = bean.getThreadAllocatedBytes(thread)
        val preparation = checkNotNull(call(sparseType.getField("Companion").get(null), "prepare",
            workingRows, columns, costs, context.objectiveConstant * sign, limits, stop))
        val prepareNs = System.nanoTime() - started
        val prepareBytes = bean.getThreadAllocatedBytes(thread) - allocated
        val prepared = call(preparation, "getPrepared")
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("problem", options.getValue("problem"))
            put("sourceSha256", sha256(bytes))
            put("sourceSemantics", source.semantics.toString())
            put("inputRows", rows.size)
            put("workingRows", workingRows.size)
            put("columns", columns.size)
            put("preparation", NumericalResidualProfile.json(call(preparation, "getMetrics")))
            put("preparationDecline", call(preparation, "getDecline")?.toString())
            put("prepareNs", prepareNs)
            put("prepareThreadBytes", prepareBytes)
            put("policy", NumericalResidualProfile.json(policy))
            put("sourceVerdictClaim", false)
            put("independentWitnessCheck", false)
            put("javaVersion", System.getProperty("java.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory())
        }
        out.resolve("preparation.json").writeText(report.toString() + "\n")
        println(report)
        if (prepared != null && !stop()) run(checkNotNull(call(prepared, "getModel")), out, bean, thread)
    }

    private fun run(model: Any, out: File, bean: ThreadMXBean, thread: Long) {
        val stop = Cancellation.after(10.seconds)
        val trail = Class.forName(ENGINE + "LpBoundTrail").constructors.single { it.parameterCount == 1 }
            .newInstance(model)
        val context = Class.forName(ENGINE + "LpSolveContext").getConstructor().newInstance()
        val pricing = Class.forName(ENGINE + "LpPricingOptions").constructors.single { it.parameterCount == 2 }
            .newInstance(LpZeroObjectivePricing.MIN_BOUND_SUPPORT, 3L)
        val allowance = checkNotNull(call(policy, "getFloating"))
        val owner = Class.forName(ENGINE + "LpScopedSolver").constructors.single { it.parameterCount == 9 }
            .newInstance(call(trail, "getState"), stop, context, 50, call(allowance, "getIterations"),
                call(allowance, "getWork"), false, Int.MAX_VALUE, pricing)
        val limits = checkNotNull(call(policy, "getCertificate"))
        val advance = Class.forName(ENGINE + "LpRootGuidanceKt").methods.single {
            it.name == "advanceRootGuidance" && it.parameterCount == 7
        }
        try {
            out.resolve("guidance.jsonl").bufferedWriter().use { output ->
                var iteration = 0
                do {
                    val started = System.nanoTime()
                    val bytes = bean.getThreadAllocatedBytes(thread)
                    val guide = advance.invoke(null, owner, call(policy, "getQuantum"), allowance, limits, stop, null,
                        call(context, "getCertificationPolicy"))
                    val elapsed = System.nanoTime() - started
                    val allocation = bean.getThreadAllocatedBytes(thread) - bytes
                    val point = call(guide, "getPrimal") as DoubleArray?
                    val numerical = NumericalFrontierSnapshot.field(owner, "solver")
                    val observations = numerical?.let { NumericalFrontierSnapshot.field(it, "observations") }
                    point?.let { out.resolve("heuristic-primal.json")
                        .writeText(JsonArray(it.map(::JsonPrimitive)).toString() + "\n") }
                    val report = buildJsonObject {
                        put("iteration", iteration++)
                        put("elapsedNs", elapsed)
                        put("threadBytes", allocation)
                        put("phase", call(guide, "getPhase").toString())
                        put("paused", call(guide, "getPaused") as Boolean)
                        put("termination", call(guide, "getTermination")?.toString())
                        put("optimalCandidate", call(guide, "getOptimalCandidate") as Boolean)
                        put("heuristicCoordinates", point?.size ?: 0)
                        put("checkedNativeBound", call(guide, "getBound")?.let { call(it, "getValue").toString() })
                        put("certificate", NumericalResidualProfile.json(call(guide, "getCertificateMetrics")))
                        put("maxLuFill", observations?.let { call(it, "getMaxLuFill") } as Double?)
                        put("maxLuDensity", observations?.let { call(it, "getMaxLuDensity") } as Double?)
                        put("numericalProgress", NumericalResidualProfile.json(numerical?.let {
                            call(it, "getLastNumericalMetrics")
                        }))
                        put("floatMetrics", NumericalResidualProfile.json(call(owner, "getLastMetrics")))
                        put("ownerMetrics", NumericalResidualProfile.json(call(owner, "getMetrics")))
                    }
                    output.appendLine(report.toString())
                    output.flush()
                    println(report)
                    check(iteration <= 256)
                } while (call(guide, "getPaused") == true && !stop())
            }
        } finally {
            (owner as AutoCloseable).close()
        }
    }

    @Suppress("SpreadOperator")
    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
