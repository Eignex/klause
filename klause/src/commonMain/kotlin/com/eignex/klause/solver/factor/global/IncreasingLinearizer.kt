package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.RelaxationBuilder
import com.eignex.klause.solver.factor.arithmetic.LinearOp

/**
 * LP relaxation of [Increasing]: one exact feasibility-defining row per adjacent pair,
 * `xs(i+1) − xs(i) ≥ gap`. The chain's polytope is the conjunction of these rows (a totally
 * unimodular interval system), so no hull cut strengthens it.
 */
internal class IncreasingLinearizer(private val xs: IntArray, private val gap: Int) : Linearizer {
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        for (i in 0 until xs.size - 1) {
            builder.linearRow(LinearOp.GE, intArrayOf(xs[i + 1], xs[i]), intArrayOf(1, -1), gap.toLong())
        }
    }
}
