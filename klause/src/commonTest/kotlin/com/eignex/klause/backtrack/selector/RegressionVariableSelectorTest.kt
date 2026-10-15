package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RegressionVariableSelectorTest {

    @Test
    fun `a reused selector should be copied per solve`() {
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
        assertEquals((0L until n.toLong()).toSet(), sat.assignment.ints.toSet())
    }

    @Test
    fun `a reused selector should be copied per minimize`() {
        val selector = RegressionVariableSelector.linUcb(seed = 1L)
        val small = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(2) { it }, domainMin = 0, domainSize = 2)),
        )
        BacktrackSolver(small.bake()).solve(BacktrackParams(variableSelector = selector, randomSeed = 0L))

        val n = 5
        val large = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(AllDifferent(IntArray(n) { it }, domainMin = 0, domainSize = n)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(1L, 2L, 3L, 4L, 5L))
        val params = BacktrackParams(variableSelector = selector, randomSeed = 0L)
        val r = BacktrackSolver(large.bake()).minimize(objective, params)
        assertIs<MinimizeResult.Optimal>(r)
    }
}
