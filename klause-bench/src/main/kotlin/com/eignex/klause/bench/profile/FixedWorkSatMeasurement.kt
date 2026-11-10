package com.eignex.klause.bench.profile

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.ValueSelector
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.portfolio.BacktrackCatalog
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.Writer
import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/** The trajectory or charged-work quantity a fixed-work limit counts. */
@Serializable
enum class FixedWorkUnit(val label: String) {
    /** Conflicts observed through the value selector. */
    CONFLICTS("conflicts"),

    /** Committed decisions observed through the value selector. */
    DECISIONS("decisions"),

    /** The resumable handle's charged work, in the units its slices budget. */
    WORK("work"),
    ;

    companion object {
        /** The unit named [label]. */
        fun of(label: String): FixedWorkUnit = requireNotNull(entries.firstOrNull { it.label == label }) {
            "Unknown fixed-work unit: $label"
        }
    }
}

/** One child-process measurement: [warmups] identical invocations precede the measured one. */
@Serializable
data class FixedWorkRequest(
    /** Plain DIMACS source copy. */
    val source: String,
    /** SHA-256 of the plain source bytes. */
    val sourceSha256: String,
    /** Solver seed. */
    val seed: Long,
    /** Backtrack catalog recipe label. */
    val recipe: String,
    /** Quantity [limit] counts. */
    val unit: FixedWorkUnit,
    /** Fixed-work limit in [unit]. */
    val limit: Long,
    /** Emergency deadline for each invocation, covering preparation and search. */
    val deadlineMillis: Long,
    /** Invocations before the measured one in the same JVM. */
    val warmups: Int,
    /** Retained trajectory rows for the measured invocation. */
    val traceRows: Int,
)

/** Child JVM entry: `REQUEST.json OUTPUT-DIRECTORY`. */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: REQUEST.json OUTPUT-DIRECTORY" }
    val request = Reports.json.decodeFromString(FixedWorkRequest.serializer(), File(args[0]).readText())
    val out = File(args[1])
    val bytes = File(request.source).readBytes()
    require(sha256(bytes) == request.sourceSha256) { "Source bytes changed" }
    out.resolve(FixedWorkSatMeasurement.RUNTIME).writeText(FixedWorkSatMeasurement.runtime().toString() + "\n")
    val records = out.resolve(FixedWorkSatMeasurement.INVOCATIONS)
    records.writeText("")
    for (index in 0..request.warmups) {
        val measured = index == request.warmups
        val trace = if (measured && request.traceRows > 0) out.resolve(FixedWorkSatMeasurement.TRACE) else null
        val record = FixedWorkSatMeasurement.invoke(bytes, request, index, measured, trace)
        records.appendText(record.toString() + "\n")
    }
}

/** Fixed-work native backtrack invocations with trajectory digests and separated preparation costs. */
object FixedWorkSatMeasurement {
    /** Per-invocation JSON lines. */
    const val INVOCATIONS = "invocations.jsonl"

    /** Measured invocation's retained trajectory rows. */
    const val TRACE = "trace.tsv"

    /** Child JVM identity. */
    const val RUNTIME = "runtime.json"

    /** Record schema identifier. */
    const val SCHEMA = "fixed-work-sat-v1"

    /**
     * Prepare [bytes] and search under [request]'s fixed-work limit, stopping early on a verdict or
     * [deadline]. The measured interval spans opening, slicing and closing the resumable handle.
     */
    fun invoke(
        bytes: ByteArray,
        request: FixedWorkRequest,
        index: Int,
        measured: Boolean,
        traceFile: File?,
        deadline: Cancellation = Cancellation.after(request.deadlineMillis.milliseconds),
    ): JsonObject {
        val writer = traceFile?.bufferedWriter()
        val trajectory = Trajectory(request.unit, request.limit, if (writer == null) 0 else request.traceRows, writer)
        val preparation = Meter()
        val model = try {
            Dimacs.parse(bytes.decodeToString()).toProblem().bake(cancellation = deadline)
        } catch (failure: RuntimeException) {
            if (!deadline()) throw failure
            null
        } finally {
            preparation.stop()
        }
        val search = Meter()
        trajectory.start(search)
        var result: SolveResult? = null
        var work = 0L
        var initialWork = 0L
        var stats = SolveStats.EMPTY
        var limitReached = false
        try {
            if (model != null && !deadline()) {
                val base = BacktrackCatalog.byLabel(request.recipe).build(request.seed, null)
                val stop = if (request.unit == FixedWorkUnit.WORK) deadline else {
                    deadline or Cancellation { trajectory.limitReached }
                }
                val params = base.copy(
                    nativeSat = true,
                    cancellation = stop,
                    variableSelector = Variables(base.variableSelector, trajectory),
                    valueSelector = Values(base.valueSelector, trajectory),
                    onEvent = { event ->
                        base.onEvent?.invoke(event)
                        trajectory.event(event)
                    },
                )
                val handle = BacktrackSolver(model).resumableSolve(params)
                try {
                    initialWork = handle.initialWork
                    while (result == null && !deadline()) {
                        if (request.unit == FixedWorkUnit.WORK) {
                            val remaining = request.limit - handle.work
                            if (remaining <= 0L) break
                            result = handle.runSlice(stop, Long.MAX_VALUE, remaining)
                        } else {
                            if (trajectory.limitReached) break
                            result = handle.runSlice(stop, Long.MAX_VALUE, -1L)
                        }
                    }
                    work = handle.work
                    if (request.unit == FixedWorkUnit.WORK && work >= request.limit) trajectory.markLimit()
                    limitReached = trajectory.limitReached
                } finally {
                    handle.close()
                }
                stats = handle.stats
            }
        } catch (failure: RuntimeException) {
            // The engine aborts handle construction when its cancellation fires before the search opens.
            if (!deadline()) throw failure
        } finally {
            search.stop()
            writer?.close()
        }
        val stopReason = when {
            result is SolveResult.Sat || result is SolveResult.Unsat -> "verdict"
            limitReached -> "limit"
            else -> "deadline"
        }
        val counters = stats.search
        return buildJsonObject {
            put("schema", SCHEMA)
            put("role", if (measured) "measured" else "warmup")
            put("index", index)
            put("jvmState", if (index == 0) "cold" else "warm")
            put("seed", request.seed)
            put("recipe", request.recipe)
            put("unit", request.unit.label)
            put("limit", request.limit)
            put("deadlineMillis", request.deadlineMillis)
            put("engine", buildJsonObject {
                put("kind", "resumable-backtrack")
                put("nativeSatRequested", true)
                put("sliceMillis", Long.MAX_VALUE)
                put("presolve", false)
                put("localSearch", false)
                put("portfolio", false)
            })
            put("sourceSha256", request.sourceSha256)
            put("sourceBooleanVariables", model?.numBoolVars)
            put("sourceIntegerVariables", model?.numIntVars)
            put("sourceFactors", model?.numFactors)
            put("preparation", preparation.json())
            put("measured", search.json())
            put("limitPoint", trajectory.limitPoint())
            put("stop", stopReason)
            put("limitReached", limitReached)
            put("interrupted", stopReason == "deadline")
            put("comparable", stopReason != "deadline")
            put("result", result?.let { it::class.simpleName } ?: "Paused")
            put("unknownReason", (result as? SolveResult.Unknown)?.reason?.name)
            put("counters", buildJsonObject {
                put("work", work)
                put("initialWork", initialWork)
                put("decisions", counters.nodes.sum.toLong())
                put("failures", counters.fails.sum.toLong())
                put("restarts", counters.restarts.sum.toLong())
                put("propagationWork", counters.propagationWork.sum.toLong())
                put("rootPropagationWork", counters.rootPropagationWork.sum.toLong())
            })
            put("trajectory", trajectory.json())
            put("nativeSatClaim", result is SolveResult.Sat)
            put("nativeUnsatClaim", result is SolveResult.Unsat)
            put("independentSourceValidation", false)
            put("performanceQualified", false)
        }
    }

    /** JVM, operating system and processor identity for the child process. */
    fun runtime(): JsonObject {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val cpu = runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
            }
        }.getOrNull()
        return buildJsonObject {
            put("javaVersion", System.getProperty("java.runtime.version"))
            put("javaVendor", System.getProperty("java.vm.vendor"))
            put("vmName", runtime.vmName)
            put("vmVersion", runtime.vmVersion)
            put("inputArguments", JsonArray(runtime.inputArguments.map(::JsonPrimitive)))
            put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
            put("arch", System.getProperty("os.arch"))
            put("availableProcessors", Runtime.getRuntime().availableProcessors())
            put("cpuModel", cpu)
            put("threadCpuSupported", Meter.threadCpu() != null)
            put("allocationSupported", Meter.allocated() != null)
        }
    }

    private class Variables(private val delegate: VariableSelector, private val trajectory: Trajectory) :
        VariableSelector by delegate {
        override fun fresh(): VariableSelector = Variables(delegate.fresh(), trajectory)

        override fun pick(session: PropagationSession, rng: Random): VarRef? =
            delegate.pick(session, rng).also { trajectory.picked(it, session) }
    }

    private class Values(private val delegate: ValueSelector, private val trajectory: Trajectory) :
        ValueSelector by delegate {
        override fun fresh(): ValueSelector = Values(delegate.fresh(), trajectory)

        override fun onCommit(varRef: VarRef, value: Long) {
            delegate.onCommit(varRef, value)
            trajectory.committed(varRef, value)
        }

        override fun onConflict(varRef: VarRef, value: Long) {
            delegate.onConflict(varRef, value)
            trajectory.conflicted(varRef, value)
        }
    }

    /** Wall, thread-CPU, process-CPU, allocation, collection and compilation deltas from construction. */
    internal class Meter {
        private val wall = System.nanoTime()
        private val thread = threadCpu()
        private val process = processCpu()
        private val bytes = allocated()
        private val collections = collections()
        private val compilation = compilation()
        private var end: JsonObject? = null

        fun elapsedNs(): Long = System.nanoTime() - wall

        fun threadCpuNs(): Long? = threadCpu()?.let { now -> thread?.let { now - it } }

        fun stop() {
            if (end != null) return
            val elapsed = elapsedNs()
            val threadCpu = threadCpuNs()
            val processCpu = processCpu()?.let { now -> process?.let { now - it } }
            val allocated = allocated()?.let { now -> bytes?.let { now - it } }
            val (count, millis) = collections()
            val compiled = compilation()?.let { now -> compilation?.let { now - it } }
            end = buildJsonObject {
                put("elapsedNs", elapsed)
                put("threadCpuNs", threadCpu)
                put("processCpuNs", processCpu)
                put("allocatedBytes", allocated)
                put("gcCount", count - collections.first)
                put("gcMillis", millis - collections.second)
                put("compilationMillis", compiled)
            }
        }

        fun json(): JsonObject = checkNotNull(end) { "Meter is still running" }

        companion object {
            private val threads = ManagementFactory.getThreadMXBean()
            private val sunThreads = threads as? com.sun.management.ThreadMXBean
            private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            private val compiler = ManagementFactory.getCompilationMXBean()

            fun threadCpu(): Long? = if (threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled) {
                threads.currentThreadCpuTime.takeIf { it >= 0L }
            } else null

            fun processCpu(): Long? = os?.processCpuTime?.takeIf { it >= 0L }

            fun allocated(): Long? = sunThreads?.takeIf { it.isThreadAllocatedMemorySupported &&
                it.isThreadAllocatedMemoryEnabled }?.currentThreadAllocatedBytes?.takeIf { it >= 0L }

            fun collections(): Pair<Long, Long> = ManagementFactory.getGarbageCollectorMXBeans().fold(0L to 0L) {
                    (count, millis), bean -> (count + bean.collectionCount.coerceAtLeast(0L)) to
                    (millis + bean.collectionTime.coerceAtLeast(0L))
            }

            fun compilation(): Long? = compiler?.takeIf { it.isCompilationTimeMonitoringSupported }?.totalCompilationTime
        }
    }

    /**
     * Ordered pick/commit/conflict/restart/sweep events folded into a 128-bit digest, with power-of-two
     * checkpoints and the first [rowLimit] rows written to [writer]. The digest covers every event.
     */
    internal class Trajectory(
        private val unit: FixedWorkUnit,
        private val limit: Long,
        private val rowLimit: Int,
        private val writer: Writer?,
    ) {
        private var low = LOW_SEED
        private var high = HIGH_SEED
        private var events = 0L
        private var picks = 0L
        private var decisions = 0L
        private var conflicts = 0L
        private var restarts = 0L
        private var sweeps = 0L
        private var written = 0L
        private var nextCheckpoint = FIRST_CHECKPOINT
        private val checkpoints = ArrayList<JsonObject>()
        private var meter: Meter? = null
        private var limitSnapshot: JsonObject? = null
        private var prefix: JsonObject? = null

        var limitReached = false
            private set

        fun start(meter: Meter) { this.meter = meter }

        fun picked(variable: VarRef?, session: PropagationSession) {
            picks++
            row('P', kind(variable), variable?.varId?.toLong() ?: -1L)
            if (decisions % LEARNED_CHECKPOINT == 0L) row('K', decisions, session.learnedClauseCount.toLong())
        }

        fun committed(variable: VarRef, value: Long) {
            decisions++
            row('C', kind(variable), variable.varId.toLong(), value)
            if (unit == FixedWorkUnit.DECISIONS && decisions == limit) markLimit()
        }

        fun conflicted(variable: VarRef, value: Long) {
            conflicts++
            row('F', kind(variable), variable.varId.toLong(), value)
            if (unit == FixedWorkUnit.CONFLICTS && conflicts == limit) markLimit()
        }

        fun event(event: SearchEvent) {
            when (event) {
                is SearchEvent.Restart -> {
                    restarts++
                    row('R', event.index, event.steps)
                }
                is SearchEvent.LearnedDbSweep -> {
                    sweeps++
                    row('S', event.kept.toLong(), event.dropped.toLong())
                }
                else -> Unit
            }
        }

        fun markLimit() {
            if (limitReached) return
            limitReached = true
            val current = meter
            limitSnapshot = buildJsonObject {
                put("elapsedNs", current?.elapsedNs())
                put("threadCpuNs", current?.threadCpuNs())
            }
            prefix = counts()
        }

        fun limitPoint(): JsonObject = limitSnapshot?.let { point ->
            buildJsonObject {
                put("reached", true)
                point.forEach { (key, value) -> put(key, value) }
            }
        } ?: buildJsonObject {
            put("reached", false)
            put("elapsedNs", JsonNull)
            put("threadCpuNs", JsonNull)
        }

        fun json(): JsonObject = buildJsonObject {
            counts().forEach { (key, value) -> put(key, value) }
            put("prefix", prefix ?: JsonNull)
            put("checkpoints", JsonArray(checkpoints))
            put("rowLimit", rowLimit)
            put("rowsWritten", written)
            put("rowsTruncated", writer != null && events > written)
        }

        private fun counts(): JsonObject = buildJsonObject {
            put("events", events)
            put("picks", picks)
            put("decisions", decisions)
            put("conflicts", conflicts)
            put("restarts", restarts)
            put("sweeps", sweeps)
            put("digest", digest())
        }

        private fun digest(): String = "%016x%016x".format(high, low)

        private fun row(tag: Char, vararg values: Long) {
            fold(tag.code.toLong())
            for (value in values) fold(value)
            events++
            if (events == nextCheckpoint) {
                checkpoints += buildJsonObject {
                    put("events", events)
                    put("digest", digest())
                }
                nextCheckpoint = if (nextCheckpoint > Long.MAX_VALUE / 2) Long.MAX_VALUE else nextCheckpoint * 2
            }
            val output = writer ?: return
            if (written >= rowLimit) return
            output.append(tag)
            for (value in values) output.append('\t').append(value.toString())
            output.append('\n')
            written++
        }

        private fun fold(value: Long) {
            low = mix(low xor value) * LOW_MULTIPLIER
            high = mix(high + value) xor (high ushr HIGH_SHIFT)
        }

        private fun kind(variable: VarRef?): Long = when (variable) {
            null -> 0L
            is VarRef.Bool -> 1L
            is VarRef.IntVar -> 2L
        }

        private companion object {
            const val LOW_SEED = 0x6a09e667f3bcc908L
            const val HIGH_SEED = -0x4498517a7b3558c5L
            const val LOW_MULTIPLIER = -0x61c8864680b583ebL
            const val HIGH_SHIFT = 29
            const val FIRST_CHECKPOINT = 1024L
            const val LEARNED_CHECKPOINT = 256L

            fun mix(value: Long): Long {
                var z = value
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                return z xor (z ushr 31)
            }
        }
    }
}
