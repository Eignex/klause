package com.eignex.klause.cli

import com.eignex.klause.solver.result.SolveStats

/**
 * Shared plumbing for the competition [OutputProtocol]s that buffer the best solution and emit it
 * with a final status line (SMT-LIB, XCSP3) — as opposed to MiniZinc, which streams each solution.
 * The buffered model is printed only on a feasible verdict; statistics render as `<prefix> key=value`
 * comment lines. Subclasses supply the comment [commentPrefix], the per-verdict [statusLine], and the
 * [keepStat] filter selecting which search counters that format reports; [streamObjective] opts into
 * printing an `o <cost>` line per incumbent.
 */
internal abstract class BufferedBestOutput : OutputProtocol {
    protected var best: String? = null

    /** Comment-line prefix for the statistics block (e.g. `;`, `c`). */
    protected abstract val commentPrefix: String

    /** When true, print `o <objective>` as each improving incumbent streams in. */
    protected open val streamObjective: Boolean = false

    /** The single status line this format prints for [verdict] at completion. */
    protected abstract fun statusLine(verdict: Verdict): String

    /** Whether the search-counter [key] is included in this format's statistics block. */
    protected abstract fun keepStat(key: String): Boolean

    final override fun onSolution(rendered: String, objective: Long?) {
        best = rendered
        if (streamObjective && objective != null) println("o $objective")
    }

    final override fun onComplete(verdict: Verdict) {
        println(statusLine(verdict))
        // The buffered model follows a feasible verdict only; UNSAT / UNKNOWN print the status alone.
        if (verdict == Verdict.SATISFIABLE || verdict == Verdict.OPTIMAL || verdict == Verdict.BEST_FOUND) {
            best?.let { println(it) }
        }
    }

    final override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        println("$commentPrefix solveTime=${solveTimeMs / 1000.0}")
        println("$commentPrefix solutions=$solutions")
        if (stats.run.backend.isNotEmpty()) {
            printStatPairs(commentPrefix, searchStatPairs(stats).filter { (k, _) -> keepStat(k) })
            printStatPairs(commentPrefix, lpStatPairs(stats))
        }
    }
}
