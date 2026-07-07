package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
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
        val json = stdout.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: error("xcsp3 cp-sat: no JSON verdict (${stdout.take(200)})")
        val v = Reports.json.decodeFromString<Verdict>(json)
        if (v.exit == "ERROR") error("xcsp3 cp-sat: ${v.error ?: "unknown"}")
        val feasible = when (v.exit) {
            "OPTIMAL", "FEASIBLE" -> true
            "UNSATISFIABLE" -> false
            else -> null
        }
        val proven = v.exit == "OPTIMAL" || v.exit == "UNSATISFIABLE"
        val timeMs = ((v.runtime ?: 0.0) * 1000).toLong()
        return SolverInvocation.Result(
            feasible = feasible,
            objective = v.objective,
            timeToBestMs = timeMs.takeIf { feasible == true },
            timeToFirstFeasibleMs = timeMs.takeIf { feasible == true },
            proven = proven,
            stats = mapOf("solveTime" to (v.runtime?.toString() ?: "0"), "maximize" to v.maximize.toString()),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }
}
