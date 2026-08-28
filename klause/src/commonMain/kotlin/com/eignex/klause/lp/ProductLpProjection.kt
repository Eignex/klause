package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.LinearOp

/**
 * LP relaxation — the four McCormick envelope inequalities `(a−aL)(b−bL) ≥ 0`, `(a−aH)(b−bH) ≥ 0`,
 * `(aH−a)(b−bL) ≥ 0`, `(a−aL)(bH−b) ≥ 0`, each expanded to a linear row in `result, a, b`. Bounds are
 * the declared domains, so the rows are global and the relaxation never cuts a feasible point. For
 * `a = b` (a square) the `a` and `b` coefficients coalesce into the secant/tangent relaxation. HULL.
 */
internal fun Product.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    val aDom = builder.declaredDomain(a)
    val bDom = builder.declaredDomain(b)
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

    mcCormick(-bL, -aL, LinearOp.GE, -(aL * bL))
    mcCormick(-bH, -aH, LinearOp.GE, -(aH * bH))
    mcCormick(-bL, -aH, LinearOp.LE, -(aH * bL))
    mcCormick(-bH, -aL, LinearOp.LE, -(aL * bH))
}
