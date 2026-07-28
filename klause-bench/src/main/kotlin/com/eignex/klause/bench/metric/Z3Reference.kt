package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import java.util.concurrent.TimeUnit

/**
 * The SMT-LIB reference. cp-sat has no SMT-LIB frontend, so quantifier-free linear-arithmetic
 * benchmarks are decided by z3 (a complete SMT solver) run as a native binary directly on the `.smt2`
 * file — no container, since z3 ships a self-contained executable. SMT-LIB benchmarks are decision
 * instances (no `(minimize`/`(maximize`), so a row records satisfiability (`sat` → feasible, `unsat`
 * → infeasible) and proves it whenever z3 returns a definitive verdict within budget; `unknown` or a
 * timeout is undecided. Rows are keyed by solver `z3`, coexisting with the cp-sat and clasp oracles.
 *
 * Mirrors [ClaspReference] (its own [ProcessBuilder], a watchdog that force-kills a runaway, a
 * wall-clock elapsed, a line-oriented parse) minus the Docker wrapping a native binary makes moot.
 */
internal object Z3Reference {
    /** The z3 executable, resolved from `PATH` by default; override with `-Dklause.bench.z3=/path/to/z3`. */
    private val BINARY = System.getProperty("klause.bench.z3", "z3")

    /** Hard per-run memory ceiling in MB (`z3 -memory:`), so one instance's blow-up can't exhaust the
     *  host — the whole-corpus sweep runs many z3 in parallel. Overrun makes z3 abort (→ undecided,
     *  which is fine for a reference). Override with `-Dklause.bench.z3.memoryMb=`. */
    private val MEMORY_MB = System.getProperty("klause.bench.z3.memoryMb", "2048")
    private const val EXIT_WAIT_MS = 5_000L

    /** Whether a usable z3 is reachable (`z3 --version` exits 0). */
    fun available(): Boolean = runCatching {
        val p = ProcessBuilder(BINARY, "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    }.getOrElse { false }

    /** Decide [ref] (an SMT-LIB `.smt2`) with z3 under [budget]. Bounded so a whole-corpus parallel sweep
     *  is safe: `-T:<sec>` caps wall-clock (z3 then answers `unknown`), `-memory:<MB>` caps RAM, and
     *  `sat.threads=1 parallel.enable=false` keep it single-threaded (no fan-out per process). A watchdog
     *  force-kills a run that ignores its own limits, so one solve can never hang or starve the box. */
    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result {
        val file = CorpusFetcher.resolve(ref.source)
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val cmd = listOf(
            BINARY,
            "-T:$timeoutSec",
            "-memory:$MEMORY_MB",
            "-smt2",
            "sat.threads=1",
            "parallel.enable=false",
            file.absolutePath,
        )
        val startNanos = System.nanoTime()
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val watchdog = Thread {
            runCatching {
                if (!proc.waitFor(budget.timeoutMillis * 2 + 20_000, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val stdout = proc.inputStream.bufferedReader().readText()
        proc.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        watchdog.interrupt()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        return parse(stdout, elapsedMs, cmd)
    }

    /** z3 prints the check-sat answer as a bare `sat` / `unsat` / `unknown` line. Map the last such
     *  line to the [SolverInvocation.Result] the reference sweep scores: SMT-LIB benchmarks are decision problems,
     *  so `sat`/`unsat` is a proof (no objective) and `unknown`/timeout is undecided. */
    internal fun parse(stdout: String, elapsedMs: Long, cmd: List<String>): SolverInvocation.Result {
        val verdict = stdout.lineSequence().map { it.trim() }
            .lastOrNull { it == "sat" || it == "unsat" || it == "unknown" }
        val feasible = when (verdict) {
            "sat" -> true
            "unsat" -> false
            else -> null
        }
        val proven = verdict == "sat" || verdict == "unsat"
        val timeMs = elapsedMs.takeIf { feasible == true }
        return SolverInvocation.Result(
            feasible = feasible,
            objective = null, // SMT-LIB benchmarks are decision instances — no optimisation objective
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = proven,
            // Seconds, so [BenchCli.solveReference] derives the proof/first-feasible time uniformly.
            stats = mapOf("solveTime" to (elapsedMs / 1000.0).toString(), "maximize" to "false"),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }
}
