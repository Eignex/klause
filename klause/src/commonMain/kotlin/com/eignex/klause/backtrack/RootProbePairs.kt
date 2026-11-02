package com.eignex.klause.backtrack

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryObservation

internal class RootProbePairs {
    private var parentCursor = 0L
    private var nextIsPair = false
    private var childPositive = true

    fun begin(session: PropagationSession, params: BacktrackParams, enabled: Boolean): Boolean {
        if (!enabled || params.rootProbeBatch < 2 || session.problem.numIntVars != 0 ||
            session.problem.numBoolVars < 2) return false
        val paired = nextIsPair
        nextIsPair = !nextIsPair
        if (paired) childPositive = !childPositive
        return paired
    }

    fun nextLiteral(candidates: Long): Long {
        val ordinal = parentCursor
        parentCursor = if (ordinal == Long.MAX_VALUE) 0L else ordinal + 1L
        childPositive = ordinal / candidates % 2L == 0L
        return ordinal % candidates
    }

    fun preferSingle() {
        nextIsPair = false
    }

    fun reset() {
        parentCursor = 0L
        nextIsPair = false
        childPositive = true
    }

    fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, Long.SIZE_BYTES + 2L, protected)
    }

    fun conflict(
        session: PropagationSession,
        params: BacktrackParams,
        parent: Int,
        budget: InprocessingBudget,
        start: Long,
        attempted: () -> Unit,
    ): Int? {
        if (session.decisionLevel != 1 || session.problem.numIntVars != 0 || budget.stopped()) return null
        val available = minOf(params.rootProbeWork - (session.work - start), budget.remaining)
        if (available <= BOOKKEEPING_WORK + 1L) return null
        val child = candidate(session, parent, budget, available - BOOKKEEPING_WORK - 1L) ?: return null
        if (minOf(params.rootProbeWork - (session.work - start), budget.remaining) <= BOOKKEEPING_WORK ||
            !budget.visit(BOOKKEEPING_WORK)) return null
        val left = minOf(params.rootProbeWork - (session.work - start), budget.remaining)
        if (left <= 0L) return null
        attempted()
        budget.note("PairEligible")
        budget.probe()
        val priorCancelled = session.probeCancelled
        return when (probe(session, params.cancellation, child, left)) {
            null -> {
                budget.note("PairIncomplete")
                if (!params.cancellation()) session.probeCancelled = priorCancelled
                null
            }
            is PropagationResult.Unsat -> {
                budget.note("PairConflicts")
                child
            }
            else -> {
                budget.note("PairConsistent")
                null
            }
        }
    }

    private fun candidate(
        session: PropagationSession,
        parent: Int,
        budget: InprocessingBudget,
        limit: Long,
    ): Int? {
        val variables = session.problem.numBoolVars
        val start = session.work
        for (offset in 1..minOf(variables - 1, CANDIDATE_LIMIT)) {
            if (session.work - start >= limit || !budget.visit()) return null
            budget.note("PairCandidates")
            val variable = ((Lit.variable(parent).toLong() + offset) % variables).toInt()
            if (session.boolValue(variable) != null) continue
            val literal = Lit.make(variable, childPositive)
            if (!session.explanationState.incremental && session.learnedClauseCount == 0 &&
                session.projection.readyBoolOccurrences(variable)?.isEmpty() == true) continue
            return literal
        }
        return null
    }

    private fun probe(
        session: PropagationSession,
        cancellation: Cancellation,
        literal: Int,
        limit: Long,
    ): PropagationResult? {
        val level = session.decisionLevel
        val start = session.work
        val token = Cancellation { cancellation() || session.work - start >= limit }
        return try {
            val result = session.withCancellation(token) {
                session.probeBool(Lit.variable(literal), Lit.isPositive(literal))
            }
            if (cancellation()) {
                session.probeCancelled = true
                null
            } else result
        } finally {
            session.popToLevel(level)
        }
    }

    private companion object {
        const val BOOKKEEPING_WORK = 8
        const val CANDIDATE_LIMIT = 64
    }
}
