package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #655 (Tranche A): the layered flow hull of [Mdd]. The flow polytope is integral, so the LP optimum
 * of a linear objective over the sequence (or the cost-MDD's cost var) equals the true optimum over
 * the diagram's accepting paths — checked against brute force.
 */
class CpToLpRelaxationMddFlowHullTest {

    private val eps = 1e-9

    @Test
    fun `a layer variable the model leaves open gets no flow hull`() {
        // The layer expansion screens values through each layer's root box and the flow rows then confine
        // the column to the arcs built, so an invented endpoint would refute accepted paths.
        val transitions = longArrayOf(
            0, 1, 0, 5, 0, 2, 0, 1,
            0, 1, 0, 5, 0, 2, 0, 1,
        )
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 100)),
            factors = arrayOf<Factor>(
                Mdd(
                    seq = intArrayOf(0, 1),
                    numStatesPerLayer = intArrayOf(1, 1, 1),
                    layerStarts = intArrayOf(0, 8, 16),
                    transitions = transitions,
                    initial = 0,
                    accepting = intArrayOf(0),
                    recordStride = 4,
                    cost = 2,
                ),
            ),
            openIntHi = booleanArrayOf(false, true, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 1))
        val hull = CpToLpRelaxation(p, obj, mddHull = true).build(PropagationSession(p))
        val bare = CpToLpRelaxation(p, obj, mddHull = false).build(PropagationSession(p))
        assertEquals(bare.model.m, hull.model.m, "no flow row over an open-sided layer variable")
        assertEquals(bare.model.n, hull.model.n, "and no arc column")
    }

    @Test
    fun `cost-MDD flow hull gives the exact minimum path cost`() {
        // 2 layers, single state each; value 1 costs 5, value 2 costs 1. Minimum-cost accepted path
        // takes value 2 twice -> cost 2. seq = vars 0,1; cost = var 2.
        val transitions = longArrayOf(
            0, 1, 0, 5, 0, 2, 0, 1, // layer 0 records (src,value,dst,weight)
            0, 1, 0, 5, 0, 2, 0, 1, // layer 1
        )
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 100)),
            factors = arrayOf<Factor>(
                Mdd(
                    seq = intArrayOf(0, 1),
                    numStatesPerLayer = intArrayOf(1, 1, 1),
                    layerStarts = intArrayOf(0, 8, 16),
                    transitions = transitions,
                    initial = 0,
                    accepting = intArrayOf(0),
                    recordStride = 4,
                    cost = 2,
                ),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(0, 0, 1)) // minimize the cost var
        val r = CpToLpRelaxation(p, obj, mddHull = true).build(PropagationSession(p))
        val sol = solveLp(r.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        assertEquals(2.0, sol.objectiveValue, eps, "minimum accepted-path cost is 2")
    }

}
