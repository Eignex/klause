package com.eignex.klause.lp.bounding

import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchExplanation

internal interface RowPropagation {
    fun propagate(lp: LpPropagator, context: SearchContext): RowPropagationResult
}

internal sealed interface RowPropagationResult {
    data object Skipped : RowPropagationResult
    data object Published : RowPropagationResult
    data object Indeterminate : RowPropagationResult
    data class Conflict(val explanation: SearchExplanation) : RowPropagationResult
}
