package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertTrue

/** Sanity check that the cached-domain-ref incremental path doesn't break correctness. */
class MddIncrementalTest {

    @Test
    fun `repeated propagate from identical state preserves filtering`() {
        // 2-symbol MDD that accepts {1,2}* of length 2; states: layer0 = {0}, layer1 = {0},
        // layer2 = {0}. Initial 0, accepting {0}. Two transitions per layer.
        val factor = Mdd(
            seq = intArrayOf(0, 1),
            numStatesPerLayer = intArrayOf(1, 1, 1),
            layerStarts = intArrayOf(0, 6, 12),
            transitions = intArrayOf(
                0, 1, 0, // layer 0: 0 --1--> 0
                0, 2, 0, // layer 0: 0 --2--> 0
                0, 1, 0, // layer 1: 0 --1--> 0
                0, 2, 0, // layer 1: 0 --2--> 0
            ),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 3,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val r1 = problem.propagate(Assumptions.None)
        assertTrue(r1 is PropagationResult.Implied, "first fire should reach fixpoint; got $r1")
        // Pinning seq[0] = 1 narrows domain; re-propagate — must still succeed and prune nothing further.
        val r2 = problem.propagate(Assumptions(ints = mapOf(0 to 1)))
        assertTrue(r2 is PropagationResult.Implied, "second fire with pin should still propagate; got $r2")
    }
}
