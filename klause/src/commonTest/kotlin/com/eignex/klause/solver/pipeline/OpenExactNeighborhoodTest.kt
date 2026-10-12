package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenExactNeighborhoodTest {
    @Test
    fun `real coordinate repair preserves an exact fractional source witness`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val search = OpenExactNeighborhood(model, TheoryParams()).search(false, maximumMoves = 4)

        search.advance(Cancellation.Never, Long.MAX_VALUE)

        val result = assertIs<SolveResult.Sat>(search.solveResult(TerminationReason.BudgetExhausted))
        assertEquals("1/3", result.assignment.exactReals?.single().toString())
        assertTrue(result.stats.ls.completionsRefuted.sum > 0)
    }

    @Test
    fun `integer repair can publish a source coordinate beyond Long`() {
        val wide = parseBigInt("9223372036854775808")
        val open = Bits(1).also { it.set(0) }
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide)))
        val search = OpenExactNeighborhood(model, TheoryParams()).search(true, maximumMoves = 3)

        search.advance(Cancellation.Never, Long.MAX_VALUE)

        val result = assertIs<SolveResult.Sat>(search.solveResult(TerminationReason.BudgetExhausted))
        assertEquals(wide, result.assignment.exactIntValue(0))
        assertEquals(1.0, result.stats.ls.completions.sum)
    }

    @Test
    fun `continuous objective neighborhoods retain witness-only authority`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val search = OpenExactNeighborhood(model, TheoryParams(), objective).search(false, maximumMoves = 5)

        search.advance(Cancellation.Never, Long.MAX_VALUE)

        val result = assertIs<MinimizeResult.BestFound>(search.minimizeResult(TerminationReason.BudgetExhausted))
        assertTrue(objective.evaluateExact(result.sample) < BigFraction.ZERO)
        assertTrue(result.sample.exactReals?.single()!! <= BigFraction.ofLong(2))
    }
}
