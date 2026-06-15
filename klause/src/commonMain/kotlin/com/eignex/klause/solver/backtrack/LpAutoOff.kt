package com.eignex.klause.solver.backtrack

/**
 * Adaptive per-node LP auto-off controller (#614), superseding the static one-shot guard (#562).
 *
 * The per-node LP relaxation is pure overhead on nodes where its bound never prunes (a loose or
 * too-expensive relaxation), so it should be switched off there — but the old guard decided this
 * **once** ("zero prunes in the first [warmup] passes ⇒ off for the whole solve") and could never
 * recover. That had two failure modes this controller fixes:
 *
 *  1. **Irreversible.** A subtree where the relaxation becomes useful (e.g. once enough integer
 *     variables are fixed for it to tighten) was never revisited. Here, a disabled LP is **re-probed**
 *     on an exponential backoff, so such a subtree is recovered.
 *  2. **One lucky early prune pinned it on forever.** The decision rested on a single fixed window;
 *     a relaxation that pruned once early then went cold kept paying for the LP at every node. Here
 *     the prune rate is tracked over a **rolling (tumbling) window**, so a relaxation that stops
 *     pruning is shed at a later window, and one that keeps pruning stays on.
 *
 * Soundness: this only decides *whether the bound is computed*, never the search's correctness.
 * Dropping a bound loses pruning, not solutions — so any gating policy is safe, and `-t` is honoured
 * because the gate can only ever reduce work.
 *
 * The controller is deliberately count-based (no wall-clock), so its behaviour is deterministic and
 * unit-testable; relaxation cost surfaces through the prune rate — an expensive relaxation that
 * rarely prunes has a low windowed rate and is shed, while a cheap one that prunes often is kept.
 *
 * @param warmup       passes before the first disable decision — never disable during the warmup.
 * @param window       size of the tumbling prune-rate window evaluated after the warmup.
 * @param minWindowPrunes lowest prune count a window may have and stay enabled; below it the LP is
 *                        disabled. The default `1` keeps any relaxation that prunes even rarely (a
 *                        single prune can save a large subtree) and sheds only the never-pruning ones.
 * @param reprobeBase  eligible nodes between the first re-probes after a disable.
 * @param reprobeMax   ceiling on the backoff interval — a disabled LP is still re-probed this often.
 */
internal class LpAutoOff(
    private val warmup: Int = 64,
    private val window: Int = 64,
    private val minWindowPrunes: Int = 1,
    private val reprobeBase: Int = DEFAULT_REPROBE_BASE,
    private val reprobeMax: Int = 8192,
) {
    companion object {
        /** Default first backoff interval; `Int.MAX_VALUE` instead makes a disable irreversible (#562). */
        const val DEFAULT_REPROBE_BASE: Int = 64
    }

    private var active = true
    private var totalSolves = 0

    // Tumbling-window accounting while active.
    private var windowSolves = 0
    private var windowPrunes = 0

    // Re-probe schedule while disabled.
    private var sinceLastProbe = 0
    private var reprobeDelay = reprobeBase
    private var probing = false

    /** Whether the LP is currently switched off (a re-probe pass does not count as off). */
    val disabled: Boolean get() = !active

    /**
     * Call once per LP-eligible node (after the depth / cadence gates pass). Returns true when the LP
     * should run now — always while active, and on the scheduled re-probe while disabled. Stateful:
     * advances the re-probe clock, so call it exactly once per eligible node.
     */
    fun shouldRun(): Boolean {
        if (active) return true
        sinceLastProbe++
        if (sinceLastProbe >= reprobeDelay) {
            probing = true
            return true
        }
        return false
    }

    /** Call after an LP run started by [shouldRun], with whether it pruned. */
    fun record(pruned: Boolean) {
        totalSolves++
        if (probing) {
            // A re-probe of a disabled LP: a prune means the relaxation is useful again — reactivate
            // and re-arm the window; otherwise back off (doubling, capped) and stay disabled.
            probing = false
            sinceLastProbe = 0
            if (pruned) {
                active = true
                windowSolves = 0
                windowPrunes = 0
                reprobeDelay = reprobeBase
            } else {
                reprobeDelay = minOf(reprobeDelay * 2, reprobeMax)
            }
            return
        }
        windowSolves++
        if (pruned) windowPrunes++
        if (totalSolves >= warmup && windowSolves >= window) {
            if (windowPrunes < minWindowPrunes) {
                active = false
                sinceLastProbe = 0
                reprobeDelay = reprobeBase
            }
            windowSolves = 0
            windowPrunes = 0
        }
    }
}
