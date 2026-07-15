package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.mps.Mps
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * The MPS (MIP) reference. cp-sat, clasp and z3 read no MPS, so mixed-integer programs are solved by
 * SCIP (Apache-2.0 since v8) in the [IMAGE] container — a strong branch-and-cut solver that reads MPS
 * natively, proves optima, and reports a primal/dual bound. Its rows are written to their own
 * `reference/scip.csv`, the MIP oracle alongside the cp-sat (MiniZinc/XCSP3), clasp (DIMACS/OPB) and
 * z3 (SMT-LIB) tables.
 *
 * SCIP reads the instance from `/dev/stdin` (so no bind mount is needed): the JVM pipes the `.mps` in
 * and drives SCIP with a batch command line (`read`/`optimize`/`quit`). Everything else mirrors
 * [ClaspReference]: a memory-capped container, a watchdog that `docker kill`s a runaway by name, and
 * label-based reaping.
 */
internal object ScipReference {
    const val IMAGE = "klause-scip:latest"

    /** Label on every reference container, so stragglers can be reaped (`docker kill --filter label`). */
    const val CONTAINER_LABEL = "klause-scip-ref"

    /** Hard per-container memory ceiling — mirrors the clasp/cp-sat oracles so no single SCIP run can
     *  exhaust the host; docker OOM-kills it and the instance is scored undecided. */
    private const val MEMORY_LIMIT = "6g"

    /** MB form of [MEMORY_LIMIT] for SCIP's own `set limits memory` (belt-and-braces with docker). */
    private const val MEMORY_LIMIT_MB = "6000"
    private const val DOCKER_INSPECT_WAIT_MS = 5_000L

    /** SCIP's `infinity` (1e20); a primal bound at or above it means no incumbent was found. */
    private const val SCIP_INFINITY = 1e19

    /** Unique suffix per container `--name`, so the watchdog can `docker kill` the exact container. */
    private val seq = AtomicLong()

    /** Kill any leftover reference containers (from an earlier interrupted run). Best-effort, by label. */
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

    /** Whether the SCIP image is built (`docker image inspect`). */
    fun imageAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("docker", "image", "inspect", IMAGE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    }.getOrElse { false }

    /** Solve [ref] (an MPS instance) with SCIP under [budget], single-threaded. The instance is piped on
     *  stdin and read as MPS; SCIP's objective sense (from the model's `OBJSENSE`) orients the reported
     *  bound. */
    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result {
        val text = CorpusFetcher.resolve(ref.source).readText()
        // MPS default is minimise; an `OBJSENSE MAXIMIZE` flips it. SCIP reports the bound in this
        // orientation, so record it for the entry (and virtual-best comparison).
        val maximize = runCatching { Mps.parse(text).sense == ObjectiveSense.MAXIMIZE }.getOrDefault(false)
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val name = "$CONTAINER_LABEL-${seq.incrementAndGet()}"
        val cmd = listOf(
            "docker",
            "run",
            "--rm",
            "-i",
            "--name", name,
            // Hard resource ceilings so no single container can starve the host: memory (see
            // [MEMORY_LIMIT]) and one CPU, with SCIP itself pinned single-threaded — the sweep's
            // parallelism is its concurrent jobs, not per-solve threads, so the host load stays bounded.
            "--memory", MEMORY_LIMIT,
            "--memory-swap", MEMORY_LIMIT,
            "--cpus", "1",
            "--label", CONTAINER_LABEL,
            IMAGE,
            "-c", "set limits time $timeoutSec",
            "-c", "set limits memory $MEMORY_LIMIT_MB",
            "-c", "read /dev/stdin mps",
            "-c", "optimize",
            "-c", "quit",
        )
        val startNanos = System.nanoTime()
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        // Feed stdin on its own thread: SCIP streams progress while parsing, so writing all of stdin
        // before reading stdout could deadlock on the pipe buffers.
        val writer = Thread {
            runCatching { proc.outputStream.use { it.write(text.toByteArray()) } }
        }.apply {
            isDaemon = true
            start()
        }
        // Watchdog: `docker kill` the container past the deadline, so a solve that ignores its time limit
        // can't hang the sweep; killing it closes stdout so the read can't block.
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
        writer.interrupt()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        return parse(stdout, elapsedMs, cmd, maximize)
    }

    /** SCIP's `optimize` summary reports `SCIP Status : … [optimal solution found] / [infeasible] / …`
     *  and a `Primal Bound : <value>` (its best incumbent, or ≥ infinity when none). Map to the
     *  [SolverInvocation.Result] the reference sweep scores: an optimum/infeasible is a proof, an
     *  incumbent without proof is a feasible witness, anything else is undecided. */
    internal fun parse(stdout: String, elapsedMs: Long, cmd: List<String>, maximize: Boolean): SolverInvocation.Result {
        val lines = stdout.lineSequence().map { it.trim() }.toList()
        val status = lines.lastOrNull { it.startsWith("SCIP Status") }.orEmpty()
        val optimal = status.contains("optimal solution found")
        val infeasible = status.contains("[infeasible]")
        val primal = lines.lastOrNull { it.startsWith("Primal Bound") }
            ?.substringAfter(":")?.trim()?.substringBefore(' ')?.toDoubleOrNull()
        val objective = primal?.takeIf { abs(it) < SCIP_INFINITY }
        val feasible = when {
            infeasible -> false
            objective != null -> true
            else -> null
        }
        val proven = optimal || infeasible
        val timeMs = elapsedMs.takeIf { feasible == true }
        return SolverInvocation.Result(
            feasible = feasible,
            objective = objective.takeIf { feasible == true },
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = proven,
            // Seconds, so [BenchCli.solveReference] derives the proof/first-feasible time uniformly.
            stats = mapOf("solveTime" to (elapsedMs / 1000.0).toString(), "maximize" to maximize.toString()),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }
}
