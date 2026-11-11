package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.magnitudeBitLength

internal class JointRepairCenter(
    private val model: Problem,
    private val budget: SourceLpBudget,
    private val stop: Cancellation,
    private val eligible: (Int) -> Boolean,
) {
    class Step(val condition: ExactNeighborhoodCondition, val coordinate: Int,
        val before: BigFraction, val after: BigFraction)

    private fun pay(work: Long, bytes: Long = 0L): Boolean {
        val funding = budget.funding ?: return false
        if (stop() || funding.remainingWork <= work || funding.remainingAllocation <= bytes) return false
        // Advisory state spends the containing ledger without renewing numerical grants.
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        return !stop() && !funding.exhausted
    }

    fun suggest(assumptions: Set<SourceDerivation.Assumption>, captured: ExactNeighborhoodCondition,
        original: ExactNeighborhoodCondition): Step? {
        if (captured.cutoff != null) return null
        var visited = 0
        for (assumption in assumptions) {
            if (++visited > MAX_ENTRIES || !pay(1L, 64L)) return null
            val coordinate = assumption.coordinate
            val id = coordinate.id
            if (coordinate.kind != SourceCoordinate.Kind.Integer || id !in 0 until model.numIntVars ||
                !eligible(id)) continue
            val before = captured.lower[id] ?: continue
            if (captured.upper[id] != before || before.den != BIG_ONE) continue
            val bits = before.num.magnitudeBitLength().toLong() + before.den.magnitudeBitLength()
            if (bits > MAX_SCALAR_BITS) continue
            if (!pay(8L + bits * bits / 64L + 2L * bits, 192L + (bits + 7L) / 2L)) return null
            val after = if (assumption.upper) before + BigFraction.ONE else before - BigFraction.ONE
            if (after.num.magnitudeBitLength().toLong() + after.den.magnitudeBitLength() > MAX_SCALAR_BITS) continue
            if (!legal(id, after)) continue
            if (stop() || budget.funding?.exhausted != false) return null
            return Step(original, id, before, after)
        }
        return null
    }

    private fun legal(id: Int, value: BigFraction): Boolean {
        val bounds = model.intBounds
        return value.den == BIG_ONE &&
            (!bounds.hasLower(id) || value >= BigFraction.ofLong(bounds.lower(id))) &&
            (!bounds.hasUpper(id) || value <= BigFraction.ofLong(bounds.upper(id)))
    }

    fun apply(step: Step, centers: MutableList<BigFraction>, bools: BooleanArray,
        condition: ExactNeighborhoodCondition, cancellation: Cancellation): Boolean {
        if (step.condition !== condition || condition.cutoff != null || cancellation() ||
            step.coordinate !in 0 until model.numIntVars || !eligible(step.coordinate)) return false
        val entries = condition.bools.size.toLong() + condition.lower.size + condition.upper.size
        if (entries > MAX_ENTRIES || !pay(1L + entries, 64L)) return false
        for ((id, value) in condition.bools) {
            if (stop() || cancellation() || id !in bools.indices || bools[id] != value) return false
        }
        for (bounds in listOf(condition.lower, condition.upper)) for ((id, value) in bounds) {
            if (id !in 0 until model.numIntVars || id !in centers.indices) return false
            val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
            if (bits > MAX_SCALAR_BITS || !pay(1L + bits * bits / 64L) || cancellation()) return false
            if (centers[id] != value || condition.lower[id] != condition.upper[id]) return false
        }
        if (step.coordinate !in centers.indices || centers[step.coordinate] != step.before ||
            !pay(1L, 64L) || cancellation() || !legal(step.coordinate, step.after)) return false
        centers[step.coordinate] = step.after
        return true
    }
}

private const val MAX_ENTRIES = 4096
private const val MAX_SCALAR_BITS = 4096
