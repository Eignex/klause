package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.ExecutionControl
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal class LinearRepairGuidance : AutoCloseable {
    class Hint internal constructor(
        private val source: SourceProofContext,
        internal val point: Map<SourceCoordinate, Double>,
    ) {
        fun matches(context: SourceProofContext): Boolean = source === context
        fun integer(id: Int): Double? = point[SourceCoordinate(SourceCoordinate.Kind.Integer, id)]
    }

    private sealed interface State {
        data object Empty : State
        class Available(val hint: Hint) : State
        data object Closed : State
    }

    private val state = AtomicReference<State>(State.Empty)

    fun remember(source: SourceProofContext, point: Map<SourceCoordinate, Double>,
        budget: SourceLpBudget, token: ExecutionControl): Boolean {
        if (state.load() != State.Empty || point.isEmpty() || point.size > MAX_COLUMNS ||
            source.coordinateCount > MAX_COLUMNS || token()) return false
        val funding = budget.funding ?: return false
        val work = 1L + 2L * point.size + source.coordinateCount
        val bytes = 256L + 128L * point.size + 64L * source.coordinateCount
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return false
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        val coordinates = source.coordinates
        val copy = LinkedHashMap<SourceCoordinate, Double>()
        for ((coordinate, value) in point) {
            if (token() || coordinate !in coordinates || !value.isFinite()) return false
            copy[coordinate] = value
        }
        if (token()) return false
        return state.compareAndSet(State.Empty, State.Available(Hint(source, copy)))
    }

    fun find(source: SourceProofContext): Hint? =
        (state.load() as? State.Available)?.hint?.takeIf { it.matches(source) }

    override fun close() {
        state.store(State.Closed)
    }

    companion object {
        const val MAX_COLUMNS = 4096
    }
}
