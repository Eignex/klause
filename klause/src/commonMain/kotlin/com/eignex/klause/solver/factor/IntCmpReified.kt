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

        // bound ∓ 1 widened to Long so a bound at Int.MIN/MAX doesn't wrap (issue #73).
        IntCmpOp.LT -> LinearOp.LE to checkedBound(bound.toLong() - 1, op, bound)

        IntCmpOp.GE -> LinearOp.GE to bound

        IntCmpOp.GT -> LinearOp.GE to checkedBound(bound.toLong() + 1, op, bound)

        IntCmpOp.EQ -> LinearOp.EQ to bound

        IntCmpOp.NE -> LinearOp.NE to bound
    }
    return ReifiedLinear(auxBoolVar, intArrayOf(1), intArrayOf(intVar), linOp, adjustedBound)
}

/** Narrow a strict-inequality bound adjustment to `Int`, failing loudly when the ±1 shift
 *  would wrap past the `Int` range (e.g. `x > Int.MAX_VALUE`). */
private fun checkedBound(value: Long, op: IntCmpOp, bound: Int): Int {
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "reifiedIntCompare: $op bound $bound adjusts to $value which exceeds Int range"
    }
    return value.toInt()
}
