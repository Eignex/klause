package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedConstraint
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchPendingLearnedPropagation
import com.eignex.klause.solver.search.SearchSession

internal class LpLearnedConflict(
    private val learned: LearnedConstraint,
    private val cp: CpSearchComponent,
) : SearchLearnedConflict, SearchPendingLearnedPropagation {
    override var propagationPending = false
        private set
    override val decisionLevel: Int get() = cp.sharedLevelForNative(learned.backjumpLevel)
    override val lbd: Int get() = learned.lbd
    override val guardLiterals: IntArray get() = learned.guardLiterals
    override val decisionLevels: IntArray get() = learned.decisionLevels

    override fun apply(session: SearchSession): SearchLearnedConflictResult {
        propagationPending = false
        if (!learned.asserting || learned.isContradiction) {
            return SearchLearnedConflictResult.Chronological
        }
        val result = when (learned) {
            is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned -> {
                cp.session.addLearnedClause(Clause(learned.literals), learned.lbd)
            }

            is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedPb -> {
                cp.session.addLearnedPb(
                    learned.weights,
                    learned.literals,
                    learned.degree,
                    learned.lbd,
                )
            }

            is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedIntegerRow -> {
                cp.session.addLearnedIntegerRow(learned)
            }
        }
        return when (result) {
            is PropagationResult.Implied -> {
                // The clause stays in the CP database; see the note in CpSearchComponent.
                when (cp.import(result, session)) {
                    ComponentResult.Consistent -> when (continuePropagation(session)) {
                        ComponentResult.Consistent -> SearchLearnedConflictResult.Resume
                        is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological
                        ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                    }

                    is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological

                    ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                }
            }

            is PropagationResult.Unsat -> {
                val next = cp.mappedLearnedConstraint(result.learnedClause as? LearnedConstraint)
                when {
                    next == null -> SearchLearnedConflictResult.Chronological

                    next.backjumpLevel == 0 && next.isContradiction -> {
                        SearchLearnedConflictResult.Exhausted
                    }

                    else -> SearchLearnedConflictResult.Backjump(LpLearnedConflict(next, cp))
                }
            }
        }
    }

    override fun continuePropagation(session: SearchSession): ComponentResult {
        propagationPending = false
        if (cp.session.fixpointCancelled) return ComponentResult.Indeterminate
        val result = session.propagate()
        propagationPending = result == ComponentResult.Indeterminate && !cp.session.fixpointCancelled
        return result
    }
}
