package com.eignex.klause.backtrack

import com.eignex.klause.util.lubyN

/**
 * Pluggable restart *schedule* for one backtrack run: the per-run decision budget and the decision of
 * when to cut a run short and pop back to root. Decouples *when* to restart from the restart *action* —
 * popping the trail, replaying nogoods, forgetting, vivifying — which stays with the caller
 * ([DfsEngine]), so a new schedule can be dropped in without touching the engine.
 *
 * Selected from [BacktrackParams] by [from]; each driver owns its own instance (and thus its own
 * detector state), so the satisfaction path ([BacktrackSolver]) and the branch-and-bound engine
 * ([com.eignex.klause.backtrack.ResumableMinimize]) never share schedule state.
 */
internal interface RestartSchedule {
    /** Size the budget for a fresh run — call at the start of each run. */
    fun beginRun() {}

    /** Feed a conflict's LBD and trail depth to the schedule; may raise a pending restart. */
    fun recordConflict(lbd: Int, trailSize: Int) {}

    /** True when the current run should restart. [decisionsThisRun] is the caller's decision count
     *  since the last [beginRun]. */
    fun shouldRestart(decisionsThisRun: Long): Boolean

    /** Consume a restart once the caller has popped to root — advance the schedule state. */
    fun onRestart() {}

    companion object {
        /** The schedule selected by [params]: adaptive Glucose when [BacktrackParams.adaptiveRestart],
         *  else Luby when [BacktrackParams.lubyRestartBase] is set, else a single unbounded run. */
        fun from(params: BacktrackParams): RestartSchedule = when {
            params.adaptiveRestart -> AdaptiveRestartSchedule()
            params.lubyRestartBase != null -> LubyRestartSchedule(params.lubyRestartBase)
            else -> NoRestartSchedule
        }
    }
}

/** A single unbounded run: never restarts. The default when no schedule is configured. */
internal object NoRestartSchedule : RestartSchedule {
    override fun shouldRestart(decisionsThisRun: Long): Boolean = false
}

/**
 * The classic Luby (geometric-free) schedule: run `lubyN(idx) · base` decisions, then restart. The
 * per-run budget follows the Luby sequence `1, 1, 2, 1, 1, 2, 4, …` scaled by [base] — universally
 * optimal in expectation for Las Vegas search with an unknown runtime distribution.
 */
internal class LubyRestartSchedule(private val base: Long) : RestartSchedule {
    private var lubyIdx = 1L
    private var perRunBudget = Long.MAX_VALUE

    override fun beginRun() {
        // Cap multiplication to avoid overflow on tiny base + huge lubyIdx.
        val limit = lubyN(lubyIdx)
        perRunBudget = if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = decisionsThisRun >= perRunBudget

    override fun onRestart() {
        lubyIdx++
    }
}

/**
 * Glucose-style adaptive schedule: a [GlucoseRestart] LBD/trail-size detector drives restarts and the
 * per-run budget is disabled (unbounded), so restarts fire on learned-clause quality rather than a
 * fixed decision count.
 */
internal class AdaptiveRestartSchedule : RestartSchedule {
    private val glucose = GlucoseRestart()
    private var restartRequested = false

    override fun recordConflict(lbd: Int, trailSize: Int) {
        if (glucose.recordConflict(lbd, trailSize)) restartRequested = true
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = restartRequested

    override fun onRestart() {
        restartRequested = false
    }
}
