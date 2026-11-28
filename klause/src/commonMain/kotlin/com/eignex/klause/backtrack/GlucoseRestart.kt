package com.eignex.klause.backtrack

// LBD-adaptive restarts (Audemard and Simon, "Predicting Learnt Clauses Quality in Modern SAT Solvers",
// IJCAI 2009, https://www.ijcai.org/Proceedings/09/Papers/074.pdf; trail blocking from "Refining Restarts
// Strategies for SAT and UNSAT", CP 2012, https://doi.org/10.1007/978-3-642-33558-7_11). Restart when the
// recent LBD window runs hotter than the global average (`recentLbdAvg · restartMargin > globalLbdAvg`), only
// once the window is full. Block when `trailSize > blockingFactor · recentTrailAvg`: the search is assigning
// deep toward a model. A restart clears the LBD window; a block clears both windows, so it defers rather than
// skips.
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
