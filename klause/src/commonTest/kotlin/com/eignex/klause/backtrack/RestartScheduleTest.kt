package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RestartScheduleTest {
    @Test
    fun `luby restart budget should follow the Luby sequence`() {
        // Base 1 so the per-run budget equals lubyN(run): 1,1,2,1,1,2,4,...
        val restart = RestartSchedule.from(BacktrackParams(lubyRestartBase = 1L))
        val expected = longArrayOf(1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8)
        for (budget in expected) {
            restart.beginRun()
            assertFalse(restart.shouldRestart(budget - 1), "should not restart below the run budget $budget")
            assertTrue(restart.shouldRestart(budget), "should restart once the run budget $budget is reached")
            restart.onRestart()
        }
    }

    @Test
    fun `luby budget should scale with the restart base`() {
        val restart = RestartSchedule.from(BacktrackParams(lubyRestartBase = 10L))
        restart.beginRun()
        assertFalse(restart.shouldRestart(9))
        assertTrue(restart.shouldRestart(10))
    }

    @Test
    fun `large positive luby units should saturate the restart budget`() {
        val restart = RestartSchedule.from(BacktrackParams(lubyRestartBase = Long.MAX_VALUE))
        repeat(3) {
            restart.beginRun()
            assertFalse(restart.shouldRestart(Long.MAX_VALUE - 1))
            assertTrue(restart.shouldRestart(Long.MAX_VALUE))
            restart.onRestart()
        }
    }

    @Test
    fun `adaptive restart should leave the per-run budget unbounded`() {
        val restart = RestartSchedule.from(BacktrackParams(adaptiveRestart = true))
        restart.beginRun()
        assertFalse(restart.shouldRestart(Long.MAX_VALUE - 1))
    }

    @Test
    fun `run without a schedule should be unbounded`() {
        val restart = RestartSchedule.from(BacktrackParams())
        restart.beginRun()
        assertFalse(restart.shouldRestart(1_000_000L))
    }

    @Test
    fun `from should select the schedule configured on the params`() {
        assertIs<DiveUntilFeasibleRestartSchedule>(
            RestartSchedule.from(BacktrackParams(modeSwitchingRestart = true)),
        )
        assertIs<EmaRestartSchedule>(RestartSchedule.from(BacktrackParams(emaRestart = true)))
        assertIs<AdaptiveRestartSchedule>(RestartSchedule.from(BacktrackParams(adaptiveRestart = true)))
        assertIs<LubyRestartSchedule>(RestartSchedule.from(BacktrackParams(lubyRestartBase = 256L)))
        assertIs<NoRestartSchedule>(RestartSchedule.from(BacktrackParams()))
    }

    @Test
    fun `adaptive should win over a luby base`() {
        assertIs<AdaptiveRestartSchedule>(
            RestartSchedule.from(BacktrackParams(adaptiveRestart = true, lubyRestartBase = 256L)),
        )
    }

    @Test
    fun `ema should win over adaptive and a luby base`() {
        assertIs<EmaRestartSchedule>(
            RestartSchedule.from(BacktrackParams(emaRestart = true, adaptiveRestart = true, lubyRestartBase = 256L)),
        )
    }

    @Test
    fun `mode-switching should win over ema`() {
        assertIs<DiveUntilFeasibleRestartSchedule>(
            RestartSchedule.from(BacktrackParams(modeSwitchingRestart = true, emaRestart = true)),
        )
    }

    private object AlwaysRestart : RestartSchedule {
        override fun shouldRestart(decisionsThisRun: Long): Boolean = true
    }

    // switchBase 3, so segment budgets follow lubyN(seg)*3: 3 (focused), 3 (stable), 6 (focused), ...
    private fun schedule() = ModeSwitchingRestartSchedule(
        stages = listOf(
            RestartStage(AlwaysRestart, PhaseMode.FOCUSED),
            RestartStage(NoRestartSchedule, PhaseMode.STABLE),
        ),
        switchBase = 3,
    )

    private fun RestartSchedule.recordConflicts(n: Int) = repeat(n) { recordConflict(lbd = 2, trailSize = 10) }

    @Test
    fun `schedule should switch to the stable dive mode after the focused budget`() {
        val s = schedule()
        s.recordConflicts(2)
        assertTrue(s.shouldRestart(0), "still focused within the first segment")
        s.recordConflicts(1)
        assertFalse(s.shouldRestart(0), "the stable dive mode suppresses restarts after the focused budget")
    }

    @Test
    fun `schedule should cycle through more than two stages and wrap to the first`() {
        val s = ModeSwitchingRestartSchedule(
            stages = listOf(
                RestartStage(NoRestartSchedule, PhaseMode.STABLE),
                RestartStage(AlwaysRestart, PhaseMode.FOCUSED),
                RestartStage(NoRestartSchedule, PhaseMode.STABLE),
            ),
            switchBase = 2,
        )
        // Segment budgets follow lubyN(seg)*2: 2, 2, 4, ...
        s.recordConflicts(2)
        assertTrue(s.shouldRestart(0), "advanced to the restart-heavy second stage")
        s.recordConflicts(2)
        assertFalse(s.shouldRestart(0), "advanced to the third, no-restart stage")
        s.recordConflicts(4)
        assertFalse(s.shouldRestart(0), "wrapped back to the first, no-restart stage")
    }

    @Test
    fun `phase mode should track the active segment`() {
        val s = schedule()
        assertEquals(PhaseMode.FOCUSED, s.phaseMode(), "the focused segment runs the focused phase")
        s.recordConflicts(3)
        assertEquals(PhaseMode.STABLE, s.phaseMode(), "the stable dive segment runs the stable phase")
        s.recordConflicts(3)
        assertEquals(PhaseMode.FOCUSED, s.phaseMode(), "focused again after the stable segment")
    }

    private object AlwaysRestartFocused : RestartSchedule {
        override fun shouldRestart(decisionsThisRun: Long): Boolean = true
        override fun phaseMode(): PhaseMode = PhaseMode.FOCUSED
    }

    @Test
    fun `schedule should dive without restarting until the first solution`() {
        val s = DiveUntilFeasibleRestartSchedule(AlwaysRestartFocused)
        s.beginRun()
        s.recordConflict(lbd = 3, trailSize = 40)
        assertFalse(s.shouldRestart(1_000_000L))
        assertEquals(PhaseMode.STABLE, s.phaseMode())
    }

    @Test
    fun `schedule should delegate to the inner schedule once a solution is found`() {
        val s = DiveUntilFeasibleRestartSchedule(AlwaysRestartFocused)
        s.onSolution()
        assertTrue(s.shouldRestart(0))
        assertEquals(PhaseMode.FOCUSED, s.phaseMode())
    }

    @Test
    fun `unit Luby base should still find sat within the decision budget`() {
        val factor = Cardinality.exactlyOne(
            intArrayOf(
                Lit.make(0, true),
                Lit.make(1, true),
                Lit.make(2, true),
                Lit.make(3, true),
            ),
        )
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val params = BacktrackParams(
            randomSeed = 0L,
            lubyRestartBase = 1L,
            maxDecisions = 200L,
            variableSelector = InputOrder,
            valueSelector = IndomainMin,
        )
        val r = BacktrackSolver(problem.bake()).solve(params)
        assertIs<SolveResult.Sat>(r)
    }
}
