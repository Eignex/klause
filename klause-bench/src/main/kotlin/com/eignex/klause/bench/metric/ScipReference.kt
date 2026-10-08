package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.klause.formats.mps.Mps
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import com.eignex.klause.ir.ObjectiveSense as ObjectiveDirection

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

    /** SCIP's tolerances and gap limit, its defaults: recorded with every row and part of its cache identity. */
    internal const val OPTIONS = "numerics/feastol=1e-06,numerics/epsilon=1e-09,limits/gap=0"

    /** The image a result came from, by id, so a rebuilt SCIP never replays an older build's results. */
    private val image: String by lazy {
        runCatching {
            val p = ProcessBuilder(
                "docker",
                "image",
                "inspect",
                "--format",
                "{{.Id}}",
                IMAGE,
            ).redirectErrorStream(true).start()
            val id = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS)
            id.takeIf { p.exitValue() == 0 }
        }.getOrNull() ?: "unknown"
    }

    /** What a cached result depends on: the image, the options and the validation rules. */
    fun identity(build: String = image): String = "$IMAGE@$build|$OPTIONS|${MpsWitness.VERSION}"

    /** Solve [ref] (an MPS instance) with SCIP under [budget], single-threaded. The instance is piped on
     *  stdin and read as MPS; SCIP's objective sense (from the model's `OBJSENSE`) orients the reported
     *  bound. Its solution is displayed after the solve and checked against the model ([MpsWitness]), a repair
     *  going through [HighsReference.repair]. */
    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result {
        val text = CorpusFiles.readText(CorpusFetcher.resolve(ref.source))
        val model = runCatching { Mps.parse(text) }.getOrNull()
        // MPS default is minimise; an `OBJSENSE MAXIMIZE` flips it. SCIP reports the bound in this
        // orientation, so record it for the entry (and virtual-best comparison).
        val maximize = model?.sense == ObjectiveDirection.MAXIMIZE
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
            "-c", "display solution",
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
        val exit = runCatching { proc.exitValue() }.getOrNull()
        // docker itself failed and the container never ran: an error to retry, not a result to cache.
        check(exit !in DOCKER_RUN_FAILED) {
            "scip: docker run exited $exit: ${proc.errorStream.bufferedReader().readText().takeLast(ERROR_TAIL_CHARS)}"
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        val claim = parseClaim(stdout)
        val attempt = MpsAttempt(
            label = "default",
            stdout = stdout,
            elapsedMs = elapsedMs,
            command = cmd.joinToString(" "),
            claim = claim,
            verdict = MpsReference.judge(model, claim, HighsReference.repair.takeIf { HighsReference.available() }),
            reported = lines(
                stdout,
            ).lastOrNull {
                it.startsWith(
                    "SCIP Status",
                )
            }?.let { mapOf("solverStatus" to it.substringAfter(':').trim()) }.orEmpty(),
        )
        return MpsReference.result(listOf(attempt), model, maximize, identity(), OPTIONS)
    }

    /**
     * SCIP's `optimize` summary reports `SCIP Status : … [optimal solution found] / [infeasible] / …`, a `Primal Bound`
     * (at or beyond infinity when it has none), a `Dual Bound` and a `Gap`; `display solution` then lists the
     * solution's nonzero values after `objective value:`, or says `no solution available`.
     */
    internal fun parseClaim(stdout: String): MpsWitness.Claim {
        val lines = lines(stdout)
        val status = lines.lastOrNull { it.startsWith("SCIP Status") }.orEmpty()
        fun bound(name: String) = lines.lastOrNull { it.startsWith(name) }
            ?.substringAfter(":")?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.takeIf { abs(it) < SCIP_INFINITY }
        val start = lines.indexOfLast { it.startsWith("objective value:") }
        val assignment = if (start < 0) {
            null
        } else {
            val values = lines.drop(start + 1).map { SOLUTION_LINE.matchEntire(it) }.takeWhile { it != null }
            values.filterNotNull().associate { match ->
                val (name, value) = match.destructured
                name to value.toDouble()
            }
        }
        return MpsWitness.Claim(
            status = when {
                "optimal solution found" in status -> MpsWitness.Status.OPTIMAL
                "[infeasible]" in status -> MpsWitness.Status.INFEASIBLE
                "limit reached" in status || "interrupted" in status -> MpsWitness.Status.LIMIT
                else -> MpsWitness.Status.UNKNOWN
            },
            primal = bound("Primal Bound"),
            dual = bound("Dual Bound"),
            gap = lines.lastOrNull { it.startsWith("Gap") }?.substringAfter(":")?.trim()?.substringBefore(' ')
                ?.toDoubleOrNull()?.div(PERCENT),
            assignment = assignment,
        )
    }

    private fun lines(stdout: String) = stdout.lineSequence().map { it.trim() }.toList()

    /** One value `display solution` lists: the variable, its value, and its objective coefficient. */
    private val SOLUTION_LINE = Regex("""(\S+)\s+(\S+)\s+\(obj:[^)]*\)""")
    private const val PERCENT = 100.0
}
