package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtGroundModel
import com.eignex.klause.formats.smtlib.SmtSessionResult
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.SolveStats

internal class SmtSessionOutput(private val source: String) : OutputProtocol {
    private var model: String? = null
    private var status = "unknown"
    private var reason: String? = null
    private val statistics = SmtLibOutput()

    override fun onSolution(rendered: String, objective: Long?, continuousObjective: Double?) { model = rendered }
    override fun onExactSolution(rendered: String, objective: BigFraction) { model = rendered }
    override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
        model = rendered
        return true
    }
    override fun onVerdictContext(context: VerdictContext) { reason = context.softVerdictCause() }
    override fun onComplete(verdict: Verdict) {
        status = when (verdict) {
            Verdict.UNSATISFIABLE -> "unsat"
            Verdict.UNKNOWN -> "unknown"
            else -> "sat"
        }
    }
    override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        statistics.onStatistics(stats, solveTimeMs, solutions)
    }
    override fun onAllowanceStatistics(pairs: List<Pair<String, String>>) {
        statistics.onAllowanceStatistics(pairs)
    }
    fun result(): SmtSessionResult = SmtSessionResult(
        status,
        model?.takeIf { status == "sat" }?.let { SmtGroundModel(source, it) },
        reason?.takeIf { status == "unknown" },
    )
}
