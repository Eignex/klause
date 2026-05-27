package com.eignex.klause.bench.parity

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

/**
 * Anytime LS benchmark harness. Runs the same MiniZinc instance through klause-LS and a
 * baseline LS solver (Yuck by default) with the same wall-clock budget; captures the full
 * stream of intermediate solutions so we can score by:
 *
 *  - **time-to-first** — wall-clock ms until the first solution.
 *  - **time-to-best** — wall-clock ms until the last improvement.
 *  - **best objective** — final reported `_objective` (or `null` for satisfaction problems
 *    / no feasible solution within budget).
 *  - **solutions seen** — count of `----------`-terminated solution blocks.
 *
 * The two solvers are invoked through `minizinc --solver <id>` so each consumes the model
 * via its own `mznlib` decomposition — that's the fair comparison surface.
 */
object LsBench {

    @Serializable
    data class Result(
        val name: String,
        val solver: String,
        val timeoutSec: Int,
        val verdict: Verdict,
        val timeToFirstMs: Long?,
        val timeToBestMs: Long?,
        val solutionsSeen: Int,
        val bestObjective: Double?,
        val finalObjective: Double?,
        /** Whether the solver itself emitted `==========` (search completed within budget). */
        val completed: Boolean,
        val detail: String,
    )

    @Serializable
    enum class Verdict {
        FEASIBLE,          // ≥1 solution found within budget
        NO_SOLUTION,       // no solution before budget
        UNSAT,             // proven UNSAT
        COMPILE_ERROR,
        TIMEOUT_NO_OUTPUT, // budget expired with no output at all
        ERROR,
    }

    data class SolverSpec(
        val id: String,
        val label: String,
        /** Extra `-G` lib path; needed for klause-ls (its mznlib lives outside the standard
         *  search dirs). `null` for system-registered solvers. */
        val mznLibDir: File? = null,
    )

    data class Config(
        val name: String,
        val mznPath: File,
        val dznPath: File? = null,
        val timeoutSec: Int = 30,
        val freeSearch: Boolean = false,
    )

    fun run(cfg: Config, spec: SolverSpec): Result {
        val cmd = buildList {
            add("minizinc")
            add("--solver"); add(spec.id)
            if (spec.mznLibDir != null) { add("-G"); add(spec.mznLibDir.absolutePath) }
            add("--time-limit"); add((cfg.timeoutSec * 1000).toString())
            add("-a")
            add("--output-objective")
            if (cfg.freeSearch) add("-f")
            add(cfg.mznPath.absolutePath)
            if (cfg.dznPath != null) add(cfg.dznPath.absolutePath)
        }
        val started = System.currentTimeMillis()
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        val proc = pb.start()
        proc.outputStream.close()
        var firstSolnAt: Long? = null
        var lastSolnAt: Long? = null
        var solutionsSeen = 0
        var bestObj: Double? = null
        var finalObj: Double? = null
        var unsat = false
        var completed = false
        var inSolution = false
        var curObj: Double? = null
        val errBuf = StringBuilder()
        // Drain stderr in a thread.
        val errThread = Thread {
            BufferedReader(InputStreamReader(proc.errorStream)).useLines { lines ->
                for (line in lines) errBuf.appendLine(line)
            }
        }.also { it.isDaemon = true; it.start() }
        // Stream stdout line by line so timestamps reflect when each solution actually arrived.
        BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
            for (line in lines) {
                if (!inSolution && line.isNotBlank()) inSolution = true
                val objMatch = Regex("""_objective\s*=\s*(-?\d+(?:\.\d+)?)""").find(line)
                if (objMatch != null) curObj = objMatch.groupValues[1].toDoubleOrNull()
                if (line.trimEnd() == "----------") {
                    val now = System.currentTimeMillis() - started
                    if (firstSolnAt == null) firstSolnAt = now
                    val o = curObj
                    if (o != null) {
                        val current = bestObj
                        if (current == null || o != current) {
                            bestObj = o
                            lastSolnAt = now
                        }
                        finalObj = o
                    } else {
                        lastSolnAt = now
                    }
                    solutionsSeen++
                    curObj = null
                    inSolution = false
                }
                if (line.trimEnd() == "==========") completed = true
                if ("=====UNSATISFIABLE=====" in line) unsat = true
            }
        }
        val finished = proc.waitFor(cfg.timeoutSec.toLong() + 5L, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        errThread.join(500)
        val exit = if (finished) proc.exitValue() else -1
        val verdict = when {
            unsat -> Verdict.UNSAT
            solutionsSeen > 0 -> Verdict.FEASIBLE
            exit != 0 && errBuf.contains("compile") -> Verdict.COMPILE_ERROR
            solutionsSeen == 0 -> Verdict.NO_SOLUTION
            else -> Verdict.ERROR
        }
        val detail = when {
            verdict == Verdict.COMPILE_ERROR -> errBuf.toString().take(500)
            exit != 0 && verdict != Verdict.UNSAT -> "exit=$exit stderr=${errBuf.toString().take(400)}"
            else -> ""
        }
        return Result(
            name = cfg.name,
            solver = spec.label,
            timeoutSec = cfg.timeoutSec,
            verdict = verdict,
            timeToFirstMs = firstSolnAt,
            timeToBestMs = lastSolnAt,
            solutionsSeen = solutionsSeen,
            bestObjective = bestObj,
            finalObjective = finalObj,
            completed = completed,
            detail = detail,
        )
    }

    /** Whether [this] objective improves on [other] — direction unknown to the harness, so
     *  any strict difference counts as "this came later in the stream and is therefore the
     *  new best". MiniZinc streams improving solutions in monotone order per solver. */
    private fun Double.improvesOn(other: Double): Boolean = this != other
}
