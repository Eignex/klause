package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The XCSP3 cp-sat reference. `minizinc --solver cp-sat` can only read FlatZinc and OR-Tools ships no
 * XCSP3 frontend, so an XCSP3 instance is solved by the [IMAGE] container: CPMpy (the OR-Tools cp-sat
 * modelling lib that won the XCSP3 2024 cp-sat track) reads the `.xml` and solves it with cp-sat
 * directly — the same engine used for MiniZinc, so the reference table stays a single cp-sat oracle.
 * Python lives only in the container (mirroring the vizier one); this returns a [SolverInvocation.Result]
 * so the reference sweep caches and scores XCSP3 exactly like the MiniZinc path.
 */
internal object Xcsp3CpSatReference {
    const val IMAGE = "klause-xcsp3-cpsat:latest"

    /** Label on every reference container, so stragglers can be reaped (`docker kill --filter label`). */
    const val CONTAINER_LABEL = "klause-xcsp3-ref"

    /** Hard per-container memory ceiling. CPMpy's XCSP3 model build blows up on a few pathological
     *  instances (one reached 50 GB); this cap makes docker OOM-kill such a container — the instance is
     *  scored as undecided and skipped — so a runaway can never exhaust the host. */
    private const val MEMORY_LIMIT = "6g"
    private const val DOCKER_INSPECT_WAIT_MS = 5_000L

    /** Unique suffix per container `--name`, so the watchdog can `docker kill` the exact container. */
    private val seq = AtomicLong()

    /** The container's one-line JSON verdict (see `klause-bench/xcsp3-cpsat/solve.py`). */
    @Serializable
    private data class Verdict(
        val exit: String,
        val runtime: Double? = null,
        val objective: Double? = null,
        val maximize: Boolean? = null,
        val error: String? = null,
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

    /** Solve [ref]'s XCSP3 `.xml` with cp-sat in the container under [budget], pinning cp-sat to
     *  [workers] search workers (the sweep parallelizes across instances, so 1 keeps a container from
     *  fanning out to every core). The objective sense (`maximize`), unknowable without parsing the
     *  model, is carried back in `stats["maximize"]`. */
    fun run(ref: ProblemRef, budget: Budget, workers: Int): SolverInvocation.Result {
        val xml = CorpusFetcher.resolve(ref.source)
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val name = "$CONTAINER_LABEL-${seq.incrementAndGet()}"
        val cmd = listOf(
            "docker",
            "run",
            "--rm",
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
        val exit = runCatching { proc.exitValue() }.getOrNull()
        // docker itself failed and the container never ran: an error to retry, not a result to cache.
        check(exit !in DOCKER_RUN_FAILED) {
            "xcsp3 cp-sat: docker run exited $exit: ${stderr.readText().takeLast(ERROR_TAIL_CHARS)}"
        }
        val v = stdout.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?.let { Reports.json.decodeFromString<Verdict>(it) }
        return when {
            v == null -> undecided(cmd, stdout, "no JSON verdict (${stderr.readText().takeLast(ERROR_TAIL_CHARS)})")
            v.exit == "ERROR" -> undecided(cmd, stdout, v.error ?: "unknown")
            else -> decided(cmd, stdout, v)
        }
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
            stats = mapOf("solveTime" to (v.runtime?.toString() ?: "0"), "maximize" to v.maximize.toString()),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
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

private const val ERROR_TAIL_CHARS = 200

// `docker run` exit codes for a failure of docker itself or of starting the container.
private val DOCKER_RUN_FAILED = 125..127
