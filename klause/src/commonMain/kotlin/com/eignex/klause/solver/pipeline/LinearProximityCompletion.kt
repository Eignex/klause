package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearAuthorityResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

internal class LinearProximityCompletion(
    private val source: SourceProofContext,
    private val center: Sample,
    private val budget: SourceLpBudget,
    private val claimDecision: () -> Boolean,
    private val observe: (String, Long) -> Unit,
) : AutoCloseable {
    private class Preparation(val coordinates: List<SourceCoordinate>,
        val point: Map<SourceCoordinate, Double>, val integers: LongArray, val reals: DoubleArray) {
        var next = 0
        var integerCount = 0
        var realCount = 0
    }

    private val completion = LinearIncumbentCompletion(budget, ownedSeed = true, observe = observe)
    private var preparation: Preparation? = null
    private var prepared = false
    private var claimed = false
    private var finished = false
    private var seed: Sample? = null
    var checked: Sample? = null
        private set

    fun takeChecked(): Sample? = checked.also { checked = null }

    fun advance(result: LinearAuthorityResult, context: SearchContext, stop: ExecutionControl): Boolean {
        if (finished) return false
        if (stop()) return true
        if (!prepared) {
            seed = prepare(result, stop)
            if (!prepared) return true
        }
        val candidate = seed ?: return finish()
        if (!claimed) {
            claimed = true
            if (!context.consumeCheck() || !claimDecision()) return finish()
            observe("Trials", 1L)
        }
        checked = completion.complete(source, candidate, stop)
        if (completion.preparationInterrupted) return finish()
        if (checked != null) {
            observe("Witnesses", 1L)
            return finish()
        }
        if (stop() || budget.funding?.paused == true) return true
        return finish()
    }

    private fun finish(): Boolean {
        finished = true
        preparation = null
        seed = null
        return false
    }

    private fun prepare(result: LinearAuthorityResult, stop: ExecutionControl): Sample? {
        if (preparation == null) {
            preparation = initialize(result) ?: return declinePreparation()
        }
        val pending = requireNotNull(preparation)
        while (pending.next < pending.coordinates.size) {
            if (stop()) return null
            val coordinate = pending.coordinates[pending.next]
            val value = pending.point[coordinate]?.takeIf(Double::isFinite) ?: return declinePreparation()
            when (coordinate.kind) {
                SourceCoordinate.Kind.Integer -> {
                    val integer = roundedInteger(coordinate, value) ?: return declinePreparation()
                    pending.integers[coordinate.id] = integer
                    pending.integerCount++
                }
                SourceCoordinate.Kind.Real -> {
                    if (coordinate.id !in pending.reals.indices) return declinePreparation()
                    pending.reals[coordinate.id] = value
                    pending.realCount++
                }
                else -> return declinePreparation()
            }
            pending.next++
        }
        if (stop()) return null
        if (pending.integerCount != pending.integers.size || pending.realCount != pending.reals.size)
            return declinePreparation()
        prepared = true
        preparation = null
        return Sample(booleanArrayOf(), pending.integers, pending.reals)
    }

    private fun declinePreparation(): Sample? {
        prepared = true
        preparation = null
        return null
    }

    private fun initialize(result: LinearAuthorityResult): Preparation? {
        val point = result.relaxedPoint
        val count = source.coordinateCount
        if (result.evidence != null || result.incumbent != null || result.fractional.isEmpty() ||
            center.numBoolVars != 0 || center.numIntVars !in 1..4096 || count !in 1..4096 ||
            count != center.numIntVars + center.numRealVars || point.size != count) return null
        val funding = budget.funding ?: return null
        val work = 32L * (count + 1L)
        val bytes = 256L * (count + 1L)
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return null
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        return Preparation(source.coordinates.toList(), point, LongArray(center.numIntVars),
            DoubleArray(center.numRealVars))
    }

    private fun roundedInteger(coordinate: SourceCoordinate, value: Double): Long? {
        val id = coordinate.id
        if (id !in 0 until center.numIntVars || value !in 0.0..1.0) return null
        val previous = center.exactIntValue(id)
        if (previous.magnitudeBitLength() > 1 || previous.signum() < 0 || previous > BIG_ONE) return null
        val rounded = when {
            value < 0.5 -> 0L
            value > 0.5 -> 1L
            previous.signum() == 0 -> 1L
            else -> 0L
        }
        val lower = source.scope.condition.boundValue(coordinate, upper = false)
        val upper = source.scope.condition.boundValue(coordinate, upper = true)
        fun allowed(integer: Long): Boolean {
            val exact = BigFraction.ofLong(integer)
            return (lower == null || exact >= lower) && (upper == null || exact <= upper)
        }
        return when {
            allowed(rounded) -> rounded
            allowed(1L - rounded) -> 1L - rounded
            else -> null
        }
    }

    override fun close() {
        preparation = null
        seed = null
        checked = null
        finished = true
        completion.close()
    }
}
