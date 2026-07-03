package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.lp.RelaxationBuilder

/** LP relaxation of a [Cardinality]: the feasibility-defining bounds `min ≤ Σ literals ≤ max`. */
internal class CardinalityLinearizer(private val literals: IntArray, private val min: Int, private val max: Int) :
    Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights = null, op = LinearOp.GE, bound = min.toLong())
        builder.boolRow(literals, weights = null, op = LinearOp.LE, bound = max.toLong())
    }
}
