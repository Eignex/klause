package com.eignex.klause.propagation

import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.plus

internal class LearnedExactPbPropagator(
    val weights: Array<BigInt>,
    val literals: IntArray,
    val degree: BigInt,
) : LearnedPropagator {
    override val boolVars: IntArray = literals.litVars()
    override val intVars: IntArray = emptyIntArray()
    override val initialBoolWatchers: IntArray = literals

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var available = BIG_ZERO
        for (index in literals.indices) {
            state.checkFiniteWork()
            state.checkIntegerScalarWork(available, weights[index])
            if (!state.litFalse(literals[index])) available += weights[index]
        }
        if (available < degree) return false
        val slack = available - degree
        for (index in literals.indices) {
            state.checkFiniteWork()
            val literal = literals[index]
            if (weights[index] <= slack || state.litTruth(literal) != null) continue
            val reason = falsePremises(state)
            if (!state.pinBool(Lit.variable(literal), Lit.isPositive(literal), reason)) return false
        }
        return true
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray = falsePremises(state)

    private fun falsePremises(state: PropagationState): IntArray {
        val out = ArrayList<Int>()
        for (literal in literals) {
            state.checkFiniteWork()
            if (state.litFalse(literal)) out.add(literal)
        }
        return out.toIntArray()
    }

    fun sharingMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.ownedReferences(weights, weights.size.toLong(), protected)
        for (weight in weights) sample.owned(weight, 32L + (weight.magnitudeBitLength().toLong() + 7) / 8, protected)
        sample.owned(degree, 32L + (degree.magnitudeBitLength().toLong() + 7) / 8, protected)
        sample.owned(literals, literals.size.toLong() * Int.SIZE_BYTES, protected)
    }
}
