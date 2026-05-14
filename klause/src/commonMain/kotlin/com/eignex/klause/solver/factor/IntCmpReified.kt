package com.eignex.klause.solver.factor

import com.eignex.klause.ast.IntCmpOp

/**
 * Convenience factory: build a [ReifiedLinear] from an [IntCmpOp] over a single int var.
 * `aux ↔ (intVar ⟨op⟩ bound)` with unit coefficient.
 *
 * [IntCmpOp.LT] and [IntCmpOp.GT] are folded into [LinearOp.LE] / [LinearOp.GE] with the
 * bound shifted by 1, since [LinearOp] has no strict-inequality forms.
 */
fun reifiedIntCompare(auxBoolVar: Int, intVar: Int, op: IntCmpOp, bound: Int): ReifiedLinear {
    val (linOp, adjustedBound) = when (op) {
        IntCmpOp.LE -> LinearOp.LE to bound
        IntCmpOp.LT -> LinearOp.LE to (bound - 1)
        IntCmpOp.GE -> LinearOp.GE to bound
        IntCmpOp.GT -> LinearOp.GE to (bound + 1)
        IntCmpOp.EQ -> LinearOp.EQ to bound
        IntCmpOp.NE -> LinearOp.NE to bound
    }
    return ReifiedLinear(auxBoolVar, intArrayOf(1), intArrayOf(intVar), linOp, adjustedBound)
}
