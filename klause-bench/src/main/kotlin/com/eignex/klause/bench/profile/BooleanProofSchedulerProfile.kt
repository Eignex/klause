package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioDiagnostics
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.util.Cancellation
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds

/** Remote-only total-cost diagnosis of retained Boolean tasks against independent finite workers. */
object BooleanProofSchedulerProfile {
    /** Compare one hash-pinned DIMACS source; native refutations remain independently unverified. */
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val required = setOf("suite", "problem", "sha256", "work", "seed", "splits", "repeats")
        require(options.keys.containsAll(required) &&
            (options.keys - required).all { it in setOf("recipe", "native") })
        val recipe = options["recipe"] ?: "input-order"
        val native = options["native"]?.toBooleanStrict() ?: false
        val limit = options.getValue("work").toLong().also { require(it in 10000L..2000000L) }
        val seed = options.getValue("seed").toLong()
        val splits = options.getValue("splits").toInt().also { require(it in 1..8) }
        val repeats = options.getValue("repeats").toInt().also { require(it in 1..6) }
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val stop = Cancellation.after(120.seconds)
        val source = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(source.source)).use { it.readNBytes(1048577) }
        require(bytes.size <= 1048576 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.cnf").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val context = BooleanProofContext(SourceScope(SourceRevision(sha256(bytes), "exact-cnf")), "identity:0")
        val allocation = ManagementFactory.getThreadMXBean() as ThreadMXBean
        allocation.isThreadAllocatedMemoryEnabled = true
        val process = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val report = out.resolve("records.jsonl")
        for (iteration in -1 until repeats) {
            val modes = if (iteration and 1 == 0) listOf("replicas", "tasks") else listOf("tasks", "replicas")
            for (mode in modes) {
                if (stop()) return
                val allocated = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())
                val cpu = process.processCpuTime
                val started = System.nanoTime()
                // Each control pays its own source construction, bake, private setup, checking and retirement.
                val problem = Dimacs.parse(bytes.decodeToString()).toProblem().bake(cancellation = stop)
                val budget = NodeBudget(limit)
                val parameters = List(2) { worker ->
                    val params = if (recipe == "input-order") BacktrackParams(
                        randomSeed = seed + worker, variableSelector = InputOrder, valueSelector = IndomainMin,
                    ) else BacktrackCatalog.byLabel(recipe).build(seed + worker, null)
                    params.copy(cancellation = stop, nodeBudget = budget, nativeSat = native)
                }
                var completedTasks = 0
                var totalTasks = 0
                var schedulerWork = 0L
                val result = if (mode == "tasks") {
                    val scheduler = SchedulerAccess(problem, context, (0 until splits).toList(), parameters)
                    scheduler.use {
                        it.run(stop, limit).also {
                            completedTasks = scheduler.completed
                            totalTasks = scheduler.size
                            schedulerWork = scheduler.work
                        }
                    }
                } else {
                    val workers = parameters.mapIndexed { worker, params ->
                        PortfolioWorker.of("bt/input-order", worker, BacktrackSolver(problem).session(), params)
                    }
                    Portfolio.thompson(workers, baseSliceWork = 5000L, maxSliceWork = 150000L,
                        sliceGrowth = 1.5, reseedStaleThreshold = 0,
                        diagnostics = PortfolioDiagnostics(limit, seed)).use { it.solve(stop) }
                }
                val elapsed = System.nanoTime() - started
                val bytesAllocated = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated
                val cpuElapsed = process.processCpuTime - cpu
                report.appendText(buildJsonObject {
                    put("mode", mode)
                    put("recipe", recipe)
                    put("nativeSat", native)
                    put("sharing", false)
                    put("presolve", false)
                    put("iteration", iteration)
                    put("warmup", iteration < 0)
                    put("sourceSha256", sha256(bytes))
                    put("seed", seed)
                    put("workerSeeds", "$seed,${seed + 1}")
                    put("result", result::class.simpleName)
                    put("nativeCoverageComplete", result is SolveResult.Unsat)
                    put("independentlyCheckedCoverageComplete", false)
                    put("completedTasks", completedTasks)
                    put("totalTasks", totalTasks)
                    put("schedulerWork", schedulerWork)
                    put("decisions", result.stats.search.nodes.sum)
                    put("nodeBudgetSpent", budget.spent)
                    put("propagationWork", result.stats.search.propagationWork.sum)
                    put("rootPropagationWork", result.stats.search.rootPropagationWork.sum)
                    put("inclusiveControlElapsedNs", elapsed)
                    put("inclusiveControlProcessCpuNs", cpuElapsed)
                    put("threadAllocatedBytes", bytesAllocated)
                    put("heapUsedBytes", ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
                    put("processors", Runtime.getRuntime().availableProcessors())
                    put("maxHeapBytes", Runtime.getRuntime().maxMemory())
                    put("emergencyStopped", stop())
                }.toString() + "\n")
            }
        }
    }

    // The executor remains internal until its source routing and activation policy are qualified.
    private class SchedulerAccess(
        problem: BakedProblem, context: BooleanProofContext, splits: List<Int>, parameters: List<BacktrackParams>,
    ) : AutoCloseable {
        private val type = Class.forName("com.eignex.klause.portfolio.BooleanProofScheduler")
        private val target = type.getConstructor(BakedProblem::class.java, BooleanProofContext::class.java,
            List::class.java, List::class.java).newInstance(problem, context, splits, parameters)
        val completed: Int get() = type.getMethod("getCompleted").invoke(target) as Int
        val size: Int get() = type.getMethod("getSize").invoke(target) as Int
        val work: Long get() = type.getMethod("getWork").invoke(target) as Long
        fun run(stop: Cancellation, work: Long): SolveResult = type.getMethod("run", Cancellation::class.java,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .invoke(target, stop, work, 5000L, 150000L) as SolveResult
        override fun close() { type.getMethod("close").invoke(target) }
    }
}
