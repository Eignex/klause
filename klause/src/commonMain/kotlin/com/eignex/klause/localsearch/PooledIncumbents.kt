package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange

// The exchange's verifier and strict-improvement test decide what becomes an incumbent, never the engine's own best.
// Importing is off under assumption pins a foreign assignment may violate; publishing is not, since zero violation
// means every factor holds. Imports are re-scored by evaluate because the caller's objective view need not agree
// numerically with the publisher's. Single-threaded: it remembers the last version imported.
internal class PooledIncumbents(
    private val exchange: IncumbentExchange<Sample, Double>?,
    private val importEnabled: Boolean,
    private val evaluate: (Sample) -> Double,
    private val timing: LocalSearchPhaseTimer? = null,
) {
    private val incumbents = exchange?.subscribe()

    fun publish(sample: Sample, objective: Double) {
        val target = exchange ?: return
        if (!target.buffersWorkerOffers) {
            timing.inPhase("publication") { target.offer(sample, objective) }
            return
        }
        target.offerFromWorker(sample, objective) { action ->
            val timer = timing
            if (timer == null) action() else timer.measurePublication(action)
        }
    }

    // Reads the exchange at most once per call.
    fun poll(currentBest: Double): Adoption? {
        if (!importEnabled) return null
        val fresh = incumbents?.poll() ?: return null
        val objective = evaluate(fresh.assignment)
        return if (objective < currentBest) Adoption(fresh.assignment, objective) else null
    }

    data class Adoption(val sample: Sample, val objective: Double)
}
