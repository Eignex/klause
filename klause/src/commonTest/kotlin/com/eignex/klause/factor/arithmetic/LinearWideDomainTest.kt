package com.eignex.klause.factor.arithmetic

import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertTrue

class LinearWideDomainTest {

    private fun wideProblem(): Problem {
        val wide = 1L shl 62
        return Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(-wide, wide), IntDomain(-wide, wide)),
            factors = arrayOf<Factor>(
                Linear(longArrayOf(1L shl 33, 1L shl 33), intArrayOf(0, 1), LinearOp.LE, 10L),
            ),
        )
    }

    @Test
    fun `a violated assignment is rejected when the row sum overflows 64 bits`() {
        val session = PropagationSession(wideProblem())
        assertTrue(session.pinInt(0, 1L shl 33) !is PropagationResult.Unsat)
        // Both pinned: the true sum is 2^67, far above the bound, but wraps in 64-bit arithmetic.
        assertTrue(session.pinInt(1, 1L shl 33) is PropagationResult.Unsat)
    }

    @Test
    fun `a satisfying assignment passes when the row bounds overflow 64 bits`() {
        val session = PropagationSession(wideProblem())
        assertTrue(session.pinInt(0, 1L shl 40) !is PropagationResult.Unsat)
        assertTrue(session.pinInt(1, -(1L shl 40)) !is PropagationResult.Unsat)
    }
}
