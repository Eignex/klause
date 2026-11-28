package com.eignex.klause.backtrack

import com.eignex.klause.solver.search.SearchRestartPolicy
import com.eignex.klause.util.lubyN

// The phase regime a restart schedule asks PhaseSaving to run under: STABLE dives on the best conflict-free
// phase, FOCUSED uses plain phase saving, UNMANAGED leaves phasing to its own rephase rotation.
internal enum class PhaseMode { UNMANAGED, STABLE, FOCUSED }

// When to restart, decoupled from the restart action (popping the trail, replaying nogoods, forgetting,
// vivifying), which stays with the caller. Each driver owns its own instance, so detector state is never shared.
internal interface RestartSchedule : SearchRestartPolicy {
    override fun beginRun() {}

    override fun recordConflict(lbd: Int, trailSize: Int) {}

    override fun shouldRestart(decisionsThisRun: Long): Boolean

    override fun onRestart() {}

    // Called on the first feasible solution and each improving one.
    override fun onSolution() {}

    fun phaseMode(): PhaseMode = PhaseMode.UNMANAGED

    companion object {
        // Conflicts in the first cycle segment of the default mode-switching schedule.
        const val DEFAULT_MODE_BASE: Long = 500L

        const val STABLE_LUBY_BASE: Long = 100L

        // Dive until a first feasible solution, then cycle a stable Luby stage with EMA- and
        // decision-level-adaptive focused stages.
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

        fun from(params: BacktrackParams): RestartSchedule = when {
            params.modeSwitchingRestart -> defaultModeSwitching()
            params.emaRestart -> EmaRestartSchedule()
            params.adaptiveRestart -> AdaptiveRestartSchedule()
            params.lubyRestartBase != null -> LubyRestartSchedule(params.lubyRestartBase)
            else -> NoRestartSchedule
        }
    }
}

internal object NoRestartSchedule : RestartSchedule {
    override fun shouldRestart(decisionsThisRun: Long): Boolean = false
}

// Dives without restarting until the first feasible solution, then hands every decision to inner. Restart-heavy
// schedules cap the search depth below where a solution lives on deep instances, while a first solution needs one
// sustained dive. Reports STABLE while diving. Conflict learning still runs, so an infeasible problem is still
// decided.
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

// Runs `lubyN(idx) · base` decisions per run (Luby, Sinclair and Zuckerman, "Optimal speedup of Las Vegas
// algorithms", Inf. Process. Lett. 47, 1993, https://doi.org/10.1016/0020-0190(93)90029-9).
internal class LubyRestartSchedule(private val base: Long) : RestartSchedule {
    private var lubyIdx = 1L
    private var perRunBudget = Long.MAX_VALUE

    override fun beginRun() {
        // Saturate instead of overflowing on a large lubyIdx.
        val limit = lubyN(lubyIdx)
        perRunBudget = if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
    }

    override fun shouldRestart(decisionsThisRun: Long): Boolean = decisionsThisRun >= perRunBudget

    override fun onRestart() {
        lubyIdx++
    }
}

// GlucoseRestart detector, no per-run budget: restarts fire on learned-clause quality.
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

// EmaRestart detector, no per-run budget.
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

// Restarts off the decision level at conflicts, the complement of the LBD-based schedules; no per-run budget.
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

internal class RestartStage(val schedule: RestartSchedule, val phaseMode: PhaseMode)

// Cycles the stages, each segment lasting `lubyN(segment) · switchBase` conflicts, so segments lengthen as the
// search proceeds. Restart decisions route to the active stage's schedule, which is re-sized with beginRun on
// each switch; the stage's phase mode couples the polarity source to it.
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

    override fun phaseMode(): PhaseMode = stages[stageIndex].phaseMode
}
