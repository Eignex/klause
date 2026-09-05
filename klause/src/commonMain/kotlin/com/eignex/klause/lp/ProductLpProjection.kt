package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.mulExact

/**
 * LP relaxation — the four McCormick envelope inequalities `(a−aL)(b−bL) ≥ 0`, `(a−aH)(b−bH) ≥ 0`,
 * `(aH−a)(b−bL) ≥ 0`, `(a−aL)(bH−b) ≥ 0`, each expanded to a linear row in `result, a, b`. Bounds are
 * the root boxes, so the rows are global and the relaxation never cuts a feasible point. For
 * `a = b` (a square) the `a` and `b` coefficients coalesce into the secant/tangent relaxation. HULL.
 *
 * Each envelope leans on one endpoint of each operand, and an endpoint the model does not state was
 * invented to close the search box: the product may leave that box at a genuine solution, and the row
 * built on it would cut the solution off. So the four are emitted individually, each only over the two
 * sides the model states — a square with one open side keeps the single envelope tangent to it.
 */
internal fun Product.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    val aLow = builder.statesLowerBound(a)
    val aHigh = builder.statesUpperBound(a)
    val bLow = builder.statesLowerBound(b)
    val bHigh = builder.statesUpperBound(b)
    // An operand with neither side stated leaves no envelope at all; allocate no column for it.
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

    /**
     * `(a − ea)(b − eb) ⟨op⟩ 0` expanded to `result − eb·a − ea·b ⟨op⟩ −ea·eb`; coefficients coalesce
     * when a and b coincide. The constants are a product of two endpoints, so a model stating wide
     * bounds can push them past 64 bits — that envelope is declined rather than emitted at a wrapped
     * constant, which would cut off feasible points while marked globally valid.
     */
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
