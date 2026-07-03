package com.eignex.klause.factor.arithmetic

import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.RelaxationBuilder

/** LP relaxation of a [Linear] constraint: the single feasibility-defining row `Σ coeffs·vars ⟨op⟩ bound`. */
internal class LinearLinearizer(
    private val op: LinearOp,
    private val vars: IntArray,
    private val coeffs: IntArray,
    private val bound: Int,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.linearRow(op, vars, coeffs, bound.toLong())
    }
}
