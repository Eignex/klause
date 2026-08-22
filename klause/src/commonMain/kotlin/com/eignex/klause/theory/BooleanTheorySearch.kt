package com.eignex.klause.theory

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Lit

/** Exhaustive Boolean skeleton search for open theories. */
internal class BooleanTheorySearch(
    private val numBools: Int,
    clauses: List<Clause>,
    private val params: TheoryParams,
) {
    private val clauses = clauses.map(Clause::literals)
    private val values = BooleanArray(numBools)
    private val assigned = BooleanArray(numBools)
    private var leaves = 0L

    fun <T> first(check: (BooleanArray) -> T?): BooleanTheoryResult<T> {
        val result = visit(0, check)
        return when {
            result != null -> BooleanTheoryResult.Found(result)
            params.cancellation() -> BooleanTheoryResult.Cancelled
            leaves >= params.maxLeaves -> BooleanTheoryResult.Unknown
            else -> BooleanTheoryResult.Exhausted
        }
    }

    private fun <T> visit(next: Int, check: (BooleanArray) -> T?): T? {
        if (params.cancellation() || leaves >= params.maxLeaves || !clausesPossible()) return null
        if (next == numBools) {
            leaves++
            return check(values)
        }
        assigned[next] = true
        values[next] = false
        visit(next + 1, check)?.let { return it }
        values[next] = true
        visit(next + 1, check)?.let { return it }
        assigned[next] = false
        return null
    }

    private fun clausesPossible(): Boolean = clauses.all { clause ->
        clause.any { lit -> !assigned[Lit.variable(lit)] || Lit.evaluate(lit, values[Lit.variable(lit)]) }
    }
}

internal sealed interface BooleanTheoryResult<out T> {
    data class Found<T>(val value: T) : BooleanTheoryResult<T>
    data object Exhausted : BooleanTheoryResult<Nothing>
    data object Cancelled : BooleanTheoryResult<Nothing>
    data object Unknown : BooleanTheoryResult<Nothing>
}
