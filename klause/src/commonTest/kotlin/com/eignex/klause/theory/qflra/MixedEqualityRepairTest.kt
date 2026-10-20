package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.exactColumnLower
import com.eignex.klause.lp.exactColumnUpper
import com.eignex.klause.lp.satisfiesSourceRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixedEqualityRepairTest {
    @Test
    fun `selected equality rows should refute within the active column limit of a sparse namespace`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(400, 424),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(400, 424),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnUpper(400, BigFraction.ZERO), exactColumnLower(424, BigFraction.ONE),
        )
        val stages = ArrayList<String>()
        var support: IntArray? = null

        val refuted = equalityRowsRefute(425, rows, Cancellation.Never,
            onStage = { stages += it }, onSupport = { support = it })

        assertTrue(refuted)
        assertEquals(rows.indices.toSet(), assertNotNull(support).toSet())
        assertTrue("PURE_REAL_EQUALITY_ACTIVE_C_2_FROM_425" in stages)
        assertEquals(EqualityRows.Declined, prepareEqualityRows(425, rows, Cancellation.Never))
        assertTrue(List(425) { BigFraction.ZERO }.satisfiesSourceRows(rows.dropLast(1)))
    }

    @Test
    fun `selected equality columns above the dense admission should withhold a refutation`() {
        val rows = List(257) { exactColumnUpper(if (it == 256) 424 else it, BigFraction.ZERO) } +
            listOf(exactColumnLower(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ONE))
        val stages = ArrayList<String>()
        var support: IntArray? = null

        val refuted = equalityRowsRefute(425, rows, Cancellation.Never,
            onStage = { stages += it }, onSupport = { support = it })

        assertFalse(refuted)
        assertNull(support)
        assertTrue("PURE_REAL_EQUALITY_ACTIVE_C_257_FROM_425" in stages)
    }

    @Test
    fun `an invalid unused coordinate should withhold a compacted equality refutation`() {
        val rows = listOf(
            exactColumnUpper(400, BigFraction.ZERO), exactColumnLower(400, BigFraction.ZERO),
            exactColumnUpper(424, BigFraction.ZERO), exactColumnLower(424, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(425), listOf(BigFraction.ZERO), BigFraction.ZERO),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(425, rows, Cancellation.Never, onSupport = { support = it })

        assertFalse(refuted)
        assertNull(support)
    }

    @Test
    fun `cancellation after active column capture should withhold source support`() {
        val rows = listOf(
            exactColumnUpper(400, BigFraction.ZERO), exactColumnLower(400, BigFraction.ZERO),
            exactColumnUpper(424, BigFraction.ZERO), exactColumnLower(424, BigFraction.ONE),
        )
        var cancelled = false
        var support: IntArray? = null

        val refuted = equalityRowsRefute(425, rows, Cancellation { cancelled },
            onStage = { if (it.startsWith("PURE_REAL_EQUALITY_ACTIVE_C_")) cancelled = true },
            onSupport = { support = it })

        assertFalse(refuted)
        assertNull(support)
        assertTrue(equalityRowsRefute(425, rows, Cancellation.Never))
    }

    @Test
    fun `a compacted general refutation should retain only the used equation dependencies`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            exactColumnUpper(3, BigFraction.ofLong(7)), exactColumnLower(3, BigFraction.ofLong(7)),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
        ) + List(129) {
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ofLong(10))
        }
        val budget = SourceLpBudget(maxOperations = 1)
        var support: IntArray? = null

        val refuted = equalityRowsRefute(4, rows, Cancellation.Never, onSupport = { support = it },
            refutesGeneral = { columns, residual, token, cite ->
                refuteRealResidualComponents(columns, residual, budget, token, onSupport = cite)
            })

        assertTrue(refuted)
        assertEquals(setOf(0, 1, 4, 5), assertNotNull(support).toSet())
        assertEquals(1, budget.operations)
        val sibling = rows.filterIndexed { index, _ -> index != 5 }
        assertTrue(listOf(BigFraction.ZERO, BigFraction.ZERO, BigFraction.ZERO, BigFraction.ofLong(7))
            .satisfiesSourceRows(sibling))
    }

    @Test
    fun `a reduced cycle should exclude retained rows outside the cycle`() {
        for (general in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 2),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(1, 2),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.MINUS_ONE),
                ExactRationalInequality(intArrayOf(2, 3),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ofLong(10)),
            ) + if (general) listOf(ExactRationalInequality(intArrayOf(1, 2, 3),
                List(3) { BigFraction.ONE }, BigFraction.ofLong(10))) else emptyList()
            val budget = SourceLpBudget()
            var support: IntArray? = null

            val refuted = equalityRowsRefute(4, rows, Cancellation.Never, onSupport = { support = it },
                refutesGeneral = { columns, residual, token, cite ->
                    refuteRealResidualComponents(columns, residual, budget, token, onSupport = cite)
                })

            assertTrue(refuted)
            val selected = assertNotNull(support)
            assertEquals(setOf(0, 1, 2, 3), selected.toSet())
            assertTrue(equalityRowsRefute(4, selected.map(rows::get), Cancellation.Never))
        }
    }

    @Test
    fun `cancellation after a necessary residual cycle should withhold source support`() {
        val rows = listOf(
            exactColumnUpper(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(1, 2, 3), List(3) { BigFraction.ONE }, BigFraction.ZERO),
        )
        var cancelled = false
        var support: IntArray? = null

        val refuted = equalityRowsRefute(4, rows, Cancellation { cancelled },
            onStage = { if (it == "PURE_REAL_EQUALITY_SUBSET_GRAPH_REFUTED") cancelled = true },
            onSupport = { support = it }, refutesGeneral = { _, _, _, _ -> false })

        assertFalse(refuted)
        assertNull(support)
        assertTrue(equalityRowsRefute(4, rows, Cancellation.Never,
            refutesGeneral = { _, _, _, _ -> false }))
    }

    @Test
    fun `zero coefficients should not cite an unrelated equation pivot`() {
        val rows = listOf(
            exactColumnUpper(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ZERO),
            exactColumnUpper(1, BigFraction.ONE), exactColumnLower(1, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ZERO, BigFraction.ONE),
                BigFraction.ONE, strict = true),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(2, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        val indices = assertNotNull(support)
        assertEquals(setOf(2, 3, 4), indices.toSet())
        assertTrue(equalityRowsRefute(2, indices.map(rows::get), Cancellation.Never))
    }

    @Test
    fun `contradictory equations should exclude unrelated equation pivots from their support`() {
        val rows = listOf(
            exactColumnUpper(2, BigFraction.ofLong(7)), exactColumnLower(2, BigFraction.ofLong(7)),
            exactColumnUpper(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ZERO),
            exactColumnUpper(0, BigFraction.ONE), exactColumnLower(0, BigFraction.ONE),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(3, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        val indices = assertNotNull(support)
        assertEquals(setOf(2, 3, 4, 5), indices.toSet())
        assertTrue(equalityRowsRefute(3, indices.map(rows::get), Cancellation.Never))
    }

    @Test
    fun `a substituted false row should cite only the pivots it uses`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            exactColumnUpper(2, BigFraction.ZERO), exactColumnLower(2, BigFraction.ZERO),
            exactColumnUpper(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(3, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        val indices = assertNotNull(support)
        assertEquals(setOf(0, 1, 4, 5, 6), indices.toSet())
        assertTrue(equalityRowsRefute(3, indices.map(rows::get), Cancellation.Never))
    }

    @Test
    fun `a residual cycle should exclude discarded rows and unused equation pivots`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ONE),
            exactColumnUpper(3, BigFraction.ONE), exactColumnLower(3, BigFraction.ONE),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(4, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        val indices = assertNotNull(support)
        assertEquals(setOf(0, 1, 2, 3), indices.toSet())
        assertTrue(equalityRowsRefute(4, indices.map(rows::get), Cancellation.Never))
    }

    @Test
    fun `a reduced general conflict should exclude discarded row conditions`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ONE),
            exactColumnUpper(3, BigFraction.ZERO), exactColumnLower(3, BigFraction.ZERO),
        )
        val budget = SourceLpBudget()
        var support: IntArray? = null

        val refuted = equalityRowsRefute(4, rows, Cancellation.Never, onSupport = { support = it },
            refutesGeneral = { columns, residual, token, cite ->
                refuteRealResidualComponents(columns, residual, budget, token, onSupport = cite)
            })

        assertTrue(refuted)
        assertEquals(setOf(0, 1, 2, 3), assertNotNull(support).toSet())
    }

    @Test
    fun `cancellation after support expansion should withhold the completed refutation`() {
        val rows = listOf(
            exactColumnUpper(0, BigFraction.ZERO), exactColumnLower(0, BigFraction.ZERO),
            exactColumnUpper(0, BigFraction.ONE), exactColumnLower(0, BigFraction.ONE),
        )
        var cancelled = false
        var support: IntArray? = null

        val refuted = equalityRowsRefute(1, rows, Cancellation { cancelled },
            onStage = { if (it == "PURE_REAL_EQUALITY_SUPPORT_TRACKED") cancelled = true },
            onSupport = { support = it })

        assertFalse(refuted)
        assertNull(support)
        assertTrue(equalityRowsRefute(1, rows, Cancellation.Never))
    }

    @Test
    fun `a reduced general conflict should retain every original source row`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.ONE }, BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1, 2), List(3) { BigFraction.MINUS_ONE },
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE, strict = strict),
            )
            val budget = SourceLpBudget()
            var support: IntArray? = null

            val refuted = equalityRowsRefute(3, rows, Cancellation.Never, onSupport = { support = it },
                refutesGeneral = { columns, residual, token, cite ->
                    refuteRealResidualComponents(columns, residual, budget, token, onSupport = cite)
                })

            assertTrue(refuted)
            assertEquals(rows.indices.toSet(), assertNotNull(support).toSet())
        }
    }

    @Test
    fun `a reduced difference cycle should retain all original source rows`() {
        for (strict in listOf(false, true)) {
            val rows = listOf(
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 1),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
                ExactRationalInequality(intArrayOf(0, 2),
                    listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO, strict = strict),
                ExactRationalInequality(intArrayOf(1, 2),
                    listOf(BigFraction.MINUS_ONE, BigFraction.ONE),
                    if (strict) BigFraction.ZERO else BigFraction.MINUS_ONE),
            )
            var support: IntArray? = null

            val refuted = equalityRowsRefute(3, rows, Cancellation.Never, onSupport = { support = it })

            assertTrue(refuted)
            assertEquals(rows.indices.toSet(), assertNotNull(support).toSet())
        }
    }

    @Test
    fun `a feasible reduced graph should retain point repair without a refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ONE),
        )

        val refuted = equalityRowsRefute(3, rows, Cancellation.Never)
        val repair = assertNotNull(prepareMixedEqualityRepair(3, rows, Cancellation.Never))
        val point = repair.completePoint(rows, null)

        assertFalse(refuted)
        assertTrue(assertNotNull(point).satisfiesSourceRows(rows))
    }

    @Test
    fun `a valid general residual should preserve a necessary cycle refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(1, 2, 3), List(3) { BigFraction.ONE }, BigFraction.ZERO),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(4, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        val selected = assertNotNull(support)
        assertEquals(setOf(0, 1, 2, 3), selected.toSet())
        assertTrue(equalityRowsRefute(4, selected.map(rows::get), Cancellation.Never))
    }

    @Test
    fun `a cancelled residual cycle should withhold all source support`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 2),
                listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1, 2),
                listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.MINUS_ONE),
        )
        var cancelled = false
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = cancelled
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        var support: IntArray? = null

        val refuted = equalityRowsRefute(3, rows, stop,
            onStage = { if (it == "PURE_REAL_EQUALITY_GRAPH_REFUTED") cancelled = true },
            onSupport = { support = it })

        assertFalse(refuted)
        assertNull(support)
        assertTrue(work > 0L)
        assertTrue(equalityRowsRefute(3, rows, Cancellation.Never))
    }

    @Test
    fun `inconsistent rational equalities should return an exact refutation`() {
        for (wide in listOf(false, true)) {
            val scale = if (wide) BigFraction.of(BIG_ONE shl 180, BIG_ONE) else BigFraction.ONE
            val rows = listOf(2L, 3L).flatMap { coefficient ->
                val value = BigFraction.ofLong(coefficient) * scale
                listOf(
                    ExactRationalInequality(intArrayOf(0), listOf(value), BigFraction.ONE),
                    ExactRationalInequality(intArrayOf(0), listOf(value.negated()), BigFraction.MINUS_ONE),
                )
            }

            val result = prepareEqualityRows(1, rows, Cancellation.Never)

            assertEquals(EqualityRows.Refuted, result)
        }
    }

    @Test
    fun `a strict row contradicted by equalities should refute the residual`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
        )
        val prepared = assertIs<EqualityRows.Prepared>(prepareEqualityRows(2, rows, Cancellation.Never))

        val result = prepared.repair.reduceRows(rows)

        assertEquals(EqualityResidual.Refuted, result)
    }

    @Test
    fun `a closed equality boundary should remain a checked feasible point`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE),
        )
        val prepared = assertIs<EqualityRows.Prepared>(prepareEqualityRows(2, rows, Cancellation.Never))

        val point = prepared.repair.point(List(2) { BigFraction.ZERO })

        assertEquals(listOf(BigFraction.ZERO, BigFraction.ONE), point)
        assertTrue(assertNotNull(point).satisfiesSourceRows(rows))
    }

    @Test
    fun `unpaired inequalities should not claim an equality refutation`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )

        val result = prepareEqualityRows(1, rows, Cancellation.Never)

        assertEquals(EqualityRows.Declined, result)
        assertFalse(equalityRowsRefute(1, rows, Cancellation.Never))
    }

    @Test
    fun `free residual coordinates should retain their source inequalities`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1), listOf(BigFraction.MINUS_ONE, BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
        )
        val prepared = assertIs<EqualityRows.Prepared>(prepareEqualityRows(2, rows, Cancellation.Never))

        val point = prepared.repair.completePoint(rows, null)

        assertTrue(assertNotNull(point).satisfiesSourceRows(rows))
        assertEquals(point[0], point[1])
        assertTrue(point[1] >= BigFraction.ONE)
    }

    @Test
    fun `an interrupted equality proof should retain work without a refutation`() {
        val rows = listOf(0L, 1L).flatMap { bound ->
            listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(bound)),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-bound)),
            )
        }
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 8L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        var interruptedSupport: IntArray? = null
        var freshSupport: IntArray? = null
        val stopped = equalityRowsRefute(1, rows, stop, onSupport = { interruptedSupport = it })
        val fresh = equalityRowsRefute(1, rows, Cancellation.Never, onSupport = { freshSupport = it })

        assertFalse(stopped)
        assertTrue(work >= 8L)
        assertTrue(fresh)
        assertNull(interruptedSupport)
        assertEquals(setOf(0, 1, 2, 3), assertNotNull(freshSupport).toSet())
    }

    @Test
    fun `strict residual support should exclude unrelated inequalities`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.MINUS_ONE }, BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ONE, strict = true),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ofLong(4)),
        )
        var support: IntArray? = null

        val refuted = equalityRowsRefute(3, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        val indices = assertNotNull(support)
        assertEquals(setOf(0, 1, 2, 3, 4), indices.toSet())
        assertTrue(equalityRowsRefute(3, indices.map(rows::get), Cancellation.Never))
    }

    @Test
    fun `inconsistent equation support should retain both original directions`() {
        val rows = listOf(0L, 0L, 1L).flatMap { bound ->
            listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(bound)),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-bound)),
            )
        }
        var support: IntArray? = null

        val refuted = equalityRowsRefute(1, rows, Cancellation.Never, onSupport = { support = it })

        assertTrue(refuted)
        assertEquals(setOf(0, 1, 4, 5), assertNotNull(support).toSet())
    }

    @Test
    fun `invalid input after contradictory equations should withhold support`() {
        val rows = listOf(0L, 1L).flatMap { bound ->
            listOf(
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ofLong(bound)),
                ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.ofLong(-bound)),
            )
        } + ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ZERO)
        var support: IntArray? = null

        val refuted = equalityRowsRefute(1, rows, Cancellation.Never, onSupport = { support = it })

        assertFalse(refuted)
        assertNull(support)
    }

    @Test
    fun `invalid source coordinate aliases should withhold an equality proof`() {
        for (invalid in listOf(-1, 0, 2)) {
            val row = ExactRationalInequality(intArrayOf(0, 1), List(2) { BigFraction.ONE }, BigFraction.ZERO)
            row.columns[1] = invalid

            val result = prepareEqualityRows(2, listOf(row), Cancellation.Never)

            assertEquals(EqualityRows.Declined, result)
        }
    }

    @Test
    fun `equality admission limits should withhold proof authority`() {
        val huge = BigFraction.of(BIG_ONE shl 4096, BIG_ONE)
        val cases = listOf(
            257 to emptyList(),
            1 to listOf(ExactRationalInequality(intArrayOf(0), listOf(huge), BigFraction.ONE)),
        )
        for ((columns, rows) in cases) {
            val result = prepareEqualityRows(columns, rows, Cancellation.Never)

            assertEquals(EqualityRows.Declined, result)
            assertFalse(equalityRowsRefute(columns, rows, Cancellation.Never))
        }
    }

    @Test
    fun `a failed rounded integer trial should leave a feasible sibling available`() {
        val rows = listOf(
            ExactRationalInequality(intArrayOf(0, 1, 2, 3),
                listOf(BigFraction.ONE, BigFraction.ONE, BigFraction.ONE, BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0, 1, 2, 3),
                listOf(BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.MINUS_ONE, BigFraction.ONE),
                BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.ONE), BigFraction.ONE),
            ExactRationalInequality(intArrayOf(0), listOf(BigFraction.MINUS_ONE), BigFraction.MINUS_ONE),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(1), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.ONE), BigFraction.ZERO),
            ExactRationalInequality(intArrayOf(2), listOf(BigFraction.MINUS_ONE), BigFraction.ZERO),
        )

        val failed = completeMixedPoint(3, 1, doubleArrayOf(1.0, 0.0, 0.0, 0.0), rows, Cancellation.Never)
        val sibling = completeMixedPoint(3, 1, doubleArrayOf(1.0, 0.0, 0.0, 1.0), rows, Cancellation.Never)

        assertNull(failed)
        assertTrue(assertNotNull(sibling).satisfiesSourceRows(rows))
    }
}
