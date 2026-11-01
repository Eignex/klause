package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation

internal class LinearRepairGuidance : AutoCloseable {
    class Hint internal constructor(
        private val source: SourceProofContext,
        private val point: Map<SourceCoordinate, Double>,
    ) {
        fun matches(context: SourceProofContext): Boolean = source === context
        fun integer(id: Int): Double? = point[SourceCoordinate(SourceCoordinate.Kind.Integer, id)]
    }

    private var hint: Hint? = null
    private var closed = false

    fun remember(source: SourceProofContext, point: Map<SourceCoordinate, Double>,
        budget: SourceLpBudget, token: Cancellation): Boolean {
        if (closed || hint != null || point.isEmpty() || point.size > MAX_COLUMNS || token()) return false
        val funding = budget.funding ?: return false
        val work = 1L + 2L * point.size
        val bytes = 256L + 128L * point.size
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return false
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        val copy = LinkedHashMap<SourceCoordinate, Double>()
        for ((coordinate, value) in point) {
            if (token() || coordinate !in source.coordinates || !value.isFinite()) return false
            copy[coordinate] = value
        }
        if (token()) return false
        hint = Hint(source, copy)
        return true
    }

    fun find(source: SourceProofContext): Hint? = hint?.takeIf { !closed && it.matches(source) }

    override fun close() {
        closed = true
        hint = null
    }

    companion object {
        const val MAX_COLUMNS = 4096
    }
}
