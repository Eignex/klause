package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

internal class LinearIncumbentExchange private constructor(
    private val source: SourceProofContext,
    private val center: Sample,
    private val columns: List<Int>,
    private val budget: SourceLpBudget,
    private val claim: () -> Boolean,
    private val observe: (String, Long) -> Unit,
) : AutoCloseable {
    private val completion = LinearIncumbentCompletion(budget, observe = observe)
    private var cursor = 0
    private var pending: Sample? = null
    var finished = false
        private set

    fun advance(cancellation: ExecutionControl): Sample? {
        while (!finished && !cancellation() && budget.funding?.paused != true) {
            if (pending == null) {
                if (cursor == columns.size || !claim()) {
                    finished = true
                    return null
                }
                val bytes = 256L + 128L * (center.numIntVars.toLong() + center.numRealVars)
                val funding = budget.funding ?: return null
                if (funding.availableWork <= center.numIntVars.toLong() + center.numRealVars ||
                    funding.remainingAllocation < bytes) {
                    finished = true
                    return null
                }
                budget.observe(preparation = center.numIntVars.toLong() + center.numRealVars)
                funding.charge(0L, bytes)
                val integers = LongArray(center.numIntVars) { center.exactIntValue(it).signum().toLong() }
                val id = columns[cursor]
                integers[id] = 1L - integers[id]
                pending = center.copy(ints = integers, exactInts = null, exactReals = null,
                    algebraicReals = null, symbolicReals = null)
                observe("Attempts", 1L)
            }
            val point = completion.complete(source, requireNotNull(pending), cancellation)
            if (point != null) {
                finished = true
                observe("Witnesses", 1L)
                return point
            }
            if (cancellation() || budget.funding?.paused == true) return null
            pending = null
            cursor++
        }
        return null
    }

    override fun close() = completion.close()

    companion object {
        fun prepare(source: SourceProofContext, center: Sample, budget: SourceLpBudget,
            cancellation: ExecutionControl, claim: () -> Boolean,
            observe: (String, Long) -> Unit): LinearIncumbentExchange? {
            if (center.numBoolVars != 0 || center.numIntVars !in 1..4096 || center.numRealVars == 0 ||
                cancellation()) return null
            val funding = budget.funding ?: return null
            val best = ArrayList<Pair<Int, BigFraction>>()
            val maximize = source.scope.objective?.sense == ObjectiveSense.MAXIMIZE
            for (id in 0 until center.numIntVars) {
                if (cancellation()) return null
                val value = center.exactIntValue(id)
                if (value.signum() < 0 || value > BIG_ONE) return null
                val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, id)
                val opposite = if (value.signum() == 0) BigFraction.ONE else BigFraction.ZERO
                if (source.scope.condition.lower[coordinate]?.let { it > opposite } == true ||
                    source.scope.condition.upper[coordinate]?.let { it < opposite } == true) continue
                var cost = source.objective[coordinate] ?: BigFraction.ZERO
                if ((value.signum() > 0) != maximize) cost = cost.negated()
                val bits = cost.num.magnitudeBitLength().toLong() + cost.den.magnitudeBitLength()
                val work = 16L + bits * bits / 8L
                val bytes = 128L + bits
                if (bits > 4096 || funding.availableWork <= work || funding.remainingAllocation <= bytes) return null
                budget.observe(preparation = work)
                funding.charge(0L, bytes)
                val index = best.indexOfFirst { cost < it.second }.let { if (it < 0) best.size else it }
                best.add(index, id to cost)
                if (best.size > 4) best.removeAt(best.lastIndex)
            }
            if (best.isEmpty() || cancellation() || funding.exhausted) return null
            return LinearIncumbentExchange(source, center, best.map { it.first }, budget, claim, observe)
        }
    }
}
