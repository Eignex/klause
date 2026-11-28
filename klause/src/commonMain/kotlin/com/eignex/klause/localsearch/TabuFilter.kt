package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.schedule.Schedule
import kotlin.math.exp
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * Drops candidates whose variable moved within the last [tenure] accepted moves (F. Glover, "Tabu Search—Part I",
 * ORSA Journal on Computing 1, 1989, https://doi.org/10.1287/ijoc.1.3.190). [aspiration] can still admit a tabu
 * move, and [dynamicTenure] maps `state.step` to the effective tenure. When every candidate is tabu and none
 * aspires, the filter passes all of them so the strategy never starves.
 */
data class TabuFilter(
    val tenure: Int = 10,
    val aspiration: AspirationCriterion = AspirationCriterion.AllowAllWhenAllTabu,
    val dynamicTenure: ((step: Long) -> Int)? = null,
) {
    /** Filter [raw], dropping tabu moves (subject to aspiration). */
    fun filter(state: LocalSearchState, raw: List<Move>): List<Move> = filter(state, raw, null)

    // completedImprovement replaces the OrImproving test with a completed-move score.
    internal fun filter(
        state: LocalSearchState,
        raw: List<Move>,
        completedImprovement: ((Move) -> Boolean)?,
    ): List<Move> {
        val effective = dynamicTenure?.invoke(state.step) ?: tenure
        if (effective <= 0 || raw.isEmpty()) return raw

        // Skips the allocation when nothing is tabu.
        var anyTabu = false
        for (m in raw) {
            if (state.tabu.isTaboo(m, effective)) {
                anyTabu = true
                break
            }
        }
        if (!anyTabu) return raw

        val admitted = ArrayList<Move>(raw.size)
        for (m in raw) {
            val tabu = state.tabu.isTaboo(m, effective)
            val aspired = if (tabu && completedImprovement != null && aspiration == AspirationCriterion.OrImproving) {
                completedImprovement(m)
            } else tabu && aspiration.admitsTabu(state, m)
            if (!tabu || aspired) admitted.add(m)
        }
        return when {
            admitted.isNotEmpty() -> admitted

            else -> raw
        }
    }

    /** Shared [TabuFilter] helpers. */
    companion object {
        /** No tabu filtering at all — every raw candidate passes through. */
        val Disabled: TabuFilter = TabuFilter(tenure = 0)

        /**
         * Dynamic tenure drawn uniformly from `[low, high]` on every call, diversifying without drifting the
         * average (É. D. Taillard, "Robust taboo search for the quadratic assignment problem", Parallel Computing 17,
         * 1991, https://doi.org/10.1016/S0167-8191(05)80147-4). A null [seed] uses `Random.Default`.
         */
        fun randomBand(low: Int, high: Int, seed: Long? = null): (Long) -> Int {
            require(low >= 0 && high >= low) { "expected 0 ≤ low ≤ high, got $low..$high" }
            val rng = if (seed != null) Random(seed) else Random.Default
            return { rng.nextInt(low..high) }
        }

        /** Dynamic tenure growing linearly from [base] to [max] over the first [maxAtStep] steps, increasing
         *  diversification as the search goes on. */
        fun linearGrowth(base: Int, max: Int, maxAtStep: Long): (Long) -> Int {
            require(base >= 0 && max >= base) { "expected 0 ≤ base ≤ max, got base=$base max=$max" }
            require(maxAtStep > 0) { "maxAtStep must be positive, got $maxAtStep" }
            return { step ->
                if (step >= maxAtStep) {
                    max
                } else {
                    base + ((max - base) * step / maxAtStep).toInt()
                }
            }
        }
    }
}

/** Per-move criterion that admits a tabu move despite the tenure check. */
sealed interface AspirationCriterion {
    /** True iff the tabu [move] should be admitted anyway. */
    fun admitsTabu(state: LocalSearchState, move: Move): Boolean

    /** No per-move admission — only the [TabuFilter] "everything tabu" fallback applies. */
    data object AllowAllWhenAllTabu : AspirationCriterion {
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean = false
    }

    /** Admit a tabu move that lowers violations or improves a feasible objective without adding violations. */
    data object OrImproving : AspirationCriterion {
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean {
            val delta = state.netDelta(move)
            if (delta < 0L) return true
            if (state.cost != 0L || delta != 0L) return false
            val objective = state.shaping.objective ?: return false
            return (state.objectiveDelta(objective, move) ?: 0.0) < 0.0
        }
    }

    /** Admits a tabu move whose resulting cost beats [LocalSearchState.bestCostSeen], the minimum across the whole
     *  search including earlier restarts. */
    data object OrImprovesBestEver : AspirationCriterion {
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean {
            // bestCostSeen starts at Long.MAX_VALUE, so any move is admitted until the watermark
            // has been seeded by at least one apply.
            val predicted = state.cost + state.netDelta(move)
            return predicted < state.bestCostSeen
        }
    }

    /** Admits a tabu move with probability [rate] in `[0, 1]`. */
    data class Probabilistic(val rate: Double = 0.1) : AspirationCriterion {
        init {
            require(rate in 0.0..1.0) { "rate must be in [0, 1], got $rate" }
        }
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean = state.rng.nextDouble() < rate
    }

    /**
     * Admits a tabu move with probability `exp(-1 / T)`, where [schedule] cools T one step per call, so admission
     * goes from liberal to rare. Holds mutable temperature state, so each solver needs its own instance.
     */
    class Cooling(private val schedule: Schedule) : AspirationCriterion {
        /** Fixed geometric cooling — the default schedule. */
        constructor(
            initialTemperature: Double = 1.0,
            coolingRate: Double = 0.999,
            minTemperature: Double = 1e-3,
        ) : this(Geometric(initialTemperature, coolingRate, minTemperature))

        /** Current temperature (cools on each [admitsTabu] call). */
        val temperature: Double get() = schedule.temperature

        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean {
            val admit = state.rng.nextDouble() < exp(-1.0 / schedule.temperature)
            schedule.step()
            return admit
        }

        /** Restore the schedule to its initial temperature. */
        fun reset() = schedule.reset()
    }
}
