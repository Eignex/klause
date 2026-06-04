package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Direct propagator-level tests for the SSP lower bound + reduced-cost arc pruning. */
class MinCostFlowTest {

    @Test
    fun `ssp lower bound tightens cost min to LP optimum`() {
        // Two parallel arcs from src (node 0) to sink (node 1). One unit must flow.
        // Arc 0 cost 3 ∈ [0, 1]; Arc 1 cost 5 ∈ [0, 1]; balance = [-1, 1].
        val factor = MinCostFlow(
            numNodes = 2,
            arcFrom = intArrayOf(0, 0),
            arcTo = intArrayOf(1, 1),
            balance = intArrayOf(-1, 1),
            flow = intArrayOf(0, 1),
            weight = intArrayOf(3, 5),
            cost = 2,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 100)),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.baked
        assertTrue(r is PropagationResult.Implied, "expected propagation success; got $r")
        // LP min = 3 (route through arc 0). LP max = 5 (route through arc 1).
        assertEquals(3, r.intMinOrNullCompat(2), "cost.min should match SSP LP lower bound")
        assertEquals(5, r.intMaxOrNullCompat(2), "cost.max should match SSP LP upper bound")
    }

    @Test
    fun `infeasible balance is rejected`() {
        // Source supplies 5 but only one arc with capacity 1 — infeasible.
        val factor = MinCostFlow(
            numNodes = 2,
            arcFrom = intArrayOf(0),
            arcTo = intArrayOf(1),
            balance = intArrayOf(-5, 5),
            flow = intArrayOf(0),
            weight = intArrayOf(1),
            cost = 1,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 100)),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.baked
        assertTrue(r is PropagationResult.Unsat, "tiny arc cap can't satisfy balance demand; got $r")
    }

    @Test
    fun `negative-weight residual cycle is handled without hanging`() {
        // #85 regression: negative arc weights create a negative-cost cycle (2->3->2, w=-3 each)
        // in the residual graph reachable from the source. Without the SPFA negative-cycle guard,
        // ssp would loop forever (distances decrease around the cycle). The guard detects it and
        // skips the unsound SSP tightening, so propagation still terminates and stays sound — the
        // trivial linear cost bounds remain in force.
        val factor = MinCostFlow(
            numNodes = 4,
            arcFrom = intArrayOf(0, 0, 2, 3),
            arcTo = intArrayOf(1, 2, 3, 2),
            balance = intArrayOf(-1, 1, 0, 0),
            flow = intArrayOf(0, 1, 2, 3),
            weight = intArrayOf(1, 0, -3, -3),
            cost = 4,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 1),
                IntDomain(0, 5),
                IntDomain(0, 5),
                IntDomain(0, 5),
                IntDomain(-1000, 1000),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.baked
        // Must terminate and stay sound: the unit is forced onto arc 0 (cost 1) by balance.
        assertTrue(r is PropagationResult.Implied, "expected propagation success; got $r")
        assertEquals(1, r.intValueOrNull(0), "the single supply unit must flow on arc 0")
    }

    @Test
    fun `reduced cost pruning forbids dearer arc when cost max is tight`() {
        // Same parallel-arcs graph; force cost.max = 3 — the dearer (w=5) arc must drop to 0.
        val factor = MinCostFlow(
            numNodes = 2,
            arcFrom = intArrayOf(0, 0),
            arcTo = intArrayOf(1, 1),
            balance = intArrayOf(-1, 1),
            flow = intArrayOf(0, 1),
            weight = intArrayOf(3, 5),
            cost = 2,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(3, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.baked
        assertTrue(r is PropagationResult.Implied, "expected propagation success; got $r")
        // The cheaper arc (flow[0]) gets the unit; the dearer arc (flow[1]) goes to 0.
        assertEquals(1, r.ints[0], "cheaper arc must carry the unit")
        assertEquals(0, r.ints[1], "dearer arc must be pruned to 0 by reduced-cost filtering")
    }
}
