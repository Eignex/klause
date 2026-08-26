package com.eignex.klause.solver.search

import com.eignex.klause.solver.Cancellation

/**
 * The components selected for one solver engine from its source model.
 *
 * Construction is outside the decision loop. A set deliberately contains no finite-domain state itself:
 * CP contributes that state through [com.eignex.klause.propagation.CpSearchComponent], while theories
 * contribute only their own incremental state.
 */
class SearchComponentSet(
    components: List<SearchComponent>,
    private val branchers: List<SearchBrancher> = emptyList(),
) {
    private val components = components.toList()

    init {
        require(components.distinctBy { it }.size == components.size) { "a search component may occur once" }
        require(branchers.none { it in components }) { "a brancher may not also be a search component" }
    }

    /** Create the one shared session which drives this selected component set. */
    fun session(
        maxChecks: Long = Long.MAX_VALUE,
        cancellation: Cancellation = Cancellation.Never,
        learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
    ): SearchSession = SearchSession(components, maxChecks, cancellation, learnedDb, branchers)
}
