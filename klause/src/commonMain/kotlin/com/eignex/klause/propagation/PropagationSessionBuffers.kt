package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem

internal class PropagationSessionBuffers(
    val boolPinned: IntArray,
    val intPinnedSet: BooleanArray,
    val intPinnedVal: LongArray,
    val impliedIntUndo: IntArray,
) {
    companion object {
        fun preparation(problem: Problem): Sequence<PropagationSessionBuffers?> = sequence {
            val boolPinned = IntArray(problem.numBoolVars)
            yield(null)
            for (v in boolPinned.indices) {
                boolPinned[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val intPinnedSet = BooleanArray(problem.numIntVars)
            yield(null)
            val intPinnedVal = LongArray(problem.numIntVars)
            yield(null)
            val impliedIntUndo = IntArray(problem.numIntVars)
            yield(null)
            for (v in impliedIntUndo.indices) {
                impliedIntUndo[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            yield(PropagationSessionBuffers(boolPinned, intPinnedSet, intPinnedVal, impliedIntUndo))
        }
    }
}
