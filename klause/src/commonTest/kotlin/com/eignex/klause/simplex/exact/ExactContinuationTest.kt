package com.eignex.klause.simplex.exact

import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactContinuationTest {
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
    fun `nonzero nonbasic endpoints should contribute to every basic row`() {
        for (status in listOf(ContinuationStatus.LOWER, ContinuationStatus.UPPER, ContinuationStatus.FIXED)) {
            val endpoint = if (status == ContinuationStatus.UPPER) BigFraction.ofLong(-2) else BigFraction.ofLong(2)
            val rhs = BigFraction.ofLong(5)
            val input = ExactContinuationInput(
                listOf(listOf(0 to BigFraction.ONE), listOf(0 to BigFraction.ONE)),
                listOf(rhs),
                listOf(if (status == ContinuationStatus.UPPER) null else endpoint, null, BigFraction.ZERO),
                listOf(if (status == ContinuationStatus.LOWER) null else endpoint, null, BigFraction.ZERO),
                listOf(1),
                listOf(status, ContinuationStatus.BASIC, ContinuationStatus.FIXED),
            )

            val result = ExactContinuation(input).resume()

            assertEquals(listOf(endpoint, rhs - endpoint, BigFraction.ZERO), result.values)
            assertNull(result.ray)
        }
    }

    @Test
    fun `zero nonbasic seats should retain a nonzero basic right hand side`() {
        val three = BigFraction.ofLong(3)
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(0 to BigFraction.ONE)),
            listOf(three),
            listOf(BigFraction.ZERO, null, BigFraction.ZERO),
            listOf(null, null, BigFraction.ZERO),
            listOf(1),
            listOf(ContinuationStatus.LOWER, ContinuationStatus.BASIC, ContinuationStatus.FIXED),
        )

        val result = ExactContinuation(input).resume()

        assertEquals(listOf(BigFraction.ZERO, three, BigFraction.ZERO), result.values)
        assertNull(result.ray)
    }

    @Test
    fun `strict epsilon seats should preserve coupled interior points in either direction`() {
        for (upper in listOf(false, true)) {
            val input = ExactContinuationInput(
                listOf(listOf(0 to BigFraction.ONE), listOf(0 to BigFraction.ONE)),
                listOf(BigFraction.ONE),
                List(3) { BigFraction.ZERO },
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ZERO),
                listOf(1),
                listOf(if (upper) ContinuationStatus.UPPER else ContinuationStatus.LOWER,
                    ContinuationStatus.BASIC, ContinuationStatus.FIXED),
                lowerStrict = listOf(!upper, false, false),
                upperStrict = listOf(upper, false, false),
            )

            val result = ExactContinuation(input).resume()

            val point = assertNotNull(result.values)
            assertTrue(point[0] > BigFraction.ZERO && point[0] < BigFraction.ONE)
            assertTrue(point[1] > BigFraction.ZERO && point[1] < BigFraction.ONE)
            assertEquals(BigFraction.ONE, point[0] + point[1])
            assertNull(result.ray)
        }
    }

    @Test
    fun `resuming after import should read the endpoints of the changed basis`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE)),
            listOf(BigFraction.ofLong(5)),
            listOf(BigFraction.ofLong(3), BigFraction.ofLong(2)),
            listOf(BigFraction.ofLong(4), BigFraction.ofLong(3)),
            listOf(0),
            listOf(ContinuationStatus.BASIC, ContinuationStatus.UPPER),
        )
        val session = ExactContinuation(input)
        val paused = session.resume(ExactContinuationLimits(maxPivots = 0))
        assertEquals(ContinuationDecline.PIVOTS, paused.metrics.decline)
        assertNull(paused.values)

        val resumed = session.resume(ExactContinuationLimits(maxPivots = 1))

        assertEquals(listOf(BigFraction.ofLong(3), BigFraction.ofLong(2)), resumed.values)
        assertEquals(paused.metrics.work + resumed.metrics.work, session.usedWork)
        assertNull(resumed.ray)
    }

    @Test
    fun `wide nonbasic endpoints should retain exact cancellation in basic values`() {
        val huge = BigFraction.of(BIG_ONE shl 180, BIG_ONE)
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(0 to BigFraction.ONE)),
            listOf(huge + BigFraction.ONE),
            listOf(huge, null, BigFraction.ZERO),
            listOf(huge, null, BigFraction.ZERO),
            listOf(1),
            listOf(ContinuationStatus.FIXED, ContinuationStatus.BASIC, ContinuationStatus.FIXED),
        )

        val result = ExactContinuation(input).resume()

        assertEquals(listOf(huge, BigFraction.ONE, BigFraction.ZERO), result.values)
        assertNull(result.ray)
    }

    @Test
    fun `an exhausted allocation allowance should not be renewed by resuming evaluation`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(0 to BigFraction.ONE)),
            listOf(BigFraction.ONE),
            List(3) { BigFraction.ZERO },
            listOf(null, null, BigFraction.ZERO),
            listOf(1),
            listOf(ContinuationStatus.LOWER, ContinuationStatus.BASIC, ContinuationStatus.FIXED),
        )
        val completed = ExactContinuation(input).resume()
        assertNotNull(completed.values)
        val limits = ExactContinuationLimits(maxAllocation = completed.metrics.allocation - 1L)
        val session = ExactContinuation(input)

        val first = session.resume(limits)
        val repeated = session.resume(limits)

        assertEquals(ContinuationDecline.ALLOCATION, first.metrics.decline)
        assertEquals(ContinuationDecline.ALLOCATION, repeated.metrics.decline)
        assertNull(first.values)
        assertNull(repeated.values)
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
