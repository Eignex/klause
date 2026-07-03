package com.eignex.klause.backtrack

/**
 * Restart *policy* for one backtrack run: the per-run decision budget and the decision of when to
 * cut a run short and pop back to root. Two schedules, selected by [BacktrackParams]:
 *
 *  - **Adaptive** ([BacktrackParams.adaptiveRestart]): a [GlucoseRestart] LBD/trail-size detector
 *    drives restarts; the Luby budget is disabled (unbounded per run).
 *  - **Luby** ([BacktrackParams.lubyRestartBase]): the run budget is `lubyN(idx) · base` decisions,
 *    the classic geometric-free schedule. With no base set, runs are unbounded (single run).
 *
 * Holds only the schedule state (the detector, the pending-restart flag, the Luby index). The restart
 * *action* — popping the trail, replaying nogoods, forgetting, vivifying — stays with the caller,
 * which calls [onRestart] once it has done that work. Shared by the satisfaction path
 * ([BacktrackSolver] `driveSearch`) and the branch-and-bound engine ([ResumableMinimize]); each
 * driver owns its own instance (and thus its own detector).
 */
internal class RestartController(private val params: BacktrackParams) {
    private val glucose: GlucoseRestart? = if (params.adaptiveRestart) GlucoseRestart() else null
    private var restartRequested = false
    private var lubyIdx = 1L
    private var perRunBudget = Long.MAX_VALUE

    /** Size the budget for a fresh run — call at the start of each run. Unbounded under adaptive
     *  restart; otherwise the Luby schedule scaled by [BacktrackParams.lubyRestartBase]. */
    fun beginRun() {
        perRunBudget = if (glucose != null) {
            Long.MAX_VALUE
        } else {
            params.lubyRestartBase?.let { base ->
                // Cap multiplication to avoid overflow on tiny base + huge lubyIdx.
                val limit = lubyN(lubyIdx)
                if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
            } ?: Long.MAX_VALUE
        }
    }

    /** Feed a conflict's LBD and trail depth to the adaptive detector; may raise a pending restart. */
    fun recordConflict(lbd: Int, trailSize: Int) {
        if (glucose != null && glucose.recordConflict(lbd, trailSize)) restartRequested = true
    }

    /** True when the current run should restart: its Luby budget is spent, or the adaptive detector
     *  asked. [decisionsThisRun] is the caller's decision count since the last [beginRun]. */
    fun shouldRestart(decisionsThisRun: Long): Boolean = decisionsThisRun >= perRunBudget || restartRequested

    /** Consume a restart once the caller has popped to root: clear the pending flag, advance the Luby
     *  index, and return the completed run's 1-based index (for the restart telemetry event). */
    fun onRestart(): Long {
        restartRequested = false
        val completed = lubyIdx
        lubyIdx++
        return completed
    }

    /**
     * Luby sequence (Luby-Sinclair-Zuckerman 1993): `1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, …`.
     * `lubyN(i) = 2^(k-1)` when `i = 2^k − 1`; otherwise `lubyN(i − 2^(k-1) + 1)` where
     * `k = ⌊log₂(i)⌋ + 1`. Iteratively unwound.
     */
    private fun lubyN(idxIn: Long): Long {
        var i = idxIn
        var k = 1
        // Find smallest k such that 2^k > i.
        while ((1L shl k) <= i) k++
        // Equivalent to the textbook recurrence; iteratively unwound.
        while (true) {
            val pow = 1L shl (k - 1)
            if (i == (pow shl 1) - 1) return pow
            // Otherwise i < (pow << 1) - 1; recurse on (i - pow + 1).
            i = i - pow + 1
            k = 1
            while ((1L shl k) <= i) k++
        }
    }
}
