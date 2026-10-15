package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.ceilDiv
import com.eignex.klause.factor.arithmetic.internals.floorDiv
import com.eignex.klause.factor.arithmetic.internals.wideNeverHolds
import com.eignex.klause.factor.arithmetic.internals.wideSumRange
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundAt
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.lazyReasonSlots
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class GridArrayMinMaxPropagator(private val factor: GridArrayMinMax) : Propagator {
    private val bounds = (1 until factor.coordinates.size).map {
        factor.comparison(it, equality = false)
    }
    private val rows = (1 until factor.coordinates.size).map { factor.comparison(it, equality = true) }

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
        for (bound in bounds) if (!enforce(bound, state, factorId)) return false
        val candidates = rows.filter { row ->
            val constants = checkNotNull(row.integralConstants)
            val range = wideSumRange(state, row.vars, Array(row.vars.size, constants::exactCoeff))
            !wideNeverHolds(row.op, range.first, range.second, constants.exactBound)
        }
        return candidates.isNotEmpty() && (candidates.size != 1 || enforce(candidates.single(), state, factorId))
    }

    private fun enforce(row: Linear, state: PropagationState, factorId: Int): Boolean {
        val constants = checkNotNull(row.integralConstants)
        val coefficients = Array(row.vars.size, constants::exactCoeff)
        val (lower, upper) = wideSumRange(state, row.vars, coefficients)
        val bound = constants.exactBound
        if (wideNeverHolds(row.op, lower, upper, bound)) return false
        for (position in row.vars.indices) {
            val coefficient = coefficients[position]
            if (coefficient == BIG_ZERO) continue
            val variable = row.vars[position]
            val (currentLower, currentUpper) = wideSumRange(state, row.vars, coefficients)
            val domain = state.intDomains[variable]
            val positive = coefficient > BIG_ZERO
            val termLower = coefficient * bigIntOf(if (positive) domain.min else domain.max)
            val termUpper = coefficient * bigIntOf(if (positive) domain.max else domain.min)
            val slack = bound - (currentLower - termLower)
            val maximum = if (positive) floorDiv(slack, coefficient) else ceilDiv(slack, coefficient)
            if (!tighten(state, factorId, variable, maximum, lower = !positive)) return false
            if (row.op == LinearOp.EQ) {
                val needed = bound - (currentUpper - termUpper)
                val minimum = if (positive) ceilDiv(needed, coefficient) else floorDiv(needed, coefficient)
                if (!tighten(state, factorId, variable, minimum, lower = positive)) return false
            }
        }
        return true
    }

    private fun tighten(state: PropagationState, factorId: Int, variable: Int, value: BigInt, lower: Boolean): Boolean {
        val domain = state.intDomains[variable]
        if (lower && value <= bigIntOf(domain.min) || !lower && value >= bigIntOf(domain.max)) return true
        if (lower && value > bigIntOf(domain.max) || !lower && value < bigIntOf(domain.min)) return false
        val reason = when {
            state.currentLevel == 0 -> null
            state.undoLogging -> state.lazyReasonSlots(0, factorId)
            else -> premises(state, state.undo.size, state.currentLevel)
        }
        return if (lower) state.tightenIntMin(variable, value.toLongExact(), reason)
            else state.tightenIntMax(variable, value.toLongExact(), reason)
    }
}
