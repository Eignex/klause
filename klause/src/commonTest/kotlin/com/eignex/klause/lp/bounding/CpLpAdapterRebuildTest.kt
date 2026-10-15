package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CpLpAdapterRebuildTest {

    private fun assertSameModel(expected: LpModel, base: LpRelaxation, session: PropagationSession) {
        val actual = LpEngine(
            session.problem,
            LinearObjective(intCoefficients = LongArray(session.problem.numIntVars)),
            LpParams(),
            SolveStatsSink(backend = "adapter"),
        ).use { assertNotNull(it.cpAdapter.relaxation(base, session)).model }
        val expectedAuthority = assertNotNull(expected.authoritativeModel())
        val actualAuthority = assertNotNull(actual.authoritativeModel())
        assertEquals(expectedAuthority.n, actualAuthority.n, "n")
        assertEquals(expectedAuthority.m, actualAuthority.m, "m")
        val recentered = actualAuthority.recentered(List(expected.n) { expectedAuthority.column(it).origin })
        for (column in 0 until expectedAuthority.numVars) {
            assertEquals(expectedAuthority.column(column), recentered.column(column), "column $column")
            if (column < expected.n) {
                assertEquals(
                    expectedAuthority.columnEntries(column),
                    recentered.columnEntries(column),
                    "matrix $column",
                )
            }
        }
        for (row in 0 until expected.m) {
            assertEquals(expectedAuthority.rhs(row), recentered.rhs(row), "rhs $row")
            assertEquals(expectedAuthority.row(row), recentered.row(row), "row $row")
        }
        assertEquals(expectedAuthority.objective, recentered.objective, "objective")
    }

    @Test
    fun `adapter should reproduce a per-node rebuild bit for bit`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 10) },
            arrayOf<Factor>(Linear(intArrayOf(1, 2, 3), intArrayOf(0, 1, 2), LinearOp.GE, 5)),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val relaxer = CpToLpRelaxation(problem, obj)

        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible)

        val node = PropagationSession(problem)
        node.implyIntAtLeast(0, 2)
        node.implyIntAtMost(1, 6)
        node.implyIntAtMost(2, 4)

        val fresh = relaxer.build(node)
        assertSameModel(fresh.model, base, node)
    }

    @Test
    fun `adapter should bound soundly over coefficients beyond Int range`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(Linear(longArrayOf(3_000_000_000L), intArrayOf(0), LinearOp.GE, 6_000_000_000L)),
        )
        val r = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(PropagationSession(problem))
        val sol = solveLp(r.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, 1e-6)
    }

    @Test
    fun `adapter should reproduce a circuit arc relaxation when an arc is pruned`() {
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
        assertTrue(base.persistentEligible)

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, 1)
        assertSameModel(relaxer.build(node).model, base, node)
    }

    @Test
    fun `adapter should reproduce a table hull when a tuple becomes infeasible`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 4), IntDomain(0, 5)),
            arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(0, 5, 2, 2, 4, 0))),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 0)), tableHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible)

        val node = PropagationSession(problem)
        node.implyIntAtMost(1, 4)
        assertSameModel(relaxer.build(node).model, base, node)
    }

    @Test
    fun `adapter should reproduce a table hull over values beyond Int range`() {
        // A truncated presence value would test membership of the wrong value.
        val b = 4_000_000_000L
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(b, b + 4), IntDomain(0, 5)),
            arrayOf<Factor>(Table(xs = intArrayOf(0, 1), tuples = longArrayOf(b, 5, b + 2, 2, b + 4, 0))),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 0)), tableHull = true)
        val base = relaxer.build(PropagationSession(problem))
        assertTrue(base.persistentEligible)

        val node = PropagationSession(problem)
        node.implyIntAtMost(0, b + 3)
        assertSameModel(relaxer.build(node).model, base, node)
    }

    @Test
    fun `cumulative energetic relaxation should not be persistent eligible`() {
        // The energetic row's rhs is recomputed from the live starts on every node.
        val factors = arrayOf<Factor>(
            Linear(intArrayOf(1, -1), intArrayOf(3, 0), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 1), LinearOp.GE, 3),
            Linear(intArrayOf(1, -1), intArrayOf(3, 2), LinearOp.GE, 3),
            Cumulative(intArrayOf(0, 1, 2), longArrayOf(3, 3, 3), longArrayOf(1, 1, 1), capacity = 1L),
        )
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 20) }, factors)
        val relaxer =
            CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1)), cumulative = true)
        assertFalse(relaxer.build(PropagationSession(problem)).persistentEligible)
    }
}
