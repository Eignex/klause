package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenPresolveResultTest {
    private fun openAbove(n: Int, vararg factors: Factor): Problem {
        val openHi = Bits(n).also { bits -> repeat(n) { bits.set(it) } }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), null, openHi),
            factors = arrayOf(*factors),
        )
    }

    private fun fullyOpen(n: Int, vararg factors: Factor): Problem {
        val all = Bits(n).also { bits -> repeat(n) { bits.set(it) } }
        return Problem(
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
    fun `bound tightening should close an open side a row implies`() {
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(PresolveConfig.NONE))

        assertEquals(1, result.closedSides)
        assertTrue(result.spec.intBounds.hasUpper(0))
        assertEquals(7L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `dual fixing should close an open side before the tightening runs`() {
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertTrue(result.spec.intBounds.hasUpper(0))
        assertEquals(0L, result.spec.intBounds.lower(0))
        assertEquals(0L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `dual fixing should not pin a column away from its objective optimum`() {
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(objective = objective))

        assertEquals(0L, result.spec.intBounds.lower(0))
        assertEquals(7L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `a solution-set-sensitive caller should keep every column unpinned`() {
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(solutionSetSensitive = true))

        assertEquals(0L, result.spec.intBounds.lower(0))
        assertEquals(7L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `a model contradictory over its open ranges should be refuted`() {
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = -1L))

        assertIs<OpenPresolveResult.Refuted>(spec.presolveOpen())
    }

    @Test
    fun `source-safe factor passes should run before open bound tightening`() {
        val spec = fullyOpen(
            3,
            row(0 to 1L, 1 to -1L, 2 to -2L, op = LinearOp.EQ, bound = 0L),
            row(1 to 1L, 2 to 2L, op = LinearOp.LE, bound = 4L),
        )

        val aggregateOnly = PresolveConfig.parse("default,-symmetry,-probe-open")
        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(aggregateOnly))

        assertEquals(1, result.closedSides)
        val aggregate = assertIs<Linear>(result.spec.factors.last())
        assertTrue(aggregate.vars.contentEquals(intArrayOf(0)))
        assertEquals(4L, aggregate.integerConstants!!.bound)
        assertEquals(4L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `columns the equalities jointly determine should be closed`() {
        val spec = fullyOpen(
            2,
            row(0 to 1L, 1 to 1L, op = LinearOp.EQ, bound = 10L),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 4L),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertEquals(4, result.closedSides)
        assertEquals(7L, result.spec.intBounds.lower(0))
        assertEquals(7L, result.spec.intBounds.upper(0))
        assertEquals(3L, result.spec.intBounds.lower(1))
        assertEquals(3L, result.spec.intBounds.upper(1))
    }

    private fun jointlyDetermined(): Problem = fullyOpen(
        2,
        row(0 to 1L, 1 to 1L, op = LinearOp.EQ, bound = 10L),
        row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 4L),
    )

    @Test
    fun `a spent allowance should close no open side`() {
        val spent = PresolveBudget(0L).orSpent(Cancellation.Never)

        val result = assertIs<OpenPresolveResult.Tightened>(jointlyDetermined().closeOpenBounds(spent))

        assertEquals(0, result.closedSides)
    }

    @Test
    fun `a determined column past double precision should be closed exactly`() {
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
    fun `a side no row implies should stay open`() {
        val spec = openAbove(2, row(0 to 1L, op = LinearOp.LE, bound = 4L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertTrue(result.spec.intBounds.hasUpper(0))
        assertTrue(result.spec.intBounds.isOpenUpper(1))
    }

    private fun openBoxAndDeclaration(box: IntDomain, declared: IntDomain, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(box, declared),
        factors = arrayOf(*factors),
        openIntHi = booleanArrayOf(true, false),
    )

    @Test
    fun `an invented box endpoint should not cap a bound proved past it`() {
        val spec = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 6)),
            factors = arrayOf(row(0 to 1L, op = LinearOp.EQ, bound = 10L)),
            openIntHi = booleanArrayOf(true),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.closeOpenBounds())

        assertEquals(IntDomain(10, 10), result.spec.declaredIntDomains.finiteDomain(0))
    }

    @Test
    fun `a proved bound should intersect a declared value set`() {
        val spec = openBoxAndDeclaration(
            box = IntDomain(0, 6),
            declared = IntDomain(0, 9).excludeValue(5),
            row(0 to 1L, op = LinearOp.LE, bound = 4L),
            row(1 to 1L, op = LinearOp.LE, bound = 7L),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.closeOpenBounds())

        assertEquals(IntDomain(0, 7).excludeValue(5), result.spec.intDomainOrNull(1))
    }

    @Test
    fun `a proved range holding no declared value should refute the model`() {
        val spec = openBoxAndDeclaration(
            box = IntDomain(0, 6),
            declared = IntDomain(0, 9).excludeValues(longArrayOf(4, 5, 6))!!,
            row(0 to 1L, op = LinearOp.LE, bound = 4L),
            row(1 to 1L, op = LinearOp.GE, bound = 4L),
            row(1 to 1L, op = LinearOp.LE, bound = 6L),
        )

        assertIs<OpenPresolveResult.Refuted>(spec.closeOpenBounds())
    }

    @Test
    fun `a round closing no open side should keep the narrowing of a closed column`() {
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0),
                longArrayOf(0, 9),
                null,
                Bits(2).also { it.set(0) },
            ),
            factors = arrayOf<Factor>(row(1 to 1L, op = LinearOp.LE, bound = 4L)),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.closeOpenBounds())

        assertEquals(0, result.closedSides)
        assertEquals(4L, result.spec.intBounds.upper(1))
    }

    @Test
    fun `open bound tightening should read every row of an increasing chain`() {
        val spec = openAbove(
            3,
            Increasing(intArrayOf(0, 1, 2), strict = true),
            row(2 to 1L, op = LinearOp.LE, bound = 4L),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.closeOpenBounds())

        assertEquals(listOf(2L, 3L, 4L), (0 until 3).map { result.spec.intBounds.upper(it) })
    }
}
