package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryObservation

internal class RootProbePairs {
    private var parentCursor = 0L
    private var nextIsPair = false
    private var childPositive = true
    private var candidateVisits = 0

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
        candidateVisits = 0
    }

    fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(this, Long.SIZE_BYTES + Int.SIZE_BYTES + 2L, protected)
    }

    fun conflict(
        session: PropagationSession,
        params: BacktrackParams,
        parent: Int,
        budget: InprocessingBudget,
        start: Long,
        neighborhood: IntArray? = null,
        attempted: () -> Unit,
    ): Int? {
        if (session.decisionLevel != 1 || session.problem.numIntVars != 0 || budget.stopped()) return null
        val available = minOf(params.rootProbeWork - (session.work - start), budget.remaining)
        if (available <= BOOKKEEPING_WORK + 1L) return null
        val child = candidate(session, parent, budget, available - BOOKKEEPING_WORK - 1L, neighborhood) ?: return null
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
        neighborhood: IntArray?,
    ): Int? {
        val variables = session.problem.numBoolVars
        val start = session.work
        candidateVisits = 0
        if (neighborhood != null) {
            for (literal in neighborhood) {
                if (!visitCandidate(session, budget, start, limit)) return null
                budget.note("PairNeighborhoodCandidates")
                val variable = Lit.variable(literal)
                if (variable >= variables || session.boolValue(variable) != null) continue
                budget.note("PairNeighborhoodEligible")
                return Lit.negate(literal)
            }
        }
        sourceCandidate(session, parent, budget, start, limit)?.let { return it }
        for (offset in 1..minOf(variables - 1, CANDIDATE_LIMIT - candidateVisits)) {
            if (!visitCandidate(session, budget, start, limit)) return null
            val variable = ((Lit.variable(parent).toLong() + offset) % variables).toInt()
            if (session.boolValue(variable) != null) continue
            val literal = Lit.make(variable, childPositive)
            if (!session.explanationState.incremental && session.learnedClauseCount == 0 &&
                session.projection.readyBoolOccurrences(variable)?.isEmpty() == true) continue
            return literal
        }
        return null
    }

    private fun visitCandidate(
        session: PropagationSession,
        budget: InprocessingBudget,
        start: Long,
        limit: Long,
    ): Boolean {
        if (candidateVisits >= CANDIDATE_LIMIT || session.work - start >= limit || !budget.visit()) return false
        candidateVisits++
        budget.note("PairCandidates")
        return true
    }

    private fun sourceCandidate(
        session: PropagationSession,
        parent: Int,
        budget: InprocessingBudget,
        start: Long,
        limit: Long,
    ): Int? {
        val occurrences = session.projection.readyBoolOccurrences(Lit.variable(parent)) ?: return null
        for (index in 0 until minOf(occurrences.size, SOURCE_ROW_LIMIT)) {
            if (session.work - start >= limit || !budget.visit()) return null
            budget.note("PairSourceRows")
            val factor = session.problem.factors[occurrences[index]]
            val child = when (factor) {
                is Clause -> sourceClause(session, factor.literals, budget, start, limit)
                is Cardinality -> sourceRow(session, factor.literals, budget, start, limit,
                    if (factor.min > 0 && factor.max < factor.literals.size) childPositive else factor.min == 0)
                is PseudoBoolean -> sourceWeightedRow(session, factor, budget, start, limit)
                else -> null
            }
            if (child != null) {
                budget.note("PairSourceEligible")
                return child
            }
        }
        return null
    }

    private fun sourceClause(
        session: PropagationSession,
        literals: IntArray,
        budget: InprocessingBudget,
        start: Long,
        limit: Long,
    ): Int? {
        if (literals.size > SOURCE_CLAUSE_LIMIT) return null
        var child: Int? = null
        for (literal in literals) {
            if (!visitCandidate(session, budget, start, limit)) return null
            budget.note("PairSourceCandidates")
            if (Lit.variable(literal) >= session.problem.numBoolVars) return null
            when (session.litTruth(literal)) {
                true -> return null
                null -> if (child == null) child = Lit.negate(literal)
                else -> Unit
            }
        }
        return child
    }

    private fun sourceRow(
        session: PropagationSession,
        literals: IntArray,
        budget: InprocessingBudget,
        start: Long,
        limit: Long,
        positive: Boolean,
    ): Int? {
        for (literal in literals) {
            if (!visitCandidate(session, budget, start, limit)) return null
            budget.note("PairSourceCandidates")
            if (session.litTruth(literal) == null) return if (positive) literal else Lit.negate(literal)
        }
        return null
    }

    private fun sourceWeightedRow(
        session: PropagationSession,
        factor: PseudoBoolean,
        budget: InprocessingBudget,
        start: Long,
        limit: Long,
    ): Int? {
        val constants = factor.integerConstants ?: return null
        val high = when (factor.op) {
            PbOp.LE -> true
            PbOp.GE -> false
            PbOp.EQ -> childPositive
        }
        for (index in factor.literals.indices) {
            if (!visitCandidate(session, budget, start, limit)) return null
            budget.note("PairSourceCandidates")
            val literal = factor.literals[index]
            val weight = constants.coeff(index)
            if (weight != 0L && session.litTruth(literal) == null) {
                return if ((weight > 0L) == high) literal else Lit.negate(literal)
            }
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
        const val SOURCE_ROW_LIMIT = 16
        const val SOURCE_CLAUSE_LIMIT = 8
    }
}
