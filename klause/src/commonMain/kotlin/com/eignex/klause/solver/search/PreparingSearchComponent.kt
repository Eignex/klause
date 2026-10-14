package com.eignex.klause.solver.search

internal interface PreparingSearchComponent : SearchComponent {
    fun prepareInitialization(context: SearchContext): Iterator<ComponentResult?>
}
