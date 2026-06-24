package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.arithmetic.LinearOp

/** LP relaxation of a [Clause]: the feasibility-defining row `Σ literals ≥ 1`. */
internal class ClauseLinearizer(private val literals: IntArray) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = 1L)
    }
}
