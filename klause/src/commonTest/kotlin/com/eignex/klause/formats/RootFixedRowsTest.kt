package com.eignex.klause.formats

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovering the reified rows a unit clause decides. These become unconditional constraints, so the risk
 * runs one way only: a row claimed here that the model does not state would refute a satisfiable problem.
 * The negation cases carry that risk, which is why each is pinned separately.
 */
class RootFixedRowsTest {

    private fun unit(variable: Int, positive: Boolean) = Clause(intArrayOf(Lit.make(variable, positive)))

    private fun rows(vararg f: Factor) = rootFixedReifiedRows(f.toList())

    @Test
    fun `a literal fixed true yields the row itself`() {
        val r = rows(unit(0, true), ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5))
        assertEquals(1, r.size)
        assertEquals(LinearOp.LE, r[0].op)
        assertEquals(5L, checkNotNull(r[0].integerConstants).bound)
    }

    @Test
    fun `a literal fixed false yields the integer negation`() {
        // not (sum <= 5) is sum >= 6, which the Linear constructor canonicalises to -sum <= -6.
        val r = rows(unit(0, false), ReifiedLinear(0, intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 5))
        assertEquals(1, r.size)
        assertEquals(LinearOp.LE, r[0].op, "GE is canonicalised to LE")
        assertEquals(-6L, checkNotNull(r[0].integerConstants).bound)
        assertTrue(
            checkNotNull(r[0].integerConstants).coeffs.all { it == -1L },
            "the canonicalisation negates the coefficients",
        )
    }

    @Test
    fun `a negated lower bound shifts the other way`() {
        // not (sum >= 5) is sum <= 4.
        val r = rows(unit(0, false), ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 5))
        assertEquals(1, r.size)
        assertEquals(LinearOp.LE, r[0].op)
        assertEquals(4L, checkNotNull(r[0].integerConstants).bound)
    }

    @Test
    fun `a negated equality yields no row`() {
        // not (sum = 5) is a disequality, which states no interval; claiming one would be unsound.
        assertEquals(0, rows(unit(0, false), ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5)).size)
    }

    @Test
    fun `an unfixed literal yields no row`() {
        val r = rows(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
        )
        assertEquals(0, r.size, "a two-literal clause fixes nothing")
    }

    @Test
    fun `a literal fixed both ways yields no row`() {
        // Contradictory units make the model unsat on their own; picking a side here would assert a row
        // the model does not state.
        val r = rows(
            unit(0, true),
            unit(0, false),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 5),
        )
        assertEquals(0, r.size)
    }

    @Test
    fun `a negation whose bound would wrap yields no row`() {
        val r = rows(unit(0, false), ReifiedLinear(0, longArrayOf(1L), intArrayOf(0), LinearOp.LE, Long.MAX_VALUE))
        assertEquals(0, r.size, "a wrapped bound is a constraint the model never stated")
    }

    @Test
    fun `a plain linear factor is not mistaken for a reified one`() {
        assertEquals(0, rows(unit(0, true), Linear(longArrayOf(1L), intArrayOf(0), LinearOp.LE, 5L)).size)
    }
}
