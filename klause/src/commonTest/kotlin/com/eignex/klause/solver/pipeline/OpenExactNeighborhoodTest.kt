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
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenExactNeighborhoodTest {
    @Test
    fun `a refuted window retracts before a sibling witness`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val third = BigFraction.ofLong(3).reciprocal()

        val refused = adapter.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ZERO)),
            Cancellation.Never)
        val accepted = adapter.check(ExactNeighborhoodCondition(emptyMap(), mapOf(0 to third), mapOf(0 to third)),
            Cancellation.Never)

        assertIs<ExactNeighborhoodResult.Refuted>(refused)
        assertEquals(third, assertIs<ExactNeighborhoodResult.Witness>(accepted).sample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `a repair refutation preserves complete source feasibility`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(3.0), LinearOp.EQ, 1.0)), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val zero = mapOf(0 to BigFraction.ZERO)
        assertIs<ExactNeighborhoodResult.Refuted>(adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero),
            Cancellation.Never))

        val result = OpenTheoryEngine(model, model.componentPlan().theoryPipeline).solve()

        assertEquals("1/3", assertIs<OpenTheoryResult.Sat>(result).assignment.realValue(0))
        adapter.close()
    }

    @Test
    fun `strict objective cutoffs retract without changing source witnesses`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams(), objective)
        val zero = mapOf(0 to BigFraction.ZERO)

        val refused = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero, BigFraction.ZERO),
            Cancellation.Never)
        val accepted = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero, BigFraction.ONE),
            Cancellation.Never)
        val one = mapOf(0 to BigFraction.ONE)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), one, one), Cancellation.Never)

        assertIs<ExactNeighborhoodResult.Refuted>(refused)
        val acceptedSample = assertIs<ExactNeighborhoodResult.Witness>(accepted).sample
        assertEquals(BigFraction.ZERO, acceptedSample.exactReals?.single())
        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ONE, siblingSample.exactReals?.single())
        assertEquals(BigFraction.ZERO, acceptedSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `an unrepresentable condition retracts before a sibling query`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val huge = BigFraction.of(parseBigInt("1" + "0".repeat(1300)), BIG_ONE)

        val unknown = adapter.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to huge)),
            Cancellation.Never)
        val zero = mapOf(0 to BigFraction.ZERO)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

        assertIs<ExactNeighborhoodResult.Undecided>(unknown)
        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ZERO, siblingSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `nested conditions retract to their enclosing source scope`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams())

        adapter.withCondition(ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE), emptyMap()),
            Cancellation.Never) {
            assertIs<ExactNeighborhoodResult.Refuted>(adapter.check(
                ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ZERO)), Cancellation.Never))
            assertIs<ExactNeighborhoodResult.Witness>(adapter.check(
                ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ONE)), Cancellation.Never))
        }
        val zero = mapOf(0 to BigFraction.ZERO)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ZERO, siblingSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `closing one worker preserves a sibling worker and its witness`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val first = OpenExactNeighborhood(model, TheoryParams())
        val second = OpenExactNeighborhood(model, TheoryParams())
        val zero = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))
        val witness = assertIs<ExactNeighborhoodResult.Witness>(first.check(zero, Cancellation.Never)).sample

        first.close()

        assertFailsWith<IllegalStateException> { first.check(zero, Cancellation.Never) }
        assertIs<ExactNeighborhoodResult.Witness>(second.check(zero, Cancellation.Never))
        assertEquals(BigFraction.ZERO, witness.exactReals?.single())
        second.close()
    }

    @Test
    fun `cancellation after native work retracts the active bounds`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        assertIs<ExactNeighborhoodResult.Witness>(adapter.check(free, Cancellation.Never))
        val before = adapter.work

        val interrupted = adapter.check(ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE), emptyMap()),
            Cancellation { adapter.work > before + 1 })
        val zero = mapOf(0 to BigFraction.ZERO)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

        assertIs<ExactNeighborhoodResult.Undecided>(interrupted)
        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ZERO, siblingSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `an interrupted native initialization leaves a later source query available`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        var polls = 0

        val interrupted = adapter.check(free, Cancellation { ++polls >= 3 })
        val zero = mapOf(0 to BigFraction.ZERO)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

        assertIs<ExactNeighborhoodResult.Undecided>(interrupted)
        val sample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ZERO, sample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `interrupted initialization cannot renew the containing native allowance`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0, 1), doubleArrayOf(2.0, 3.0), LinearOp.EQ, 1.0)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams(openWorkLimit = 1))
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            val interrupted = it.check(free, Cancellation { it.work >= 1 })
            val sibling = it.check(free, Cancellation.Never)

            assertIs<ExactNeighborhoodResult.Undecided>(interrupted)
            assertIs<ExactNeighborhoodResult.Undecided>(sibling)
            assertEquals(1L, it.work)
        }
    }

    @Test
    fun `sibling queries cannot renew committed Boolean decisions`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        val adapter = OpenExactNeighborhood(model, TheoryParams(maxDecisions = 1))
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            assertIs<ExactNeighborhoodResult.Witness>(it.check(free, Cancellation.Never))
            val sibling = it.check(free, Cancellation.Never)

            assertIs<ExactNeighborhoodResult.Undecided>(sibling)
            assertEquals(1L, it.stats.openTheory.openBoolDecisions)
        }
    }

    @Test
    fun `native completion charges the current invocation meter`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0, 1), doubleArrayOf(2.0, 3.0), LinearOp.EQ, 1.0)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        var firstWork = 0L
        var siblingWork = 0L
        val first = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { firstWork += it }
        }
        val sibling = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { siblingWork += it }
        }
        val zero = mapOf(0 to BigFraction.ZERO)
        val half = mapOf(0 to BigFraction.ofLong(2).reciprocal())

        adapter.use {
            assertIs<ExactNeighborhoodResult.Witness>(it.check(
                ExactNeighborhoodCondition(emptyMap(), zero, zero), first))
            val firstCharge = firstWork
            assertTrue(firstCharge > 0)
            assertIs<ExactNeighborhoodResult.Witness>(it.check(
                ExactNeighborhoodCondition(emptyMap(), half, half), sibling))

            assertTrue(siblingWork > 0)
            assertEquals(firstCharge, firstWork)
        }
    }

    @Test
    fun `a sibling invocation cannot rearm containing cancellation`() {
        var cancelled = false
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams(cancellation = Cancellation { cancelled }))
        val zero = mapOf(0 to BigFraction.ZERO)
        val condition = ExactNeighborhoodCondition(emptyMap(), zero, zero)

        adapter.use {
            assertIs<ExactNeighborhoodResult.Witness>(it.check(condition, Cancellation.Never))
            val spent = it.work
            cancelled = true
            val sibling = it.check(condition, Cancellation.Never)

            assertIs<ExactNeighborhoodResult.Undecided>(sibling)
            assertEquals(spent, it.work)
        }
    }

    @Test
    fun `completed native source preparation survives interrupted initialization retries`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0, 1), doubleArrayOf(2.0, 3.0), LinearOp.EQ, 1.0)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            var preparedWork = 0L
            repeat(2) { attempt ->
                var operations = 0L
                val stop = object : Cancellation {
                    private val meter = WorkMeter { units -> if (adapter.sourcePrepared) operations += units }
                    override fun isCancelled(): Boolean = operations > 0
                    override fun workMeter(): WorkMeter = meter
                }
                val result = it.check(free, stop)

                assertIs<ExactNeighborhoodResult.Undecided>(result)
                assertTrue(operations > 0)
                assertTrue(it.sourcePrepared)
                if (attempt == 0) preparedWork = it.preparationWork
                else assertEquals(preparedWork, it.preparationWork)
            }
            val zero = mapOf(0 to BigFraction.ZERO)
            val sibling = it.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

            assertIs<ExactNeighborhoodResult.Witness>(sibling)
            assertTrue(preparedWork > 0)
            assertEquals(preparedWork, it.preparationWork)
        }
    }

    @Test
    fun `cancelled native source preparation withholds its factory before a sibling query`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0, 1), doubleArrayOf(2.0, 3.0), LinearOp.EQ, 1.0)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0), realUpper = doubleArrayOf(1.0, 1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            val interrupted = it.check(free, Cancellation { it.preparationWork > 0 })
            assertIs<ExactNeighborhoodResult.Undecided>(interrupted)
            assertFalse(it.sourcePrepared)
            val spent = it.preparationWork
            val zero = mapOf(0 to BigFraction.ZERO)
            val sibling = it.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), Cancellation.Never)

            assertIs<ExactNeighborhoodResult.Witness>(sibling)
            assertTrue(it.sourcePrepared)
            assertTrue(it.preparationWork > spent)
        }
    }

    @Test
    fun `empty model retries retain an exhausted native allowance`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val adapter = OpenExactNeighborhood(model, TheoryParams(maxLeaves = 1))
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        val first = adapter.check(free, Cancellation.Never)
        val second = adapter.check(free, Cancellation.Never)
        val third = adapter.check(free, Cancellation.Never)

        assertIs<ExactNeighborhoodResult.Witness>(first)
        assertIs<ExactNeighborhoodResult.Undecided>(second)
        assertIs<ExactNeighborhoodResult.Undecided>(third)
        assertEquals(1L, adapter.work)
        adapter.close()
    }

    @Test
    fun `a wide integer objective cutoff retains its exact threshold`() {
        val wide = parseBigInt("9223372036854775808")
        val open = Bits(1).also { it.set(0) }
        val model = Problem(0, intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide)))
        val adapter = OpenExactNeighborhood(model, TheoryParams(), LinearObjective(intCoefficients = longArrayOf(1)))
        val threshold = BigFraction.of(wide, BIG_ONE)

        val refused = adapter.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), threshold),
            Cancellation.Never)
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), threshold + BigFraction.ONE)
        val accepted = adapter.check(condition, Cancellation.Never)

        assertIs<ExactNeighborhoodResult.Refuted>(refused)
        assertEquals(wide, assertIs<ExactNeighborhoodResult.Witness>(accepted).sample.exactIntValue(0))
        adapter.close()
    }

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
