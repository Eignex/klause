package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.table.Element
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #651: [Element] now overrides [Factor.conflictReason] with the hole-aware Hall-style nogood of
 * every read int var ([collectHoleAndBoundAntecedents] over [Element.intVars]), like [AllDifferent].
 * The variable-array path cites `idx`, `result`, and the position vars; the constant-array path
 * cites only `idx`/`result`. Previously the failure fell through to the coarse default bool-pins
 * reason, suppressed once an int decision is on the trail. Tests: (1) the reason is a sound
 * non-empty witness — every literal false at conflict time; (2) full enumeration under CDCL learning
 * matches brute force for both the variable and constant array paths.
 */
class ElementConflictReasonTest {

    @Test
    fun `var-array conflict reason is a sound nonempty witness`() {
        // idx in [0,1] selects arr=[v2, v3]; result must equal arr[idx]. A level-1 decision forces
        // result ≥ 10 but squeezes both elements ≤ 5 — no position can supply result, so idx is
        // wiped and propagate returns false.
        val factor = Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 10), IntDomain(0, 10), IntDomain(0, 10)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.tightenIntMin(1, 10), "result ≥ 10")
        assertTrue(state.tightenIntMax(2, 5) && state.tightenIntMax(3, 5), "both elements ≤ 5")
        assertFalse(problem.propagators[0].propagate(state, 0), "no position can supply result=10 → infeasible")

        val reason = problem.propagators[0].conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem)
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.toList() }
        .toHashSet()

    @Test
    fun `enumerate matches brute force for variable array`() {
        // result == arr[idx], arr=[v2, v3]. ints = [idx, result, v2, v3], all over small ranges.
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 4,
                intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
                factors = arrayOf<Factor>(
                    Element(idx = 0, result = 1, arr = intArrayOf(2, 3), arrIsVars = true, indexOffset = 0),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (idx in 0..1) {
                for (res in 0..3) {
                    for (v2 in 0..3) {
                        for (v3 in 0..3) {
                            val selected = if (idx == 0) v2 else v3
                            if (res == selected) brute.add(listOf(idx, res, v2, v3))
                        }
                    }
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: var-array element must match brute force")
        }
    }

    @Test
    fun `enumerate matches brute force for constant array`() {
        // result == arr[idx] with constant arr=[5, 7, 5]. ints = [idx, result].
        val arr = intArrayOf(5, 7, 5)
        for (seed in 1L..5L) {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 9)),
                factors = arrayOf<Factor>(
                    Element(idx = 0, result = 1, arr = arr, arrIsVars = false, indexOffset = 0),
                ),
            )
            val brute = HashSet<List<Int>>()
            for (idx in 0..2) {
                for (res in 0..9) {
                    if (res == arr[idx]) brute.add(listOf(idx, res))
                }
            }
            assertEquals(brute, enumerate(problem, seed), "seed=$seed: const-array element must match brute force")
        }
    }
}
