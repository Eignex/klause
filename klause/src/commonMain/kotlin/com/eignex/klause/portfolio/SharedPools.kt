package com.eignex.klause.portfolio

/**
 * The cross-arm sharing channels handed to every backtrack arm of one portfolio: the learned-clause
 * pool ([clauses], always on for a non-LS portfolio), the global-cut pool ([cuts], gated by
 * [PortfolioScenario.shareCuts], on by default), and the objective lower-bound manager ([bounds],
 * present for an optimising portfolio — #809 / F1). Each may be null when its sharing does not apply; an
 * LS-only portfolio gets no [SharedPools] at all.
 */
internal class SharedPools(
    val clauses: SharedClausePool?,
    val cuts: SharedCutPool?,
    val bounds: SharedObjectiveBound? = null,
)
