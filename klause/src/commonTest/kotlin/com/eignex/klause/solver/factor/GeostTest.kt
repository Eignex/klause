package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeostTest {

    @Test
    fun `kernel sweep tightens origin in axis when other dim is fully overlap-forced`() {
        // Two 2D boxes of size (2, 2) on a 5×5 grid.
        // Box 0 origin x fixed to 0 (singleton), origin y ∈ [0, 3].
        // Box 1 origin x ∈ [0, 0] (forced equal — they MUST overlap on x), origin y ∈ [0, 3].
        // Because the x-dim overlap is forced, the y-dim must avoid the mandatory-overlap
        // interval [b1.y.max + 1 − s0.y, b1.y.min + s1.y − 1] = [−1, 1]. So origin_0.y ≥ 2
        // and origin_1.y ≥ 2 (symmetric). The sweep should push both up.
        val factor = Geost(
            numDims = 2,
            numObjects = 2,
            origin = intArrayOf(0, 1, 2, 3), // (b0x, b0y, b1x, b1y) as variable ids
            length = intArrayOf(2, 2, 2, 2),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 0), // b0.x pinned to 0
                IntDomain(0, 3), // b0.y free
                IntDomain(0, 0), // b1.x pinned to 0 — forced x-overlap
                IntDomain(0, 0), // b1.y pinned to 0 (forces b0.y into M = [-1, 1] to overlap)
            ),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions.None)
        assertTrue(r is PropagationResult.Implied, "expected propagation success; got $r")
        // M_{0,1; y} = [b1.y.max + 1 − s0.y, b1.y.min + s1.y − 1] = [-1, 1] → b0.y must avoid → ≥ 2.
        assertEquals(2, r.intMinOrNullCompat(1), "b0.y.min should be pushed to 2 by kernel sweep")
    }

    @Test
    fun `forced multi-dim overlap is detected as infeasibility`() {
        // Two identical boxes pinned to same origin in every dim → must overlap → fail.
        val factor = Geost(
            numDims = 2,
            numObjects = 2,
            origin = intArrayOf(0, 1, 2, 3),
            length = intArrayOf(3, 3, 3, 3),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(0, 0),
                IntDomain(0, 0),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions.None)
        assertTrue(r is PropagationResult.Unsat, "co-located identical boxes must conflict; got $r")
    }
}
