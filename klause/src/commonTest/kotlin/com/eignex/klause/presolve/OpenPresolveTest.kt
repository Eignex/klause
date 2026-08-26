package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntBounds
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Presolve for a model whose integer sides are open. The finite lane's passes read CP domains an open
 * column does not have; what this lane can be given is a proof of the bounds themselves.
 */
class OpenPresolveTest {

    /** `n` columns, all open above, lower bound 0. */
    private fun openAbove(n: Int, vararg factors: Factor): ProblemSpec {
        val openHi = Bits(n).also { bits -> repeat(n) { bits.set(it) } }
        return ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), null, openHi),
            factors = arrayOf(*factors),
        )
    }

    private fun row(vararg terms: Pair<Int, Long>, op: LinearOp, bound: Long) = Linear(
        LongArray(terms.size) { terms[it].second },
        IntArray(terms.size) { terms[it].first },
        op,
        bound,
    )

    @Test
    fun `an open side a row already implies is closed`() {
        // 0 <= x and x <= 7 leaves nothing open, and the bound is the row's.
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertEquals(1, result.closedSides)
        assertTrue(result.spec.intBounds.hasUpper(0), "the open side was proved")
        assertEquals(7L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `a model with no open side is handed back untouched`() {
        val spec = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(9), null, null),
            factors = arrayOf<Factor>(row(0 to 1L, op = LinearOp.LE, bound = 5L)),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertEquals(0, result.closedSides)
        assertTrue(result.spec === spec, "nothing to prove, so nothing is rebuilt")
    }

    @Test
    fun `a model contradictory over its open ranges is refuted rather than boxed`() {
        // x >= 0 with x <= -1: no solution anywhere in the unbounded model, not merely inside a box.
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = -1L))

        assertIs<OpenPresolveResult.Refuted>(spec.presolveOpen())
    }

    @Test
    fun `a side no row implies stays open`() {
        val spec = openAbove(2, row(0 to 1L, op = LinearOp.LE, bound = 4L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertTrue(result.spec.intBounds.hasUpper(0), "the constrained column closes")
        assertTrue(result.spec.intBounds.isOpenUpper(1), "the unconstrained one cannot be proved")
    }
}
