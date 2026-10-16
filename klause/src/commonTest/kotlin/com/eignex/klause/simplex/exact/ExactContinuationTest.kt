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
    fun `implicit import should retain full points within a single conflict scan allowance`() {
        val input = ExactContinuationInput(
            List(10) { if (it == 0) listOf(0 to BigFraction.ONE) else emptyList() },
            List(4) { BigFraction.ZERO },
            List(14) { if (it == 0) null else if (it < 10) BigFraction.ONE else BigFraction.ZERO },
            List(14) { if (it == 0) null else if (it < 10) BigFraction.ONE else BigFraction.ZERO },
            List(4) { 13 - it },
            List(14) {
                when {
                    it == 0 -> ContinuationStatus.FREE
                    it < 10 -> ContinuationStatus.FIXED
                    else -> ContinuationStatus.BASIC
                }
            },
            allowImplicitEmptyColumns = true,
        )
        val limits = ExactContinuationLimits(maxCells = 40, maxAllocation = 18000L)

        val implicit = ExactContinuation(input).resume(limits)
        val dense = ExactContinuation(input).resume(limits.copy(maxCells = 76))

        assertEquals(List(14) { if (it in 1..9) BigFraction.ONE else BigFraction.ZERO }, implicit.values)
        assertEquals(listOf(13, 12, 11, 10), implicit.headings)
        assertNull(implicit.metrics.decline)
        assertTrue(implicit.metrics.allocation <= limits.maxAllocation)
        assertEquals(ContinuationDecline.ALLOCATION, dense.metrics.decline)
        assertNull(dense.values)
    }

    @Test
    fun `an imported pivot should invalidate a prior negative strict conflict check`() {
        val two = BigFraction.ofLong(2)
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE, 1 to BigFraction.ONE), listOf(1 to BigFraction.ONE), emptyList()),
            List(2) { BigFraction.ZERO },
            listOf(null, BigFraction.ZERO, two, BigFraction.ZERO, BigFraction.ZERO),
            listOf(null, BigFraction.ONE, two, BigFraction.ZERO, BigFraction.ZERO),
            listOf(0, 1),
            listOf(
                ContinuationStatus.BASIC, ContinuationStatus.BASIC, ContinuationStatus.FIXED,
                ContinuationStatus.FIXED, ContinuationStatus.FIXED,
            ),
            lowerStrict = listOf(false, true, false, false, false),
            allowImplicitEmptyColumns = true,
        )

        val result = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = 14, maxImportPivots = 1))

        assertEquals(listOf(BigFraction.ONE, BigFraction.MINUS_ONE), result.ray)
        assertNull(result.values)
        assertNull(result.metrics.decline)
        assertEquals(1, result.metrics.imports)
        assertEquals(0, result.metrics.repairs)
    }

    @Test
    fun `reordered implicit rows should resume with original global headings`() {
        val two = BigFraction.ofLong(2)
        val three = BigFraction.ofLong(3)
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList(), listOf(1 to BigFraction.ONE)),
            listOf(BigFraction.ONE, two),
            listOf(two, BigFraction.ZERO, three, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO),
            listOf(two, null, three, null, BigFraction.ZERO, BigFraction.ZERO),
            listOf(5, 4),
            listOf(
                ContinuationStatus.FIXED, ContinuationStatus.LOWER, ContinuationStatus.FIXED,
                ContinuationStatus.LOWER, ContinuationStatus.BASIC, ContinuationStatus.BASIC,
            ),
            allowImplicitEmptyColumns = true,
        )
        val session = ExactContinuation(input)
        val limits = ExactContinuationLimits(maxCells = 14, maxPivots = 0)
        val stopped = session.resume(limits)

        val resumed = session.resume(limits.copy(maxPivots = 2))

        assertEquals(ContinuationDecline.PIVOTS, stopped.metrics.decline)
        assertEquals(2, stopped.metrics.importPosition)
        assertEquals(0, resumed.metrics.builds)
        assertEquals(listOf(3, 1), resumed.headings)
        assertEquals(listOf(two, BigFraction.ONE, three, two, BigFraction.ZERO, BigFraction.ZERO), resumed.values)
        assertEquals(stopped.metrics.work + resumed.metrics.work, session.usedWork)
        assertEquals(stopped.metrics.allocation + resumed.metrics.allocation, session.usedAllocation)
    }

    @Test
    fun `interrupted import scans should not publish negative checkpoints`() {
        val two = BigFraction.ofLong(2)
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ONE, 1 to BigFraction.ONE), listOf(1 to BigFraction.ONE), emptyList()),
            List(2) { BigFraction.ZERO },
            listOf(null, BigFraction.ZERO, two, BigFraction.ZERO, BigFraction.ZERO),
            listOf(null, BigFraction.ONE, two, BigFraction.ZERO, BigFraction.ZERO),
            listOf(0, 1),
            listOf(
                ContinuationStatus.BASIC, ContinuationStatus.BASIC, ContinuationStatus.FIXED,
                ContinuationStatus.FIXED, ContinuationStatus.FIXED,
            ),
            lowerStrict = listOf(false, true, false, false, false),
            allowImplicitEmptyColumns = true,
        )
        val limits = ExactContinuationLimits(maxCells = 14, maxImportPivots = 0)
        var probePolls = 0
        val probe = ExactContinuation(input).resume(limits, Cancellation { probePolls++; false })
        assertEquals(ContinuationDecline.IMPORT_LIMIT, probe.metrics.decline)
        for (beforeFinalPoll in listOf(0, 15)) {
            val session = ExactContinuation(input)
            var polls = 0
            val stopped = session.resume(limits, Cancellation { ++polls == probePolls - 2 - beforeFinalPoll })
            val spent = session.usedAllocation

            val resumed = session.resume(limits.copy(maxAllocation = spent + 1100L))

            assertEquals(ContinuationDecline.CANCELLED, stopped.metrics.decline)
            assertEquals(ContinuationPhase.IMPORT, stopped.metrics.phase)
            if (beforeFinalPoll == 0) {
                assertEquals(probe.metrics.allocation, stopped.metrics.allocation)
            } else {
                assertTrue(stopped.metrics.allocation < probe.metrics.allocation)
            }
            assertEquals(ContinuationDecline.ALLOCATION, resumed.metrics.decline)
            assertNull(resumed.values)
            assertNull(resumed.ray)
            assertTrue(session.usedAllocation >= spent && session.usedAllocation <= spent + 1100L)
        }
    }

    @Test
    fun `retained negative import checks should honor tighter resume limits`() {
        val huge = BigFraction.of(BIG_ONE shl 40, BIG_ONE)
        val two = BigFraction.ofLong(2)
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to huge), emptyList()),
            listOf(BigFraction.ONE),
            listOf(two, BigFraction.ZERO, null, BigFraction.ZERO),
            listOf(two, null, null, BigFraction.ZERO),
            listOf(1),
            listOf(
                ContinuationStatus.FIXED, ContinuationStatus.BASIC, ContinuationStatus.FREE, ContinuationStatus.FIXED,
            ),
            allowImplicitEmptyColumns = true,
        )
        val limits = ExactContinuationLimits(maxCells = 4, maxImportPivots = 0)
        val reasons = listOf(
            ContinuationDecline.DIMENSION, ContinuationDecline.BITS, ContinuationDecline.WORK,
            ContinuationDecline.ALLOCATION, ContinuationDecline.TIME, ContinuationDecline.CANCELLED,
        )
        for (reason in reasons) {
            val session = ExactContinuation(input)
            val imported = session.resume(limits)
            val tighter = when (reason) {
                ContinuationDecline.DIMENSION -> limits.copy(maxCells = 3)
                ContinuationDecline.BITS -> limits.copy(maxBits = 24)
                ContinuationDecline.WORK -> limits.copy(maxWork = session.usedWork)
                ContinuationDecline.ALLOCATION -> limits.copy(maxAllocation = session.usedAllocation)
                ContinuationDecline.TIME -> limits.copy(maxTimeNs = 0L)
                else -> limits
            }
            val token = if (reason == ContinuationDecline.CANCELLED) Cancellation { true } else Cancellation.Never

            val stopped = session.resume(tighter, token)

            assertEquals(ContinuationDecline.IMPORT_LIMIT, imported.metrics.decline)
            assertEquals(reason, stopped.metrics.decline)
            assertNull(stopped.values)
            assertNull(stopped.ray)
        }
    }

    @Test
    fun `an interrupted import pivot should retain its preceding numerical checkpoint`() {
        val huge = BigFraction.of(BIG_ONE shl 40, BIG_ONE)
        val two = BigFraction.ofLong(2)
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to huge), emptyList()),
            listOf(BigFraction.ONE),
            listOf(two, BigFraction.ZERO, null, BigFraction.ZERO),
            listOf(two, null, null, BigFraction.ZERO),
            listOf(1),
            listOf(
                ContinuationStatus.FIXED, ContinuationStatus.BASIC, ContinuationStatus.FREE, ContinuationStatus.FIXED,
            ),
            allowImplicitEmptyColumns = true,
        )
        val limits = ExactContinuationLimits(maxCells = 4, maxImportPivots = 1)
        var probePolls = 0
        val probe = ExactContinuation(input).resume(
            limits.copy(maxImportPivots = 0), Cancellation { probePolls++; false },
        )
        val session = ExactContinuation(input)
        var polls = 0
        val interrupted = session.resume(limits, Cancellation { ++polls == probePolls + 6 })

        val resumed = session.resume(limits)

        assertEquals(ContinuationDecline.IMPORT_LIMIT, probe.metrics.decline)
        assertEquals(ContinuationDecline.CANCELLED, interrupted.metrics.decline)
        assertEquals(0, interrupted.metrics.imports)
        assertEquals(0, interrupted.metrics.importPosition)
        assertEquals(0, resumed.metrics.builds)
        assertEquals(1, resumed.metrics.imports)
        assertEquals(listOf(1), resumed.headings)
        assertEquals(listOf(two, huge.reciprocal(), BigFraction.ZERO, BigFraction.ZERO), resumed.values)
        assertEquals(interrupted.metrics.work + resumed.metrics.work, session.usedWork)
        assertEquals(interrupted.metrics.allocation + resumed.metrics.allocation, session.usedAllocation)
    }

    @Test
    fun `implicit empty columns should retain finite seats and global pivot order`() {
        val two = BigFraction.ofLong(2)
        for (implicit in listOf(false, true)) {
            val input = ExactContinuationInput(
                listOf(
                    emptyList(), listOf(0 to BigFraction.ONE), emptyList(), listOf(0 to BigFraction.ONE), emptyList(),
                ),
                listOf(BigFraction.ONE),
                listOf(two, BigFraction.ZERO, null, BigFraction.ZERO, null, BigFraction.ZERO),
                listOf(two, BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.ONE, null, BigFraction.ZERO),
                listOf(5),
                listOf(
                    ContinuationStatus.FIXED, ContinuationStatus.LOWER, ContinuationStatus.UPPER,
                    ContinuationStatus.LOWER, ContinuationStatus.FREE, ContinuationStatus.BASIC,
                ),
                allowImplicitEmptyColumns = implicit,
            )

            val result = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = if (implicit) 5 else 8))

            assertEquals(
                listOf(
                    two, BigFraction.ONE, BigFraction.MINUS_ONE, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO,
                ),
                result.values,
            )
            assertEquals(listOf(1), result.headings)
            assertNull(result.ray)
        }
    }

    @Test
    fun `implicit empty strict coordinates should share the full interior step`() {
        val two = BigFraction.ofLong(2)
        val three = BigFraction.ofLong(3)
        for (upper in listOf(false, true)) {
            val input = ExactContinuationInput(
                listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
                listOf(BigFraction.ONE),
                listOf(two, BigFraction.ZERO, two, BigFraction.ZERO),
                listOf(three, BigFraction.ONE, two, BigFraction.ZERO),
                listOf(3),
                listOf(
                    if (upper) ContinuationStatus.UPPER else ContinuationStatus.LOWER,
                    ContinuationStatus.LOWER, ContinuationStatus.FIXED, ContinuationStatus.BASIC,
                ),
                lowerStrict = listOf(!upper, false, false, false),
                upperStrict = listOf(upper, false, false, false),
                allowImplicitEmptyColumns = true,
            )

            val result = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = 4))

            val point = assertNotNull(result.values)
            assertTrue(point[0] > two && point[0] < three)
            assertEquals(listOf(BigFraction.ONE, two, BigFraction.ZERO), point.drop(1))
            assertNull(result.headings)
        }
    }

    @Test
    fun `implicit empty basic targets should retain logical repair`() {
        val two = BigFraction.ofLong(2)
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ZERO),
            listOf(two, null, null, BigFraction.ZERO),
            listOf(two, null, null, BigFraction.ZERO),
            listOf(0),
            listOf(
                ContinuationStatus.BASIC, ContinuationStatus.FREE, ContinuationStatus.FREE, ContinuationStatus.FIXED,
            ),
            allowImplicitEmptyColumns = true,
        )

        val result = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = 4))

        assertEquals(listOf(3), result.headings)
        assertEquals(two, assertNotNull(result.values)[0])
        assertEquals(1, result.metrics.repairs)
    }

    @Test
    fun `implicit columns should retain the original signed conflict row`() {
        for (implicit in listOf(false, true)) {
            val input = ExactContinuationInput(
                listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
                listOf(BigFraction.ofLong(2)),
                listOf(null, BigFraction.ZERO, null, BigFraction.ZERO),
                listOf(null, BigFraction.ONE, null, BigFraction.ZERO),
                listOf(3),
                listOf(
                    ContinuationStatus.FREE, ContinuationStatus.LOWER,
                    ContinuationStatus.FREE, ContinuationStatus.BASIC,
                ),
                allowImplicitEmptyColumns = implicit,
            )

            val result = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = if (implicit) 4 else 6))

            assertNull(result.values)
            assertEquals(listOf(BigFraction.ONE), result.ray)
        }
    }

    @Test
    fun `nonempty zero coefficients should count as stored structural columns`() {
        val input = ExactContinuationInput(
            listOf(listOf(0 to BigFraction.ZERO), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ZERO),
            List(4) { null },
            List(4) { null },
            listOf(3),
            List(4) { if (it == 3) ContinuationStatus.BASIC else ContinuationStatus.FREE },
            allowImplicitEmptyColumns = true,
        )

        val tooSmall = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = 4))
        val fitting = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = 5))

        assertEquals(ContinuationDecline.DIMENSION, tooSmall.metrics.decline)
        assertEquals(0L, tooSmall.metrics.allocation)
        assertNotNull(fitting.values)
    }

    @Test
    fun `implicit layouts should retain original row and coordinate ceilings`() {
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ZERO),
            List(4) { null },
            List(4) { null },
            listOf(3),
            List(4) { if (it == 3) ContinuationStatus.BASIC else ContinuationStatus.FREE },
            allowImplicitEmptyColumns = true,
        )
        val limits = listOf(
            ExactContinuationLimits(maxRows = 0),
            ExactContinuationLimits(maxColumns = 3),
            ExactContinuationLimits(maxCells = 3),
        )
        for (limit in limits) {
            val result = ExactContinuation(input).resume(limit)

            assertEquals(ContinuationDecline.DIMENSION, result.metrics.decline)
            assertEquals(0L, result.metrics.allocation)
            assertEquals(0, result.metrics.builds)
        }
    }

    @Test
    fun `implicit layouts should validate original basis identities`() {
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ZERO),
            List(4) { null },
            List(4) { null },
            listOf(4),
            List(4) { ContinuationStatus.FREE },
            allowImplicitEmptyColumns = true,
        )

        val result = ExactContinuation(input).resume(ExactContinuationLimits(maxCells = 4))

        assertEquals(ContinuationDecline.INVALID_BASIS, result.metrics.decline)
        assertEquals(0, result.metrics.builds)
        assertNull(result.values)
        assertNull(result.ray)
    }

    @Test
    fun `a retained dense layout should decline a smaller cell ceiling`() {
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ONE),
            listOf(null, BigFraction.ZERO, null, BigFraction.ZERO),
            listOf(null, null, null, BigFraction.ZERO),
            listOf(3),
            listOf(
                ContinuationStatus.FREE, ContinuationStatus.LOWER, ContinuationStatus.FREE, ContinuationStatus.BASIC,
            ),
            allowImplicitEmptyColumns = true,
        )
        val session = ExactContinuation(input)
        val dense = session.resume(ExactContinuationLimits(maxCells = 6, maxPivots = 0))

        val smaller = session.resume(ExactContinuationLimits(maxCells = 4))

        assertEquals(ContinuationDecline.PIVOTS, dense.metrics.decline)
        assertEquals(ContinuationDecline.DIMENSION, smaller.metrics.decline)
        assertEquals(0, smaller.metrics.builds)
        assertNull(smaller.values)
    }

    @Test
    fun `implicit feasibility should resume the same globally indexed tableau`() {
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ONE),
            listOf(null, BigFraction.ZERO, null, BigFraction.ZERO),
            listOf(null, null, null, BigFraction.ZERO),
            listOf(3),
            listOf(
                ContinuationStatus.FREE, ContinuationStatus.LOWER, ContinuationStatus.FREE, ContinuationStatus.BASIC,
            ),
            allowImplicitEmptyColumns = true,
        )
        val session = ExactContinuation(input)
        val limits = ExactContinuationLimits(maxCells = 4, maxPivots = 0)
        val stopped = session.resume(limits)

        val resumed = session.resume(limits.copy(maxPivots = 1))

        assertEquals(ContinuationDecline.PIVOTS, stopped.metrics.decline)
        assertEquals(1, stopped.metrics.builds)
        assertEquals(0, resumed.metrics.builds)
        assertEquals(1, resumed.metrics.retainedPivots)
        assertEquals(listOf(1), resumed.headings)
        assertEquals(listOf(BigFraction.ZERO, BigFraction.ONE, BigFraction.ZERO, BigFraction.ZERO), resumed.values)
        assertEquals(stopped.metrics.work + resumed.metrics.work, session.usedWork)
        assertEquals(stopped.metrics.allocation + resumed.metrics.allocation, session.usedAllocation)
    }

    @Test
    fun `implicit layout preparation should retain failed allocation spending`() {
        val input = ExactContinuationInput(
            listOf(emptyList(), listOf(0 to BigFraction.ONE), emptyList()),
            listOf(BigFraction.ZERO),
            List(4) { null },
            List(4) { null },
            listOf(3),
            List(4) { if (it == 3) ContinuationStatus.BASIC else ContinuationStatus.FREE },
            allowImplicitEmptyColumns = true,
        )
        val session = ExactContinuation(input)
        val limits = ExactContinuationLimits(maxCells = 4, maxAllocation = 160L)
        val first = session.resume(limits)
        val spent = session.usedAllocation

        val repeated = session.resume(limits)

        assertEquals(ContinuationDecline.ALLOCATION, first.metrics.decline)
        assertEquals(ContinuationDecline.ALLOCATION, repeated.metrics.decline)
        assertEquals(0, first.metrics.builds)
        assertTrue(spent > 0L && session.usedAllocation >= spent && session.usedAllocation <= limits.maxAllocation)
        assertNull(repeated.values)
    }

    @Test
    fun `implicit strict equations should survive a precision restart`() {
        val huge = BigFraction.of(BIG_ONE shl 100, BIG_ONE)
        val two = BigFraction.ofLong(2)
        val input = ExactContinuationInput(
            listOf(
                emptyList(), listOf(0 to huge, 1 to BigFraction.ONE),
                listOf(0 to BigFraction.ONE, 1 to huge), emptyList(),
            ),
            List(2) { BigFraction.ONE },
            listOf(null, BigFraction.ZERO, BigFraction.ZERO, two, BigFraction.ZERO, BigFraction.ZERO),
            listOf(null, null, null, two, BigFraction.ZERO, BigFraction.ZERO),
            listOf(1, 2),
            listOf(
                ContinuationStatus.FREE, ContinuationStatus.BASIC, ContinuationStatus.BASIC,
                ContinuationStatus.FIXED, ContinuationStatus.FIXED, ContinuationStatus.FIXED,
            ),
            lowerStrict = listOf(false, true, true, false, false, false),
            allowImplicitEmptyColumns = true,
        )
        val session = ExactContinuation(input)
        val limits = ExactContinuationLimits(maxCells = 14)

        val result = session.resume(limits)
        val narrower = session.resume(limits.copy(maxBits = 24))

        val point = assertNotNull(result.values)
        assertEquals(BigFraction.ONE, huge * point[1] + point[2])
        assertEquals(BigFraction.ONE, point[1] + huge * point[2])
        assertEquals(two, point[3])
        assertTrue(result.metrics.restarts > 0)
        assertEquals(ContinuationDecline.BITS, narrower.metrics.decline)
        assertNull(narrower.values)
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
