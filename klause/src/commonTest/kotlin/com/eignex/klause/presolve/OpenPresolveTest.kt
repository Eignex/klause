package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Factor
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

    /** `n` columns open on both sides, so nothing bounds them but the rows. */
    private fun fullyOpen(n: Int, vararg factors: Factor): ProblemSpec {
        val all = Bits(n).also { bits -> repeat(n) { bits.set(it) } }
        return ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                LongArray(n),
                LongArray(n),
                all,
                Bits(n).also { b ->
                    repeat(n) { b.set(it) }
                },
            ),
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
    fun `an equality whose bound is outside its coefficient lattice is refuted over open ranges`() {
        // 2x + 4y = 3 is feasible over the rationals and its columns are open both ways, so neither the
        // relaxation nor interval propagation concludes anything; gcd(2, 4) does not divide 3.
        val spec = fullyOpen(2, row(0 to 2L, 1 to 4L, op = LinearOp.EQ, bound = 3L))

        assertIs<OpenPresolveResult.Refuted>(spec.presolveOpen())
    }

    @Test
    fun `inequalities that no bound crosses are refuted by the relaxation`() {
        // x + y <= 5 with x + y >= 10. Every column is open, so no interval propagation moves a bound
        // and no pair ever crosses; the contradiction is only visible to a dual ray.
        val spec = fullyOpen(
            2,
            row(0 to 1L, 1 to 1L, op = LinearOp.LE, bound = 5L),
            row(0 to -1L, 1 to -1L, op = LinearOp.LE, bound = -10L),
        )

        assertIs<OpenPresolveResult.Refuted>(spec.presolveOpen())
    }

    @Test
    fun `a satisfiable equality over open ranges is not refuted`() {
        val spec = fullyOpen(2, row(0 to 2L, 1 to 4L, op = LinearOp.EQ, bound = 6L))

        assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())
    }

    @Test
    fun `columns the equalities jointly determine are closed`() {
        // Row by row both columns are unbounded; together the equalities fix x = 7 and y = 3.
        val spec = fullyOpen(
            2,
            row(0 to 1L, 1 to 1L, op = LinearOp.EQ, bound = 10L),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 4L),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertEquals(4, result.closedSides, "both sides of both columns are proved")
        assertEquals(7L, result.spec.intBounds.lower(0))
        assertEquals(7L, result.spec.intBounds.upper(0))
        assertEquals(3L, result.spec.intBounds.lower(1))
        assertEquals(3L, result.spec.intBounds.upper(1))
    }

    @Test
    fun `a determined column past double precision is closed exactly`() {
        // 2^62 + 1 has no exact double, so a relaxation reading these rows in floating point cannot
        // return the bound itself. x = 2^62 + 1 and y = 0 are what the equalities say.
        val k = (1L shl 62) + 1L
        val spec = fullyOpen(
            2,
            row(0 to 1L, 1 to 1L, op = LinearOp.EQ, bound = k),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = k),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertEquals(k, result.spec.intBounds.lower(0))
        assertEquals(k, result.spec.intBounds.upper(0))
        assertEquals(0L, result.spec.intBounds.lower(1))
        assertEquals(0L, result.spec.intBounds.upper(1))
    }

    @Test
    fun `a side no row implies stays open`() {
        val spec = openAbove(2, row(0 to 1L, op = LinearOp.LE, bound = 4L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertTrue(result.spec.intBounds.hasUpper(0), "the constrained column closes")
        assertTrue(result.spec.intBounds.isOpenUpper(1), "the unconstrained one cannot be proved")
    }

    @Test
    fun `a unit-asserted reified row is rounded in open presolve`() {
        val openHi = Bits(1).also { it.set(0) }
        val spec = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(1), longArrayOf(0), null, openHi),
            factors = arrayOf(
                Clause(intArrayOf(Lit.make(0, positive = true))),
                ReifiedLinear(0, longArrayOf(2), intArrayOf(0), LinearOp.LE, 1L),
            ),
        )

        assertIs<OpenPresolveResult.Refuted>(spec.presolveOpen())
    }
}
