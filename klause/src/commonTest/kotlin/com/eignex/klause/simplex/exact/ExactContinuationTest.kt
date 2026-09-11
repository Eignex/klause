package com.eignex.klause.simplex.exact

import com.eignex.klause.util.Cancellation
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExactContinuationTest {
    @Test
    fun `higher effort resumes the same feasible tableau`() {
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
    fun `ordered import keeps native free upper only and fixed coordinates`() {
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
    fun `dependent target columns retain a logical complement`() {
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
    fun `nonsingular target order is retained after seating permutations`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(1 to BigFraction.ONE)),
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

        assertEquals(listOf(1, 0), result.headings)
        assertEquals(List(2) { BigFraction.ONE }, assertNotNull(result.values).take(2))
    }

    @Test
    fun `oversized rational import uses exact source values`() {
        val large = BigFraction.of(BigInteger.ONE shl 180, BigInteger.ONE)
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
    fun `cancelled import does not lose its completed checkpoint`() {
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
    fun `import limit resumes seating without rebuilding`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(1 to BigFraction.ONE)),
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
        val session = ExactContinuation(input)

        val first = session.resume(ExactContinuationLimits(maxImportPivots = 1))
        val last = session.resume(ExactContinuationLimits(maxImportPivots = 2))

        assertEquals(ContinuationDecline.IMPORT_LIMIT, first.metrics.decline)
        assertEquals(1, first.metrics.importPosition)
        assertEquals(0, last.metrics.builds)
        assertEquals(1, last.metrics.imports)
        assertEquals(listOf(1, 0), last.headings)
    }

    @Test
    fun `overflow during a pivot discards partial import and charges both builds`() {
        val huge = BigFraction.of(BigInteger.ONE shl 100, BigInteger.ONE)
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
        )

        val result = ExactContinuation(input).resume()

        val x = assertNotNull(result.values)
        assertEquals(BigFraction.ONE, huge * x[0] + x[1])
        assertEquals(BigFraction.ONE, x[0] + huge * x[1])
        assertEquals(1, result.metrics.restarts)
        assertEquals(2, result.metrics.builds)
        assertEquals(2, result.metrics.imports)
    }

    @Test
    fun `resource ceilings decline without certifying a candidate`() {
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
    fun `fixed nonbasics cannot repair an impossible row`() {
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
    fun `free nonbasics move in either direction during feasibility`() {
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
    fun `an exhausted slice does not buy additional pivots on repeat`() {
        val input = ExactContinuationInput(
            List(3) { listOf(it to BigFraction.MINUS_ONE) },
            List(3) { BigFraction.MINUS_ONE },
            List(6) { BigFraction.ZERO },
            List(6) { null },
            listOf(3, 4, 5),
            List(6) { if (it < 3) ContinuationStatus.LOWER else ContinuationStatus.BASIC },
        )
        val session = ExactContinuation(input)
        val first = session.resume(ExactContinuationLimits(maxPivots = 1))

        val repeated = session.resume(ExactContinuationLimits(maxPivots = 1))

        assertEquals(ContinuationDecline.PIVOTS, first.metrics.decline)
        assertEquals(ContinuationDecline.PIVOTS, repeated.metrics.decline)
        assertEquals(0, repeated.metrics.pivots)
        assertEquals(1, repeated.metrics.retainedPivots)
        assertEquals(0, repeated.metrics.builds)
    }

    @Test
    fun `cancellation during import preserves the last complete pivot`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE), listOf(1 to BigFraction.ONE)),
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
        var checkpoints = 0
        ExactContinuation(input).resume(
            ExactContinuationLimits(maxImportPivots = 1),
            Cancellation {
                checkpoints++
                false
            },
        )
        val session = ExactContinuation(input)
        var calls = 0

        val stopped = session.resume(cancellation = Cancellation { ++calls >= checkpoints - 1 })
        val resumed = session.resume()

        assertEquals(ContinuationDecline.CANCELLED, stopped.metrics.decline)
        assertEquals(ContinuationPhase.IMPORT, stopped.metrics.phase)
        assertEquals(1, stopped.metrics.importPosition)
        assertEquals(0, resumed.metrics.builds)
        assertEquals(1, resumed.metrics.imports)
        assertEquals(listOf(1, 0), resumed.headings)
    }

    @Test
    fun `fixed width intermediate scalars respect the configured bit limit`() {
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
