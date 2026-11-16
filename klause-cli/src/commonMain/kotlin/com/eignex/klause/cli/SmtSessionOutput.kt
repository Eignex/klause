package com.eignex.klause.cli

import com.eignex.klause.formats.smtlib.SmtSessionModel
import com.eignex.klause.formats.smtlib.SmtSessionResult
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats

internal class SmtSessionOutput(
    private val interpretation: () -> SmtSessionModel?,
    private val comment: (String) -> Unit,
    private val objective: () -> BigFraction?,
) : OutputProtocol {
    private var witnessed = false
    private var status = "unknown"
    private var reason: String? = null
    private var optimize = false
    private var quadraticResult: QuadraticOptimizationResult? = null
    private var linearResult: LinearOptimizationResult? = null

    override fun begin(optimize: Boolean, maximize: Boolean) { this.optimize = optimize }
    override fun onSolution(rendered: String, objective: Long?, continuousObjective: Double?) {
        witnessed = true
        this.objective()?.let { comment("objective=$it") } ?: run {
            if (continuousObjective != null) comment("objective=$continuousObjective")
            else if (objective != null) comment("objective=$objective")
        }
    }
    override fun onExactSolution(rendered: String, objective: BigFraction) {
        witnessed = true
        comment("objective=${this.objective() ?: objective}")
    }
    override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
        witnessed = true
        if (optimize) comment("objective=${arithmeticObjectiveText(objective)}")
        return true
    }
    override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
        quadraticResult = result
        if (rendered != null) {
            witnessed = true
            (result as? QuadraticOptimizationResult.WithIncumbent)?.let {
                comment("objective=${smtAlgebraicValue(it.incumbent.value)}")
            }
        }
        val bound = when (result) {
            is QuadraticOptimizationResult.Optimal -> result.bound
            is QuadraticOptimizationResult.UnattainedLimit -> result.bound
            is QuadraticOptimizationResult.BestFound -> result.bound
            is QuadraticOptimizationResult.Unknown -> result.bound
            else -> null
        }
        bound?.let {
            val direction = if (result.sense == ObjectiveSense.MINIMIZE) "lower" else "upper"
            comment("objectiveBoundDirection=$direction")
            comment("objectiveBound=${smtAlgebraicValue(it.value)}")
            comment("objectiveBoundStrict=${it.strict}")
        }
    }
    override fun onLinearResult(result: LinearOptimizationResult) {
        linearResult = result
        val bound = when (val claim = result.terminal?.claim) {
            is SourceClaim.Optimal -> SourceClaim.Bound(claim.value)
            is SourceClaim.Limit -> SourceClaim.Bound(claim.value, strict = true)
            else -> result.bound?.claim as? SourceClaim.Bound
        } ?: return
        val direction = if (result.scope.objective?.sense == ObjectiveSense.MINIMIZE) "lower" else "upper"
        comment("objectiveBoundDirection=$direction")
        comment("objectiveBound=${arithmeticObjectiveText(bound.value)}")
        comment("objectiveBoundStrict=${bound.strict}")
    }
    override fun onVerdictContext(context: VerdictContext) { reason = context.softVerdictCause() }
    override fun onComplete(verdict: Verdict) {
        status = when (verdict) {
            Verdict.UNSATISFIABLE -> "unsat"
            Verdict.UNKNOWN -> "unknown"
            else -> "sat"
        }
        if (optimize) {
            val limit = linearResult?.terminal?.claim is SourceClaim.Limit ||
                quadraticResult is QuadraticOptimizationResult.UnattainedLimit
            val sense = linearResult?.scope?.objective?.sense ?: quadraticResult?.sense
            val completion = if (limit) {
                if (sense == ObjectiveSense.MINIMIZE) "unattained-infimum" else "unattained-supremum"
            } else verdict.name.lowercase().replace('_', '-')
            comment("optimizationStatus=$completion")
        }
        if (status == "unknown") reason?.let(comment)
    }
    override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        comment("solveTime=${solveTimeMs / 1000.0}")
        comment("solutions=$solutions")
        val pairs = presolveStatPairs(stats) + searchStatPairs(stats) + caStatPairs(stats) +
            openTheoryStatPairs(stats, solveTimeMs) + lpStatPairs(stats) + lsStatPairs(stats, solveTimeMs) +
            portfolioStatPairs(stats)
        onAllowanceStatistics(pairs)
    }
    override fun onAllowanceStatistics(pairs: List<Pair<String, String>>) {
        pairs.forEach { (key, value) -> comment("$key=$value") }
    }
    override fun onImprovement(arm: String, objective: Long, continuousObjective: Double?, elapsedMs: Long) {
        comment("arm=$arm objective=${continuousObjective ?: objective} time=$elapsedMs")
    }
    override fun onImprovement(arm: String, objective: BigFraction, elapsedMs: Long) {
        comment("arm=$arm objective=$objective time=$elapsedMs")
    }
    fun result(): SmtSessionResult = SmtSessionResult(status,
        interpretation().takeIf { witnessed && status != "unsat" }, reason?.takeIf { status == "unknown" })
}
