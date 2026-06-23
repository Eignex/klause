package com.eignex.klause.portfolio

/**
 * The cross-arm sharing channels handed to every backtrack arm of one portfolio: the learned-clause
 * pool ([clauses], always on for a non-LS portfolio), the global-cut pool ([cuts], gated by
 * [PortfolioScenario.shareCuts], on by default), the objective lower-bound manager ([bounds]) and the
 * globally-valid level-0 variable-bound manager ([varBounds]), both present for an optimising portfolio.
 * Each may be null when its sharing does not apply; an LS-only portfolio gets no [SharedPools] at all.
 */
internal class SharedPools(
    val clauses: SharedClausePool?,
    val cuts: SharedCutPool?,
    val bounds: SharedObjectiveBound? = null,
    val varBounds: SharedVarBounds? = null,
)
