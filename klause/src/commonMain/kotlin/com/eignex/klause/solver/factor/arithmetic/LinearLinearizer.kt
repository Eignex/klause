package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Contribution
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder

/** LP relaxation of a [Linear] constraint: the single feasibility-defining row `Σ coeffs·vars ⟨op⟩ bound`. */
internal class LinearLinearizer(
    private val op: LinearOp,
    private val vars: IntArray,
    private val coeffs: IntArray,
    private val bound: Int,
) : Linearizer {
    override val contribution: Contribution get() = Contribution.CORE

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.linearRow(op, vars, coeffs, bound.toLong())
    }
}
