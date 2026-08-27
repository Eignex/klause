package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.CountStat
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.MaxStat
import com.eignex.kumulant.stat.summary.MeanStat
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult

/**
 * Core tree-search counters — the CDCL / DFS backbone shared by the complete backends: node and
 * failure counts, restarts, propagations, learned clauses, and the depth distribution. Zero for a
 * pure local-search solve. See [SolveStats].
 */
data class SearchStats(
    /** Decision nodes visited. */
    val nodes: SumResult = ZERO_COUNT,
    /** Failed nodes: propagation conflicts plus bound-pruned subtrees. */
    val fails: SumResult = ZERO_COUNT,
    /** Restarts performed (shared field — the LS engine folds its own restart count in here). */
    val restarts: SumResult = ZERO_COUNT,
    /** Propagation events. */
    val propagations: SumResult = ZERO_COUNT,
    /** Clauses learned by conflict analysis. */
    val learnedClauses: SumResult = ZERO_COUNT,
    /** Clauses conflict analysis re-derived identically — a livelock indicator when large. */
    val relearned: SumResult = ZERO_COUNT,
    /** Deepest decision level reached. */
    val peakDepth: MaxResult = NO_MAX,
    /** Mean decision depth over visited nodes: deep-and-thin vs shallow-and-wide. */
    val depthMean: WeightedMeanResult = WeightedMeanResult(totalWeights = 0.0, mean = Double.NaN),
) {
    /** Combine two workers' search stats: counters add, peak depth maxes, depth means weight-combine. */
    fun mergedWith(o: SearchStats): SearchStats = SearchStats(
        nodes = SumResult(nodes.sum + o.nodes.sum),
        fails = SumResult(fails.sum + o.fails.sum),
        restarts = SumResult(restarts.sum + o.restarts.sum),
        propagations = SumResult(propagations.sum + o.propagations.sum),
        learnedClauses = SumResult(learnedClauses.sum + o.learnedClauses.sum),
        relearned = SumResult(relearned.sum + o.relearned.sum),
        peakDepth = MaxResult(maxOf(peakDepth.max, o.peakDepth.max)),
        depthMean = mergeDepthMean(depthMean, o.depthMean),
    )
}

/** Mutable [SearchStats] accumulator; snapshots into a [SearchStats]. See [SolveStatsSink]. */
internal class SearchStatsSink {
    val nodes: CountStat = CountStat()
    val fails: CountStat = CountStat()
    val restarts: CountStat = CountStat()
    val propagations: CountStat = CountStat()
    val learnedClauses: CountStat = CountStat()
    val relearned: CountStat = CountStat()
    val peakDepth: MaxStat = MaxStat()
    val depthMean: MeanStat = MeanStat()

    /**
     * Nodes visited so far, as a plain counter.
     *
     * [nodes] holds the same total but reading it allocates a result, and this is read on the search's
     * pause check — every node — by a scheduler slicing on node count rather than on a clock.
     */
    var nodeCount: Long = 0L
        private set

    /** Call on every visited decision node so [nodes] increments and the depth stats see the observation. */
    fun observeNode(depth: Int) {
        nodeCount++
        nodes.update(1.0)
        peakDepth.update(depth.toDouble())
        depthMean.update(depth.toDouble())
    }
    fun observeFail() = fails.update(1.0)
    fun observeRestart() = restarts.update(1.0)
    fun observePropagation(count: Long = 1L) = repeat(count.toInt()) { propagations.update(1.0) }
    fun observeLearn(count: Long = 1L) = repeat(count.toInt()) { learnedClauses.update(1.0) }
    fun observeRelearn() = relearned.update(1.0)

    fun snapshot(): SearchStats = SearchStats(
        nodes = nodes.read(),
        fails = fails.read(),
        restarts = restarts.read(),
        propagations = propagations.read(),
        learnedClauses = learnedClauses.read(),
        relearned = relearned.read(),
        peakDepth = peakDepth.read(),
        depthMean = depthMean.read(),
    )
}
