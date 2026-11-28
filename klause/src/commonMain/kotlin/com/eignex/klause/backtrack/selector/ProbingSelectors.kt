package com.eignex.klause.backtrack.selector

/**
 * Factory for the probing-driven branch heuristics whose implementations are internal. Each returns a fresh
 * instance at the portfolio catalog's defaults.
 */
object ProbingSelectors {
    /** A fresh dom/wdeg variable selector (domain size divided by accumulated constraint weight). */
    fun domWdeg(): VariableSelector = DomWdeg()

    /** A fresh activity-based-search variable selector (singleton-forcing activity with decay). */
    fun activityBasedSearch(): VariableSelector = ActivityBasedSearch()

    /** A fresh impact-based value selector (orders values by probed search-space reduction). */
    fun impact(): ValueSelector = Impact()
}
