package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuccessorCycleFactorTest {

    @Test
    fun `n equals number of successor variables`() {
        val circuit = Circuit(intArrayOf(0, 1, 2, 3))
        assertEquals(4, circuit.n)
    }

    @Test
    fun `boolVars is empty for circuit`() {
        val circuit = Circuit(intArrayOf(0, 1, 2))
        assertTrue(circuit.boolVars.isEmpty())
    }

    @Test
    fun `intVars equals the successor array`() {
        val succ = intArrayOf(0, 1, 2)
        val circuit = Circuit(succ)
        assertTrue(circuit.intVars.contentEquals(succ))
    }

    @Test
    fun `single node circuit is trivially satisfied`() {
        val circuit = Circuit(intArrayOf(0))
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 0)), listOf<Factor>(circuit))
        assertTrue(problem.baked !is PropagationResult.Unsat)
    }
}
