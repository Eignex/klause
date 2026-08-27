package com.eignex.klause.factor.circuit

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertTrue

class CircuitFactorTest {

    @Test
    fun `single node circuit is trivially satisfied`() {
        val circuit = Circuit(intArrayOf(0))
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 0)), listOf<Factor>(circuit))
        assertTrue(problem.baked !is PropagationResult.Unsat)
    }
}
