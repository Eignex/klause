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

        val next = LinkedHashMap<Long, RetainedBooleanClause>()
        for (clause in learned + clauses) {
            charge(1L)
            if (next.size == limit) break
            if (!next.containsKey(clause.key)) next[clause.key] = clause
        }
        adopted = saturatedAdd(adopted, next.keys.count { key -> clauses.none { it.key == key } }.toLong())
        clauses = next.values.toList()
        peakLiteralBytes = maxOf(peakLiteralBytes, clauses.sumOf { it.literals.size.toLong() * Int.SIZE_BYTES })
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
