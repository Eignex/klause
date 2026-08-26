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

    /** Render an incumbent objective for an output protocol. */
    protected open fun formatObjective(objective: Long): String = objective.toString()

    /** The single status line this format prints for [verdict] at completion. */
    protected abstract fun statusLine(verdict: Verdict): String

    /** Whether the search-counter [key] is included in this format's statistics block. */
    protected abstract fun keepStat(key: String): Boolean

    /** The incumbent's objective, for a format whose status line depends on it. */
    protected open fun onSolutionObjective(objective: Long?) = Unit

    /** Why the run stopped; see [VerdictContext]. Set before the verdict is rendered. */
    protected var context: VerdictContext = VerdictContext()
        private set

    /** The cause to print beside [verdict] as a comment, or null when the verdict speaks for itself.
     *  The status line has already named the verdict, so this carries only what it does not say. A
     *  format overrides this to add a cause of its own. */
    protected open fun verdictReason(verdict: Verdict): String? =
        if (verdict == Verdict.UNKNOWN) context.softVerdictCause() else null

    final override fun onVerdictContext(context: VerdictContext) {
        this.context = context
    }

    final override fun onSolution(rendered: String, objective: Long?) {
        best = rendered
        onSolutionObjective(objective)
        if (streamObjective && objective != null) println("o ${formatObjective(objective)}")
    }

    final override fun onComplete(verdict: Verdict) {
        println(statusLine(verdict))
        // A comment, so it rides alongside the status line without touching what either protocol
        // promises: `;` is an SMT-LIB comment and `c` is one in the competition protocol.
        verdictReason(verdict)?.let { println("$commentPrefix $it") }
        // The buffered model follows a feasible verdict only; UNSAT / UNKNOWN print the status alone.
        if (verdict == Verdict.SATISFIABLE || verdict == Verdict.OPTIMAL || verdict == Verdict.BEST_FOUND) {
            best?.let { println(it) }
        }
    }

    final override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        println("$commentPrefix solveTime=${solveTimeMs / 1000.0}")
        println("$commentPrefix solutions=$solutions")
        // The search block is unconditional counters, so it is worth printing only for an engine that
        // ran; the LP block reports nothing unless the LP did work, so it gates itself and must not sit
        // behind the backend check — a run that records no backend can still have solved relaxations.
        if (stats.run.backend.isNotEmpty()) {
            printStatPairs(commentPrefix, searchStatPairs(stats).filter { (k, _) -> keepStat(k) })
        }
        printStatPairs(commentPrefix, lpStatPairs(stats))
    }
}
