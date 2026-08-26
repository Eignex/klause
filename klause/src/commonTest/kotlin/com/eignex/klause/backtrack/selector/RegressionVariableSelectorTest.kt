package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The LinUCB variable heuristic only reorders branching choices, so every verdict must stay
 *  correct regardless of what the per-session model learns. */
class RegressionVariableSelectorTest {

    @Test
    fun `solves an all-different and yields a valid permutation`() {
        val n = 6
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
        )
        val r = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(variableSelector = RegressionVariableSelector.linUcb(seed = 1L), randomSeed = 0L),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0L until n.toLong()).toSet(), sat.assignment.ints.toSet(), "not a permutation")
    }

    @Test
    fun `a reused selector is copied per solve so a prior problem's state cannot leak`() {
        // One selector carried through a shared BacktrackParams across two solves of different size:
        // the engine copies it per search, so the larger solve gets fresh, correctly-sized state.
        val selector = RegressionVariableSelector.linUcb(seed = 1L)
        val small = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(2) { it }, domainMin = 0, domainSize = 2)),
        )
        BacktrackSolver(small.bake()).solve(BacktrackParams(variableSelector = selector, randomSeed = 0L))

        val n = 8
        val large = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
        )
        val r = BacktrackSolver(large.bake()).solve(BacktrackParams(variableSelector = selector, randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0L until n.toLong()).toSet(), sat.assignment.ints.toSet(), "not a permutation")
    }

    @Test
    fun `a reused selector is copied on the minimize path too`() {
        // The COP path (minimize) builds its own shared run separately from solve; the selector copy
        // must cover it, else the small solve's activity arrays are indexed against the larger one.
        val selector = RegressionVariableSelector.linUcb(seed = 1L)
        val small = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(2) { it }, domainMin = 0, domainSize = 2)),
        )
        BacktrackSolver(small.bake()).solve(BacktrackParams(variableSelector = selector, randomSeed = 0L))

        val n = 6
        val large = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L, 4L, 5L, 6L))
        val params = BacktrackParams(variableSelector = selector, randomSeed = 0L)
        val r = BacktrackSolver(large.bake()).minimize(objective, params)
        assertIs<MinimizeResult.Optimal>(r)
    }

    @Test
    fun `proves the optimum under bandit branching`() {
        // minimize x + 2y subject to x + y >= 3, x,y in [0..5]. Optimum = 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val r = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(variableSelector = RegressionVariableSelector.linUcb(seed = 2L), randomSeed = 0L),
        )
        assertEquals(3.0, assertIs<MinimizeResult.Optimal>(r).objectiveValue)
    }
}
