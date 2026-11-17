package com.eignex.klause.solver.search

internal class ExplainedLearnedConflict(
    private val explanation: SearchExplanation,
    override val decisionLevel: Int,
    override val decisionLevels: IntArray,
) : SearchLearnedConflict {
    override val lbd: Int get() = decisionLevels.toSet().size
    override val guardLiterals: IntArray get() = explanation.literals

    override fun apply(session: SearchSession): SearchLearnedConflictResult {
        session.learn(explanation)
        return when (session.propagate()) {
            ComponentResult.Consistent -> SearchLearnedConflictResult.Resume
            is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological
            ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
        }
    }
}
