package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedRealCompletionTest {
    @Test
    fun `independent real components should retain exact source row agreement`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
            ExactRationalInequality(
                intArrayOf(2, 3),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
        )

        val point = assertNotNull(completeMixedRealComponents(4, rows, SourceLpBudget(), Cancellation.Never))

        assertTrue(point.satisfiesSourceRows(rows))
    }

    @Test
    fun `independent components should share one source operation allowance`() {
        val rows = listOf(
            ExactRationalInequality(
                intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
            ExactRationalInequality(
                intArrayOf(2, 3),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE),
                BigFraction.MINUS_ONE,
            ),
        )

        val point = completeMixedRealComponents(4, rows, SourceLpBudget(maxOperations = 1), Cancellation.Never)

        assertNull(point)
    }

    @Test
    fun `positive parallel rows should preserve the strongest source side in either order`() {
        for (direction in listOf(1L, -1L)) {
            val rows = listOf(
                ExactRationalInequality(
                    intArrayOf(0, 1),
                    listOf(BigFraction.ofLong(4 * direction), BigFraction.ofLong(2 * direction)),
                    BigFraction.ofLong(8 * direction),
                ),
                ExactRationalInequality(
                    intArrayOf(0, 1),
                    listOf(BigFraction.ofLong(2 * direction), BigFraction.ofLong(direction)),
                    BigFraction.ofLong(3 * direction),
                ),
            )
            for (input in listOf(rows, rows.reversed())) {
                val compacted = assertNotNull(compactMixedRealRows(2, input, Cancellation.Never))

                assertEquals(1, compacted.size)
                for ((x, y) in listOf(1L to 1L, 2L to 0L, 0L to 3L, 0L to 4L)) {
                    val point = listOf(BigFraction.ofLong(x), BigFraction.ofLong(y))
                    assertEquals(point.satisfiesSourceRows(input), point.satisfiesSourceRows(compacted))
                }
            }
        }
    }

    @Test
    fun `equal parallel bounds should preserve strictness in either order`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(2) },
                BigFraction.ofLong(2), strict = true),
        )
        for (input in listOf(rows, rows.reversed())) {
            val compacted = assertNotNull(compactMixedRealRows(2, input, Cancellation.Never))

            assertEquals(1, compacted.size)
            assertTrue(listOf(BigFraction.ZERO, BigFraction.ZERO).satisfiesSourceRows(compacted))
            assertTrue(!listOf(BigFraction.ONE, BigFraction.ZERO).satisfiesSourceRows(compacted))
        }
    }

    @Test
    fun `a tighter closed side should dominate a looser strict side`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ofLong(2) },
                BigFraction.ofLong(4), strict = true),
        )
        for (input in listOf(rows, rows.reversed())) {
            val compacted = assertNotNull(compactMixedRealRows(2, input, Cancellation.Never))

            assertEquals(1, compacted.size)
            assertTrue(listOf(BigFraction.ONE, BigFraction.ZERO).satisfiesSourceRows(compacted))
            assertTrue(!listOf(BigFraction.ofLong(2), BigFraction.ZERO).satisfiesSourceRows(compacted))
        }
    }

    @Test
    fun `opposite parallel directions should retain both source sides`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.ZERO),
        )

        val compacted = assertNotNull(compactMixedRealRows(2, rows, Cancellation.Never))

        assertEquals(2, compacted.size)
        for (x in listOf(-1L, 0L, 1L, 2L)) {
            val point = listOf(BigFraction.ofLong(x), BigFraction.ZERO)
            assertEquals(point.satisfiesSourceRows(rows), point.satisfiesSourceRows(compacted))
        }
    }

    @Test
    fun `different coefficient ratios should retain both source inequalities`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.ofLong(2)),
                BigFraction.ONE),
        )

        val compacted = assertNotNull(compactMixedRealRows(2, rows, Cancellation.Never))

        assertEquals(2, compacted.size)
        for ((x, y) in listOf(0L to 0L, 1L to 0L, 0L to 1L, 2L to 0L)) {
            val point = listOf(BigFraction.ofLong(x), BigFraction.ofLong(y))
            assertEquals(point.satisfiesSourceRows(rows), point.satisfiesSourceRows(compacted))
        }
    }

    @Test
    fun `stopped row compaction should publish no partial row list`() {
        val rows = List(130) {
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE)
        }
        for (limit in listOf(0L, 800L)) {
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = charged > limit
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }

            val compacted = compactMixedRealRows(2, rows, stop)

            assertNull(compacted)
            assertTrue(charged > limit)
        }
    }

    @Test
    fun `oversized compaction scalars should withhold the optional row list`() {
        val wide = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), listOf(wide, BigFraction.ONE), BigFraction.ONE),
        )

        val compacted = compactMixedRealRows(2, rows, Cancellation.Never)

        assertNull(compacted)
    }

    @Test
    fun `row compaction should decline source views exceeding its storage bound`() {
        val rows = List(700) {
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE)
        }

        val compacted = compactMixedRealRows(2, rows, Cancellation.Never)

        assertNull(compacted)
    }

    @Test
    fun `compacted general components should fit the existing numerical dimensions`() {
        val rows = List(130) {
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ONE)
        } + (0 until 3).map { column ->
            ExactRationalInequality(intArrayOf(column), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO)
        }
        val budget = SourceLpBudget()
        val stages = ArrayList<String>()
        assertTrue(!budget.admitsDimensions(3, rows.size.toLong()))

        val point = assertNotNull(completeMixedRealComponents(3, rows, budget, Cancellation.Never) { stages += it })

        assertTrue(point.satisfiesSourceRows(rows))
        assertTrue("RECOVERY_REAL_COMPONENT_COMPACTED_133_4" in stages)
        assertTrue("RECOVERY_REAL_COMPONENT_LP_REQUEST" in stages)
    }

    @Test
    fun `compacted components should retain an exhausted source operation allowance`() {
        val rows = List(130) {
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ONE)
        }
        val budget = SourceLpBudget(maxOperations = 0)

        val point = completeMixedRealComponents(3, rows, budget, Cancellation.Never)

        assertNull(point)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `an exact general component contradiction should refute the real residual`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val stages = ArrayList<String>()

        val result = checkMixedRealComponents(3, rows, SourceLpBudget(), Cancellation.Never) { stages += it }

        assertEquals(MixedPointCompletionResult.Refuted, result)
        assertTrue("RECOVERY_REAL_COMPONENT_LP_INFEASIBLE" in stages)
    }

    @Test
    fun `a declined numerical component should not refute its residual`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        val budget = SourceLpBudget(maxOperations = 0)

        val result = checkMixedRealComponents(3, rows, budget, Cancellation.Never)

        assertEquals(MixedPointCompletionResult.Declined, result)
        assertEquals(0, budget.operations)
    }

    @Test
    fun `stopping after numerical refutation should withhold the residual result`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        )
        var cancelled = false
        val stop = Cancellation { cancelled }

        val result = checkMixedRealComponents(3, rows, SourceLpBudget(), stop) { stage ->
            if (stage == "RECOVERY_REAL_COMPONENT_LP_INFEASIBLE") cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(MixedPointCompletionResult.Declined, result)
    }

    @Test
    fun `source snapshot storage should count against compaction admission`() {
        val rows = List(130) {
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE)
        }

        val compacted = compactMixedRealRows(2, rows, Cancellation.Never, originCells = 65536L)

        assertNull(compacted)
    }

    @Test
    fun `an exact graph contradiction should refute the real residual`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )

        val result = checkMixedRealComponents(1, rows, SourceLpBudget(), Cancellation.Never)

        assertEquals(MixedPointCompletionResult.Refuted, result)
    }

    @Test
    fun `stopped real components should decline rather than refute`() {
        val rows = listOf(ExactRationalInequality(intArrayOf(), emptyList(), BigFraction.MINUS_ONE))

        val result = checkMixedRealComponents(1, rows, SourceLpBudget(), Cancellation { true })

        assertEquals(MixedPointCompletionResult.Declined, result)
    }
}
