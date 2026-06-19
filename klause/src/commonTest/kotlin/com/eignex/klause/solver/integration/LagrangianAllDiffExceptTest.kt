package com.eignex.klause.solver.integration

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.lp.LpPlan
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * #714: the Lagrangian AllDifferent bound must not treat an `alldifferent_except` as a pure
 * all-different. Three vars over {0,1} with `alldifferent_except_0` is feasible (all may be 0), but a
 * *pure* all-different over 3 vars / 2 values is Hall-infeasible — so treating it as pure would prove
 * a false UNSAT and prune the real optimum.
 */
class LagrangianAllDiffExceptTest {

    @Test
    fun `alldifferent except 0 is not pruned as a pure all-different`() {
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 1) },
            arrayOf<Factor>(
                AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 2, exceptSet = intArrayOf(0)),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        // Lagrangian on (the AllDifferent bound) + the LP bounding stack — the #714 trigger.
        val r = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lagrangian = true, lpPlan = LpPlan(bounding = true)),
        )
        // Feasible: all zero ⇒ objective 0. A false UNSAT (the bug) would surface as Infeasible here.
        val opt = assertIs<MinimizeResult.Optimal>(r)
        assertEquals(0.0, opt.objectiveValue)
    }
}
