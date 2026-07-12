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
        /** Conflicts in the first mode segment of the default [ModeSwitchingRestartSchedule]; later
         *  segments scale it by the Luby sequence. */
        const val DEFAULT_MODE_BASE: Long = 500L

        /** The schedule selected by [params], in precedence order: mode-switching when
         *  [BacktrackParams.modeSwitchingRestart], else EMA-adaptive when [BacktrackParams.emaRestart],
         *  else Glucose-adaptive when [BacktrackParams.adaptiveRestart], else Luby when
         *  [BacktrackParams.lubyRestartBase] is set, else a single unbounded run. */
        fun from(params: BacktrackParams): RestartSchedule = when {
            params.modeSwitchingRestart -> ModeSwitchingRestartSchedule(
                focused = EmaRestartSchedule(),
                stable = NoRestartSchedule,
                modeBase = DEFAULT_MODE_BASE,
            )

            params.emaRestart -> EmaRestartSchedule()

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

/**
 * EMA-based adaptive schedule: an [EmaRestart] detector drives restarts off exponential moving averages
 * of learned-clause LBD (the CaDiCaL/Kissat scheme) rather than [GlucoseRestart]'s bounded windows. The
 * per-run budget is disabled (unbounded), as for [AdaptiveRestartSchedule].
 */
internal class EmaRestartSchedule : RestartSchedule {
    private val ema = EmaRestart()
    private var restartRequested = false

    override fun recordConflict(lbd: Int, trailSize: Int) {
        if (ema.recordConflict(lbd, trailSize)) restartRequested = true
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = restartRequested

    override fun onRestart() {
        restartRequested = false
    }
}

/**
 * Stable/focused mode-switching schedule (the CaDiCaL/Kissat regime), mixing two schedules within one
 * run to be robust on optimization without hand-tuning. The search alternates between a [focused] mode
 * — restart-heavy proving, e.g. an adaptive detector — and a [stable] dive mode that rarely (or never)
 * restarts so the solver can drive deep and hold onto a good assignment via phase saving. Each mode
 * segment lasts `lubyN(seg) · modeBase` conflicts, so dive phases lengthen as the search proceeds.
 * Starts focused.
 *
 * Delegation is total: [shouldRestart] / [onRestart] / [beginRun] all route to the active sub-schedule,
 * and [recordConflict] both feeds the active schedule and drives the mode clock. On a mode switch the
 * newly active schedule's [beginRun] is called so its budget is sized for the fresh segment.
 */
internal class ModeSwitchingRestartSchedule(
    private val focused: RestartSchedule,
    private val stable: RestartSchedule,
    private val modeBase: Long,
) : RestartSchedule {
    private var inStableMode = false
    private var segment = 1L
    private var conflictsThisSegment = 0L
    private var segmentBudget = segmentBudget()

    private fun active(): RestartSchedule = if (inStableMode) stable else focused

    private fun segmentBudget(): Long {
        val limit = lubyN(segment)
        return if (limit > Long.MAX_VALUE / modeBase) Long.MAX_VALUE else limit * modeBase
    }

    override fun beginRun() = active().beginRun()

    override fun recordConflict(lbd: Int, trailSize: Int) {
        active().recordConflict(lbd, trailSize)
        if (++conflictsThisSegment >= segmentBudget) {
            inStableMode = !inStableMode
            segment++
            conflictsThisSegment = 0
            segmentBudget = segmentBudget()
            active().beginRun()
        }
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = active().shouldRestart(decisionsThisRun)

    override fun onRestart() = active().onRestart()
}
