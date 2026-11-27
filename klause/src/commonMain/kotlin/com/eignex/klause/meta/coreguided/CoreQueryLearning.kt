package com.eignex.klause.meta.coreguided

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.RetainedBooleanClause
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.saturatedAdd

// One OLL worker owns an append-only factor and Boolean coordinate space. A different
// worker or reconstructed source has no authority to install these clauses.
internal class CoreQueryLearning(private val limit: Int) {
    var clauses: List<RetainedBooleanClause> = emptyList()
        private set
    private var adopted = 0L
    private var peakLiteralBytes = 0L

    fun complete(session: PropagationSession, cancellation: ExecutionControl, charge: (Long) -> Unit) {
        val learned = session.retainCompletedBooleanClauses(limit, 8 * limit, cancellation)
        if (learned.isEmpty()) return

        val next = ArrayList<RetainedBooleanClause>(limit)
        for (clause in learned + clauses) {
            charge(1L)
            if (next.size == limit) break
            if (!next.containsClause(clause, charge)) next.add(clause)
        }
        adopted = saturatedAdd(adopted, next.count { !clauses.containsClause(it, charge) }.toLong())
        clauses = next
        peakLiteralBytes = maxOf(peakLiteralBytes, clauses.sumOf { it.literals.size.toLong() * Int.SIZE_BYTES })
    }

    private fun List<RetainedBooleanClause>.containsClause(
        candidate: RetainedBooleanClause,
        charge: (Long) -> Unit,
    ): Boolean = any {
        charge(1L + minOf(it.literals.size, candidate.literals.size))
        it.matches(candidate.literals)
    }

    fun retireCore(literals: IntArray, charge: (Long) -> Unit) {
        if (literals.size > 4 || clauses.isEmpty()) return
        val core = literals.sortedArray()
        charge(core.size.toLong())
        clauses = clauses.filterNot {
            charge(1L + minOf(it.literals.size, core.size))
            it.matches(core)
        }
    }

    fun statistics(): Map<String, Long> = mapOf(
        "coreQueryLearningRetained" to adopted,
        "coreQueryLearningPeakLiteralBytes" to peakLiteralBytes,
    )

    fun close(charge: (Long) -> Unit) {
        charge(clauses.size.toLong())
        clauses = emptyList()
    }
}
