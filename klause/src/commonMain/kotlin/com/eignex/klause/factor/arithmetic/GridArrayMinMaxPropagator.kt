package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundAt
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

internal class GridArrayMinMaxPropagator(private val factor: GridArrayMinMax) : Propagator {
    private val bounds = (1 until factor.coordinates.size).map {
        factor.comparison(it, equality = false).propagatorProjection()
    }
    private val rows = (1 until factor.coordinates.size).map { factor.comparison(it, equality = true) }
    private val equalities = rows.map { it.propagatorProjection() }

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int): IntArray =
        premises(state, atTrail, atLevel)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray =
        premises(state, state.undo.size, state.currentLevel)

    // An equality selected by excluding other operands needs every bound that justified those exclusions.
    private fun premises(state: PropagationState, atTrail: Int, atLevel: Int): IntArray {
        val literals = IntArrayList()
        for (variable in factor.variables.ints) for (lower in listOf(false, true)) {
            val value = state.boundAt(variable, lower, atTrail)
            val literal = state.boundLiteral(variable, lower, value, atTrail, atLevel)
            if (literal != Lit.NONE) literals.add(literal)
        }
        return literals.toIntArray()
    }

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
