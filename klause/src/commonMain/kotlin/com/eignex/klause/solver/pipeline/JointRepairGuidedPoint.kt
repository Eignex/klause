package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation

internal object JointRepairGuidedPoint {
    fun propose(
        model: Problem, center: Sample, condition: ExactNeighborhoodCondition,
        hint: LinearRepairGuidance.Hint, budget: SourceLpBudget, token: Cancellation,
    ): Sample? {
        val columns = model.numBoolVars.toLong() + model.numIntVars + model.numRealVars
        if (columns > LinearRepairGuidance.MAX_COLUMNS || model.numRealVars == 0 ||
            center.exactInts != null || center.numBoolVars != model.numBoolVars ||
            center.numIntVars != model.numIntVars || center.numRealVars != model.numRealVars || token()
        ) return null
        val funding = budget.funding ?: return null
        val work = 1L + 4L * columns
        val bytes = 512L + 64L * columns
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return null
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        val bounds = model.intBounds
        val ints = center.ints
        var changed = false
        for (id in ints.indices) {
            if (token() || !bounds.hasLower(id) || !bounds.hasUpper(id) ||
                bounds.lower(id) < 0L || bounds.upper(id) > 1L) return null
            if (id in condition.lower || id in condition.upper) continue
            val value = hint.integer(id)?.takeIf { it in 0.0..1.0 } ?: continue
            val proposed = if (value >= 0.5) 1L else 0L
            if (proposed !in bounds.lower(id)..bounds.upper(id)) continue
            changed = changed || ints[id] != proposed
            ints[id] = proposed
        }
        if (!changed || token()) return null
        val reals = DoubleArray(model.numRealVars) { hint.real(it) ?: center.approximateRealValue(it) }
        // Changed discrete coordinates invalidate the center's exact payload and certificates.
        return center.copy(ints = ints, reals = reals, exactReals = null,
            exactInts = null, algebraicReals = null, symbolicReals = null).takeUnless { token() }
    }
}
