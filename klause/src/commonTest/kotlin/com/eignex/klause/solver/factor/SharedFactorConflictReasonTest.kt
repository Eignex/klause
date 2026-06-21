package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

/**
 * Portfolio workers share one Problem (and therefore one factor object) across sessions.
 * Conflict-reason scratch must live on the per-session state, not the factor: a factor
 * field written by one session's failure and read after another session's failure hands
 * the analyzer the wrong reason, which corrupts the learned clause (#182). No concurrency
 * is needed to expose the hazard — interleaving two sessions sequentially suffices.
 */
class SharedFactorConflictReasonTest {

    private fun problemOf(factor: Factor, vararg domains: IntDomain) = Problem(
        numBoolVars = 0,
        numIntVars = domains.size,
        intDomains = arrayOf(*domains),
        factors = arrayOf(factor),
    )

    /** Drive [state] into an AllDifferent conflict by pinning two vars to [value]. */
    private fun failPinnedPair(state: PropagationState, a: Int, b: Int, value: Int): Boolean {
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(a, value) && state.tightenIntMax(a, value)) { "pin $a failed" }
        check(state.tightenIntMin(b, value) && state.tightenIntMax(b, value)) { "pin $b failed" }
        return state.problem.propagators[0].propagate(state, 0)
    }

    @Test
    fun `interleaved sessions keep independent alldifferent conflict reasons`() {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = problemOf(factor, IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9))

        // Control: session A alone — pin vars 0 and 1 to the same value and capture the reason.
        val control = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(control, a = 0, b = 1, value = 3), "pinned pair must conflict")
        val controlReason = problem.propagators[0].conflictReason(control, 0)

        // Interleaved: session A fails as above, then session B (same factor object) fails on a
        // DIFFERENT pair, then A's reason is read. With factor-level scratch B's failure
        // overwrites A's and this assertion breaks.
        val a = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(a, a = 0, b = 1, value = 3))
        val b = PropagationState(problem, Assumptions.None)
        assertFalse(failPinnedPair(b, a = 1, b = 2, value = 7))
        val interleavedReason = problem.propagators[0].conflictReason(a, 0)

        assertContentEquals(
            controlReason,
            interleavedReason,
            "session A's conflict reason must be unaffected by session B's later failure",
        )
    }
}
