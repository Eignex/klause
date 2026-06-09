package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.lp.DualSimplex
import com.eignex.klause.solver.lp.LpBuilder
import com.eignex.klause.solver.lp.LpRelaxation
import com.eignex.klause.solver.lp.Relation
import com.eignex.klause.solver.lp.Sense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #246: LP-guided value ordering (round-toward-LP diving). */
class LpBranchingTest {

    @Test
    fun `order puts the value nearest the LP value first`() {
        // Pure LP (no propagation, which would integer-fix x): max x s.t. 3x <= 2 -> x = 2/3, round -> 1.
        val b = LpBuilder()
        val x = b.addVar(0, 5, cost = 1)
        b.addRow(mapOf(x to 3L), Relation.LE, 2)
        val model = b.build(Sense.MAXIMIZE)
        val solution = DualSimplex(model).solve()
        val relaxation = LpRelaxation(
            model = model,
            colVarId = intArrayOf(0),
            colIsBool = booleanArrayOf(false),
            objectiveConstant = 0L,
            intColOf = intArrayOf(0),
            boolColOf = IntArray(0),
        )
        val hints = LpHints(1, 0)
        hints.record(relaxation, solution)

        val ordered = hints.order(VarRef.IntVar(0), sequenceOf(0, 1, 2, 3, 4, 5)).toList()
        // round(2/3)=1 first; ties (0,2 both dist 1) keep input order.
        assertEquals(listOf(1, 0, 2, 3, 4, 5), ordered)
    }

    @Test
    fun `order is a no-op without a hint`() {
        val hints = LpHints(2, 0)
        assertEquals(listOf(5, 0, 3), hints.order(VarRef.IntVar(1), sequenceOf(5, 0, 3)).toList())
    }

    @Test
    fun `lp branching preserves the optimum`() {
        // Triangle covering: x_i+x_j >= 2 over [0,5], minimize sum -> 3. Diving must not change it.
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 2),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val off = BacktrackSolver(p).minimize(obj, BacktrackParams(randomSeed = 1L, lpBounding = true))
        val on = BacktrackSolver(p).minimize(
            obj,
            BacktrackParams(randomSeed = 1L, lpBounding = true, lpBranching = true),
        )
        assertTrue(off is MinimizeResult.Optimal && on is MinimizeResult.Optimal)
        assertEquals(3.0, off.objectiveValue)
        assertEquals(3.0, on.objectiveValue)
    }
}
