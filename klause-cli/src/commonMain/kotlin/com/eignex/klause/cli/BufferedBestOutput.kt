package com.eignex.klause.cli

import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.SolveStats
import com.eignex.util.numbers.BigFraction
import com.eignex.util.process.flushStandardOutput

// Terminal status and proof metadata stay separate from incumbent output.
internal abstract class BufferedBestOutput : OutputProtocol {
    protected var best: String? = null
    private var emittedWitness: String? = null

    protected abstract val commentPrefix: String

    protected open val streamObjective: Boolean = false

    protected open val streamFiniteWitness: Boolean = false

    protected open val objectivePrefix: String = "o "

    protected open fun completionMetadata(verdict: Verdict): List<String> = emptyList()

    protected open fun formatObjective(objective: Long): String = objective.toString()

    protected open fun formatContinuousObjective(objective: Double): String = objective.toString()

    protected open fun formatExactObjective(objective: BigFraction): String =
        objective.toString()

    protected abstract fun statusLine(verdict: Verdict): String

    protected abstract fun keepStat(key: String): Boolean

    protected open fun onSolutionObjective(objective: Long?) = Unit

    protected open fun lpStatisticPairs(stats: SolveStats): List<Pair<String, String>> = lpStatPairs(stats)

    protected open fun acceptsSourceWitness(): Boolean = true

    protected var context: VerdictContext = VerdictContext()
        private set

    // Carries only what the status line does not already say.
    protected open fun verdictReason(verdict: Verdict): String? =
        if (verdict == Verdict.UNKNOWN) context.softVerdictCause() else null

    final override fun onVerdictContext(context: VerdictContext) {
        this.context = context
    }

    final override fun onSolution(rendered: String, objective: Long?, continuousObjective: Double?) {
        if (!acceptsSourceWitness()) return
        best = rendered
        onSolutionObjective(objective)
        if (!streamObjective) return
        // A continuous contribution has no exact integer form, so it supersedes the discrete value.
        if (continuousObjective != null) {
            println("$objectivePrefix${formatContinuousObjective(continuousObjective)}")
        } else if (objective != null) {
            println("$objectivePrefix${formatObjective(objective)}")
        }
        if (streamFiniteWitness && (objective != null || continuousObjective != null)) emitWitness(rendered)
    }

    final override fun onExactSolution(rendered: String, objective: BigFraction) {
        if (!acceptsSourceWitness()) return
        best = rendered
        if (streamObjective) {
            println("$objectivePrefix${formatExactObjective(objective)}")
            if (streamFiniteWitness) emitWitness(rendered)
        }
    }

    protected open fun formatArithmeticObjective(objective: SourceValue): String = arithmeticObjectiveText(objective)

    protected open fun arithmeticWitnessStatus(): String? = null

    final override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
        if (!acceptsSourceWitness()) return false
        val value = formatArithmeticObjective(objective)
        best = rendered
        if (streamObjective) println("$objectivePrefix$value")
        emitWitness(rendered)
        return true
    }

    private fun emitWitness(rendered: String) {
        arithmeticWitnessStatus()?.let(::println)
        println(rendered)
        flushStandardOutput()
        emittedWitness = rendered
    }

    final override fun onComplete(verdict: Verdict) {
        println(statusLine(verdict))
        completionMetadata(verdict).forEach { println("$commentPrefix $it") }
        // A comment line, so it leaves what either protocol promises untouched.
        verdictReason(verdict)?.let { println("$commentPrefix $it") }
        // A terminal status does not republish an incumbent already streamed with its objective.
        if (verdict in FEASIBLE_VERDICTS) {
            best?.takeUnless { it == emittedWitness }?.let { println(it) }
        }
    }

    final override fun onAllowanceStatistics(pairs: List<Pair<String, String>>) {
        printStatPairs(commentPrefix, pairs)
    }

    final override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        println("$commentPrefix solveTime=${solveTimeMs / 1000.0}")
        println("$commentPrefix solutions=$solutions")
        // Presolve is reported whatever backend followed, including none: on the open-model formats it is
        // the only phase that runs before the route is chosen.
        printStatPairs(commentPrefix, presolveStatPairs(stats))
        // The LP block gates itself and must stay outside the backend check: a run that records no backend
        // can still have solved relaxations.
        if (stats.run.backend.isNotEmpty()) {
            printStatPairs(commentPrefix, searchStatPairs(stats).filter { (k, _) -> keepStat(k) })
            printStatPairs(commentPrefix, caStatPairs(stats))
            printStatPairs(commentPrefix, openTheoryStatPairs(stats, solveTimeMs).filter { (k, _) -> keepStat(k) })
        }
        printStatPairs(commentPrefix, lpStatisticPairs(stats))
        printStatPairs(commentPrefix, lsStatPairs(stats, solveTimeMs))
        printStatPairs(commentPrefix, portfolioStatPairs(stats))
    }

    private companion object {
        val FEASIBLE_VERDICTS =
            setOf(Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND, Verdict.UNBOUNDED)
    }
}
