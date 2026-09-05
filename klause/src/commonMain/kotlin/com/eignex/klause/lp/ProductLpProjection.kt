package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.LinearOp

/**
 * LP relaxation — the four McCormick envelope inequalities `(a−aL)(b−bL) ≥ 0`, `(a−aH)(b−bH) ≥ 0`,
 * `(aH−a)(b−bL) ≥ 0`, `(a−aL)(bH−b) ≥ 0`, each expanded to a linear row in `result, a, b`. Bounds are
 * the root boxes, so the rows are global and the relaxation never cuts a feasible point. For
 * `a = b` (a square) the `a` and `b` coefficients coalesce into the secant/tangent relaxation. HULL.
 *
 * Each envelope leans on one endpoint of each operand, and an endpoint the model does not state was
 * invented to close the search box: the product may leave that box at a genuine solution, and the row
 * built on it would cut the solution off. So the four are emitted individually, each only over the two
 * sides the model states — a square with one open side keeps the two envelopes on its stated side.
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

    // Each row is `result + ca·a + cb·b ⟨op⟩ rhs`; coefficients coalesce when a and b coincide.
    fun mcCormick(ca: Long, cb: Long, op: LinearOp, rhs: Long) =
        builder.row(intArrayOf(resCol, aCol, bCol), longArrayOf(1L, ca, cb), op, rhs, Contribution.HULL)

    if (aLow && bLow) mcCormick(-bL, -aL, LinearOp.GE, -(aL * bL))
    if (aHigh && bHigh) mcCormick(-bH, -aH, LinearOp.GE, -(aH * bH))
    if (aHigh && bLow) mcCormick(-bL, -aH, LinearOp.LE, -(aH * bL))
    if (aLow && bHigh) mcCormick(-bH, -aL, LinearOp.LE, -(aL * bH))
}
