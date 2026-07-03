package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.RelaxationBuilder

/** LP relaxation of a [Clause]: the feasibility-defining row `Σ literals ≥ 1`. */
internal class ClauseLinearizer(private val literals: IntArray) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = 1L)
    }
}
