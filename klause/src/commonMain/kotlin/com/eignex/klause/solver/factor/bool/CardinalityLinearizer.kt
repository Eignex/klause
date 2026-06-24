package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.arithmetic.LinearOp

/** LP relaxation of a [Cardinality]: the feasibility-defining bounds `min ≤ Σ literals ≤ max`. */
internal class CardinalityLinearizer(
    private val literals: IntArray,
    private val min: Int,
    private val max: Int,
) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = min.toLong())
        builder.boolRow(literals, weights = null, op = LinearOp.LE, bound = max.toLong())
    }
}
