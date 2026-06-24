package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.arithmetic.LinearOp

/** LP relaxation of a [PseudoBoolean]: the feasibility-defining row `Σ weights·literals ⟨op⟩ bound`. */
internal class PseudoBooleanLinearizer(
    private val weights: IntArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Int,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val linearOp = when (op) {
            PbOp.LE -> LinearOp.LE
            PbOp.GE -> LinearOp.GE
            PbOp.EQ -> LinearOp.EQ
        }
        builder.boolRow(literals, weights, linearOp, bound.toLong())
    }
}
