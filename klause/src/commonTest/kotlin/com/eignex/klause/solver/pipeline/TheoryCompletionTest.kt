package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.Bits
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TheoryCompletionTest {

    // 0 < x < 1 over an open continuous x: both rows strict.
    private val model = Problem(
        numBoolVars = 0,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0, strict = true),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, 0.0, strict = true),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
        realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
    )

    @Test
    fun `completion preserves a pinned integer beyond Long`() {
        val wide = parseBigInt("9223372036854775808")
        val open = Bits(1).also { it.set(0) }
        val source = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val completion = TheoryCompletion(source, TheoryParams())
        val candidate = Sample(booleanArrayOf(), longArrayOf(), exactInts = listOf(wide))

        val result = completion.complete(candidate, Cancellation.Never)

        assertEquals(wide, assertIs<Completion.Witness>(result).sample.exactIntValue(0))
        completion.close()
    }

    @Test
    fun `candidate retries share their native check allowance`() {
        val completion = TheoryCompletion(Problem(0, 0, emptyArray(), emptyArray()), TheoryParams(maxLeaves = 1))
        val candidate = Sample(booleanArrayOf(), longArrayOf())

        val first = completion.complete(candidate, Cancellation.Never)
        val second = completion.complete(candidate, Cancellation.Never)

        assertIs<Completion.Witness>(first)
        assertIs<Completion.Undecided>(second)
        assertEquals(1L, first.work)
        assertEquals(1L, second.work)
        completion.close()
    }

    @Test
    fun `a candidate over strict rows completes to an exact point strictly inside them`() {
        val candidate = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(0.5))

        val completion = TheoryCompletion(model, TheoryParams()).complete(candidate, Cancellation.Never)

        val x = assertNotNull(assertIs<Completion.Witness>(completion).sample.exactReals)[0]
        assertTrue(x > BigFraction.ZERO && x < BigFraction.ONE, "x=$x")
    }

    @Test
    fun `a pinned Boolean completion preserves strict source rows`() {
        val guarded = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            numRealVars = 1,
            realLower = model.realLower,
            realUpper = model.realUpper,
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.GE, 1.0)))
        val candidate = Sample(booleanArrayOf(false), LongArray(0), doubleArrayOf(0.0))

        val completion = TheoryCompletion(guarded, TheoryParams()).complete(candidate, Cancellation.Never)

        val sample = assertIs<Completion.Witness>(completion).sample
        assertEquals(false, sample.bools.single())
        val x = assertNotNull(sample.exactReals).single()
        assertTrue(x > BigFraction.ZERO && x < BigFraction.ONE)
    }

    @Test
    fun `an inconsistent pinned Boolean completion is refuted`() {
        val guarded = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            numRealVars = 1,
            realLower = model.realLower,
            realUpper = model.realUpper,
            factors = arrayOf(
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.LE, 0.0),
                ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0),
                    doubleArrayOf(1.0), LinearOp.GE, 1.0)))
        val candidate = Sample(booleanArrayOf(true), LongArray(0), doubleArrayOf(0.0))

        val completion = TheoryCompletion(guarded, TheoryParams()).complete(candidate, Cancellation.Never)

        assertIs<Completion.Refuted>(completion)
    }
}
