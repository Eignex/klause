package com.eignex.klause.backtrack

/**
 * LBD-adaptive restart policy for the pure-Boolean search path (Audemard and Simon, "Predicting Learnt
 * Clauses Quality in Modern SAT Solvers", IJCAI 2009, https://www.ijcai.org/Proceedings/09/Papers/074.pdf;
 * trail blocking from "Refining Restarts Strategies for SAT and UNSAT", CP 2012,
 * https://doi.org/10.1007/978-3-642-33558-7_11). It restarts when the solver is *learning poorly*: a
 * short window of recent learned-clause LBD running hotter than the long-run global LBD average means
 * the recent clauses are low-quality and the search should re-pick. The complementary trail-size
 * blocking suppresses a restart when the trail is much larger than its recent average — the solver is
 * assigning deep toward a model, so interrupting it would throw away progress.
 *
 * LBD is computed per learned clause in [com.eignex.klause.propagation.ConflictAnalyzer]; the trail
 * depth is read off the engine.
 *
 *  - [lbdWindow] — capacity of the recent-LBD window. The restart check is gated on this
 *    window being full, so tiny instances never restart.
 *  - [trailWindow] — capacity of the recent-trail-size window for the blocking check.
 *  - [restartMargin] `K` — restart when `recentLbdAvg * K > globalLbdAvg` (default 0.8,
 *    i.e. recent LBD ≈25% above the long-run average).
 *  - [blockingFactor] `R` — block the restart when `trailSize > R * recentTrailAvg`
 *    (default 1.4).
 *
 * The long-run LBD sum and conflict count accumulate for the whole search; the recent windows
 * are circular buffers that evict their oldest entry when full, and are fast-cleared whenever a
 * restart fires or a block triggers (so a blocked restart genuinely defers, not just skips).
 */
internal class GlucoseRestart(
    private val lbdWindow: Int = 50,
    private val trailWindow: Int = 5000,
    private val restartMargin: Double = 0.8,
    private val blockingFactor: Double = 1.4,
) {
    init {
        require(lbdWindow > 0) { "lbdWindow must be positive, got $lbdWindow" }
        require(trailWindow > 0) { "trailWindow must be positive, got $trailWindow" }
        require(restartMargin > 0.0) { "restartMargin must be positive, got $restartMargin" }
        require(blockingFactor > 0.0) { "blockingFactor must be positive, got $blockingFactor" }
    }

    private val lbdBuf = IntArray(lbdWindow)
    private var lbdCount = 0
    private var lbdHead = 0
    private var lbdSum = 0L

    private val trailBuf = IntArray(trailWindow)
    private var trailCount = 0
    private var trailHead = 0
    private var trailSum = 0L

    private var globalLbdSum = 0L
    private var conflicts = 0L

    /**
     * Record one learned conflict and decide whether to restart. [lbd] is the learned
     * clause's literal-block distance; [trailSize] is the search depth at the conflict.
     * Returns true iff the engine should pop to root and restart.
     */
    fun recordConflict(lbd: Int, trailSize: Int): Boolean {
        conflicts++
        globalLbdSum += lbd
        pushLbd(lbd)
        pushTrail(trailSize)

        // A trail far above its recent average means the solver is driving deep toward a model:
        // defer the restart and reset the LBD window so it must refill before it can trigger again.
        if (trailCount == trailWindow && trailSize.toDouble() > blockingFactor * (trailSum.toDouble() / trailCount)) {
            clearTrail()
            clearLbd()
            return false
        }

        if (lbdCount == lbdWindow) {
            val recentAvg = lbdSum.toDouble() / lbdWindow
            val globalAvg = globalLbdSum.toDouble() / conflicts
            if (recentAvg * restartMargin > globalAvg) {
                clearLbd()
                return true
            }
        }
        return false
    }

    private fun pushLbd(v: Int) {
        if (lbdCount < lbdWindow) {
            lbdBuf[(lbdHead + lbdCount) % lbdWindow] = v
            lbdSum += v
            lbdCount++
        } else {
            lbdSum += v - lbdBuf[lbdHead]
            lbdBuf[lbdHead] = v
            lbdHead = (lbdHead + 1) % lbdWindow
        }
    }

    private fun pushTrail(v: Int) {
        if (trailCount < trailWindow) {
            trailBuf[(trailHead + trailCount) % trailWindow] = v
            trailSum += v
            trailCount++
        } else {
            trailSum += v - trailBuf[trailHead]
            trailBuf[trailHead] = v
            trailHead = (trailHead + 1) % trailWindow
        }
    }

    private fun clearLbd() {
        lbdCount = 0
        lbdHead = 0
        lbdSum = 0L
    }

    private fun clearTrail() {
        trailCount = 0
        trailHead = 0
        trailSum = 0L
    }
}
