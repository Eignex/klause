package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
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
    fun `a linear row over coefficients beyond Int range bounds soundly`() {
        // 3e9·x >= 6e9 over x in [0,10], minimize x. The exact row carries Long coefficients into the
        // relaxation, so the LP optimum is x = 2.
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(Linear(longArrayOf(3_000_000_000L), intArrayOf(0), LinearOp.GE, 6_000_000_000L)),
        )
        val r = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(PropagationSession(problem))
        val sol = solveLp(r.model)
        assertEquals(LpVerdict.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, 1e-6, "3e9·x >= 6e9 gives min x = 2")
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
    fun `rebound reproduces a table hull when a tuple becomes infeasible`() {
        // Table {(0,5),(2,2),(4,0)}: each tuple's selector is present while every entry stays live.
        // Pruning value 5 from x1 drops the (0,5) selector; re-binding must pin it to 0 like a rebuild.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 5)),
            arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 5, 2, 2, 4, 0))),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 0)), tableHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible, "a table hull relaxation must be persistent-eligible")

        val node = PropagationSession(problem)
        node.implyIntAtMost(1, 4) // value 5 leaves x1 — tuple (0,5) infeasible
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `rebound reproduces a table hull over values beyond Int range`() {
        // Tuple values past 2^31 (a float-scaled table): the selector's presence rule stores the true
        // Long value, so pruning a wide value must pin the tuple's column exactly as a rebuild does. A
        // truncated presence value would test membership of the wrong value and diverge from the rebuild.
        val b = 4_000_000_000L
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(b, b + 4), IntDomain(0, 5)),
            arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(b, 5, b + 2, 2, b + 4, 0))),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 0)), tableHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible, "a table hull relaxation must be persistent-eligible")

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, b + 3) // value b+4 leaves x0 — tuple (b+4, 0) infeasible
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `rebound reproduces an nvalue hull when a value is pruned`() {
        // var n = |distinct(x0,x1,x2)| over [0,3]; pruning value 3 from x0 drops its z selector.
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 3) },
            arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2))),
        )
        val relaxer =
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)), nValueHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible, "an nvalue hull relaxation must be persistent-eligible")

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, 2)
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `rebound reproduces a gcc count hull when a value is pruned`() {
        // counts of cover values 1,2 over x0,x1; count vars are 2,3. Pruning value 2 from x0 drops a z.
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 3) },
            arrayOf<Factor>(
                GlobalCardinality(xs = intArrayOf(0, 1), cover = longArrayOf(1, 2), countVars = intArrayOf(2, 3)),
            ),
        )
        val relaxer =
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 1, 1)), gccCountHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible, "a gcc count hull relaxation must be persistent-eligible")

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, 1)
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `rebound reproduces a gcc count hull over cover values beyond Int range`() {
        // Cover values past 2^31: the count-linkage rows key selectors by cover position and the
        // selector presence carries the full Long value, so the hull emits its columns and re-binds
        // bit-identically when a wide value is pruned.
        val b = 4_000_000_000L
        val problem = Problem(
            0,
            4,
            arrayOf(IntDomain(b + 1, b + 2), IntDomain(b + 1, b + 2), IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1),
                    cover = longArrayOf(b + 1, b + 2),
                    countVars = intArrayOf(2, 3),
                ),
            ),
        )
        val relaxer =
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 1, 1)), gccCountHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.model.n > 4, "wide-cover gcc must emit its selector hull columns")

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, b + 1) // value b+2 leaves x0 — drops that selector
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `rebound reproduces an element hull when an index value is pruned`() {
        // result = arr[idx], arr = [7,3,9,5]; pruning index 3 drops the p=3 selector.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 9)),
            arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = longArrayOf(7, 3, 9, 5), arrIsVars = false, indexOffset = 0),
            ),
        )
        val relaxer =
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0, 1)), elementHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible, "an element hull relaxation must be persistent-eligible")

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, 2)
        assertSameModel(relaxer.build(node).model, base.rebound(node).model)
    }

    @Test
    fun `a cumulative energetic relaxation is not persistent-eligible`() {
        // The energetic makespan row's right-hand side is recomputed from the live starts, so its
        // coefficients/rhs vary per node — not re-bindable, it stays on the per-node rebuild.
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(3, 0), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.GE, 3),
            Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L),
        )
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 20) }, factors)
        val relaxer =
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)), cumulative = true)
        assertFalse(
            relaxer.build(PropagationSession(problem)).persistentEligible,
            "a live-coefficient energetic row varies per node and must not be persisted",
        )
    }
}
