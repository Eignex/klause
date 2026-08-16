package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample

/**
 * Cross-engine solution flow: pulls the best assignment any arm has published and hands it back
 * only when it is *fresh* (not already imported) and strictly better than the caller's current best.
 * Identity-gated so an unchanged pool costs nothing; inert without a [supplier] or when [enabled] is
 * false — the run carries assumption pins a foreign full assignment could violate. The caller adopts the
 * returned sample however it sees fit: a restart anchor for [LocalSearchSolver], the incumbent for
 * [com.eignex.klause.meta.alns.Alns]. Sharing this one implementation keeps the two outer loops from each
 * carrying their own copy of the identity-gate + pin-guard + adopt-if-better logic.
 *
 * Single-threaded and stateful (it remembers the last import); drive it from one loop.
 */
internal class PooledSolutionImporter(
    private val supplier: (() -> Sample?)?,
    private val enabled: Boolean,
    private val evaluate: (Sample) -> Double,
) {
    private var lastImported: Sample? = null

    /**
     * The pooled assignment worth adopting given the caller's [currentBest] objective, or null when the
     * pool offers nothing new and strictly better. Consults [supplier] at most once per call, so a caller
     * polling once per restart/iteration triggers exactly one supplier hit per poll.
     */
    fun poll(currentBest: Double): Adoption? {
        if (!enabled) return null
        val pooled = supplier?.invoke() ?: return null
        if (pooled === lastImported) return null
        lastImported = pooled
        val objective = evaluate(pooled)
        return if (objective < currentBest) Adoption(pooled, objective) else null
    }

    /** A pooled assignment [sample] with its [objective], strictly better than the caller's current best. */
    data class Adoption(val sample: Sample, val objective: Double)
}
