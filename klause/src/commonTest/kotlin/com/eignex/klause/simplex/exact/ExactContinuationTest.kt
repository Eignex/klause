package com.eignex.klause.simplex.exact

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactContinuationTest {
    @Test
    fun `cancelled coupled pivots should resume a complete exact assignment`() {
        val n = 8
        val input = ExactContinuationInput(
            List(n) { column ->
                List(n) { row -> row to BigFraction.ofLong(if (row == column) 2L else 1L) }
            },
            List(n) { BigFraction.ofLong(n + 1L) },
            List(2 * n) { BigFraction.ZERO },
            List(2 * n) { if (it < n) null else BigFraction.ZERO },
            List(n) { n + it },
            List(2 * n) { if (it < n) ContinuationStatus.LOWER else ContinuationStatus.BASIC },
        )
        for (stopAt in listOf(256, 512, 1024, 2048)) {
            val session = ExactContinuation(input)
            var polls = 0

            val interrupted = session.resume(cancellation = Cancellation { ++polls >= stopAt })
            val resumed = session.resume()

            assertEquals(ContinuationDecline.CANCELLED, interrupted.metrics.decline)
            assertNull(interrupted.values)
            assertNull(interrupted.ray)
            assertEquals(List(2 * n) { if (it < n) BigFraction.ONE else BigFraction.ZERO },
                assertNotNull(resumed.values))
            assertNull(resumed.ray)
            assertNull(resumed.metrics.decline)
            assertEquals(interrupted.metrics.work + resumed.metrics.work, session.usedWork)
        }
    }

    @Test
    fun `independent equality pivots should finish within their allocation allowance`() {
        val n = 32
        val huge = BigFraction.of(BIG_ONE shl 130, BIG_ONE)
        val limits = ExactContinuationLimits(maxAllocation = 800_000)
        for (target in listOf(BigFraction.ONE, huge)) {
            val input = ExactContinuationInput(
                List(n) { listOf(it to BigFraction.ONE) },
                List(n) { target },
                List(2 * n) { BigFraction.ZERO },
                List(2 * n) { if (it < n) null else BigFraction.ZERO },
                List(n) { n + it },
                List(2 * n) { if (it < n) ContinuationStatus.LOWER else ContinuationStatus.BASIC },
            )

            val result = ExactContinuation(input).resume(limits)

            assertEquals(List(2 * n) { if (it < n) target else BigFraction.ZERO },
                assertNotNull(result.values, result.metrics.toString()))
            assertNull(result.ray)
            assertNull(result.metrics.decline)
            assertTrue(result.metrics.allocation <= limits.maxAllocation)
        }
    }

    @Test
    fun `sparse wide equality systems should finish within their containing work allowance`() {
        val rows = 16
        val columns = 64
        val total = columns + rows
        val huge = BigFraction.of(BIG_ONE shl 130, BIG_ONE)
        val limits = ExactContinuationLimits(maxWork = 300_000)
        for (coupled in listOf(false, true)) {
            val input = ExactContinuationInput(
                List(columns) { column ->
                    buildList {
                        if (column < rows) {
                            add(column to BigFraction.ONE)
                            if (coupled && column + 1 < rows) add(column + 1 to BigFraction.MINUS_ONE)
                        }
                    }
                },
                List(rows) { if (!coupled || it == 0) huge else BigFraction.ZERO },
                List(total) { if (it < rows || it >= columns) BigFraction.ZERO else BigFraction.ONE },
                List(total) {
                    when {
                        it < rows -> null
                        it >= columns -> BigFraction.ZERO
                        else -> BigFraction.ONE
                    }
                },
                List(rows) { columns + it },
                List(total) {
                    when {
                        it < rows -> ContinuationStatus.LOWER
                        it < columns -> ContinuationStatus.FIXED
                        else -> ContinuationStatus.BASIC
                    }
                },
            )

            val result = ExactContinuation(input).resume(limits)

            val expected = List(total) {
                when {
                    it < rows -> huge
                    it < columns -> BigFraction.ONE
                    else -> BigFraction.ZERO
                }
            }
            assertEquals(expected, assertNotNull(result.values, result.metrics.toString()))
            assertNull(result.ray)
            assertNull(result.metrics.decline)
            assertTrue(result.metrics.work <= limits.maxWork)
        }
    }

    @Test
    fun `already seated logical targets should complete within a bounded import allowance`() {
        val n = 32
        val input = ExactContinuationInput(
            List(n) { listOf(it to BigFraction.ONE) }, List(n) { BigFraction.ZERO },
            List(2 * n) { BigFraction.ZERO }, List(2 * n) { null },
            List(n) { n + it },
            List(2 * n) { if (it < n) ContinuationStatus.LOWER else ContinuationStatus.BASIC },
        )
        val limits = ExactContinuationLimits(maxWork = 40_000)

        val result = ExactContinuation(input).resume(limits)

        assertEquals(List(2 * n) { BigFraction.ZERO }, assertNotNull(result.values))
        assertNull(result.ray)
        assertNull(result.metrics.decline)
        assertEquals(0, result.metrics.imports)
        assertEquals(0, result.metrics.pivots)
        assertTrue(result.metrics.work <= limits.maxWork)
    }

    @Test
    fun `a refuted row after an import pivot should retain its exact conflict`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE, 1 to BigFraction.ONE)),
            listOf(BigFraction.ONE, BigFraction.ZERO),
            listOf(null, BigFraction.ZERO, BigFraction.ZERO),
            listOf(null, BigFraction.ZERO, BigFraction.ZERO),
            listOf(0, 2),
            listOf(ContinuationStatus.BASIC, ContinuationStatus.FIXED, ContinuationStatus.BASIC),
        )

        val result = ExactContinuation(input).resume()

        assertNull(result.values)
        val ray = assertNotNull(result.ray)
        assertEquals(1, result.metrics.imports)
        assertEquals(BigFraction.ZERO, ray[0] + ray[1])
        assertTrue(!ray[0].isZero)
    }

    @Test
    fun `an interrupted seated target pass should resume its completed position`() {
        val n = 32
        val input = ExactContinuationInput(
            List(n) { listOf(it to BigFraction.ONE) }, List(n) { BigFraction.ZERO },
            List(2 * n) { BigFraction.ZERO }, List(2 * n) { null },
            List(n) { n + it },
            List(2 * n) { if (it < n) ContinuationStatus.LOWER else ContinuationStatus.BASIC },
        )
        val completed = ExactContinuation(input).resume()
        val inputWork = completed.metrics.workByPhase.getValue(ContinuationPhase.INPUT)
        val limit = ExactContinuationLimits(maxWork = inputWork + 512)
        val session = ExactContinuation(input)

        val interrupted = session.resume(limit)
        val resumed = session.resume()

        assertEquals(ContinuationDecline.WORK, interrupted.metrics.decline)
        assertTrue(interrupted.metrics.importPosition in 1 until n)
        assertNull(interrupted.values)
        assertNull(interrupted.ray)
        assertEquals(List(2 * n) { BigFraction.ZERO }, assertNotNull(resumed.values))
        assertEquals(interrupted.metrics.work + resumed.metrics.work, session.usedWork)
    }

    @Test
    fun `strict endpoints should materialize interior coordinates in either direction`() {
        for (upper in listOf(false, true)) {
            val input = ExactContinuationInput(
                listOf(emptyList()),
                emptyList(),
                listOf(BigFraction.ZERO),
                listOf(BigFraction.ONE),
                emptyList(),
                listOf(if (upper) ContinuationStatus.UPPER else ContinuationStatus.LOWER),
                lowerStrict = listOf(!upper),
                upperStrict = listOf(upper),
            )

            val result = ExactContinuation(input).resume()

            val point = assertNotNull(result.values).single()
            assertTrue(point > BigFraction.ZERO && point < BigFraction.ONE)
            assertNull(result.headings)
            assertNull(result.statuses)
            assertNull(result.ray)
        }
    }

    @Test
    fun `strict coordinates should preserve coupled equations and unused fixed values`() {
        val two = BigFraction.ofLong(2)
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ONE),
            listOf(BigFraction.ZERO, BigFraction.ZERO, two, BigFraction.ZERO),
            listOf(BigFraction.ONE, BigFraction.ONE, two, BigFraction.ZERO),
            listOf(1),
            listOf(
                ContinuationStatus.LOWER,
                ContinuationStatus.BASIC,
                ContinuationStatus.FIXED,
                ContinuationStatus.FIXED,
            ),
            lowerStrict = listOf(true, true, false, false),
        )

        val result = ExactContinuation(input).resume()

        val point = assertNotNull(result.values)
        assertTrue(point[0] > BigFraction.ZERO && point[1] > BigFraction.ZERO)
        assertEquals(BigFraction.ONE, point[0] + point[1])
        assertEquals(two, point[2])
        assertEquals(BigFraction.ZERO, point[3])
    }

    @Test
    fun `strict fixed endpoints should decline without a source refutation`() {
        val input = ExactContinuationInput(
            listOf(emptyList()),
            emptyList(),
            listOf(BigFraction.ZERO),
            listOf(BigFraction.ZERO),
            emptyList(),
            listOf(ContinuationStatus.FIXED),
            lowerStrict = listOf(true),
        )

        val result = ExactContinuation(input).resume()

        assertEquals(ContinuationDecline.CANDIDATE, result.metrics.decline)
        assertNull(result.values)
        assertNull(result.ray)
    }

    @Test
    fun `strict materialization should not replenish an exhausted allowance`() {
        val input = ExactContinuationInput(
            listOf(emptyList()),
            emptyList(),
            listOf(BigFraction.ZERO),
            listOf(BigFraction.ONE),
            emptyList(),
            listOf(ContinuationStatus.LOWER),
            lowerStrict = listOf(true),
        )
        val completed = ExactContinuation(input).resume()
        val limits = ExactContinuationLimits(maxWork = completed.metrics.work - 1L)
        val session = ExactContinuation(input)

        val first = session.resume(limits)
        val repeated = session.resume(limits)

        assertNotNull(completed.values)
        assertEquals(ContinuationDecline.WORK, first.metrics.decline)
        assertEquals(ContinuationDecline.WORK, repeated.metrics.decline)
        assertNull(first.values)
        assertNull(repeated.values)
        assertTrue(session.usedWork <= limits.maxWork)
    }

    @Test
    fun `cancelled strict continuation should not publish an interior point`() {
        val input = ExactContinuationInput(
            listOf(emptyList()),
            emptyList(),
            listOf(BigFraction.ZERO),
            listOf(BigFraction.ONE),
            emptyList(),
            listOf(ContinuationStatus.LOWER),
            lowerStrict = listOf(true),
        )

        val result = ExactContinuation(input).resume(cancellation = Cancellation { true })

        assertEquals(ContinuationDecline.CANCELLED, result.metrics.decline)
        assertNull(result.values)
        assertNull(result.ray)
    }

    @Test
    fun `strict feasibility should retain exact equations across a precision restart`() {
        val huge = BigFraction.of(BIG_ONE shl 100, BIG_ONE)
        val input = ExactContinuationInput(
            listOf(listOf(0 to huge, 1 to BigFraction.ONE), listOf(0 to BigFraction.ONE, 1 to huge)),
            List(2) { BigFraction.ONE },
            List(4) { BigFraction.ZERO },
            listOf(null, null, BigFraction.ZERO, BigFraction.ZERO),
            listOf(0, 1),
            listOf(
                ContinuationStatus.BASIC,
                ContinuationStatus.BASIC,
                ContinuationStatus.FIXED,
                ContinuationStatus.FIXED,
            ),
            lowerStrict = listOf(true, true, false, false),
        )

        val result = ExactContinuation(input).resume()

        val point = assertNotNull(result.values)
        assertTrue(point[0] > BigFraction.ZERO && point[1] > BigFraction.ZERO)
        assertEquals(BigFraction.ONE, huge * point[0] + point[1])
        assertEquals(BigFraction.ONE, point[0] + huge * point[1])
        assertTrue(result.metrics.restarts > 0)
    }

    @Test
    fun `higher effort should resume the same feasible tableau`() {
        val n = 4
        val input = ExactContinuationInput(
            List(n) { listOf(it to BigFraction.MINUS_ONE) },
            List(n) { BigFraction.MINUS_ONE },
            List(2 * n) { BigFraction.ZERO },
            List(2 * n) { null },
            List(n) { n + it },
            List(2 * n) { if (it < n) ContinuationStatus.LOWER else ContinuationStatus.BASIC },
        )
        val session = ExactContinuation(input)

        val short = session.resume(ExactContinuationLimits(maxPivots = 2))
        val full = session.resume(ExactContinuationLimits(maxPivots = 4))

        assertEquals(ContinuationDecline.PIVOTS, short.metrics.decline)
        assertEquals(2, short.metrics.retainedPivots)
        assertEquals(2, full.metrics.pivots)
        assertEquals(4, full.metrics.retainedPivots)
        assertEquals(0, full.metrics.builds)
        assertEquals(List(n) { BigFraction.ONE }, assertNotNull(full.values).take(n))
        assertEquals(short.metrics.work + full.metrics.work, session.usedWork)
    }

    @Test
    fun `ordered import should keep free upper only and fixed coordinates`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(0 to BigFraction.ONE)),
            listOf(BigFraction.ofLong(-3)),
            listOf(null, null, BigFraction.ZERO),
            listOf(null, BigFraction.ofLong(-2), BigFraction.ZERO),
            listOf(0),
            listOf(ContinuationStatus.BASIC, ContinuationStatus.UPPER, ContinuationStatus.FIXED),
        )

        val result = ExactContinuation(input).resume()

        assertEquals(listOf(0), result.headings)
        assertEquals(listOf(BigFraction.MINUS_ONE, BigFraction.ofLong(-2), BigFraction.ZERO), result.values)
        assertEquals(1, result.metrics.imports)
        assertEquals(0, result.metrics.pivots)
    }

    @Test
    fun `dependent target columns should retain a logical complement`() {
        val input = ExactContinuationInput(
            List(2) { listOf(0 to BigFraction.ONE, 1 to BigFraction.ONE) },
            List(2) { BigFraction.ONE },
            List(4) { BigFraction.ZERO },
            List(4) { null },
            listOf(1, 0),
            listOf(
                ContinuationStatus.BASIC,
                ContinuationStatus.BASIC,
                ContinuationStatus.LOWER,
                ContinuationStatus.LOWER,
            ),
        )

        val result = ExactContinuation(input).resume()

        assertNotNull(result.values)
        assertEquals(listOf(1, 3), result.headings)
        assertEquals(1, result.metrics.repairs)
        assertEquals(1, result.metrics.imports)
        assertEquals(BigFraction.ONE, result.values[0] + result.values[1])
    }

    @Test
    fun `oversized rational import should use exact source values`() {
        val large = BigFraction.of(BIG_ONE shl 180, BIG_ONE)
        val input = ExactContinuationInput(
            listOf(listOf(0 to large)),
            listOf(BigFraction.ONE),
            listOf(null, BigFraction.ZERO),
            listOf(null, BigFraction.ZERO),
            listOf(0),
            listOf(ContinuationStatus.BASIC, ContinuationStatus.FIXED),
        )

        val result = ExactContinuation(input).resume()

        assertEquals(large.reciprocal(), assertNotNull(result.values)[0])
        assertEquals(0, result.metrics.restarts)
        assertEquals(1, result.metrics.builds)
    }

    @Test
    fun `cancelled import should keep its completed checkpoint`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE)),
            listOf(BigFraction.ONE),
            List(2) { BigFraction.ZERO },
            List(2) { null },
            listOf(0),
            listOf(ContinuationStatus.BASIC, ContinuationStatus.LOWER),
        )
        val session = ExactContinuation(input)

        val cancelled = session.resume(cancellation = Cancellation { true })
        val resumed = session.resume()

        assertEquals(ContinuationDecline.CANCELLED, cancelled.metrics.decline)
        assertNull(cancelled.values)
        assertEquals(BigFraction.ONE, assertNotNull(resumed.values)[0])
    }

    @Test
    fun `resource ceilings should decline without certifying a candidate`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE)),
            listOf(BigFraction.ONE),
            List(2) { BigFraction.ZERO },
            List(2) { null },
            listOf(0),
            listOf(ContinuationStatus.BASIC, ContinuationStatus.LOWER),
        )
        val cases = listOf(
            ExactContinuationLimits(maxRows = 0) to ContinuationDecline.DIMENSION,
            ExactContinuationLimits(maxWork = 0) to ContinuationDecline.WORK,
            ExactContinuationLimits(maxAllocation = 0) to ContinuationDecline.ALLOCATION,
            ExactContinuationLimits(maxTimeNs = 0) to ContinuationDecline.TIME,
        )
        for ((limits, expected) in cases) {
            val result = ExactContinuation(input).resume(limits)

            assertEquals(expected, result.metrics.decline)
            assertNull(result.values)
            assertNull(result.ray)
        }
    }

    @Test
    fun `fixed nonbasics should not repair an impossible row`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE)),
            listOf(BigFraction.ONE),
            List(2) { BigFraction.ZERO },
            List(2) { BigFraction.ZERO },
            listOf(1),
            listOf(ContinuationStatus.FIXED, ContinuationStatus.BASIC),
        )

        val result = ExactContinuation(input).resume()

        assertNotNull(result.ray)
        assertNull(result.values)
        assertEquals(0, result.metrics.pivots)
    }

    @Test
    fun `free nonbasics should move in either direction during feasibility`() {
        for (rhs in listOf(BigFraction.ONE, BigFraction.MINUS_ONE)) {
            val input = ExactContinuationInput(
                listOf(listOf(0 to BigFraction.ONE)),
                listOf(rhs),
                listOf(null, BigFraction.ZERO),
                listOf(null, BigFraction.ZERO),
                listOf(1),
                listOf(ContinuationStatus.FREE, ContinuationStatus.BASIC),
            )

            val result = ExactContinuation(input).resume()

            assertEquals(rhs, assertNotNull(result.values)[0])
            assertEquals(1, result.metrics.pivots)
        }
    }

    @Test
    fun `fixed width intermediate scalars should respect the configured bit limit`() {
        val h = BigFraction.ofLong(1L shl 20)
        val input = ExactContinuationInput(
            listOf(listOf(0 to h, 1 to BigFraction.ONE), listOf(0 to BigFraction.ONE, 1 to h)),
            List(2) { BigFraction.ONE },
            List(4) { BigFraction.ZERO },
            listOf(null, null, BigFraction.ZERO, BigFraction.ZERO),
            listOf(0, 1),
            listOf(
                ContinuationStatus.BASIC,
                ContinuationStatus.BASIC,
                ContinuationStatus.FIXED,
                ContinuationStatus.FIXED,
            ),
        )

        for (resumed in listOf(false, true)) {
            val session = ExactContinuation(input)
            if (resumed) assertNotNull(session.resume().values)

            val result = session.resume(ExactContinuationLimits(maxBits = 24))

            assertEquals(ContinuationDecline.BITS, result.metrics.decline)
            assertEquals(if (resumed) ContinuationPhase.INPUT else ContinuationPhase.IMPORT, result.metrics.phase)
            assertNull(result.values)
            assertNull(result.ray)
        }
    }
}
