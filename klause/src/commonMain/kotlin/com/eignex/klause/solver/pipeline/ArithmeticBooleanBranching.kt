package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision

internal class ArithmeticBooleanBranching(
    private val preferred: IntArray,
    numBooleans: Int,
) : BooleanBranching {
    private val fallback = BooleanBranching.SourceOrder(numBooleans)

    override fun alternatives(context: SearchContext): List<SearchDecision>? {
        val variable = preferred.firstOrNull { context.boolValue(it) == null }
            ?: return fallback.alternatives(context)
        return listOf(SearchDecision.Bool(Lit.make(variable, false)), SearchDecision.Bool(Lit.make(variable, true)))
    }
}
