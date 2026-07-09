package com.eignex.klause.backtrack.selector

/**
 * Public factory for the probing-driven branch heuristics whose implementations stay `internal`:
 * dom/wdeg and activity-based variable orders plus impact-based value ordering. Callers outside the
 * [com.eignex.klause.backtrack.selector] package (the bench config space, the CLI) build these by
 * factory rather than by referencing the hidden classes, so the concrete selectors keep their minimal
 * surface. Each function returns a fresh instance at the same defaults [com.eignex.klause.portfolio
 * .BacktrackCatalog] uses, so a factory-built selector matches the catalog arm of the same name.
 */
object ProbingSelectors {
    /** A fresh dom/wdeg variable selector (domain size divided by accumulated constraint weight). */
    fun domWdeg(): VariableSelector = DomWdeg()

    /** A fresh activity-based-search variable selector (singleton-forcing activity with decay). */
    fun activityBasedSearch(): VariableSelector = ActivityBasedSearch()

    /** A fresh impact-based value selector (orders values by probed search-space reduction). */
    fun impact(): ValueSelector = Impact()
}
