package com.eignex.klause.lp

import com.eignex.klause.lp.engine.ExactBasisMetrics
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpCertifier
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactDoubleBoundedSplit
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceLpTest {
    @Test
    fun `scoped refinement observations reconcile with completed source work`() {
        val observations = ArrayList<ExactBasisMetrics>()
        val a = BigFraction.ofLong(1_000_000_007L)
        val b = BigFraction.ofLong(1_000_000_009L)
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), listOf(a, BigFraction.ONE), BigFraction.ONE),
            ExactRationalInequality(
                intArrayOf(0, 1), listOf(a.negated(), BigFraction.MINUS_ONE), BigFraction.MINUS_ONE,
            ),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, b), BigFraction.ONE),
            ExactRationalInequality(
                intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, b.negated()), BigFraction.MINUS_ONE,
            ),
        )
        val context = LpSolveContext(onRefinementBasisVerification = observations::add)
        val budget = SourceLpBudget(solveContext = { context })
        SourceLp(rows, 2, budget).use { source ->
            val result = assertNotNull(source.solve(Cancellation.Never))
            val refinement = assertNotNull(result.refinement)

            assertTrue(observations.isNotEmpty())
            assertTrue(refinement.luFactories > 0)
            assertEquals(refinement.luFactories, observations.sumOf { it.factoryCalls })
            assertEquals(refinement.luBuilds, observations.sumOf { it.builds })
            assertEquals(refinement.luReuse, observations.sumOf { it.reuse })
            assertEquals(refinement.luSolves, observations.sumOf { it.solves })
            assertEquals(refinement.luWork, observations.sumOf { it.work })
            assertTrue(assertNotNull(result.exactPrimal).satisfiesSourceRows(rows))
            val calls = observations.size
            val spent = budget.reservedWork
            assertNull(source.solve(Cancellation { true }))
            assertEquals(calls, observations.size)
            assertEquals(spent, budget.reservedWork)
        }
    }

    @Test
    fun `basis observations retain the source witness and cancellation spending`() {
        val observations = ArrayList<ExactBasisMetrics>()
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(3L)), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(-3L)), BigFraction.MINUS_ONE),
        )
        val context = LpSolveContext(
            certificationPolicy = LpCertificationPolicy { certifier, success ->
            success && certifier == LpCertifier.EXACT_BASIS
        }
        )
        val budget = SourceLpBudget(solveContext = { context }, onBasisVerification = observations::add)
        SourceLp(rows, 1, budget).use { source ->
            val result = assertNotNull(source.solve(Cancellation.Never))
            assertEquals(BigFraction.ofLong(3L).reciprocal(), result.exactPrimal?.first())
            assertTrue(observations.isNotEmpty())
            val calls = observations.size
            val spent = budget.reservedWork
            assertNull(source.solve(Cancellation { true }))
            assertEquals(calls, observations.size)
            assertEquals(spent, budget.reservedWork)
        }
    }

    @Test
    fun `equality subset retains both source rows and omits unequal and unpaired bounds`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(2L)), BigFraction.ofLong(2L), strict),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(-3L)), BigFraction.ofLong(-3L)),
                exactColumnUpper(0, BigFraction.ofLong(2L)),
                exactColumnUpper(1, BigFraction.ONE),
            )

            val subset = assertNotNull(sourceEqualityRows(rows, 2, SourceLpBudget(), Cancellation.Never))

            assertEquals(2, subset.size)
            assertSame(rows[0], subset[0])
            assertSame(rows[1], subset[1])
        }
    }

    @Test
    fun `a subset witness does not satisfy an omitted source constraint`() {
        val rows = listOf(
            exactColumnUpper(0, BigFraction.ZERO),
            exactColumnLower(0, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(), emptyList(), BigFraction.MINUS_ONE),
        )

        val subset = assertNotNull(sourceEqualityRows(rows, 1, SourceLpBudget(), Cancellation.Never))

        assertEquals(2, subset.size)
        assertTrue(listOf(BigFraction.ZERO).satisfiesSourceRows(subset))
        assertFalse(listOf(BigFraction.ZERO).satisfiesSourceRows(rows))
    }

    @Test
    fun `unequal opposing rows cannot become an equality subset`() {
        val rows = listOf(exactColumnUpper(0, BigFraction.ONE), exactColumnLower(0, BigFraction.ZERO))

        assertTrue(assertNotNull(sourceEqualityRows(rows, 1, SourceLpBudget(), Cancellation.Never)).isEmpty())
    }

    @Test
    fun `equality matching spending survives heavy admission exhaustion and cancellation`() {
        for (cancel in listOf(false, true)) {
            var cancelled = false
            val rows = listOf(exactColumnUpper(0, BigFraction.ONE), exactColumnLower(0, BigFraction.ONE))
            val budget = SourceLpBudget(maxOperations = if (cancel) 4 else 1, onWork = { cancelled = cancel })
            val token = Cancellation { cancelled }
            val subset = assertNotNull(sourceEqualityRows(rows, 1, budget, token))
            val spent = budget.reservedWork
            val allocated = budget.reservedAllocation

            assertIs<ExactDoubleBoundedSplit.Unknown>(sourceDoubleBoundedSplit(subset, 1, budget, token))

            assertEquals(1, budget.operations)
            assertEquals(spent, budget.reservedWork)
            assertEquals(allocated, budget.reservedAllocation)
            assertTrue(spent > 0L)
            assertTrue(allocated > 0L)
        }
    }

    @Test
    fun `scaled opposite source rows imply bounds without claiming attainment`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ofLong(2L)), BigFraction.ofLong(8L)),
                ExactRationalInequality(
                    intArrayOf(0),
                    listOf(BigFraction.ofLong(-3L)),
                    BigFraction.ofLong(-6L),
                    strict,
                ),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            )
            val budget = SourceLpBudget(solveContext = { error("paired rows cannot allocate an LP owner") })

            val split = assertIs<ExactDoubleBoundedSplit.Split>(
                sourceDoubleBoundedSplit(rows, 2, budget, Cancellation.Never),
            )

            assertEquals(
                listOf(BigFraction.ofLong(4L), BigFraction.ofLong(-12L), BigFraction.ofLong(-4L)),
                split.bounded.map { it.lower },
            )
            assertTrue(split.unbounded.isEmpty())
            assertEquals(1, budget.operations)
            assertTrue(budget.reservedWork > 0L)
            assertTrue(budget.reservedAllocation > 0L)
            split.bounded.forEachIndexed { index, row ->
                assertEquals(index, row.index)
                assertSame(rows[index], row.inequality)
            }
            for (value in 0L..5L) {
                val point = listOf(BigFraction.ofLong(value), BigFraction.ZERO)
                if (point.satisfiesSourceRows(rows)) {
                    for (row in split.bounded) {
                        assertTrue(row.lower <= point[0] * row.inequality.coefficients.single())
                    }
                }
            }
        }
    }

    @Test
    fun `unpaired rows retain the common cone check`() {
        val rows = listOf(exactColumnUpper(0, BigFraction.ONE))
        val budget = SourceLpBudget()

        val split = assertIs<ExactDoubleBoundedSplit.Split>(
            sourceDoubleBoundedSplit(rows, 2, budget, Cancellation.Never),
        )

        assertEquals(listOf(0), split.unbounded)
        assertTrue(split.bounded.isEmpty())
        assertTrue(budget.operations > 1)
        assertTrue(budget.measuredPreparationWork > 0L)
        assertTrue(budget.measuredFloatWork > 0L)
    }

    @Test
    fun `crossed opposite bounds do not publish an unexplained contradiction`() {
        val rows = listOf(exactColumnUpper(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ONE))

        assertIs<ExactDoubleBoundedSplit.Unknown>(
            sourceDoubleBoundedSplit(rows, 1, SourceLpBudget(), Cancellation.Never),
        )
    }

    @Test
    fun `constant source rows retain strictness and exact zero activity`() {
        for (strict in listOf(false, true)) {
            val row = ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ZERO), BigFraction.ZERO, strict)

            val split = assertIs<ExactDoubleBoundedSplit.Split>(
                sourceDoubleBoundedSplit(listOf(row), 1, SourceLpBudget(), Cancellation.Never),
            )

            assertSame(row, split.bounded.single().inequality)
            assertEquals(BigFraction.ZERO, split.bounded.single().lower)
        }
    }

    @Test
    fun `failed and cancelled source operations report spending once`() {
        for (throws in listOf(false, true)) {
            val sink = SmtStatsSink()
            val budget = SourceLpBudget(onWork = sink::observeSourceLp)
            var cancelled = false
            runCatching {
                budget.run(emptyList(), 1, Cancellation { cancelled }) {
                    budget.observe(preparation = 7L, floatWork = 11L, continuation = 13L, refinement = 17L)
                    if (throws) error("source operation")
                    cancelled = true
                    true
                }
            }
            budget.closeOwner(AutoCloseable {})
            budget.closeOwner(AutoCloseable {})

            val reported = sink.snapshot().sourceLp
            assertEquals(1L, reported.operations)
            assertEquals(budget.reservedWork, reported.modeledWork)
            assertEquals(budget.reservedAllocation, reported.modeledAllocation)
            assertEquals(budget.activeNanos, reported.activeNs)
            assertEquals(7L, reported.preparationWork)
            assertEquals(11L, reported.floatWork)
            assertEquals(13L, reported.continuationWork)
            assertEquals(17L, reported.refinementWork)
        }
    }

    @Test
    fun `restoring original right hand side cannot reuse a changed conflict`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ONE))
        SourceLp(rows, 1, SourceLpBudget()).use { owner ->
            val changed = assertNotNull(
                owner.solve(Cancellation.Never, rhs = listOf(BigFraction.ZERO, BigFraction.MINUS_ONE)),
            )
            assertEquals(LpVerdict.INFEASIBLE, changed.verdict)

            val restored = assertNotNull(owner.solve(Cancellation.Never))

            assertNotNull(restored.exactPrimal)
            assertTrue(assertNotNull(restored.exactPrimal).satisfiesSourceRows(rows))
        }
    }

    @Test
    fun `source row mutation cannot alter an admitted owner`() {
        val coefficients = mutableListOf(BigFraction.ONE)
        val row = ExactRationalInequality(intArrayOf(0), coefficients, BigFraction.ONE)
        SourceLp(listOf(row), 1, SourceLpBudget()).use { owner ->
            assertNotNull(owner.solve(Cancellation.Never, activity = 0))
            coefficients[0] = BigFraction.MINUS_ONE

            val result = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnLower(0, BigFraction.ofLong(2L)))),
            )

            assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        }
    }

    @Test
    fun `opposite singleton branch sides do not survive scope restoration`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ZERO), exactColumnUpper(0, BigFraction.ONE))
        SourceLp(rows, 1, SourceLpBudget()).use { owner ->
            val lower = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnLower(0, BigFraction.ONE))),
            )
            assertEquals(BigFraction.ONE, assertNotNull(lower.exactPrimal).first())

            val upper = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnUpper(0, BigFraction.ZERO))),
            )

            assertEquals(BigFraction.ZERO, assertNotNull(upper.exactPrimal).first())
        }
    }

    @Test
    fun `repeated branch forms reuse definitions without retaining their thresholds`() {
        val rows = listOf(exactColumnLower(0, BigFraction.ZERO), exactColumnLower(1, BigFraction.ZERO))
        val branch = ExactRationalInequality(
            intArrayOf(0, 1),
            listOf(BigFraction.ONE, BigFraction.ONE),
            BigFraction.ONE,
        )
        SourceLp(rows, 2, SourceLpBudget()).use { owner ->
            repeat(3) { assertNotNull(owner.solve(Cancellation.Never, branches = listOf(branch))) }

            val result = assertNotNull(
                owner.solve(Cancellation.Never, branches = listOf(exactColumnLower(0, BigFraction.ofLong(2L)))),
            )

            assertTrue(assertNotNull(result.exactPrimal).first() >= BigFraction.ofLong(2L))
        }
    }

    @Test
    fun `source family reservations survive replacement owners`() {
        val budget = SourceLpBudget(maxOperations = 1)
        SourceLp(emptyList(), 1, budget).use { assertNotNull(it.solve(Cancellation.Never)) }

        SourceLp(emptyList(), 1, budget).use { assertNull(it.solve(Cancellation.Never)) }

        assertEquals(1, budget.operations)
        assertTrue(budget.reservedWork > 0L)
        assertTrue(budget.reservedAllocation > 0L)
    }

    @Test
    fun `closing every owner preserves primary and suppressed failures`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        var closed = 0
        val failure = kotlin.test.assertFailsWith<IllegalStateException> {
            closeSourceLpOwners(
                listOf(
                    AutoCloseable {
                        closed++
                        throw first
                    },
                    AutoCloseable {
                        closed++
                        throw second
                    },
                ),
            )
        }

        assertEquals(first, failure)
        assertEquals(listOf(second), failure.suppressedExceptions.toList())
        assertEquals(2, closed)
    }
}
