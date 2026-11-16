package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.schedule.Schedule
import kotlin.math.exp
import kotlin.random.Random
import kotlin.random.nextInt

/**
 * Reusable tabu-search candidate-set filter that removes moves whose variable was touched within the
 * last [tenure] accepted moves (F. Glover, "Tabu Search—Part I", ORSA Journal on Computing 1, 1989,
 * https://doi.org/10.1287/ijoc.1.3.190), with two extensions:
 *
 *   - [aspiration] decides whether a tabu move can still be admitted. The default ("when *every*
 *     candidate is tabu, drop the filter") preserves liveness when the tabu would otherwise starve
 *     the strategy. [AspirationCriterion.OrImproving] additionally admits individual tabu moves that
 *     reduce violations or improve the objective while preserving feasibility.
 *
 *   - [dynamicTenure] lets the effective tenure scale with the search state. Default is constant;
 *     pass a function of `state.step` to vary the tenure over time.
 *
 * Strategies that want no tabu can pass [Disabled]. The filter is allocation-light: on the common
 * path it returns the input list unchanged when no moves are tabu.
 */
data class TabuFilter(
    val tenure: Int = 10,
    val aspiration: AspirationCriterion = AspirationCriterion.AllowAllWhenAllTabu,
    val dynamicTenure: ((step: Long) -> Int)? = null,
) {
    /** Filter [raw], dropping tabu moves (subject to aspiration). */
    fun filter(state: LocalSearchState, raw: List<Move>): List<Move> = filter(state, raw, null)

    /** [completedImprovement] replaces the [AspirationCriterion.OrImproving] test with a completed-move score. */
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

            // Every candidate was tabu and none met the aspiration: drop the filter rather than
            // starve the strategy.
            else -> raw
        }
    }

    /** Shared [TabuFilter] helpers. */
    companion object {
        /** No tabu filtering at all — every raw candidate passes through. */
        val Disabled: TabuFilter = TabuFilter(tenure = 0)

        /**
         * Dynamic-tenure preset: pick a fresh tenure uniformly at random in `[low, high]`
         * on every call. Adds diversification without making the average tenure drift — the
         * robust tabu pattern of É. D. Taillard, "Robust taboo search for the quadratic assignment
         * problem", Parallel Computing 17, 1991, https://doi.org/10.1016/S0167-8191(05)80147-4 —
         * useful when a single fixed tenure either traps the search (too short) or starves it (too long).
         *
         * The randomness comes from a private [Random] seeded by [seed]; pass a stable
         * seed for reproducible runs, leave null for `Random.Default`.
         */
        fun randomBand(low: Int, high: Int, seed: Long? = null): (Long) -> Int {
            require(low >= 0 && high >= low) { "expected 0 ≤ low ≤ high, got $low..$high" }
            val rng = if (seed != null) Random(seed) else Random.Default
            return { rng.nextInt(low..high) }
        }

        /**
         * Dynamic-tenure preset: grow tenure linearly with `state.step` from [base] up to
         * [max], saturating after [maxAtStep] steps. Slow start (short tabu, encourages
         * local progress) then plateau (longer tabu, forces diversification). Inverse of
         * the literature "decaying tabu" — useful when the search benefits from increasing
         * diversification as it stalls.
         */
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

/**
 * Per-move criterion that admits a tabu move despite the tenure check. [OrImprovesBestEver] is the
 * standard aspiration (the move leads to a state strictly better than the best known so far);
 * [OrImproving] is the weaker test against the current state.
 */
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

    /** Standard literature aspiration: admit a tabu move if it would lead to a cost
     *  strictly less than [LocalSearchState.bestCostSeen] — the historical minimum
     *  across the whole search session (preserved across restarts). Stronger than
     *  [OrImproving]: the move must reach a global minimum the search has never been
     *  at, not merely improve over the current epoch's plateau. */
    data object OrImprovesBestEver : AspirationCriterion {
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean {
            // bestCostSeen starts at Long.MAX_VALUE, so any move is admitted until the watermark
            // has been seeded by at least one apply.
            val predicted = state.cost + state.netDelta(move)
            return predicted < state.bestCostSeen
        }
    }

    /** Random leak: admit a tabu move with constant probability [rate]. Pairs well with
     *  longer [TabuFilter.tenure] values to get a controllable diversification rate
     *  without ever fully resetting the tabu window. [rate] ∈ [0, 1]; 0 disables the
     *  leak (behaves like [AllowAllWhenAllTabu]), 1 disables tabu entirely. Typical
     *  useful range is 0.05–0.2. */
    data class Probabilistic(val rate: Double = 0.1) : AspirationCriterion {
        init {
            require(rate in 0.0..1.0) { "rate must be in [0, 1], got $rate" }
        }
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean = state.rng.nextDouble() < rate
    }

    /**
     * Simulated-annealing-style cooling admission. Admit a tabu move with probability
     * `exp(-1 / temperature)`; the [schedule] advances one step on every admitsTabu call. Early in
     * the search T is high and tabu moves are admitted liberally (effectively no tabu); late in the
     * search T is low and admission becomes vanishingly rare (behaves like [AllowAllWhenAllTabu]).
     *
     * Carries the schedule's mutable temperature state; don't share a single instance across
     * concurrent solvers.
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
