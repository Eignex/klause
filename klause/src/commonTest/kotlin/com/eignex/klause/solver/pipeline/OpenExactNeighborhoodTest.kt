package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.collections.Bits
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenExactNeighborhoodTest {
    @Test
    fun `real min max completion should publish a checked original source point`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(RealArrayMinMax(2, intArrayOf(0, 1), true)),
            numRealVars = 3, realLower = doubleArrayOf(0.0, 1.0, 0.0), realUpper = doubleArrayOf(2.0, 3.0, 3.0))
        val adapter = OpenExactNeighborhood(source, TheoryParams(maxInstructions = 1000, randomSeed = 17))

        val result = adapter.use {
            it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), ExecutionControl.Never)
        }

        val point = assertIs<ExactNeighborhoodResult.Witness>(result).sample
        val coordinates = assertNotNull(point.exactReals)
        val maximum = if (coordinates[0] > coordinates[1]) coordinates[0] else coordinates[1]
        assertEquals(maximum, coordinates[2])
    }

    @Test
    fun `generated Boolean centers should respect required source root values`() {
        for (value in listOf(true, false)) {
            val source = Problem(
                1,
                0,
                emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, value)))),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(1.0),
            )
            val adapter = OpenExactNeighborhood(source, TheoryParams())

            val result = adapter.search(adaptive = false, maximumMoves = 1).use { search ->
                search.advance(ExecutionControl.Never, 1000)
                search.solveResult(TerminationReason.BudgetExhausted)
            }

            val sample = assertIs<SolveResult.Sat>(result).assignment
            assertEquals(value, sample.boolValue(0))
            assertEquals(BigFraction.ZERO, sample.exactReals?.single())
            assertEquals(1.0, result.stats.ls.completions.sum)
        }
    }

    @Test
    fun `explicit contrary Boolean pins should still refute after generated center preparation`() {
        val source = Problem(
            1,
            0,
            emptyArray(),
            arrayOf(Clause(intArrayOf(Lit.make(0, true)))),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val plan = source.componentPlan()
        val queries = RetainedLinearQueries(source, TheoryParams(), null) { stop, _ ->
            plan.search(source, emptyMap(), cancellation = stop)
        }

        queries.use {
            assertTrue(it.prepareBooleanCenter(ExecutionControl.Never))
            assertEquals(true, it.rootBooleanValue(0))
            val condition = ExactNeighborhoodCondition(mapOf(0 to false), emptyMap(), emptyMap())

            assertIs<ExactNeighborhoodResult.Refuted>(it.check(condition, ExecutionControl.Never))
            assertEquals(true, it.rootBooleanValue(0))
        }
    }

    @Test
    fun `generated Boolean centers should exclude retracted query assignments`() {
        val source = Problem(
            1,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val adapter = OpenExactNeighborhood(source, TheoryParams())
        val pinned = adapter.check(
            ExactNeighborhoodCondition(mapOf(0 to true), emptyMap(), emptyMap()),
            ExecutionControl.Never,
        )
        assertTrue(assertIs<ExactNeighborhoodResult.Witness>(pinned).sample.boolValue(0))

        val result = adapter.search(adaptive = false, maximumMoves = 1).use { search ->
            search.advance(ExecutionControl.Never, 1000)
            search.solveResult(TerminationReason.BudgetExhausted)
        }

        assertFalse(assertIs<SolveResult.Sat>(result).assignment.boolValue(0))
    }

    @Test
    fun `native neighborhood checking should keep the supplied runtime publication`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val plan = source.prepareExecutionPlan(objective)
        val invocation = EngineInvocation(plan, ModelIdentity.of(source, objective), objective)
        val adapter = OpenExactNeighborhood(
            source,
            TheoryParams(openWorkLimit = 1000),
            objective,
            plan,
            invocation = { invocation },
        )
        val third = BigFraction.ofLong(3).reciprocal()

        val result = adapter.use {
            it.check(
                ExactNeighborhoodCondition(
                    emptyMap(),
                    mapOf(0 to third),
                    mapOf(0 to third),
                ),
                ExecutionControl.Never,
            )
        }

        val sample = assertIs<ExactNeighborhoodResult.Witness>(result).sample
        val publication = assertIs<CompletedEnginePublication.Witness>(
            assertNotNull(sample.witnessCertificate).publication,
        )
        assertSame(invocation, publication.invocation)
        assertEquals(sample, publication.sample)
        assertEquals(third.toDouble(), publication.value)
    }

    @Test
    fun `unbound native neighborhood checking should withhold a runtime certificate`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())

        val result = OpenExactNeighborhood(source, TheoryParams()).use {
            it.check(ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap()), ExecutionControl.Never)
        }

        assertNull(assertIs<ExactNeighborhoodResult.Witness>(result).sample.witnessCertificate)
    }

    @Test
    fun `a refuted window should retract before a sibling witness`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val third = BigFraction.ofLong(3).reciprocal()

        val refused = adapter.check(
            ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ZERO)),
            ExecutionControl.Never,
        )
        val accepted = adapter.check(
            ExactNeighborhoodCondition(emptyMap(), mapOf(0 to third), mapOf(0 to third)),
            ExecutionControl.Never,
        )

        assertIs<ExactNeighborhoodResult.Refuted>(refused)
        assertEquals(third, assertIs<ExactNeighborhoodResult.Witness>(accepted).sample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `retained point reuse should reject an incompatible strict guard`() {
        val model = Problem(
            1, 0, emptyArray(),
            arrayOf(
                ReifiedRealLinear(
                    0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
                    LinearOp.LE, 0.0, strict = true,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(-1.0),
            realUpper = doubleArrayOf(1.0),
        )
        val fixed = mapOf(0 to BigFraction.ZERO)

        OpenExactNeighborhood(model, TheoryParams()).use { adapter ->
            val accepted = adapter.check(
                ExactNeighborhoodCondition(mapOf(0 to false), fixed, fixed), ExecutionControl.Never,
            )
            val refused = adapter.check(
                ExactNeighborhoodCondition(mapOf(0 to true), fixed, fixed), ExecutionControl.Never,
            )

            assertIs<ExactNeighborhoodResult.Refuted>(refused)
            val point = assertIs<ExactNeighborhoodResult.Witness>(accepted).sample
            assertFalse(point.boolValue(0))
            assertEquals(BigFraction.ZERO, point.exactReals?.single())
        }
    }

    @Test
    fun `strict objective cutoffs should retract without changing source witnesses`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val adapter = OpenExactNeighborhood(model, TheoryParams(), objective)
        val zero = mapOf(0 to BigFraction.ZERO)

        val refused = adapter.check(
            ExactNeighborhoodCondition(emptyMap(), zero, zero, BigFraction.ZERO),
            ExecutionControl.Never,
        )
        val accepted = adapter.check(
            ExactNeighborhoodCondition(emptyMap(), zero, zero, BigFraction.ONE),
            ExecutionControl.Never,
        )
        val one = mapOf(0 to BigFraction.ONE)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), one, one), ExecutionControl.Never)

        assertIs<ExactNeighborhoodResult.Refuted>(refused)
        val acceptedSample = assertIs<ExactNeighborhoodResult.Witness>(accepted).sample
        assertEquals(BigFraction.ZERO, acceptedSample.exactReals?.single())
        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ONE, siblingSample.exactReals?.single())
        assertEquals(BigFraction.ZERO, acceptedSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `an unrepresentable condition should retract before a sibling query`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val huge = BigFraction.of(parseBigInt("1" + "0".repeat(1300)), BIG_ONE)

        val unknown = adapter.check(
            ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to huge)),
            ExecutionControl.Never,
        )
        val zero = mapOf(0 to BigFraction.ZERO)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), ExecutionControl.Never)

        assertIs<ExactNeighborhoodResult.Undecided>(unknown)
        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ZERO, siblingSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `nested conditions should retract to their enclosing source scope`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams())

        adapter.withCondition(
            ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE), emptyMap()),
            ExecutionControl.Never,
        ) {
            assertIs<ExactNeighborhoodResult.Refuted>(
                adapter.check(
                    ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ZERO)),
                    ExecutionControl.Never,
                ),
            )
            assertIs<ExactNeighborhoodResult.Witness>(
                adapter.check(
                    ExactNeighborhoodCondition(emptyMap(), emptyMap(), mapOf(0 to BigFraction.ONE)),
                    ExecutionControl.Never,
                ),
            )
        }
        val zero = mapOf(0 to BigFraction.ZERO)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), ExecutionControl.Never)

        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ZERO, siblingSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `closing one worker should preserve a sibling worker and its witness`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val first = OpenExactNeighborhood(model, TheoryParams())
        val second = OpenExactNeighborhood(model, TheoryParams())
        val zero = ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ZERO), mapOf(0 to BigFraction.ZERO))
        val witness = assertIs<ExactNeighborhoodResult.Witness>(first.check(zero, ExecutionControl.Never)).sample

        first.close()

        assertFailsWith<IllegalStateException> { first.check(zero, ExecutionControl.Never) }
        assertIs<ExactNeighborhoodResult.Witness>(second.check(zero, ExecutionControl.Never))
        assertEquals(BigFraction.ZERO, witness.exactReals?.single())
        second.close()
    }

    @Test
    fun `cancellation after native work should retract the active bounds`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())
        assertIs<ExactNeighborhoodResult.Witness>(adapter.check(free, ExecutionControl.Never))
        val before = adapter.work

        val interrupted = adapter.check(
            ExactNeighborhoodCondition(emptyMap(), mapOf(0 to BigFraction.ONE), emptyMap()),
            ExecutionControl { adapter.work > before + 1 },
        )
        val zero = mapOf(0 to BigFraction.ZERO)
        val sibling = adapter.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), ExecutionControl.Never)

        assertIs<ExactNeighborhoodResult.Undecided>(interrupted)
        val siblingSample = assertIs<ExactNeighborhoodResult.Witness>(sibling).sample
        assertEquals(BigFraction.ZERO, siblingSample.exactReals?.single())
        adapter.close()
    }

    @Test
    fun `interrupted initialization should not renew the containing native allowance`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(2.0, 3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams(openWorkLimit = 1))
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            val interrupted = it.check(free, ExecutionControl { it.work >= 1 })
            val sibling = it.check(free, ExecutionControl.Never)

            assertIs<ExactNeighborhoodResult.Undecided>(interrupted)
            assertIs<ExactNeighborhoodResult.Undecided>(sibling)
            assertEquals(1L, it.work)
        }
    }

    @Test
    fun `sibling queries should not renew committed Boolean decisions`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        val adapter = OpenExactNeighborhood(model, TheoryParams(maxDecisions = 1))
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            assertIs<ExactNeighborhoodResult.Witness>(it.check(free, ExecutionControl.Never))
            val sibling = it.check(free, ExecutionControl.Never)

            assertIs<ExactNeighborhoodResult.Undecided>(sibling)
            assertEquals(1L, it.stats.openTheory.openBoolDecisions)
        }
    }

    @Test
    fun `native completion should charge the current invocation meter`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(2.0, 3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        var firstWork = 0L
        var siblingWork = 0L
        val first = object : ExecutionControl() {
            override fun isCancelled(): Boolean = false
            override val accounting: WorkMeter get() = WorkMeter { firstWork += it }
        }
        val sibling = object : ExecutionControl() {
            override fun isCancelled(): Boolean = false
            override val accounting: WorkMeter get() = WorkMeter { siblingWork += it }
        }
        val zero = mapOf(0 to BigFraction.ZERO)
        val half = mapOf(0 to BigFraction.ofLong(2).reciprocal())

        adapter.use {
            assertIs<ExactNeighborhoodResult.Witness>(
                it.check(
                    ExactNeighborhoodCondition(emptyMap(), zero, zero),
                    first,
                ),
            )
            val firstCharge = firstWork
            assertTrue(firstCharge > 0)
            assertIs<ExactNeighborhoodResult.Witness>(
                it.check(
                    ExactNeighborhoodCondition(emptyMap(), half, half),
                    sibling,
                ),
            )

            assertTrue(siblingWork > 0)
            assertEquals(firstCharge, firstWork)
        }
    }

    @Test
    fun `a sibling invocation should not rearm containing cancellation`() {
        var cancelled = false
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams(cancellation = ExecutionControl { cancelled }))
        val zero = mapOf(0 to BigFraction.ZERO)
        val condition = ExactNeighborhoodCondition(emptyMap(), zero, zero)

        adapter.use {
            assertIs<ExactNeighborhoodResult.Witness>(it.check(condition, ExecutionControl.Never))
            val spent = it.work
            cancelled = true
            val sibling = it.check(condition, ExecutionControl.Never)

            assertIs<ExactNeighborhoodResult.Undecided>(sibling)
            assertEquals(spent, it.work)
        }
    }

    @Test
    fun `completed native source preparation should survive interrupted initialization retries`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(2.0, 3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            var preparedWork = 0L
            repeat(2) { attempt ->
                var operations = 0L
                val stop = object : ExecutionControl() {
                    private val meter = WorkMeter { units -> if (adapter.sourcePrepared) operations += units }
                    override fun isCancelled(): Boolean = operations > 0
                    override val accounting: WorkMeter get() = meter
                }
                val result = it.check(free, stop)

                assertIs<ExactNeighborhoodResult.Undecided>(result)
                assertTrue(operations > 0)
                assertTrue(it.sourcePrepared)
                if (attempt == 0) {
                    preparedWork = it.preparationWork
                } else {
                    assertEquals(preparedWork, it.preparationWork)
                }
            }
            val zero = mapOf(0 to BigFraction.ZERO)
            val sibling = it.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), ExecutionControl.Never)

            assertIs<ExactNeighborhoodResult.Witness>(sibling)
            assertTrue(preparedWork > 0)
            assertEquals(preparedWork, it.preparationWork)
        }
    }

    @Test
    fun `cancelled native source preparation should withhold its factory before a sibling query`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0, 1),
                    doubleArrayOf(2.0, 3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(1.0, 1.0),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams())
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        adapter.use {
            val interrupted = it.check(free, ExecutionControl { it.preparationWork > 0 })
            assertIs<ExactNeighborhoodResult.Undecided>(interrupted)
            assertFalse(it.sourcePrepared)
            val spent = it.preparationWork
            val zero = mapOf(0 to BigFraction.ZERO)
            val sibling = it.check(ExactNeighborhoodCondition(emptyMap(), zero, zero), ExecutionControl.Never)

            assertIs<ExactNeighborhoodResult.Witness>(sibling)
            assertTrue(it.sourcePrepared)
            assertTrue(it.preparationWork > spent)
        }
    }

    @Test
    fun `empty model retries should keep an exhausted native allowance`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val adapter = OpenExactNeighborhood(model, TheoryParams(maxLeaves = 1))
        val free = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap())

        val first = adapter.check(free, ExecutionControl.Never)
        val second = adapter.check(free, ExecutionControl.Never)
        val third = adapter.check(free, ExecutionControl.Never)

        assertIs<ExactNeighborhoodResult.Witness>(first)
        assertIs<ExactNeighborhoodResult.Undecided>(second)
        assertIs<ExactNeighborhoodResult.Undecided>(third)
        assertEquals(1L, adapter.work)
        adapter.close()
    }

    @Test
    fun `a wide integer objective cutoff should keep its exact threshold`() {
        val wide = parseBigInt("9223372036854775808")
        val open = Bits(1).also { it.set(0) }
        val model = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide)),
        )
        val adapter = OpenExactNeighborhood(model, TheoryParams(), LinearObjective(intCoefficients = longArrayOf(1)))
        val threshold = BigFraction.of(wide, BIG_ONE)

        val refused = adapter.check(
            ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), threshold),
            ExecutionControl.Never,
        )
        val condition = ExactNeighborhoodCondition(emptyMap(), emptyMap(), emptyMap(), threshold + BigFraction.ONE)
        val accepted = adapter.check(condition, ExecutionControl.Never)

        assertIs<ExactNeighborhoodResult.Refuted>(refused)
        assertEquals(wide, assertIs<ExactNeighborhoodResult.Witness>(accepted).sample.exactIntValue(0))
        adapter.close()
    }

    @Test
    fun `real coordinate repair should preserve an exact fractional source witness`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(3.0),
                    LinearOp.EQ,
                    1.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val search = OpenExactNeighborhood(model, TheoryParams()).search(false, maximumMoves = 4)

        search.advance(ExecutionControl.Never, Long.MAX_VALUE)

        val result = assertIs<SolveResult.Sat>(search.solveResult(TerminationReason.BudgetExhausted))
        assertEquals("1/3", result.assignment.exactReals?.single().toString())
        assertTrue(result.stats.ls.completionsRefuted.sum > 0)
    }

    @Test
    fun `integer repair should publish a source coordinate beyond Long`() {
        val wide = parseBigInt("9223372036854775808")
        val open = Bits(1).also { it.set(0) }
        val model = Problem(
            0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), open, open),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(BIG_ONE), LinearOp.EQ, wide)),
        )
        val search = OpenExactNeighborhood(model, TheoryParams()).search(true, maximumMoves = 3)

        search.advance(ExecutionControl.Never, Long.MAX_VALUE)

        val result = assertIs<SolveResult.Sat>(search.solveResult(TerminationReason.BudgetExhausted))
        assertEquals(wide, result.assignment.exactIntValue(0))
        assertEquals(1.0, result.stats.ls.completions.sum)
    }

    @Test
    fun `continuous objective neighborhoods should keep witness only authority`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(2.0),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val search = OpenExactNeighborhood(model, TheoryParams(), objective).search(false, maximumMoves = 5)

        search.advance(ExecutionControl.Never, Long.MAX_VALUE)

        val result = assertIs<MinimizeResult.BestFound>(search.minimizeResult(TerminationReason.BudgetExhausted))
        assertTrue(objective.evaluateExact(result.sample) < BigFraction.ZERO)
        assertTrue(result.sample.exactReals?.single()!! <= BigFraction.ofLong(2))
    }
}
