package com.eignex.klause.backtrack

import com.eignex.klause.solver.search.SearchRestartPolicy
import com.eignex.klause.util.lubyN

/**
 * The phase regime a [RestartSchedule] asks the engine's [PhaseSaving] to run under. [PhaseMode.STABLE]
 * dives on the best conflict-free phase (holding progress across restarts); [PhaseMode.FOCUSED] uses
 * plain phase saving without the target bias; [PhaseMode.UNMANAGED] leaves phasing to its own rephase
 * rotation (the default, so a schedule that doesn't opt in never perturbs phasing).
 */
internal enum class PhaseMode { UNMANAGED, STABLE, FOCUSED }

/**
 * Pluggable restart *schedule* for one backtrack run: the per-run decision budget and the decision of
 * when to cut a run short and pop back to root. Decouples *when* to restart from the restart *action* —
 * popping the trail, replaying nogoods, forgetting, vivifying — which stays with the caller
 * ([com.eignex.klause.solver.search.SearchRun]), so a new schedule can be dropped in without touching traversal.
 *
 * Selected from [BacktrackParams] by [from]; each driver owns its own instance (and thus its own
 * detector state), so the satisfaction path ([BacktrackSolver]) and the branch-and-bound engine
 * ([com.eignex.klause.backtrack.ResumableMinimize]) never share schedule state.
 */
internal interface RestartSchedule : SearchRestartPolicy {
    /** Size the budget for a fresh run — call at the start of each run. */
    override fun beginRun() {}

    /** Feed a conflict's LBD and trail depth to the schedule; may raise a pending restart. */
    override fun recordConflict(lbd: Int, trailSize: Int) {}

    /** True when the current run should restart. [decisionsThisRun] is the caller's decision count
     *  since the last [beginRun]. */
    override fun shouldRestart(decisionsThisRun: Long): Boolean

    /** Consume a restart once the caller has popped to root — advance the schedule state. */
    override fun onRestart() {}

    /** Notify the schedule that a feasible solution was found (the first one, and each improving one on
     *  the optimize path). Lets a schedule change regime once the search has something to hold onto. */
    override fun onSolution() {}

    /** The phase regime the schedule wants the engine's [PhaseSaving] to run right now.
     *  [PhaseMode.UNMANAGED] (the default) leaves phasing to its own rephase rotation; a schedule that
     *  alternates a proving and a diving regime returns [PhaseMode.STABLE] / [PhaseMode.FOCUSED] to
     *  couple the polarity source to the mode. */
    fun phaseMode(): PhaseMode = PhaseMode.UNMANAGED

    companion object {
        /** Conflicts in the first cycle segment of the default [ModeSwitchingRestartSchedule]; later
         *  segments scale it by the Luby sequence. */
        const val DEFAULT_MODE_BASE: Long = 500L

        /** Luby decision base for the stable stage of the default cycle. */
        const val STABLE_LUBY_BASE: Long = 100L

        /** The default restart policy: dive without restarting until a first feasible solution
         *  ([DiveUntilFeasibleRestartSchedule]), then run a cycling portfolio — a Luby stable stage that
         *  dives on the best phase, an EMA/LBD-adaptive focused stage, and a decision-level-adaptive
         *  focused stage. Restart-heavy search starves first-feasible on deep instances (it caps the
         *  search depth below where a solution lives); once an incumbent exists, restarts help improve
         *  and prove it. */
        private fun defaultModeSwitching(): RestartSchedule = DiveUntilFeasibleRestartSchedule(
            ModeSwitchingRestartSchedule(
                stages = listOf(
                    RestartStage(LubyRestartSchedule(STABLE_LUBY_BASE), PhaseMode.STABLE),
                    RestartStage(EmaRestartSchedule(), PhaseMode.FOCUSED),
                    RestartStage(DlRestartSchedule(), PhaseMode.FOCUSED),
                ),
                switchBase = DEFAULT_MODE_BASE,
            ),
        )

        /** The schedule selected by [params], in precedence order: mode-switching when
         *  [BacktrackParams.modeSwitchingRestart], else EMA-adaptive when [BacktrackParams.emaRestart],
         *  else LBD-adaptive when [BacktrackParams.adaptiveRestart], else Luby when
         *  [BacktrackParams.lubyRestartBase] is set, else a single unbounded run. */
        fun from(params: BacktrackParams): RestartSchedule = when {
            params.modeSwitchingRestart -> defaultModeSwitching()
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
 * Feasibility-first gate: dives without restarting until the first feasible solution is seen, then hands
 * every decision over to [inner]. Restart-heavy schedules starve first-feasible on deep instances — they
 * cap the search depth below the depth a solution lives at — but a first solution only needs one
 * sustained dive, and once an incumbent exists restarts help improve and prove it. Reports
 * [PhaseMode.STABLE] while diving so the engine dives on the target phase, and forwards later solutions
 * to [inner]. Diving without restarts stays complete (conflict learning still runs), so an infeasible
 * problem is still decided, just without restart diversification until a solution appears.
 */
internal class DiveUntilFeasibleRestartSchedule(private val inner: RestartSchedule) : RestartSchedule {
    private var engaged = false

    override fun beginRun() {
        if (engaged) inner.beginRun()
    }

    override fun recordConflict(lbd: Int, trailSize: Int) {
        if (engaged) inner.recordConflict(lbd, trailSize)
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = engaged && inner.shouldRestart(decisionsThisRun)

    override fun onRestart() {
        if (engaged) inner.onRestart()
    }

    override fun onSolution() {
        if (engaged) {
            inner.onSolution()
        } else {
            engaged = true
            inner.beginRun()
        }
    }

    override fun phaseMode(): PhaseMode = if (engaged) inner.phaseMode() else PhaseMode.STABLE
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
 * LBD/trail-size adaptive schedule: a [GlucoseRestart] detector drives restarts and the per-run budget
 * is disabled (unbounded), so restarts fire on learned-clause quality rather than a fixed decision count.
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
 * of learned-clause LBD rather than [GlucoseRestart]'s bounded windows. The
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
 * Decision-level adaptive schedule: a [DecisionLevelRestart] detector drives restarts off the trail
 * depth at conflicts, the complement of the LBD-based [AdaptiveRestartSchedule] / [EmaRestartSchedule].
 * The per-run budget is unbounded.
 */
internal class DlRestartSchedule : RestartSchedule {
    private val dl = DecisionLevelRestart()
    private var restartRequested = false

    override fun recordConflict(lbd: Int, trailSize: Int) {
        if (dl.recordConflict(trailSize)) restartRequested = true
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = restartRequested

    override fun onRestart() {
        restartRequested = false
        dl.clearWindow()
    }
}

/** One member of a [ModeSwitchingRestartSchedule] cycle: a sub-schedule that governs restarts while it
 *  is active, paired with the phase regime the engine should run under during it. */
internal class RestartStage(val schedule: RestartSchedule, val phaseMode: PhaseMode)

/**
 * Cycling multi-strategy restart portfolio, mixing several schedules within one run to be robust on
 * optimization without hand-tuning. The search cycles the [stages] list:
 * each segment lasts `lubyN(seg) · switchBase` conflicts, then the next stage takes over, so segments
 * lengthen as the search proceeds. A stage's [RestartStage.phaseMode] couples the polarity heuristic to
 * the mode — the typical cycle pairs a [PhaseMode.STABLE] Luby stage (dive on the target phase, holding
 * progress) with one or more [PhaseMode.FOCUSED] adaptive stages (restart-heavy proving). Starts on the
 * first stage.
 *
 * Delegation is total: [shouldRestart] / [onRestart] / [beginRun] all route to the active sub-schedule,
 * and [recordConflict] both feeds the active schedule and drives the cycle clock. On a stage switch the
 * newly active schedule's [beginRun] is called so its budget is sized for the fresh segment.
 */
internal class ModeSwitchingRestartSchedule(private val stages: List<RestartStage>, private val switchBase: Long) :
    RestartSchedule {
    init {
        require(stages.isNotEmpty()) { "a mode-switching schedule needs at least one stage" }
    }

    private var stageIndex = 0
    private var segment = 1L
    private var conflictsThisSegment = 0L
    private var segmentBudget = segmentBudget()

    private fun active(): RestartSchedule = stages[stageIndex].schedule

    private fun segmentBudget(): Long {
        val limit = lubyN(segment)
        return if (limit > Long.MAX_VALUE / switchBase) Long.MAX_VALUE else limit * switchBase
    }

    override fun beginRun() = active().beginRun()

    override fun recordConflict(lbd: Int, trailSize: Int) {
        active().recordConflict(lbd, trailSize)
        if (++conflictsThisSegment >= segmentBudget) {
            stageIndex = (stageIndex + 1) % stages.size
            segment++
            conflictsThisSegment = 0
            segmentBudget = segmentBudget()
            active().beginRun()
        }
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = active().shouldRestart(decisionsThisRun)

    override fun onRestart() = active().onRestart()

    /** The phase regime of the active stage, so a stable Luby stage dives on the target phase. */
    override fun phaseMode(): PhaseMode = stages[stageIndex].phaseMode
}
