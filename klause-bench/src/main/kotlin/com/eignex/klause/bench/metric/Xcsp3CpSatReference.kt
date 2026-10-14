package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * XCSP3 reference adapter for CPMpy and OR-Tools cp-sat in the [IMAGE] container.
 * Returns [SolverInvocation.Result] for shared caching and scoring with MiniZinc runs.
 */
internal object Xcsp3CpSatReference {
    const val IMAGE = "klause-xcsp3-cpsat:source-preserving-v2"
    const val VERSION = "xcsp3-source-preserving-v2"

    /** Label on every reference container, so stragglers can be reaped (`docker kill --filter label`). */
    const val CONTAINER_LABEL = "klause-xcsp3-ref"

    /** Hard container memory ceiling. Oversized CPMpy builds are OOM-killed and scored as undecided. */
    private const val MEMORY_LIMIT = "6g"
    private const val DOCKER_INSPECT_WAIT_MS = 5_000L

    /** Unique suffix per container `--name`, so the watchdog can `docker kill` the exact container. */
    private val seq = AtomicLong()

    /** The container's one-line JSON verdict (see `klause-bench/xcsp3-cpsat/solve.py`). */
    @Serializable
    private data class Verdict(
        val exit: String,
        val version: String = "",
        val runtime: Double? = null,
        val objective: Double? = null,
        val maximize: Boolean? = null,
        val error: String? = null,
        val exactObjective: String? = null,
        val seed: Long? = null,
        val workers: Int? = null,
        val cpModelPresolve: Boolean? = null,
    )

    /** Kill any leftover reference containers (from an earlier interrupted run). Best-effort: a
     *  container outlives the JVM that spawned it, so a graceful stop and each run's start reap by
     *  label. Combined with [MEMORY_LIMIT], a hard-killed run's orphans stay bounded and short-lived. */
    fun reapStragglers() {
        runCatching {
            val list = ProcessBuilder("docker", "ps", "-q", "--filter", "label=$CONTAINER_LABEL").start()
            val ids = list.inputStream.bufferedReader().readText().trim()
            list.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS)
            if (ids.isNotEmpty()) {
                ProcessBuilder(listOf("docker", "kill") + ids.lines())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(DOCKER_INSPECT_WAIT_MS * 2, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Whether the converter image is built (`docker image inspect`). */
    fun imageAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("docker", "image", "inspect", IMAGE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    }.getOrElse { false }

    private val image: String by lazy {
        val p = ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}", IMAGE)
            .redirectErrorStream(true).start()
        val id = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0) {
            "xcsp3 cp-sat: cannot identify $IMAGE"
        }
        id
    }

    fun identity(build: String = image): String = "$IMAGE@$build|$VERSION"

    /** Solve [ref]'s XCSP3 `.xml` with cp-sat in the container under [budget], pinning cp-sat to
     *  [workers] search workers (the sweep parallelizes across instances, so 1 keeps a container from
     *  fanning out to every core). The objective sense (`maximize`), unknowable without parsing the
     *  model, is carried back in `stats["maximize"]`. A compressed instance is mounted as a decompressed
     *  staged copy, since the container reads a plain file. */
    fun run(ref: ProblemRef, budget: Budget, workers: Int, seed: Long): SolverInvocation.Result =
        CorpusFiles.withPlainFile(CorpusFetcher.resolve(ref.source)) { run(it, budget, workers, seed) }

    private fun run(xml: File, budget: Budget, workers: Int, seed: Long): SolverInvocation.Result {
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        // The process id keeps names apart across bench processes run side by side, one instance each, as a lab runs
        // them: each counts from 1, and docker refuses a name already in use.
        val name = "$CONTAINER_LABEL-${ProcessHandle.current().pid()}-${seq.incrementAndGet()}"
        val cmd = listOf(
            "docker",
            "run",
            "--rm",
            // The output streams to this process; a container log would also keep it on the VM's disk.
            "--log-driver", "none",
            "--name", name,
            // Hard resource ceilings so no single container can starve the host: memory (OOM-kill a
            // pathological CPMpy model build), swap (= memory, so it can't spill to disk), and CPU
            // (matched to the pinned worker count). Labelled so stragglers can be reaped.
            "--memory", MEMORY_LIMIT,
            "--memory-swap", MEMORY_LIMIT,
            "--cpus", workers.toString(),
            "--label", CONTAINER_LABEL,
            "-v",
            "${xml.parentFile.absolutePath}:/in:ro",
            IMAGE,
            "/in/${xml.name}",
            timeoutSec.toString(),
            workers.toString(),
            seed.toString(),
        )
        // CPMpy's parser chatter goes to stderr. A pipe nobody drains fills and blocks the container until the
        // watchdog kills it, so it goes to a file, whose tail also explains a missing verdict.
        val stderr = File.createTempFile("$CONTAINER_LABEL-", ".err")
        try {
            return solve(cmd, name, budget, stderr)
        } finally {
            stderr.delete()
        }
    }

    private fun solve(cmd: List<String>, name: String, budget: Budget, stderr: File): SolverInvocation.Result {
        val startNanos = System.nanoTime()
        val proc = ProcessBuilder(cmd).redirectError(stderr).start()
        // Watchdog: `docker kill` the CONTAINER (not just the client) if it blows the deadline — CPMpy's
        // parse phase is not time-bounded and can hang/balloon, and killing only the client leaves the
        // container running. Killing the container closes stdout, so the read below can't block forever.
        val watchdog = Thread {
            runCatching {
                if (!proc.waitFor(budget.timeoutMillis * 2 + 20_000, TimeUnit.MILLISECONDS)) {
                    ProcessBuilder("docker", "kill", name)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS)
                    proc.destroyForcibly()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val stdout = proc.inputStream.bufferedReader().readText()
        proc.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS)
        watchdog.interrupt()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        val exit = runCatching { proc.exitValue() }.getOrNull()
        // docker itself failed and the container never ran: an error to retry, not a result to cache.
        check(exit !in DOCKER_RUN_FAILED) {
            "xcsp3 cp-sat: docker run exited $exit: ${stderr.readText().takeLast(ERROR_TAIL_CHARS)}"
        }
        return parse(cmd, stdout, identity(), stderr.readText().takeLast(ERROR_TAIL_CHARS)).copy(elapsedMs = elapsedMs)
    }

    internal fun parse(
        cmd: List<String>,
        stdout: String,
        identity: String,
        error: String = "",
    ): SolverInvocation.Result {
        val v = stdout.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?.let { Reports.json.decodeFromString<Verdict>(it) }
        check(v == null || v.version == VERSION) {
            "xcsp3 cp-sat: incompatible importer '${v?.version}'; rebuild $IMAGE from klause-bench/xcsp3-cpsat"
        }
        val result = when {
            v == null -> undecided(cmd, stdout, "no JSON verdict ($error)")
            v.exit == "ERROR" -> undecided(cmd, stdout, v.error ?: "unknown")
            else -> decided(cmd, stdout, v)
        }
        return result.copy(stats = result.stats + ("referenceVersion" to identity))
    }

    private fun decided(cmd: List<String>, stdout: String, v: Verdict): SolverInvocation.Result {
        val feasible = when (v.exit) {
            "OPTIMAL", "FEASIBLE" -> true
            "UNSATISFIABLE" -> false
            else -> null
        }
        val timeMs = ((v.runtime ?: 0.0) * 1000).toLong()
        return SolverInvocation.Result(
            feasible = feasible,
            objective = v.objective,
            timeToBestMs = timeMs.takeIf { feasible == true },
            timeToFirstFeasibleMs = timeMs.takeIf { feasible == true },
            proven = v.exit == "OPTIMAL" || v.exit == "UNSATISFIABLE",
            stats = mapOf("solveTime" to (v.runtime?.toString() ?: "0"), "maximize" to v.maximize.toString()) +
                listOfNotNull(v.seed?.let { "referenceSeed" to "$it" }, v.workers?.let { "referenceWorkers" to "$it" },
                    v.cpModelPresolve?.let { "cpModelPresolve" to "$it" }).toMap(),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
            exactObjective = v.exactObjective,
        )
    }

    // A container that ran but gave no verdict, from a parse failure, an unsupported constraint or an OOM kill,
    // fails the same way on every run. It is a result, so the bench caches it instead of solving it again.
    private fun undecided(cmd: List<String>, stdout: String, error: String) = SolverInvocation.Result(
        feasible = null,
        objective = null,
        timeToBestMs = null,
        proven = false,
        stats = mapOf("error" to "xcsp3 cp-sat: $error"),
        rawOutput = stdout,
        command = cmd.joinToString(" "),
    )
}

internal const val ERROR_TAIL_CHARS = 200

// `docker run` exit codes for a failure of docker itself or of starting the container.
internal val DOCKER_RUN_FAILED = 125..127
