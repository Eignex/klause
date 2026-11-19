package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.ExecutionControl

internal class LinearAuthorityBoundBaseline(
    private val budget: SourceLpBudget,
    private val onCost: (Long, Long) -> Unit,
) {
    private class Completed(val source: SourceProofContext, val result: LinearAuthorityResult) {
        var reported = false

        fun matches(other: SourceProofContext): Boolean = source === other ||
            (source.scope.revision == other.scope.revision && source.scope.objective == other.scope.objective &&
                source.scope.condition.bools == other.scope.condition.bools && source.hasSameLinearData(other) &&
                source.affineObjectiveAvailable == other.affineObjectiveAvailable &&
                source.semantics == other.semantics &&
                source.semanticsChecker === other.semanticsChecker)
    }
    private var completed: Completed? = null
    private val counts = linkedMapOf<String, Long>()
    val observations: Map<String, Long> get() = counts.toMap()

    fun clear() {
        completed = null
    }

    private fun observe(name: String) {
        val key = "numericalCutBaseline$name"
        counts[key] = (counts[key] ?: 0L) + 1L
    }

    fun advance(
        source: SourceProofContext, cancellation: ExecutionControl,
        baseline: () -> LinearAuthorityResult, ordinary: () -> LinearAuthorityResult,
    ): LinearAuthorityResult {
        val previous = completed
        if (previous != null) {
            onCost(1L + source.coordinateCount + source.premiseCount, 0L)
            if (cancellation() || budget.funding?.paused == true) {
                return if (previous.source === source) previous.result.copy(primalStages = emptyMap(), paused = true)
                else LinearAuthorityResult(paused = true)
            }
            if (!previous.matches(source)) completed = null
        }
        var checkpoint = completed
        if (checkpoint == null) {
            observe("Queries")
            val result = baseline()
            if (result.evidence != null) return result
            check(result.incumbent == null) { "a bound-only baseline must not produce an incumbent" }
            val retained = LinearAuthorityResult(bound = result.bound, paused = result.paused,
                primalStages = result.primalStages)
            if (result.paused || cancellation() || budget.funding?.exhausted == true) return retained
            checkpoint = Completed(source, retained)
            completed = checkpoint
            observe("Completed")
            observe(if (result.bound == null) "BoundUnavailable" else "BoundAvailable")
            onCost(1L, 1024L + 64L * (source.premiseCount + result.primalStages.size))
        } else observe("Reused")
        val result = ordinary()
        val stages = if (checkpoint.reported) result.primalStages else
            mergeLinearPrimalStages(checkpoint.result.primalStages, result.primalStages)
        checkpoint.reported = true
        if (checkpoint.source.scope != source.scope) return result.copy(primalStages = stages)
        val strongest = strongerLinearBound(checkpoint.result.bound, result.bound)
        return result.copy(bound = strongest, primalStages = stages,
            cutBoundFamilies = if (strongest != null && strongest === checkpoint.result.bound) emptySet()
            else result.cutBoundFamilies)
    }
}

internal fun mergeLinearPrimalStages(before: Map<String, Long>, after: Map<String, Long>): Map<String, Long> =
    (before.keys + after.keys).associateWith { key ->
        if ("Max" in key) maxOf(before[key] ?: 0L, after[key] ?: 0L)
        else (before[key] ?: 0L) + (after[key] ?: 0L)
    }
