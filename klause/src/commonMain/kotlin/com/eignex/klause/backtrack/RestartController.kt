package com.eignex.klause.backtrack

import com.eignex.klause.util.lubyN

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
}
