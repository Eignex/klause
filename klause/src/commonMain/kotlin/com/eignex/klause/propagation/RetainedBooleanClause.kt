package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause

// The owner must preserve the producer's model and Boolean coordinates across the query boundary.
internal class RetainedBooleanClause(val literals: IntArray, val lbd: Int) {
    fun matches(other: IntArray, size: Int = other.size): Boolean =
        literals.size == size && literals.indices.all { literals[it] == other[it] }
}

internal fun PropagationState.installRetainedRootClauses(clauses: List<RetainedBooleanClause>) {
    for (clause in clauses) {
        // A root fixpoint visits every general clause. The native lane installs watches immediately.
        work += clause.literals.size
        addLearnedClause(Clause(clause.literals), clause.lbd, origin = RETAINED_QUERY_ORIGIN)
    }
}

internal const val RETAINED_QUERY_ORIGIN = -3
