package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.table.Regular
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
 * #655 (Tranche A): the layer-expanded DFA flow hull of [Regular]. The flow polytope is integral, so
 * the LP optimum of a linear objective over the sequence must **equal** the true optimum over the
 * automaton's accepting strings — checked against brute force. (Equality ⇒ both sound — never below
 * the true optimum — and tight.)
 */
class CpToLpRelaxationRegularFlowHullTest {

    private val eps = 1e-9

    @Test
    fun `a sequence position the model leaves open gets no flow hull`() {
        // The layer expansion screens symbols through each position's root box and the flow rows then
        // confine the column to the arcs built, so an invented endpoint would refute accepted strings.
        val trans = intArrayOf(1, 2, 2, 1)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                Regular(
                    intArrayOf(0, 1, 2),
                    2,
                    2,
                    LongArray(trans.size) { trans[it].toLong() },
                    q0 = 1,
                    accepting = intArrayOf(1),
                ),
            ),
            openIntHi = booleanArrayOf(false, true, false),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val hull = CpToLpRelaxation(p, obj, regularHull = true).build(PropagationSession(p))
        val bare = CpToLpRelaxation(p, obj, regularHull = false).build(PropagationSession(p))
        assertEquals(bare.model.m, hull.model.m, "no flow row over an open-sided position")
        assertEquals(bare.model.n, hull.model.n, "and no arc column")
    }

    @Test
    fun `flow hull gives the exact optimum on an even-count DFA`() {
        // 2 states, alphabet {1,2}: state tracks parity of symbol 2; accept even count. δ table row-major.
        val trans = intArrayOf(1, 2, 2, 1) // δ(1,1)=1 δ(1,2)=2 δ(2,1)=2 δ(2,2)=1
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(
                Regular(
                    intArrayOf(0, 1, 2),
                    2,
                    2,
                    LongArray(trans.size) { trans[it].toLong() },
                    q0 = 1,
                    accepting = intArrayOf(1),
                ),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1)) // minimize Σ seq
        val r = CpToLpRelaxation(p, obj, regularHull = true).build(PropagationSession(p))
        val sol = solveLp(r.model)
        assertEquals(FloatLpStatus.OPTIMAL, sol.status)
        // Cheapest accepted string is 1,1,1 (zero 2s, even), Σ = 3.
        assertEquals(3.0, sol.objectiveValue, eps)
    }

}
