package com.eignex.klause.lp.bounding

/**
 * Per-technique cut-separator activity gate (#59), the per-family analogue of the whole-simplex
 * [LpEffortLadder]. The ladder demotes the entire [LpEffort.CUTS] rung when during-search separation as a
 * whole stops paying; this gate instead attributes reward to **each** separator family independently, so a
 * single unhelpful family (an AllDifferent Hall cut, a knapsack cover, the implied-bound or flow-cover
 * separator, …) is disabled while the rest keep separating.
 *
 * The reward is count-based and deterministic, exactly like the ladder (no wall-clock): a separator is
 * *productive* in a round when it returns at least one violated cut — a cut a separator emits cuts off the
 * current fractional point by construction, so a family that produces nothing over a whole window is pure
 * per-node scan cost for this subtree. Such a family is disabled; a disabled family is re-probed on
 * exponential backoff and re-enabled the moment a probe round is productive again, recovering a subtree
 * where its structure starts to bind.
 *
 * Soundness: a separator only ever *adds* valid cuts, so skipping one loses potential tightening, never a
 * solution — the gate changes how much separation work runs, never the optimum. Per-family state is
 * indexed by the separator's position in the engine's separator list.
 *
 * @param count       number of separators governed (the engine's separator-list size).
 * @param warmup      rounds a family must run before it can be disabled — never disable during warmup.
 * @param window      size of the tumbling productivity window evaluated after the warmup.
 * @param reprobeBase rounds between the first re-probes of a disabled family; `Int.MAX_VALUE` makes a
 *                    disable irreversible (mirrors [LpEffortLadder]'s `autoOffReprobe` off).
 * @param reprobeMax  ceiling on the backoff interval — a disabled family still re-probes this often.
 */
internal class LpSeparatorGate(
    count: Int,
    private val warmup: Int = DEFAULT_WARMUP,
    private val window: Int = DEFAULT_WINDOW,
    private val reprobeBase: Int = LpEffortLadder.DEFAULT_REPROBE_BASE,
    private val reprobeMax: Int = DEFAULT_REPROBE_MAX,
) {
    companion object {
        const val DEFAULT_WARMUP: Int = 64
        const val DEFAULT_WINDOW: Int = 64
        const val DEFAULT_REPROBE_MAX: Int = 8192
    }

    private val enabled = BooleanArray(count) { true }
    private val totalRounds = IntArray(count)
    private val windowRounds = IntArray(count)
    private val windowProductive = IntArray(count)
    private val sinceProbe = IntArray(count)
    private val reprobeDelay = IntArray(count) { reprobeBase }
    private val probing = BooleanArray(count)

    /** Whether separator [i] runs this round — enabled, or the scheduled re-probe of a disabled one.
     *  Stateful: advances the re-probe clock of a disabled family, so call exactly once per round. */
    fun shouldRun(i: Int): Boolean {
        if (enabled[i]) {
            probing[i] = false
            return true
        }
        sinceProbe[i]++
        if (sinceProbe[i] >= reprobeDelay[i]) {
            probing[i] = true
            return true
        }
        return false
    }

    /** Record separator [i]'s round, with whether it produced at least one violated cut. Pairs with the
     *  [shouldRun] that authorized the round. */
    fun record(i: Int, productive: Boolean) {
        totalRounds[i]++
        if (probing[i]) {
            // A re-probe of a disabled family: a productive round earns it back; else back off (capped).
            probing[i] = false
            sinceProbe[i] = 0
            if (productive) {
                enabled[i] = true
                windowRounds[i] = 0
                windowProductive[i] = 0
                reprobeDelay[i] = reprobeBase
            } else {
                reprobeDelay[i] = minOf(reprobeDelay[i] * 2, reprobeMax)
            }
            return
        }
        windowRounds[i]++
        if (productive) windowProductive[i]++
        if (totalRounds[i] >= warmup && windowRounds[i] >= window) {
            // A family that produced no violated cut across the whole window is pure scan cost — disable it.
            if (windowProductive[i] == 0) {
                enabled[i] = false
                sinceProbe[i] = 0
                reprobeDelay[i] = reprobeBase
            }
            windowRounds[i] = 0
            windowProductive[i] = 0
        }
    }

    /** Whether separator [i] is currently enabled (excludes the transient re-probe round). */
    fun isEnabled(i: Int): Boolean = enabled[i]
}
