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
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.util.Cancellation
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MpsLpAdmission {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].startsWith("batch64="))
        val requests = Json.decodeFromString<List<List<String>>>(
            Base64.getDecoder().decode(args[0].substringAfter('=')).decodeToString(),
        )
        require(requests.size in 1..16)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        Reports.writeJson(out.resolve("build-provenance.json").path, InstalledBuild.reference)
        requests.forEachIndexed { index, request ->
            inspect(request, out.resolve("$index").apply { mkdirs() })
        }
    }

    private fun inspect(request: List<String>, out: File) {
        val options = request.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(options.keys == setOf("suite", "problem", "sha256"))
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.mps").writeBytes(bytes)
        val parsed = Mps.parse(bytes.decodeToString())
        val stop = Cancellation.after(20.seconds)
        val original = requireNotNull(parsed.toLinearSourceProblem(cancellation = stop))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(original, ProblemSettings()), ExecutionOperation.OPTIMIZE, FiniteEngine.BACKTRACK,
            maximize = original.objective?.sense == ObjectiveSense.MAXIMIZE, cancellation = stop,
        ))
        val plan = requireNotNull(invocation.preparedPlan)
        val problem = requireNotNull(plan.model)
        val objective = plan.objective?.let { if (plan.maximize) it.negated() else it }
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("suite", options.getValue("suite"))
            put("problem", options.getValue("problem"))
            put("sourceSha256", sha256(bytes))
            put("scope", "prepared-root-relaxation-diagnostic")
            put("solverExecuted", false)
            put("sourceVerdictClaim", false)
            put("sourceSemantics", original.semantics.toString())
            put("route", invocation.route::class.simpleName)
            put("compiledIntegers", problem.numIntVars)
            put("compiledBooleans", problem.numBoolVars)
            put("compiledReals", problem.numRealVars)
            put("compiledFactors", problem.factors.size)
            put("factorKinds", buildJsonObject {
                problem.factors.groupingBy { it::class.simpleName ?: "Factor" }.eachCount().toSortedMap()
                    .forEach { (kind, count) -> put(kind, count) }
            })
            put("lpMaxTableauCells", problem.settings.lpMaxTableauCells)
            put("lpCeilingTableauCells", problem.settings.lpCeilingTableauCells)
            put("defaultPlan", LpAutoConfig.resolve(problem, LpConfig.AUTO).toString())
            put("aggressivePlan", LpAutoConfig.resolve(problem, LpConfig.AGGRESSIVE).toString())
            put("full", shape(problem, objective, false, stop))
            put("objectiveCone", shape(problem, objective, true, stop))
            put("javaVersion", System.getProperty("java.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
            put("jvmOptions", buildJsonArray {
                ManagementFactory.getRuntimeMXBean().inputArguments.forEach { add(JsonPrimitive(it)) }
            })
        }
        out.resolve("admission.json").writeText("$report\n")
        println(report)
    }

    private fun shape(problem: Problem, objective: LinearObjective?, cone: Boolean, stop: Cancellation): JsonObject {
        check(!stop())
        // Reflection keeps diagnostic access to internal assembly out of the published API.
        val type = Class.forName("com.eignex.klause.lp.relaxation.CpToLpRelaxation")
        val constructor = type.constructors.single { it.parameterTypes.last().simpleName == "DefaultConstructorMarker" }
        check(constructor.parameterCount == 23)
        val values = arrayOfNulls<Any>(23)
        constructor.parameterTypes.forEachIndexed { index, parameter ->
            if (parameter == Boolean::class.javaPrimitiveType) values[index] = false
        }
        values[0] = problem
        values[1] = objective
        values[11] = cone
        values[21] = (1 or (1 shl 1) or (1 shl 11)).inv()
        val relaxer = constructor.newInstance(*values)
        val domainType = Class.forName("com.eignex.klause.lp.relaxation.RootDomains")
        val domains = domainType.constructors.single().newInstance(problem)
        val build = type.methods.single { it.name == "build" && it.parameterCount == 3 &&
            it.parameterTypes[0].simpleName == "RelaxationDomains" }
        val relaxation = build.invoke(relaxer, domains, emptyList<Any>(), stop)
        val model = get(relaxation, "Model")
        val rows = get(model, "M") as Int
        val columns = get(model, "N") as Int
        val matrix = optional(model, "DoubleView") ?: get(model, "Csc")
        val pointers = get(matrix, "ColPtr") as IntArray
        val rowIndices = get(matrix, "RowIdx") as IntArray
        val parents = IntArray(rows + columns) { it }
        fun root(value: Int): Int {
            var current = value
            while (parents[current] != current) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }
        for (column in 0 until columns) {
            for (entry in pointers[column] until pointers[column + 1]) {
                parents[root(rows + column)] = root(rowIndices[entry])
            }
        }
        val rowCounts = (0 until rows).groupingBy(::root).eachCount()
        val columnCounts = (0 until columns).groupingBy { root(rows + it) }.eachCount()
        return buildJsonObject {
            put("rows", rows)
            put("columns", columns)
            put("nonzeros", rowIndices.size)
            put("emittedDenseProxy", rows.toLong() * (columns.toLong() + rows + 1))
            put("rowComponents", rowCounts.size)
            put("largestComponentRows", rowCounts.values.maxOrNull() ?: 0)
            put("largestComponentColumns", rowCounts.keys.maxOfOrNull { columnCounts[it] ?: 0 } ?: 0)
            put("isolatedColumns", (0 until columns).count { pointers[it] == pointers[it + 1] })
            put("persistentEligible", get(relaxation, "PersistentEligible") as Boolean)
        }
    }

    private fun optional(receiver: Any, name: String): Any? =
        receiver.javaClass.getMethod("get$name").invoke(receiver)

    private fun get(receiver: Any, name: String): Any = requireNotNull(optional(receiver, name))
}
