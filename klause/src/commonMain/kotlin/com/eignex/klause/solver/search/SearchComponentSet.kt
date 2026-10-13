package com.eignex.klause.solver.search

import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.lock

/**
 * The components selected for one solver engine from its source model.
 *
 * Construction is outside the decision loop. A set deliberately contains no finite-domain state itself:
 * CP contributes that state through [com.eignex.klause.propagation.CpSearchComponent], while theories
 * contribute only their own incremental state. A set permits one [session] construction attempt,
 * including an attempt that fails. Repeated openings require fresh mutable component instances.
 */
class SearchComponentSet(
    components: List<SearchComponent>,
    private val branchers: List<SearchBrancher> = emptyList(),
    proposals: List<SearchBranchProposal>,
) {
    /** Retain the installed-component and legacy brancher ordering. */
    constructor(components: List<SearchComponent>, branchers: List<SearchBrancher> = emptyList()) :
        this(components, branchers, emptyList())

    private val proposals = proposals.toList()
    private val components = components.toList()
    private val openingLock = Concurrency.Strict.lock()
    private var claimed = false

    init {
        require(components.distinctBy { it }.size == components.size) { "a search component may occur once" }
        require(proposals.distinctBy { it }.size == proposals.size) { "a search proposal may occur once" }
        require(proposals.none { proposal -> components.any { it == proposal } || branchers.any { it == proposal } }) {
            "a separate proposal may not also be an installed component or legacy brancher"
        }
        require(branchers.none { it in components }) { "a brancher may not also be a search component" }
    }

    /** Claim this set and create its session with cancellation-only controls. */
    fun session(
        maxChecks: Long = Long.MAX_VALUE,
        cancellation: Cancellation = Cancellation.Never,
        learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
        atoms: SearchAtomRegistry? = null,
    ): SearchSession = session(maxChecks, cancellation, learnedDb, atoms, SearchExecutionControlAdapter(cancellation))

    /**
     * Claim this set and create its one shared session.
     *
     * Reuse throws before attaching [atoms] or initializing components. A failed construction consumes
     * the claim, because its components may retain partially installed state.
     */
    fun session(
        maxChecks: Long = Long.MAX_VALUE,
        cancellation: Cancellation = Cancellation.Never,
        learnedDb: SearchLearnedDbParams = SearchLearnedDbParams(),
        atoms: SearchAtomRegistry? = null,
        executionControls: SearchExecutionControls,
    ): SearchSession {
        openingLock.withLock {
            check(!claimed) { "a mutable component set permits one session construction attempt" }
            claimed = true
        }
        return SearchSession(
            components,
            maxChecks,
            cancellation,
            learnedDb,
            branchers,
            atoms,
            executionControls,
            proposals,
        )
    }
}
