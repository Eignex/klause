package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Persistent global cut pool — root-harvested structural cuts re-added at every node. */
class CutPoolTest {

    // all_different(x0,x1,x2) over [0,4]; minimize 3x0+2x1+x2. Smallest values go to the largest
    // coefficients: x0=0, x1=1, x2=2 -> 3*0+2*1+1*2 = 4. The AllDifferent Hall cut (Σx >= 0+1+2 = 3)
    // is globally valid and is what the pool caches.
    private fun problem() = Problem(
        numBoolVars = 0,
        numIntVars = 3,
        intDomains = Array(3) { IntDomain(0, 4) },
        factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 5)),
    )

    private val obj = LinearObjective(intCoefficients = longArrayOf(3, 2, 1))

    @Test
    fun `cut pool preserves the optimum`() {
        // The root-harvested pooled cuts are globally valid (it rides on `cuts`), so the proven optimum
        // is unchanged. (Node count is not asserted: valid cuts shift the LP vertex, hence reduced-cost
        // fixings and branching, either way — the same non-monotonicity warm-starting has.)
        val p = problem()
        val pool = BacktrackSolver(p.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpPlan = LpPlan(bounding = true, cuts = true)),
        )
        assertTrue(pool is MinimizeResult.Optimal)
        assertEquals(4.0, pool.objectiveValue)
    }
}
