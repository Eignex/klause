package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random

/**
 * Reusable candidate-set filter that removes moves whose variable was touched within the
 * last [tenure] accepted moves. Centralises the inline `if (tabuTenure > 0) ...` block
 * that every LS strategy used to carry, with two extensions over the original inline form:
 *
 *   - [aspiration] decides whether a tabu move can still be admitted. The default
 *     ("when *every* candidate is tabu, drop the filter") matches the historical behaviour.
 *     [AspirationCriterion.OrImproving] additionally admits individual tabu moves that
 *     strictly improve the current cost — the standard literature aspiration.
 *
 *   - [dynamicTenure] lets the effective tenure scale with the search state. Default is
 *     constant; pass a function of `state.step` to vary the tenure over time (e.g. randomly
 *     within a band, or growing during stalls).
 *
 * Strategies that want no tabu can pass [Disabled]. The filter is allocation-light: on the
 * common path it returns the input list unchanged when no moves are tabu, allocating only
 * when the filtered subset differs.
 */
data class TabuFilter(
    val tenure: Int = 10,
    val aspiration: AspirationCriterion = AspirationCriterion.AllowAllWhenAllTabu,
    val dynamicTenure: ((step: Long) -> Int)? = null,
) {
    fun filter(state: LocalSearchState, raw: List<Move>): List<Move> {
        val effective = dynamicTenure?.invoke(state.step) ?: tenure
        if (effective <= 0 || raw.isEmpty()) return raw

        // Pass 1: detect whether any move is tabu (skips allocation when nothing is).
        var anyTabu = false
        for (m in raw) {
            if (state.isTaboo(m, effective)) { anyTabu = true; break }
        }
        if (!anyTabu) return raw

        // Pass 2: collect the admitted set under the configured aspiration rule.
        val admitted = ArrayList<Move>(raw.size)
        for (m in raw) {
            val tabu = state.isTaboo(m, effective)
            if (!tabu || aspiration.admitsTabu(state, m)) admitted.add(m)
        }
        return when {
            admitted.isNotEmpty() -> admitted
            // Final fallback: every candidate was tabu and none met the aspiration; drop
            // the filter entirely rather than starve the strategy.
            else -> raw
        }
    }

    companion object {
        /** No tabu filtering at all — every raw candidate passes through. */
        val Disabled: TabuFilter = TabuFilter(tenure = 0)

        /**
         * Dynamic-tenure preset: pick a fresh tenure uniformly at random in `[low, high]`
         * on every call. Adds diversification without making the average tenure drift —
         * the [Glover-Laguna 1997] "robust tabu" pattern, useful when a single fixed
         * tenure either traps the search (too short) or starves it (too long).
         *
         * The randomness comes from a private [Random] seeded by [seed]; pass a stable
         * seed for reproducible runs, leave null for `Random.Default`.
         */
        fun randomBand(low: Int, high: Int, seed: Long? = null): (Long) -> Int {
            require(low >= 0 && high >= low) { "expected 0 ≤ low ≤ high, got $low..$high" }
            val rng = if (seed != null) Random(seed) else Random.Default
            return { rng.nextInt(low, high + 1) }
        }

        /**
         * Dynamic-tenure preset: grow tenure linearly with `state.step` from [base] up to
         * [max], saturating after [maxAtStep] flips. Slow start (short tabu, encourages
         * local progress) then plateau (longer tabu, forces diversification). Inverse of
         * the literature "decaying tabu" — useful when the search benefits from increasing
         * diversification as it stalls.
         */
        fun linearGrowth(base: Int, max: Int, maxAtStep: Long): (Long) -> Int {
            require(base >= 0 && max >= base) { "expected 0 ≤ base ≤ max, got base=$base max=$max" }
            require(maxAtStep > 0) { "maxAtStep must be positive, got $maxAtStep" }
            return { step ->
                if (step >= maxAtStep) max
                else base + ((max - base) * step / maxAtStep).toInt()
            }
        }
    }
}

/**
 * Per-move criterion that admits a tabu move despite the tenure check. The standard
 * literature aspiration is "tabu move M is permitted if M leads to a state strictly better
 * than the best known so far"; we approximate that with [OrImproving], which admits a
 * tabu move iff its [LocalSearchState.netDelta] is strictly negative (i.e. it would lower
 * the current violation count).
 */
sealed interface AspirationCriterion {
    fun admitsTabu(state: LocalSearchState, move: Move): Boolean

    /** No per-move admission — only the [TabuFilter] "everything tabu" fallback applies. */
    data object AllowAllWhenAllTabu : AspirationCriterion {
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean = false
    }

    /** Admit a tabu move if it strictly improves the current cost (`netDelta < 0`). */
    data object OrImproving : AspirationCriterion {
        override fun admitsTabu(state: LocalSearchState, move: Move): Boolean =
            state.netDelta(move) < 0
    }
}
