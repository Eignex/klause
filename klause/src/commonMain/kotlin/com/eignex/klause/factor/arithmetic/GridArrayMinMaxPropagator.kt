package com.eignex.klause.factor.arithmetic

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

internal class GridArrayMinMaxPropagator(factor: GridArrayMinMax) : Propagator {
    private val bounds = (1 until factor.coordinates.size).map {
        factor.comparison(it, equality = false).propagatorProjection()
    }
    private val rows = (1 until factor.coordinates.size).map { factor.comparison(it, equality = true) }
    private val equalities = rows.map { it.propagatorProjection() }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (bound in bounds) if (!bound.propagate(state, factorId)) return false
        var candidate = -1
        var count = 0
        for ((index, row) in rows.withIndex()) {
            val constants = checkNotNull(row.integerConstants)
            var lower = BIG_ZERO
            var upper = BIG_ZERO
            for (position in row.vars.indices) {
                val coefficient = constants.coeff(position)
                val domain = state.intDomains[row.vars[position]]
                lower += bigIntOf(coefficient) * bigIntOf(if (coefficient >= 0) domain.min else domain.max)
                upper += bigIntOf(coefficient) * bigIntOf(if (coefficient >= 0) domain.max else domain.min)
            }
            val bound = bigIntOf(constants.bound)
            if (lower > bound || upper < bound) continue
            candidate = index
            count++
        }
        return count > 0 && (count != 1 || equalities[candidate].propagate(state, factorId))
    }
}
