package com.eignex.klause.solver.lp.relaxation

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.circuit.Circuit
import com.eignex.klause.solver.factor.linear.Linear
import com.eignex.klause.solver.factor.linear.LinearOp
import com.eignex.klause.solver.factor.linear.ReifiedLinear
import com.eignex.klause.solver.factor.table.Table
import com.eignex.klause.solver.lp.LpModel
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #39: a node-invariant relaxation is built once and re-bound to live column bounds, which must yield
 * exactly the model a per-node rebuild would — the persistent LP's bit-identity guarantee — and only
 * for relaxations whose layout does not depend on the live domains (no auxiliary columns, no live-M
 * rows).
 */
class CpToLpRelaxationReboundTest {

    private fun assertSameModel(expected: LpModel, actual: LpModel) {
        assertEquals(expected.n, actual.n, "n")
        assertEquals(expected.m, actual.m, "m")
        assertContentEquals(expected.csc.colPtr, actual.csc.colPtr, "colPtr")
        assertContentEquals(expected.csc.rowIdx, actual.csc.rowIdx, "rowIdx")
        assertContentEquals(expected.csc.colVal, actual.csc.colVal, "colVal")
        assertContentEquals(expected.rhs, actual.rhs, "rhs")
        assertContentEquals(expected.cost, actual.cost, "cost")
        assertContentEquals(expected.upper, actual.upper, "upper")
        assertContentEquals(expected.hasUpper, actual.hasUpper, "hasUpper")
        assertContentEquals(expected.loShift, actual.loShift, "loShift")
        assertContentEquals(expected.tag, actual.tag, "tag")
        assertEquals(expected.objConstant, actual.objConstant, "objConstant")
        assertEquals(expected.sense, actual.sense, "sense")
    }

    @Test
    fun `rebound reproduces a per-node rebuild bit-for-bit`() {
        // x0 + 2·x1 + 3·x2 ≥ 5 over [0,10], minimize x0 + x1 + x2 — a pure-linear, persistent-eligible
        // relaxation. Build once from declared domains, then re-bind to a branch-tightened session and
        // compare against a fresh build of that same session.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 10) },
            arrayOf<Factor>(Linear(intArrayOf(1, 2, 3), intArrayOf(0, 1, 2), LinearOp.GE, 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val relaxer = CpToLpRelaxation(problem, obj)

        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible, "a pure-linear relaxation must be persistent-eligible")

        val node = PropagationSession(problem)
        node.implyIntAtLeast(0, 2)
        node.implyIntAtMost(1, 6)
        node.implyIntAtMost(2, 4)

        val fresh = relaxer.build(node)
        val reboundModel = base.rebound(node).model
        assertSameModel(fresh.model, reboundModel)
    }

    @Test
    fun `rebound tracks a pinned column`() {
        // Pinning x1 to a point must give the same shifted bounds and rhs as a rebuild sees.
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 8) },
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 9)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val relaxer = CpToLpRelaxation(problem, obj)
        val base = relaxer.build(PropagationSession(problem))

        val node = PropagationSession(problem)
        node.pinInt(1, 5)
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `rebound reproduces a circuit arc relaxation when an arc is pruned`() {
        // A 3-node circuit: arc columns are laid out from the declared domains and pinned live, so the
        // relaxation is persistent-eligible. Pruning value 2 from succ[0] drops arc 0->2; re-binding
        // must pin that arc column to 0 exactly as a per-node rebuild does.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(Circuit(intArrayOf(0, 1, 2))),
        )
        val relaxer = CpToLpRelaxation(
            problem,
            LinearObjective(intCoefficients = longArrayOf(0, 0, 0)),
            circuitArcs = true,
        )
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible, "a circuit arc relaxation must be persistent-eligible")

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, 1) // drop value 2 from succ[0]
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `a live-M reified relaxation is not persistent-eligible`() {
        val problem = Problem(
            1,
            3,
            Array(3) { IntDomain(0, 10) },
            arrayOf<Factor>(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                ReifiedLinear(
                    auxBoolVar = 0,
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.LE,
                    bound = 4,
                ),
            ),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 1, 1)))
        assertFalse(
            relaxer.build(PropagationSession(problem)).persistentEligible,
            "a relaxation with live-M reified rows varies per node and must not be persisted",
        )
    }

    @Test
    fun `a hull relaxation with auxiliary columns is not persistent-eligible`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 5)),
            arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 5, 2, 2, 4, 0))),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 0)), tableHull = true)
        assertFalse(
            relaxer.build(PropagationSession(problem)).persistentEligible,
            "selector columns are not CP-var-backed, so the table hull cannot be re-bound",
        )
    }
}
