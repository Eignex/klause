package com.eignex.klause.solver

import com.eignex.klause.solver.factor.arithmetic.LinearOp

/**
 * The sink a [Linearizer] emits its LP relaxation into. A factor states linear constraints over the
 * problem's integer variables by raw id; the driver behind this interface maps each variable to its
 * LP column, caps the model, and tracks row provenance — a [Linearizer] never touches the underlying
 * tableau.
 */
interface RelaxationBuilder {
    /**
     * Emit `Σ coeffs(k) · intVars(k) ⟨op⟩ bound` over integer variables (raw ids). [LinearOp.NE] is
     * not linear-relaxable and is ignored.
     */
    fun linearRow(op: LinearOp, intVars: IntArray, coeffs: IntArray, bound: Long)
}
