package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The DIMACS / OPB reference. cp-sat (OR-Tools) has no native SAT/pseudo-Boolean frontend, so these two
 * Boolean formats are solved by clasp (Potassco) in the [IMAGE] container — a strong complete SAT/PB
 * solver that reads DIMACS CNF and OPB directly, decides SAT/UNSAT, and minimises an OPB `min:` objective
 * to proven optimum. The reference table keys each row by its actual solver (`clasp`), so a Boolean
 * oracle coexists with the cp-sat MiniZinc/XCSP3 oracle in one `references.csv`.
 *
 * clasp reads the instance on stdin (so no bind mount is needed). DIMACS is fed verbatim; OPB needs the
 * standard `* #variable= N #constraint= M` problem line, which the vendored instances omit, so it is
 * synthesized from the source before piping. Everything else mirrors [Xcsp3CpSatReference]: a
 * memory-capped container, a watchdog that `docker kill`s a runaway by name, and label-based reaping.
 */
internal object ClaspReference {
    const val IMAGE = "klause-clasp:latest"

    /** Label on every reference container, so stragglers can be reaped (`docker kill --filter label`). */
    const val CONTAINER_LABEL = "klause-clasp-ref"

    /** Hard per-container memory ceiling — mirrors the cp-sat oracle so no single clasp run (a huge SAT
     *  instance's clause DB can balloon) can exhaust the host; docker OOM-kills it and the instance is
     *  scored undecided. */
    private const val MEMORY_LIMIT = "6g"
    private const val DOCKER_INSPECT_WAIT_MS = 5_000L

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

    /** Whether the clasp image is built (`docker image inspect`). */
    fun imageAvailable(): Boolean = runCatching {
        val p = ProcessBuilder("docker", "image", "inspect", IMAGE)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    }.getOrElse { false }

    /** Solve [ref] (DIMACS or OPB) with clasp under [budget], pinning it to [workers] threads. The
     *  instance is piped on stdin (OPB gets its problem line synthesized first). Objective sense is
     *  always minimise for OPB (clasp's only PB mode); DIMACS has none — both report `maximize=false`. */
    fun run(ref: ProblemRef, budget: Budget, workers: Int): SolverInvocation.Result {
        val text = CorpusFetcher.resolve(ref.source).readText()
        val input = if (ref.format == Format.OPB) opbWithProblemLine(text) else text
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val name = "$CONTAINER_LABEL-${seq.incrementAndGet()}"
        val cmd = listOf(
            "docker",
            "run",
            "--rm",
            "-i",
            "--name", name,
            // Hard resource ceilings so no single container can starve the host (see [MEMORY_LIMIT]).
            "--memory", MEMORY_LIMIT,
            "--memory-swap", MEMORY_LIMIT,
            "--cpus", workers.toString(),
            "--label", CONTAINER_LABEL,
            IMAGE,
            "--time-limit=$timeoutSec",
            "--parallel-mode=$workers",
        )
        val startNanos = System.nanoTime()
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        // Feed stdin on its own thread: clasp streams progress lines to stdout while parsing a large
        // instance, so writing all of stdin before reading stdout would deadlock on the pipe buffers.
        val writer = Thread {
            runCatching { proc.outputStream.use { it.write(input.toByteArray()) } }
        }.apply {
            isDaemon = true
            start()
        }
        // Watchdog: `docker kill` the container (not just the client) past the deadline, so a solve that
        // ignores `--time-limit` can't hang the sweep; killing it closes stdout so the read can't block.
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
        return parse(stdout, elapsedMs, cmd)
    }

    /** clasp's status is a `s <STATUS>` line; a PB optimisation streams incumbents as `o <cost>` lines
     *  (the last is the best/optimum). Map to the [SolverInvocation.Result] the reference sweep scores. */
    internal fun parse(stdout: String, elapsedMs: Long, cmd: List<String>): SolverInvocation.Result {
        val lines = stdout.lineSequence().map { it.trim() }.toList()
        val status = lines.lastOrNull { it.startsWith("s ") }?.removePrefix("s ")?.trim()
        val bestObjective = lines.filter { it.startsWith("o ") }
            .mapNotNull { it.removePrefix("o ").trim().toDoubleOrNull() }
            .lastOrNull()
        val feasible = when (status) {
            "OPTIMUM FOUND", "SATISFIABLE" -> true
            "UNSATISFIABLE" -> false
            else -> null
        }
        // clasp is complete, so a decision instance (no `o` lines ⇒ it wasn't optimising) is *decided* by
        // a model — SAT is proven, mirroring cp-sat reporting a satisfied CSP as OPTIMAL. A `SATISFIABLE`
        // with `o` lines is an optimisation that found an incumbent but timed out before proving optimum.
        val optimising = bestObjective != null
        val proven = when (status) {
            "OPTIMUM FOUND", "UNSATISFIABLE" -> true
            "SATISFIABLE" -> !optimising
            else -> false
        }
        val timeMs = elapsedMs.takeIf { feasible == true }
        return SolverInvocation.Result(
            feasible = feasible,
            objective = bestObjective.takeIf { feasible == true },
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = proven,
            // Seconds, so [BenchCli.solveReference] derives the proof/first-feasible time uniformly.
            stats = mapOf("solveTime" to (elapsedMs / 1000.0).toString(), "maximize" to "false"),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }

    /** Prepend the standard OPB problem line `* #variable= N #constraint= M` when absent (the vendored
     *  instances omit it and clasp then rejects them). N = the highest `xK` index, M = the number of
     *  constraint lines (each terminated by `;`), excluding comments and the `min:`/`max:` objective. */
    internal fun opbWithProblemLine(text: String): String {
        if (PROBLEM_LINE.containsMatchIn(text)) return text
        val nVar = VARIABLE.findAll(text).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0
        val nCon = text.lineSequence().count { line ->
            val t = line.trim()
            t.endsWith(";") && !t.startsWith("*") && !t.startsWith("min:") && !t.startsWith("max:")
        }
        return "* #variable= $nVar #constraint= $nCon\n$text"
    }

    private val PROBLEM_LINE = Regex("""(?m)^\s*\*\s*#variable=""")
    private val VARIABLE = Regex("""x(\d+)""")
}
