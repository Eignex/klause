package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.LinearOp
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.mulExact

// The four McCormick envelope rows over the root boxes. Each leans on one endpoint per operand, and an
// unstated endpoint was invented to close the search box, so each row is emitted only over two stated
// sides; a square with one open side keeps the single tangent envelope.
//
// McCormick, "Computability of global solutions to factorable nonconvex programs: Part I — Convex
// underestimating problems", Mathematical Programming 10 (1976), https://doi.org/10.1007/BF01580665.
internal fun Product.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    val aLow = builder.statesLowerBound(a)
    val aHigh = builder.statesUpperBound(a)
    val bLow = builder.statesLowerBound(b)
    val bHigh = builder.statesUpperBound(b)
    if (!(aLow || aHigh) || !(bLow || bHigh)) return
    val aDom = builder.rootDomain(a)
    val bDom = builder.rootDomain(b)
    val aL = aDom.min
    val aH = aDom.max
    val bL = bDom.min
    val bH = bDom.max
    val resCol = builder.intColumn(result)
    val aCol = builder.intColumn(a)
    val bCol = builder.intColumn(b)

    // `(a − ea)(b − eb) ⟨op⟩ 0` as `result − eb·a − ea·b ⟨op⟩ −ea·eb`. An envelope whose constants pass
    // 64 bits is declined: a wrapped constant would cut off feasible points.
    fun mcCormick(ea: Long, eb: Long, op: LinearOp) {
        val coeffs: LongArray
        val rhs: Long
        try {
            coeffs = longArrayOf(1L, mulExact(-1L, eb), mulExact(-1L, ea))
            rhs = mulExact(-1L, mulExact(ea, eb))
        } catch (_: CheckedLongOverflowException) {
            return
        }
        builder.row(intArrayOf(resCol, aCol, bCol), coeffs, op, rhs, Contribution.HULL)
    }

    if (aLow && bLow) mcCormick(aL, bL, LinearOp.GE)
    if (aHigh && bHigh) mcCormick(aH, bH, LinearOp.GE)
    if (aHigh && bLow) mcCormick(aH, bL, LinearOp.LE)
    if (aLow && bHigh) mcCormick(aL, bH, LinearOp.LE)
}
