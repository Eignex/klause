package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbsRealCompletionTest {
    @Test
    fun `real absolute value completion shares conditioned mixed product encodings`() {
        val source = Problem(0, 1, arrayOf(IntDomain(-1, 1)), arrayOf(
            RealProduct(0, 0, 2, -2.0, 2.0), RealAbs(0, 1),
        ), numRealVars = 3, realLower = doubleArrayOf(-2.0, 1.0, -1.0),
            realUpper = doubleArrayOf(2.0, 1.0, -1.0))

        val result = BacktrackSolver(source.bake()).solve()

        val witness = assertIs<SolveResult.Sat>(result).assignment
        val coordinates = assertNotNull(witness.exactReals)
        assertTrue(witness.intValue(0) in setOf(-1L, 1L))
        assertEquals(BigFraction.ofLong(-witness.intValue(0)), coordinates[0])
        assertEquals(BigFraction.ONE, coordinates[1])
        assertEquals(BigFraction.MINUS_ONE, coordinates[2])
    }

    @Test
    fun `local search real absolute value completion refutes only the proposed discrete point`() {
        val source = Problem(0, 1, arrayOf(IntDomain(-1, 1)), arrayOf(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
            RealAbs(0, 1),
        ), numRealVars = 2, realLower = doubleArrayOf(-2.0, 1.0), realUpper = doubleArrayOf(2.0, 1.0))
        val completion = LeafRealCompletion(source, null)

        val refuted = completion.complete(Sample(BooleanArray(0), longArrayOf(0)), Cancellation.Never)
        val sibling = completion.complete(Sample(BooleanArray(0), longArrayOf(-1)), Cancellation.Never)

        assertIs<Completion.Refuted>(refuted)
        assertTrue(refuted.work > 0L)
        val witness = assertIs<Completion.Witness>(sibling).sample
        assertEquals(listOf(BigFraction.MINUS_ONE, BigFraction.ONE), witness.exactReals)
    }

    @Test
    fun `finite real completion enforces the complete magnitude under fixed integer coordinates`() {
        for (integer in listOf(-1L, 0L, 1L)) {
            val source = Problem(0, 1, arrayOf(IntDomain(integer, integer)), arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                RealAbs(0, 1),
                Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
            ), numRealVars = 2, realLower = doubleArrayOf(-2.0, 0.0), realUpper = doubleArrayOf(2.0, 2.0))

            val result = BacktrackSolver(source.bake()).solve()

            if (integer == 0L) assertIs<SolveResult.Unsat>(result) else {
                val witness = assertIs<SolveResult.Sat>(result).assignment
                val coordinates = assertNotNull(witness.exactReals)
                assertEquals(BigFraction.ofLong(integer), coordinates[0])
                assertEquals(BigFraction.ONE, coordinates[1])
            }
        }
    }

    @Test
    fun `finite real completion searches private signs instead of accepting an arbitrary relaxation point`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealAbs(0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 1.0), realUpper = doubleArrayOf(2.0, 1.0))

        val result = BacktrackSolver(source.bake()).solve()

        val witness = assertIs<SolveResult.Sat>(result).assignment
        val coordinates = assertNotNull(witness.exactReals)
        assertTrue(coordinates[0] in listOf(BigFraction.ONE, BigFraction.MINUS_ONE))
        assertEquals(BigFraction.ONE, coordinates[1])
    }

    @Test
    fun `real absolute value completion retains the caller allowance and discards interrupted scopes`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealAbs(0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 1.0), realUpper = doubleArrayOf(2.0, 1.0))
        val completion = AbsRealCompletion(source)
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 7L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val interrupted = completion.complete(Sample(BooleanArray(0), LongArray(0)), stop)
        val sibling = completion.complete(Sample(BooleanArray(0), LongArray(0)), Cancellation.Never)

        assertIs<AbsRealCompletion.Result.Undecided>(interrupted.result)
        assertEquals(charged, interrupted.work)
        assertTrue(charged >= 7L)
        assertIs<AbsRealCompletion.Result.Witness>(sibling.result)
        assertTrue(sibling.work > 0L)
    }
}
