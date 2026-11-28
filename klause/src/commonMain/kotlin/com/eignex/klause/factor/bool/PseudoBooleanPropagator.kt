package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.factor.bool.internals.propagatePbBounds
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.LearnedPropagator
import com.eignex.klause.propagation.PbAccumulator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.installLitWatch
import com.eignex.klause.propagation.removeBoolWatch
import com.eignex.util.arrays.argsortBy
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList

// [watched] enables weighted watched-literal wakeup (Chai and Kuehlmann, "A fast pseudo-Boolean constraint
// solver", IEEE TCAD 24(3), 2005, https://doi.org/10.1109/TCAD.2004.842808) for learned `≥` constraints:
// while the non-false watched weight is at least `bound + maxCoeff`, the slack covers every coefficient, so
// the constraint can neither propagate nor conflict and need only fire when a watched literal goes false.
internal class PseudoBooleanPropagator(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    internal val weights: LongArray,
    val literals: IntArray,
    private val op: PbOp,
    private val bound: Long,
    private val watched: Boolean = false,
) : LearnedPropagator {

    private val watchTarget: Long = if (watched) bound + (weights.maxOrNull() ?: 0L) else 0L

    // Greedy pick order for the smallest covering watch set.
    internal val byWeightDesc: IntArray = if (watched) {
        argsortBy(literals.size) { a, b -> weights[b].compareTo(weights[a]) }
    } else {
        emptyIntArray()
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        if (watched) propagateWatched(state, factorId) else propagatePbBounds(state, weights, literals, op, bound)

    fun selectWatched(state: PropagationState): IntArray = selectCover(state).first

    private fun selectCover(state: PropagationState): Pair<IntArray, Boolean> {
        val out = IntArrayList(byWeightDesc.size)
        var sum = 0L
        for (idx in byWeightDesc) {
            val lit = literals[idx]
            if (state.litFalse(lit)) continue
            out.add(lit)
            sum += weights[idx]
            if (sum >= watchTarget) return out.toIntArray() to true
        }
        return out.toIntArray() to false
    }

    private fun propagateWatched(state: PropagationState, factorId: Int): Boolean {
        val old = state.refPayload[factorId] as? IntArray ?: emptyIntArray()
        val (next, covered) = selectCover(state)
        // Safe to mutate the watcher index: propagate runs after the wakeup walk over the fired literal's list.
        for (lit in old) if (!next.contains(lit)) state.removeBoolWatch(factorId, lit)
        for (lit in next) if (!old.contains(lit)) state.installLitWatch(lit, factorId)
        state.refPayload[factorId] = next
        // Uncovered, [next] watches every non-false literal so any further change re-fires.
        if (covered) return true
        return propagatePbBounds(state, weights, literals, op, bound)
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = Lit.NONE)

    // An `EQ` loads the half that forced [forcedLit], or for a seed the violated half.
    fun loadReason(acc: PbAccumulator, forcedLit: Int, state: PropagationState): Boolean = when (op) {
        PbOp.GE -> acc.loadPb(weights, literals, geBound = bound)

        PbOp.LE -> loadLe(acc)

        PbOp.EQ -> when {
            forcedLit != Lit.NONE -> if (forcedByGeSide(
                    forcedLit,
                )
            ) {
                acc.loadPb(weights, literals, geBound = bound)
            } else {
                loadLe(acc)
            }

            geSideViolated(state) -> acc.loadPb(weights, literals, geBound = bound)

            else -> loadLe(acc)
        }
    }

    private fun geSideViolated(state: PropagationState): Boolean {
        var nonFalseSum = 0L
        for (i in literals.indices) {
            val b = state.boolValues[Lit.variable(literals[i])]
            if (b == null || Lit.evaluate(literals[i], b)) nonFalseSum += weights[i]
        }
        return nonFalseSum < bound
    }

    // `Σ w·ℓ ≤ bound` as its `≥` complement `Σ w·¬ℓ ≥ (Σw − bound)`.
    private fun loadLe(acc: PbAccumulator): Boolean {
        var sum = 0L
        for (w in weights) sum += w
        val flipped = IntArray(literals.size) { literals[it] xor 1 }
        return acc.loadPb(weights, flipped, geBound = sum - bound)
    }

    private fun forcedByGeSide(forcedLit: Int): Boolean {
        val v = Lit.variable(forcedLit)
        for (lit in literals) if (Lit.variable(lit) == v) return lit == forcedLit
        return true
    }
}
