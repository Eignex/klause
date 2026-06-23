package com.eignex.klause.portfolio

/**
 * The cross-arm sharing channels handed to every backtrack arm of one portfolio: the learned-clause
 * pool ([clauses], always on for a non-LS portfolio) and the global-cut pool ([cuts], gated by
 * [PortfolioScenario.shareCuts], on by default). Either may be null when its sharing does not apply; an
 * LS-only portfolio gets no [SharedPools] at all.
 */
internal class SharedPools(val clauses: SharedClausePool?, val cuts: SharedCutPool?)
