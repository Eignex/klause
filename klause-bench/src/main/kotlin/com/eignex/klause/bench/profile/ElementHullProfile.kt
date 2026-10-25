package com.eignex.klause.bench.profile

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.factor.table.Element
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpAutoConfig
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.util.Cancellation
import com.sun.management.ThreadMXBean
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ElementHullProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        require(args.size == 3 && options.keys == setOf("suite", "problem", "sha256"))
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(bytes.size <= 16 * 1024 * 1024 && sha256(bytes) == options.getValue("sha256"))
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("source.xml").writeBytes(bytes)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val stop = Cancellation.after(30.seconds)
        val parsed = Xcsp3.parse(bytes.decodeToString())
        val objective = parsed.objective?.toLinearObjective()?.let {
            if (parsed.sense == ObjectiveSense.MAXIMIZE) it.negated() else it
        }
        val budget = PresolveBudget(1_650_000_000L)
        val prepared = FinitePipeline.prepare(FinitePipelineRequest(
            parsed.problem, FiniteEngine.MIXED, objective, cancellation = stop, solveCancellation = stop,
            presolveBudget = budget, randomSeed = 3L,
        ))
        check(prepared.presolve?.infeasible != true && !stop())
        val problem = prepared.problem
        val elements = problem.factors.filterIsInstance<Element>()
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        val relaxation = MpsLpAdmission.rootRelaxation(problem, prepared.objective, false, stop, element = true)
        val assemblyNs = System.nanoTime() - started
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        val model = get(relaxation, "Model")
        val requirements = get(relaxation, "ColReq") as Array<*>
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("sourceSha256", sha256(bytes))
            put("suite", options.getValue("suite"))
            put("problem", options.getValue("problem"))
            put("searchExecuted", false)
            put("sourceVerdictClaim", false)
            put("forcedElementHull", true)
            put("defaultElementAdmission", LpAutoConfig.resolve(problem, LpConfig.AUTO).element)
            put("aggressiveElementAdmission", LpAutoConfig.resolve(problem, LpConfig.AGGRESSIVE).element)
            put("defaultSelection", selection(problem, prepared.objective, LpConfig.AUTO, stop))
            put("aggressiveSelection", selection(problem, prepared.objective, LpConfig.AGGRESSIVE, stop))
            put("preparationWork", budget.spent())
            put("preparationWorkAllowance", budget.allowance)
            put("elementFactors", elements.size)
            put("elementIndexes", elements.map { it.idx }.distinct().size)
            put("rawPrivateChoices", elements.sumOf { it.arr.size.toLong() })
            put("rows", get(model, "M") as Int)
            put("columns", get(model, "N") as Int)
            put("selectorColumns", requirements.count { it != null })
            put("selectorMemberships", buildJsonArray {
                requirements.filterNotNull().groupingBy { (it as LongArray)[0] }.eachCount().toSortedMap()
                    .forEach { (index, count) -> add(buildJsonObject { put("index", index); put("choices", count) }) }
            })
            put("assemblyNs", assemblyNs)
            put("assemblyThreadAllocatedBytes", allocated)
            put("javaVersion", System.getProperty("java.version"))
            put("os", System.getProperty("os.name"))
            put("arch", System.getProperty("os.arch"))
        }
        out.resolve("element-hull.json").writeText("$report\n")
        println(report)
    }

    private fun selection(
        problem: Problem,
        objective: LinearObjective?,
        config: LpConfig,
        stop: Cancellation,
    ) = buildJsonObject {
        val method = LpAutoConfig.javaClass.methods.single { it.name.startsWith("admission$") }
        val admission = method.invoke(LpAutoConfig, problem, config, LpPlan())
        @Suppress("UNCHECKED_CAST")
        val indexes = get(admission, "ElementIndexes") as Set<Int>
        val plan = get(admission, "Plan") as LpPlan
        put("indexes", buildJsonArray { indexes.sorted().forEach { add(JsonPrimitive(it)) } })
        for (name in listOf("EstimatedRows", "EstimatedColumns", "EstimatedCells")) {
            put(name.replaceFirstChar { it.lowercase() }, get(admission, name) as Long)
        }
        put("elementEnabled", plan.element)
        put("elementOnlyProjection", true)
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        val relaxation = MpsLpAdmission.rootRelaxation(problem, objective, false, stop, plan.element, indexes)
        put("assemblyNs", System.nanoTime() - started)
        put("assemblyThreadAllocatedBytes", bean.getThreadAllocatedBytes(thread) - before)
        val model = get(relaxation, "Model")
        val rows = get(model, "M") as Int
        val columns = get(model, "N") as Int
        put("rows", rows)
        put("columns", columns)
        put("emittedCells", rows.toLong() * (columns.toLong() + rows + 1))
        put("selectorColumns", (get(relaxation, "ColReq") as Array<*>).count { it != null })
    }

    private fun get(receiver: Any, name: String): Any =
        requireNotNull(receiver.javaClass.getMethod("get$name").invoke(receiver))
}
