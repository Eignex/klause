package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength
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

    fun rememberIntegerPoint(source: SourceProofContext, coordinates: List<SourceCoordinate>,
        point: List<BigFraction>, budget: SourceLpBudget, token: ExecutionControl): Boolean {
        if (state.load() != State.Empty || coordinates.size != point.size || coordinates.isEmpty() ||
            coordinates.size > MAX_COLUMNS || source.coordinateCount > MAX_COLUMNS || token()) return false
        val funding = budget.funding ?: return false
        val scan = 1L + 2L * coordinates.size + source.coordinateCount
        val bytes = 256L + 128L * coordinates.size
        if (funding.availableWork <= scan || funding.remainingAllocation <= bytes) return false
        budget.observe(preparation = scan)
        funding.charge(0L, bytes)
        val sourceCoordinates = source.coordinates
        val integers = sourceCoordinates.count { it.kind == SourceCoordinate.Kind.Integer }
        if (integers == 0) return false
        var conversion = 0L
        for (index in coordinates.indices) {
            if (token()) return false
            val coordinate = coordinates[index]
            if (coordinate.kind != SourceCoordinate.Kind.Integer) continue
            if (coordinate !in sourceCoordinates) return false
            val value = point[index]
            val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
            if (bits > MAX_SCALAR_BITS) return false
            conversion += 1L + bits * bits / 64L
        }
        if (funding.availableWork <= conversion) return false
        budget.observe(preparation = conversion)
        val copy = LinkedHashMap<SourceCoordinate, Double>()
        for (index in coordinates.indices) {
            if (token()) return false
            val coordinate = coordinates[index]
            if (coordinate.kind != SourceCoordinate.Kind.Integer) continue
            val value = point[index].toDouble().takeIf(Double::isFinite) ?: return false
            copy[coordinate] = value
        }
        if (copy.size != integers || token()) return false
        return state.compareAndSet(State.Empty, State.Available(Hint(source, copy)))
    }

    override fun close() {
        state.store(State.Closed)
    }

    companion object {
        const val MAX_COLUMNS = 4096
        private const val MAX_SCALAR_BITS = 4096
    }
}
