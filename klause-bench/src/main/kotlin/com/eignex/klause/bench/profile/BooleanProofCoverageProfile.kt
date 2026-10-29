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
import com.eignex.klause.ir.Lit
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioDiagnostics
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.search.BooleanCoverageAttribution
import com.eignex.klause.solver.search.BooleanCube
import com.eignex.klause.solver.search.BooleanProofContext
import com.eignex.klause.solver.search.BooleanProofTasks
import com.eignex.klause.solver.search.BooleanTaskCheck
import com.eignex.klause.solver.search.BooleanTaskLease
import com.eignex.klause.util.Cancellation
import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds

/** Bounded Boolean attribution and scoped task protocol qualification through finite solver paths. */
object BooleanProofCoverageProfile {
    private data class WorkSlices(val base: Long, val maximum: Long) {
        init { require(base > 0 && maximum >= base && maximum <= 150000) }
    }

    /** Capture one pinned DIMACS source under `$OUT`; this is a protocol control, not a production scheduler. */
    @JvmStatic
    fun main(args: Array<String>) {
        val started = System.nanoTime()
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val required = setOf("suite", "problem", "sha256", "work", "seed", "splits")
        val optional = setOf("cost-repeats", "slice-work", "slice-max-work")
        require(options.keys.containsAll(required) && (options.keys - required).all { it in optional })
        val work = options.getValue("work").toLong().also { require(it in 1..100000) }
        val seed = options.getValue("seed").toLong()
        val splits = options.getValue("splits").toInt().also { require(it in 0..4) }
        val costRepeats = (options["cost-repeats"]?.toInt() ?: 0).also { require(it in 0..12) }
        val slices = WorkSlices(options["slice-work"]?.toLong() ?: 8L,
            options["slice-max-work"]?.toLong() ?: 64L)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        val stop = Cancellation.after(120.seconds)
        val reference = Catalog.uncapped(options.getValue("suite")).problems
            .single { it.name == options.getValue("problem") }
        val bytes = CorpusFiles.open(CorpusFetcher.resolve(reference.source)).use { it.readNBytes(1048577) }
        require(bytes.size <= 1048576 && sha256(bytes) == options.getValue("sha256"))
        out.resolve("source.cnf").writeBytes(bytes)
        out.resolve("request.txt").writeText(args.joinToString("\n"))
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        val preparationStarted = System.nanoTime()
        val document = Dimacs.parse(bytes.decodeToString())
        require(!document.triviallyUnsat && document.numBoolVars in splits..4096)
        val source = document.toProblem()
        val problem = source.bake(cancellation = stop)
        check(!stop())
        val preparationNs = System.nanoTime() - preparationStarted
        val context = BooleanProofContext(
            SourceScope(SourceRevision(sha256(bytes), "dimacs-cnf:" + source.semantics)), "source-identity:0",
        )
        out.resolve("context.json").writeText(buildJsonObject {
            put("sourceSha256", context.scope.revision.identity)
            put("semantics", context.scope.revision.semantics)
            put("mapping", context.mapping)
            put("callerBooleanAssumptions", JsonObject(emptyMap()))
            put("objective", JsonNull)
            put("cutoff", JsonNull)
            put("presolve", "off")
            put("nativeSat", false)
        }.toString())
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val report = out.resolve("records.jsonl")
        for ((index, observed) in listOf(false, true, true).withIndex()) {
            val branches = out.resolve("branches-$index.txt")
            val capture = BooleanCoverageAttribution(context, onEntry = { worker, cube ->
                branches.appendText("$worker:${cube.literals.joinToString(",")}\n")
            })
            val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
            val begin = System.nanoTime()
            val trace = out.resolve("trace-$index.txt")
            val workers = List(2) { worker ->
                PortfolioWorker.of("bt/input-order", worker, BacktrackSolver(problem).session(),
                    params(seed, stop).copy(branchObserver = if (observed) {
                        capture.observer(worker, problem.numBoolVars)
                    } else null))
            }
            val result = Portfolio.thompson(workers, baseSliceWork = slices.base, maxSliceWork = slices.maximum,
                sliceGrowth = 1.5, reseedStaleThreshold = 0,
                diagnostics = PortfolioDiagnostics(work, seed) { trace.appendText("$it\n") }).use { it.solve(stop) }
            val record = resultRecord(result, begin, allocated, bean).toMutableMap()
            record["mode"] = JsonPrimitive(if (observed) "f1-attribution" else "f1-baseline")
            record["entries"] = JsonPrimitive(capture.entries)
            record["duplicateEntries"] = JsonPrimitive(capture.duplicateEntries)
            record["repeatedEntries"] = JsonPrimitive(capture.repeatedEntries)
            record["declinedEntries"] = JsonPrimitive(capture.declinedEntries)
            record["retainedRegions"] = JsonPrimitive(capture.regions)
            record["retainedLiteralPayloadBytes"] = JsonPrimitive(capture.literalBytes)
            record["preparationNs"] = JsonPrimitive(preparationNs)
            report.appendText("${JsonObject(record)}\n")
            capture.clear()
        }
        independentReplicas(problem, context, seed, work, stop, bean, report)
        qualifyTasks(problem, context, splits, seed, work, stop, bean, report)
        qualifyCosts(problem, context, splits, seed, work, costRepeats, stop, bean, report, slices)
        out.resolve("completed.json").writeText(buildJsonObject {
            put("sourceSha256", sha256(bytes))
            put("revision", Reports.readGitSha())
            put("javaVersion", System.getProperty("java.version"))
            put("osArch", System.getProperty("os.arch"))
            put("processors", Runtime.getRuntime().availableProcessors())
            put("maxHeapBytes", Runtime.getRuntime().maxMemory())
            put("elapsedNs", System.nanoTime() - started)
            put("emergencyStopped", stop())
            put("independentlyVerifiedUnsatOrOptimality", false)
            put("runtimeScheduler", false)
        }.toString())
    }

    private fun qualifyCosts(
        problem: BakedProblem, context: BooleanProofContext, splits: Int, seed: Long, work: Long,
        repeats: Int, stop: Cancellation, bean: ThreadMXBean, report: File,
        slices: WorkSlices,
    ) {
        if (repeats == 0) return
        val process = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        for (iteration in -2 until repeats) {
            val order = listOf("f1", "cubes", "pins")
            val offset = (iteration + 2) % order.size
            val rotated = order.drop(offset) + order.take(offset)
            val modes = if (iteration and 1 == 0) rotated else rotated.reversed()
            for (mode in modes) {
                if (stop()) return
                val beforeCpu = process.processCpuTime
                val beforeAllocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
                val begin = System.nanoTime()
                val (results, complete) = costQueries(mode, problem, context, splits, seed, work, stop, slices)
                val elapsed = System.nanoTime() - begin
                val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeAllocated
                val cpu = process.processCpuTime - beforeCpu
                val record = buildJsonObject {
                    put("mode", "cost-$mode")
                    put("iteration", iteration)
                    put("warmup", iteration < 0)
                    put("nativeCoverageComplete", complete)
                    put("inclusiveControlElapsedNs", elapsed)
                    put("inclusiveControlProcessCpuNs", cpu)
                    put("threadAllocatedBytes", allocated)
                    put("decisions", results.sumOf { it.stats.search.nodes.sum })
                    put("propagationWork", results.sumOf { it.stats.search.propagationWork.sum })
                    put("rootPropagationWork", results.sumOf { it.stats.search.rootPropagationWork.sum })
                    put("independentlyVerifiedUnsatOrOptimality", false)
                }
                val storeStarted = System.nanoTime()
                report.appendText("$record\n")
                report.appendText(buildJsonObject {
                    put("mode", "cost-record-store")
                    put("control", mode)
                    put("iteration", iteration)
                    put("elapsedNs", System.nanoTime() - storeStarted)
                }.toString() + "\n")
            }
        }
    }

    private fun costQueries(
        mode: String, problem: BakedProblem, context: BooleanProofContext,
        splits: Int, seed: Long, work: Long, stop: Cancellation, slices: WorkSlices,
    ): Pair<List<SolveResult>, Boolean> {
        if (mode == "f1") {
            val workers = List(2) { worker ->
                PortfolioWorker.of("bt/input-order", worker, BacktrackSolver(problem).session(), params(seed, stop))
            }
            val result = Portfolio.thompson(workers, baseSliceWork = slices.base, maxSliceWork = slices.maximum,
                sliceGrowth = 1.5, reseedStaleThreshold = 0,
                diagnostics = PortfolioDiagnostics(work, seed)).use { it.solve(stop) }
            return listOf(result) to (result is SolveResult.Unsat)
        }
        if (mode == "pins") {
            val count = 1 shl splits
            val results = List(count) { mask ->
                val pins = context.scope.condition.bools + (0 until splits).associate {
                    it to (mask and (1 shl it) != 0)
                }
                BacktrackSolver(problem).solve(params(seed, stop).copy(
                    assumptions = Assumptions(bools = pins), nodeBudget = NodeBudget(maxOf(1L, work / count)),
                ))
            }
            return results to results.all { it is SolveResult.Unsat }
        }
        val tasks = BooleanProofTasks(context, (0 until splits).toList())
        val leases = List(tasks.size) { checkNotNull(tasks.acquire(it)) }
        val results = leases.map { lease ->
            taskQuery(problem, lease, seed, work, tasks.size, stop).also { result ->
                tasks.complete(lease) {
                    if (result is SolveResult.Unsat) BooleanTaskCheck.NativeRefutation else BooleanTaskCheck.Unresolved
                }
            }
        }
        return results to tasks.nativeCoverageComplete.also { tasks.cancel() }
    }

    private fun independentReplicas(
        problem: BakedProblem, context: BooleanProofContext, seed: Long, work: Long,
        stop: Cancellation, bean: ThreadMXBean, report: File,
    ) {
        val capture = BooleanCoverageAttribution(context)
        for (worker in 0..1) {
            if (stop()) break
            val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
            val begin = System.nanoTime()
            val result = BacktrackSolver(problem).solve(params(seed, stop).copy(
                nodeBudget = NodeBudget(maxOf(1L, work / 2)),
                branchObserver = capture.observer(worker, problem.numBoolVars),
            ))
            if (result is SolveResult.Unsat) capture.recordRefutation(context, worker, BooleanCube(emptyList()))
            val record = resultRecord(result, begin, allocated, bean).toMutableMap()
            record["mode"] = JsonPrimitive("independent-replicas")
            record["worker"] = JsonPrimitive(worker)
            record["duplicateEntries"] = JsonPrimitive(capture.duplicateEntries)
            record["duplicateNativeRefutations"] = JsonPrimitive(capture.duplicateRefutations)
            record["refutedRegions"] = JsonPrimitive(capture.refutedRegions)
            report.appendText("${JsonObject(record)}\n")
        }
        capture.clear()
    }

    private fun qualifyTasks(
        problem: BakedProblem, context: BooleanProofContext, splits: Int, seed: Long, work: Long,
        stop: Cancellation, bean: ThreadMXBean, report: File,
    ) {
        val tasks = BooleanProofTasks(context, (0 until splits).toList())
        val retired = checkNotNull(tasks.acquire(0))
        faultQuery(problem, retired, seed, work, tasks.size, stop, bean, report, "lost")
        tasks.loseWorker(0)
        val cancelled = checkNotNull(tasks.acquire(1))
        faultQuery(problem, cancelled, seed, work, tasks.size, stop or Cancellation { true }, bean, report, "cancelled")
        tasks.cancel()
        check(!tasks.complete(retired) { error("lost completion reached checker") })
        check(!tasks.complete(cancelled) { error("cancelled completion reached checker") })
        val stale = checkNotNull(tasks.acquire(2))
        faultQuery(problem, stale, seed, work, tasks.size, stop, bean, report, "stale")
        tasks.replaceContext(context.copy(mapping = "source-identity:1"))
        check(!tasks.complete(stale) { error("stale completion reached checker") })
        val leases = List(tasks.size) { worker -> checkNotNull(tasks.acquire(worker)) }
        for (lease in leases) {
            if (stop()) break
            val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
            val begin = System.nanoTime()
            val result = taskQuery(problem, lease, seed, work, tasks.size, stop)
            val accepted = tasks.complete(lease) { current ->
                check(current.context == tasks.context && current.cube == lease.cube)
                if (result is SolveResult.Unsat) BooleanTaskCheck.NativeRefutation else BooleanTaskCheck.Unresolved
            }
            check(!tasks.complete(lease) { error("duplicate completion reached checker") })
            val record = resultRecord(result, begin, allocated, bean).toMutableMap()
            record["mode"] = JsonPrimitive("protocol-qualification")
            record["task"] = JsonPrimitive(lease.task)
            record["cube"] = JsonArray(lease.cube.literals.map(::JsonPrimitive))
            record["nativeClosureAccepted"] = JsonPrimitive(accepted)
            record["closedTasks"] = JsonPrimitive(tasks.closed)
            record["nativeCoverageComplete"] = JsonPrimitive(tasks.nativeCoverageComplete)
            record["independentlyCheckedCoverageComplete"] = JsonPrimitive(tasks.independentlyCheckedCoverageComplete)
            report.appendText("${JsonObject(record)}\n")
        }
        tasks.cancel()
    }

    private fun faultQuery(
        problem: BakedProblem, lease: BooleanTaskLease, seed: Long, work: Long, tasks: Int,
        stop: Cancellation, bean: ThreadMXBean, report: File, fault: String,
    ) {
        val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
        val begin = System.nanoTime()
        val result = taskQuery(problem, lease, seed, work, tasks, stop)
        val record = resultRecord(result, begin, allocated, bean).toMutableMap()
        record["mode"] = JsonPrimitive("protocol-$fault")
        record["task"] = JsonPrimitive(lease.task)
        record["cube"] = JsonArray(lease.cube.literals.map(::JsonPrimitive))
        record["mapping"] = JsonPrimitive(lease.context.mapping)
        record["completionDiscarded"] = JsonPrimitive(true)
        report.appendText("${JsonObject(record)}\n")
    }

    private fun taskQuery(
        problem: BakedProblem, lease: BooleanTaskLease, seed: Long, work: Long, tasks: Int, stop: Cancellation,
    ): SolveResult {
        require(lease.context.scope.objective == null && lease.context.cutoff == null)
        val condition = lease.context.scope.condition
        require(condition.lower.isEmpty() && condition.upper.isEmpty())
        val pins = condition.bools + lease.cube.literals.associate { Lit.variable(it) to Lit.isPositive(it) }
        return BacktrackSolver(problem).solve(params(seed, stop).copy(
            assumptions = Assumptions(bools = pins), nodeBudget = NodeBudget(maxOf(1L, work / tasks)),
        ))
    }

    private fun params(seed: Long, stop: Cancellation): BacktrackParams = BacktrackParams(
        randomSeed = seed, cancellation = stop, nativeSat = false,
        variableSelector = InputOrder, valueSelector = IndomainMin,
    )

    private fun resultRecord(result: SolveResult, started: Long, allocated: Long, bean: ThreadMXBean): JsonObject =
        buildJsonObject {
            put("result", result::class.simpleName)
            put("elapsedNsIncludingCheckAndRetirement", System.nanoTime() - started)
            put("threadAllocatedBytes", bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - allocated)
            put("heapUsedBytes", ManagementFactory.getMemoryMXBean().heapMemoryUsage.used)
            put("decisions", result.stats.search.nodes.sum)
            put("propagationWork", result.stats.search.propagationWork.sum)
            put("rootPropagationWork", result.stats.search.rootPropagationWork.sum)
            put("learningAndInprocessing", JsonObject(result.stats.search.learning.mapValues {
                JsonPrimitive(it.value)
            }))
            put("independentlyVerifiedUnsatOrOptimality", false)
        }
}
