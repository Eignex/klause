package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenPresolveTest {

    /** `n` columns, all open above, lower bound 0. */
    private fun openAbove(n: Int, vararg factors: Factor): Problem {
        val openHi = Bits(n).also { bits -> repeat(n) { bits.set(it) } }
        return Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(LongArray(n), LongArray(n), null, openHi),
            factors = arrayOf(*factors),
        )
    }

    /** `n` columns open on both sides, so nothing bounds them but the rows. */
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
    fun `an open side a row already implies is closed`() {
        // 0 <= x and x <= 7 leaves nothing open, and the bound is the row's. Source passes are off so
        // the bound is the tightening's: dual fixing would otherwise pin this column first, which the
        // pair of tests below covers.
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(PresolveConfig.NONE))

        assertEquals(1, result.closedSides)
        assertTrue(result.spec.intBounds.hasUpper(0), "the open side was proved")
        assertEquals(7L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `a source pin closes an open side before the tightening runs`() {
        // Dual fixing reaches this column without solving anything: it occurs only as `+x` in a `<=`
        // row, so lowering never violates, and with no objective an optimum sits at its lower bound.
        // The pin closes the open side, which is the cheaper of the two ways off the open route.
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen())

        assertTrue(result.spec.intBounds.hasUpper(0), "the source pin closed the side")
        assertEquals(0L, result.spec.intBounds.lower(0))
        assertEquals(0L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `a stated objective keeps a column off the bound its optimum sits away from`() {
        // Maximizing x over `x <= 7` puts the optimum at 7, stated here as the minimize objective -x.
        // Lowering x never violates the row, so with no objective dual fixing pins it to 0 — an optimum
        // only by accident. The coefficient is what holds the pin back.
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(objective = objective))

        assertEquals(0L, result.spec.intBounds.lower(0))
        assertEquals(7L, result.spec.intBounds.upper(0), "the optimum stays reachable")
    }

    @Test
    fun `a solution-set-sensitive caller keeps every column unpinned`() {
        // Counting has to see the whole set, so the passes that discard optimum-equivalent solutions are
        // held back and the bound the tightening proves is the only narrowing.
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = 7L))

        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(solutionSetSensitive = true))

        assertEquals(0L, result.spec.intBounds.lower(0))
        assertEquals(7L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `a model contradictory over its open ranges is refuted rather than boxed`() {
        // x >= 0 with x <= -1: no solution anywhere in the unbounded model, not merely inside a box.
        val spec = openAbove(1, row(0 to 1L, op = LinearOp.LE, bound = -1L))

        assertIs<OpenPresolveResult.Refuted>(spec.presolveOpen())
    }

    @Test
    fun `source-safe factor passes run before open bound tightening`() {
        // z = x + 2y lets the aggregate pass replace x + 2y <= 4 with z <= 4 without requiring a
        // finite CP domain for any column. The rewritten row then closes z's upper side.
        val spec = fullyOpen(
            3,
            row(0 to 1L, 1 to -1L, 2 to -2L, op = LinearOp.EQ, bound = 0L),
            row(1 to 1L, 2 to 2L, op = LinearOp.LE, bound = 4L),
        )

        // Ordering the interchangeable y and z, or probing the rows, would close sides too; this test is
        // about the aggregate.
        val aggregateOnly = PresolveConfig.parse("default,-symmetry,-probe-open")
        val result = assertIs<OpenPresolveResult.Tightened>(spec.presolveOpen(aggregateOnly))

        assertEquals(1, result.closedSides)
        val aggregate = assertIs<Linear>(result.spec.factors.last())
        assertTrue(aggregate.vars.contentEquals(intArrayOf(0)))
        assertEquals(4L, aggregate.integerConstants!!.bound)
        assertEquals(4L, result.spec.intBounds.upper(0))
    }

    @Test
    fun `an LP refutation records standalone route work`() {
        val spec = fullyOpen(
            2,
            row(0 to 1L, 1 to 1L, op = LinearOp.LE, bound = 5L),
            row(0 to -1L, 1 to -1L, op = LinearOp.LE, bound = -10L),
        )
        val sink = LpStatsSink()

        spec.closeOpenBounds(lpStats = sink)

        val stats = sink.snapshot()
        assertTrue(
            stats.standalonePasses.sum + stats.componentPasses.sum > 0.0,
            "the LP solve that refuted the open model must be reported",
        )
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

    private fun jointlyDetermined(): Problem = fullyOpen(
        2,
        row(0 to 1L, 1 to 1L, op = LinearOp.EQ, bound = 10L),
        row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 4L),
    )

    @Test
    fun `a spent allowance closes no open side`() {
        val spent = PresolveBudget(0L).orSpent(Cancellation.Never)

        val result = assertIs<OpenPresolveResult.Tightened>(jointlyDetermined().closeOpenBounds(spent))

        assertEquals(0, result.closedSides)
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

    /** Column 0 open above with the box [box]; column 1 closed, declaring [declared]. */
    private fun openBoxAndDeclaration(box: IntDomain, declared: IntDomain, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(box, declared),
        factors = arrayOf(*factors),
        openIntHi = booleanArrayOf(true, false),
    )

    @Test
    fun `an invented box endpoint does not cap a bound proved past it`() {
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
    fun `a proved bound intersects a declared value set rather than replacing it`() {
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
    fun `a proved range holding no declared value refutes the model`() {
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
    fun `a round that closes no open side keeps the narrowing it proved for a closed one`() {
        // Column 0 is open above and no row mentions it, so nothing closes; x1 <= 4 still narrows the
        // declared range of column 1.
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
    fun `open bound tightening reads every row of an increasing chain`() {
        val spec = openAbove(
            3,
            Increasing(intArrayOf(0, 1, 2), strict = true),
            row(2 to 1L, op = LinearOp.LE, bound = 4L),
        )

        val result = assertIs<OpenPresolveResult.Tightened>(spec.closeOpenBounds())

        assertEquals(listOf(2L, 3L, 4L), (0 until 3).map { result.spec.intBounds.upper(it) })
    }
}
