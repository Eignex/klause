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
    fun `precision selection should preserve cumulative imports under the same ceiling`() {
        val huge = BigFraction.of(BIG_ONE shl 100, BIG_ONE)
        val input = ExactContinuationInput(
            listOf(
                listOf(0 to BigFraction.ONE),
                listOf(1 to huge, 2 to BigFraction.ONE),
                listOf(1 to BigFraction.ONE, 2 to huge),
            ),
            List(3) { BigFraction.ONE },
            List(6) { BigFraction.ZERO },
            List(3) { null } + List(3) { BigFraction.ZERO },
            listOf(0, 1, 2),
            List(3) { ContinuationStatus.BASIC } + List(3) { ContinuationStatus.FIXED },
        )
        val limits = ExactContinuationLimits(maxImportPivots = 3, preserveOverflowCheckpoint = false)
        val fixed = ExactContinuation(input)
        val early = ExactContinuation(input)

        val replayed = fixed.resume(limits)
        val direct = early.resume(limits.copy(startArbitraryPrecision = true))
        val promoted = ExactContinuation(input).resume(limits.copy(preserveOverflowCheckpoint = true))
        val paused = ExactContinuation(input)
        val partial = paused.resume(limits.copy(maxImportPivots = 1))
        val continued = paused.resume(limits.copy(preserveOverflowCheckpoint = true))
        val repeated = fixed.resume(limits.copy(startArbitraryPrecision = true))

        assertEquals(ContinuationDecline.IMPORT_LIMIT, replayed.metrics.decline)
        assertEquals(1, replayed.metrics.restarts)
        assertEquals(3, replayed.metrics.imports)
        assertNull(replayed.values)
        val point = assertNotNull(direct.values)
        assertEquals(BigFraction.ONE, point[0])
        assertEquals(BigFraction.ONE, huge * point[1] + point[2])
        assertEquals(BigFraction.ONE, point[1] + huge * point[2])
        assertEquals(3, direct.metrics.imports)
        assertEquals(0, direct.metrics.restarts)
        assertEquals(point, assertNotNull(promoted.values))
        assertEquals(3, promoted.metrics.imports)
        assertEquals(1, promoted.metrics.restarts)
        assertTrue(requireNotNull(promoted.metrics.workByPhase[ContinuationPhase.PROMOTION]) > 0L)
        assertEquals(point, assertNotNull(continued.values))
        assertEquals(1, partial.metrics.imports)
        assertEquals(2, continued.metrics.imports)
        assertEquals(3, paused.usedImportPivots)
        assertEquals(partial.metrics.work + continued.metrics.work, paused.usedWork)
        assertEquals(partial.metrics.allocation + continued.metrics.allocation, paused.usedAllocation)
        assertEquals(ContinuationDecline.IMPORT_LIMIT, repeated.metrics.decline)
        assertEquals(0, repeated.metrics.imports)
        assertEquals(3, fixed.usedImportPivots)
        assertEquals(replayed.metrics.work + repeated.metrics.work, fixed.usedWork)
        assertEquals(replayed.metrics.allocation + repeated.metrics.allocation, fixed.usedAllocation)
    }

    @Test
    fun `a rebound wide endpoint should restart from current exact bounds`() {
        val initial = ExactContinuationInput(
            listOf(emptyList()), emptyList(), listOf(BigFraction.ZERO), listOf(null),
            emptyList(), listOf(ContinuationStatus.LOWER),
        )
        val session = ExactContinuation(initial)
        assertNotNull(session.resume().values)
        val huge = BigFraction.of(BIG_ONE shl 140, BIG_ONE)
        val changed = ExactContinuationInput(
            initial.columns, initial.rhs, listOf(huge), initial.upper,
            initial.headings, initial.statuses,
        )

        val result = session.resume(replacement = changed)

        assertEquals(listOf(huge), assertNotNull(result.values))
        assertNull(result.ray)
    }

    @Test
    fun `cancelled overflow promotion should keep a complete checkpoint and cumulative spending`() {
        val initial = ExactContinuationInput(
            listOf(emptyList()), emptyList(), listOf(BigFraction.ZERO), listOf(null),
            emptyList(), listOf(ContinuationStatus.LOWER),
        )
        val huge = BigFraction.of(BIG_ONE shl 140, BIG_ONE)
        val changed = ExactContinuationInput(
            initial.columns, initial.rhs, listOf(huge), initial.upper,
            initial.headings, initial.statuses,
        )
        val probe = ExactContinuation(initial)
        assertNotNull(probe.resume().values)
        var checks = 0
        assertNotNull(probe.resume(replacement = changed, cancellation = Cancellation {
            checks++
            false
        }).values)
        var promotionInterruptions = 0
        var previousCheckpoints = 0

        for (cancelAt in 1..checks) {
            val session = ExactContinuation(initial)
            assertNotNull(session.resume().values)
            val priorWork = session.usedWork
            val priorAllocation = session.usedAllocation
            var observed = 0

            val cancelled = session.resume(
                replacement = changed,
                cancellation = Cancellation { ++observed == cancelAt },
            )
            if (cancelled.metrics.phase != ContinuationPhase.PROMOTION) continue
            val retained = session.resume()

            promotionInterruptions++
            assertEquals(ContinuationDecline.CANCELLED, cancelled.metrics.decline)
            assertNull(cancelled.values)
            val point = assertNotNull(retained.values)
            if (point == listOf(BigFraction.ZERO)) previousCheckpoints++ else assertEquals(listOf(huge), point)
            assertEquals(0, retained.metrics.builds)
            assertTrue(cancelled.metrics.work > 0L)
            assertTrue(cancelled.metrics.allocation > 0L)
            assertEquals(priorWork + cancelled.metrics.work + retained.metrics.work, session.usedWork)
            assertEquals(
                priorAllocation + cancelled.metrics.allocation + retained.metrics.allocation,
                session.usedAllocation,
            )
        }
        assertTrue(promotionInterruptions > 0)
        assertTrue(previousCheckpoints > 0)
    }

    @Test
    fun `rebound open sides should reseat nonbasic coordinates`() {
        val initial = ExactContinuationInput(
            listOf(emptyList()), emptyList(), listOf(BigFraction.ZERO), listOf(null),
            emptyList(), listOf(ContinuationStatus.LOWER),
        )
        val session = ExactContinuation(initial)
        assertNotNull(session.resume().values)
        val upperOnly = ExactContinuationInput(
            listOf(emptyList()), emptyList(), listOf(null), listOf(BigFraction.MINUS_ONE),
            emptyList(), listOf(ContinuationStatus.UPPER), upperStrict = listOf(true),
        )

        val result = session.resume(replacement = upperOnly)

        assertTrue(assertNotNull(result.values).single() < BigFraction.MINUS_ONE)
        assertNull(result.ray)
    }

    @Test
    fun `a changed exact matrix should decline retained feasibility`() {
        val initial = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE)), listOf(BigFraction.ONE),
            listOf(null, BigFraction.ZERO), listOf(null, BigFraction.ZERO),
            listOf(0), listOf(ContinuationStatus.BASIC, ContinuationStatus.FIXED),
        )
        val session = ExactContinuation(initial)
        assertNotNull(session.resume().values)
        val changed = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ofLong(2))), initial.rhs, initial.lower, initial.upper,
            initial.headings, initial.statuses,
        )

        val result = session.resume(replacement = changed)

        assertEquals(ContinuationDecline.INVALID_INPUT, result.metrics.decline)
        assertNull(result.values)
        assertNull(result.ray)
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
